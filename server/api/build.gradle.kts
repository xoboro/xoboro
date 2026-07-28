import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(26)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_26)
    allWarningsAsErrors.set(true)
  }
}

dependencies {
  implementation(project(":core:application"))
  implementation(project(":core:domain"))
  implementation(libs.ktor.server.auth)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.rate.limit)
  implementation(libs.ktor.serialization.kotlinx.json)

  testImplementation(kotlin("test-junit5"))
  testImplementation(project(":server:security"))
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.ktor.server.content.negotiation)
  testImplementation(libs.ktor.server.status.pages)
  testImplementation(libs.ktor.server.test.host)
}

tasks.test {
  useJUnitPlatform()
}
