pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "EasyBCPlanner"
include(":app")

includeBuild("../../sync-kit/android") {
    dependencySubstitution {
        substitute(module("com.keyneom:sync-kit-android")).using(project(":synckit"))
    }
}
