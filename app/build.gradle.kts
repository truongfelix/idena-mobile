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
        versionCode = 19
        versionName = "0.1.18"
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
            // Same key as the debug builds: the release installs as an update and keeps the chain and
            // the imported key (a new signing key would need an uninstall, which deletes both).
            signingConfig = signingConfigs.getByName("debug")
            // Test phase: keeps `adb run-as` for logs and data. Remove before giving the APK to others,
            // with USB debugging on it lets an authorized computer read the node key.
            isDebuggable = true
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
