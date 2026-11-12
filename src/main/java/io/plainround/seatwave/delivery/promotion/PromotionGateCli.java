package io.plainround.seatwave.delivery.promotion;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.delivery.promotion.PromotionGate.StagingRecord;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The promote stage's command: {@code ./gradlew promotionGate -Pimage=<repository@digest>
 * -PstagingRecord=<file>}. Reads the staging record, asks {@link PromotionGate}, prints the answer
 * and exits 0 to promote, 1 to refuse and 2 on wrong usage, so the pipeline stops on anything but a
 * promotion.
 */
public final class PromotionGateCli {

  static final int PROMOTE = 0;
  static final int REFUSE = 1;
  static final int USAGE = 2;
  // Bound the file read: a staging record is a digest and five gate names, well under 4 KiB.
  private static final long MAX_RECORD_BYTES = 4_096;
  // Unknown fields are refused: a record with a misspelled gate list must not pass as "no gates".
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private PromotionGateCli() {}

  /** Runs the gate and exits with its status. */
  public static void main(String[] arguments) {
    System.exit(run(arguments, System.out, System.err));
  }

  /** Runs the gate on {@code image stagingRecordPath}; returns the exit status. */
  static int run(String[] arguments, PrintStream out, PrintStream err) {
    if (arguments.length != 2 || arguments[0].isBlank() || arguments[1].isBlank()) {
      err.println("usage: promotionGate -Pimage=<repository@sha256:...> -PstagingRecord=<file>");
      return USAGE;
    }
    Result<String, PromotionRefusal> decision =
        read(Path.of(arguments[1])).flatMap(staging -> PromotionGate.decide(arguments[0], staging));
    decision.ifSuccess(digest -> out.println("promote: production deploys " + digest));
    decision.peekError(refusal -> err.println("refused: " + refusal.describe()));
    return decision.isError() ? REFUSE : PROMOTE;
  }

  /** Reads a staging record written as JSON: {"imageDigest": "sha256:...", "passed": [...]}. */
  static Result<StagingRecord, PromotionRefusal> read(Path recordPath) {
    try {
      if (Files.size(recordPath) > MAX_RECORD_BYTES) {
        return Result.error(
            new PromotionRefusal.UnreadableRecord(
                recordPath.toString(), "larger than " + MAX_RECORD_BYTES + " bytes"));
      }
      StagingRecord staging = JSON.readValue(Files.readString(recordPath), StagingRecord.class);
      return Result.success(staging);
    } catch (IOException | JacksonException failure) {
      // Expected bad input: a missing file, broken JSON, an unknown field or a digest that is not
      // a digest (the record's constructor refuses it and Jackson wraps that refusal).
      return Result.error(
          new PromotionRefusal.UnreadableRecord(
              recordPath.toString(),
              failure.getClass().getSimpleName() + ": " + firstLine(failure)));
    }
  }

  private static String firstLine(Exception failure) {
    String message = Objects.requireNonNullElse(failure.getMessage(), "no detail given");
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
