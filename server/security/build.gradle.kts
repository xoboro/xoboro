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
  implementation(libs.spring.security.crypto)
  implementation(libs.commons.logging)

  testImplementation(kotlin("test-junit5"))
}

tasks.test {
  useJUnitPlatform()
}
