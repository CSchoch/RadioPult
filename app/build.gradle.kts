plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.radiopult.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.radiopult.app"
        minSdk = 26
        targetSdk = 35
        // CI passes these via -PversionCode / -PversionName (see .github/workflows/release.yml)
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 2
        versionName = (project.findProperty("versionName") as String?) ?: "1.1.0"
    }

    // Release signing comes from environment variables (set by CI from repository secrets).
    // Without them the release build falls back to the debug key so it is still installable.
    val releaseKeystore = System.getenv("RADIOPULT_KEYSTORE_FILE")?.let { file(it) }
    signingConfigs {
        if (releaseKeystore != null && releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RADIOPULT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RADIOPULT_KEY_ALIAS")
                keyPassword = System.getenv("RADIOPULT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
