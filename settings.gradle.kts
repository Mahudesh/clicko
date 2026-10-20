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
        // Kadb's SPAKE2 dependency (Android 11+ wireless-debugging pairing) is only on JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.Flyfish233") } }
    }
}
rootProject.name = "TV Remote"
include(":app")
