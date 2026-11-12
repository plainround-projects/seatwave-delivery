package io.plainround.seatwave.delivery.stack;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * The lab database and the load, read from config/stack.properties. Every key is required and every
 * problem is reported together, so a reader fixing the file sees them all at once.
 *
 * @param url the JDBC URL of the lab database (loopback only)
 * @param ownerUser the role that owns the schema and runs Flyway
 * @param ownerPassword its password, read from the ignored .secrets folder
 * @param appUser the role the application versions use
 * @param appPassword its password
 * @param poolSize connections in the application pool
 * @param connectTimeoutMillis how long a request waits for a connection before it fails
 * @param load the rename-under-load run
 * @param scale the timing run
 */
public record StackSettings(
    String url,
    String ownerUser,
    String ownerPassword,
    String appUser,
    String appPassword,
    int poolSize,
    long connectTimeoutMillis,
    Load load,
    Scale scale) {

  /**
   * Lab 3's load on PostgreSQL.
   *
   * @param requestsPerSecond requests sent each second, half inserts and half reads
   * @param pods pods in the rollout
   * @param rolloutSeconds time to replace every pod
   * @param quietSeconds load after each rollout before the next step
   * @param seedBookings bookings the table holds before the run
   * @param maxInFlight requests in flight at most; one that finds no room fails
   */
  public record Load(
      int requestsPerSecond,
      int pods,
      int rolloutSeconds,
      int quietSeconds,
      int seedBookings,
      int maxInFlight) {}

  /**
   * The timing run.
   *
   * @param bookings rows loaded before the migrations
   * @param probeWritesPerSecond new bookings written during every step
   * @param baselineSeconds probe time before each step, the comparison for its stall
   * @param batchRows rows the backfill copies per committed batch
   * @param pauseSeconds the backfill's pause after each batch
   */
  public record Scale(
      int bookings,
      int probeWritesPerSecond,
      int baselineSeconds,
      int batchRows,
      BigDecimal pauseSeconds) {}

  // Ranges a lab on one laptop can serve; outside them the run would measure the laptop's limits.
  private static final int MAX_POOL = 90;
  private static final int MAX_RATE = 2_000;
  private static final int MAX_BOOKINGS = 10_000_000;
  private static final int MAX_SECONDS = 600;
  private static final int MIN_CONNECT_MILLIS = 250;
  private static final int MAX_CONNECT_MILLIS = 60_000;
  private static final int MIN_PODS = 2;
  private static final int MAX_PODS = 10;
  private static final int MAX_IN_FLIGHT = 10_000;
  private static final int MAX_BATCH_ROWS = 1_000_000;

  /** Rejects missing values; ranges are checked by {@link #load(Path)}. */
  public StackSettings {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(ownerUser, "ownerUser");
    Objects.requireNonNull(ownerPassword, "ownerPassword");
    Objects.requireNonNull(appUser, "appUser");
    Objects.requireNonNull(appPassword, "appPassword");
    Objects.requireNonNull(load, "load");
    Objects.requireNonNull(scale, "scale");
  }

  /** Never prints the passwords. */
  @Override
  public String toString() {
    return "StackSettings[url=" + url + ", ownerUser=" + ownerUser + ", appUser=" + appUser + "]";
  }

  /**
   * Reads and checks the settings file. The password files it names are read relative to the folder
   * the program runs in (the project folder).
   *
   * @return the settings, or every problem found, each naming its key
   */
  public static Result<StackSettings, List<String>> load(Path settingsPath) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException failure) {
      return Result.error(List.of("cannot read " + settingsPath + ": " + failure.getMessage()));
    }
    Reading reading = new Reading(properties);
    String url = reading.text("db.url");
    if (!url.isEmpty() && !url.startsWith("jdbc:postgresql://127.0.0.1:")) {
      reading.problems.add("db.url must name the loopback address 127.0.0.1, found " + url);
    }
    String ownerUser = reading.text("db.ownerUser");
    String ownerPassword = reading.secret("db.ownerPasswordFile");
    String appUser = reading.text("db.appUser");
    String appPassword = reading.secret("db.appPasswordFile");
    int poolSize = reading.whole("db.poolSize", 1, MAX_POOL);
    long connectTimeoutMillis =
        reading.whole("db.connectTimeoutMillis", MIN_CONNECT_MILLIS, MAX_CONNECT_MILLIS);
    Load load =
        new Load(
            reading.whole("load.requestsPerSecond", 1, MAX_RATE),
            reading.whole("load.pods", MIN_PODS, MAX_PODS),
            reading.whole("load.rolloutSeconds", 1, MAX_SECONDS),
            reading.whole("load.quietSeconds", 0, MAX_SECONDS),
            reading.whole("load.seedBookings", 1, MAX_BOOKINGS),
            reading.whole("load.maxInFlight", 1, MAX_IN_FLIGHT));
    Scale scale =
        new Scale(
            reading.whole("scale.bookings", 1, MAX_BOOKINGS),
            reading.whole("scale.probeWritesPerSecond", 1, MAX_RATE),
            reading.whole("scale.baselineSeconds", 1, MAX_SECONDS),
            reading.whole("backfill.batchRows", 1, MAX_BATCH_ROWS),
            reading.decimal("backfill.pauseSeconds", BigDecimal.ZERO, BigDecimal.TEN));
    if (!reading.problems.isEmpty()) {
      return Result.error(List.copyOf(reading.problems));
    }
    return Result.success(
        new StackSettings(
            url,
            ownerUser,
            ownerPassword,
            appUser,
            appPassword,
            poolSize,
            connectTimeoutMillis,
            load,
            scale));
  }

  /** Reads keys and collects every problem instead of stopping at the first. */
  private static final class Reading {
    private final Properties properties;
    private final List<String> problems = new ArrayList<>();

    Reading(Properties properties) {
      this.properties = properties;
    }

    String text(String key) {
      String value = properties.getProperty(key, "").strip();
      if (value.isEmpty()) {
        problems.add(key + " is missing");
      }
      return value;
    }

    String secret(String key) {
      String file = text(key);
      if (file.isEmpty()) {
        return "";
      }
      try {
        String value = Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) {
          problems.add(key + " names an empty file: " + file);
        }
        return value;
      } catch (IOException failure) {
        problems.add(key + " names a file that cannot be read (run scripts/stack.sh up): " + file);
        return "";
      }
    }

    int whole(String key, int minimum, int maximum) {
      String value = text(key);
      if (value.isEmpty()) {
        return minimum;
      }
      try {
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) {
          problems.add(
              String.format(
                  Locale.ROOT, "%s must be %d to %d, found %d", key, minimum, maximum, parsed));
        }
        return parsed;
      } catch (NumberFormatException notANumber) {
        problems.add(key + " must be a whole number, found " + value);
        return minimum;
      }
    }

    BigDecimal decimal(String key, BigDecimal minimum, BigDecimal maximum) {
      String value = text(key);
      if (value.isEmpty()) {
        return minimum;
      }
      try {
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.compareTo(minimum) < 0 || parsed.compareTo(maximum) > 0) {
          problems.add(key + " must be " + minimum + " to " + maximum + ", found " + parsed);
        }
        return parsed;
      } catch (NumberFormatException notANumber) {
        problems.add(key + " must be a decimal number, found " + value);
        return minimum;
      }
    }
  }
}
