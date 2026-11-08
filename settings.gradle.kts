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
        // Local repo for the sherpa-onnx AAR downloaded by scripts/prepare-deps.sh
        maven { url = uri("libs/repo") }
    }
}
rootProject.name = "neurostenografist"
include(":app")
