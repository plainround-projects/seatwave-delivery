package io.plainround.seatwave.delivery.iac;

import com.github.sviperll.result4j.Result;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The real world the tool manages: a cloud provider's API in miniature. It knows nothing about
 * state files or addresses. It holds objects by an id it assigns, refuses a second object with a
 * name that must be unique in the account, and lets a person change an object by hand, as the
 * console does. Not thread-safe: one apply uses one instance at a time, as the demo does.
 */
public final class FakeCloud {

  /** One object as the provider stores it. */
  public record CloudObject(String type, Map<String, String> arguments) {
    public CloudObject {
      arguments = Map.copyOf(arguments);
    }
  }

  /** The provider refused a create: an object of this type already uses the name. */
  public record AlreadyExists(String type, String name) {}

  // Names the AWS API keeps unique per account and region; a VPC or a subnet has no such name,
  // so creating one twice makes two.
  private static final Map<String, String> UNIQUE_NAME_ARGUMENT =
      Map.of(
          "aws_eks_cluster", "name",
          "aws_eks_node_group", "node_group_name",
          "aws_rds_cluster", "cluster_identifier");
  // A quota is one setting per account: creating it again sets the same quota, as the provider
  // does.
  private static final Map<String, String> ADOPTED_BY_KEY =
      Map.of("aws_servicequotas_service_quota", "quota_code");

  private final Map<String, CloudObject> objectsById = new LinkedHashMap<>();
  private int createdCount;

  /**
   * Creates an object, as the provider's create call does.
   *
   * @return the new object's id (or, for a quota, the id of the one setting it adopted), or the
   *     refusal when a name that must be unique is already taken
   */
  public Result<String, AlreadyExists> create(String type, Map<String, String> arguments) {
    String adoptKey = ADOPTED_BY_KEY.get(type);
    Optional<String> adopted =
        adoptKey == null ? Optional.empty() : find(type, adoptKey, arguments.get(adoptKey));
    if (adopted.isPresent()) {
      update(adopted.get(), arguments);
      return Result.success(adopted.get());
    }
    String nameArgument = UNIQUE_NAME_ARGUMENT.get(type);
    if (nameArgument != null && find(type, nameArgument, arguments.get(nameArgument)).isPresent()) {
      return Result.error(new AlreadyExists(type, arguments.get(nameArgument)));
    }
    createdCount++;
    String id = String.format(Locale.ROOT, "%s-%04d", type.replace("aws_", ""), createdCount);
    objectsById.put(id, new CloudObject(type, arguments));
    return Result.success(id);
  }

  public Optional<CloudObject> read(String id) {
    return Optional.ofNullable(objectsById.get(id));
  }

  public void update(String id, Map<String, String> arguments) {
    CloudObject existing = objectsById.get(id);
    if (existing == null) {
      throw new IllegalArgumentException("no object " + id);
    }
    objectsById.put(id, new CloudObject(existing.type(), arguments));
  }

  public void delete(String id) {
    objectsById.remove(id);
  }

  /** A person changes one argument in the console; no state file hears about it. */
  public void changeByHand(String id, String argument, String value) {
    CloudObject existing = objectsById.get(id);
    Map<String, String> changed = new LinkedHashMap<>(existing.arguments());
    changed.put(argument, value);
    objectsById.put(id, new CloudObject(existing.type(), changed));
  }

  /** The id of the object of this type with this unique name, as a console search finds it. */
  public Optional<String> idOfNamed(String type, String name) {
    return find(type, UNIQUE_NAME_ARGUMENT.get(type), name);
  }

  /** Every object as type and arguments, without ids, sorted: what two accounts can compare. */
  public List<String> inventory() {
    return objectsById.values().stream()
        .map(object -> object.type() + " " + new TreeMap<>(object.arguments()))
        .sorted()
        .toList();
  }

  public long countOfType(String type) {
    return objectsById.values().stream().filter(object -> object.type().equals(type)).count();
  }

  private Optional<String> find(String type, String argument, String value) {
    return objectsById.entrySet().stream()
        .filter(entry -> entry.getValue().type().equals(type))
        .filter(entry -> value.equals(entry.getValue().arguments().get(argument)))
        .map(Map.Entry::getKey)
        .findFirst();
  }
}
