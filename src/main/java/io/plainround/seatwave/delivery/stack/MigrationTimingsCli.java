package io.plainround.seatwave.delivery.stack;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.delivery.migration.MigrationCli;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Behavior;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The five migrations and the backfill on a bookings table at one shard's size, with a probe
 * writing new bookings (as the release running at that step writes them) all along. For each step
 * it prints how long it took and what the probe saw meanwhile, beside the probe's own baseline just
 * before the step: so a lock shows as a stall, and the backfill's load as slower writes.
 */
public final class MigrationTimingsCli {

  private static final double NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1);
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;
  private static final int PROBE_ROOM = 512;
  // Spare seats for the probe: 150 writes a second for up to 40 minutes.
  private static final int SPARE_SEATS = 400_000;

  private final StackSettings settings;
  private final LabDatabase database;
  private final PrintStream out;
  private final AtomicLong nextSeat = new AtomicLong();
  private volatile Behavior probeBehavior = MigrationCli.V1;

  private MigrationTimingsCli(StackSettings settings, LabDatabase database, PrintStream out) {
    this.settings = settings;
    this.database = database;
    this.out = out;
  }

  /** Runs the timing on the lab database named by the settings file in the first argument. */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      System.err.println("usage: migrationTimings <stack.properties>");
      System.exit(2);
    }
    Result<StackSettings, List<String>> loaded = StackSettings.load(Path.of(arguments[0]));
    if (loaded.isError()) {
      loaded.peekError(problems -> problems.forEach(problem -> System.err.println(problem)));
      System.exit(2);
    }
    StackSettings settings = loaded.discardError().orElseThrow();
    try (LabDatabase database = LabDatabase.open(settings, Path.of(""))) {
      new MigrationTimingsCli(settings, database, System.out).run();
    }
  }

  private void run() throws Exception {
    StackSettings.Scale scale = settings.scale();
    database.clean();
    database.migrate("db/migration", "3");
    long loadStarted = System.nanoTime();
    database.seed(scale.bookings(), SPARE_SEATS);
    nextSeat.set(scale.bookings() + 1L);
    out.printf(
        Locale.ROOT,
        "MIGRATION TIMINGS on PostgreSQL: %,d bookings loaded (with their holds) and vacuumed in"
            + " %.1f s; table %s, with indexes %s%n",
        scale.bookings(),
        seconds(System.nanoTime() - loadStarted),
        size("pg_table_size('bookings')"),
        size("pg_total_relation_size('bookings')"));
    out.printf(
        Locale.ROOT,
        "probe: %d new bookings a second, written as the release running at each step writes them;"
            + " baseline = the %d s before each step%n",
        scale.probeWritesPerSecond(),
        scale.baselineSeconds());
    out.printf(
        Locale.ROOT,
        "%-58s %10s %10s | %9s %9s %9s %7s | %9s %9s%n",
        "step (probe writes as)",
        "took ms",
        "flyway ms",
        "p50 ms",
        "p99 ms",
        "max ms",
        "failed",
        "base p99",
        "base max");
    List<OpenLoad.Sample> all;
    try (OpenLoad probe =
        new OpenLoad(scale.probeWritesPerSecond(), PROBE_ROOM, this::probeWrite)) {
      probe.start();
      // Warm-up, not measured: the JVM, the pool and the first Flyway run settle first.
      Thread.sleep(TimeUnit.SECONDS.toMillis(scale.baselineSeconds()));
      database.migrate("db/migration", "3");
      List<Window> windows = new ArrayList<>();
      windows.add(migration("V35_1 add price_paid", "35.1"));
      windows.add(migration("V35_2 create the backfill procedure", "35.2"));
      probeBehavior = MigrationCli.V2;
      windows.add(backfill());
      windows.add(migration("V35_3 CHECK price_paid NOT VALID", "35.3"));
      windows.add(migration("V35_4 VALIDATE, SET NOT NULL, price DROP NOT NULL", "35.4"));
      probeBehavior = MigrationCli.V4;
      windows.add(migration("V35_5 drop price", "35.5"));
      Thread.sleep(TimeUnit.SECONDS.toMillis(scale.baselineSeconds()));
      all = probe.stop();
      for (Window window : windows) {
        print(window, all);
      }
    }
    long failed = all.stream().filter(sample -> sample.failure().isPresent()).count();
    out.printf(
        Locale.ROOT,
        "probe in all: %,d writes, %,d failed%s%n",
        all.size(),
        failed,
        all.stream()
            .flatMap(sample -> sample.failure().stream())
            .findFirst()
            .map(reason -> " (first: " + reason + ")")
            .orElse(""));
  }

  private Window migration(String label, String version) throws InterruptedException {
    Thread.sleep(TimeUnit.SECONDS.toMillis(settings.scale().baselineSeconds()));
    String probeLabel = probeBehavior.label();
    long started = System.nanoTime();
    MigrateResult result = database.migrate("db/migration", version);
    long ended = System.nanoTime();
    long flywayMillis =
        result.migrations.stream().mapToLong(migration -> migration.executionTime).sum();
    return new Window(label + " (" + probeLabel + ")", started, ended, Optional.of(flywayMillis));
  }

  private Window backfill() throws InterruptedException, SQLException {
    Thread.sleep(TimeUnit.SECONDS.toMillis(settings.scale().baselineSeconds()));
    StackSettings.Scale scale = settings.scale();
    long started = System.nanoTime();
    long notices = database.backfill(scale.batchRows(), scale.pauseSeconds());
    long ended = System.nanoTime();
    return new Window(
        String.format(
            Locale.ROOT,
            "backfill %,d a batch, pause %s s: %d batches (%s)",
            scale.batchRows(),
            scale.pauseSeconds().toPlainString(),
            notices,
            probeBehavior.label()),
        started,
        ended,
        Optional.empty());
  }

  private Optional<String> probeWrite() throws SQLException {
    ReleaseQueries.confirm(
        database.app(),
        probeBehavior,
        ReleaseQueries.seatAt(nextSeat.getAndIncrement()),
        Instant.now());
    return Optional.empty();
  }

  private void print(Window window, List<OpenLoad.Sample> all) {
    long baselineStart =
        window.started() - TimeUnit.SECONDS.toNanos(settings.scale().baselineSeconds());
    List<OpenLoad.Sample> during = between(all, window.started(), window.ended());
    List<OpenLoad.Sample> baseline = between(all, baselineStart, window.started());
    out.printf(
        Locale.ROOT,
        "%-58s %10.1f %10s | %9.1f %9.1f %9.1f %7d | %9.1f %9.1f%n",
        window.label(),
        (window.ended() - window.started()) / NANOS_PER_MILLI,
        window.flywayMillis().map(String::valueOf).orElse("-"),
        percentile(during, PERCENTILE_50),
        percentile(during, PERCENTILE_99),
        percentile(during, 1),
        during.stream().filter(sample -> sample.failure().isPresent()).count(),
        percentile(baseline, PERCENTILE_99),
        percentile(baseline, 1));
  }

  /**
   * The writes that were due while the step ran, plus the one write due just before it that its
   * lock may have caught: a migration of a few milliseconds would otherwise overlap no write.
   */
  private static List<OpenLoad.Sample> between(List<OpenLoad.Sample> all, long from, long to) {
    return all.stream()
        .filter(sample ->
            sample.startNanos() < to && sample.startNanos() + sample.durationNanos() > from)
        .toList();
  }

  private static double percentile(List<OpenLoad.Sample> samples, double fraction) {
    if (samples.isEmpty()) {
      return 0;
    }
    List<Long> durations =
        samples.stream()
            .map(OpenLoad.Sample::durationNanos)
            .sorted(Comparator.naturalOrder())
            .toList();
    int index = (int) Math.ceil(fraction * durations.size()) - 1;
    return durations.get(Math.max(0, index)) / NANOS_PER_MILLI;
  }

  private String size(String expression) throws SQLException {
    try (Connection connection = database.owner().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + LabDatabase.SCHEMA);
      try (ResultSet row = statement.executeQuery("SELECT pg_size_pretty(" + expression + ")")) {
        row.next();
        return row.getString(1);
      }
    }
  }

  private static double seconds(long nanos) {
    return nanos / (double) TimeUnit.SECONDS.toNanos(1);
  }

  /** One step's time span on {@link System#nanoTime()}, and Flyway's own execution time. */
  private record Window(String label, long started, long ended, Optional<Long> flywayMillis) {}
}
