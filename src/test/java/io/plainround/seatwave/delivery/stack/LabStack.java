package io.plainround.seatwave.delivery.stack;

import java.nio.file.Path;

/**
 * Opens the lab database for a stack test: the settings file named by the system property
 * seatwave.stackSettings, and only the target "local". The pipeline's staging and production
 * databases need their accounts, so naming one fails with a message instead of a connection error.
 */
final class LabStack {

  private LabStack() {}

  static LabDatabase open() {
    String target = System.getProperty("seatwave.target", "local");
    if (!target.equals("local")) {
      throw new IllegalStateException(
          "only the local lab database (compose.yaml) is configured; "
              + target
              + " needs its own account and was not run");
    }
    String settingsPath = System.getProperty("seatwave.stackSettings", "config/stack.properties");
    StackSettings settings =
        StackSettings.load(Path.of(settingsPath))
            .orOnErrorThrow(problems -> new IllegalStateException("stack settings: " + problems));
    return LabDatabase.open(settings, Path.of(""));
  }
}
