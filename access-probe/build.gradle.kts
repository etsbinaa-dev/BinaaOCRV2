// تطبيق فحص للـCI فقط (حزمة وuid مستقلان): يثبت على المحاكي أن تطبيقًا عاديًا لا يستطيع تشغيل CorpusActivity
// في نسخة debug. لا يعتمد عليه :app، ولا يُرفع ولا يُوزَّع، ولا يدخل APK الإصدار (CI يتحقق من ذلك).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.binaa.ocr.accessprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.binaa.ocr.accessprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
