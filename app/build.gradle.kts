plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
}

// Use the passed property, fallback to "1.0.0" for normal builds
val vName = project.findProperty("versionName") as String? ?: "1.0.0"

// Auto-generate versionCode from versionName (e.g., 0.2 → 200)
val vCode =
    vName
        .split(".")
        .map { it.toIntOrNull() ?: 0 }
        .let { parts ->
            parts.let {
                (it.getOrElse(0) { 0 }) * 10000 +
                    (it.getOrElse(1) { 0 }) * 100 +
                    (it.getOrElse(2) { 0 })
            }
        }

android {
    namespace = "com.amedeo.zapperiptv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.amedeo.zapperiptv"
        minSdk = 21
        targetSdk = 35
        versionName = vName
        versionCode = vCode
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true
    }

    // --- Conditional signing config (only used when environment variables exist) ---
    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_FILE_PATH")
        if (keystorePath != null) {
            create("ciRelease") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Use the CI signing config if available, otherwise leave unsigned
            signingConfigs.findByName("ciRelease")?.let {
                signingConfig = it
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        viewBinding = true
        buildConfig = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity)

    // Arch Components
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // UI
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.tvprovider)

    // Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.rtsp)

    // JSON / Data
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
}

// ktlint configuration
ktlint {
    version.set("1.5.0")
    android.set(true)
    outputToConsole.set(true)
}

// Tasks to run ktlint
tasks.named("check") {
    dependsOn("ktlintCheck")
}

// Automatically format Kotlin files before compilation
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("ktlintFormat")
}

tasks.register("ktlintFormatAll") {
    group = "verification"
    description = "Format all Kotlin files with ktlint"
    dependsOn("ktlintFormat")
}
