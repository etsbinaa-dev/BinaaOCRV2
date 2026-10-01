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
        // Tesseract4Android فقط (غير منشور على Maven Central؛ نفس مصدر Binaa Payment OCR). وقت البناء فقط، لا شبكة على الهاتف.
        // exclusiveContent: هذه المجموعة من JitPack وحده، ولا شيء غيرها من JitPack. والـAAR مثبّت بـSHA-256 (app/build.gradle.kts).
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "BinaaOCRV2"

// :core = منطق Kotlin خالص (يُختبر على JVM بلا Android). :golden-corpus = أدوات اختبار (debug فقط). :app = تطبيق Android. :access-probe = تطبيق فحص للـCI فقط. :header-engine = قواعد رأس WhatsApp (Kotlin خالص).
include(":core", ":golden-corpus", ":header-engine", ":app", ":access-probe")
