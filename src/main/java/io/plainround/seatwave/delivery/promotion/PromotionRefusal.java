package io.plainround.seatwave.delivery.promotion;

import io.plainround.seatwave.delivery.promotion.PromotionGate.Gate;
import java.util.Objects;
import java.util.Set;

/**
 * Why the promotion gate refused an image. Each variant names the rule it broke, and {@link
 * #describe()} is the sentence the promote stage prints after the word "refused".
 */
public sealed interface PromotionRefusal {

  /** Returns one sentence that names the broken rule and the value that broke it. */
  String describe();

  /** The image is named by a tag (which can be moved) or is not an image reference at all. */
  record NotByDigest(String productionImage) implements PromotionRefusal {
    /** Rejects a missing image. */
    public NotByDigest {
      Objects.requireNonNull(productionImage, "productionImage");
    }

    @Override
    public String describe() {
      return "production takes an image by digest, not by tag: " + productionImage;
    }
  }

  /** The digest is well formed but is not the one staging ran its gates on. */
  record UntestedDigest(String digest, String testedDigest) implements PromotionRefusal {
    /** Rejects a missing digest. */
    public UntestedDigest {
      Objects.requireNonNull(digest, "digest");
      Objects.requireNonNull(testedDigest, "testedDigest");
    }

    @Override
    public String describe() {
      return "digest " + digest + " is not the one staging tested (" + testedDigest + ")";
    }
  }

  /** Staging ran the digest but did not pass every gate. */
  record GatesMissing(Set<Gate> missing) implements PromotionRefusal {
    /** Copies the set so the refusal cannot change; an empty set is not a refusal. */
    public GatesMissing {
      missing = Set.copyOf(missing);
      if (missing.isEmpty()) {
        throw new IllegalArgumentException("a refusal for missing gates names at least one gate");
      }
    }

    @Override
    public String describe() {
      return "staging did not pass " + missing.stream().map(Gate::name).sorted().toList();
    }
  }

  /** The staging record could not be read or does not hold a valid digest and gate list. */
  record UnreadableRecord(String path, String reason) implements PromotionRefusal {
    /** Rejects a missing path or reason. */
    public UnreadableRecord {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String describe() {
      return "cannot read the staging record " + path + ": " + reason;
    }
  }
}
