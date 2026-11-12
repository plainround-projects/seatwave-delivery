package io.plainround.seatwave.delivery.iac;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.delivery.iac.PlanEngine.Action;
import io.plainround.seatwave.delivery.iac.PlanEngine.Plan;
import io.plainround.seatwave.delivery.iac.PlanEngine.Resource;
import io.plainround.seatwave.delivery.iac.StateBackend.LockMode;
import io.plainround.seatwave.delivery.iac.StateBackend.StateFile;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows state, plan, apply, drift, lost and stale state, locking and idempotence on SeatWave's
 * environment, declared below as the Java mirror of infra/modules/seatwave-environment.
 */
public final class IacDemo {

  /** The module's variables for one environment, validated as the module's precondition is. */
  record Environment(
      String namePrefix,
      List<String> dataZones,
      List<String> computeZones,
      int nodesAtRestPerZone,
      int nodesAtPeakPerZone,
      int nodesNeededAtPeak,
      String instanceType,
      List<String> databaseClusters,
      int onDemandVcpuQuota) {
    Environment {
      // Static stability (Module 34): each compute zone alone must hold the peak's whole need.
      if (computeZones.size() < MINIMUM_ZONES || nodesAtPeakPerZone < nodesNeededAtPeak) {
        throw new IllegalArgumentException(namePrefix + ": a lost zone would leave the peak short");
      }
      dataZones = List.copyOf(dataZones);
      computeZones = List.copyOf(computeZones);
      databaseClusters = List.copyOf(databaseClusters);
    }

    Environment withComputeZones(List<String> zones) {
      return new Environment(
          namePrefix,
          dataZones,
          zones,
          nodesAtRestPerZone,
          nodesAtPeakPerZone,
          nodesNeededAtPeak,
          instanceType,
          databaseClusters,
          onDemandVcpuQuota);
    }

    Environment withQuota(int vcpus) {
      return new Environment(
          namePrefix,
          dataZones,
          computeZones,
          nodesAtRestPerZone,
          nodesAtPeakPerZone,
          nodesNeededAtPeak,
          instanceType,
          databaseClusters,
          vcpus);
    }
  }

  private static final int MINIMUM_ZONES = 2;
  private static final int FINGERPRINT_HEX_CHARACTERS = 12;
  // cidrsubnet("10.35.0.0/16", 4, index) in the module: each /20 starts 16 apart in the third
  // octet.
  private static final int SUBNET_STEP = 16;
  // Module 34: 3 nodes at rest and 10 at the peak, each of two zones sized for all of it.
  static final Environment PRODUCTION =
      new Environment(
          "seatwave",
          List.of("use1-az1", "use1-az2", "use1-az4"),
          List.of("use1-az1", "use1-az2"),
          3,
          10,
          10,
          "m7i.xlarge",
          List.of("seatwave-db-a", "seatwave-db-b"),
          160);
  // Staging: the same shape, smaller; one database cluster; its own account.
  static final Environment STAGING =
      new Environment(
          "seatwave-staging",
          List.of("use1-az1", "use1-az2", "use1-az4"),
          List.of("use1-az1", "use1-az2"),
          1,
          2,
          2,
          "m7i.xlarge",
          List.of("seatwave-staging-db-a"),
          40);
  // The AWS provider replaces a subnet whose zone changes and a node group whose types change.
  private static final Map<String, Set<String>> FORCES_REPLACEMENT =
      Map.of(
          "aws_subnet", Set.of("availability_zone_id", "cidr_block"),
          "aws_eks_node_group", Set.of("instance_types", "subnet_ids"),
          "aws_rds_cluster", Set.of("engine", "db_subnet_group_name"));
  private static final PlanEngine ENGINE = new PlanEngine(FORCES_REPLACEMENT);

  private final PrintStream out;

  private IacDemo(PrintStream out) {
    this.out = out;
  }

  /** Prints the six experiments to standard output. */
  public static void main(String[] arguments) throws NoSuchAlgorithmException {
    run(System.out);
  }

  /** Prints the six experiments; nothing is random, so every run prints the same bytes. */
  public static void run(PrintStream out) throws NoSuchAlgorithmException {
    new IacDemo(out).runAll();
  }

  private void runAll() throws NoSuchAlgorithmException {
    FakeCloud productionAccount = new FakeCloud();
    StateBackend productionState = new StateBackend(LockMode.LOCKING, "lineage-production");
    oneDeclarationTwoEnvironments(productionAccount, productionState);
    fourKindsOfChange(productionAccount, productionState);
    driftFromAHandChange(productionAccount, productionState);
    lostAndStaleState(productionAccount, productionState);
    twoAppliesAtOnce(LockMode.NO_LOCKING);
    twoAppliesAtOnce(LockMode.LOCKING);
    declarativeAgainstScript();
  }

