// Interfaces de extensions-lib 1.4 (https://github.com/keiyoushi/extensions-lib, Apache 2.0).
// Solo se usan para compilar: Mihon aporta las implementaciones reales al cargar la extensión.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.kanade.tachiyomi.extensionlib"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.okhttp)
    compileOnly(libs.rxjava)
    compileOnly(libs.jsoup)
    compileOnly(libs.preference)
}
