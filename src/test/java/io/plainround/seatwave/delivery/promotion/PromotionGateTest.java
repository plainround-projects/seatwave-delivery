package io.plainround.seatwave.delivery.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.delivery.promotion.PromotionGate.Gate;
import io.plainround.seatwave.delivery.promotion.PromotionGate.StagingRecord;
import io.plainround.seatwave.delivery.support.Errors;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromotionGateTest {

  private static final String TESTED = "sha256:" + "c".repeat(64);
  private static final String IMAGE =
      "111122223333.dkr.ecr.us-east-1.amazonaws.com/seatwave@" + TESTED;

  @TempDir Path folder;

  @Test
  void refusesARecordThatMissesAGateAndNamesTheGate() {
    StagingRecord noScan =
        new StagingRecord(TESTED, EnumSet.complementOf(EnumSet.of(Gate.IMAGE_SCAN)));

    assertThat(Errors.of(PromotionGate.decide(IMAGE, noScan)).map(PromotionRefusal::describe))
        .contains("staging did not pass [IMAGE_SCAN]");
  }

  @Test
  void refusesARecordWithNoGateAtAll() {
    assertThat(Errors.of(PromotionGate.decide(IMAGE, new StagingRecord(TESTED, Set.of()))))
        .containsInstanceOf(PromotionRefusal.GatesMissing.class);
  }

  @Test
  void refusesAnImageReferenceThatIsNotARepositoryPath() {
    assertThat(Errors.of(PromotionGate.decide("@" + TESTED, passed())))
        .containsInstanceOf(PromotionRefusal.NotByDigest.class);
  }

  @Test
  void aStagingRecordMustHoldASha256Digest() {
    assertThatThrownBy(() -> new StagingRecord("1.4.0", Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1.4.0");
  }

  @Test
  void aRefusalForMissingGatesNamesAtLeastOne() {
    assertThatThrownBy(() -> new PromotionRefusal.GatesMissing(Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyRefusalSaysWhatIsWrong() {
    assertThat(new PromotionRefusal.NotByDigest("seatwave:1.4.0").describe())
        .isEqualTo("production takes an image by digest, not by tag: seatwave:1.4.0");
    assertThat(new PromotionRefusal.UntestedDigest("sha256:b", "sha256:a").describe())
        .isEqualTo("digest sha256:b is not the one staging tested (sha256:a)");
    assertThat(new PromotionRefusal.UnreadableRecord("record.json", "missing").describe())
        .isEqualTo("cannot read the staging record record.json: missing");
  }

  @Test
  void theCommandPromotesTheTestedDigestAndExitsZero() throws IOException {
    Path record = write("{\"imageDigest\":\"" + TESTED + "\",\"passed\":" + allGates() + "}");

    Run run = run(IMAGE, record.toString());

    assertThat(run.status()).isEqualTo(PromotionGateCli.PROMOTE);
    assertThat(run.out()).isEqualTo("promote: production deploys " + TESTED + "\n");
  }

  @Test
  void theCommandRefusesAnotherDigestAndExitsOne() throws IOException {
    Path record = write("{\"imageDigest\":\"" + TESTED + "\",\"passed\":" + allGates() + "}");

    Run run = run(IMAGE.replace(TESTED, "sha256:" + "d".repeat(64)), record.toString());

    assertThat(run.status()).isEqualTo(PromotionGateCli.REFUSE);
    assertThat(run.err()).startsWith("refused: digest sha256:dddd");
  }

  @Test
  void theCommandRefusesARecordWithAnUnknownField() throws IOException {
    Path record =
        write("{\"imageDigest\":\"" + TESTED + "\",\"passed\":" + allGates() + ",\"gates\":[]}");

    Run run = run(IMAGE, record.toString());

    assertThat(run.status()).isEqualTo(PromotionGateCli.REFUSE);
    assertThat(run.err()).contains("cannot read the staging record").contains("gates");
  }

  @Test
  void theCommandRefusesARecordWhoseDigestIsATag() throws IOException {
    Path record = write("{\"imageDigest\":\"1.4.0\",\"passed\":" + allGates() + "}");

    assertThat(run(IMAGE, record.toString()).err()).contains("staging records a sha256 digest");
  }

  @Test
  void theCommandRefusesAMissingOrOversizedRecord() throws IOException {
    Path huge = write(" ".repeat(5_000));

    assertThat(run(IMAGE, folder.resolve("absent.json").toString()).err())
        .contains("cannot read the staging record")
        .contains("absent.json");
    assertThat(run(IMAGE, huge.toString()).err()).contains("larger than 4096 bytes");
  }

  @Test
  void theCommandWithoutBothArgumentsPrintsItsUsageAndExitsTwo() {
    Run run = run("", "");

    assertThat(run.status()).isEqualTo(PromotionGateCli.USAGE);
    assertThat(run.err()).startsWith("usage: promotionGate");
  }

  private static StagingRecord passed() {
    return new StagingRecord(TESTED, EnumSet.allOf(Gate.class));
  }

  private static String allGates() {
    return EnumSet.allOf(Gate.class).stream()
        .map(gate -> "\"" + gate.name() + "\"")
        .toList()
        .toString()
        .replace(" ", "");
  }

  private Path write(String content) throws IOException {
    Path file = Files.createTempFile(folder, "staging-record", ".json");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private static Run run(String image, String recordPath) {
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    int status;
    try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
      status = PromotionGateCli.run(new String[] {image, recordPath}, out, err);
    }
    return new Run(
        status,
        outBytes.toString(StandardCharsets.UTF_8),
        errBytes.toString(StandardCharsets.UTF_8));
  }

  private record Run(int status, String out, String err) {}
}
