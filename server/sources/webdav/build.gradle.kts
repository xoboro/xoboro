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
  implementation(project(":server:media"))

  testImplementation(kotlin("test-junit5"))
  testImplementation(libs.ktor.server.core)
  testImplementation(libs.ktor.server.netty)
}

tasks.test {
  useJUnitPlatform()
}
