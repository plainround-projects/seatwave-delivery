// Module 35's build: SeatWave's delivery as code. Four JDK-only models (plan engine, canary, schema
// steps, promotion gate), the gate the pipeline calls, and a real PostgreSQL run of the rename.
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Expected refusals (a tag instead of a digest, a canary that crossed a bound) as values.
    implementation(libs.result4j)
    // The promote stage reads the staging record, a small JSON file, into a record.
    implementation(libs.jackson.databind)
    // The rename runs on real PostgreSQL: Flyway applies the five migrations, the JDBC driver and
    // a bounded pool carry the load of the stack tests and measurements.
    implementation(libs.flyway.core)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// Tags that need the lab database of compose.yaml; ./gradlew check never runs them, so a reader
// without Docker can still build and test. PostgreSQL's locks and constraints are the subject, so
// no embedded stand-in replaces it: H2 has neither NOT VALID nor procedures that commit.
val databaseTags = arrayOf("stack", "compatibility", "smoke")
val stackSettings = file("config/stack.properties").absolutePath

tasks.test {
    useJUnitPlatform {
        excludeTags(*databaseTags)
    }
    // Tests read the migrations and golden files by a path relative to the project folder.
    workingDir = projectDir
    testLogging {
        events("failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

fun databaseTest(taskName: String, tag: String, summary: String, schemaProperty: String?) =
    tasks.register<Test>(taskName) {
        group = "verification"
        description = summary
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        workingDir = projectDir
        useJUnitPlatform {
            includeTags(tag)
        }
        // The pipeline names the database it tests (staging, production); only the local lab
        // database of compose.yaml is configured here, and any other name fails with a message.
        val target = schemaProperty?.let { providers.gradleProperty(it).orElse("local").get() } ?: "local"
        systemProperty("seatwave.target", target)
        systemProperty("seatwave.stackSettings", stackSettings)
        // The database is outside Gradle's view, so these tests are never up to date.
        outputs.upToDateWhen { false }
        testLogging {
            events("passed", "failed", "skipped")
        }
    }

databaseTest("stackTest", "stack", "Runs every database test against the lab PostgreSQL (scripts/stack.sh test).", null)
// The pipeline's staging stage: -Pschema=<database> -Pcode=previous-release. Every version that
// can be running (the previous release and the new one) is run against the migrated schema.
databaseTest("compatibilityTest", "compatibility", "Runs the previous and the new release's queries against the migrated schema.", "schema")
// The pipeline's smoke test after a deploy: -Ptarget=<environment>.
databaseTest("smokeTest", "smoke", "Books and reads one seat with the newest release's queries.", "target")

// Code that only runs against PostgreSQL is exercised by stackTest, not by ./gradlew check.
val databaseOnly = listOf("**/stack/**")

tasks.jacocoTestReport {
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(databaseOnly) })
    reports {
        xml.required = true
    }
}

// Every other class counts: the programs' main methods are tested too, by capturing what they print.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(databaseOnly) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// One task per program, so a reader runs one command per example.
val programs = listOf(
    Triple("iacDemo", "io.plainround.seatwave.delivery.iac.IacDemo", "The plan engine's six experiments: state, plan, apply, drift, lost state, locking"),
    Triple("canaryRun", "io.plainround.seatwave.delivery.canary.CanaryCli", "The canary on seeded traffic: six cases and a canary too small to see a fault"),
    Triple("migrationRun", "io.plainround.seatwave.delivery.migration.MigrationCli", "The rename of bookings.price, done right and three ways wrong, checked step by step"),
    Triple("lab1Run", "io.plainround.seatwave.delivery.iac.Lab1Environments", "Lab 1: three environments from one declaration"),
    Triple("lab2Run", "io.plainround.seatwave.delivery.canary.Lab2PlantedError", "Lab 2: a planted error that starts 8 minutes in"),
    Triple("lab3Run", "io.plainround.seatwave.delivery.migration.Lab3RenameUnderLoad", "Lab 3 in memory: both plans under load"),
)
programs.forEach { (taskName, mainClassName, summary) ->
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = summary
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = mainClassName
        workingDir = projectDir
    }
}

// The pipeline's promote stage: ./gradlew promotionGate -Pimage=<repo@sha256:...> -PstagingRecord=<file>
tasks.register<JavaExec>("promotionGate") {
    group = "seatwave"
    description = "Refuses any production image but the digest the staging record tested, with every gate passed."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.plainround.seatwave.delivery.promotion.PromotionGateCli"
    workingDir = projectDir
    args(
        providers.gradleProperty("image").orElse("").get(),
        providers.gradleProperty("stagingRecord").orElse("").get(),
    )
}

// The programs that need the lab database (scripts/stack.sh up first).
val stackPrograms = listOf(
    Triple("renameUnderLoad", "io.plainround.seatwave.delivery.stack.RenameUnderLoadCli", "Lab 3 on PostgreSQL: both plans under 300 requests a second, failed requests counted"),
    Triple("migrationTimings", "io.plainround.seatwave.delivery.stack.MigrationTimingsCli", "Each migration's duration and longest write stall, and the backfill, on a loaded bookings table"),
)
stackPrograms.forEach { (taskName, mainClassName, summary) ->
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = summary
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = mainClassName
        workingDir = projectDir
        args(stackSettings)
    }
}

// kubeconform 0.8.0 must be installed; the two Argo Rollouts schemas are vendored, so no network.
tasks.register<Exec>("manifestCheck") {
    group = "verification"
    description = "Validates delivery/rollout.yaml strictly and checks that a misspelled field is refused."
    workingDir = projectDir
    commandLine("scripts/manifest-check.sh")
}
