plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ML Kit Text Recognition بالنموذج المضمّن (لاتيني): يعمل بلا شبكة. الإصدار نفسه يُسجَّل في كل OcrDocument.
val mlkitTextRecognitionVersion = "16.0.1"

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
        versionName = "2.0.0-alpha1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MLKIT_TEXT_VERSION", "\"$mlkitTextRecognitionVersion\"")
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
    // قياس HeaderDetector في مشغّل الـCorpus (debug فقط) قبل 5b.
    debugImplementation(project(":header-engine"))
    androidTestImplementation(project(":header-engine"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.mlkit:text-recognition:$mlkitTextRecognitionVersion")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
