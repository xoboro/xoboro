plugins {
  base
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
  group = "io.xoboro"
  version = "0.1.0-SNAPSHOT"
}

tasks.named("check") {
  dependsOn(":core:domain:check")
  dependsOn(":server:app:check")
}
