pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

rootProject.name = "xoboro"

include(":core:domain")
include(":core:application")
include(":server:app")
include(":server:media")
include(":server:persistence")
include(":server:security")
include(":server:sources:local")
include(":server:tasks")
