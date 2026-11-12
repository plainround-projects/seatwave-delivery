package io.plainround.seatwave.delivery.canary;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.Bounds;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.NotPassed;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.Pass;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.Signal;
import io.plainround.seatwave.delivery.canary.CanaryAnalysis.WindowMetrics;
import java.io.PrintStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * A canary on seeded traffic: stepwise shares of 1, 5, 25 and 100 percent, judged every minute
 * against the stable version's same-minute metrics, rolled back when a bound is crossed, with the
 * requests each release exposed counted; then the same bad release all at once, and a canary too
 * small to see a fault.
 */
public final class CanaryCli {

  /** How one version behaves: its error and degraded rates and its latency distribution. */
  record Profile(String label, double errorRate, double degradedRate, double latencySigma) {}

  /** One step of the schedule: the canary's share and how long it holds before the next. */
  record Step(double share, int minutes) {}

  /** What a whole canary run did. */
  record Outcome(String result, long exposed, long failed, long degraded) {}

  // Module 33: SeatWave at rest serves 3,000 requests a second. Night traffic is assumed at a
  // tenth.
  private static final int DAY_REQUESTS_PER_SECOND = 3_000;
  private static final int NIGHT_REQUESTS_PER_SECOND = 300;
  private static final int SECONDS_PER_MINUTE = 60;
  // Assumed latency: median 40 ms; sigma 0.4723 puts p99 near 120 ms (exp(2.326 x 0.4723) = 3).
  private static final double MEDIAN_LATENCY_MILLIS = 40;
  private static final double HEALTHY_SIGMA = 0.4723;
  private static final double SLOW_SIGMA = 0.99;
  private static final int LATENCY_BUCKETS_MILLIS = 10_000;
  private static final double P99 = 0.99;
  // Assumed baseline: 0.5 percent errors, 0.2 percent degraded (202 PENDING confirms, Module 36).
  static final Profile STABLE = new Profile("stable", 0.005, 0.002, HEALTHY_SIGMA);
  static final Profile GOOD = new Profile("good release", 0.005, 0.002, HEALTHY_SIGMA);
  static final Profile BROKEN_HOLDS =
      new Profile("holds broken (5.5% errors)", 0.055, 0.002, HEALTHY_SIGMA);
  static final Profile SLOW = new Profile("slow (p99 about 400 ms)", 0.005, 0.002, SLOW_SIGMA);
  static final Profile DEGRADING =
      new Profile("degraded confirms (6%)", 0.005, 0.06, HEALTHY_SIGMA);
  static final Profile SUBTLE =
      new Profile("subtle fault (2.0% errors)", 0.02, 0.002, HEALTHY_SIGMA);
  static final List<Step> SCHEDULE =
      List.of(new Step(0.01, 5), new Step(0.05, 10), new Step(0.25, 10), new Step(1.0, 0));
  // The walkthrough's rule: errors above baseline + 1 point; degraded the same; p99 20 percent
  // over.
  private static final double MARGIN = 0.01;
  private static final double LATENCY_FACTOR = 1.20;
  private static final double SMALLEST_FAULT_TO_CATCH = STABLE.errorRate() + 2 * MARGIN;
  static final long MINIMUM_REQUESTS =
      CanaryAnalysis.requiredSample(STABLE.errorRate() + MARGIN, SMALLEST_FAULT_TO_CATCH);
  static final Bounds FULL =
      new Bounds(EnumSet.allOf(Signal.class), MARGIN, MARGIN, LATENCY_FACTOR, MINIMUM_REQUESTS);
  static final Bounds ERRORS_ONLY =
      new Bounds(EnumSet.of(Signal.ERRORS), MARGIN, MARGIN, LATENCY_FACTOR, MINIMUM_REQUESTS);
  private static final int MAX_EXTENSION_MINUTES = 30;
  private static final int HUMAN_NOTICES_AFTER_MINUTES = 20;
  private static final int NIGHT_STEP_MINUTES = 5;
  private static final double NIGHT_SHARE = 0.01;
  private static final double RAISED_NIGHT_SHARE = 0.05;
  private static final long KNOWN_BASELINE_REQUESTS = 1_000_000;
  private static final long RARE_FAULT_ONE_IN = 5_000;
  private static final int TRIALS = 1_000;
  private static final long SEED = 35;

