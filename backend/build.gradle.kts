plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

jacoco {
    toolVersion = "0.8.12"
}

group = "com.pulse"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Force Testcontainers off the version pinned by the Spring Boot 3.4.x BOM
// (1.20.5). The pinned version's shaded docker-java fork uses Docker API 1.32,
// which modern Docker Engines (>= 25.x / API 1.44+) reject with HTTP 400. The
// 1.21.x line negotiates a newer API version that works on current macOS
// Docker Desktop and Linux Docker Engine. See PKT-FINAL-1.
dependencyManagement {
    dependencies {
        dependencySet(mapOf("group" to "org.testcontainers", "version" to "1.21.4")) {
            entry("testcontainers")
            entry("junit-jupiter")
            entry("postgresql")
            entry("jdbc")
            entry("database-commons")
        }
    }
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Cloud SQL (GCP Cloud Run connectivity)
    implementation("com.google.cloud:spring-cloud-gcp-starter-sql-postgresql:6.3.0")

    // GCP Secret Manager
    implementation("com.google.cloud:google-cloud-secretmanager:2.55.0")

    // GCP Cloud Storage (PKT-FINAL-5: storage scaffold execute uses
    // Storage.create(BlobInfo, BlobTargetOption.doesNotExist()) to create
    // empty folder markers idempotently). Gated by
    // pulse.storage.scaffold.live-writes-enabled.
    implementation("com.google.cloud:google-cloud-storage:2.55.0")

    // Spark + Cobrix (local discovery preview runs)
    implementation("org.apache.spark:spark-sql_2.12:3.5.1") {
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
    }
    implementation("za.co.absa.cobrix:spark-cobol_2.12:2.10.2") {
        exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
    }
    implementation("javax.servlet:javax.servlet-api:4.0.1")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Jackson
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // ULID
    implementation("com.github.f4b6a3:ulid-creator:5.2.3")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // JGit (tenant-scoped local + remote repo operations)
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.1.0.202411261347-r")

    // Calcite — SQL expression parser used by ExpressionValidationService (#88 PR B)
    // for live validation of derived-column expressions, filter / router predicates,
    // and DQ custom SQL. Babel parser is more permissive than core (accepts dbt /
    // Spark idioms like backticks), which is what we need for dialog input.
    implementation("org.apache.calcite:calcite-core:1.39.0")
    implementation("org.apache.calcite:calcite-babel:1.39.0")

    // LangGraph4j — the Chat orchestration StateGraph (ADR 0025). The 7 chat
    // stages run as graph nodes with a shared AgentState, deterministic
    // routing, an interruptBefore plan gate, and a Postgres checkpointer that
    // is the snapshot/undo store. NOTE on coordinates: ADR 0025's prose names
    // "langgraph4j 1.8.x" which does NOT exist on Maven Central. The real
    // groupId is org.bsc.langgraph4j; the postgres-saver checkpointer exists
    // only on the 1.6.0-beta train (not stable 1.5.14). We import the BOM at
    // 1.6.0-beta5 and depend on core + postgres-saver.
    implementation(platform("org.bsc.langgraph4j:langgraph4j-bom:1.6.0-beta5"))
    implementation("org.bsc.langgraph4j:langgraph4j-core")
    // The BOM does NOT manage langgraph4j-postgres-saver (its managed set is
    // core/langchain4j/spring-ai/studio only), so pin the version explicitly.
    // postgres-saver pulls org.postgresql:postgresql:42.7.7, already on the
    // classpath via Spring Boot's runtimeOnly("org.postgresql:postgresql").
    implementation("org.bsc.langgraph4j:langgraph4j-postgres-saver:1.6.0-beta5")

    // Dev
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers -- used by JsonbColumnDriftIT (and any future
    // entity/schema drift detector) to boot an isolated Postgres 16 so the
    // assertion runs against a freshly-migrated schema, independent of any
    // shared dev DB that may have hand-applied ALTERs (see PKT-FINAL-1 /
    // BUG-2026-05-25-01).
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")

    // Suite A backend integration tests — Awaitility powers any wait-for
    // signal during onboarding scenarios and the readiness verdict transition
    // assertions (Scenario F in the follow-up phase). The
    // PKT-CAND-suite-a-backend-integration-test packet suggested 4.8.1 but
    // Maven Central's latest is 4.3.0 (2025-02-21 release); we pin to that.
    // See docs/testing/suite-a-backend-integration.md.
    testImplementation("org.awaitility:awaitility:4.3.0")

    // Suite A Scenario G — WireMock-mocked OpenRouter LLM for chat tool_calls regression.
    // 3.10.0 doesn't exist on Maven Central; 3.13.1 is the latest 3.x.
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")
}

// ---------------------------------------------------------------------------
// CI lane wiring (TASK_P0_ci_lane_separation_and_postgres_smoke).
//
// Three lanes:
//   * fastPrTest             — runs on every PR in the "fast" GitHub job.
//                              Excludes *IT.java and any class tagged "runtime",
//                              "integration", or "live-vertex". H2 +
//                              application-test profile.
//                              Target: < 8 minutes wall time on cold cache.
//   * backendIntegrationTest — runs on every PR in the "integration" job.
//                              Includes *IT.java and classes tagged
//                              "integration"; excludes "runtime" and
//                              "live-vertex". Postgres + application-postgres-it
//                              profile (Flyway on).
//   * runtimeNightlyTest     — runs only on the nightly schedule. Includes
//                              classes tagged "runtime"
//                              (CanonicalLoanMasterAirflowRuntimeIT,
//                              JsonBlueprintLiveRuntimeProofIT,
//                              AggregateBlueprintLiveRuntimeProofIT, …).
//
    // The plain `./gradlew test` task remains available for local unit/slice
    // verification without Docker-only harnesses. Live-runtime and Suite A
    // classes opt out of `test` via the JUnit @Tag exclusion below.
// ---------------------------------------------------------------------------

val cobolExcludes = listOf(
    "com/pulse/cobol/runner/CobolDiscoverySparkJobRunnerTest.class",
    "com/pulse/cobol/service/CobolFlatteningServiceTest.class",
    "com/pulse/cobol/service/CobolSparkPreviewServiceTest.class"
)

tasks.withType<Test> {
    useJUnitPlatform()
    ignoreFailures = (System.getenv("JACOCO_IGNORE_FAILURES") == "true")
    jvmArgs("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")

    // Forward Testcontainers / Docker discovery env vars to the forked test
    // JVM. macOS Docker Desktop (used on local dev hosts) puts the socket at
    // ~/.docker/run/docker.sock, not /var/run/docker.sock, so Testcontainers'
    // default-strategy probe fails unless DOCKER_HOST is set explicitly.
    // Linux CI typically does have /var/run/docker.sock and the explicit set
    // is harmless.
    listOf(
        "DOCKER_HOST",
        "DOCKER_TLS_VERIFY",
        "DOCKER_CERT_PATH",
        // Modern Docker Engines (>= 25.x / API 1.44+) reject the older API
        // version baked into Testcontainers' shaded docker-java. Setting
        // DOCKER_API_VERSION explicitly forces a recent negotiated version.
        "DOCKER_API_VERSION",
        "TESTCONTAINERS_HOST_OVERRIDE",
        "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
        "TESTCONTAINERS_RYUK_DISABLED"
    ).forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}

tasks.named<Test>("test") {
    // Spark 3.5.1 / Cobrix local parser tests require antlr4-runtime 4.9.3,
    // while Hibernate 6.6 requires 4.13.x for Spring/JPA tests. Keep the
    // application-aligned test task on 4.13.x and run the Spark-local tests
    // in a dedicated task with their own runtime classpath.
    exclude(*cobolExcludes.toTypedArray())
    useJUnitPlatform {
        // Local `./gradlew test` skips live-runtime/Docker/Testcontainers suites
        // by default; they belong to runtimeNightlyTest or the dedicated suiteA
        // task.
        excludeTags("runtime", "live-vertex", "suite-a")
    }
    finalizedBy(tasks.jacocoTestReport)
}

val fastPrTest by tasks.registering(Test::class) {
    description = "Fast PR lane: unit + slice tests only. No *IT.java, no @Tag(integration|runtime|live-vertex)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        excludeTags("integration", "runtime", "live-vertex")
    }
    exclude(*cobolExcludes.toTypedArray())
    // Belt-and-suspenders: any IT.java that forgets to add @Tag still cannot
    // sneak into the fast lane.
    exclude("**/*IT.class")
    ignoreFailures = (System.getenv("JACOCO_IGNORE_FAILURES") == "true")
    jvmArgs("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")
}

