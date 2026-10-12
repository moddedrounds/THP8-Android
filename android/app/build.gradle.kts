// The APK only packages: the native libraries are built separately by tools/android/build_native.sh
// (clearer logs and incremental builds than driving CMake through Gradle) and staged into jniLibs.
//
// A build WITH the game's translated code inside is for the user's own devices only: never publish it.

plugins {
    id("com.android.application")
}

// SDL3's Java classes (SDLActivity and friends) are used straight from the SDK tree's SDL checkout.
val sdkTree: File = (findProperty("thps8.sdk") as String?)?.let { file(it) }
    ?: rootProject.projectDir.parentFile.parentFile.resolve("rexglue-sdk-android")

// Launcher art made from the owner's own game by tools/android/make_launcher_art.py (git-ignored):
// background + app icon. Without it the app keeps the system's default icon and a plain launcher.
val artRes: File = rootProject.projectDir.resolve("art-res")
val hasArt = artRes.resolve("mipmap-xxxhdpi/ic_launcher.png").isFile

android {
    namespace = "org.project8recomp.android"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.project8recomp.android"
        minSdk = 29      // memfd / ASharedMemory and a linker with the namespaces the SDK needs
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m3"
        ndk { abiFilters += "arm64-v8a" }
        manifestPlaceholders["appIcon"] =
            if (hasArt) "@mipmap/ic_launcher" else "@android:drawable/sym_def_app_icon"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the local debug key: this APK is for the owner's own devices, not a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // The SDK dlopen()s librexgpu-xenos.so by path from the native library directory, so the
            // libraries must exist as real files there rather than stay compressed inside the APK.
            useLegacyPackaging = true
        }
    }

    // Bundled GPU driver (assets/drivers/...): keep it stored, not deflated, so the app can compare its
    // size cheaply and copy it only when needed.
    androidResources {
        noCompress += listOf("so")
    }

    sourceSets {
        getByName("main") {
            java.srcDir(sdkTree.resolve("thirdparty/sdl3/android-project/app/src/main/java"))
            if (hasArt) res.srcDir(artRes)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
