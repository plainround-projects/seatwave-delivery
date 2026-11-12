package io.plainround.seatwave.delivery.migration;

import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Behavior;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Column;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Schema;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Walks a schema change step by step, checking at every step that every behavior that can be
 * running works against the schema and the data: the rename of bookings.price to price_paid done
 * with expand and contract over four releases, then done wrong three ways.
 */
public final class MigrationCli {

  /** One step of a plan, in the order the pipeline runs them. */
  public sealed interface Step permits Migrate, Backfill, Rollout, LockFlag {}

  /**
   * A Flyway migration: its version within this module (3 for V35_3), its label, and the schema
   * after it. Flyway applies versions in ascending order only, so a lower version after a higher
   * one is refused.
   */
  public record Migrate(int version, String label, Schema after) implements Step {}

  /** The batched job that copies the old column into the new one. */
  public record Backfill(String column) implements Step {}

  /** A rolling update: the new behaviors run beside the old ones until the old are gone. */
  public record Rollout(String label, Set<Behavior> next) implements Step {}

  /** The flag is set on everywhere and can no longer be turned off: one behavior is gone. */
  public record LockFlag(String label, Behavior retired) implements Step {}

  public static final Behavior V1 = new Behavior("v1", Set.of("price"), Set.of("price"));
  public static final Behavior V2 =
      new Behavior("v2", Set.of("price"), Set.of("price", "price_paid"));
  public static final Behavior V3_FLAG_OFF =
      new Behavior("v3 flag off", Set.of("price"), Set.of("price", "price_paid"));
  public static final Behavior V3_FLAG_ON =
      new Behavior("v3 flag on", Set.of("price_paid"), Set.of("price", "price_paid"));
  public static final Behavior V4 = new Behavior("v4", Set.of("price_paid"), Set.of("price_paid"));
  public static final Behavior V5 = new Behavior("v5", Set.of("price_paid"), Set.of("price_paid"));

  static final Schema BEFORE =
      new Schema(Map.of("price", new Column("price", true)), Set.of("price"));
  static final Schema EXPANDED =
      new Schema(
          Map.of("price", new Column("price", true), "price_paid", new Column("price_paid", false)),
          Set.of("price"));
  static final Schema SWITCHED =
      new Schema(
          Map.of("price", new Column("price", false), "price_paid", new Column("price_paid", true)),
          Set.of("price", "price_paid"));
  static final Schema CONTRACTED =
      new Schema(Map.of("price_paid", new Column("price_paid", true)), Set.of("price_paid"));

  // V35_3 adds CHECK (price_paid IS NOT NULL) NOT VALID. NOT VALID skips the old rows only: every
  // new row must pass at once, so from here an insert without price_paid fails, as for a NOT NULL
  // column, and v1 can no longer come back. Old rows are not checked yet; the backfill filled them.
  static final Schema CHECK_ADDED =
      new Schema(
          Map.of("price", new Column("price", true), "price_paid", new Column("price_paid", true)),
          Set.of("price", "price_paid"));

  public static final List<Step> EXPAND_AND_CONTRACT =
      List.of(
          new Migrate(1, "release 1: V35_1 add price_paid, nullable (expand)", EXPANDED),
          new Migrate(2, "release 1: V35_2 create the batched backfill procedure", EXPANDED),
          new Rollout("release 1: roll out v2 (writes both, reads price)", Set.of(V2)),
          new Backfill("price_paid"),
          new Migrate(
              3, "release 2: V35_3 add CHECK (price_paid IS NOT NULL) NOT VALID", CHECK_ADDED),
          new Migrate(
              4,
              "release 2: V35_4 validate, price_paid SET NOT NULL, price DROP NOT NULL",
              SWITCHED),
          new Rollout(
              "release 2: roll out v3 (writes both; flag bookings.read-price-paid picks the read)",
              Set.of(V3_FLAG_OFF, V3_FLAG_ON)),
          new LockFlag(
              "flag bookings.read-price-paid on at 100 percent, then locked on", V3_FLAG_OFF),
          new Rollout("release 3: roll out v4 (reads and writes price_paid only)", Set.of(V4)),
          new Migrate(5, "release 4: V35_5 drop price (contract)", CONTRACTED),
          new Rollout("release 4: roll out v5 (the flag's code deleted)", Set.of(V5)));
  public static final List<Step> ONE_STEP_RENAME =
      List.of(
          new Migrate(
              1,
              "one release: RENAME COLUMN price TO price_paid (Flyway on the first new pod)",
              CONTRACTED),
          new Rollout("one release: roll out v4", Set.of(V4)));
  public static final List<Step> BACKFILL_TOO_EARLY =
      List.of(
          new Migrate(1, "release 1: V35_1 add price_paid, nullable (expand)", EXPANDED),
          new Backfill("price_paid"),
          new Rollout("release 1: roll out v2", Set.of(V2)));
  public static final List<Step> CONTRACT_TOO_EARLY =
      List.of(
          new Migrate(2, "release 1: V35_1 and V35_2 add price_paid and the backfill", EXPANDED),
          new Rollout("release 1: roll out v2", Set.of(V2)),
          new Backfill("price_paid"),
          new Migrate(4, "release 2: V35_3 and V35_4", SWITCHED),
          new Rollout("release 2: roll out v3", Set.of(V3_FLAG_OFF, V3_FLAG_ON)),
          new Migrate(5, "release 3: V35_5 drop price together with v4", CONTRACTED),
          new Rollout("release 3: roll out v4", Set.of(V4)));

