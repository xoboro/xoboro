import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

kotlin {
  jvmToolchain(26)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_26)
    allWarningsAsErrors.set(true)
  }
}

application {
  mainClass.set("io.xoboro.server.ApplicationKt")
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
  implementation(project(":compatibility:komga-api"))
  implementation(project(":core:application"))
  implementation(project(":core:domain"))
  implementation(project(":server:media"))
  implementation(project(":server:metadata"))
  implementation(project(":server:api"))
  implementation(project(":server:persistence"))
  implementation(project(":server:security"))
  implementation(project(":server:sources:local"))
  implementation(project(":server:tasks"))
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.auth)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.call.logging)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.sse)
  implementation(libs.ktor.server.swagger)
  implementation(libs.ktor.server.forwarded.header)
  implementation(libs.ktor.server.rate.limit)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.tsid.creator)
  runtimeOnly(libs.logback.classic)
  runtimeOnly(libs.logstash.logback.encoder)

  testImplementation(kotlin("test-junit5"))
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.ktor.client.mock)
}

tasks.test {
  useJUnitPlatform {
    excludeTags("performance")
  }
}

// Opt-in synthetic large-library performance harness (see server/app/src/test/kotlin/io/xoboro/
// server/perf). It is a `@Tag("performance")` JUnit test excluded from the default `test`/`check`
// tasks above, and is only run through this dedicated task so a multi-thousand-book scan never
// slows down ordinary CI runs. Library size is controlled via -Pxoboro.perf.* Gradle properties
// (or the matching XOBORO_PERF_* environment variables), defaulting to a small smoke-run size.
fun perfIntProperty(
  gradlePropertyName: String,
  environmentVariableName: String,
  default: Int,
): String =
  (providers.gradleProperty(gradlePropertyName).orNull ?: System.getenv(environmentVariableName))
    ?: default.toString()

tasks.register<Test>("performanceHarness") {
  group = "verification"
  description =
    "Runs the opt-in synthetic large-library performance harness. Not part of check/test; " +
      "size via -Pxoboro.perf.seriesCount/-Pxoboro.perf.booksPerSeries/-Pxoboro.perf.oneShotCount."
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform {
    includeTags("performance")
  }
  systemProperty(
    "xoboro.perf.seriesCount",
    perfIntProperty("xoboro.perf.seriesCount", "XOBORO_PERF_SERIES_COUNT", 20),
  )
  systemProperty(
    "xoboro.perf.booksPerSeries",
    perfIntProperty("xoboro.perf.booksPerSeries", "XOBORO_PERF_BOOKS_PER_SERIES", 5),
  )
  systemProperty(
    "xoboro.perf.oneShotCount",
    perfIntProperty("xoboro.perf.oneShotCount", "XOBORO_PERF_ONE_SHOT_COUNT", 5),
  )
  testLogging {
    showStandardStreams = true
    events("passed", "skipped", "failed", "standardOut", "standardError")
  }
  outputs.upToDateWhen { false }
}
