import java.util.Properties

plugins {
    // AGP 9 ships built-in Kotlin support, so org.jetbrains.kotlin.android is NOT
    // applied here - adding it would conflict. The Compose compiler plugin is still
    // required and is what pulls in the Kotlin Gradle Plugin version.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is opt-in. The keystore and its passwords are local-only
// (`keystore.properties` is gitignored), so a fresh clone still configures and builds
// normally: assembleDebug works, and assembleRelease produces an unsigned APK rather
// than failing.
//
// These have to live outside the `android {}` block: inside it, `java` resolves to the
// Gradle java extension rather than the package, so `java.util.Properties` would not
// compile.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.audioprobe"

    // AndroidX Compose 1.12.x / core 1.19.0 / lifecycle 2.11.0 require compiling
    // against API 37 in their AAR metadata.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.audioprobe"
        // AudioPlaybackConfiguration / AudioManager.getActivePlaybackConfigurations
        // need API 26; Shizuku itself needs API 23+.
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v1 covers API < 24; v2/v3 are what modern devices actually verify.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The Shizuku user service is declared through AIDL.
        aidl = true
        // Used to pass BuildConfig.DEBUG into Shizuku.UserServiceArgs.debuggable().
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // The parsers are pure Kotlin, but return defaults rather than throwing
            // "not mocked" if a stray Android call ever creeps into the JVM tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    debugImplementation(libs.androidx.ui.tooling)

    // Shizuku: privileged shell identity without root.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // The parsers are pure Kotlin and are verified against dumps captured from real
    // devices (app/src/test/resources/fixtures).
    testImplementation(libs.junit)
}
