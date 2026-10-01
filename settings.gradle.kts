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

// :core = منطق Kotlin خالص (يُختبر على JVM بلا Android). :golden-corpus = أدوات اختبار (debug فقط). :app = تطبيق Android. :access-probe = تطبيق فحص للـCI فقط. :header-engine = قواعد رأس WhatsApp (Kotlin خالص).
include(":core", ":golden-corpus", ":header-engine", ":app", ":access-probe")
