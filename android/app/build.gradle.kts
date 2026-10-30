plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.sharlupa.claudeguard"
    // Собирается только под свежие версии Android: 15 (API 35) и 16 (API 36)
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sharlupa.claudeguard"
        minSdk = 35
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // vpn  — только сетевая блокировка (VPN-блокировщик), без службы спец. возможностей;
    //        Play Protect не ругается на такие приложения.
    // full — плюс блокировка экрана Claude через службу спец. возможностей.
    flavorDimensions += "mode"
    productFlavors {
        create("vpn") {
            dimension = "mode"
            buildConfigField("boolean", "SCREEN_GUARD", "false")
        }
        create("full") {
            dimension = "mode"
            buildConfigField("boolean", "SCREEN_GUARD", "true")
        }
    }

    val keystorePath = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
