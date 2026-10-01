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

// :core = منطق Kotlin خالص (يُختبر على JVM بلا Android). :golden-corpus = أدوات اختبار (debug فقط). :app = تطبيق Android.
include(":core", ":golden-corpus", ":app")