val backendIntegrationTest by tasks.registering(Test::class) {
    description = "Integration PR lane: *IT.java + @Tag(integration). Excludes @Tag(runtime|live-vertex). Postgres profile."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        excludeTags("runtime", "live-vertex")
    }
    exclude(*cobolExcludes.toTypedArray())
    // Limit selection to *IT.java classes — unit tests run on the fast lane.
    include("**/*IT.class")
    // Each *IT.java boots a full Spring context (Postgres + Flyway + Spark
    // beans). One worker keeps memory pressure low and avoids context-cache
    // races on the same Postgres database.
    maxParallelForks = 1
    // Recycle the JVM between *IT.java classes — each one's Spring context
    // can hold significant heap, and Spark + Cobrix + Calcite together push
    // a single forked JVM past the default 512m on Linux/CI.
    forkEvery = 1
    minHeapSize = "512m"
    maxHeapSize = "2g"
    // Default to the postgres-it profile if the caller did not set one.
    systemProperty(
        "spring.profiles.active",
        System.getProperty("spring.profiles.active", "postgres-it")
    )
    // Forward DB credentials so CI's Postgres service container is reachable.
    listOf("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD",
           "REDIS_HOST", "REDIS_PORT")
        .forEach { name ->
            System.getenv(name)?.let { environment(name, it) }
        }
    ignoreFailures = (System.getenv("JACOCO_IGNORE_FAILURES") == "true")
    jvmArgs("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")
}

