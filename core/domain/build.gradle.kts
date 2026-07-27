import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvmToolchain(26)

  jvm {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_26)
      allWarningsAsErrors.set(true)
    }
  }

  sourceSets {
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}
