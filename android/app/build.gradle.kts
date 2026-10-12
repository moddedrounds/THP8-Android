// The APK only packages: the native libraries are built separately by tools/android/build_native.sh
// (clearer logs and incremental builds than driving CMake through Gradle) and staged into jniLibs.
//
// Signing: a release key when RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
// RELEASE_KEY_PASSWORD are set in the environment (keep the keystore out of the repository), otherwise
// the local debug key. -Papp.versionCode=N / -Papp.versionName=X override the version.

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
        versionCode = (findProperty("app.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("app.versionName") as String?) ?: "0.1.0-dev"
        ndk { abiFilters += "arm64-v8a" }
        // In-app update checks read this update.json; empty (the default) = no checks (dev builds).
        resValue("string", "update_manifest_url", (findProperty("app.updateUrl") as String?) ?: "")
        // The ISO installer accepts exactly the releases in src/common/supported_dumps.h, the table the
        // launcher and the game's own gate share upstream: read it rather than keep a copy.
        val dumps = rootProject.projectDir.parentFile.resolve("src/common/supported_dumps.h").readText()
        val accepted = Regex("\"([0-9a-f]{64})\"").findAll(dumps).map { it.groupValues[1] }.toList()
        check(accepted.isNotEmpty()) { "no SHA-256 rows found in src/common/supported_dumps.h" }
        resValue("string", "accepted_xex_sha256", accepted.joinToString(","))
        manifestPlaceholders["appIcon"] =
            if (hasArt) "@mipmap/ic_launcher" else "@android:drawable/sym_def_app_icon"
    }

    val releaseStore = System.getenv("RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
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
            // Third-party licence notices (android/licenses/NOTICE.txt), shown by the launcher.
            assets.srcDir(rootProject.projectDir.resolve("licenses"))
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
