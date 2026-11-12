# Changelog

## 1.0.0 (12 November 2026)

Built from Module 35's code seed (`_tools/module-35-code/`, 21 files, and the three lab solutions).

- Gradle build to the course baseline: wrapper 9.9.1 checked by sha256, version catalog,
  lockfiles, `-Xlint:all -Werror`, Spotless with google-java-format 2.0.0, JaCoCo line floor 0.80,
  one task per program, and the tasks `delivery/pipeline.yaml` calls: `compatibilityTest`,
  `smokeTest`, `promotionGate`.
- The seed's sealed result types replaced by result4j `Result` with sealed or record error types:
  `PromotionGate.decide` (`PromotionRefusal`), `CanaryAnalysis.judge` (`NotPassed`: `Fail`,
  `Inconclusive`), `StateBackend.lock` and `push`, `FakeCloud.create`.
- Seed defect fixed: the model printed the way back to v1 as possible after V35_3, but V35_3's
  `NOT VALID` check already refuses v1's inserts without `price_paid`. `MigrationCli.CHECK_ADDED`
  now makes `price_paid` required for new rows; PostgreSQL refuses v1 there too (stack test).
- Seed defect fixed: the comment at the top of `infra/live/main.tf` showed a `-var` with a
  placeholder that the secret scanner reported as a generic API key; reworded, no allow-list.
- The model's checks print and compare in label order: immutable sets iterate in an order that
  changes from one JVM to the next, which could reorder the printed problems.
- `MigrationCli.trace` returns each step's check, so a stack test compares the model with
  PostgreSQL step by step; a migration whose version is not above the highest applied is refused
  (as Flyway refuses it).
- New: the PostgreSQL lab (`compose.yaml`, `scripts/stack.sh`, `config/stack.properties`), Module
  13's schema copied into `db/module-13/`, the stack tests, `RenameUnderLoadCli` and
  `MigrationTimingsCli`.
- New in the OpenTofu module: ElastiCache for Valkey (`<prefix>-cache`), MSK (`<prefix>-kafka`), a
  security group that admits only the EKS cluster to the data stores, and a producer IAM policy
  with no wildcard; provider pin moved to `hashicorp/aws` 6.73.0, the current release.
- New: `scripts/tofu-check.sh` (fmt and validate in OpenTofu's pinned image) and
  `scripts/manifest-check.sh` (kubeconform strict on `delivery/rollout.yaml` against vendored
  schemas, and a mutant that must be refused).
