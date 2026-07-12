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
        maven {
            url = uri("https://maven.pkg.github.com/keyneom/sync-kit")
            credentials {
                username = githubPackagesUsername()
                password = githubPackagesPassword()
            }
        }
        // Last resort so local development can build against a sync-kit
        // release candidate before it is published (publishToMavenLocal).
        mavenLocal()
    }
}
rootProject.name = "EasyBCPlanner"
include(":app")

private fun githubPackagesUsername(): String =
    providers.gradleProperty("gpr.user").orNull
        ?: System.getenv("GITHUB_ACTOR")?.takeIf { it.isNotBlank() }
        ?: error(
            "GitHub Packages username required: set gpr.user in ~/.gradle/gradle.properties " +
                "or export GITHUB_ACTOR (CI sets this automatically).",
        )

private fun githubPackagesPassword(): String =
    providers.gradleProperty("gpr.key").orNull
        ?: System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        ?: error(
            "GitHub Packages token required: set gpr.key in ~/.gradle/gradle.properties " +
                "or export GITHUB_TOKEN (CI sets this automatically).",
        )
