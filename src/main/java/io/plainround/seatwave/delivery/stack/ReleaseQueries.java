package io.plainround.seatwave.delivery.stack;

import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Behavior;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * The queries a release runs against bookings, built from the same {@link Behavior} the model
 * checks: a confirm inserts a CONFIRMED hold and a booking that writes exactly the behavior's price
 * columns; a read selects exactly the columns it reads and fails on a NULL, as the code that
 * formats a price would. So the database is asked the very question the model answers. Stateless;
 * safe to call from many threads at once.
 */
public final class ReleaseQueries {

  /** The only column names a behavior may name; anything else is a programming error. */
  private static final Set<String> PRICE_COLUMNS = Set.of("price", "price_paid");

  // Module 1's 300-second hold; the booked hold ends a minute in.
  private static final Duration HOLD = Duration.ofSeconds(300);
  private static final Duration TIME_TO_CONFIRM = Duration.ofMinutes(1);
  private static final int CUSTOMERS = 1_000;
  // A probe's rows are rolled back, so any fixed time serves.
  private static final Instant PROBE_TIME = Instant.parse("2026-11-12T12:00:00Z");

  /** Where a new booking goes: one seat no one has held. */
  public record Seat(long eventId, String seatLabel) {}

  private ReleaseQueries() {}

  /** The seat at a 1-based index of the seeded seat plan (20 sections of 1,000 per event). */
  public static Seat seatAt(long seatIndex) {
    long offset = seatIndex - 1;
    long eventId = 1 + offset / LabDatabase.SEATS_PER_EVENT;
    int inEvent = (int) (offset % LabDatabase.SEATS_PER_EVENT);
    char section = (char) ('A' + inEvent / LabDatabase.SEATS_PER_SECTION);
    return new Seat(eventId, section + "-" + (inEvent % LabDatabase.SEATS_PER_SECTION + 1));
  }

  /**
   * Books a seat as this behavior's code does, in one transaction: the hold, then the booking with
   * the behavior's price columns. Throws the database's refusal (missing column, NOT NULL, CHECK).
   *
   * @return the new booking's id
   */
  public static UUID confirm(DataSource pool, Behavior behavior, Seat seat, Instant now)
      throws SQLException {
    try (Connection connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try {
        UUID bookingId = confirmOn(connection, behavior, seat, now);
        connection.commit();
        return bookingId;
      } catch (SQLException refused) {
        connection.rollback();
        throw refused;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  /**
   * Reads one booking's price as this behavior's code does.
   *
   * @return true when every column the behavior reads exists and holds a value for this row
   * @throws SQLException when a column it names does not exist
   */
  public static boolean read(DataSource pool, Behavior behavior, UUID bookingId)
      throws SQLException {
    List<String> priceColumns = checkedColumns(behavior.reads());
    String sql =
        "SELECT " + String.join(", ", priceColumns) + " FROM bookings WHERE booking_id = ?";
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, bookingId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          return false;
        }
        for (int column = 1; column <= priceColumns.size(); column++) {
          if (row.getBigDecimal(column) == null) {
            return false;
          }
        }
        return true;
      }
    }
  }

  /**
   * Whether this behavior's code works against the database as it is now, without changing it: a
   * confirm inside a transaction that is rolled back, and a count of rows whose read columns are
   * NULL (any such row is a read that fails). Used to ask "could this version run now?", for the
   * version rolling out and for the one a rollback would bring back.
   *
   * @return empty when the behavior works, or the database's reason it does not
   */
  public static Optional<String> wouldFail(DataSource pool, Behavior behavior, Seat seat)
      throws SQLException {
    try (Connection connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try {
          confirmOn(connection, behavior, seat, PROBE_TIME);
        } catch (SQLException refused) {
          return Optional.of(
              "insert refused (" + refused.getSQLState() + "): " + firstLine(refused));
        }
        for (String column : checkedColumns(behavior.reads())) {
          try (PreparedStatement count =
                  connection.prepareStatement(
                      "SELECT count(*) FROM bookings WHERE " + column + " IS NULL");
              ResultSet result = count.executeQuery()) {
            result.next();
            long lacking = result.getLong(1);
            if (lacking > 0) {
              return Optional.of(
                  "read of " + column + " fails on " + lacking + " rows that lack it");
            }
          } catch (SQLException refused) {
            return Optional.of(
                "read refused (" + refused.getSQLState() + "): " + firstLine(refused));
          }
        }
        return Optional.empty();
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
      }
    }
  }

  private static UUID confirmOn(Connection connection, Behavior behavior, Seat seat, Instant now)
      throws SQLException {
    List<String> priceColumns = checkedColumns(behavior.writes());
    UUID holdId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    long customerId = 1 + Math.floorMod(seat.seatLabel().hashCode(), CUSTOMERS);
    // The column names come from the allow-list above, never from input; every value is a
    // parameter except the fixed price, which is the same literal for every release.
    String bookingSql =
        "INSERT INTO bookings (booking_id, hold_id, event_id, seat_label, customer_id, "
            + String.join(", ", priceColumns)
            + ", currency, confirmed_at) VALUES (?, ?, ?, ?, ?, "
            + priceColumns.stream().map(column -> "80.00").collect(Collectors.joining(", "))
            + ", 'USD', ?)";
    try (PreparedStatement hold =
            connection.prepareStatement(
                "INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at,"
                    + " expires_at, ended_at) VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)");
        PreparedStatement booking = connection.prepareStatement(bookingSql)) {
      hold.setObject(1, holdId);
      hold.setLong(2, seat.eventId());
      hold.setString(3, seat.seatLabel());
      hold.setLong(4, customerId);
      hold.setTimestamp(5, Timestamp.from(now));
      hold.setTimestamp(6, Timestamp.from(now.plus(HOLD)));
      hold.setTimestamp(7, Timestamp.from(now.plus(TIME_TO_CONFIRM)));
      hold.executeUpdate();
      booking.setObject(1, bookingId);
      booking.setObject(2, holdId);
      booking.setLong(3, seat.eventId());
      booking.setString(4, seat.seatLabel());
      booking.setLong(5, customerId);
      booking.setTimestamp(6, Timestamp.from(now.plus(TIME_TO_CONFIRM)));
      booking.executeUpdate();
    }
    return bookingId;
  }

  /** The behavior's columns, sorted, after checking each is a known price column. */
  private static List<String> checkedColumns(Set<String> columns) {
    Objects.requireNonNull(columns, "columns");
    if (!PRICE_COLUMNS.containsAll(columns) || columns.isEmpty()) {
      throw new IllegalArgumentException("a release touches price or price_paid: " + columns);
    }
    return columns.stream().sorted().toList();
  }

  private static String firstLine(SQLException refused) {
    String message = Objects.requireNonNullElse(refused.getMessage(), "no message");
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
