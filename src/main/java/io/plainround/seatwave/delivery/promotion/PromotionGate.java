package io.plainround.seatwave.delivery.promotion;

import com.github.sviperll.result4j.Result;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The promotion rule: production runs only the image digest that staging tested, named by digest
 * and never by tag, and only after every staging gate passed. The pipeline's promote stage calls
 * it; the cluster's admission policy checks the same digest's signed attestation. Stateless and
 * safe to call from any thread.
 */
public final class PromotionGate {

  /** The gates a build must pass in staging before it may be promoted. */
  public enum Gate {
    UNIT_AND_BOUNDARY_TESTS,
    IMAGE_SCAN,
    SIGNATURE,
    SCHEMA_COMPATIBILITY,
    STAGING_SMOKE_TESTS
  }

  /** What staging recorded for one build: the digest it ran and the gates it passed. */
  public record StagingRecord(String imageDigest, Set<Gate> passed) {
    /** Rejects a value that is not a sha256 digest, and copies the gates. */
    public StagingRecord {
      Objects.requireNonNull(imageDigest, "imageDigest");
      Objects.requireNonNull(passed, "passed");
      if (!DIGEST.matcher(imageDigest).matches()) {
        throw new IllegalArgumentException("staging records a sha256 digest: " + imageDigest);
      }
      passed = passed.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(passed));
    }
  }

  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  // A repository path, then @ and a digest: a tag such as :1.4.0 can be moved; a digest cannot.
  private static final Pattern BY_DIGEST =
      Pattern.compile("[a-z0-9.-]+(/[a-z0-9._-]+)+@(sha256:[0-9a-f]{64})");

  private PromotionGate() {}

  /**
   * Decides whether production may run this image.
   *
   * @return the digest to deploy, or the first rule the image breaks: named by tag, a digest
   *     staging did not test, or a staging gate missing
   */
  public static Result<String, PromotionRefusal> decide(
      String productionImage, StagingRecord staging) {
    Objects.requireNonNull(productionImage, "productionImage");
    Objects.requireNonNull(staging, "staging");
    var reference = BY_DIGEST.matcher(productionImage);
    if (!reference.matches()) {
      return Result.error(new PromotionRefusal.NotByDigest(productionImage));
    }
    String digest = reference.group(2);
    if (!digest.equals(staging.imageDigest())) {
      return Result.error(new PromotionRefusal.UntestedDigest(digest, staging.imageDigest()));
    }
    Set<Gate> missing = EnumSet.allOf(Gate.class);
    missing.removeAll(staging.passed());
    if (!missing.isEmpty()) {
      return Result.error(new PromotionRefusal.GatesMissing(missing));
    }
    return Result.success(digest);
  }
}
