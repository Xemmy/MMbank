plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xemmy.mmbank"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xemmy.mmbank"
        minSdk = 26
        targetSdk = 35
        versionCode = 72
        versionName = "0.46.0"
    }

    // A fixed debug keystore (committed, standard well-known debug password)
    // so every CI-built debug APK shares one signing key. Without this, each
    // GitHub Actions run auto-generates a fresh random key, and Android
    // refuses to install an APK as an update over a different certificate —
    // forcing an uninstall before every new build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Personal sideloaded build: keep minification off so stack traces
            // from the background services stay readable.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        // `assembleRelease` runs lintVital, and AGP 8.7.3's bundled lint CRASHES
        // there rather than reporting a finding:
        //
        //   Unexpected failure during lint analysis of MMBankApp.kt
        //   Found class ...KaCallableMemberCall, but interface was expected
        //   detector: androidx.lifecycle.lint.NonNullableMutableLiveDataDetector
        //
        // An IncompatibleClassChangeError between lint's bundled Kotlin analysis
        // API and the Kotlin it runs against -- a toolchain mismatch, not
        // anything in this source. It took out every release build (the step is
        // continue-on-error, so the workflow stayed green while silently
        // producing no release APK: `Upload Release APK` is gated on
        // steps.build_release.outcome and was skipped every run).
        //
        // Disabling the single issue this detector owns is what lint's own crash
        // message recommends, and costs nothing here: the app uses no
        // MutableLiveData at all, so the detector has nothing to find. Drop this
        // once AGP ships a lint whose analysis API matches the Kotlin plugin.
        disable += "NullSafeMutableLiveData"
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle + Compose
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room (unified ledger storage)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Network (Forex rates + Binance public/read-only API)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Encrypted storage for the Binance read-only API keys
    implementation(libs.androidx.security.crypto)

    // Biometric app lock: BiometricPrompt needs a FragmentActivity host, and
    // ProcessLifecycleOwner drives the lock-on-background behavior.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.process)

    // SQLCipher: at-rest encryption of the Room DB (SecureDatabaseFactory +
    // the one-time plaintext migration in DatabaseEncryptionMigrator).
    implementation(libs.sqlcipher.android)

    // Gate-2 local-AI parsing: expedited background worker for Gemma/LiteRT-LM
    implementation(libs.androidx.work.runtime.ktx)

    // Jetpack Glance: home screen widget (DailyBudgetWidget)
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
