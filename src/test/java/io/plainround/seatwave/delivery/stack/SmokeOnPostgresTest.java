package io.plainround.seatwave.delivery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.delivery.migration.MigrationCli;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The smoke test after a deploy: on the fully migrated schema the newest release books and reads.
 */
@Tag("stack")
@Tag("smoke")
class SmokeOnPostgresTest {

  private static final int BATCH_ROWS = 10_000;
  private static LabDatabase database;

  @BeforeAll
  static void openDatabase() {
    database = LabStack.open();
  }

  @AfterAll
  static void closeDatabase() {
    database.close();
  }

  @Test
  void theNewestReleaseBooksASeatAndReadsWhatTheFanPaid() throws Exception {
    database.clean();
    database.migrate("db/migration", "3");
    database.seed(1, 10);
    database.migrate("db/migration", "35.2");
    database.backfill(BATCH_ROWS, BigDecimal.ZERO);
    database.migrate("db/migration", "35.5");

    UUID bookingId =
        ReleaseQueries.confirm(
            database.app(), MigrationCli.V5, ReleaseQueries.seatAt(2), Instant.now());

    assertThat(ReleaseQueries.read(database.app(), MigrationCli.V5, bookingId)).isTrue();
  }
}
