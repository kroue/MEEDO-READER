import java.time.LocalDate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)         // Kotlin Symbol Processing for Room
    alias(libs.plugins.hilt)        // Hilt dependency injection
    alias(libs.plugins.google.services) // Firebase
}

android {
    namespace = "com.waterdistrict.meterreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.meedo.billing"
        minSdk = 26
        targetSdk = 35
        // Raised for every build handed out — Android refuses to install an
        // APK whose versionCode is lower than the one already on the phone,
        // and with equal codes there is no way to tell two builds apart.
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Shown on the About screen, so a reader reporting a problem can say
        // which build is on the phone. Date only, not a timestamp: a value
        // that changed every minute would invalidate the build config — and
        // every compile that depends on it — on every single build.
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
    }

    // Room writes a JSON schema per database version here, and every migration
    // is written and tested against those files. Previously `exportSchema` was
    // true on the @Database but no location was configured, so seven versions
    // of schema history were silently never written — and with destructive
    // fallback also enabled, a schema change simply wiped unsynced readings
    // instead of migrating them. Commit app/schemas/ along with the code.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    // Release signing. Credentials come from keystore.properties (gitignored)
    // or from environment variables in CI — never from this file. Without a
    // signingConfig, `assembleRelease` produced an unsigned APK that no device
    // will install, so releases were being built and hand-signed ad hoc.
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties().apply {
                if (propsFile.exists()) propsFile.inputStream().use { load(it) }
            }
            val path = props.getProperty("storeFile") ?: System.getenv("MEEDO_KEYSTORE_PATH")
            if (path != null && file(path).exists()) {
                storeFile = file(path)
                storePassword = props.getProperty("storePassword") ?: System.getenv("MEEDO_KEYSTORE_PASSWORD")
                keyAlias = props.getProperty("keyAlias") ?: System.getenv("MEEDO_KEY_ALIAS")
                keyPassword = props.getProperty("keyPassword") ?: System.getenv("MEEDO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Falls back to unsigned when no keystore is configured locally, so
            // a developer without the signing key can still build a release.
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile?.exists() == true }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        // Off by default since AGP 8, and the About screen reads the version
        // name, version code and build date from BuildConfig.
        buildConfig = true
    }
}

dependencies {
    // ── Core ──────────────────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // ── Jetpack Compose ───────────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // ── Room Database ─────────────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)          // Flow support
    ksp(libs.androidx.room.compiler)

    // ── WorkManager ───────────────────────────────────────────────────────────
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)          // Hilt + WorkManager
    ksp(libs.androidx.hilt.compiler)                 // generates HiltWorkerFactory bindings for @HiltWorker classes

    // ── Hilt DI ───────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ── QR Code / Barcode generation (ZXing) ─────────────────────────────────
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)     // for QR Bitmap generation

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ── Permissions ───────────────────────────────────────────────────────────
    implementation(libs.accompanist.permissions)

    // ── Testing ───────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)

    // ── Firebase ──────────────────────────────────────────────────────────────
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
}
