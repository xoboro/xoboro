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
  implementation(project(":core:application"))

  testImplementation(kotlin("test-junit5"))
}

tasks.test {
  useJUnitPlatform()
}
