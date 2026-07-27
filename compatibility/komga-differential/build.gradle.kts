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
  mainClass.set("io.xoboro.compatibility.komga.differential.MainKt")
}

dependencies {
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test-junit5"))
}

tasks.test {
  useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
  workingDir(rootProject.projectDir)
}
