package io.plainround.seatwave.delivery.iac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.delivery.iac.StateBackend.LockMode;
import io.plainround.seatwave.delivery.iac.StateBackend.StateFile;
import io.plainround.seatwave.delivery.support.Errors;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateBackendTest {

  @Test
  void theLockIsFreeAgainOnlyAfterItsHolderUnlocks() {
    StateBackend backend = new StateBackend(LockMode.LOCKING, "lineage-staging");
    backend.lock("pipeline-A");

    backend.unlock("pipeline-B");
    assertThat(backend.lockHolder()).contains("pipeline-A");
    backend.unlock("pipeline-A");
    assertThat(backend.lock("pipeline-B").discardError()).contains("pipeline-B");
  }

  @Test
  void withoutLockingEveryRequestSucceeds() {
    StateBackend backend = new StateBackend(LockMode.NO_LOCKING, "lineage-staging");
    backend.lock("pipeline-A");

    assertThat(backend.lock("pipeline-B").discardError()).contains("pipeline-B");
  }

  @Test
  void aPushOfAnotherEnvironmentsHistoryIsRefused() {
    StateBackend backend = new StateBackend(LockMode.LOCKING, "lineage-staging");

    assertThat(Errors.of(backend.push(new StateFile("lineage-production", 5, Map.of()))))
        .hasValueSatisfying(refusal ->
            assertThat(refusal.describe())
                .isEqualTo("lineage lineage-production is another environment's history"));
  }

  @Test
  void aPushOfANewerSerialReplacesTheSharedState() {
    StateBackend backend = new StateBackend(LockMode.LOCKING, "lineage-staging");

    assertThat(backend.push(new StateFile("lineage-staging", 1, Map.of())).discardError())
        .contains(1L);
    assertThat(backend.read().serial()).isEqualTo(1);
    assertThat(Errors.of(backend.push(new StateFile("lineage-staging", 1, Map.of()))))
        .containsInstanceOf(StateBackend.NotNewer.class);
  }

  @Test
  void aStateFileNeedsALineageAndANonNegativeSerial() {
    assertThatThrownBy(() -> new StateFile(" ", 0, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StateFile("lineage", -1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
