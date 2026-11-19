plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.irvingguerra.litedash.core.ui"
    compileSdk = 37
    defaultConfig { minSdk = 22 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { create("benchmark") { initWith(getByName("release")) } }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.ui)
}
