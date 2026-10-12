#!/usr/bin/env bash
# Package the staged native libraries (tools/android/build_native.sh) into an APK.
#
#   tools/android/build_apk.sh        -> android/app/build/outputs/apk/release/app-release.apk
#
# Extra arguments go to Gradle (e.g. -Papp.versionCode=100 -Papp.versionName=1.0.0); set the RELEASE_*
# signing variables (see android/app/build.gradle.kts) to sign with a release key.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
SDK_TREE="${THPS_SDK:-$(dirname "$REPO")/rexglue-sdk-android}"
cd "$REPO/android"

# The Gradle wrapper is not committed (it is a binary); take SDL3's, which pins the Gradle version
# its Android project is known to build with.
SDL_PROJECT="$SDK_TREE/thirdparty/sdl3/android-project"
if [ ! -x gradlew ]; then
  cp "$SDL_PROJECT/gradlew" gradlew; chmod +x gradlew
  mkdir -p gradle/wrapper
  cp "$SDL_PROJECT/gradle/wrapper/gradle-wrapper.jar" "$SDL_PROJECT/gradle/wrapper/gradle-wrapper.properties" gradle/wrapper/
fi
echo "sdk.dir=$ANDROID_HOME" > local.properties

ls app/src/main/jniLibs/arm64-v8a/*.so >/dev/null 2>&1 || { echo "no native libraries staged: run build_native.sh first"; exit 1; }
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}" ./gradlew --no-daemon -Pthps8.sdk="$SDK_TREE" assembleRelease "$@"
APK=app/build/outputs/apk/release/app-release.apk
ls -la "$APK"
