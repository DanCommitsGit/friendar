plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Set by the Release workflow. Local builds use version 1.0 and the debug key.
val releaseVersion: String? = System.getenv("FRIENDAR_VERSION")
val releaseKeystore: String? = System.getenv("FRIENDAR_KEYSTORE_FILE")

// versionCode is major * 10000 + minor * 100 + patch.
val releaseVersionCode: Int? = releaseVersion?.let { version ->
    val parts = version.split(".").map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null && it in 0..99 }) {
        "FRIENDAR_VERSION must look like 1.2.3 with each part under 100, got '$version'"
    }
    parts[0]!! * 10_000 + parts[1]!! * 100 + parts[2]!!
}

android {
    namespace = "io.github.dancommitsgit.friendar"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dancommitsgit.friendar"
        minSdk = 31
        targetSdk = 34
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersion ?: "1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FRIENDAR_KEYSTORE_PASSWORD")
                keyAlias = "friendar"
                keyPassword = System.getenv("FRIENDAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    // The BOM maps material3 to 1.4.0, which lacks the Expressive components. 1.5.0-beta01
    // needs compileSdk 37 and Android Gradle plugin 9.1 or later.
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core.uwb:uwb:1.0.0")

    testImplementation("junit:junit:4.13.2")
}
