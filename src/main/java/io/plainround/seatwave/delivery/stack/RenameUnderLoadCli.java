package io.plainround.seatwave.delivery.stack;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.delivery.migration.MigrationCli;
import io.plainround.seatwave.delivery.migration.MigrationCli.Backfill;
import io.plainround.seatwave.delivery.migration.MigrationCli.LockFlag;
import io.plainround.seatwave.delivery.migration.MigrationCli.Migrate;
import io.plainround.seatwave.delivery.migration.MigrationCli.Rollout;
import io.plainround.seatwave.delivery.migration.MigrationCli.Step;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Behavior;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Lab 3 on PostgreSQL: the model's two plans, step for step, on Module 13's bookings table under
 * continuous open-loop load. Each request goes to a random pod and runs one of that pod's behaviors
 * (half confirms, half reads of a random booking) through {@link ReleaseQueries}; a rollout
 * replaces one pod at a time; migrations and the backfill run while the load goes on, as the
 * pipeline runs them. Prints what was sent, the mix, and every failure by its reason.
 */
public final class RenameUnderLoadCli {

  private static final double MILLIS_PER_SECOND = 1_000.0;
  // A booking read from before the run or written during it: plenty for 15 minutes at 2,000/s.
  private static final int SPARE_SEATS = 2_000_000;

  private final StackSettings settings;
  private final LabDatabase database;
  private final PrintStream out;
  private final AtomicLong nextSeat = new AtomicLong();
  private final List<UUID> bookingIds = new ArrayList<>();
  private final Map<String, AtomicLong> mix = new TreeMap<>();
  private volatile List<Set<Behavior>> pods;

  private RenameUnderLoadCli(StackSettings settings, LabDatabase database, PrintStream out) {
    this.settings = settings;
    this.database = database;
    this.out = out;
  }