  static List<Resource> declare(Environment environment) {
    List<Resource> resources = new ArrayList<>();
    String prefix = environment.namePrefix();
    resources.add(
        new Resource(
            "aws_vpc.seatwave",
            "aws_vpc",
            Map.of("cidr_block", "10.35.0.0/16", "tags.Name", prefix)));
    for (int zoneIndex = 0; zoneIndex < environment.dataZones().size(); zoneIndex++) {
      String zone = environment.dataZones().get(zoneIndex);
      resources.add(
          new Resource(
              "aws_subnet.private[\"" + zone + "\"]",
              "aws_subnet",
              Map.of(
                  "availability_zone_id",
                  zone,
                  "cidr_block",
                  "10.35." + SUBNET_STEP * zoneIndex + ".0/20",
                  "vpc_id",
                  "aws_vpc.seatwave.id")));
    }
    resources.add(
        new Resource(
            "aws_eks_cluster.seatwave",
            "aws_eks_cluster",
            Map.of("name", prefix, "version", "1.37")));
    for (String zone : environment.computeZones()) {
      resources.add(
          new Resource(
              "aws_eks_node_group.zone[\"" + zone + "\"]",
              "aws_eks_node_group",
              Map.of(
                  "node_group_name",
                  prefix + "-" + zone,
                  "cluster_name",
                  prefix,
                  "subnet_ids",
                  "aws_subnet.private[\"" + zone + "\"].id",
                  "instance_types",
                  environment.instanceType(),
                  "scaling_config.min_size",
                  String.valueOf(environment.nodesAtRestPerZone()),
                  "scaling_config.max_size",
                  String.valueOf(environment.nodesAtPeakPerZone()))));
    }
    for (String cluster : environment.databaseClusters()) {
      resources.add(
          new Resource(
              "aws_rds_cluster.shard[\"" + cluster + "\"]",
              "aws_rds_cluster",
              Map.of(
                  "cluster_identifier",
                  cluster,
                  "engine",
                  "postgres",
                  "db_cluster_instance_class",
                  "db.m6id.xlarge",
                  "db_subnet_group_name",
                  prefix)));
    }
    resources.add(
        new Resource(
            "aws_servicequotas_service_quota.on_demand_vcpus",
            "aws_servicequotas_service_quota",
            Map.of(
                "quota_code",
                "L-1216C47A",
                "value",
                String.valueOf(environment.onDemandVcpuQuota()))));
    return resources;
  }

  private void oneDeclarationTwoEnvironments(FakeCloud account, StateBackend backend) {
    out.println("PART 1. ONE DECLARATION, TWO ENVIRONMENTS");
    FakeCloud stagingAccount = new FakeCloud();
    StateBackend stagingState = new StateBackend(LockMode.LOCKING, "lineage-staging");
    applyAndReport("staging", declare(STAGING), stagingState, stagingAccount);
    Plan again = ENGINE.plan(declare(STAGING), stagingState.read(), stagingAccount);
    out.println("staging, plan again: " + summary(again));
    applyAndReport("production", declare(PRODUCTION), backend, account);
    out.println();
  }

  private void fourKindsOfChange(FakeCloud account, StateBackend backend) {
    out.println("PART 2. ONE EDIT, FOUR KINDS OF CHANGE (planned, not applied)");
    List<Resource> edited = new ArrayList<>();
    for (Resource resource :
        declare(PRODUCTION.withComputeZones(List.of("use1-az1", "use1-az2", "use1-az4")))) {
      Map<String, String> arguments = new LinkedHashMap<>(resource.arguments());
      if (resource.address().equals("aws_eks_node_group.zone[\"use1-az1\"]")) {
        arguments.put("scaling_config.max_size", "12");
      }
      if (resource.address().equals("aws_eks_node_group.zone[\"use1-az2\"]")) {
        arguments.put("instance_types", "m7i.2xlarge");
      }
      if (!resource.address().equals("aws_rds_cluster.shard[\"seatwave-db-b\"]")) {
        edited.add(new Resource(resource.address(), resource.type(), arguments));
      }
    }
    printPlan(ENGINE.plan(edited, backend.read(), account));
    out.println();
  }

  private void driftFromAHandChange(FakeCloud account, StateBackend backend) {
    out.println("PART 3. DRIFT: a person lowers a node group's maximum in the console");
    String nodeGroupId = account.idOfNamed("aws_eks_node_group", "seatwave-use1-az2").orElseThrow();
    account.changeByHand(nodeGroupId, "scaling_config.max_size", "4");
    Plan plan = ENGINE.plan(declare(PRODUCTION), backend.read(), account);
    printPlan(plan);
    backend.save(ENGINE.apply(plan, declare(PRODUCTION), account).written());
    out.println(
        "after apply: " + summary(ENGINE.plan(declare(PRODUCTION), backend.read(), account)));
    out.println();
  }

