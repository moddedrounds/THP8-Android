# Porting plan: Tony Hawk's Project 8 on Android (AYN Thor)

Status: M2 deliverable, 2026-10-09. Written from the two reference ports, the Project8Recomp
sources, and experiments run on this machine today. Every claim is marked **verified** (I ran it
or read the exact code), **reported** (a reference port's docs say so), or **unverified**.

## 0. Verdict

Feasible, and unusually well precedented. The closest reference, **NFSMW-Recompiled-Mobile**,
is the *same stack* as ours (ReXGlue SDK v0.10.0, the SDK's own Xenos-to-Vulkan renderer, SDL3)
and it **boots, reaches the menu and plays on a Snapdragon 8 Elite** (reported).

- Getting THP8 to compile, launch and render on the Thor: **moderate work, low risk** - most of the
  platform layer already exists in public form.
- Getting it to a *stable* frame rate: **the real unknown**. NFSMW is CPU-bound at 28-33 fps in
  heavy scenes on a faster chip than the Thor's (reported). THP8 on PC issues 700-2,450 draws per
  frame (verified, M1 log). My rough estimate (unverified, to be replaced by a measurement in M4)
  is that 30 fps is plausible and 60 fps in heavy scenes is not guaranteed.

## 1. What I read

| Source | What it is | Licence | Use to us |
| --- | --- | --- | --- |
| [NFSMW-Recompiled-Mobile](https://github.com/victorgbd/NFSMW-Recompiled-Mobile) (`docs/android.md`, `android/`, `tools/android/`) | ReXGlue v0.10.0 Android port, Snapdragon 8 Elite / Adreno 830 | **GPL-3.0** | Primary blueprint: build, app entry, driver loading, perf findings |
| [LibertyRecompAndroid](https://github.com/vaduur/LibertyRecompAndroid) (`docs/android/PORTING.md`, `BUILDING.md`) | GTA IV Android port, own "native" renderer, SD 865 / Adreno 650 | **GPL-3.0** | Second opinion: launcher/installer, Turnip proxy, lifecycle, audio priority, vsync |
| [hells-gate-recomp-android](https://github.com/deivid22srk/hells-gate-recomp-android) (`patches/sdk/*android*.patch`) | The base **Android patch for ReXGlue v0.10.0** that NFSMW downloads | **none declared** | Required base layer; cannot be redistributed, see section 9 |
| [libadrenotools](https://github.com/bylaws/libadrenotools) | Loads a custom Vulkan driver (Turnip) into an app | BSD-2-Clause | Optional GPU driver path |

## 2. What I verified today

1. **The Android SDK patches apply to our SDK.** I applied `rexglue-sdk-v0.10.0-android.patch` and
   `-android-perf.patch` (pinned commit `0a79f938`) to a clean v0.10.0 tree: both apply cleanly.
2. **They coexist with THP8's own SDK patch.** The two sets touch 4 common files. Applied in the
   order *Android, then THP8*, a three-way merge leaves exactly **2 small conflict hunks in one
   file** (`src/graphics/vulkan/command_processor.cpp`: an `#include` list and a diagnostic block).
   Mechanical to resolve. (`mmio_handler.cpp` is excluded from the perf patch, as NFSMW does.)
3. **THP8's startup gate needs an extracted folder.** `dump_gate.h` requires `game_data_root` to be
   a directory containing `default.xex` (hash-checked). NFSMW's "read the ISO in place" trick would
   conflict with that gate, so **THP8 on Android uses the same extracted `game/` folder as the PC
   build**. This matches upstream's installer design.
4. **The game code is arm64-safe.** The one x86-specific piece (`guest_vertex_unpack_simd`) has a
   scalar fallback on other CPUs (`guest_vertex_unpack.h`: `THPS_VERTEX_UNPACK_X86_SIMD`), so arm64
   compiles; a NEON version would be a later optimisation. The four performance hooks
   (`guest_swap_wait`, `guest_ring_wait`, `guest_u8x4_unpack`, `guest_vertex_unpack`) use ELF
   weak-alias hooks, which should work on Android (only Apple is excluded). Not yet compiled for
   arm64, so this is read from the code, not tested.
   Linux-only code in `thps_p8_app.h` (`prctl`, `SIGTERM`) is guarded by `__linux__` and has a
   fallback define for `PR_SET_PTRACER`.
5. **Perf counters and the frame-time CSV exist only in non-Release SDK builds** (found in M1).
   Android builds must use RelWithDebInfo for measurements.
6. **Vulkan features the SDK requires** (`vulkan_device.cpp`): `independentBlend`,
   `fragmentStoresAndAtomics`, `vertexPipelineStoresAndAtomics`, `geometryShader`,
   `fillModeNonSolid` (the last two are required everywhere except macOS). NFSMW runs on a modern
   Adreno with these, so I expect the Adreno 740 to pass; the Thor's driver must confirm it (M3 probe).
7. **Cvars NFSMW passes that exist in our SDK:** `vulkan_sparse_shared_memory`,
   `render_target_path_vulkan`, `present_letterbox`, `readback_resolve`,
   `occlusion_query_enable`, `anisotropic_override`, `resolution_scale`, `swap_post_effect`,
   `user_data_root`, `cache_root`. **Do not exist in v0.10.0:** `gpu_backend`, `thread_affinity`
   (NFSMW adds it itself), `audio_maxqframes`, `storage_root`, `vulkan_pipeline_creation_threads`.
8. **Thor hardware** ([retrohandhelds.gg](https://retrohandhelds.gg/ayn-thor-some-thoughts-for-preorder/),
   [heldgames.com](https://heldgames.com/devices/ayn-thor)): Snapdragon 8 Gen 2 / Adreno 740,
   8/12/16 GB LPDDR5X, Android 13, **6" 1920x1080 120 Hz AMOLED** top screen, **3.92" 1240x1080
   60 Hz** bottom screen, active fan, built-in gamepad controls. (The Thor *Lite* is a Snapdragon
   865 and is a different, weaker target. This plan assumes Base/Pro/Max.)

## 3. Target architecture

```
 PC / WSL2 (build machine)                                   AYN Thor (Android 13)
 -------------------------                                   ---------------------
 rexglue-sdk v0.10.0
   + Android patch (downloaded, pinned)
   + THP8 patch (rebased)         --> host `rexglue` CLI
   + our Android additions                |  codegen (your default.xex)
                                          v
 generated-android/*.cpp (121 MB, never committed)
        |  NDK clang, arm64-v8a
        v
 libmain.so (generated game + app + hooks)  +  librexruntime.so  +  librexgpu-xenos.so
 libSDL3 (inside rexruntime)  +  [libadrenotools hooks, optional Turnip]
        |  Gradle
        v
 thps8.apk  ---- adb install ---->  Launcher activity (status / Play / logs / driver)
                                     |  starts, in its own process (:game)
                                     v
                                    GameActivity (SDLActivity) -> SDL_main -> ThpsP8App
                                     reads  <files>/game/  (extracted disc, with default.xex)
```

Repo layout (Android changes stay separate so upstream can be rebased):

```
android/                         Gradle project + Java/Kotlin shell (new)
android/app/src/main/cpp/        CMakeLists.txt, android_main.cpp, thps_p8_android_app.cpp (new)
patches/rexglue-sdk-android/     our patches ON TOP of the third-party Android base (new)
tools/android/                   fetch/prepare/codegen/build/install scripts (new)
src/game, src/launcher, config   untouched (the Windows/Linux build keeps working)
```

## 4. Component plan

### 4.1 Toolchain (WSL2)
NDK **r28.2.13676358** (what NFSMW verified with this exact patch; the patch ships a
`jthread_compat.h` so older libc++ is fine), CMake **3.31.6** from the Android SDK (the SDK needs
3.25+), JDK 17, Android command-line tools (no Android Studio needed), Gradle wrapper. `adb`: use
the **Windows** `adb.exe` from WSL (USB passthrough into WSL is fragile) or wireless adb.
Prerequisite: accepting the Android SDK licence on your behalf - I will ask first.

### 4.2 The SDK for Android
Order: **v0.10.0 -> third-party Android patch (+perf, minus `mmio_handler.cpp`) -> THP8
`0001` rebased -> our Android additions** (libadrenotools, app hooks). `0002` (MoltenVK) is
macOS-only and is skipped. A script downloads the third-party patch at build time (never stored in
git). Our rebased patch must contain only our own change, so conflicting files use `--unidiff-zero`
(NFSMW does the same, see `sdk/README.md`). Android needs **its own SDK tree**: the patch changes
the codegen template (`pch_h.inja`: ARM64 memory barriers, runtime physical-memory offset), so
the desktop SDK and generated code must not be shared. D3D12 off, Vulkan on, Tracy off.

### 4.3 Code generation
Build the **host** `rexglue` from the Android-patched SDK on Linux x86-64, then run it on the same
manifest (`config/thps_p8_manifest.toml`, new output directory `generated-android/`). The output
(237 files, 121 MB on PC) is compiled by NDK clang. Clang can need >2 GB per file: compile the
generated sources 4-6 at a time (62 GB RAM available here). Reference: NFSMW `generar_codigo.py`.

### 4.4 Native app library (`libmain.so`)
`android_main.cpp` (adapt NFSMW's): `SDL_main` hands the `JavaVM`/activity to the SDK, calls the
three `AndroidInitialize()` functions (**the SDK never calls them itself**; skipping the memory one
gives "Unable to reserve the 4gb guest address space"), then runs `GetCreator("thps_p8")`.
Link rules to copy: never link `rexui`/`rexcore` into `libmain` (duplicates the cvar registry),
`--export-dynamic`, `useLegacyPackaging=true` (the SDK `dlopen`s `librexgpu-xenos.so`). Build flags
worth taking from Liberty: `-march=armv8.2-a` (inline LSE atomics; the X3/A7xx cores support it),
`-fno-emulated-tls`, ThinLTO for `libmain`.

### 4.5 Java shell
SDL3's `SDLActivity` subclass for the game, plus a small launcher activity: game status, **Play**,
driver choice, log sharing. The game runs in its own process (`:game`) because native libraries
cannot be reloaded (both references do this). Manifest: `sensorLandscape`, Vulkan 1.1 feature flag,
game mode, `profileable` (lets `simpleperf` run without root).

### 4.6 GPU driver
1. **Stock Qualcomm driver first.** It works on NFSMW's Adreno 830 (reported).
2. **Turnip via libadrenotools as an option.** My understanding is that Turnip support for Adreno
   7xx is generally good (unverified here), but NFSMW's
   Turnip on the 830 hung in the tiled path and only ran with `TU_DEBUG=sysmem` at ~44 fps vs 60
   (reported). Liberty found `sysmem` faster on its renderer. Treat Turnip as an experiment, not a
   default; the 740 result is **unverified**.
3. A tiny **Vulkan probe** (device, driver version, the required features above) ships in the
   first APK so we learn the Thor's capabilities before the game is involved.

### 4.7 Game data and installer
- **M3/M4:** copy the already-extracted folder with `adb push C:\thp8-work\game` to
  `Android/data/<package>/files/game`. Zero new code, matches the dump gate.
- **Later (optional):** an in-app installer: pick the ISO, run the existing extractor
  (`thps_p8_identify`'s code, shared via JNI like Liberty's `libliberty_install.so`), 4.7 GB copy with
  a progress bar. Reading an ISO by path needs "All files access" (Android 11+); the SAF picker
  returns `content://` URIs the extractor cannot open (both references hit this).
- Saves (`user_data_root`) and shader/pipeline cache (`cache_root`) go in app-specific storage.

### 4.8 Input
The Thor's built-in controls should enumerate as a standard Android gamepad, which SDL3 maps and
the SDK already reads (NFSMW: Bluetooth pads work). Keep `--mnk_mode=true` like the PC launcher.
**Unverified:** button mapping, Start/Back, and whether the Thor reports one or two devices.
No on-screen overlay is planned (the device has real controls).

### 4.9 Audio
Try the SDK's stock SDL3 audio first. NFSMW had to write an AAudio backend because the phone
advertised 6 channels and SDL's 6-channel stream was silent, and it still ships with **choppy XMA
audio it could not fix** (reported, 8 hypotheses ruled out). Liberty fixed crackle by giving the
"Audio Worker"/"XMA Decoder" threads `nice -16` (reported). Risk: **medium**. Plan: stock first;
AAudio backend and thread-priority tweak as fallbacks (written fresh, see section 9).

### 4.10 Display and frame pacing
The game renders 16:9 (our PC run: 1280x720, presenter scales). The Thor's top screen is exactly
1920x1080, 120 Hz. We keep the guest at its 60 Hz vblank (**verified on PC: gameplay ran at 60 fps with
vsync on, M1**), which maps to every second 120 Hz refresh. NFSMW and Liberty both found vblank
timing matters: precise vblank deadlines (already in THP8's SDK patch) and adaptive vsync so a 17.9 ms
frame costs 17.9 ms, not 33.3 ms (NFSMW `parche_vblank`); Liberty drives vblank from `AChoreographer`.
Also to verify: the activity opening on the **top** screen rather than the bottom touchscreen.

### 4.11 Performance levers (M5)
From NFSMW (all reported, to be re-measured on the 740): fix per-frame pipeline recreation in the
presenter; second Vulkan queue for present (CP was blocked 81% waiting on the queue mutex);
futex-wake instead of spinning when the command ring is full (one core was at 100%); cheaper
write-watch page-fault handling; vsync/vblank timing; **thread affinity** (+17% on their 8 Elite; the
Gen 2 has one X3, four mid, three little cores, so the policy must differ); `fence` polling via
`vkGetFenceStatus`; scene in one pass instead of 3 tiles (+25-45% in racing; depends on whether THP8
uses tiling, **unverified**). Several of these are in the Android patch's "perf" half; the rest are
NFSMW-specific and would be rewritten for THP8. Cost model to confirm: ~7.7 us CPU per draw on
NFSMW's 8 Elite.

### 4.12 Diagnostics and dev loop
Logs to `<files>/logs/` (some ROMs cap `logcat`), `--perf_log_csv` for frame times (RelWithDebInfo),
a "share log" button so you can send me a log without a PC cable, and helper scripts (install, launch
with args, pull logs, thermal status). **Measure cold**: NFSMW's phone went from thermal state 0 to 3
in two minutes and lost clocks; the Thor's fan should help but is unverified.

## 5. THP8-specific items
- **The "30 fps cap" is vsync** (M1 finding): 60 fps when a frame fits in 16.7 ms, otherwise it
  quantises to 33.3 ms. The in-level cutscene bug only appears with vsync **off** (~200 fps). On the
  Thor the guest cannot exceed 60, so the cutscene problem should not arise; I will not uncap, and
  M5's "60 fps" question becomes *"can the Thor hold 16.7 ms?"*, answered by cost reduction, not by
  a cutscene-aware cap. If we ever target >60, then a cap during cutscenes is the right fix.
- The "Performance" preset (17 title flags in `settings.cpp`) is passed on Android too.
- The upstream **launcher/supervisor** (`thps_p8_gui`, `thps_p8_launch`) is not used on Android; the
  Java shell replaces them. `src/launcher` stays untouched.
- THP8's first run compiles pipelines (M1: stutter for the first minutes). A persistent Vulkan
  pipeline cache (NFSMW's `parche_cache_pipelines`) matters more on a phone-class CPU.
- The generated APK **contains your translated game**: install on your own devices only.

## 6. Risks (ranked)

| # | Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- | --- |
| 1 | CPU-bound below 30 fps in heavy scenes | Medium-high | High | M4 measure; section 4.11 levers; lower resolution/MSAA/post if applicable |
| 2 | Adreno 740 driver bug or missing feature | Medium | High | Vulkan probe first; Turnip as alternative driver |
| 3 | Audio crackle/XMA stutter (NFSMW never solved it) | Medium | Medium | Stock SDL first, AAudio + thread priority fallbacks |
| 4 | Long load stalls (NFSMW: 5-78 s with all threads asleep, cause unknown) | Medium | Medium | Watch in M4; reuse its thread-wait dump idea |
| 5 | Base Android patch unlicensed and unmaintained | Certain | Medium | Pin commit, download at build time; ask author or rewrite later |
| 6 | Memory pressure (4 GB guest address reserve + ~1 GB resident) | Low-medium | Medium | Thor has 8-16 GB; test on the 8 GB model if that is yours |
| 7 | Thermal throttling | Medium | Medium | Cold-start measurements; fan; cap if needed |
| 8 | Activity lands on the bottom screen / dual-screen quirks | Low-medium | Low | Launch display option; test |
| 9 | Hook addresses wrong on a different executable | Low | High | Only one supported disc, hash-gated (already enforced) |

## 7. Unverified (will be settled by running, not by reading)
Thor's Vulkan feature set and driver version; whether stock SDL3 audio works; gamepad mapping; the
frame rate and draw cost on the Adreno 740; whether THP8 uses predicated tiling; whether
Turnip loads and which mode; which screen the game opens on; memory use; Android 13 behaviours of
the patch (NFSMW tested Android 16, Liberty Android 13); how long the first native build takes (237
generated files; NFSMW only says its first build "takes a while").

## 8. Milestones

**M3 - compiles, then launches** (agent-driven; you only plug in the Thor)
1. Install NDK/SDK/Gradle in WSL; fetch the third-party patch; rebase our patch; commit as
   `patches/rexglue-sdk-android/`.
2. Build the Android-patched host `rexglue`, regenerate code, cross-compile all native libraries.
   *Done when: `libmain.so`, `librexruntime.so`, `librexgpu-xenos.so` build for arm64-v8a.*
3. Package the APK (probe + game code). *Done when: it installs on the Thor and the launcher opens,
   the probe screen shows the GPU/driver/features, and the game process starts and logs.*
   **You:** enable Developer options + USB debugging (I will give exact taps), plug in, approve the
   computer, paste back the probe text.

**M4 - boots and renders on the Thor**
`adb push` the extracted game (4.7 GB), launch, fix crashes from your pasted logs, get gameplay
rendering with input and audio. *Done when: you can skate in a level on the Thor.*
**You:** run it, describe what you see, send the log (button or `adb pull`).

**M5 - performance**
Cold-start frame-time CSV on the Thor, profile (simpleperf), apply section 4.11 levers one by one,
re-measure each. *Done when: stable 30 fps in the worst scene; then the 60 fps question is answered
with data.*

## 9. Licensing and legal
- Both Android references are **GPL-3.0**; Project8Recomp is **BSD-3-Clause**. I will **not copy their
  source into this repo**. I use them as design references and write the Android layer fresh, which keeps
  the repo BSD. If you ever want literally their files, they must live in a separate GPL-labelled
  folder and the combined binary becomes GPL on redistribution.
- The base Android SDK patch has **no licence**. It is downloaded at build time from a pinned commit
  and never committed or shipped. Public release would need the author's permission or a rewrite.
- No game data, `default.xex`, generated sources, or APKs are ever committed (`.gitignore` already
  covers them). The APK embeds your translated game: keep it private.

## 10. Decisions I need from you
1. **Android SDK licence:** OK for me to accept it for the SDK/NDK/command-line downloads (~2-3 GB)?
2. **Thor model and RAM** (Base/Pro/Max, 8/12/16 GB), and that it is not the Lite.
3. **Connection:** USB-C cable to this PC (preferred) or wireless debugging?
4. **Scope of M3:** minimal (stock driver only, adb-pushed game data), as planned above, or also the
   in-app installer now? I recommend minimal first.