  /** Runs both plans on the lab database named by the settings file in the first argument. */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      System.err.println("usage: renameUnderLoad <stack.properties>");
      System.exit(2);
    }
    Result<StackSettings, List<String>> loaded = StackSettings.load(Path.of(arguments[0]));
    if (loaded.isError()) {
      loaded.peekError(problems -> problems.forEach(problem -> System.err.println(problem)));
      System.exit(2);
    }
    StackSettings settings = loaded.discardError().orElseThrow();
    try (LabDatabase database = LabDatabase.open(settings, Path.of(""))) {
      StackSettings.Load load = settings.load();
      System.out.printf(
          Locale.ROOT,
          "RENAME UNDER LOAD on PostgreSQL: %d requests a second (half confirms, half reads), %d"
              + " pods, one replaced every %.1f s, %d quiet seconds after each rollout; %,d"
              + " bookings to start%n",
          load.requestsPerSecond(),
          load.pods(),
          (double) load.rolloutSeconds() / load.pods(),
          load.quietSeconds(),
          load.seedBookings());
      new RenameUnderLoadCli(settings, database, System.out)
          .run("expand and contract", MigrationCli.EXPAND_AND_CONTRACT, "db/migration");
      new RenameUnderLoadCli(settings, database, System.out)
          .run("one-step rename", MigrationCli.ONE_STEP_RENAME, "db/one-step-rename");
    }
  }

  private void run(String plan, List<Step> steps, String folder) throws Exception {
    StackSettings.Load load = settings.load();
    database.clean();
    database.migrate(folder, "3");
    database.seed(load.seedBookings(), SPARE_SEATS);
    nextSeat.set(load.seedBookings() + 1L);
    loadBookingIds();
    pods = List.copyOf(java.util.Collections.nCopies(load.pods(), Set.of(MigrationCli.V1)));
    out.println();
    out.println(plan.toUpperCase(Locale.ROOT));
    long started = System.nanoTime();
    List<OpenLoad.Sample> samples;
    try (OpenLoad openLoad =
        new OpenLoad(load.requestsPerSecond(), load.maxInFlight(), this::oneRequest)) {
      openLoad.start();
      for (Step step : steps) {
        runStep(step, folder);
      }
      samples = openLoad.stop();
    }
    double seconds = (System.nanoTime() - started) / (double) TimeUnit.SECONDS.toNanos(1);
    report(samples, seconds);
  }

  private void runStep(Step step, String folder) throws Exception {
    long started = System.nanoTime();
    String label;
    switch (step) {
      case Migrate migrate -> {
        database.migrate(folder, "35." + migrate.version());
        label = migrate.label();
      }
      case Backfill backfill -> {
        long batches = backfill();
        label = "job: backfill " + backfill.column() + " (" + batches + " batches)";
      }
      case Rollout rollout -> {
        StackSettings.Load load = settings.load();
        long podMillis = Math.round(load.rolloutSeconds() * MILLIS_PER_SECOND / load.pods());
        for (int replaced = 0; replaced < load.pods(); replaced++) {
          Thread.sleep(podMillis);
          List<Set<Behavior>> next = new ArrayList<>(pods);
          next.set(replaced, rollout.next());
          pods = List.copyOf(next);
        }
        Thread.sleep(TimeUnit.SECONDS.toMillis(load.quietSeconds()));
        label = rollout.label();
      }
      case LockFlag lockFlag -> {
        pods = pods.stream().map(behaviors -> without(behaviors, lockFlag.retired())).toList();
        label = lockFlag.label();
      }
    }
    out.printf(
        Locale.ROOT,
        "  %-86s %8.1f s%n",
        label,
        (System.nanoTime() - started) / (double) TimeUnit.SECONDS.toNanos(1));
  }

  private long backfill() throws SQLException {
    StackSettings.Scale scale = settings.scale();
    return database.backfill(scale.batchRows(), scale.pauseSeconds());
  }

  private Optional<String> oneRequest() throws SQLException {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    List<Set<Behavior>> current = pods;
    List<Behavior> choices = List.copyOf(current.get(random.nextInt(current.size())));
    Behavior behavior = choices.get(random.nextInt(choices.size()));
    if (random.nextBoolean()) {
      count(behavior.label() + " confirm");
      UUID bookingId =
          ReleaseQueries.confirm(
              database.app(),
              behavior,
              ReleaseQueries.seatAt(nextSeat.getAndIncrement()),
              Instant.now());
      synchronized (bookingIds) {
        bookingIds.add(bookingId);
      }
      return Optional.empty();
    }
    count(behavior.label() + " read");
    UUID bookingId;
    synchronized (bookingIds) {
      bookingId = bookingIds.get(random.nextInt(bookingIds.size()));
    }
    return ReleaseQueries.read(database.app(), behavior, bookingId)
        ? Optional.empty()
        : Optional.of("read: the row has no value in " + behavior.reads());
  }

  private void count(String kind) {
    synchronized (mix) {
      mix.computeIfAbsent(kind, ignored -> new AtomicLong()).incrementAndGet();
    }
  }

  private void loadBookingIds() throws SQLException {
    synchronized (bookingIds) {
      bookingIds.clear();
      try (Connection connection = database.app().getConnection();
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT booking_id FROM bookings")) {
        while (rows.next()) {
          bookingIds.add(rows.getObject(1, UUID.class));
        }
      }
    }
    synchronized (mix) {
      mix.clear();
    }
  }

  private void report(List<OpenLoad.Sample> samples, double seconds) {
    long failed = samples.stream().filter(sample -> sample.failure().isPresent()).count();
    out.printf(
        Locale.ROOT,
        "  %,d requests sent in %.1f s (%.1f a second), %,d served, %,d failed%n",
        samples.size(),
        seconds,
        samples.size() / seconds,
        samples.size() - failed,
        failed);
    synchronized (mix) {
      out.println(
          "  mix: "
              + mix.entrySet().stream()
                  .map(entry ->
                      String.format(Locale.ROOT, "%s %,d", entry.getKey(), entry.getValue().get()))
                  .collect(Collectors.joining("; ")));
    }
    Map<String, Long> reasons =
        samples.stream()
            .flatMap(sample -> sample.failure().stream())
            .collect(Collectors.groupingBy(reason -> reason, TreeMap::new, Collectors.counting()));
    reasons.forEach(
        (reason, count) -> out.printf(Locale.ROOT, "  failed %,7d: %s%n", count, reason));
  }

  private static Set<Behavior> without(Set<Behavior> behaviors, Behavior retired) {
    return behaviors.size() > 1 && behaviors.contains(retired)
        ? behaviors.stream()
            .filter(behavior -> !behavior.equals(retired))
            .collect(Collectors.toUnmodifiableSet())
        : behaviors;
  }
}
