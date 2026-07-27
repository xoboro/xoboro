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
  implementation(project(":server:persistence"))
  implementation(project(":server:security"))
  implementation(project(":server:sources:local"))
  implementation(project(":server:tasks"))
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.call.logging)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.status.pages)
  implementation(libs.ktor.server.sse)
  implementation(libs.ktor.server.forwarded.header)
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
  useJUnitPlatform()
}
