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

rootProject.name = "BinaaOCRV2"

// :core = منطق Kotlin خالص (يُختبر على JVM بلا Android). :app = تطبيق Android.
include(":core", ":app")
