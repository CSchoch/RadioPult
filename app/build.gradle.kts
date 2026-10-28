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
        // Raise both before tagging a release (tag V<versionName>, see README › Releases).
        // F-Droid builds from the tagged source and reads the version from here.
        versionCode = 8
        versionName = "1.7.0"
    }

    // Release signing comes from environment variables (set by CI from repository secrets).
    // Without them the release build falls back to the debug key so it is still installable.
    val releaseKeystore = System.getenv("RADIOPULT_KEYSTORE_FILE")?.let { file(it) }
    signingConfigs {
        // Debug builds use the keystore checked into the repo instead of the per-machine
        // ~/.android/debug.keystore, so test APKs built anywhere install over each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        debug {
            // Separate app next to the released one, so test builds never block release updates
            applicationIdSuffix = ".debug"
        }
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
    // XML-Parser für die Tests der Antwortauswertung (auf dem Gerät liefert Android ihn mit)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
