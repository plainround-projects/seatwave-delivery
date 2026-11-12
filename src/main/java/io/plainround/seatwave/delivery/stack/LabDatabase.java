package io.plainround.seatwave.delivery.stack;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The lab database: two pools (the owner runs Flyway and the backfill, the application role runs
 * the releases' queries), Flyway over Module 13's schema and this module's migrations, and the
 * seeding of a bookings table. Created once per run and closed at its end; its pools are safe to
 * share between threads.
 */
public final class LabDatabase implements AutoCloseable {

  /** The schema every table lives in; the application role's connections default to it. */
  public static final String SCHEMA = "seatwave";

  // Module 13's arena: 20 sections of 1,000 seats per event.
  static final int SECTIONS_PER_EVENT = 20;
  static final int SEATS_PER_SECTION = 1_000;
  static final int SEATS_PER_EVENT = SECTIONS_PER_EVENT * SEATS_PER_SECTION;
  // Module 13's customers and Module 1's 300-second hold; a booked hold ended a minute in.
  private static final int CUSTOMERS = 1_000;
  private static final int OWNER_POOL = 2;

  private final HikariDataSource owner;
  private final HikariDataSource app;
  private final Path projectFolder;

  private LabDatabase(HikariDataSource owner, HikariDataSource app, Path projectFolder) {
    this.owner = owner;
    this.app = app;
    this.projectFolder = projectFolder;
  }

  /** Opens both pools; fails at once if the database does not answer within the timeout. */
  public static LabDatabase open(StackSettings settings, Path projectFolder) {
    Objects.requireNonNull(settings, "settings");
    HikariDataSource owner =
        pool(settings, settings.ownerUser(), settings.ownerPassword(), OWNER_POOL, "owner");
    try {
      HikariDataSource app =
          pool(settings, settings.appUser(), settings.appPassword(), settings.poolSize(), "app");
      return new LabDatabase(owner, app, projectFolder.toAbsolutePath());
    } catch (RuntimeException failure) {
      owner.close();
      throw failure;
    }
  }

  /** The application role's pool: what a running release uses. */
  public HikariDataSource app() {
    return app;
  }

  /** The owner's pool: what the pipeline's migrate step and the backfill job use. */
  public HikariDataSource owner() {
    return owner;
  }

  /** Drops everything in the schema, so each run starts from an empty database. */
  public void clean() {
    flyway(List.of(), MigrationVersion.LATEST).clean();
  }

  /**
   * Applies migrations up to and including {@code target} ("3" for Module 13's schema, "35.4"),
   * from Module 13's folder and one more folder: db/migration for the five-step rename, or
   * db/one-step-rename for the wrong way.
   */
  public MigrateResult migrate(String folder, String target) {
    return flyway(List.of(folder), MigrationVersion.fromVersion(target)).migrate();
  }

  /**
   * Flyway over Module 13's folder and the given folders, as the pipeline's migrate step runs it.
   */
  public Flyway flyway(List<String> folders, MigrationVersion target) {
    String[] locations =
        Stream.concat(Stream.of("db/module-13"), folders.stream())
            .map(folder -> "filesystem:" + projectFolder.resolve(folder))
            .toArray(String[]::new);
    return Flyway.configure()
        .dataSource(owner)
        .schemas(SCHEMA)
        .locations(locations)
        .placeholders(Map.of("appRole", "seatwave_app"))
        .target(target)
        // A lab database is rebuilt for every run; production never cleans.
        .cleanDisabled(false)
        .load();
  }

  /**
   * Loads Module 13's catalog and {@code bookings} booked seats (each with its CONFIRMED hold and a
   * price of 80.00 USD), with room for {@code spareSeats} more seats to book during the run. Runs
   * as the owner in one transaction, then VACUUM ANALYZE so the table starts as a running system's
   * would: visibility map set and statistics current.
   */
  public void seed(int bookings, int spareSeats) throws SQLException {
    int events = Math.ceilDiv(bookings + spareSeats, SEATS_PER_EVENT);
    try (Connection connection = owner.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET search_path TO " + SCHEMA);
        statement.execute("INSERT INTO venues (venue_id, name) VALUES (1, 'Arena')");
        execute(
            connection,
            """
            INSERT INTO events (event_id, venue_id, name, starts_at)
            SELECT event_id, 1, 'Arena Night ' || event_id, TIMESTAMPTZ '2026-12-08 20:00:00+00'
              FROM generate_series(1, ?) AS events(event_id)
            """,
            events);
        execute(
            connection,
            """
            INSERT INTO sections (event_id, section_code, price, currency)
            SELECT event_id, chr(64 + section_index), 80.00, 'USD'
              FROM generate_series(1, ?) AS events(event_id),
                   generate_series(1, 20) AS sections(section_index)
            """,
            events);
        execute(
            connection,
            """
            INSERT INTO seats (event_id, seat_label, section_code, seat_number)
            SELECT event_id, chr(64 + section_index) || '-' || seat_number, chr(64 + section_index),
                   seat_number
              FROM generate_series(1, ?) AS events(event_id),
                   generate_series(1, 20) AS sections(section_index),
                   generate_series(1, 1000) AS seats(seat_number)
            """,
            events);
        execute(
            connection,
            """
            INSERT INTO customers (customer_ref, created_at)
            SELECT 'fan_' || customer_index, TIMESTAMPTZ '2026-11-01 00:00:00+00'
              FROM generate_series(1, ?) AS customers(customer_index)
            """,
            CUSTOMERS);
        execute(
            connection,
            """
            INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at,
                               expires_at, ended_at)
            SELECT gen_random_uuid(), 1 + (seat_index - 1) / 20000,
                   chr(65 + ((seat_index - 1) % 20000) / 1000) || '-' || ((seat_index - 1) % 1000 + 1),
                   1 + seat_index % 1000, 'CONFIRMED', TIMESTAMPTZ '2026-11-08 10:00:00+00',
                   TIMESTAMPTZ '2026-11-08 10:05:00+00', TIMESTAMPTZ '2026-11-08 10:01:00+00'
              FROM generate_series(1, ?) AS booked(seat_index)
            """,
            bookings);
        statement.execute(
            """
            INSERT INTO bookings (booking_id, hold_id, event_id, seat_label, customer_id, price,
                                  currency, confirmed_at)
            SELECT gen_random_uuid(), hold_id, event_id, seat_label, customer_id, 80.00, 'USD',
                   ended_at
              FROM holds
            """);
      }
      connection.commit();
      connection.setAutoCommit(true);
      try (Statement statement = connection.createStatement()) {
        statement.execute("VACUUM (ANALYZE) " + SCHEMA + ".holds, " + SCHEMA + ".bookings");
      }
    }
  }

  /**
   * Runs the backfill job of V35_2 as the pipeline calls it: one CALL outside any transaction, so
   * the procedure can commit each batch.
   *
   * @return the batches it reported, the last of which copied 0 rows
   */
  public long backfill(int batchRows, BigDecimal pauseSeconds) throws SQLException {
    try (Connection connection = owner.getConnection();
        CallableStatement call = connection.prepareCall("CALL backfill_price_paid(?, ?)")) {
      call.setInt(1, batchRows);
      call.setBigDecimal(2, pauseSeconds);
      call.execute();
      long notices = 0;
      for (SQLWarning notice = call.getWarnings();
          notice != null;
          notice = notice.getNextWarning()) {
        notices++;
      }
      return notices;
    }
  }

  /** Closes both pools. */
  @Override
  public void close() {
    try (owner) {
      app.close();
    }
  }

  private static void execute(Connection connection, String sql, int value) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, value);
      statement.execute();
    }
  }

  private static HikariDataSource pool(
      StackSettings settings, String user, String password, int size, String name) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.url());
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(size);
    config.setMinimumIdle(size);
    config.setPoolName("seatwave-" + name);
    config.setConnectionTimeout(settings.connectTimeoutMillis());
    // Every connection of the application role starts in SeatWave's schema.
    config.setConnectionInitSql("SET search_path TO " + SCHEMA);
    return new HikariDataSource(config);
  }
}
