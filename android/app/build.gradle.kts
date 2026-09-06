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

        // Chaquopy needs a matching host interpreter to resolve pip
        // requirements at build time. Arch ships 3.12/3.14, so this points at
        // a uv-managed 3.10 rather than requiring a system package.
        buildPython(System.getenv("CHAQUOPY_PYTHON") ?: "python3.12")

        pip {
            // gamdl's pure-Python dependency tree. gamdl itself is not here:
            // its _ammuxer extension is Rust with no Android wheel on PyPI, so
            // it is supplied separately as a locally built wheel.
            install("mutagen>=1.47.0")
            install("httpx>=0.28.1")
            install("httpx-retries>=0.4.6")
            install("m3u8>=6.0.0")
            // NOT YET RESOLVED: pywidevine's dependency chain does not
            // install under Chaquopy. pywidevine 1.9 needs pycryptodome
            // >=3.23 (Chaquopy tops out at 3.21); pinning 1.8.0 pulls pymp4,
            // whose 1.3+ releases hard-pin construct==2.8.8, and neither that
            // construct nor pymp4 1.2.0 has a build available here.
            //
            // Downloading therefore cannot work on Android until this is
            // sorted -- most likely by vendoring a patched pymp4, or building
            // the wheels against Chaquopy directly. The capabilities probe
            // reports pywidevine as missing, so the UI says so rather than
            // failing at download time.
            install("yt-dlp>=2025.10.22")
            install("click>=8.3.0")
            install("colorama>=0.4.6")
            install("structlog>=25.5.0")
            install("async-lru>=2.0.5")
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
