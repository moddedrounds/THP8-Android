# Tony Hawk's Project 8 on Android

An **Android (arm64) port** of [Project8Recomp](https://github.com/theokyr/Project8Recomp), the
static recompilation of Tony Hawk's Project 8 (Xbox 360). The game's PowerPC code is translated to
C++ ahead of time from *your* `default.xex` and compiled for Android, on a patched
[ReXGlue SDK](https://github.com/rexglue/rexglue-sdk) with a Vulkan renderer (Mesa Turnip
recommended on Adreno), SDL3 input, an in-game menu and frame pacing / optional frame generation.

Status: playable on the AYN Thor, with 30 and 60 fps modes; busy areas can still dip below 60.
Details, measurements and open items are in [`PROGRESS.md`](PROGRESS.md). Use the same disc release
that upstream Project8Recomp supports (see [README.md](README.md)).

## Players: start here

You do not need a computer.

1. **[Download the APK](https://github.com/moddedrounds/THP8-Android/releases/latest/download/THP8-Android.apk)** and install it (allow installing from your browser / file manager).
2. Make a disc image (`.iso`) from **your own** Tony Hawk's Project 8 Xbox 360 disc (USA/Europe retail (title 415607DD)) and put it on
   the device.
3. Open the app and tap **Select my Project 8 ISO**. Pick your `.iso`. The app extracts it to its
   own storage and checks it: the game executable must be the supported release, and an incomplete
   or damaged image is refused with a message saying which file is broken.
4. Tap **Play**. You can delete the `.iso` afterwards.

Updates: the app checks for new versions itself (or tap **Check for updates**); one tap downloads,
verifies and installs them, keeping your installed game and saves.

Requirements: Android 10 or newer, ARM64, Vulkan, and about 6 GB free in addition to the ISO.
Tested on the AYN Thor (Snapdragon 8 Gen 2); a controller is recommended. Other devices are untested.

## Legal: bring your own game

**No game data is distributed here.** The repository and the APK contain no disc image, no
`default.xex`, no textures, models, sounds, movies or other files from the disc. To play you need
**your own legally obtained copy** of the original Xbox 360 game, dumped from a disc you own; the app
reads it on your device and nothing is uploaded anywhere. Requests for game files or ISOs will be
closed without reply.

## What you need

- A Linux x86_64 machine or **WSL2** (Ubuntu 24.04 is what the port is built on).
- `git`, `python3`, `clang`/`clang++` (20 tested), `cmake` (3.25+), `ninja`,
  OpenJDK **17** (`JAVA_HOME`, default `/usr/lib/jvm/java-17-openjdk-amd64`).
- The Android SDK command-line tools in `ANDROID_HOME` (default `~/android-sdk`) with
  `platforms;android-35`, `build-tools`, `ndk;28.2.13676358` and `cmake;3.31.6`:

  ```bash
  sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.31.6" "platform-tools"
  ```

- An arm64 Android device with a Vulkan 1.1+ GPU. Development and testing were done on an
  **AYN Thor (Snapdragon 8 Gen 2 / Adreno 740, Android 13)**; other devices are untested.

## Building it yourself

The scripts expect the SDK tree and helper checkouts **next to** this repository:

```
work/
  <this repo>/
  rexglue-sdk-android/     created by prepare_sdk.sh
  third_party/             created by fetch_adrenotools.sh (optional)
```

1. Prepare the ReXGlue SDK (clones v0.10.0, downloads the pinned third-party Android base patches,
   applies `patches/rexglue-sdk-android/*.patch`):

   ```bash
   tools/android/prepare_sdk.sh
   ```

2. Build the host recompiler:

   ```bash
   tools/android/build_host_cli.sh
   ```

3. Optional: fetch the Mesa Turnip driver the app can bundle (MIT, pinned by SHA-256) and
   libadrenotools (custom driver loading). Without them the app uses the system Vulkan driver.

   ```bash
   tools/android/fetch_turnip.sh
   tools/android/fetch_adrenotools.sh
   ```

4. Extract **your** disc image (an XISO or a redump-style XGD2/XGD3 image) to a folder:

   ```bash
   python3 tools/android/xdvdfs.py /path/to/your-own-dump.iso /path/to/game
   ```

5. Translate your `default.xex`, build the native libraries and the APK:

   ```bash
   tools/android/codegen.sh /path/to/game
   tools/android/build_native.sh
   tools/android/build_apk.sh
   ```

   The APK lands in `android/app/build/outputs/apk/release/app-release.apk`.

## Installing on the device

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Copy the extracted game folder (the one holding `default.xex`) to the app's data folder on the device,
`Android/data/<package>/files/game/` (the package name is in `android/app/build.gradle.kts`).
`adb` cannot create folders under `Android/data` directly; create the tree with
`adb shell mkdir -p` first, or use `tools/android/windows/push_game.ps1`.

Saves, logs and caches live next to it in `files/saves`, `files/logs`, `files/cache`. Extra launch
options can be put one per line in `files/args.txt`.

## Developer scripts

`tools/android/windows/*.ps1` are the test and benchmark scripts used during development (install,
launch, menu driving, frame-time and power capture over adb). They assume `adb` on `PATH` (or `ADB`
set to its path) and a work folder named in the first lines of each script: edit those to your own
layout. `PROGRESS.md` is the full development log.

## Credits

- [Project8Recomp](https://github.com/theokyr/Project8Recomp) and its contributors: the
  recompilation this port is built on.
- [ReXGlue SDK](https://github.com/rexglue/rexglue-sdk), [SDL](https://libsdl.org),
  [Mesa Turnip](https://docs.mesa3d.org/drivers/freedreno.html) via
  [AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers),
  [libadrenotools](https://github.com/bylaws/libadrenotools), and the Android base patches from
  [hells-gate-recomp-android](https://github.com/deivid22srk/hells-gate-recomp-android).

Tony Hawk's Project 8 is a trademark of its respective owners. This project is not affiliated with or
endorsed by them.
