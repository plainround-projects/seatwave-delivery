package io.plainround.seatwave.delivery.iac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.delivery.iac.PlanEngine.Action;
import io.plainround.seatwave.delivery.iac.PlanEngine.Plan;
import io.plainround.seatwave.delivery.iac.PlanEngine.Resource;
import io.plainround.seatwave.delivery.iac.StateBackend.StateFile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanEngineTest {

  private static final PlanEngine ENGINE =
      new PlanEngine(Map.of("aws_eks_node_group", Set.of("instance_types")));
  private static final Resource GROUP =
      new Resource(
          "aws_eks_node_group.zone[\"use1-az1\"]",
          "aws_eks_node_group",
          Map.of("node_group_name", "seatwave-use1-az1", "instance_types", "m7i.xlarge"));

  @Test
  void anApplyChangesOnlyTheDifferenceAndCountsTheWrite() {
    FakeCloud cloud = new FakeCloud();
    StateFile applied = applied(List.of(GROUP), cloud);

    Plan again = ENGINE.plan(List.of(GROUP), applied, cloud);

    assertThat(applied.serial()).isEqualTo(1);
    assertThat(again.changes()).isEmpty();
    assertThat(again.drifts()).isEmpty();
  }

  @Test
  void anArgumentTheProviderCannotChangeInPlaceIsAReplace() {
    FakeCloud cloud = new FakeCloud();
    StateFile applied = applied(List.of(GROUP), cloud);
    Resource bigger = withArgument(GROUP, "instance_types", "m7i.2xlarge");

    Plan plan = ENGINE.plan(List.of(bigger), applied, cloud);
    StateFile after = ENGINE.apply(plan, List.of(bigger), cloud).written();

    assertThat(plan.count(Action.REPLACE)).isEqualTo(1);
    assertThat(cloud.countOfType("aws_eks_node_group")).isEqualTo(1);
    assertThat(after.resourcesByAddress().get(GROUP.address()).resource()).isEqualTo(bigger);
  }

  @Test
  void anObjectDeletedBehindTheToolsBackIsDriftAndIsCreatedAgain() {
    FakeCloud cloud = new FakeCloud();
    StateFile applied = applied(List.of(GROUP), cloud);
    cloud.delete(applied.resourcesByAddress().get(GROUP.address()).id());

    Plan plan = ENGINE.plan(List.of(GROUP), applied, cloud);

    assertThat(plan.drifts()).extracting(PlanEngine.Drift::real).containsExactly("(deleted)");
    assertThat(plan.count(Action.CREATE)).isEqualTo(1);
  }

  @Test
  void aResourceRemovedFromTheCodeIsDestroyed() {
    FakeCloud cloud = new FakeCloud();
    StateFile applied = applied(List.of(GROUP), cloud);

    Plan plan = ENGINE.plan(List.of(), applied, cloud);
    ENGINE.apply(plan, List.of(), cloud);

    assertThat(plan.count(Action.DELETE)).isEqualTo(1);
    assertThat(cloud.countOfType("aws_eks_node_group")).isZero();
  }

  @Test
  void aSecondObjectWithAUniqueNameIsRefusedAndLeftOutOfTheState() {
    FakeCloud cloud = new FakeCloud();
    applied(List.of(GROUP), cloud);

    Plan fromNothing = ENGINE.plan(List.of(GROUP), StateFile.empty("lost"), cloud);
    PlanEngine.ApplyOutcome outcome = ENGINE.apply(fromNothing, List.of(GROUP), cloud);

    assertThat(outcome.refusals())
        .containsExactly(GROUP.address() + ": aws_eks_node_group seatwave-use1-az1 already exists");
    assertThat(outcome.written().resourcesByAddress()).isEmpty();
  }

  @Test
  void anAddressMustStartWithItsType() {
    assertThatThrownBy(() -> new Resource("aws_vpc.seatwave", "aws_subnet", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theCloudRefusesToUpdateAnObjectItDoesNotHave() {
    assertThatThrownBy(() -> new FakeCloud().update("vpc-9999", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("vpc-9999");
  }

  @Test
  void aQuotaIsOneSettingThatASecondCreateAdopts() {
    FakeCloud cloud = new FakeCloud();
    Map<String, String> quota = Map.of("quota_code", "L-1216C47A", "value", "160");

    String first =
        cloud.create("aws_servicequotas_service_quota", quota).discardError().orElseThrow();
    String second =
        cloud
            .create(
                "aws_servicequotas_service_quota",
                Map.of("quota_code", "L-1216C47A", "value", "40"))
            .discardError()
            .orElseThrow();

    assertThat(second).isEqualTo(first);
    assertThat(cloud.read(first).orElseThrow().arguments()).containsEntry("value", "40");
  }

  private static StateFile applied(List<Resource> desired, FakeCloud cloud) {
    Plan plan = ENGINE.plan(desired, StateFile.empty("lineage"), cloud);
    return ENGINE.apply(plan, desired, cloud).written();
  }

  private static Resource withArgument(Resource resource, String argument, String value) {
    Map<String, String> arguments = new java.util.TreeMap<>(resource.arguments());
    arguments.put(argument, value);
    return new Resource(resource.address(), resource.type(), arguments);
  }
}
