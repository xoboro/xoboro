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
  // A WebP *reader* for ImageIO, registered by service loader. Pure Java, no native
  // library. The scanner already offers to discover `cover.webp`, and without a
  // reader that discovery produced a permanently failing artwork task rather than a
  // cover. See docs/architecture/0104-webp-support.md - WebP output is declined, and
  // this library ships no writer to accidentally enable it.
  runtimeOnly(libs.imageio.webp)

  testImplementation(kotlin("test-junit5"))
}

tasks.test {
  useJUnitPlatform()
}