  private void lostAndStaleState(FakeCloud account, StateBackend backend) {
    out.println("PART 4. LOST AND STALE STATE");
    StateFile staleCopy = backend.read();
    List<Resource> threeZones =
        declare(PRODUCTION.withComputeZones(List.of("use1-az1", "use1-az2", "use1-az4")));
    applyAndReport("production, third compute zone added", threeZones, backend, account);
    Plan fromStale = ENGINE.plan(threeZones, staleCopy, account);
    out.println("stale copy (serial " + staleCopy.serial() + "), plan: " + summary(fromStale));
    out.println("  refused on apply: " + ENGINE.apply(fromStale, threeZones, account).refusals());
    out.println(
        "  push of the stale copy: "
            + backend
                .push(staleCopy)
                .map(serial -> "pushed, serial " + serial)
                .recoverError(refusal -> "refused: " + refusal.describe()));
    Plan fromNothing = ENGINE.plan(threeZones, StateFile.empty("lineage-production"), account);
    out.println("lost state (empty file), plan: " + summary(fromNothing));
    PlanEngine.ApplyOutcome outcome = ENGINE.apply(fromNothing, threeZones, account);
    outcome.refusals().forEach(refusal -> out.println("  refused: " + refusal));
    out.println(
        "  account now holds "
            + account.countOfType("aws_vpc")
            + " VPCs and "
            + account.countOfType("aws_subnet")
            + " subnets; the new state knows only the new ones");
    out.println();
  }

  private void twoAppliesAtOnce(LockMode lockMode) {
    out.println("PART 5. TWO PIPELINES APPLY STAGING AT ONCE, " + lockMode);
    FakeCloud account = new FakeCloud();
    StateBackend backend = new StateBackend(lockMode, "lineage-staging");
    backend.save(
        ENGINE
            .apply(
                ENGINE.plan(declare(STAGING), backend.read(), account), declare(STAGING), account)
            .written());
    Environment withThirdZone =
        STAGING.withComputeZones(List.of("use1-az1", "use1-az2", "use1-az4"));
    List<Resource> codeOfA = declare(withThirdZone);
    List<Resource> codeOfB = declare(STAGING.withQuota(60));
    out.println("A locks: " + describe(backend.lock("pipeline-A")));
    Plan planOfA = ENGINE.plan(codeOfA, backend.read(), account);
    out.println("B locks: " + describe(backend.lock("pipeline-B")));
    if (backend.lockHolder().filter("pipeline-A"::equals).isPresent()) {
      backend.save(ENGINE.apply(planOfA, codeOfA, account).written());
      backend.unlock("pipeline-A");
      out.println(
          "B waits, then runs on merged main (A's change and its own): "
              + describe(backend.lock("pipeline-B")));
      List<Resource> merged = declare(withThirdZone.withQuota(60));
      Plan planOfB = ENGINE.plan(merged, backend.read(), account);
      out.println("B's plan: " + summary(planOfB));
      backend.save(ENGINE.apply(planOfB, merged, account).written());
      backend.unlock("pipeline-B");
      reportFinal(backend, account, merged);
      return;
    }
    Plan planOfB = ENGINE.plan(codeOfB, backend.read(), account);
    backend.save(ENGINE.apply(planOfA, codeOfA, account).written());
    out.println(
        "A applied, state serial " + backend.read().serial() + " records the third node group");
    backend.save(ENGINE.apply(planOfB, codeOfB, account).written());
    out.println("B applied from the same serial; its save overwrote A's");
    reportFinal(backend, account, declare(withThirdZone.withQuota(60)));
  }

  private void reportFinal(StateBackend backend, FakeCloud account, List<Resource> merged) {
    out.println(
        "final: state serial "
            + backend.read().serial()
            + ", "
            + backend.read().resourcesByAddress().size()
            + " resources recorded, "
            + account.countOfType("aws_eks_node_group")
            + " node groups running");
    Plan next = ENGINE.plan(merged, backend.read(), account);
    out.println("next plan of merged main: " + summary(next));
    out.println("  refused on apply: " + ENGINE.apply(next, merged, account).refusals());
    out.println();
  }