val vertexLiveTest by tasks.registering(Test::class) {
    description = "Opt-in paid Vertex AI connectivity smoke tests tagged @Tag(live-vertex)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("live-vertex")
    }
    maxParallelForks = 1
    ignoreFailures = (System.getenv("JACOCO_IGNORE_FAILURES") == "true")
    jvmArgs("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")
    listOf(
        "VERTEX_PROJECT_ID",
        "PULSE_VERTEX_LIVE_PROJECT_ID",
        "VERTEX_LOCATION",
        "VERTEX_CREDENTIALS_PATH",
        "VERTEX_IMPERSONATE_SERVICE_ACCOUNT",
        "GOOGLE_APPLICATION_CREDENTIALS"
    ).forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}

val runtimeNightlyTest by tasks.registering(Test::class) {
    description = "Nightly lane: live-runtime / Docker tests tagged @Tag(runtime)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("runtime")
    }
    exclude(*cobolExcludes.toTypedArray())
    // Live-runtime classes spin up real Docker services; one at a time, fresh
    // JVM each, generous heap.
    maxParallelForks = 1
    forkEvery = 1
    minHeapSize = "512m"
    maxHeapSize = "2g"
    systemProperty(
        "spring.profiles.active",
        System.getProperty("spring.profiles.active", "test")
    )
    listOf("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD",
           "REDIS_HOST", "REDIS_PORT")
        .forEach { name ->
            System.getenv(name)?.let { environment(name, it) }
        }
    ignoreFailures = (System.getenv("JACOCO_IGNORE_FAILURES") == "true")
    jvmArgs("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")
}

