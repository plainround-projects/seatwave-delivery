package io.plainround.seatwave.delivery.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Captures what a program prints, in memory, without touching the process's real streams. */
public final class Printed {

  /** A program that prints to the stream it is given. */
  @FunctionalInterface
  public interface Program {
    /** Runs the program. */
    void run(PrintStream out) throws Exception;
  }

  private Printed() {}

  /** Runs the program and returns everything it printed. */
  public static String by(Program program) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      program.run(out);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  /** The golden copy of a program's output, as the module quotes it. */
  public static String golden(String name) {
    try {
      return Files.readString(Path.of("src/test/resources/golden", name), StandardCharsets.UTF_8);
    } catch (IOException missing) {
      throw new UncheckedIOException("golden file " + name + " cannot be read", missing);
    }
  }
}
