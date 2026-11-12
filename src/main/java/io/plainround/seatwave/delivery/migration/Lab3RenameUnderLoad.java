package io.plainround.seatwave.delivery.migration;

import io.plainround.seatwave.delivery.migration.MigrationCli.Backfill;
import io.plainround.seatwave.delivery.migration.MigrationCli.LockFlag;
import io.plainround.seatwave.delivery.migration.MigrationCli.Migrate;
import io.plainround.seatwave.delivery.migration.MigrationCli.Rollout;
import io.plainround.seatwave.delivery.migration.MigrationCli.Step;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Behavior;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Column;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Lab 3: runs each plan's steps under load on an in-memory bookings table. Every second, 300
 * requests (half confirms that insert, half reads of a booking) go to the 3 pods; a rollout
 * replaces one pod every 25.7 s (Module 33's 77 s for 3 pods). A request fails when its pod's code
 * names a column that is missing, leaves out a NOT NULL column, or reads a value a row lacks.
 */
public final class Lab3RenameUnderLoad {

  private static final int PODS = 3;
  private static final double SECONDS_PER_POD = 77.0 / PODS;
  private static final int REQUESTS_PER_SECOND = 300;
  private static final int QUIET_SECONDS_BETWEEN_STEPS = 60;
  private static final int SEED_ROWS = 1_000;
  private static final long SEED = 353;

  private final Random random = new Random(SEED);
  private final List<Map<String, Long>> rows = new ArrayList<>();
  private Map<String, Column> columns = Map.of("price", new Column("price", true));
  private final List<Set<Behavior>> pods = new ArrayList<>();
  private long served;
  private long failed;

  private Lab3RenameUnderLoad() {
    for (int pod = 0; pod < PODS; pod++) {
      pods.add(Set.of(MigrationCli.V1));
    }
    for (int row = 0; row < SEED_ROWS; row++) {
      rows.add(new HashMap<>(Map.of("price", (long) row)));
    }
  }

  /** Prints both plans' served and failed requests to standard output. */
  public static void main(String[] arguments) {
    run(System.out);
  }

  /** Prints both plans' counts; the fixed seed makes every run print the same bytes. */
  public static void run(PrintStream out) {
    report(
        out,
        "expand and contract",
        new Lab3RenameUnderLoad().run(MigrationCli.EXPAND_AND_CONTRACT));
    report(out, "one-step rename", new Lab3RenameUnderLoad().run(MigrationCli.ONE_STEP_RENAME));
  }

  private Lab3RenameUnderLoad run(List<Step> steps) {
    for (Step step : steps) {
      switch (step) {
        case Migrate migrate -> migrate(migrate);
        case Backfill backfill ->
            rows.forEach(row -> row.putIfAbsent(backfill.column(), row.get("price")));
        case Rollout rollout -> {
          for (int replaced = 0; replaced < PODS; replaced++) {
            serveFor(SECONDS_PER_POD);
            pods.set(replaced, rollout.next());
          }
          serveFor(QUIET_SECONDS_BETWEEN_STEPS);
        }
        case LockFlag lockFlag ->
            pods.replaceAll(behaviors -> without(behaviors, lockFlag.retired()));
      }
    }
    return this;
  }

  private void migrate(Migrate migrate) {
    boolean isRename =
        columns.containsKey("price")
            && !migrate.after().columns().containsKey("price")
            && !columns.containsKey("price_paid");
    if (isRename) {
      rows.forEach(row -> row.put("price_paid", row.remove("price")));
    } else if (!migrate.after().columns().containsKey("price")) {
      rows.forEach(row -> row.remove("price"));
    }
    columns = migrate.after().columns();
  }

  private void serveFor(double seconds) {
    long requests = Math.round(seconds * REQUESTS_PER_SECOND);
    for (long request = 0; request < requests; request++) {
      Set<Behavior> pod = pods.get(random.nextInt(PODS));
      List<Behavior> choices = List.copyOf(pod);
      Behavior behavior = choices.get(random.nextInt(choices.size()));
      boolean isOk = random.nextBoolean() ? insert(behavior) : read(behavior);
      served++;
      if (!isOk) {
        failed++;
      }
    }
  }

  private boolean insert(Behavior behavior) {
    if (!columns.keySet().containsAll(behavior.writes())) {
      return false;
    }
    boolean isMissingRequired =
        columns.values().stream()
            .anyMatch(column -> column.isRequired() && !behavior.writes().contains(column.name()));
    if (isMissingRequired) {
      return false;
    }
    Map<String, Long> row = new HashMap<>();
    behavior.writes().forEach(column -> row.put(column, served));
    rows.add(row);
    return true;
  }

  private boolean read(Behavior behavior) {
    Map<String, Long> row = rows.get(random.nextInt(rows.size()));
    return behavior.reads().stream()
        .allMatch(column -> columns.containsKey(column) && row.get(column) != null);
  }

  private static Set<Behavior> without(Set<Behavior> behaviors, Behavior retired) {
    return behaviors.size() > 1 && behaviors.contains(retired)
        ? Set.of(
            behaviors.stream()
                .filter(behavior -> !behavior.equals(retired))
                .findFirst()
                .orElseThrow())
        : behaviors;
  }

  private static void report(PrintStream out, String plan, Lab3RenameUnderLoad lab) {
    out.printf(
        Locale.ROOT, "%-20s %,9d requests served, %,7d failed%n", plan, lab.served, lab.failed);
  }
}
