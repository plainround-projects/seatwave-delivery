package io.plainround.seatwave.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.delivery.canary.CanaryCli;
import io.plainround.seatwave.delivery.canary.Lab2PlantedError;
import io.plainround.seatwave.delivery.iac.IacDemo;
import io.plainround.seatwave.delivery.iac.Lab1Environments;
import io.plainround.seatwave.delivery.migration.Lab3RenameUnderLoad;
import io.plainround.seatwave.delivery.migration.MigrationCli;
import io.plainround.seatwave.delivery.support.Printed;
import org.junit.jupiter.api.Test;

/**
 * Each program prints, byte for byte, what the module quotes (src/test/resources/golden). A changed
 * assumption or rule changes a number, and this test says which program moved; update the golden
 * file only together with the module's text.
 */
class ProgramOutputTest {

  @Test
  void thePlanEngineDemoPrintsItsSixExperiments() throws Exception {
    assertThat(Printed.by(IacDemo::run)).isEqualTo(Printed.golden("IacDemo.out.txt"));
  }

  @Test
  void theCanaryProgramPrintsItsSixCasesAndTheTooSmallCanary() throws Exception {
    assertThat(Printed.by(CanaryCli::run)).isEqualTo(Printed.golden("CanaryCli.out.txt"));
  }

  @Test
  void theMigrationProgramPrintsTheFourPlansAndTheTwoEstimates() throws Exception {
    assertThat(Printed.by(MigrationCli::run)).isEqualTo(Printed.golden("MigrationCli.out.txt"));
  }

  @Test
  void theWayBackToVersionOneClosesAtTheNotValidCheck() throws Exception {
    String printed = Printed.by(MigrationCli::run);
    String atCheck =
        printed.substring(
            printed.indexOf("V35_3 add CHECK"), printed.indexOf("release 2: V35_4 validate"));

    assertThat(atCheck)
        .contains("roll back to [v1]: impossible, roll forward (v1 inserts without price_paid");
  }

  @Test
  void theThreeLabsPrintWhatTheModuleQuotes() throws Exception {
    assertThat(Printed.by(Lab1Environments::run))
        .isEqualTo(Printed.golden("Lab1Environments.out.txt"));
    assertThat(Printed.by(Lab2PlantedError::run))
        .isEqualTo(Printed.golden("Lab2PlantedError.out.txt"));
    assertThat(Printed.by(Lab3RenameUnderLoad::run))
        .isEqualTo(Printed.golden("Lab3RenameUnderLoad.out.txt"));
  }
}
