package io.plainround.seatwave.delivery.support;

import com.github.sviperll.result4j.Result;
import java.util.Optional;

/** Reads the error side of a result, for assertions. */
public final class Errors {

  private Errors() {}

  /** The result's error, or empty when it is a success. */
  public static <E> Optional<E> of(Result<?, E> result) {
    return result.map(success -> Optional.<E>empty()).recoverError(Optional::of);
  }
}