  // A branch that held V35_2 merges after V35_3 has reached the database: V35_2 arrives late.
  // Flyway refuses it (outOfOrder is off by default), and so does the check.
  public static final List<Step> OUT_OF_ORDER =
      List.of(
          new Migrate(1, "release 1: V35_1 add price_paid, nullable (expand)", EXPANDED),
          new Rollout("release 1: roll out v2", Set.of(V2)),
          new Backfill("price_paid"),
          new Migrate(
              3, "release 2: V35_3 add CHECK (price_paid IS NOT NULL) NOT VALID", CHECK_ADDED),
          new Migrate(2, "release 2: V35_2 arrives after V35_3", CHECK_ADDED));

  // Module 33's rollout: 3 pods replaced one at a time (maxSurge 1, maxUnavailable 0) in 77 s.
  private static final int PODS = 3;
  private static final double ROLLOUT_SECONDS = 77;
  // Assumed: 10 percent of Module 33's 3,000 requests a second read or write bookings.price.
  private static final double BOOKINGS_REQUESTS_PER_SECOND = 300;
  // Backfill sizing: Module 3's 500,000 bookings a day for a year, over Module 18's 64 shards.
  private static final long BOOKINGS_PER_DAY = 500_000;
  private static final int DAYS = 365;
  private static final int SHARDS = 64;
  private static final int BATCH_ROWS = 10_000;
  private static final double SECONDS_PER_BATCH = 0.1;
  private static final int SHARDS_IN_PARALLEL = 8;

  private MigrationCli() {}

  /** Prints the four plans and the two estimates to standard output. */
  public static void main(String[] arguments) {
    run(System.out);
  }

  /** Prints the four plans and the two estimates; nothing is random, so every run is the same. */
  public static void run(PrintStream out) {
    out.println(
        "PLAN 1. EXPAND AND CONTRACT: rename bookings.price to price_paid over four releases");
    walk(EXPAND_AND_CONTRACT, out);
    out.println();
    out.println("PLAN 2. THE SAME RENAME IN ONE RELEASE");
    walk(ONE_STEP_RENAME, out);
    double secondsPerPod = ROLLOUT_SECONDS / PODS;
    double oldPodSeconds = 0;
    for (int replaced = 0; replaced < PODS; replaced++) {
      oldPodSeconds += secondsPerPod * (PODS - replaced) / PODS;
    }
    out.printf(
        Locale.ROOT,
        "  estimated failed requests: %.0f a second x %.1f s of old-pod traffic (%.1f s per pod,"
            + " shares 3/3, 2/3, 1/3) = %,.0f%n",
        BOOKINGS_REQUESTS_PER_SECOND,
        oldPodSeconds,
        secondsPerPod,
        BOOKINGS_REQUESTS_PER_SECOND * oldPodSeconds);
    out.println();
    out.println("PLAN 3. BACKFILL BEFORE THE OLD CODE IS GONE");
    walk(BACKFILL_TOO_EARLY, out);
    out.println();
    out.println("PLAN 4. CONTRACT IN THE SAME RELEASE AS THE LAST CODE CHANGE");
    walk(CONTRACT_TOO_EARLY, out);
    out.println();
    long rowsPerShard = (BOOKINGS_PER_DAY * DAYS + SHARDS - 1) / SHARDS;
    long batches = (rowsPerShard + BATCH_ROWS - 1) / BATCH_ROWS;
    double secondsPerShard = batches * SECONDS_PER_BATCH;
    out.printf(
        Locale.ROOT,
        "BACKFILL (estimated): %,d rows a shard, %d batches of %,d at %.1f s = %.1f s a shard; %d"
            + " shards, %d at a time: %.0f s%n",
        rowsPerShard,
        batches,
        BATCH_ROWS,
        SECONDS_PER_BATCH,
        secondsPerShard,
        SHARDS,
        SHARDS_IN_PARALLEL,
        secondsPerShard * SHARDS / SHARDS_IN_PARALLEL);
  }

