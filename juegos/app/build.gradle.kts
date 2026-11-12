plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cr.juegos.carro"
    compileSdk = 35

    defaultConfig {
        applicationId = "cr.juegos.carro"
        minSdk = 22
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Llave fija en el repo para que cada APK nuevo se instale encima del anterior.
    signingConfigs {
        create("fija") {
            storeFile = file("juegos.keystore")
            storePassword = "juegos123"
            keyAlias = "juegos"
            keyPassword = "juegos123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fija")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}
