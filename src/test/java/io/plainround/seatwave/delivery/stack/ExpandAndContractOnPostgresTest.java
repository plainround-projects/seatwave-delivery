package io.plainround.seatwave.delivery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.delivery.migration.MigrationCli;
import io.plainround.seatwave.delivery.migration.MigrationCli.Backfill;
import io.plainround.seatwave.delivery.migration.MigrationCli.LockFlag;
import io.plainround.seatwave.delivery.migration.MigrationCli.Migrate;
import io.plainround.seatwave.delivery.migration.MigrationCli.Rollout;
import io.plainround.seatwave.delivery.migration.MigrationCli.Step;
import io.plainround.seatwave.delivery.migration.MigrationCli.StepCheck;
import io.plainround.seatwave.delivery.migration.SchemaCompatibility.Behavior;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The model of Section 8 against PostgreSQL 18.6, step by step: at every step of a plan the same
 * step is applied to Module 13's bookings table, and each version the model checks (the one
 * running, the one rolling out, the one a rollback would bring back) runs its own confirm and read
 * against the database. The database must agree with the model every time: where the model says a
 * version works, it works; where the model says the way back is closed, the old version is refused.
 */
@Tag("stack")
@Tag("compatibility")
class ExpandAndContractOnPostgresTest {

  private static final int SEED_BOOKINGS = 20;
  private static final int SPARE_SEATS = 5_000;
  private static final int BATCH_ROWS = 10_000;
  private static LabDatabase database;
  private final AtomicLong nextSeat = new AtomicLong(SEED_BOOKINGS + 1L);

  @BeforeAll
  static void openDatabase() {
    database = LabStack.open();
  }

  @AfterAll
  static void closeDatabase() {
    database.close();
  }

  @Test
  void theFourReleasePlanWorksForEveryVersionThatCanBeRunningAtEveryStep() throws Exception {
    List<String> disagreements = compare(MigrationCli.EXPAND_AND_CONTRACT, "db/migration");

    assertThat(disagreements).isEmpty();
  }

  @Test
  void theWayBackToVersionOneClosesAtTheNotValidCheckOnPostgres() throws Exception {
    start("db/migration");
    database.migrate("db/migration", "35.2");
    assertThat(wouldFail(MigrationCli.V1)).isEmpty();

    database.migrate("db/migration", "35.3");

    assertThat(wouldFail(MigrationCli.V1))
        .hasValueSatisfying(reason ->
            assertThat(reason).startsWith("insert refused (23514)").contains("price_paid_present"));
    assertThat(wouldFail(MigrationCli.V2)).isEmpty();
  }

  @Test
  void theOneStepRenameBreaksTheVersionStillRunningAsTheModelSays() throws Exception {
    List<String> disagreements = compare(MigrationCli.ONE_STEP_RENAME, "db/one-step-rename");

    assertThat(disagreements).isEmpty();
    assertThat(wouldFail(MigrationCli.V1))
        .hasValueSatisfying(reason -> assertThat(reason).startsWith("insert refused (42703)"));
  }

  @Test
  void droppingPriceWithTheLastCodeChangeBreaksVersionThreeAsTheModelSays() throws Exception {
    assertThat(compare(MigrationCli.CONTRACT_TOO_EARLY, "db/migration")).isEmpty();
  }

  /** Runs the plan on the database and the model side by side; returns where they disagree. */
  private List<String> compare(List<Step> steps, String folder) throws Exception {
    start(folder);
    List<StepCheck> model = MigrationCli.trace(steps);
    List<String> disagreements = new ArrayList<>();
    List<Behavior> running = new ArrayList<>(List.of(MigrationCli.V1));
    for (int index = 0; index < steps.size(); index++) {
      Step step = steps.get(index);
      StepCheck check = model.get(index);
      apply(step, folder, check);
      boolean isAnyBroken = false;
      for (Behavior behavior : check.checked()) {
        Optional<String> failure = wouldFail(behavior);
        isAnyBroken |= failure.isPresent();
        if (check.problems().isEmpty() && failure.isPresent()) {
          disagreements.add(
              check.label()
                  + ": the model says "
                  + behavior.label()
                  + " works, but "
                  + failure.get());
        }
      }
      if (!check.problems().isEmpty() && !isAnyBroken) {
        disagreements.add(
            check.label() + ": the model says " + check.problems() + ", but every version works");
      }
      if (check.hasWayBack()) {
        boolean isWayBackOpen = true;
        for (Behavior behavior : check.rollbackTarget()) {
          isWayBackOpen &= wouldFail(behavior).isEmpty();
        }
        if (isWayBackOpen != check.rollbackProblems().isEmpty()) {
          disagreements.add(
              check.label()
                  + ": rollback to "
                  + check.rollbackTarget().stream().map(Behavior::label).sorted().toList()
                  + (isWayBackOpen ? " works on PostgreSQL" : " fails on PostgreSQL")
                  + ", the model says "
                  + (check.rollbackProblems().isEmpty() ? "possible" : check.rollbackProblems()));
        }
      }
      running.clear();
      running.addAll(check.running());
    }
    return disagreements;
  }

  /**
   * Applies one step as the pipeline does. During a rollout every version involved really writes a
   * booking, as live traffic would, so the rows the backfill and the checks meet are the rows a
   * real rollout leaves behind (for example v1's rows without price_paid).
   */
  private void apply(Step step, String folder, StepCheck check) throws SQLException {
    switch (step) {
      case Migrate migrate -> {
        if (check.problems().stream().noneMatch(problem -> problem.contains("out of order"))) {
          database.migrate(folder, "35." + migrate.version());
        }
      }
      case Backfill backfill -> backfill();
      case Rollout rollout -> {
        for (Behavior behavior : check.checked()) {
          if (wouldFail(behavior).isEmpty()) {
            ReleaseQueries.confirm(database.app(), behavior, nextSeat(), Instant.now());
          }
        }
      }
      case LockFlag lockFlag -> {}
    }
  }

  private void start(String folder) throws SQLException {
    database.clean();
    database.migrate(folder, "3");
    database.seed(SEED_BOOKINGS, SPARE_SEATS);
    nextSeat.set(SEED_BOOKINGS + 1L);
  }

  private void backfill() throws SQLException {
    database.backfill(BATCH_ROWS, BigDecimal.ZERO);
  }

  private Optional<String> wouldFail(Behavior behavior) throws SQLException {
    return ReleaseQueries.wouldFail(database.app(), behavior, nextSeat());
  }

  private ReleaseQueries.Seat nextSeat() {
    return ReleaseQueries.seatAt(nextSeat.getAndIncrement());
  }
}