  private void declarativeAgainstScript() throws NoSuchAlgorithmException {
    out.println("PART 6. THE SAME ENVIRONMENT TWICE: DECLARATION AGAINST SCRIPT");
    FakeCloud firstAccount = new FakeCloud();
    FakeCloud secondAccount = new FakeCloud();
    for (FakeCloud account : List.of(firstAccount, secondAccount)) {
      StateBackend backend = new StateBackend(LockMode.LOCKING, "lineage-staging");
      for (int run = 1; run <= 2; run++) {
        Plan plan = ENGINE.plan(declare(STAGING), backend.read(), account);
        backend.save(ENGINE.apply(plan, declare(STAGING), account).written());
      }
    }
    out.println(
        "declaration applied twice in each of two accounts: fingerprints "
            + fingerprint(firstAccount)
            + " and "
            + fingerprint(secondAccount)
            + ", "
            + firstAccount.inventory().size()
            + " objects each");
    FakeCloud scriptAccount = new FakeCloud();
    for (int run = 1; run <= 2; run++) {
      List<String> errors = runScript(scriptAccount);
      String nodeGroupId =
          scriptAccount.idOfNamed("aws_eks_node_group", "seatwave-staging-use1-az1").orElseThrow();
      out.println(
          "script run "
              + run
              + ": "
              + scriptAccount.inventory().size()
              + " objects, VPCs: "
              + scriptAccount.countOfType("aws_vpc")
              + ", node group min_size "
              + scriptAccount
                  .read(nodeGroupId)
                  .orElseThrow()
                  .arguments()
                  .get("scaling_config.min_size")
              + ", fingerprint "
              + fingerprint(scriptAccount)
              + ", errors "
              + errors);
    }
  }

  /** The imperative way: steps, each a command, one of them relative ("add a node"). */
  private static List<String> runScript(FakeCloud account) {
    List<String> errors = new ArrayList<>();
    for (Resource resource : declare(STAGING)) {
      Map<String, String> arguments = new LinkedHashMap<>(resource.arguments());
      if (resource.type().equals("aws_eks_node_group")) {
        arguments.put("scaling_config.min_size", "0");
      }
      account
          .create(resource.type(), arguments)
          .peekError(taken -> errors.add(taken.name() + " already exists"));
    }
    for (String zone : STAGING.computeZones()) {
      String id = account.idOfNamed("aws_eks_node_group", "seatwave-staging-" + zone).orElseThrow();
      Map<String, String> arguments =
          new LinkedHashMap<>(account.read(id).orElseThrow().arguments());
      int minimum = Integer.parseInt(arguments.get("scaling_config.min_size"));
      arguments.put(
          "scaling_config.min_size", String.valueOf(minimum + STAGING.nodesAtRestPerZone()));
      account.update(id, arguments);
    }
    return errors;
  }

  private void applyAndReport(
      String label, List<Resource> desired, StateBackend backend, FakeCloud account) {
    Plan plan = ENGINE.plan(desired, backend.read(), account);
    out.println(label + ", plan: " + summary(plan));
    backend.save(ENGINE.apply(plan, desired, account).written());
    out.println(
        label
            + ", applied: state serial "
            + backend.read().serial()
            + ", "
            + backend.read().resourcesByAddress().size()
            + " resources recorded");
  }

  private void printPlan(Plan plan) {
    plan.drifts()
        .forEach(drift ->
            out.println(
                "  drift: "
                    + drift.address()
                    + " "
                    + drift.argument()
                    + " recorded "
                    + drift.recorded()
                    + ", real "
                    + drift.real()));
    for (PlanEngine.Change change : plan.changes()) {
      String symbol =
          switch (change.action()) {
            case CREATE -> "  +   ";
            case UPDATE_IN_PLACE -> "  ~   ";
            case REPLACE -> "  -/+ ";
            case DELETE -> "  -   ";
          };
      out.println(
          symbol
              + change.address()
              + (change.differences().isEmpty()
                  ? ""
                  : "  " + String.join("; ", change.differences())));
    }
    out.println("  " + summary(plan));
  }

  private static String describe(Result<String, StateBackend.HeldBy> lock) {
    return lock.map(holder -> "acquired by " + holder)
        .recoverError(held -> "refused, held by " + held.holder());
  }

  private static String summary(Plan plan) {
    if (plan.changes().isEmpty()) {
      return "no changes"
          + (plan.drifts().isEmpty() ? "" : " (" + plan.drifts().size() + " drifted)");
    }
    return plan.count(Action.CREATE)
        + " to add, "
        + plan.count(Action.UPDATE_IN_PLACE)
        + " to change, "
        + plan.count(Action.REPLACE)
        + " to replace, "
        + plan.count(Action.DELETE)
        + " to destroy";
  }

  private static String fingerprint(FakeCloud account) throws NoSuchAlgorithmException {
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest(String.join("\n", account.inventory()).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_HEX_CHARACTERS);
  }
}
