package io.plainround.seatwave.delivery.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.delivery.support.Errors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StackSettingsTest {

  @TempDir Path folder;

  @Test
  void theProjectsSettingsFileNamesEveryKeyInRange() throws IOException {
    String settings =
        Files.readString(Path.of("config/stack.properties"), StandardCharsets.UTF_8)
            .replace(".secrets/owner.password", secret("owner").toString())
            .replace(".secrets/app.password", secret("app").toString());

    var loaded = StackSettings.load(write(settings));

    assertThat(Errors.of(loaded)).isEmpty();
    assertThat(loaded.discardError().orElseThrow().load().requestsPerSecond()).isEqualTo(300);
    assertThat(loaded.discardError().orElseThrow().toString()).doesNotContain("secret-value");
  }

  @Test
  void everyProblemIsReportedTogether() throws IOException {
    var loaded =
        StackSettings.load(
            write(
                "db.url=jdbc:postgresql://10.0.0.5:5432/seatwave\n"
                    + "db.poolSize=500\n"
                    + "load.pods=three\n"
                    + "backfill.pauseSeconds=-1\n"
                    + "db.ownerPasswordFile=missing.password\n"));

    assertThat(Errors.of(loaded).orElseThrow())
        .contains(
            "db.url must name the loopback address 127.0.0.1, found"
                + " jdbc:postgresql://10.0.0.5:5432/seatwave",
            "db.poolSize must be 1 to 90, found 500",
            "load.pods must be a whole number, found three",
            "backfill.pauseSeconds must be 0 to 10, found -1",
            "db.ownerPasswordFile names a file that cannot be read (run scripts/stack.sh up):"
                + " missing.password",
            "db.appUser is missing");
  }

  @Test
  void aMissingSettingsFileIsOneProblemNamingIt() {
    assertThat(Errors.of(StackSettings.load(folder.resolve("absent.properties"))).orElseThrow())
        .singleElement()
        .asString()
        .contains("absent.properties");
  }

  private Path secret(String name) throws IOException {
    Path file = folder.resolve(name + ".password");
    Files.writeString(file, "secret-value\n", StandardCharsets.UTF_8);
    return file;
  }

  private Path write(String content) throws IOException {
    Path file = folder.resolve("stack.properties");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }
}
