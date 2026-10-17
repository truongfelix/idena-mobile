plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.truongfelix.idenanode"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.truongfelix.idenanode"
        minSdk = 29
        targetSdk = 35
        versionCode = 104
        versionName = "0.2.0-social.1"
        ndk {
            // The node library is built for 64-bit ARM only.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // R8 drops the unused Kotlin/Compose code (the dex shrinks from ~22 MB to a few MB).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned: scripts/sign-release.sh signs it with the release key, which never enters the
            // repository or GitHub. Not debuggable: with USB debugging on, `adb run-as` would let an authorized
            // computer run code as the app, which can open the node key with the app's Keystore key.
            isDebuggable = false
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
        // False positives with Kotlin 2.0 and Compose 1.7 (BOM 2024.12.01): it flags every produceState,
        // also those that assign value first thing. Turn it back on with the next Compose update.
        disable += "ProduceStateDoesNotAssignValue"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // The Idena node: scripts/build-node-aar.sh builds it from go/mobile.
    implementation(files("libs/idena-node.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR codes of the address (Receive), drawn on the phone.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for the JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
