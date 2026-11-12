package io.plainround.seatwave.delivery.iac;

import com.github.sviperll.result4j.Result;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Where the state file lives, shared by everyone who applies: a stand-in for OpenTofu's S3 backend.
 * A save overwrites, as a put to a bucket does, so only the lock keeps two applies apart. A push of
 * a file from disk is checked: same lineage, newer serial. Not thread-safe: the demo's two
 * pipelines take turns on one thread, which is what makes their interleaving reproducible.
 */
public final class StateBackend {

  /** How the backend guards concurrent applies. */
  public enum LockMode {
    LOCKING,
    NO_LOCKING
  }

  /** One resource as the state records it: the provider's id and the arguments last applied. */
  public record Recorded(String id, PlanEngine.Resource resource) {}

  /**
   * The state file. The lineage names one environment's history and never changes; the serial
   * counts the writes, so an older copy can be recognized.
   */
  public record StateFile(String lineage, long serial, Map<String, Recorded> resourcesByAddress) {
    public StateFile {
      if (lineage.isBlank() || serial < 0) {
        throw new IllegalArgumentException("a state file needs a lineage and a serial >= 0");
      }
      resourcesByAddress = Map.copyOf(new TreeMap<>(resourcesByAddress));
    }

    public static StateFile empty(String lineage) {
      return new StateFile(lineage, 0, Map.of());
    }
  }

  /** A lock request refused: someone else holds it; the caller must wait and plan again. */
  public record HeldBy(String holder) {}

  /** Why the backend kept the shared state and refused a pushed file. */
  public sealed interface PushRefusal {
    /** Returns one sentence naming the rule the file broke. */
    String describe();
  }

  /** The file belongs to another environment's history. */
  public record OtherLineage(String lineage) implements PushRefusal {
    @Override
    public String describe() {
      return "lineage " + lineage + " is another environment's history";
    }
  }

  /** The file is not newer than the shared state: pushing it would lose writes. */
  public record NotNewer(long serial, long sharedSerial) implements PushRefusal {
    @Override
    public String describe() {
      return "serial " + serial + " is not newer than the shared " + sharedSerial;
    }
  }

  private final LockMode lockMode;
  private StateFile current;
  private String lockHolder;

  public StateBackend(LockMode lockMode, String lineage) {
    this.lockMode = lockMode;
    this.current = StateFile.empty(lineage);
  }

  public StateFile read() {
    return current;
  }

  /**
   * Takes the lock, as OpenTofu does before a plan that will be applied.
   *
   * @return the holder, now owning the lock; or who holds it already. With {@link
   *     LockMode#NO_LOCKING} every request succeeds, which is what lets two applies overwrite
   */
  public Result<String, HeldBy> lock(String holder) {
    if (lockMode == LockMode.NO_LOCKING) {
      return Result.success(holder);
    }
    if (lockHolder != null) {
      return Result.error(new HeldBy(lockHolder));
    }
    lockHolder = holder;
    return Result.success(holder);
  }

  public void unlock(String holder) {
    if (holder.equals(lockHolder)) {
      lockHolder = null;
    }
  }

  /** What an apply does at its end: overwrite. The lock, not this method, prevents lost writes. */
  public void save(StateFile written) {
    current = written;
  }

  /** What `tofu state push` does with a file from disk: refuse another history or an old copy. */
  public Result<Long, PushRefusal> push(StateFile fromDisk) {
    if (!fromDisk.lineage().equals(current.lineage())) {
      return Result.error(new OtherLineage(fromDisk.lineage()));
    }
    if (fromDisk.serial() <= current.serial()) {
      return Result.error(new NotNewer(fromDisk.serial(), current.serial()));
    }
    current = fromDisk;
    return Result.success(fromDisk.serial());
  }

  public Optional<String> lockHolder() {
    return Optional.ofNullable(lockHolder);
  }
}
