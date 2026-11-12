package io.plainround.seatwave.delivery.canary;

import io.plainround.seatwave.delivery.canary.CanaryAnalysis.WindowMetrics;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Random;

/**
 * Lab 2: a release that is healthy for 8 minutes and then fails 4 percent of requests (a planted
 * error, like a pool that runs dry). Judged every minute, the canary rolls it back during the 5
 * percent step; judged once at the end of each step, it is caught later and exposes more fans.
 */
public final class Lab2PlantedError {

  private static final int REQUESTS_PER_SECOND = 3_000;
  private static final int SECONDS_PER_MINUTE = 60;
  private static final int FAULT_STARTS_AT_MINUTE = 9;
  private static final double FAULT_ERROR_RATE = 0.04;
  private static final long SEED = 352;

  /** When the analysis looks at the canary's window. */
  enum Judging {
    EVERY_MINUTE,
    END_OF_STEP
  }

  private Lab2PlantedError() {}

  /** Prints both policies' rollback minute, exposure and errors. */
  public static void main(String[] arguments) {
    run(System.out);
  }

  /** Prints both policies; the fixed seed makes every run print the same bytes. */
  public static void run(PrintStream out) {
    report(out, "judged every minute", run(Judging.EVERY_MINUTE));
    report(out, "judged once per step", run(Judging.END_OF_STEP));
  }

  /** Returns {minute stopped, canary requests exposed, canary errors}. */
  static long[] run(Judging judging) {
    Random random = new Random(SEED);
    long exposed = 0;
    long failed = 0;
    int clockMinutes = 0;
    for (CanaryCli.Step step : CanaryCli.SCHEDULE) {
      if (step.share() >= 1.0) {
        return new long[] {clockMinutes, exposed, failed};
      }
      long[] stable = new long[2];
      long[] canary = new long[2];
      for (int minute = 1; minute <= step.minutes(); minute++) {
        clockMinutes++;
        long minuteRequests = (long) REQUESTS_PER_SECOND * SECONDS_PER_MINUTE;
        long canaryRequests = Math.round(minuteRequests * step.share());
        double canaryRate =
            clockMinutes >= FAULT_STARTS_AT_MINUTE ? FAULT_ERROR_RATE : CanaryCli.GOOD.errorRate();
        count(random, minuteRequests - canaryRequests, CanaryCli.STABLE.errorRate(), stable);
        count(random, canaryRequests, canaryRate, canary);
        exposed += canaryRequests;
        boolean isJudgingNow = judging == Judging.EVERY_MINUTE || minute == step.minutes();
        if (isJudgingNow && isFailure(stable, canary)) {
          failed += canary[1];
          return new long[] {clockMinutes, exposed, failed};
        }
      }
      failed += canary[1];
    }
    throw new IllegalStateException("the schedule must end at 100 percent");
  }

  private static boolean isFailure(long[] stable, long[] canary) {
    return CanaryAnalysis.whyNot(
                CanaryAnalysis.judge(
                    new WindowMetrics(stable[0], stable[1], 0, 0),
                    new WindowMetrics(canary[0], canary[1], 0, 0),
                    CanaryCli.ERRORS_ONLY))
            .orElse(null)
        instanceof CanaryAnalysis.Fail;
  }

  private static void count(Random random, long requests, double errorRate, long[] totals) {
    for (long request = 0; request < requests; request++) {
      totals[0]++;
      if (random.nextDouble() < errorRate) {
        totals[1]++;
      }
    }
  }

  private static void report(PrintStream out, String policy, long[] result) {
    out.printf(
        Locale.ROOT,
        "%-22s rolled back at minute %d; canary requests %,d; canary errors %,d%n",
        policy,
        result[0],
        result[1],
        result[2]);
  }
}
