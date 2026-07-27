import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
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
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test-junit5"))
  testImplementation(project(":server:media"))
  testImplementation(project(":server:persistence"))
  testImplementation(project(":server:sources:local"))
}

tasks.test {
  useJUnitPlatform()
}
