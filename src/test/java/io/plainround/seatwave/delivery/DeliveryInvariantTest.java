package io.plainround.seatwave.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.delivery.canary.CanaryAnalysis;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.Bounds;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.Signal;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.WindowMetrics;
import io.plainround.seatwave.delivery.iac.StateBackend;
import io.plainround.seatwave.delivery.iac.StateBackend.LockMode;
import io.plainround.seatwave.delivery.migration.MigrationCli;
import io.plainround.seatwave.delivery.promotion.PromotionGate;
import io.plainround.seatwave.delivery.promotion.PromotionGate.Gate;
import io.plainround.seatwave.delivery.promotion.PromotionGate.StagingRecord;
import io.plainround.seatwave.delivery.promotion.PromotionRefusal;
import io.plainround.seatwave.delivery.support.Errors;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * The module's invariant: production runs what staging tested, and no schema step breaks running
 * code; with the rules that keep it (the canary's judgment and the state lock). Each test names the
 * one rule it guards, and a mutant that removes that rule fails it.
 */
class DeliveryInvariantTest {

  private static final String TESTED = "sha256:" + "a".repeat(64);
  private static final String UNTESTED = "sha256:" + "b".repeat(64);
  private static final String REPOSITORY = "111122223333.dkr.ecr.us-east-1.amazonaws.com/seatwave";
  private static final StagingRecord STAGING_PASSED =
      new StagingRecord(TESTED, EnumSet.allOf(Gate.class));
  // The walkthrough's bounds: errors above baseline + 1 point; at least 1,098 canary requests.
  private static final Bounds BOUNDS =
      new Bounds(EnumSet.allOf(Signal.class), 0.01, 0.01, 1.20, 1_098);
  private static final WindowMetrics BASELINE = new WindowMetrics(100_000, 500, 200, 120);

  @Test
  void productionRefusesADigestThatStagingDidNotTest() {
    assertThat(Errors.of(PromotionGate.decide(REPOSITORY + "@" + UNTESTED, STAGING_PASSED)))
        .containsInstanceOf(PromotionRefusal.UntestedDigest.class);
    assertThat(PromotionGate.decide(REPOSITORY + "@" + TESTED, STAGING_PASSED).discardError())
        .contains(TESTED);
  }

  @Test
  void productionRefusesATagEvenWhenItNamesTheTestedBuild() {
    assertThat(Errors.of(PromotionGate.decide(REPOSITORY + ":1.4.0", STAGING_PASSED)))
        .containsInstanceOf(PromotionRefusal.NotByDigest.class);
  }

  @Test
  void everyStepOfTheExpandAndContractRenameIsCompatibleWithWhatIsRunning() {
    assertThat(MigrationCli.problemsOf(MigrationCli.EXPAND_AND_CONTRACT)).isEmpty();
  }

  @Test
  void renamingTheColumnInOneReleaseBreaksTheVersionStillRunning() {
    assertThat(MigrationCli.problemsOf(MigrationCli.ONE_STEP_RENAME))
        .contains("v1 uses price, which does not exist");
  }

  @Test
  void aMigrationAppliedOutOfOrderIsRefused() {
    assertThat(MigrationCli.problemsOf(MigrationCli.OUT_OF_ORDER))
        .containsExactly("V35_2 is out of order: V35_3 is already applied");
  }

  @Test
  void aCanaryAboveBaselinePlusOnePointIsRolledBack() {
    assertThat(
            CanaryAnalysis.whyNot(
                CanaryAnalysis.judge(BASELINE, new WindowMetrics(1_800, 108, 4, 120), BOUNDS)))
        .containsInstanceOf(CanaryAnalysis.Fail.class);
    assertThat(
            CanaryAnalysis.judge(BASELINE, new WindowMetrics(1_800, 10, 4, 121), BOUNDS).isError())
        .isFalse();
  }

  @Test
  void aCanaryWithFewerThanTheMinimumRequestsIsNotJudgedHoweverCleanItLooks() {
    assertThat(
            CanaryAnalysis.whyNot(
                CanaryAnalysis.judge(BASELINE, new WindowMetrics(1_097, 0, 0, 100), BOUNDS)))
        .containsInstanceOf(CanaryAnalysis.Inconclusive.class);
  }

  @Test
  void aCanaryErrorBoundIsTheStableErrorRatePlusTheMarginNotAnyOtherRate() {
    // Stable fails 3.0 percent of requests and degrades 0.2 percent; a canary at 3.5 percent errors
    // is within stable + 1 point, so it passes. A bound built from any other baseline rate (the
    // degraded rate, or zero) would be 1.2 percent and roll it back.
    WindowMetrics noisyStable = new WindowMetrics(100_000, 3_000, 200, 120);

    assertThat(
            CanaryAnalysis.judge(noisyStable, new WindowMetrics(4_000, 140, 8, 120), BOUNDS)
                .isError())
        .isFalse();
  }

  @Test
  void aSecondApplyCannotTakeTheStateLockWhileTheFirstHoldsIt() {
    StateBackend production = new StateBackend(LockMode.LOCKING, "lineage-production");
    production.lock("pipeline-A");

    assertThat(production.lock("pipeline-B").isError()).isTrue();
    assertThat(production.lockHolder()).contains("pipeline-A");
  }
}
