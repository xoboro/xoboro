import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvmToolchain(17)

  jvm {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
      allWarningsAsErrors.set(true)
    }
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":core:domain"))
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}
