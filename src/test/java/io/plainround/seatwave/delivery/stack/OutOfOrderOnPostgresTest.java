package io.plainround.seatwave.delivery.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.delivery.migration.MigrationCli;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A migration that arrives after a higher version is applied is refused by Flyway itself, as the
 * model refuses it: V35_1 and V35_3 are applied from a branch that lacked V35_2, then V35_2 lands.
 */
@Tag("stack")
class OutOfOrderOnPostgresTest {

  private static LabDatabase database;

  @TempDir Path branch;

  @BeforeAll
  static void openDatabase() {
    database = LabStack.open();
  }

  @AfterAll
  static void closeDatabase() {
    database.close();
  }

  @Test
  void flywayRefusesAMigrationThatArrivesAfterAHigherOneAsTheModelDoes() throws Exception {
    database.clean();
    copy("V35_1__bookings_add_price_paid.sql", "V35_3__bookings_price_paid_check.sql");
    database.flyway(List.of(branch.toString()), MigrationVersion.LATEST).migrate();
    copy("V35_2__bookings_backfill_procedure.sql");

    assertThatThrownBy(
            () -> database.flyway(List.of(branch.toString()), MigrationVersion.LATEST).migrate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("Detected resolved migration not applied to database: 35.2");
    assertThat(MigrationCli.problemsOf(MigrationCli.OUT_OF_ORDER))
        .containsExactly("V35_2 is out of order: V35_3 is already applied");
  }

  private void copy(String... names) throws IOException {
    for (String name : names) {
      Files.copy(Path.of("db/migration", name), branch.resolve(name));
    }
  }
}