  private final Random random = new Random(SEED);
  private final PrintStream out;

  private CanaryCli(PrintStream out) {
    this.out = out;
  }

  /** Prints the canary experiments to standard output. */
  public static void main(String[] arguments) {
    run(System.out);
  }

  /** Prints the canary experiments; the fixed seed makes every run print the same bytes. */
  public static void run(PrintStream out) {
    new CanaryCli(out).runAll();
  }

  private void runAll() {
    out.printf(
        Locale.ROOT,
        "CANARY ANALYSIS at %,d requests a second; steps 1%% for 5 min, 5%% for 10, 25%% for 10,"
            + " then 100%%%n",
        DAY_REQUESTS_PER_SECOND);
    out.printf(
        Locale.ROOT,
        "bounds: errors and degraded above baseline + %.1f point (95%% confidence); p99 above"
            + " baseline x %.2f%n",
        MARGIN * 100,
        LATENCY_FACTOR);
    out.printf(
        Locale.ROOT,
        "judged every minute once the step holds %,d canary requests (to catch %.1f%% errors"
            + " against a %.1f%% bound, 80%% power)%n%n",
        MINIMUM_REQUESTS,
        SMALLEST_FAULT_TO_CATCH * 100,
        (STABLE.errorRate() + MARGIN) * 100);
    Outcome good = canary(GOOD, FULL, DAY_REQUESTS_PER_SECOND);
    Outcome broken = canary(BROKEN_HOLDS, FULL, DAY_REQUESTS_PER_SECOND);
    Outcome slow = canary(SLOW, FULL, DAY_REQUESTS_PER_SECOND);
    Outcome degradedFull = canary(DEGRADING, FULL, DAY_REQUESTS_PER_SECOND);
    Outcome degradedErrorsOnly = canary(DEGRADING, ERRORS_ONLY, DAY_REQUESTS_PER_SECOND);
    Outcome allAtOnce = allAtOnce(BROKEN_HOLDS);
    out.println(
        "SUMMARY: requests sent to the new version before it was stopped or fully promoted");
    out.printf(
        Locale.ROOT,
        "%-46s %-26s %12s %10s %10s%n",
        "case",
        "outcome",
        "exposed",
        "errors",
        "degraded");
    printRow("A " + GOOD.label() + ", full analysis", good);
    printRow("B " + BROKEN_HOLDS.label() + ", full analysis", broken);
    printRow("C " + SLOW.label() + ", full analysis", slow);
    printRow("D " + DEGRADING.label() + ", full analysis", degradedFull);
    printRow("D " + DEGRADING.label() + ", errors only", degradedErrorsOnly);
    printRow("E " + BROKEN_HOLDS.label() + ", all at once", allAtOnce);
    out.println();
    tooSmallToSee();
  }

