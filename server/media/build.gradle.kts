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
  implementation(libs.natural.comparator)
  implementation(libs.tika.core)
  implementation(libs.cryptohash)
  implementation(libs.jsoup)
  implementation(libs.jspecify)
  implementation(libs.pdfbox)
  implementation(libs.junrar)

  testImplementation(kotlin("test-junit5"))
}

tasks.test {
  useJUnitPlatform()
}
