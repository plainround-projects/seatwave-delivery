# seatwave-delivery

SeatWave's delivery, as code: the environment as an OpenTofu module, a pipeline that promotes one
image digest from staging to production, a canary that rolls a bad release back by itself, and the
rename of `bookings.price` to `price_paid` done with expand and contract. This is the project of
Module 35 ("Delivery") of the course *System Design Fundamentals*; the running case is SeatWave, a
ticketing system.

The invariant: **the artifact deployed to production is the exact one tested in staging, and every
schema migration is backward compatible with the running version.** `PromotionGate` enforces the
first half; `SchemaCompatibility`, checked step by step against PostgreSQL 18.6, the second.

## What is here

| Path | What it is | Run here? |
|---|---|---|
| `infra/modules/seatwave-environment`, `infra/live` | OpenTofu module (network, NAT per zone, EKS and node groups, RDS clusters, ElastiCache for Valkey, MSK, a security group, an IAM policy, the vCPU quota) and its root (S3 backend, lock, client-side encryption, both environments' values) | `tofu fmt -check` and `tofu validate` in OpenTofu's 1.13.9 image; plan and apply `[not run]` (need AWS accounts) |
| `delivery/pipeline.yaml` | the pipeline, a neutral definition for any CI system | its local tasks only, one by one; end to end `[not run]` (no CI workflow on purpose) |
| `delivery/rollout.yaml` | the canary: an Argo Rollouts `Rollout` and `AnalysisTemplate` | validated strictly by kubeconform; on a cluster `[not run]` (needs Module 33's cluster) |
| `db/migration/V35_1` to `V35_5` | the rename's five Flyway migrations | run on PostgreSQL 18.6 under load |
| `db/module-13/` | Module 13's schema (V1 to V3), copied unchanged, so the rename runs on its real `bookings` table | run |
| `src/main/java/.../iac`, `canary`, `migration`, `promotion` | four models and the promotion gate, JDK and result4j only | run |
| `src/main/java/.../stack` | the rename on PostgreSQL: settings, pools, each release's queries, an open-loop load, two measuring programs | run |

## Prerequisites

- Java 25 (Eclipse Temurin 25.0.5+8 is the newest build, checked 12 November 2026; this machine ran
  25.0.2+10, the same language level, found by the Gradle toolchain).
- Nothing else for `./gradlew check`. For the stack: Docker with Compose v2 (this machine: Docker
  24.0.6, Compose v2.23.0). For `manifestCheck`: kubeconform 0.8.0. For `scripts/tofu-check.sh`:
  Docker and access to the public OpenTofu registry.

## Pinned dependencies (each checked on Maven Central, its license read, and OSV queried with no advisory listed, on 12 November 2026)

| Coordinates | License | Why the JDK alone is not enough |
|---|---|---|
| `com.github.sviperll:result4j:1.2.0` | Apache-2.0 | expected refusals as values with sealed error types |
| `tools.jackson.core:jackson-databind:3.3.0` | Apache-2.0 | reads the staging record (JSON) into a record, refusing unknown fields |
| `org.flywaydb:flyway-core:13.12.2`, `org.flywaydb:flyway-database-postgresql:13.12.2` | Apache-2.0 | runs the migrations exactly as the pipeline's migrate step does |
| `org.postgresql:postgresql:42.7.14` | BSD-2-Clause | the JDBC driver |
| `com.zaxxer:HikariCP:7.1.0` | Apache-2.0 | a bounded pool for the load runs |
| `ch.qos.logback:logback-classic:1.6.6` | EPL-1.0 / LGPL-2.1 | quiets Flyway's and HikariCP's logging (warnings only) |
| `org.junit:junit-bom:6.1.3`, `org.assertj:assertj-core:3.27.7` | EPL-2.0, Apache-2.0 | test runner and assertions |
| Gradle 9.9.1 (wrapper, sha256 checked), Spotless 8.11.0 with google-java-format 2.0.0, JaCoCo 0.8.16 | Apache-2.0, EPL-2.0 | build, format, coverage |
| `postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42` | PostgreSQL License | the database the migrations run on (Docker Official Image, arm64, the same digest as Module 13's project) |
| `ghcr.io/opentofu/opentofu:1.13.9@sha256:f5aef7f2632b490e3c289a239d7974497d0583f3df6bfa35e8eb31a367ee5ec0` | MPL-2.0 | `fmt` and `validate` with the version the code pins |
| `hashicorp/aws` 6.73.0 (registry.opentofu.org) | MPL-2.0 | the provider the module is validated against (current on both registries, 12 November 2026) |

The versions in `delivery/pipeline.yaml` (Trivy, cosign, Argo Rollouts, kubectl) belong to the
pipeline's runner, not to this build.

## Commands, with their real output

Every command below ran on an Apple M2 Max laptop (macOS 27.2), Docker Desktop's Linux VM for the
containers. Timings cross the VM boundary and are this laptop's, not production's.

### Build, test and coverage (no Docker)

```text
$ ./gradlew clean check
> Task :spotlessCheck
> Task :check
> Task :jacocoTestReport
BUILD SUCCESSFUL in 3s
10 actionable tasks: 10 executed
```

tests: 60 in 9 classes, 0 failed; line coverage 98.0 percent (1107 of 1130 lines, floor 80 percent; the stack package is measured by the stack tests instead)

### The programs (no Docker)

```text
$ ./gradlew -q iacDemo
PART 1. ONE DECLARATION, TWO ENVIRONMENTS
staging, plan: 9 to add, 0 to change, 0 to replace, 0 to destroy
staging, applied: state serial 1, 9 resources recorded
staging, plan again: no changes
production, plan: 10 to add, 0 to change, 0 to replace, 0 to destroy
production, applied: state serial 1, 10 resources recorded

PART 2. ONE EDIT, FOUR KINDS OF CHANGE (planned, not applied)
  ~   aws_eks_node_group.zone["use1-az1"]  scaling_config.max_size: 10 -> 12
  -/+ aws_eks_node_group.zone["use1-az2"]  instance_types: m7i.xlarge -> m7i.2xlarge
  +   aws_eks_node_group.zone["use1-az4"]
  -   aws_rds_cluster.shard["seatwave-db-b"]
  1 to add, 1 to change, 1 to replace, 1 to destroy

PART 3. DRIFT: a person lowers a node group's maximum in the console
  drift: aws_eks_node_group.zone["use1-az2"] scaling_config.max_size recorded 10, real 4
  ~   aws_eks_node_group.zone["use1-az2"]  scaling_config.max_size: 4 -> 10
  0 to add, 1 to change, 0 to replace, 0 to destroy
after apply: no changes

PART 4. LOST AND STALE STATE
production, third compute zone added, plan: 1 to add, 0 to change, 0 to replace, 0 to destroy
production, third compute zone added, applied: state serial 3, 11 resources recorded
stale copy (serial 2), plan: 1 to add, 0 to change, 0 to replace, 0 to destroy
  refused on apply: [aws_eks_node_group.zone["use1-az4"]: aws_eks_node_group seatwave-use1-az4 already exists]
  push of the stale copy: refused: serial 2 is not newer than the shared 3
lost state (empty file), plan: 11 to add, 0 to change, 0 to replace, 0 to destroy
  refused: aws_eks_cluster.seatwave: aws_eks_cluster seatwave already exists
  refused: aws_eks_node_group.zone["use1-az1"]: aws_eks_node_group seatwave-use1-az1 already exists
  refused: aws_eks_node_group.zone["use1-az2"]: aws_eks_node_group seatwave-use1-az2 already exists
  refused: aws_eks_node_group.zone["use1-az4"]: aws_eks_node_group seatwave-use1-az4 already exists
  refused: aws_rds_cluster.shard["seatwave-db-a"]: aws_rds_cluster seatwave-db-a already exists
  refused: aws_rds_cluster.shard["seatwave-db-b"]: aws_rds_cluster seatwave-db-b already exists
  account now holds 2 VPCs and 6 subnets; the new state knows only the new ones

PART 5. TWO PIPELINES APPLY STAGING AT ONCE, NO_LOCKING
A locks: acquired by pipeline-A
B locks: acquired by pipeline-B
A applied, state serial 2 records the third node group
B applied from the same serial; its save overwrote A's
final: state serial 2, 9 resources recorded, 3 node groups running
next plan of merged main: 1 to add, 0 to change, 0 to replace, 0 to destroy
  refused on apply: [aws_eks_node_group.zone["use1-az4"]: aws_eks_node_group seatwave-staging-use1-az4 already exists]

PART 5. TWO PIPELINES APPLY STAGING AT ONCE, LOCKING
A locks: acquired by pipeline-A
B locks: refused, held by pipeline-A
B waits, then runs on merged main (A's change and its own): acquired by pipeline-B
B's plan: 0 to add, 1 to change, 0 to replace, 0 to destroy
final: state serial 3, 10 resources recorded, 3 node groups running
next plan of merged main: no changes
  refused on apply: []

PART 6. THE SAME ENVIRONMENT TWICE: DECLARATION AGAINST SCRIPT
declaration applied twice in each of two accounts: fingerprints 607cc3a19a18 and 607cc3a19a18, 9 objects each
script run 1: 9 objects, VPCs: 1, node group min_size 1, fingerprint 607cc3a19a18, errors []
script run 2: 13 objects, VPCs: 2, node group min_size 2, fingerprint 7fc91452ff24, errors [seatwave-staging already exists, seatwave-staging-use1-az1 already exists, seatwave-staging-use1-az2 already exists, seatwave-staging-db-a already exists]
```

```text
$ ./gradlew -q canaryRun
CANARY ANALYSIS at 3,000 requests a second; steps 1% for 5 min, 5% for 10, 25% for 10, then 100%
bounds: errors and degraded above baseline + 1.0 point (95% confidence); p99 above baseline x 1.20
judged every minute once the step holds 1,098 canary requests (to catch 2.5% errors against a 1.5% bound, 80% power)

case: good release
     1% to minute  5: canary     9,000 req, errors 0.58% (baseline 0.50%), degraded 0.17% (0.20%), p99 123 ms (120) -> pass
     5% to minute 15: canary    90,000 req, errors 0.46% (baseline 0.49%), degraded 0.20% (0.20%), p99 121 ms (120) -> pass
    25% to minute 25: canary   450,000 req, errors 0.49% (baseline 0.49%), degraded 0.20% (0.20%), p99 120 ms (120) -> pass
  minute 25: promoted to 100%
case: holds broken (5.5% errors)
     1% to minute  1: canary     1,800 req, errors 6.00% (baseline 0.49%), degraded 0.39% (0.19%), p99 120 ms (121) -> ROLL BACK: errors 6.00% > bound 1.49% (test 1.96%)
case: slow (p99 about 400 ms)
     1% to minute  1: canary     1,800 req, errors 0.78% (baseline 0.52%), degraded 0.11% (0.20%), p99 419 ms (121) -> ROLL BACK: p99 419 ms > bound 145 ms
case: degraded confirms (6%)
     1% to minute  1: canary     1,800 req, errors 0.72% (baseline 0.50%), degraded 7.22% (0.21%), p99 119 ms (121) -> ROLL BACK: degraded 7.22% > bound 1.21% (test 1.64%)
case: degraded confirms (6%), watching errors only
     1% to minute  5: canary     9,000 req, errors 0.57% (baseline 0.51%), degraded 5.92% (0.21%), p99 123 ms (120) -> pass
     5% to minute 15: canary    90,000 req, errors 0.48% (baseline 0.51%), degraded 6.01% (0.20%), p99 122 ms (121) -> pass
    25% to minute 25: canary   450,000 req, errors 0.50% (baseline 0.51%), degraded 6.00% (0.20%), p99 120 ms (120) -> pass
  minute 25: promoted to 100%
case: holds broken (5.5% errors), all at once; a person rolls back after 20 minutes

SUMMARY: requests sent to the new version before it was stopped or fully promoted
case                                           outcome                         exposed     errors   degraded
A good release, full analysis                  promoted at minute 25           549,000      2,695      1,100
B holds broken (5.5% errors), full analysis    rolled back at minute 1           1,800        108          7
C slow (p99 about 400 ms), full analysis       rolled back at minute 1           1,800         14          2
D degraded confirms (6%), full analysis        rolled back at minute 1           1,800         13        130
D degraded confirms (6%), errors only          promoted at minute 25           549,000      2,718     32,946
E holds broken (5.5% errors), all at once      rolled back by hand, minute 20    3,600,000    198,820      7,186

A CANARY TOO SMALL: subtle fault (2.0% errors) at night, 300 requests a second, 1% for 5 minutes
  canary requests in the step: 300 x 0.01 x 300 s = 900
  needed to catch 2.0% against 1.5% (95% confidence, 80% power): 4,040; for 2.5%: 1,098
  time to 4,040 requests at 1%: 22.4 min; at 5%: 4.5 min
     900 canary requests: fault caught in 349 of 1,000 seeded trials
   4,040 canary requests: fault caught in 769 of 1,000 seeded trials
  a fault in 1 of 5,000 requests: chance the 900-request step sees even one: 16%
```

```text
$ ./gradlew -q migrationRun
PLAN 1. EXPAND AND CONTRACT: rename bookings.price to price_paid over four releases
  release 1: V35_1 add price_paid, nullable (expand)
      ok for [v1]
  release 1: V35_2 create the batched backfill procedure
      ok for [v1]
  release 1: roll out v2 (writes both, reads price)
      ok for [v1, v2]
      roll back to [v1]: possible
  job: backfill price_paid in batches of 10,000
      ok for [v2]
      roll back to [v1]: possible
  release 2: V35_3 add CHECK (price_paid IS NOT NULL) NOT VALID
      ok for [v2]
      roll back to [v1]: impossible, roll forward (v1 inserts without price_paid, which is NOT NULL)
  release 2: V35_4 validate, price_paid SET NOT NULL, price DROP NOT NULL
      ok for [v2]
      roll back to [v1]: impossible, roll forward (v1 inserts without price_paid, which is NOT NULL)
  release 2: roll out v3 (writes both; flag bookings.read-price-paid picks the read)
      ok for [v2, v3 flag off, v3 flag on]
      roll back to [v2]: possible
  flag bookings.read-price-paid on at 100 percent, then locked on
      ok for [v3 flag on]
  release 3: roll out v4 (reads and writes price_paid only)
      ok for [v3 flag on, v4]
      roll back to [v3 flag on]: possible
  release 4: V35_5 drop price (contract)
      ok for [v4]
      roll back to [v3 flag on]: impossible, roll forward (v3 flag on uses price, which does not exist)
  release 4: roll out v5 (the flag's code deleted)
      ok for [v4, v5]
      roll back to [v4]: possible

PLAN 2. THE SAME RENAME IN ONE RELEASE
  one release: RENAME COLUMN price TO price_paid (Flyway on the first new pod)
      BREAKS: v1 uses price, which does not exist
      BREAKS: v1 inserts without price_paid, which is NOT NULL
  one release: roll out v4
      BREAKS: v1 uses price, which does not exist
      BREAKS: v1 inserts without price_paid, which is NOT NULL
      BREAKS: v4 reads price_paid, which rows from v1 lack
      roll back to [v1]: impossible, roll forward (v1 uses price, which does not exist)
  estimated failed requests: 300 a second x 51.3 s of old-pod traffic (25.7 s per pod, shares 3/3, 2/3, 1/3) = 15,400

PLAN 3. BACKFILL BEFORE THE OLD CODE IS GONE
  release 1: V35_1 add price_paid, nullable (expand)
      ok for [v1]
  job: backfill price_paid in batches of 10,000
      BREAKS: v1 still inserts rows without price_paid after the backfill
  release 1: roll out v2
      ok for [v1, v2]
      roll back to [v1]: possible

PLAN 4. CONTRACT IN THE SAME RELEASE AS THE LAST CODE CHANGE
  release 1: V35_1 and V35_2 add price_paid and the backfill
      ok for [v1]
  release 1: roll out v2
      ok for [v1, v2]
      roll back to [v1]: possible
  job: backfill price_paid in batches of 10,000
      ok for [v2]
      roll back to [v1]: possible
  release 2: V35_3 and V35_4
      ok for [v2]
      roll back to [v1]: impossible, roll forward (v1 inserts without price_paid, which is NOT NULL)
  release 2: roll out v3
      ok for [v2, v3 flag off, v3 flag on]
      roll back to [v2]: possible
  release 3: V35_5 drop price together with v4
      BREAKS: v3 flag off uses price, which does not exist
      BREAKS: v3 flag on uses price, which does not exist
      roll back to [v2]: impossible, roll forward (v2 uses price, which does not exist)
  release 3: roll out v4
      BREAKS: v3 flag off uses price, which does not exist
      BREAKS: v3 flag on uses price, which does not exist
      roll back to [v3 flag off, v3 flag on]: impossible, roll forward (v3 flag off uses price, which does not exist)

BACKFILL (estimated): 2,851,563 rows a shard, 286 batches of 10,000 at 0.1 s = 28.6 s a shard; 64 shards, 8 at a time: 229 s
```

Labs 1 to 3 (`./gradlew -q lab1Run`, `lab2Run`, `lab3Run`):

```text
seatwave-staging: 9 to add
seatwave: 10 to add
seatwave-loadtest: 9 to add
  differs: cluster_name staging seatwave-staging, production seatwave
  differs: node_group_name staging seatwave-staging-use1-az1, production seatwave-use1-az1
  differs: scaling_config.max_size staging 2, production 10
  differs: scaling_config.min_size staging 1, production 3
one compute zone refused: seatwave-onezone: a lost zone would leave the peak short
judged every minute    rolled back at minute 10; canary requests 54,000; canary errors 876
judged once per step   rolled back at minute 15; canary requests 99,000; canary errors 2,693
expand and contract    164,400 requests served,       0 failed
one-step rename         41,100 requests served,  15,366 failed
```

### The promote stage's gate

```text
$ ./gradlew -q promotionGate -Pimage=111122223333.dkr.ecr.us-east-1.amazonaws.com/seatwave@sha256:c4d30cd227bdc2bc94f0c2bfc9b69b3bf11ddf7d808259389af216b92ca41e3a -PstagingRecord=staging-record.json
promote: production deploys sha256:c4d30cd227bdc2bc94f0c2bfc9b69b3bf11ddf7d808259389af216b92ca41e3a
exit status 0
$ ./gradlew -q promotionGate -Pimage=111122223333.dkr.ecr.us-east-1.amazonaws.com/seatwave@sha256:c50689d0e6dd1f93a50f137ab2d954cc1f5e9e43fad2193ed3f9353783949621 -PstagingRecord=staging-record.json
refused: digest sha256:c50689d0e6dd1f93a50f137ab2d954cc1f5e9e43fad2193ed3f9353783949621 is not the one staging tested (sha256:c4d30cd227bdc2bc94f0c2bfc9b69b3bf11ddf7d808259389af216b92ca41e3a)
exit status 1: the stage stops
$ ./gradlew -q promotionGate -Pimage=111122223333.dkr.ecr.us-east-1.amazonaws.com/seatwave:1.4.0 -PstagingRecord=staging-record.json
refused: production takes an image by digest, not by tag: 111122223333.dkr.ecr.us-east-1.amazonaws.com/seatwave:1.4.0
exit status 1: the stage stops
```

### Mutants: each rule removed, and the tests that then fail

```text
mutant 1 the promotion gate no longer compares the digest with the one staging tested
  fails DeliveryInvariantTest.productionRefusesADigestThatStagingDidNotTest
  fails PromotionGateTest.theCommandRefusesAnotherDigestAndExitsOne
mutant 2 the schema check no longer reports a column the running code uses but the schema lacks
  fails DeliveryInvariantTest.renamingTheColumnInOneReleaseBreaksTheVersionStillRunning
  fails ProgramOutputTest.theMigrationProgramPrintsTheFourPlansAndTheTwoEstimates
  fails MigrationCliTest.droppingTheOldColumnWithTheLastCodeChangeBreaksTheVersionStillRunning
mutant 3 the canary is judged with fewer than the minimum requests
  fails DeliveryInvariantTest.aCanaryWithFewerThanTheMinimumRequestsIsNotJudgedHoweverCleanItLooks
mutant 4 the canary's error bound is built from the wrong baseline rate (degraded, not errors)
  fails DeliveryInvariantTest.aCanaryErrorBoundIsTheStableErrorRatePlusTheMarginNotAnyOtherRate
  fails ProgramOutputTest.theThreeLabsPrintWhatTheModuleQuotes
  fails ProgramOutputTest.theCanaryProgramPrintsItsSixCasesAndTheTooSmallCanary
mutant 5 the state lock never refuses a second holder, so an apply changes production state without it
  fails DeliveryInvariantTest.aSecondApplyCannotTakeTheStateLockWhileTheFirstHoldsIt
  fails ProgramOutputTest.thePlanEngineDemoPrintsItsSixExperiments
mutant 6 a migration applied out of order is accepted
  fails DeliveryInvariantTest.aMigrationAppliedOutOfOrderIsRefused
mutant 7 seed defect 2 put back: the model treats the NOT VALID check as if it bound no new row
  fails ProgramOutputTest.theWayBackToVersionOneClosesAtTheNotValidCheck
  fails ProgramOutputTest.theMigrationProgramPrintsTheFourPlansAndTheTwoEstimates
  fails SchemaCompatibilityTest.anInsertThatLeavesOutARequiredColumnIsAProblem
  fails ExpandAndContractOnPostgresTest.theFourReleasePlanWorksForEveryVersionThatCanBeRunningAtEveryStep
```

Every mutant is killed. Mutant 7 is seed defect 2 put back; the stack test kills it on PostgreSQL, where v1's insert is refused at V35_3.

### The canary's manifests and the OpenTofu code

```text
$ ./gradlew -q manifestCheck
$ kubeconform -strict -summary -schema-location <vendored> delivery/rollout.yaml
Summary: 2 resources found in 1 file - Valid: 2, Invalid: 0, Errors: 0, Skipped: 0
$ kubeconform ... rollout-mutant.yaml   (setWeight misspelled)
rollout-mutant.yaml - Rollout seatwave is invalid: problem validating schema. Check JSON formatting: jsonschema validation failed with 'delivery/schemas/argoproj.io/rollout_v1alpha1.json#' - at '/spec/strategy/canary/steps/2': additional properties 'setWieght' not allowed
Summary: 2 resources found in 1 file - Valid: 1, Invalid: 1, Errors: 0, Skipped: 0
mutant refused, as it must be
```

```text
$ scripts/tofu-check.sh
$ tofu version
OpenTofu v1.13.9
$ tofu fmt -check -recursive infra
exit status 0: every file is formatted
$ tofu -chdir=infra/live init -backend=false
- Installing hashicorp/aws v6.73.0...
- Installed hashicorp/aws v6.73.0 (signed, key ID 0C0AF313E5FD9F80)
OpenTofu has been successfully initialized!
$ tofu -chdir=infra/live validate
Success! The configuration is valid.
```

`tofu plan` and `apply` against the two accounts are `[not run]`: they need AWS accounts. The module
declares 36 resources for staging and 37 for production (counted from the code, not printed by a
plan).

### The stack: PostgreSQL 18.6 (Compose project `seatwave-delivery`, port 127.0.0.1:23500)

`scripts/stack.sh` is a dry run by default; `--run` does it, after a question that `--yes` answers.
`up` writes three throwaway passwords into the ignored `.secrets/` folder; `down` runs `down -v`
and deletes them.

```text
$ scripts/stack.sh test --run --yes
plan: test the Compose project seatwave-delivery
ExpandAndContractOnPostgresTest > theWayBackToVersionOneClosesAtTheNotValidCheckOnPostgres() PASSED
ExpandAndContractOnPostgresTest > theFourReleasePlanWorksForEveryVersionThatCanBeRunningAtEveryStep() PASSED
ExpandAndContractOnPostgresTest > droppingPriceWithTheLastCodeChangeBreaksVersionThreeAsTheModelSays() PASSED
ExpandAndContractOnPostgresTest > theOneStepRenameBreaksTheVersionStillRunningAsTheModelSays() PASSED
OutOfOrderOnPostgresTest > flywayRefusesAMigrationThatArrivesAfterAHigherOneAsTheModelDoes() PASSED
SmokeOnPostgresTest > theNewestReleaseBooksASeatAndReadsWhatTheFanPaid() PASSED
BUILD SUCCESSFUL in 7s
nothing of seatwave-delivery is left
```

The pipeline's two database tasks run on the same lab database (`-Pschema` and `-Ptarget` name it;
only `local` is configured, and any other name fails with a message):

```text
$ ./gradlew compatibilityTest -Pschema=local -Pcode=previous-release
ExpandAndContractOnPostgresTest > theWayBackToVersionOneClosesAtTheNotValidCheckOnPostgres() PASSED
ExpandAndContractOnPostgresTest > theFourReleasePlanWorksForEveryVersionThatCanBeRunningAtEveryStep() PASSED
ExpandAndContractOnPostgresTest > droppingPriceWithTheLastCodeChangeBreaksVersionThreeAsTheModelSays() PASSED
ExpandAndContractOnPostgresTest > theOneStepRenameBreaksTheVersionStillRunningAsTheModelSays() PASSED
BUILD SUCCESSFUL in 3s
$ ./gradlew smokeTest -Ptarget=local
SmokeOnPostgresTest > theNewestReleaseBooksASeatAndReadsWhatTheFanPaid() PASSED
BUILD SUCCESSFUL in 2s
$ ./gradlew smokeTest -Ptarget=staging
> Task :smokeTest FAILED
SmokeOnPostgresTest > initializationError FAILED
    java.lang.IllegalStateException: only the local lab database (compose.yaml) is configured; staging needs its own account and was not run
nothing of seatwave-delivery is left
```

The migration runbook's first step on the lab database (`scripts/runbook-blocker.sh`, stack up):

```text
$ scripts/runbook-blocker.sh
$ psql "$DATABASE_URL" -c "SELECT pid, pg_blocking_pids(pid) AS blocked_by, wait_event_type, state, now() - xact_start AS age, left(query, 60) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'"
 pid  | blocked_by | wait_event_type | state  |   age    |                             left                             
------+------------+-----------------+--------+----------+--------------------------------------------------------------
 7795 | {7778}     | Lock            | active | 00:00:01 | SET lock_timeout = '2s'; ALTER TABLE seatwave.bookings ADD C
(1 row)

the migration, after its lock_timeout: exit status 1, ERROR:  canceling statement due to lock timeout
```

### Measurements (each under the shared measurement lock, load average beside it)

The rename under load, both plans (`./gradlew -q renameUnderLoad`):

```text
(1-minute, 5-minute and 15-minute load averages before the run: 7.12 7.73 7.41)
RENAME UNDER LOAD on PostgreSQL: 300 requests a second (half confirms, half reads), 3 pods, one replaced every 25.7 s, 60 quiet seconds after each rollout; 1,000 bookings to start

EXPAND AND CONTRACT
  release 1: V35_1 add price_paid, nullable (expand)                                          0.0 s
  release 1: V35_2 create the batched backfill procedure                                      0.0 s
  release 1: roll out v2 (writes both, reads price)                                         137.0 s
  job: backfill price_paid (2 batches)                                                        0.2 s
  release 2: V35_3 add CHECK (price_paid IS NOT NULL) NOT VALID                               0.0 s
  release 2: V35_4 validate, price_paid SET NOT NULL, price DROP NOT NULL                     0.0 s
  release 2: roll out v3 (writes both; flag bookings.read-price-paid picks the read)        137.0 s
  flag bookings.read-price-paid on at 100 percent, then locked on                             0.0 s
  release 3: roll out v4 (reads and writes price_paid only)                                 137.0 s
  release 4: V35_5 drop price (contract)                                                      0.0 s
  release 4: roll out v5 (the flag's code deleted)                                          137.0 s
  164,535 requests sent in 548.5 s (300.0 a second), 164,535 served, 0 failed
  mix: v1 confirm 7,743; v1 read 7,696; v2 confirm 20,520; v2 read 20,636; v3 flag off confirm 6,353; v3 flag off read 6,373; v3 flag on confirm 14,166; v3 flag on read 14,288; v4 confirm 20,457; v4 read 20,597; v5 confirm 12,922; v5 read 12,784

ONE-STEP RENAME
  one release: RENAME COLUMN price TO price_paid (Flyway on the first new pod)                0.0 s
  one release: roll out v4                                                                  137.0 s
  41,114 requests sent in 137.1 s (300.0 a second), 25,714 served, 15,400 failed
  mix: v1 confirm 7,719; v1 read 7,686; v4 confirm 12,983; v4 read 12,726
  failed   7,683: 42703 ERROR: column "price" does not exist
  failed   7,717: 42703 ERROR: column "price" of relation "bookings" does not exist
(load averages after the run: 2.24 3.62 5.22)
```

Each migration and the backfill on one shard's worth of bookings (`./gradlew -q migrationTimings`):

```text
(1-minute, 5-minute and 15-minute load averages before the run: 7.59 5.67 5.41)
MIGRATION TIMINGS on PostgreSQL: 2,851,563 bookings loaded (with their holds) and vacuumed in 244.8 s; table 297 MB, with indexes 617 MB
probe: 150 new bookings a second, written as the release running at each step writes them; baseline = the 20 s before each step
step (probe writes as)                                        took ms  flyway ms |    p50 ms    p99 ms    max ms  failed |  base p99  base max
V35_1 add price_paid (v1)                                        36.4          4 |       3.9       7.9       7.9       0 |      19.3      88.1
V35_2 create the backfill procedure (v1)                         31.3          4 |       3.7       3.9       3.9       0 |      12.0      98.9
backfill 10,000 a batch, pause 0.06 s: 288 batches (v2)       82535.5          - |       4.3      80.7     139.6       0 |      13.2      53.4
V35_3 CHECK price_paid NOT VALID (v2)                            40.4          6 |       3.5       4.9       4.9       0 |      12.4      80.5
V35_4 VALIDATE, SET NOT NULL, price DROP NOT NULL (v2)         1219.0       1188 |       3.3      43.3      47.0       0 |       8.2      60.4
V35_5 drop price (v4)                                            43.5          8 |      22.8      72.2      72.2       0 |       9.7      72.2
probe in all: 36,600 writes, 0 failed
(load averages after the run: 2.92 5.21 5.44)
```

### Repeatability and paths

```text
$ for run in 1..40: ./gradlew -q test --rerun-tasks   (each in a new JVM, no busy loop beside it)
40 runs of the test task (60 tests each): 40 passed, 0 failed; load average after: 5.84 4.98 5.19
./gradlew check from a path of 321 characters: BUILD SUCCESSFUL
./gradlew check from a folder whose name has a space: BUILD SUCCESSFUL
```

## Project structure

```text
.editorconfig
.gitattributes
.gitignore
ARCHITECTURE.md
CHANGELOG.md
LICENSE
README.md
build.gradle.kts
compose.yaml
config/stack.properties
db/init/10-roles.sh
db/migration/V35_1__bookings_add_price_paid.sql
db/migration/V35_2__bookings_backfill_procedure.sql
db/migration/V35_3__bookings_price_paid_check.sql
db/migration/V35_4__bookings_price_paid_required.sql
db/migration/V35_5__bookings_drop_price.sql
db/module-13/README.txt
db/module-13/R__app_grants.sql
db/module-13/V1__venues_events_sections_seats.sql
db/module-13/V2__customers_holds_bookings.sql
db/module-13/V3__section_price_version.sql
db/one-step-rename/V35_1__bookings_rename_price.sql
delivery/pipeline.yaml
delivery/rollout.yaml
delivery/schemas/README.txt
gradle.lockfile
gradle/libs.versions.toml
gradlew
gradlew.bat
infra/live/.terraform.lock.hcl
infra/live/main.tf
infra/modules/seatwave-environment/main.tf
infra/modules/seatwave-environment/outputs.tf
infra/modules/seatwave-environment/variables.tf
scripts/manifest-check.sh
scripts/runbook-blocker.sh
scripts/stack.sh
scripts/tofu-check.sh
settings-gradle.lockfile
settings.gradle.kts
src/main/java/io/plainround/seatwave/delivery/canary/CanaryAnalysis.java
src/main/java/io/plainround/seatwave/delivery/canary/CanaryCli.java
src/main/java/io/plainround/seatwave/delivery/canary/Lab2PlantedError.java
src/main/java/io/plainround/seatwave/delivery/iac/FakeCloud.java
src/main/java/io/plainround/seatwave/delivery/iac/IacDemo.java
src/main/java/io/plainround/seatwave/delivery/iac/Lab1Environments.java
src/main/java/io/plainround/seatwave/delivery/iac/PlanEngine.java
src/main/java/io/plainround/seatwave/delivery/iac/StateBackend.java
src/main/java/io/plainround/seatwave/delivery/migration/Lab3RenameUnderLoad.java
src/main/java/io/plainround/seatwave/delivery/migration/MigrationCli.java
src/main/java/io/plainround/seatwave/delivery/migration/SchemaCompatibility.java
src/main/java/io/plainround/seatwave/delivery/promotion/PromotionGate.java
src/main/java/io/plainround/seatwave/delivery/promotion/PromotionGateCli.java
src/main/java/io/plainround/seatwave/delivery/promotion/PromotionRefusal.java
src/main/java/io/plainround/seatwave/delivery/stack/LabDatabase.java
src/main/java/io/plainround/seatwave/delivery/stack/MigrationTimingsCli.java
src/main/java/io/plainround/seatwave/delivery/stack/OpenLoad.java
src/main/java/io/plainround/seatwave/delivery/stack/ReleaseQueries.java
src/main/java/io/plainround/seatwave/delivery/stack/RenameUnderLoadCli.java
src/main/java/io/plainround/seatwave/delivery/stack/StackSettings.java
src/main/resources/logback.xml
src/test/java/io/plainround/seatwave/delivery/DeliveryInvariantTest.java
src/test/java/io/plainround/seatwave/delivery/ProgramOutputTest.java
src/test/java/io/plainround/seatwave/delivery/canary/CanaryAnalysisTest.java
src/test/java/io/plainround/seatwave/delivery/iac/PlanEngineTest.java
src/test/java/io/plainround/seatwave/delivery/iac/StateBackendTest.java
src/test/java/io/plainround/seatwave/delivery/migration/MigrationCliTest.java
src/test/java/io/plainround/seatwave/delivery/migration/SchemaCompatibilityTest.java
src/test/java/io/plainround/seatwave/delivery/promotion/PromotionGateTest.java
src/test/java/io/plainround/seatwave/delivery/stack/ExpandAndContractOnPostgresTest.java
src/test/java/io/plainround/seatwave/delivery/stack/LabStack.java
src/test/java/io/plainround/seatwave/delivery/stack/OutOfOrderOnPostgresTest.java
src/test/java/io/plainround/seatwave/delivery/stack/SmokeOnPostgresTest.java
src/test/java/io/plainround/seatwave/delivery/stack/StackSettingsTest.java
src/test/java/io/plainround/seatwave/delivery/support/Errors.java
src/test/java/io/plainround/seatwave/delivery/support/Printed.java
(plus the Gradle wrapper, the two vendored schemas and the golden output files of the tests)
```

## What is simplified

- The models (`FakeCloud`, `PlanEngine`, `CanaryAnalysis`, `SchemaCompatibility`) are teaching
  models: `IacDemo` declares 9 to 11 of the module's 36 and 37 resources; the canary runs on seeded
  simulated traffic. Production uses OpenTofu, Argo Rollouts and Prometheus for the same jobs.
- A release's SQL is generated from the columns its behavior reads and writes; SeatWave's real
  queries are longer, and its hold flow is Module 13's.
- The lab database runs in one container with 4 CPUs and 2 GB; a shard on RDS
  (db.m6id.xlarge) has more memory and local NVMe storage, so its times differ.
- The pipeline's CI integration, OIDC roles per stage, cosign attestation and the cluster's
  admission policy are designed (`delivery/pipeline.yaml`, Module 39) but not built.

## No CI on purpose

This repository has no `.github/` folder and no workflow file of any kind. The course's projects are
verified locally; `delivery/pipeline.yaml` is a neutral design, and its tasks were run here one by
one, as shown above. Running it end to end needs a CI system and AWS accounts, and is `[not run]`.

## The Compose path

`compose.yaml` holds the one service, PostgreSQL 18.6, pinned by tag and digest, on 127.0.0.1:23500
only, with no privilege, no host network, no Docker socket, a read-only root file system and 2 GB of
memory. `scripts/stack.sh test` starts it, runs `./gradlew stackTest` and removes it with
`down -v` on every path.