  private Outcome canary(Profile release, Bounds bounds, int requestsPerSecond) {
    out.println(
        "case: " + release.label() + (bounds == ERRORS_ONLY ? ", watching errors only" : ""));
    long exposed = 0;
    long failed = 0;
    long degraded = 0;
    int clockMinutes = 0;
    for (Step step : SCHEDULE) {
      if (step.share() >= 1.0) {
        out.printf(Locale.ROOT, "  minute %d: promoted to 100%%%n", clockMinutes);
        return new Outcome("promoted at minute " + clockMinutes, exposed, failed, degraded);
      }
      Window stableWindow = new Window();
      Window canaryWindow = new Window();
      Result<Pass, NotPassed> verdict = Result.success(new Pass(0));
      for (int minute = 1; minute <= step.minutes() + MAX_EXTENSION_MINUTES; minute++) {
        clockMinutes++;
        long minuteRequests = (long) requestsPerSecond * SECONDS_PER_MINUTE;
        long canaryRequests = Math.round(minuteRequests * step.share());
        serve(STABLE, minuteRequests - canaryRequests, stableWindow);
        serve(release, canaryRequests, canaryWindow);
        verdict = CanaryAnalysis.judge(stableWindow.metrics(), canaryWindow.metrics(), bounds);
        Optional<NotPassed> problem = CanaryAnalysis.whyNot(verdict);
        boolean isStepOver =
            minute >= step.minutes()
                && !(problem.orElse(null) instanceof CanaryAnalysis.Inconclusive);
        if (problem.orElse(null) instanceof CanaryAnalysis.Fail || isStepOver) {
          break;
        }
      }
      exposed += canaryWindow.requests;
      failed += canaryWindow.errors;
      degraded += canaryWindow.degraded;
      out.printf(
          Locale.ROOT,
          "  %4.0f%% to minute %2d: canary %,9d req, errors %.2f%% (baseline %.2f%%), degraded"
              + " %.2f%% (%.2f%%), p99 %.0f ms (%.0f) -> %s%n",
          step.share() * 100,
          clockMinutes,
          canaryWindow.requests,
          canaryWindow.metrics().errorRate() * 100,
          stableWindow.metrics().errorRate() * 100,
          canaryWindow.metrics().degradedRate() * 100,
          stableWindow.metrics().degradedRate() * 100,
          canaryWindow.metrics().latencyP99Millis(),
          stableWindow.metrics().latencyP99Millis(),
          describe(verdict));
      Optional<NotPassed> problem = CanaryAnalysis.whyNot(verdict);
      if (problem.isPresent()) {
        String result =
            switch (problem.get()) {
              case CanaryAnalysis.Fail fail -> "rolled back at minute " + clockMinutes;
              case CanaryAnalysis.Inconclusive inconclusive -> "not proven, rolled back";
            };
        return new Outcome(result, exposed, failed, degraded);
      }
    }
    throw new IllegalStateException("the schedule must end at 100 percent");
  }

  private Outcome allAtOnce(Profile release) {
    Window window = new Window();
    serve(
        release,
        (long) DAY_REQUESTS_PER_SECOND * SECONDS_PER_MINUTE * HUMAN_NOTICES_AFTER_MINUTES,
        window);
    out.printf(
        Locale.ROOT,
        "case: %s, all at once; a person rolls back after %d minutes%n%n",
        release.label(),
        HUMAN_NOTICES_AFTER_MINUTES);
    return new Outcome(
        "rolled back by hand, minute " + HUMAN_NOTICES_AFTER_MINUTES,
        window.requests,
        window.errors,
        window.degraded);
  }

