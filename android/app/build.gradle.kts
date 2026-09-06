import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("com.chaquo.python")

    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.batgaurish.soiboi"
    compileSdk = 37
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
            storeFile = keystoreProperties.getProperty("storeFile")?.let { file(it) }
            storePassword = keystoreProperties.getProperty("storePassword")
        }
    }

    defaultConfig {
        applicationId = "com.batgaurish.soiboi"

        // Chaquopy ships a CPython runtime per ABI, and 3.12 exists only for
        // arm64-v8a and x86_64. Flutter otherwise builds a fat APK that also
        // includes armeabi-v7a, which fails configuration outright -- so the
        // set is cleared rather than appended to.
        //
        // arm64-v8a covers every phone made in the last decade; x86_64 is for
        // the emulator.
        ndk {
            abiFilters.clear()
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug" 
        }
    }

    configurations.all {
        resolutionStrategy {
            force("androidx.appcompat:appcompat:1.6.1")
            force("androidx.fragment:fragment:1.6.2")
        }
    }
    
}

chaquopy {
    defaultConfig {
        // 3.12 rather than 3.10: m3u8 pulls backports-datetime-fromisoformat
        // on Python < 3.11, and that has no Android wheel. gamdl's native
        // muxer is built abi3, so it is unaffected by the version choice --
        // it only has to be linked against the matching Chaquopy libpython.
        version = "3.12"

        // Chaquopy resolves pip requirements at build time using a host
        // interpreter of the same version, which must therefore be 3.12 --
        // overridable for systems whose python3.12 is not on PATH.
        buildPython(System.getenv("CHAQUOPY_PYTHON") ?: "python3.12")

        pip {
            // Three packages come from a locally built repository rather than
            // PyPI, because none of them installs here as published. See
            // tools/build_android_wheels.py for what each repair is and why.
            options("--find-links", "${project.projectDir}/../pip-repo")

            // gamdl pulls the rest of the tree (mutagen, yt-dlp, httpx, m3u8,
            // click, pywidevine...) through its own dependency metadata.
            install("gamdl==3.8.5")
        }
    }

    sourceSets {
        getByName("main") {
            // The same package the desktop build runs, so a fix on one
            // platform is a fix on both.
            srcDir("../../pipeline")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

flutter {
    source = "../.."
}
