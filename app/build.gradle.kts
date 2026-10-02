import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ML Kit Text Recognition بالنموذج المضمّن (لاتيني): يعمل بلا شبكة. الإصدار نفسه يُسجَّل في كل OcrDocument.
val mlkitTextRecognitionVersion = "16.0.1"

// Tesseract لاسم رأس WhatsApp فقط (نفس المكتبة والإصدار المثبتين في Binaa Payment OCR).
val tesseract4androidVersion = "4.9.0"

// نماذج Tesseract المثبّتة (tessdata/tessdata.lock): «لغة مستودع commit sha256 حجم» لكل سطر.
val tessdataModels: List<String> = rootProject.file("tessdata/tessdata.lock").readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { line ->
        val f = line.split(Regex("\\s+"))
        require(f.size == 5 && f[2].matches(Regex("[0-9a-f]{40}")) && f[3].matches(Regex("[0-9a-f]{64}")) && f[4].toLong() > 0) { "bad tessdata.lock line: $line" }
        f.joinToString(" ")
    }
require(tessdataModels.map { it.substringBefore(' ') } == listOf("ara", "eng")) { "tessdata.lock must pin exactly ara and eng" }

/**
 * ينزّل نماذج Tesseract من raw.githubusercontent.com بالـcommit المثبّت، ويتحقق من الحجم وSHA-256، ثم يضعها في assets مولَّدة
 * (build/، لا Git). أي اختلاف يُفشل البناء قبل التغليف. ذاكرة التنزيل باسم الـSHA نفسه، فلا تُستعمل نسخة غير متحقق منها.
 */
abstract class FetchTessdata : DefaultTask() {
    @get:Input abstract val models: ListProperty<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verified(file: File, sha: String, bytes: Long) = file.isFile && file.length() == bytes && sha256(file) == sha

    @TaskAction
    fun fetch() {
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        val out = outputDir.get().asFile.resolve("tessdata").apply { deleteRecursively(); mkdirs() }
        for (line in models.get()) {
            val (language, repository, commit, sha, size) = line.split(" ")
            val bytes = size.toLong()
            val cached = cache.resolve("$sha.traineddata")
            if (!verified(cached, sha, bytes)) {
                val url = "https://raw.githubusercontent.com/$repository/$commit/$language.traineddata"
                val part = cache.resolve("$sha.part")
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                try {
                    if (connection.responseCode != 200) throw GradleException("tessdata $language: HTTP ${connection.responseCode} from $url")
                    connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                } finally {
                    connection.disconnect()
                }
                val actual = sha256(part)
                if (part.length() != bytes || actual != sha) {
                    part.delete()
                    throw GradleException("tessdata $language from $url does not match tessdata.lock: expected $bytes bytes sha256 $sha, got ${part.length()} bytes sha256 $actual")
                }
                if (!part.renameTo(cached)) throw GradleException("cannot cache tessdata $language")
            }
            cached.copyTo(out.resolve("$language.traineddata"), overwrite = true)
            if (!verified(out.resolve("$language.traineddata"), sha, bytes)) throw GradleException("tessdata $language corrupted while packaging")
            logger.lifecycle("tessdata $language: $repository@$commit sha256 $sha ($bytes bytes) verified")
        }
    }
}

val fetchTessdata = tasks.register<FetchTessdata>("fetchTessdata") {
    models.set(tessdataModels)
    cacheDir.set(File(gradle.gradleUserHomeDir, "caches/binaa-tessdata"))
    outputDir.set(layout.buildDirectory.dir("generated/tessdata-assets"))
}

/** يتحقق أن AAR الـTesseract المحلول (من JitPack) هو نفسه المثبّت بـSHA-256 قبل أي بناء. */
abstract class VerifyPinnedArtifact : DefaultTask() {
    @get:InputFiles abstract val artifact: ConfigurableFileCollection
    @get:Input abstract val expectedSha256: Property<String>
    @get:OutputFile abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        val file = artifact.singleFile
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        logger.lifecycle("${file.name}: sha256 $actual (${file.length()} bytes)")
        if (actual != expectedSha256.get()) throw GradleException("${file.name} sha256 $actual does not match the pinned ${expectedSha256.get()}")
        stamp.get().asFile.writeText(actual)
    }
}

val tesseractAar: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

val verifyTesseractAar = tasks.register<VerifyPinnedArtifact>("verifyTesseractAar") {
    artifact.from(tesseractAar)
    expectedSha256.set(rootProject.file("gradle/tesseract4android.sha256").readLines().map { it.trim() }.single { it.isNotEmpty() && !it.startsWith("#") })
    stamp.set(layout.buildDirectory.file("intermediates/verify-tesseract-aar/sha256.txt"))
}
tasks.named("preBuild") { dependsOn(verifyTesseractAar) }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchTessdata, FetchTessdata::outputDir)
    }
}

android {
    namespace = "com.binaa.ocr"
    compileSdk = 36

    defaultConfig {
        // نهائي إلى الأبد: كل تحديثات V2.x تُثبَّت فوق بعضها بهذا المعرّف وبمفتاح V2 نفسه.
        applicationId = "com.binaa.ocr"
        minSdk = 29
        targetSdk = 36
        // يجب أن يزيد versionCode في كل نسخة تُوزَّع، وإلا رفض Android التحديث.
        versionCode = 1
        // رقم كل نسخة ظاهر في الشاشة الرئيسية: أول 7 أحرف من commit البناء (في CI)، فلا تلتبس نسخة قديمة بجديدة.
        versionName = "2.0.0-alpha1+" + (System.getenv("GITHUB_SHA")?.take(7) ?: "local")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MLKIT_TEXT_VERSION", "\"$mlkitTextRecognitionVersion\"")
        buildConfigField("String", "TESSERACT_ANDROID_VERSION", "\"$tesseract4androidVersion\"")
        // نفس أسطر tessdata.lock (مفصولة بـ;): التطبيق يتحقق من SHA-256 كل نموذج قبل استعماله.
        buildConfigField("String", "TESSDATA_MODELS", "\"${tessdataModels.joinToString(";")}\"")
    }

    // مفتاح V2 الدائم. الملف وكلمات السر خارج Git: يوفّرها GitHub Actions من GitHub Secrets.
    // بدونها لا تُوقَّع نسخة release (وCI يرفض إخراجها).
    val v2KeystorePath = System.getenv("BINAA_V2_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (v2KeystorePath != null) {
            create("v2") {
                storeFile = file(v2KeystorePath)
                storePassword = System.getenv("BINAA_V2_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BINAA_V2_KEY_ALIAS")
                keyPassword = System.getenv("BINAA_V2_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // نسخة الاختبار تُثبَّت بجانب نسخة الإصدار بلا تعارض (مفتاح debug، معرّف مختلف).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (v2KeystorePath != null) signingConfig = signingConfigs.getByName("v2")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    // Golden Corpus: نسخة debug واختبارات الجهاز فقط. CI يتحقق أنه غير موجود في APK الإصدار.
    debugImplementation(project(":golden-corpus"))
    androidTestImplementation(project(":golden-corpus"))
    // Header Engine (قواعد رأس WhatsApp، Kotlin خالص). غير موصول بأي واجهة إنتاج بعد.
    implementation(project(":header-engine"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.mlkit:text-recognition:$mlkitTextRecognitionVersion")
    implementation("cz.adaptech.tesseract4android:tesseract4android:$tesseract4androidVersion")
    tesseractAar("cz.adaptech.tesseract4android:tesseract4android:$tesseract4androidVersion@aar")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
