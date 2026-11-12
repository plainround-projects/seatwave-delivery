package io.plainround.seatwave.delivery.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MigrationCliTest {

  @Test
  void backfillingBeforeTheOldVersionIsGoneLeavesRowsWithoutTheNewColumn() {
    assertThat(MigrationCli.problemsOf(MigrationCli.BACKFILL_TOO_EARLY))
        .containsExactly("v1 still inserts rows without price_paid after the backfill");
  }

  @Test
  void droppingTheOldColumnWithTheLastCodeChangeBreaksTheVersionStillRunning() {
    assertThat(MigrationCli.problemsOf(MigrationCli.CONTRACT_TOO_EARLY))
        .contains("v3 flag on uses price, which does not exist");
  }
}