  /** Runs the steps without printing; returns every problem found, empty when all are safe. */
  public static List<String> problemsOf(List<Step> steps) {
    return walk(
        steps, new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
  }

  /**
   * What the check found at one step: the behaviors it checked (during a rollout, the old and the
   * new together), what is running afterwards, the release a rollback would bring back, and the
   * problems of each.
   *
   * @param label the step as the pipeline names it
   * @param checked every behavior that can be running during the step
   * @param running the behaviors running once the step is done
   * @param rollbackTarget the behaviors a rollback would bring back (equal to running when there is
   *     nothing to roll back to)
   * @param problems why the step breaks something that is running; empty when it is safe
   * @param rollbackProblems why the rollback target would fail now; empty when the way back is open
   */
  public record StepCheck(
      String label,
      Set<Behavior> checked,
      Set<Behavior> running,
      Set<Behavior> rollbackTarget,
      List<String> problems,
      List<String> rollbackProblems) {
    /** Copies every collection. */
    public StepCheck {
      checked = Set.copyOf(checked);
      running = Set.copyOf(running);
      rollbackTarget = Set.copyOf(rollbackTarget);
      problems = List.copyOf(problems);
      rollbackProblems = List.copyOf(rollbackProblems);
    }

    /** Whether there is an earlier release to roll back to at this step. */
    public boolean hasWayBack() {
      return !rollbackTarget.equals(running);
    }
  }

  /**
   * Runs the steps and prints, after each, any problem and whether the last release can be undone.
   *
   * @return every problem found, in order; empty when every step is safe for what is running
   */
  public static List<String> walk(List<Step> steps, PrintStream out) {
    List<String> allProblems = new ArrayList<>();
    for (StepCheck check : trace(steps)) {
      allProblems.addAll(check.problems());
      out.println("  " + check.label());
      check.problems().forEach(problem -> out.println("      BREAKS: " + problem));
      if (check.problems().isEmpty()) {
        out.println("      ok for " + labels(check.checked()));
      }
      if (check.hasWayBack()) {
        out.println(
            "      roll back to "
                + labels(check.rollbackTarget())
                + ": "
                + (check.rollbackProblems().isEmpty()
                    ? "possible"
                    : "impossible, roll forward (" + check.rollbackProblems().getFirst() + ")"));
      }
    }
    return allProblems;
  }

  /**
   * Checks every step against what can be running, in order, without printing: the model a stack
   * test compares with PostgreSQL step by step.
   */
  public static List<StepCheck> trace(List<Step> steps) {
    Schema schema = BEFORE;
    int appliedVersion = 0;
    Set<Behavior> running = Set.of(V1);
    Set<Behavior> previous = Set.of(V1);
    List<StepCheck> checks = new ArrayList<>();
    for (Step step : steps) {
      List<String> problems;
      String label;
      Set<Behavior> checked = running;
      switch (step) {
        case Migrate migrate -> {
          label = migrate.label();
          if (migrate.version() <= appliedVersion) {
            // Flyway's default: a version lower than the highest applied one is never run.
            problems =
                List.of(
                    String.format(
                        Locale.ROOT,
                        "V35_%d is out of order: V35_%d is already applied",
                        migrate.version(),
                        appliedVersion));
          } else {
            appliedVersion = migrate.version();
            schema = keepUnfilled(migrate.after(), schema);
            problems = SchemaCompatibility.problems(running, schema);
          }
        }
        case Backfill backfill -> {
          label =
              String.format(
                  Locale.ROOT, "job: backfill %s in batches of %,d", backfill.column(), BATCH_ROWS);
          problems = SchemaCompatibility.backfillProblems(running, backfill.column());
          Set<String> filled = new LinkedHashSet<>(schema.filledColumns());
          filled.add(backfill.column());
          schema = new Schema(schema.columns(), filled);
        }
        case Rollout rollout -> {
          label = rollout.label();
          checked = union(running, rollout.next());
          problems = SchemaCompatibility.problems(checked, schema);
          previous = running;
          running = rollout.next();
          schema = unfillWhatIsNoLongerWritten(schema, running);
        }
        case LockFlag lockFlag -> {
          label = lockFlag.label();
          Set<Behavior> remaining = new LinkedHashSet<>(running);
          remaining.remove(lockFlag.retired());
          running = Set.copyOf(remaining);
          previous = running;
          checked = running;
          problems = List.of();
        }
      }
      checks.add(
          new StepCheck(
              label,
              checked,
              running,
              previous,
              problems,
              SchemaCompatibility.problems(previous, schema)));
    }
    return checks;
  }

  private static Schema keepUnfilled(Schema after, Schema before) {
    Set<String> filled = new LinkedHashSet<>(after.filledColumns());
    filled.removeIf(
        column -> before.columns().containsKey(column) && !before.filledColumns().contains(column));
    return new Schema(after.columns(), filled);
  }

  private static Schema unfillWhatIsNoLongerWritten(Schema schema, Set<Behavior> running) {
    Set<String> filled = new LinkedHashSet<>(schema.filledColumns());
    filled.removeIf(
        column -> running.stream().anyMatch(behavior -> !behavior.writes().contains(column)));
    return new Schema(schema.columns(), filled);
  }

  private static Set<Behavior> union(Set<Behavior> first, Set<Behavior> second) {
    Set<Behavior> both = new LinkedHashSet<>(first);
    both.addAll(second);
    return both;
  }

  private static String labels(Set<Behavior> behaviors) {
    return behaviors.stream().map(Behavior::label).sorted().toList().toString();
  }
}
