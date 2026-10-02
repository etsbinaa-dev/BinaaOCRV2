import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Golden Corpus: أدوات اختبار فقط. يعتمد عليها :app في debug والاختبارات فقط، ولا تدخل نسخة الإصدار أبدًا
// (CI يتحقق من ذلك على الـAPK النهائي). Kotlin خالص: تُختبر على JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
