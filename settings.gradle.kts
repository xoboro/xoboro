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
include(":compatibility:komga-api")
include(":compatibility:komga-differential")
include(":server:app")
include(":server:api")
include(":server:media")
include(":server:metadata")
include(":server:persistence")
include(":server:security")
include(":server:sources:local")
include(":server:tasks")
