package io.plainround.seatwave.delivery.canary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.delivery.canary.CanaryAnalysis.Bounds;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.Signal;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.WindowMetrics;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanaryAnalysisTest {

  private static final Bounds ALL =
      new Bounds(EnumSet.allOf(Signal.class), 0.01, 0.01, 1.20, 1_098);
  private static final WindowMetrics STABLE = new WindowMetrics(100_000, 500, 200, 120);

  @Test
  void theMinimumSampleIsTheModulesOneThousandNinetyEight() {
    assertThat(CanaryAnalysis.requiredSample(0.015, 0.025)).isEqualTo(1_098);
    assertThat(CanaryAnalysis.requiredSample(0.015, 0.020)).isEqualTo(4_040);
  }

  @Test
  void aFaultAtOrUnderTheBoundCannotBeCaught() {
    assertThatThrownBy(() -> CanaryAnalysis.requiredSample(0.015, 0.015))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSlowCanaryFailsOnP99AloneAndSaysByHowMuch() {
    var verdict = CanaryAnalysis.judge(STABLE, new WindowMetrics(2_000, 10, 4, 145), ALL);

    assertThat(CanaryAnalysis.whyNot(verdict))
        .containsInstanceOf(CanaryAnalysis.Fail.class)
        .hasValueSatisfying(problem ->
            assertThat(((CanaryAnalysis.Fail) problem).reasons())
                .containsExactly("p99 145 ms > bound 144 ms"));
  }

  @Test
  void degradedAnswersFailTheFullAnalysisButNotAnErrorsOnlyOne() {
    WindowMetrics degrading = new WindowMetrics(5_000, 25, 300, 120);
    Bounds errorsOnly = new Bounds(EnumSet.of(Signal.ERRORS), 0.01, 0.01, 1.20, 1_098);

    assertThat(CanaryAnalysis.judge(STABLE, degrading, ALL).isError()).isTrue();
    assertThat(CanaryAnalysis.judge(STABLE, degrading, errorsOnly).isError()).isFalse();
  }

  @Test
  void aRateJustAboveItsBoundIsWithinChanceAndPasses() {
    // 1.6 percent against a 1.5 percent bound on 2,000 requests: the 95 percent test needs about
    // 1.95 percent before it calls it a failure.
    assertThat(CanaryAnalysis.judge(STABLE, new WindowMetrics(2_000, 32, 4, 120), ALL).isError())
        .isFalse();
  }

  @Test
  void aPassCountsTheCanaryRequestsItJudged() {
    var verdict = CanaryAnalysis.judge(STABLE, new WindowMetrics(2_000, 10, 4, 120), ALL);

    assertThat(verdict.discardError()).contains(new CanaryAnalysis.Pass(2_000));
    assertThat(CanaryAnalysis.whyNot(verdict)).isEmpty();
  }

  @Test
  void boundsMustBePositiveAndTheFactorAboveOne() {
    assertThatThrownBy(() -> new Bounds(EnumSet.allOf(Signal.class), 0, 0.01, 1.2, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Bounds(EnumSet.allOf(Signal.class), 0.01, 0.01, 1.0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Bounds(EnumSet.allOf(Signal.class), 0.01, 0.01, 1.2, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aWindowsCountsMustFitInItsRequests() {
    assertThatThrownBy(() -> new WindowMetrics(10, 6, 5, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WindowMetrics(-1, 0, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new WindowMetrics(0, 0, 0, 0).errorRate()).isZero();
    assertThat(new WindowMetrics(0, 0, 0, 0).degradedRate()).isZero();
  }

  @Test
  void aFailureNamesTheBoundItCrossed() {
    assertThatThrownBy(() -> new CanaryAnalysis.Fail(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
