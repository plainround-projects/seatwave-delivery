package io.plainround.seatwave.delivery.iac;

import io.plainround.seatwave.delivery.iac.StateBackend.Recorded;
import io.plainround.seatwave.delivery.iac.StateBackend.StateFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A plan engine in miniature, in the order OpenTofu works: refresh what the state records against
 * the real cloud (drift), compare the declaration with the refreshed record (the plan), and change
 * the cloud only to close the difference (the apply).
 */
public final class PlanEngine {

  /** What the plan proposes for one address. */
  public enum Action {
    CREATE,
    UPDATE_IN_PLACE,
    REPLACE,
    DELETE
  }

  /** One resource as declared: an address such as aws_eks_node_group.zone["use1-az1"]. */
  public record Resource(String address, String type, Map<String, String> arguments) {
    public Resource {
      if (address.isBlank() || !address.startsWith(type + ".")) {
        throw new IllegalArgumentException("address must start with its type: " + address);
      }
      arguments = Map.copyOf(new TreeMap<>(arguments));
    }
  }

  /** One proposed change and the arguments that cause it. */
  public record Change(Action action, String address, List<String> differences) {
    public Change {
      differences = List.copyOf(differences);
    }
  }

  /** An argument whose real value no longer matches the record; real is "(deleted)" if gone. */
  public record Drift(String address, String argument, String recorded, String real) {}

  /**
   * The plan: drift found by the refresh, the changes, and the refreshed record they start from.
   */
  public record Plan(List<Drift> drifts, List<Change> changes, StateFile refreshed) {
    public Plan {
      drifts = List.copyOf(drifts);
      changes = List.copyOf(changes);
    }

    public long count(Action action) {
      return changes.stream().filter(change -> change.action() == action).count();
    }
  }

  /** The state written by an apply, and the creates the provider refused. */
  public record ApplyOutcome(StateFile written, List<String> refusals) {
    public ApplyOutcome {
      refusals = List.copyOf(refusals);
    }
  }

  private final Map<String, Set<String>> argumentsThatForceReplacement;

  /** The provider schema decides which argument changes cannot be made in place. */
  public PlanEngine(Map<String, Set<String>> argumentsThatForceReplacement) {
    this.argumentsThatForceReplacement = Map.copyOf(argumentsThatForceReplacement);
  }

  public Plan plan(List<Resource> desired, StateFile recorded, FakeCloud cloud) {
    List<Drift> drifts = new ArrayList<>();
    StateFile refreshed = refresh(recorded, cloud, drifts);
    Map<String, Resource> desiredByAddress = new LinkedHashMap<>();
    desired.forEach(resource -> desiredByAddress.put(resource.address(), resource));
    List<Change> changes = new ArrayList<>();
    for (Resource wanted : desired) {
      Recorded existing = refreshed.resourcesByAddress().get(wanted.address());
      if (existing == null) {
        changes.add(new Change(Action.CREATE, wanted.address(), List.of()));
        continue;
      }
      List<String> differences = differences(existing.resource(), wanted);
      if (differences.isEmpty()) {
        continue;
      }
      Set<String> forcing = argumentsThatForceReplacement.getOrDefault(wanted.type(), Set.of());
      boolean mustReplace =
          differences.stream().anyMatch(line -> forcing.contains(line.split(":")[0]));
      changes.add(
          new Change(
              mustReplace ? Action.REPLACE : Action.UPDATE_IN_PLACE,
              wanted.address(),
              differences));
    }
    for (String address : refreshed.resourcesByAddress().keySet()) {
      if (!desiredByAddress.containsKey(address)) {
        changes.add(new Change(Action.DELETE, address, List.of()));
      }
    }
    return new Plan(drifts, changes, refreshed);
  }

  public ApplyOutcome apply(Plan plan, List<Resource> desired, FakeCloud cloud) {
    Map<String, Recorded> next = new TreeMap<>(plan.refreshed().resourcesByAddress());
    Map<String, Resource> desiredByAddress = new LinkedHashMap<>();
    desired.forEach(resource -> desiredByAddress.put(resource.address(), resource));
    List<String> refusals = new ArrayList<>();
    for (Change change : plan.changes()) {
      Resource wanted = desiredByAddress.get(change.address());
      switch (change.action()) {
        case CREATE -> create(wanted, cloud, next, refusals);
        case UPDATE_IN_PLACE -> {
          cloud.update(next.get(change.address()).id(), wanted.arguments());
          next.put(change.address(), new Recorded(next.get(change.address()).id(), wanted));
        }
        case REPLACE -> {
          // Destroy, then create: a name that must be unique is free again only after the delete.
          cloud.delete(next.remove(change.address()).id());
          create(wanted, cloud, next, refusals);
        }
        case DELETE -> cloud.delete(next.remove(change.address()).id());
      }
    }
    StateFile before = plan.refreshed();
    return new ApplyOutcome(new StateFile(before.lineage(), before.serial() + 1, next), refusals);
  }

  private static void create(
      Resource wanted, FakeCloud cloud, Map<String, Recorded> next, List<String> refusals) {
    cloud
        .create(wanted.type(), wanted.arguments())
        .peekSuccess(id -> next.put(wanted.address(), new Recorded(id, wanted)))
        .peekError(taken ->
            refusals.add(
                wanted.address() + ": " + taken.type() + " " + taken.name() + " already exists"));
  }

  private static StateFile refresh(StateFile recorded, FakeCloud cloud, List<Drift> drifts) {
    Map<String, Recorded> refreshed = new TreeMap<>();
    recorded
        .resourcesByAddress()
        .forEach((address, entry) -> {
          Optional<FakeCloud.CloudObject> real = cloud.read(entry.id());
          if (real.isEmpty()) {
            drifts.add(new Drift(address, "(object)", "exists", "(deleted)"));
            return;
          }
          Resource asRecorded = entry.resource();
          Resource asReal = new Resource(address, asRecorded.type(), real.get().arguments());
          for (String difference : differences(asRecorded, asReal)) {
            String argument = difference.split(":")[0];
            drifts.add(
                new Drift(
                    address,
                    argument,
                    asRecorded.arguments().get(argument),
                    asReal.arguments().get(argument)));
          }
          refreshed.put(address, new Recorded(entry.id(), asReal));
        });
    return new StateFile(recorded.lineage(), recorded.serial(), refreshed);
  }

  private static List<String> differences(Resource from, Resource to) {
    Set<String> arguments = new TreeSet<>(from.arguments().keySet());
    arguments.addAll(to.arguments().keySet());
    List<String> differences = new ArrayList<>();
    for (String argument : arguments) {
      String before = from.arguments().get(argument);
      String after = to.arguments().get(argument);
      if (before == null ? after != null : !before.equals(after)) {
        differences.add(argument + ": " + before + " -> " + after);
      }
    }
    return differences;
  }
}