// ---------------------------------------------------------------------------
// Suite A — regression-prevention integration tests
// (PKT-CAND-suite-a-backend-integration-test).
//
// Suite A runs the Testcontainers-backed Postgres + fake-gcs-server harness
// against the consolidated PKT-FINAL-1/2/3 onboarding surface (and PKT-FINAL-4
// once it lands). Phase 1 scope is Scenarios A (happy path), B (empty-repo
// init fallback), and C (clone failure cleanup). Tests are tagged
// `suite-a` so they can be filtered as a focused regression suite, and also
// `integration` so they are picked up by the existing `backendIntegrationTest`
// lane. Tests that depend on un-merged fixes (PKT-FINAL-4) are
// `@Disabled` in source until those changes merge — Suite A stays green.
//
// Run locally: `./gradlew suiteA`
// Requires: a working Docker daemon (macOS Docker Desktop, Linux Docker
// Engine, or Colima). See docs/testing/suite-a-backend-integration.md for
// the full operator runbook.
// ---------------------------------------------------------------------------
val suiteA by tasks.registering(Test::class) {
    description = "Suite A regression-prevention IT lane: @Tag(suite-a). Postgres + fake-gcs-server Testcontainers."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("suite-a")
        excludeTags("runtime", "live-vertex")
    }
    exclude(*cobolExcludes.toTypedArray())
    // Each *SuiteAIT class boots its own Spring context against a private
    // Testcontainers Postgres + fake-gcs-server pair. One worker keeps
    // container resource pressure bounded.
    maxParallelForks = 1
    forkEvery = 1
    minHeapSize = "512m"
    maxHeapSize = "2g"
    systemProperty(
        "spring.profiles.active",
        System.getProperty("spring.profiles.active", "suite-a-it")
    )
    // Forward the same Docker discovery env vars as the top-level Test
    // configuration block above (macOS Docker Desktop puts the socket at
    // ~/.docker/run/docker.sock, not /var/run/docker.sock).
    listOf(
        "DOCKER_HOST",
        "DOCKER_TLS_VERIFY",
        "DOCKER_CERT_PATH",
        "DOCKER_API_VERSION",
        "TESTCONTAINERS_HOST_OVERRIDE",
        "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
        "TESTCONTAINERS_RYUK_DISABLED"
    ).forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    ignoreFailures = (System.getenv("JACOCO_IGNORE_FAILURES") == "true")
    jvmArgs("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")
}

val cobolSparkTestRuntimeClasspath by configurations.creating {
    extendsFrom(configurations.testRuntimeClasspath.get())
    resolutionStrategy.force("org.antlr:antlr4-runtime:4.9.3")
}

val cobolSparkTest by tasks.registering(Test::class) {
    description = "Runs local Spark/Cobrix COBOL tests with Spark-compatible ANTLR runtime."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.main.get().output + sourceSets.test.get().output + cobolSparkTestRuntimeClasspath
    useJUnitPlatform()
    ignoreFailures = (System.getenv("JACOCO_IGNORE_FAILURES") == "true")
    jvmArgs("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")
    include(
        "com/pulse/cobol/runner/CobolDiscoverySparkJobRunnerTest.class",
        "com/pulse/cobol/service/CobolFlatteningServiceTest.class",
        "com/pulse/cobol/service/CobolSparkPreviewServiceTest.class"
    )
    shouldRunAfter(tasks.named("test"))
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED")
}

val cobolRunnerSourceDir = layout.projectDirectory.dir("src/main/java/com/pulse/cobol/runner")
val cobolRunnerClassesDir = layout.buildDirectory.dir("cobol-runner/classes")

val compileCobolRunner by tasks.registering(JavaCompile::class) {
    source = fileTree(cobolRunnerSourceDir) { include("**/*.java") }
    classpath = sourceSets.main.get().compileClasspath
    destinationDirectory.set(cobolRunnerClassesDir)
    options.release.set(17)
}

val cobolRunnerJar by tasks.registering(Jar::class) {
    dependsOn(compileCobolRunner)
    archiveBaseName.set("pulse-cobol-runner")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(cobolRunnerClassesDir)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
        csv.required = true
    }
}