  private void tooSmallToSee() {
    double bound = STABLE.errorRate() + MARGIN;
    long nightStepRequests =
        Math.round(
            NIGHT_REQUESTS_PER_SECOND * NIGHT_SHARE * SECONDS_PER_MINUTE * NIGHT_STEP_MINUTES);
    long neededForSubtle = CanaryAnalysis.requiredSample(bound, SUBTLE.errorRate());
    out.printf(
        Locale.ROOT,
        "A CANARY TOO SMALL: %s at night, %d requests a second, 1%% for %d minutes%n",
        SUBTLE.label(),
        NIGHT_REQUESTS_PER_SECOND,
        NIGHT_STEP_MINUTES);
    out.printf(
        Locale.ROOT,
        "  canary requests in the step: %d x 0.01 x %d s = %,d%n",
        NIGHT_REQUESTS_PER_SECOND,
        NIGHT_STEP_MINUTES * SECONDS_PER_MINUTE,
        nightStepRequests);
    out.printf(
        Locale.ROOT,
        "  needed to catch %.1f%% against %.1f%% (95%% confidence, 80%% power): %,d; for %.1f%%:"
            + " %,d%n",
        SUBTLE.errorRate() * 100,
        bound * 100,
        neededForSubtle,
        SMALLEST_FAULT_TO_CATCH * 100,
        MINIMUM_REQUESTS);
    out.printf(
        Locale.ROOT,
        "  time to %,d requests at 1%%: %.1f min; at 5%%: %.1f min%n",
        neededForSubtle,
        neededForSubtle / (NIGHT_REQUESTS_PER_SECOND * NIGHT_SHARE) / SECONDS_PER_MINUTE,
        neededForSubtle / (NIGHT_REQUESTS_PER_SECOND * RAISED_NIGHT_SHARE) / SECONDS_PER_MINUTE);
    // The stable side still serves about 89,100 requests in the step, so its rate is taken as
    // known.
    WindowMetrics knownBaseline =
        new WindowMetrics(
            KNOWN_BASELINE_REQUESTS,
            Math.round(KNOWN_BASELINE_REQUESTS * STABLE.errorRate()),
            0,
            0);
    Bounds errorsWithoutMinimum =
        new Bounds(EnumSet.of(Signal.ERRORS), MARGIN, MARGIN, LATENCY_FACTOR, 1);
    for (long sampleSize : List.of(nightStepRequests, neededForSubtle)) {
      int caught = 0;
      for (int trial = 0; trial < TRIALS; trial++) {
        Window window = new Window();
        serveCountsOnly(SUBTLE, sampleSize, window);
        if (CanaryAnalysis.whyNot(
                    CanaryAnalysis.judge(knownBaseline, window.metrics(), errorsWithoutMinimum))
                .orElse(null)
            instanceof CanaryAnalysis.Fail) {
          caught++;
        }
      }
      out.printf(
          Locale.ROOT,
          "  %,6d canary requests: fault caught in %d of %,d seeded trials%n",
          sampleSize,
          caught,
          TRIALS);
    }
    double chanceOfSeeingOne = 1 - Math.pow(1 - 1.0 / RARE_FAULT_ONE_IN, nightStepRequests);
    out.printf(
        Locale.ROOT,
        "  a fault in 1 of %,d requests: chance the %,d-request step sees even one: %.0f%%%n",
        RARE_FAULT_ONE_IN,
        nightStepRequests,
        chanceOfSeeingOne * 100);
  }

  private void serve(Profile profile, long requests, Window window) {
    for (long request = 0; request < requests; request++) {
      double outcome = random.nextDouble();
      if (outcome < profile.errorRate()) {
        window.errors++;
      } else if (outcome < profile.errorRate() + profile.degradedRate()) {
        window.degraded++;
      }
      double latencyMillis =
          MEDIAN_LATENCY_MILLIS * Math.exp(profile.latencySigma() * random.nextGaussian());
      window.latencyCounts[(int) Math.min(latencyMillis, LATENCY_BUCKETS_MILLIS - 1)]++;
      window.requests++;
    }
  }

  private void serveCountsOnly(Profile profile, long requests, Window window) {
    for (long request = 0; request < requests; request++) {
      if (random.nextDouble() < profile.errorRate()) {
        window.errors++;
      }
      window.requests++;
    }
  }

  private static String describe(Result<Pass, NotPassed> verdict) {
    return CanaryAnalysis.whyNot(verdict)
        .map(problem ->
            switch (problem) {
              case CanaryAnalysis.Fail fail -> "ROLL BACK: " + String.join("; ", fail.reasons());
              case CanaryAnalysis.Inconclusive inconclusive ->
                  "inconclusive: " + inconclusive.canaryRequests() + " of " + inconclusive.needed();
            })
        .orElse("pass");
  }

  private void printRow(String label, Outcome outcome) {
    out.printf(
        Locale.ROOT,
        "%-46s %-26s %,12d %,10d %,10d%n",
        label,
        outcome.result(),
        outcome.exposed(),
        outcome.failed(),
        outcome.degraded());
  }

  /** Counts and a 1-millisecond latency histogram for one version in one step. */
  private static final class Window {
    private long requests;
    private long errors;
    private long degraded;
    private final long[] latencyCounts = new long[LATENCY_BUCKETS_MILLIS];

    WindowMetrics metrics() {
      long target = (long) Math.ceil(requests * P99);
      long seen = 0;
      int bucket = 0;
      while (bucket < LATENCY_BUCKETS_MILLIS - 1 && seen + latencyCounts[bucket] < target) {
        seen += latencyCounts[bucket];
        bucket++;
      }
      return new WindowMetrics(requests, errors, degraded, requests == 0 ? 0 : bucket + 1);
    }
  }
}
