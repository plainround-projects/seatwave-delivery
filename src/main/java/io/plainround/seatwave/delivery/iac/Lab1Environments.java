package io.plainround.seatwave.delivery.iac;

import io.plainround.seatwave.delivery.iac.IacDemo.Environment;
import io.plainround.seatwave.delivery.iac.PlanEngine.Resource;
import io.plainround.seatwave.delivery.iac.StateBackend.StateFile;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Lab 1: plan staging, production and a third environment from one declaration. */
public final class Lab1Environments {

  // The load-test environment: production's peak shape, one database cluster, its own account.
  static final Environment LOAD_TEST =
      new Environment(
          "seatwave-loadtest",
          List.of("use1-az1", "use1-az2", "use1-az4"),
          List.of("use1-az1", "use1-az2"),
          0,
          10,
          10,
          "m7i.xlarge",
          List.of("seatwave-loadtest-db-a"),
          100);

  private Lab1Environments() {}

  /** Prints the three plans, the differences and the refusal to standard output. */
  public static void main(String[] arguments) {
    run(System.out);
  }

  /** Prints the three plans, the node groups' differences and the one-zone refusal. */
  public static void run(PrintStream out) {
    PlanEngine engine = new PlanEngine(Map.of("aws_eks_node_group", Set.of("instance_types")));
    for (Environment environment : List.of(IacDemo.STAGING, IacDemo.PRODUCTION, LOAD_TEST)) {
      List<Resource> desired = IacDemo.declare(environment);
      PlanEngine.Plan plan = engine.plan(desired, StateFile.empty("lab"), new FakeCloud());
      out.println(
          environment.namePrefix() + ": " + plan.count(PlanEngine.Action.CREATE) + " to add");
    }
    Resource stagingGroup = nodeGroup(IacDemo.STAGING);
    Resource productionGroup = nodeGroup(IacDemo.PRODUCTION);
    new TreeMap<>(stagingGroup.arguments())
        .forEach((argument, stagingValue) -> {
          String productionValue = productionGroup.arguments().get(argument);
          if (!stagingValue.equals(productionValue)) {
            out.println(
                "  differs: "
                    + argument
                    + " staging "
                    + stagingValue
                    + ", production "
                    + productionValue);
          }
        });
    try {
      new Environment(
          "seatwave-onezone",
          List.of("use1-az1", "use1-az2", "use1-az4"),
          List.of("use1-az1"),
          3,
          10,
          10,
          "m7i.xlarge",
          List.of("seatwave-onezone-db-a"),
          160);
    } catch (IllegalArgumentException refused) {
      out.println("one compute zone refused: " + refused.getMessage());
    }
  }

  private static Resource nodeGroup(Environment environment) {
    return IacDemo.declare(environment).stream()
        .filter(resource -> resource.address().equals("aws_eks_node_group.zone[\"use1-az1\"]"))
        .findFirst()
        .orElseThrow();
  }
}
