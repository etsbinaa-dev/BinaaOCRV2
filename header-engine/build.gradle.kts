import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Header Engine: كشف رأس WhatsApp Image Viewer هندسيًا وقرار الاسم. Kotlin خالص (يُختبر على JVM بلا Android).
// المحرّكات (Tesseract وML Kit) تأتي من :app؛ هنا القواعد فقط.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
