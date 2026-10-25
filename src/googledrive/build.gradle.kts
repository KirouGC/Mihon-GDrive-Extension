plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La versión debe empezar por "1.4." para que Mihon la cargue (versión de extensions-lib).
val extVersionCode = 2
val libVersion = "1.4"

android {
    namespace = "eu.kanade.tachiyomi.extension.all.googledrive"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.all.googledrive"
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        versionName = "$libVersion.$extVersionCode"
        manifestPlaceholders["appName"] = "Tachiyomi: Google Drive"
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly(project(":stubs"))
    implementation(project(":lib:archive"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.okhttp)
    compileOnly(libs.rxjava)
    compileOnly(libs.jsoup)
    compileOnly(libs.preference)
}
