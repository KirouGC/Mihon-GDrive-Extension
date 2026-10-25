plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La versión debe empezar por "1.4." para que Mihon la cargue (versión de extensions-lib).
val extVersionCode = 1
val libVersion = "1.4"

android {
    namespace = "eu.kanade.tachiyomi.extension.all.nyaa"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.all.nyaa"
        minSdk = 24
        targetSdk = 34
        versionCode = extVersionCode
        versionName = "$libVersion.$extVersionCode"
        manifestPlaceholders["appName"] = "Tachiyomi: Nyaa (Literature)"
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

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/torrentJni"))

    packaging {
        // Que Android extraiga libtorrent4j.so al instalar; si no, NativeLoader lo saca del APK.
        jniLibs.useLegacyPackaging = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin { jvmToolchain(17) }

// libtorrent4j publica sus .so dentro de JARs (lib/<abi>/libtorrent4j.so); se copian como jniLibs.
val torrentNatives by configurations.creating

val extractTorrentNatives by tasks.registering(Copy::class) {
    from(torrentNatives.map { zipTree(it) }) {
        include("lib/**/*.so")
        eachFile { path = path.removePrefix("lib/") }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/torrentJni"))
}

tasks.named("preBuild") { dependsOn(extractTorrentNatives) }

dependencies {
    compileOnly(project(":stubs"))
    implementation(project(":lib:archive"))
    implementation(libs.libtorrent4j)
    torrentNatives(libs.libtorrent4j.arm64)
    torrentNatives(libs.libtorrent4j.arm)
    torrentNatives(libs.libtorrent4j.x64)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.okhttp)
    compileOnly(libs.rxjava)
    compileOnly(libs.jsoup)
    compileOnly(libs.preference)
}
