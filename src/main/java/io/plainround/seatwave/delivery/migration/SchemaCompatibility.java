package io.plainround.seatwave.delivery.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The rule every schema change must pass: at every moment of a release, every version of the code
 * that can be running works against the schema and the data as they are at that moment. During a
 * rollout that is the old and the new version together.
 */
public final class SchemaCompatibility {

  /** A column; required means NOT NULL with no default, so every insert must supply it. */
  public record Column(String name, boolean isRequired) {}

  /**
   * The bookings table at one moment. A column is filled when every existing row has a value; a new
   * nullable column is not filled until a backfill has run.
   */
  public record Schema(Map<String, Column> columns, Set<String> filledColumns) {
    public Schema {
      columns = Map.copyOf(new TreeMap<>(columns));
      filledColumns = Set.copyOf(filledColumns);
      if (!columns.keySet().containsAll(filledColumns)) {
        throw new IllegalArgumentException("a filled column must exist: " + filledColumns);
      }
    }
  }

  /** One running behavior: a code version, or one side of a flag, and the columns it touches. */
  public record Behavior(String label, Set<String> reads, Set<String> writes) {
    public Behavior {
      reads = Set.copyOf(reads);
      writes = Set.copyOf(writes);
    }
  }

  private SchemaCompatibility() {}

  /**
   * Every way the running behaviors fail against this schema; empty means compatible. Behaviors are
   * checked in the order of their labels, so the same question always gets the same answer in the
   * same order (an immutable set's own order changes from one JVM to the next).
   */
  public static List<String> problems(Set<Behavior> running, Schema schema) {
    List<String> problems = new ArrayList<>();
    List<Behavior> inOrder = byLabel(running);
    for (Behavior behavior : inOrder) {
      Set<String> touched = new TreeSet<>(behavior.reads());
      touched.addAll(behavior.writes());
      for (String column : touched) {
        if (!schema.columns().containsKey(column)) {
          problems.add(behavior.label() + " uses " + column + ", which does not exist");
        }
      }
      for (Column column : new TreeMap<>(schema.columns()).values()) {
        if (column.isRequired() && !behavior.writes().contains(column.name())) {
          problems.add(
              behavior.label() + " inserts without " + column.name() + ", which is NOT NULL");
        }
      }
      for (String column : new TreeSet<>(behavior.reads())) {
        if (!schema.columns().containsKey(column)) {
          continue;
        }
        if (!schema.filledColumns().contains(column)) {
          problems.add(behavior.label() + " reads " + column + ", which some rows do not have");
        }
        for (Behavior writer : inOrder) {
          if (!writer.writes().contains(column)) {
            problems.add(
                behavior.label()
                    + " reads "
                    + column
                    + ", which rows from "
                    + writer.label()
                    + " lack");
          }
        }
      }
    }
    return problems;
  }

  /** A backfill fills a column only if no running behavior can insert a row without it. */
  public static List<String> backfillProblems(Set<Behavior> running, String column) {
    return byLabel(running).stream()
        .filter(behavior -> !behavior.writes().contains(column))
        .map(behavior ->
            behavior.label() + " still inserts rows without " + column + " after the backfill")
        .toList();
  }

  private static List<Behavior> byLabel(Set<Behavior> behaviors) {
    return behaviors.stream().sorted(Comparator.comparing(Behavior::label)).toList();
  }
}
