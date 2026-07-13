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

rootProject.name = "Distaut"

include(
    ":app",
    ":core:model",
    ":core:effects",
    ":core:recipes",
    ":core:render-api",
    ":core:render-kotlin",
    ":feature:editor",
)
