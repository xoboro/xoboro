import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
}

kotlin {
  jvmToolchain(17)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    allWarningsAsErrors.set(true)
  }
}

dependencies {
  implementation(project(":core:domain"))
  implementation(libs.natural.comparator)
  implementation(libs.tika.core)

  testImplementation(kotlin("test-junit5"))
}

tasks.test {
  useJUnitPlatform()
}
