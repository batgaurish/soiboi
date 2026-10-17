plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.chaquopy)
}

// The gamdl wheels (gamdl, its muxer, bliss) are built locally by soiboi/tools/build_android_wheels.py into pip-repo.
// Without that folder the app still builds and runs: library scans and covers need only mutagen, and downloads report
// that the downloader is missing instead of failing the build.
val pipRepo = file("../pip-repo")

android {
    namespace = "app.soiboi.android"
    compileSdk = 37
    packaging { jniLibs { useLegacyPackaging = true } } // the lossless wrapper's programs must be extracted to run

    defaultConfig {
        applicationId = "com.batgaurish.soiboi"
        minSdk = 26
        targetSdk = 36
        versionCode = 150
        versionName = "2.5.0"
        ndk { abiFilters += (System.getenv("SOIBOI_ABIS") ?: "arm64-v8a,x86_64").split(",").map { it.trim() } }
    }
    buildTypes { debug { applicationIdSuffix = ".dev" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }

    sourceSets.getByName("main") {
        // One copy of the UI, compiled into both apps. Fonts ride along as assets.
        kotlin.directories += "../shared-ui/kotlin"
        assets.directories += "../shared-ui/resources"
    }
}

kotlin { jvmToolchain(21) }

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython(System.getenv("CHAQUOPY_PYTHON") ?: "python3.12")
        pip {
            if (pipRepo.exists()) { options("--find-links", pipRepo.path); install("gamdl==3.8.5"); install("bliss_analyze==0.1.0") }
            else install("mutagen")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir("../pipeline")
            exclude("native/**"); exclude("**/__pycache__/**"); exclude("**/*.pyc")
        }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
}
