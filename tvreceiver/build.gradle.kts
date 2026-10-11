plugins {
    id("com.android.application")
}

android {
    namespace = "com.stremio.mobile.tvreceiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.stremio.mobile.tvreceiver"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // debuggable by default
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.json:json:20250517")
}
