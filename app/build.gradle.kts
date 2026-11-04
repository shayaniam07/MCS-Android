plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mcs.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcs.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.foundation:foundation:1.8.2")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
