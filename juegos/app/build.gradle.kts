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

    // Llave fija (no va en el repo): si existe app/juegos.keystore se usa para que cada APK nuevo
    // se instale encima del anterior. En GitHub sale del secreto JUEGOS_KEYSTORE_B64.
    // Sin ella se firma con la llave de depuración (instalable, pero cada versión pide desinstalar).
    val llave = file("juegos.keystore")
    val fija = if (llave.exists()) signingConfigs.create("fija") {
        storeFile = llave
        storePassword = System.getenv("JUEGOS_KS_PASS")?.takeIf { it.isNotEmpty() } ?: "juegos123"
        keyAlias = System.getenv("JUEGOS_KS_ALIAS")?.takeIf { it.isNotEmpty() } ?: "juegos"
        keyPassword = System.getenv("JUEGOS_KS_PASS")?.takeIf { it.isNotEmpty() } ?: "juegos123"
    } else signingConfigs.getByName("debug")

    buildTypes {
        getByName("debug") {
            signingConfig = fija
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = fija
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
