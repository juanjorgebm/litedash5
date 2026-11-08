plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.irvingguerra.litedash.feature.tpms"
    compileSdk = 37
    defaultConfig { minSdk = 22 }
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
    implementation(project(":core:vehicle"))
    implementation(project(":feature:obd"))
    implementation(libs.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    testImplementation(libs.junit)
}
