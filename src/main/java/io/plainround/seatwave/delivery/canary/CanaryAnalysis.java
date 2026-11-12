package io.plainround.seatwave.delivery.canary;

import com.github.sviperll.result4j.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Judges a canary against the baseline it runs beside. A rate is a failure only when it is above
 * its bound with 95 percent confidence, and nothing is judged before the window holds enough canary
 * requests to see the smallest fault the bounds are meant to catch.
 */
public final class CanaryAnalysis {

  /** The signals a canary can watch. Module 36: a degraded response is not an error. */
  public enum Signal {
    ERRORS,
    LATENCY_P99,
    DEGRADED
  }

  /** The rollback bounds. Margins are in fractions: 0.01 is one percentage point. */
  public record Bounds(
      Set<Signal> watched,
      double errorMargin,
      double degradedMargin,
      double latencyP99Factor,
      long minimumCanaryRequests) {
    public Bounds {
      if (errorMargin <= 0
          || degradedMargin <= 0
          || latencyP99Factor <= 1
          || minimumCanaryRequests < 1) {
        throw new IllegalArgumentException("bounds must be positive and the factor above 1");
      }
      watched = Set.copyOf(watched);
    }
  }

  /** What one version served in one window. */
  public record WindowMetrics(long requests, long errors, long degraded, double latencyP99Millis) {
    public WindowMetrics {
      if (requests < 0 || errors < 0 || degraded < 0 || errors + degraded > requests) {
        throw new IllegalArgumentException("counts must be non-negative and fit in the requests");
      }
    }

    public double errorRate() {
      return requests == 0 ? 0 : (double) errors / requests;
    }

    public double degradedRate() {
      return requests == 0 ? 0 : (double) degraded / requests;
    }
  }

  /** Every watched signal is inside its bound, on at least the minimum sample. */
  public record Pass(long canaryRequests) {}

  /** Why a window did not pass: a crossed bound (roll back) or too small a sample (wait). */
  public sealed interface NotPassed permits Fail, Inconclusive {}

  /** At least one bound is crossed: roll back. */
  public record Fail(List<String> reasons) implements NotPassed {
    /** Copies the reasons; a failure names at least one. */
    public Fail {
      reasons = List.copyOf(reasons);
      if (reasons.isEmpty()) {
        throw new IllegalArgumentException("a failure names the bound it crossed");
      }
    }
  }

  /** Too few canary requests to judge; the step must wait, never promote. */
  public record Inconclusive(long canaryRequests, long needed) implements NotPassed {}

  // One-sided 95 percent confidence and 80 percent power, from the standard normal distribution.
  static final double Z_CONFIDENCE_95_ONE_SIDED = 1.645;
  static final double Z_POWER_80 = 0.8416;

  private CanaryAnalysis() {}

  /**
   * Judges one window of the canary against the stable version's same window.
   *
   * @return a pass, or why not: {@link Inconclusive} below the minimum sample, whatever the rates,
   *     and {@link Fail} with every crossed bound otherwise
   */
  public static Result<Pass, NotPassed> judge(
      WindowMetrics baseline, WindowMetrics canary, Bounds bounds) {
    // The sample comes first: a small window is never a pass, however clean it looks.
    if (canary.requests() < bounds.minimumCanaryRequests()) {
      return Result.error(new Inconclusive(canary.requests(), bounds.minimumCanaryRequests()));
    }
    List<String> reasons = new ArrayList<>();
    if (bounds.watched().contains(Signal.ERRORS)) {
      checkRate(
              "errors",
              canary.errorRate(),
              baseline.errorRate() + bounds.errorMargin(),
              canary.requests())
          .ifPresent(reasons::add);
    }
    if (bounds.watched().contains(Signal.DEGRADED)) {
      checkRate(
              "degraded",
              canary.degradedRate(),
              baseline.degradedRate() + bounds.degradedMargin(),
              canary.requests())
          .ifPresent(reasons::add);
    }
    double latencyBound = baseline.latencyP99Millis() * bounds.latencyP99Factor();
    if (bounds.watched().contains(Signal.LATENCY_P99) && canary.latencyP99Millis() > latencyBound) {
      reasons.add(
          String.format(
              Locale.ROOT, "p99 %.0f ms > bound %.0f ms", canary.latencyP99Millis(), latencyBound));
    }
    return reasons.isEmpty()
        ? Result.success(new Pass(canary.requests()))
        : Result.error(new Fail(reasons));
  }

  /** The reason a verdict did not pass, or empty when it passed. */
  public static Optional<NotPassed> whyNot(Result<Pass, NotPassed> verdict) {
    return verdict.map(pass -> Optional.<NotPassed>empty()).recoverError(Optional::of);
  }

  /**
   * Requests needed so that a version whose true rate is faultRate is caught 80 percent of the time
   * by the 95 percent test against boundRate.
   */
  public static long requiredSample(double boundRate, double faultRate) {
    if (faultRate <= boundRate) {
      throw new IllegalArgumentException("a fault at or under the bound cannot be caught");
    }
    double numerator =
        Z_CONFIDENCE_95_ONE_SIDED * Math.sqrt(boundRate * (1 - boundRate))
            + Z_POWER_80 * Math.sqrt(faultRate * (1 - faultRate));
    double perRequest = numerator / (faultRate - boundRate);
    return (long) Math.ceil(perRequest * perRequest);
  }

  private static Optional<String> checkRate(
      String name, double observed, double bound, long requests) {
    // Above the bound by more than chance explains, so a healthy canary is rarely rolled back.
    double threshold =
        bound + Z_CONFIDENCE_95_ONE_SIDED * Math.sqrt(bound * (1 - bound) / requests);
    if (observed <= threshold) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            Locale.ROOT,
            "%s %.2f%% > bound %.2f%% (test %.2f%%)",
            name,
            observed * 100,
            bound * 100,
            threshold * 100));
  }
}
