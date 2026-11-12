package io.plainround.seatwave.delivery.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Behavior;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Column;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchemaCompatibilityTest {

  @Test
  void anInsertThatLeavesOutARequiredColumnIsAProblem() {
    assertThat(SchemaCompatibility.problems(Set.of(MigrationCli.V1), MigrationCli.CHECK_ADDED))
        .containsExactly("v1 inserts without price_paid, which is NOT NULL");
  }

  @Test
  void aReadOfAColumnThatSomeRowsLackIsAProblem() {
    assertThat(SchemaCompatibility.problems(Set.of(MigrationCli.V3_FLAG_ON), MigrationCli.EXPANDED))
        .containsExactly("v3 flag on reads price_paid, which some rows do not have");
  }

  @Test
  void aReadOfAColumnAnotherRunningVersionDoesNotWriteIsAProblem() {
    assertThat(
            SchemaCompatibility.problems(
                Set.of(MigrationCli.V4, MigrationCli.V1), MigrationCli.SWITCHED))
        .contains("v4 reads price_paid, which rows from v1 lack");
  }

  @Test
  void theSecondReleaseRunsBesideTheFirstOnTheExpandedSchema() {
    assertThat(
            SchemaCompatibility.problems(
                Set.of(MigrationCli.V1, MigrationCli.V2), MigrationCli.EXPANDED))
        .isEmpty();
  }

  @Test
  void aBackfillIsUnsafeWhileAVersionStillInsertsRowsWithoutTheColumn() {
    assertThat(
            SchemaCompatibility.backfillProblems(
                Set.of(MigrationCli.V1, MigrationCli.V2), "price_paid"))
        .containsExactly("v1 still inserts rows without price_paid after the backfill");
  }

  @Test
  void aFilledColumnMustExist() {
    assertThatThrownBy(
            () -> new Schema(Map.of("price", new Column("price", true)), Set.of("price_paid")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBehaviorKeepsItsOwnCopyOfItsColumns() {
    java.util.Set<String> reads = new java.util.HashSet<>(List.of("price"));
    Behavior behavior = new Behavior("v1", reads, Set.of("price"));
    reads.add("price_paid");

    assertThat(behavior.reads()).containsExactly("price");
  }
}
