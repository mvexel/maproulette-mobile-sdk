plugins {
    id("com.android.application")
}

android {
    namespace = "org.maproulette.example"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.maproulette.example"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("org.maproulette:maproulette-mobile-sdk:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
