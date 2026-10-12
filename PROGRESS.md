# Android port progress

Goal: Tony Hawk's Project 8 (Project8Recomp) running natively on an AYN Thor
(Snapdragon 8 Gen 2, Adreno 740, Android 13). Branch: `android-port`.

No game content is ever committed: no disc image, `default.xex`, extracted
data or generated translation units. `.gitignore` covers them, and
`tools/check_no_game_content.sh` is run before commits that add files.

## Environment

- Host: Windows 11 Pro, i7-13700K, RTX 3090, 128 GB RAM.
- Build environment: WSL2 Ubuntu 24.04 (10 vCPU, 62 GB). Work tree `~/thp8/`:
  - `~/thp8/Project8Recomp` — this repo
  - `~/thp8/rexglue-sdk` — ReXGlue v0.10.0 + `patches/rexglue-sdk/series`
  - `~/thp8/logs` — build logs
- Toolchain: clang 20.1.8 (apt.llvm.org), cmake 3.28, ninja, Rust/xwin (Windows sysroot).
- Disc image kept outside the repo on the Windows side, read from WSL via `/mnt/<drive>`.
- Windows test install: `C:\thp8-work\` (thps_p8.exe + DLLs, `game/` = extracted
  disc, `play_thp8.bat`). Not in git.

## Milestones

| | Milestone | Status |
| --- | --- | --- |
| M1 | Build on PC, boot, playable at 30 fps, frame times | **DONE 2026-10-09** (user playtest: playable, smooth after shader warm-up, occasional stutters) |
| M2 | PORTING_PLAN.md | **DONE 2026-10-09** - see PORTING_PLAN.md; awaiting user answers to its section 10 |
| M3 | arm64 Android build compiles; APK launches | **DONE 2026-10-09**: builds, installs and launches on the Thor; renders the real game |
| M4 | Boots and renders gameplay on the Thor | **in progress**: boots, intro + first level (Suburbia) render correctly at 1080p; audio/controls/stability await the user's hands-on test |
| M5 | Stable 30 fps; investigate 60 fps | not started |

## What works

- Disc verified EXACT (2006 retail, 5,127 files); extracted 4.7 GB in 75 s;
  `default.xex` SHA-256 matches `cfc73234...a5dd`.
- Native Linux SDK build, `rexglue codegen` (12 s, 237 files, 121 MB generated).
- Windows SDK (Vulkan, clang-cl + xwin) and game cross-built; `thps_p8.exe` starts on
  the RTX 3090, creates a Vulkan swapchain, compiles pipelines and draws the real 3D
  intro scene (agent smoke test, 40 s, clean exit code 0).

## What's broken / open

- (fixed) Frame-time CSV was empty with a Release SDK: perf counters are compiled out of
  Release (`$<NOT:$<CONFIG:Release>>` in `rexglue-sdk/CMakeLists.txt`). Build the SDK and game
  as RelWithDebInfo (`rexruntimerd`, `rexgpu-xenosrd`, `TracyClientrd`) - same as `package_release.sh`.
- Not yet verified by a human: controls, audio, gameplay, 30 fps feel.
- Log noise: "Skipped N Vulkan frame presentations ... async placeholder pipeline" during
  first-time shader compilation (expected on first run).

## Commands that worked

```sh
# root deps (from Windows): wsl -d Ubuntu-24.04 -u root -- bash s2_root_deps.sh
cd ~/thp8/rexglue-sdk && git checkout v0.10.0
while IFS= read -r p; do git apply --index ../Project8Recomp/patches/rexglue-sdk/$p; done \
  < ../Project8Recomp/patches/rexglue-sdk/series
git submodule update --init --recursive
# native SDK (provides `rexglue` codegen)
cmake --preset linux-amd64 -DCMAKE_C_COMPILER=clang-20 -DCMAKE_CXX_COMPILER=clang++-20
cmake --build out/build/linux-amd64 --config Release --target install --parallel 10
# disc
thps_p8_identify --identify_disc=<iso> --json                 # EXACT
thps_p8_identify --identify_disc=<iso> --extract_to=<dir>
# codegen (manifest copy with REPLACE_ME -> extracted dir; *.local.toml is gitignored)
rexglue codegen config/thps_p8_manifest.local.toml
ln -s ../config src/config; ln -s ../generated src/generated   # both needed (gitignored)
# Windows cross build
tools/cross/setup_windows_sysroot.sh                           # needs rust/cargo + llvm-20 on PATH
cmake -S rexglue-sdk -B out/build/win-cross-rd -G Ninja -DCMAKE_TOOLCHAIN_FILE=.../windows-msvc.cmake \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo -DREXGLUE_USE_D3D12=OFF -DREXGLUE_USE_VULKAN=ON
cmake --build ... --target install
cmake -S src/game -B build/game-win-rd -G Ninja -DCMAKE_TOOLCHAIN_FILE=... \
  -DCMAKE_PREFIX_PATH=<win sdk prefix> -DTHPS_P8_REXGLUE_HOST_EXECUTABLE=<linux sdk>/bin/rexglue \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo
```

Gotcha: PowerShell mangles inline `wsl bash -c '...'` quoting; write scripts to files and
run `wsl -d Ubuntu-24.04 -- bash /mnt/c/.../script.sh`.

## M1 measurements (user playtest, RTX 3090, 1280x720 windowed, Performance preset, vsync default)

Session 243 s, 13,800 frames (`perf/frames-20261009-133925.csv`, not committed):

| Segment | frames | mean | p50 | p95 | p99 | max |
| --- | --- | --- | --- | --- | --- | --- |
| whole session | 13,800 | 17.64 ms | 16.55 | 18.06 | 33.67 | 1266 |
| after warm-up (t >= 160 s, gameplay, 700-2,000 draws/frame) | 4,968 | 16.80 ms (59.5 fps) | 16.54 | 17.99 | 18.07 | 150.7 |

Post-warm-up hitches: 19 frames > 33 ms, 5 > 50 ms, 4 > 100 ms. Window 120-160 s (first level load +
first-time pipeline compilation) ran 31-56 fps with ~20 hitches per 10 s. The 5 draw-heavy
windows after that held 59-60 fps.

**Finding: the default is not a 30 fps cap.** Every swap carries a distinct frame (draw count
varies smoothly 700-2,000, no alternating empty frames), at ~16.7 ms, so gameplay renders at 60 fps
on the 3090. The launcher's "cap" setting is `--vsync` (default true); it passes no 30 fps flag. The
upstream "30 fps" numbers (Steam Deck, 33.3 ms) fit this: heavy scenes miss the 16.7 ms vblank and
quantise to 33.3 ms. With vsync off the game runs ~200 fps and in-level cutscenes run 3-5x too fast.
So the guest is paced to the 60 Hz vblank, and 30 fps on Android is a quantised fallback, not a
setting. Matters for M5 (capping to 30 deliberately, and the cutscene fix).

## M2 key results (details in PORTING_PLAN.md)

- Closest reference: NFSMW-Recompiled-Mobile (same ReXGlue v0.10.0 + Vulkan Xenos renderer, plays on
  Snapdragon 8 Elite). Both references are GPL-3.0, so we write our Android layer fresh (repo is BSD-3).
- Base Android SDK patch (hells-gate-recomp-android, pinned `0a79f938`, no licence) applies to v0.10.0 and
  coexists with `0001-thp8-runtime-stack.patch`: only 2 conflict hunks in one file
  (`src/graphics/vulkan/command_processor.cpp`). Order: Android -> THP8 -> ours.
- THP8's dump gate needs an extracted folder with `default.xex` (no ISO-in-place trick).
- Unverified until run on the Thor: Vulkan features, audio, input mapping, frame cost on Adreno 740.

## M3 results (2026-10-09)

- Toolchain in WSL: JDK 17, Android cmdline-tools, NDK 28.2.13676358, CMake 3.31.6, platform/build-tools 35,
  Gradle 8.12 + AGP 8.7.3 (SDL3's own Android project versions). Windows `adb.exe` (platform-tools 37.0.1) in
  `C:\thp8-work\tools\platform-tools` for USB.
- `tools/android/prepare_sdk.sh`: v0.10.0 + third-party Android patches (pinned commit + SHA-256) + our THP8
  patch rebased (`patches/rexglue-sdk-android/`). Idempotent; verified from a clean reset.
- `tools/android/codegen.sh`: Android-tree `rexglue` regenerates the game (237 files, 12 s); output differs from the
  desktop one only in ARM64 memory barriers and the runtime physical-memory offset, as expected.
- `tools/android/build_native.sh`: whole game + SDK compile for arm64 in ~3 min on this PC (10 threads). Libraries are
  suffixed `rd` for RelWithDebInfo (`librexruntimerd.so`, `librexgpu-xenosrd.so`): keep the names (the SDK
  finds the GPU plugin by suffix); GameActivity picks the runtime name at run time.
- `tools/android/build_apk.sh`: 21 MB APK (stripped libs). Static checks pass: all DT_NEEDED satisfied by packaged or
  system libraries, SDL JNI present in the runtime, `SDL_main` + `PPCImageConfig` exported from `libmain.so`,
  16 KB segment alignment.
- Gotchas: SDK writes its libraries into its own `out/android-arm64`, not the build dir; Windows has no Python
  (only the Store shortcut), so scripts run inside WSL.
- Not yet verified: the TLS segment alignment is 8 (Liberty's notes say Bionic wants 64 on arm64) - will show up
  as a load failure if it matters.

## First run on the Thor (2026-10-09, AYN Thor, Android 13, QCS8550 = Snapdragon 8 Gen 2, 15.6 GB RAM)

- Vulkan probe: Adreno 740, Vulkan 1.3.128, Qualcomm driver build E031.41.03.47 (Dec 2023); has every
  feature the SDK requires; 3 graphics queues; BC textures yes; shaderFloat64 no (not required).
- Game data: 5,127 files / 5.0 GB pushed in 22 s. `adb push` cannot create directories in Android/data
  ("remote secure_mkdirs: Operation not permitted"): pre-create the tree with `adb shell mkdir -p`, then push `game/.`
  (see `tools/android/windows/push_game.ps1`). PowerShell 5.1 mangles adb's stderr and binary pipes: use `cmd /c`.
- Launch: process `:game` alive, RSS 0.9-1.3 GB, no crash. Game log has no errors; warnings identical to the PC run
  (`d:\data\progress_log` access denied, missing gamecontrollerdb.txt, shader-compile frame drops).
- Intro renders at a steady 60 fps (p50 16.7 ms). In 75 s the game reached the first level (Suburbia) by itself and
  renders correctly. Level load / first-time shader compile: fps dips to ~20 for a while, one 4.5 s stall.
  Thermal status 0 throughout (short run).
- Thor controls enumerate as "Xbox Wireless Controller" (VID 0x2020 PID 0x0112) in SDL.
- Audio: SDL opens the endpoint as 6 ch / 48 kHz; NFSMW's port found 6-channel SDL output silent on a phone.
  NOT yet verified by ear.
- Cosmetic: Android gesture bar visible over the bottom of the image; black bars top/bottom (letterbox).

## Next step

User plays on the Thor: audio, controls, stutter, anything visibly wrong. Then: hide system bars, steady-state frame
times after level load, fix issues found.

## 2026-10-09 (session 2): m4f verified on the Thor

- thps8-m4f.apk (bdb866b) is installed. The game auto-boots into Suburbia (garage, idle player)
  about 60-100 s after launch, so gameplay-like scenes can be measured with no input.
- Steady state in-level: 24-32 fps, 750-1100 draws, frames quantized to 33/50 ms. GPU at its
  680 MHz max and ~92% busy, thermal status 0 (SoC ~90 C): the GPU is the wall, not throttling.
- GPU cost model from the CSV: ~12-15 ms fixed per frame (even at ~170-200 draws, 58 fps) plus
  ~20-25 us per draw. 30 fps needs the in-level frame under 33 ms; it sits at 33-35 ms.
- Cheap knobs tried via args.txt (no rebuild), all within run-to-run noise (<=5-10% per draw):
  --anisotropic_override=0, --gamma_render_target_as_unorm16=false, both together.
  --gpu_timestamp_queries=true costs nothing measurable (E1 run with no args.txt looks the same).
- Level-load time varies by +/-45 s between runs: compare only settled windows.
- Next: per-pass GPU timing to find the ~12 ms fixed cost (MSAA resolve/EDRAM transfers?).

## 2026-10-09 (session 2, part 2): where the frame time goes, and what changed

Reaching real gameplay without a human: `tools/android/windows/to_gameplay.ps1` cold-starts the game
and walks the menus (New Career -> Start Game) with injected gamepad input (`pad.ps1`: sendevent to
the Thor's pad, /dev/input/event9), recognising screens by region colours. It ends in the
Suburbia garage; then `pad.ps1 -Keys START` ends the intro camera shot and the player has control.
`run_exp.ps1 -Args2 ... -Tag x` does all of that plus 45 s of measurement. (The scripts still
carry the session's scratchpad paths; edit the `$sp`/`$old` variables.)

Two distinct scenes (this matters, earlier numbers mixed them up):
- Intro camera shot (first ~40 s in the garage): 1758 draws, 119 passes/frame, 19.4 fps, GPU 50 ms.
- Real gameplay after START: 916 draws, 29.5 fps, GPU 32.5 ms, frames at 33.3 ms with ~3% at 50 ms.
  GPU is at the 33.3 ms budget edge; any 1-2 ms saved would make this a locked 30.

Pass-timing results (`--gpu_timestamp_queries=true --gpu_pass_timing=true`, patch 0006):
- Draw work inside passes is tiny (2.7 ms of 53 in the heavy scene); the rest is between passes:
  ~119 passes/frame at ~0.2-0.4 ms each (fixed per-pass cost, independent of render area).
- Why passes end (`--gpu_pass_reason_stats=true`), heavy scene: 48% a whole-buffer barrier
  TRANSFER_READ -> SHADER_WRITE (scratch buffer reuse between texture loads: each texture load =
  compute decode -> scratch -> copy to image), 31% EDRAM-buffer + render-target -> shader-read
  (resolve dumps, ~40 resolves/frame), 13% render-target changes.
- So the cost is dominated by render-to-texture round trips (resolve -> shared memory -> texture
  load) and per-texture scratch loads, each of which ends the pass. The structural fix is to
  hoist texture loads / ring-allocate scratch like the shared-memory uploads were hoisted (0003),
  or sample resolved render targets directly. Not done.
- Render-area limiting (`--gpu_limit_render_area=true`): works (mean coverage 0.45) but no
  measurable gain (32.5 vs 32.7 ms). Left off. Shrinking the area to 1/64 ("div 8", diagnostic)
  cut GPU 50 -> 31 ms because it clips fragment work, so load/store is NOT the main cost.
- anisotropic_override=0, gamma_render_target_as_unorm16=false, both together: still noise-level.

CPU (command thread profile, heavy scene): hoisted upload `std::rotate` over the whole pass was
~16% of that thread -> now a memmove splice; mprotect write-watch re-arming ~13% (unchanged);
vkCmdBeginRendering 11%, vkResetCommandPool 8%, descriptor updates 8-11%.
Affinity now re-ranks: busiest pinned thread gets the fastest core (command thread 74% -> 60%).
Neither moved fps in the GPU-bound scene.

Open leads, in order: (1) hoist texture loads before the current pass / ring-allocate the scratch
buffer; (2) try the 120 Hz display mode (current mode is 60 Hz, 16.7 ms steps; 120 Hz halves the
penalty of a missed vblank); (3) fewer resolves/passes; (4) mprotect batching.

- 120 Hz display mode: GameActivity can request it (flag file files/highrefresh.txt; logs
  "highrefresh: preferred display mode 2"), but Android keeps the panel at 60 Hz (mActiveModeId=1)
  and frame stats did not change. Dead end without a surface frame-rate hint; left inert.
- Final installed build: thps8-m5i.apk (C:\thp8-work\android), no args.txt on the device.

## 2026-10-09 (session 2, part 3): texture-load hoisting (patch 0007), and a correction

CORRECTION: pressing START in the garage opens the PAUSE menu. The "916 draws / 29.5 fps gameplay"
numbers earlier in this file were the paused world (overhead camera), not skating. Real gameplay at
the spawn point (before START) is the 1758-draw garage view; run_idle.ps1 measures that, run_exp.ps1
(which presses START) measures the paused view, so only use it as a second data point.

gpu_hoist_texture_loads (default ON in LaunchConfig now): RequestTextures records loads, scratch
use and transition barriers as a block and splices it in front of the current pass instead of
ending the pass for each load. Measured, same build, hoist off -> on:
- garage gameplay (1758 draws): 19.4 -> 22.3 fps, GPU 50.5 -> 37 ms
- paused world (917 draws):    29.3 -> 35.8 fps, GPU 32.5 -> 25 ms
~350k blocks hoisted vs 31 fallbacks in a ~5 min soak with stick/jump input, no errors, picture
identical. Hazards that force the fallback (pass ended in front of the block): a texture in the
block was already sampled in this pass, or an upload range was read in this pass.
What is left in the garage scene (reason stats): ~60% resolve round trips (RT -> EDRAM dump ->
shared memory -> texture load), ~25% render-target changes.

Skate 3 and GTA IV ports on the same device use adrenotools (custom Vulkan driver via
libhook_impl/libmain_hook, env SKATE3_VULKAN_DRIVER_NAME, libgsl_alloc_hook). The stock driver is
the main remaining suspect for the gap; needs a Turnip build + libadrenotools (NOT done: a driver
binary download needs the owner's OK). vulkan.pastel.so on the device is SwiftShader, not useful.

- SPIR-V optimizer (spirv-tools performance passes on translated guest shaders, tried as
  --vulkan_spirv_optimize): shaders 71-73% of original size, ~65-75 ms per shader to optimize, GPU
  time unchanged (36.1 vs 36.4 ms in the garage scene). Reverted; not worth the build weight.
- Pass timing with the hoist on (garage scene, 37 ms GPU): ~19 ms is the rendering work of passes
  that end at resolves, ~11 ms other pass work, ~5 ms the resolve dumps/copies (42 resolves/frame).
  Remaining cost is shading/fill + resolve round trips, not pass overhead.
- Skate 3 port on this device (chat.buku.skate3) is the same ReXGlue runtime but with a custom
  game-specific "native render" path (cvars native_render_*, per-scene 2D/mesh decode workers) and
  an adrenotools custom Vulkan driver loader (libhook_impl/libmain_hook). Not just a driver swap.

## 2026-10-09 (session 2, part 4): custom Vulkan driver (Mesa Turnip) via libadrenotools - tested, NOT a win

Built in, off by default: `--android_vulkan_driver=<folder>` (+ `--android_vulkan_driver_lib`,
`--android_vulkan_driver_env`, `--android_gpu_turbo`). Drivers are pushed to the external
files/drivers/<folder>/ and copied to internal storage by GameActivity at launch; native side
(custom_driver.cpp) opens a patched libvulkan with libadrenotools and passes the handle to the SDK
(patch 0008, env REX_VULKAN_LOADER_HANDLE). libadrenotools (BSD-2, bylaws, pinned 8fae8ce) is fetched
by tools/android/fetch_adrenotools.sh into ../third_party, not committed; Turnip builds from
K11MCH1/AdrenoToolsDrivers v26.0.0-rc08 (SHA-256s in ~/thp8/drivers/SHA256SUMS), not committed.

It works (device 'Turnip Adreno (TM) 740', Mesa 26.0.0-devel, game renders) but is slower here, garage
gameplay scene, same build, hoisting on:
- stock Qualcomm driver:   ~23.8 fps, GPU ~36 ms
- Turnip R8 (default):      19.8 fps, GPU 47 ms
- Turnip R8 Sysmem build:   19.6 fps, GPU 47 ms
- Turnip R8 Gmem build:     10 fps,   GPU 92 ms
So the stock driver stays the default. The SDK already honours bin_select/bin_mask predication, so the
1758 draws are real passes, not duplicated tiles.

## 2026-10-09 (session 2, part 5): Turnip becomes the default; stutter fixed; correction of the Turnip verdict

The idle garage scene misled the earlier Turnip verdict (it measured 19.8 vs 23.8 fps). Skating
with scripted input (run_skate.ps1: stick forward + push/ollie taps + sway, 45 s) shows the drivers
are equal in frame rate (stock 23.3 fps / Turnip 23.6-25.6 fps, both quantised to 33/50/67 ms), and
the owner reports the stock driver shows the blue-haze bug while Turnip never does. So:
- Turnip (Mesa 26.0.0-devel R8, bundled in the APK as assets/drivers/turnip, fetched and SHA-256
  checked by tools/android/fetch_turnip.sh) is now the DEFAULT (--android_vulkan_driver=turnip in
  LaunchConfig). Falls back to the stock driver when the bundle is missing or after 2 consecutive
  launches that never got the graphics up (files/drivers/.failed_launches).
  Force stock with `--android_vulkan_driver=` (empty) in args.txt.
- Patch 0009: persistent VkPipelineCache (files/cache/vk_pipeline_cache_*.bin) AND a real bug fix:
  the SDK's shader/pipeline storage was never read back on Android (fopen "a+b" starts reading at
  the END in bionic), so every launch recompiled ~700 pipelines on demand and dropped a frame for
  each (~100 dropped presentations per 45 s). With the seek fix the 2nd launch pre-creates the
  stored pipelines at startup: dropped presentations 100 -> 3 -> 1 over launches 1-3; p99 frame
  time 383 ms (stock, first run) / 166 ms -> 67 ms. First launch after install still compiles.
- Skating A/B on Turnip with warm caches: ~25.5 fps, frames 33/50 ms (GPU ~36 ms mean), i.e. still
  not a locked 30; needs ~10% less GPU time.

## 2026-10-09 (session 2, part 6): what limits the frame now, and what is NOT the cause

Default build = Turnip + hoisting + persistent caches. 3 min scripted skating soak: no crash/errors,
0 dropped presentations, worst frame 95 ms; 22-28 fps depending on scene density (33/50 ms frames).

Findings (all measured with run_skate.ps1 / run_busy.ps1, Turnip):
- Per frame ~1100 draws (main scene 1040x584 2xMSAA ~970, shadow maps 134 at 1024^2/512^2, ~175 draws
  with <=6 vertices, 48 draws that write nothing). Work is almost linear in draws: ~25-27 us per
  draw all-in; ~1200 draws/frame is the cliff between 33 ms and 50 ms frames.
- NOT the cause: fragment/fill (render area shrunk to 1/16 saves 0.8 ms), MSAA (force-off ~5%), GPU
  clock (turbo no change), barrier count, upload barrier chains (merging blocks: no change),
  anisotropic/gamma knobs, SPIR-V optimisation, shader ALU (no expensive shader pair; per-draw stamps
  show ~10-17 us each, spread evenly).
- Resolves: ~30/frame, dump 0.10 + copy 0.06 ms each = ~4.8 ms/frame. The ~14 ms "fixed" span seen
  with draws suppressed is the guest CPU feeding commands (progressive submission), not GPU work.
- Affinity matters: pinning off -> 20% of frames slip a vblank, on -> ~5%.
- gpu_frame_us is the frame's lifetime (open -> close stamp), NOT pure GPU work; it follows the
  vblank-quantised period. Use busy% x period or A/B on fps for work comparisons.
- Remaining levers: fewer/cheaper draws per frame (structural: game-specific native renderer like the
  Skate 3 port, or draw batching), direct render-target->texture path (saves <= ~3-4 ms), shadow
  update every other frame (~3-4 ms, changes visuals).

Tools: tools/android/windows/{pad,to_gameplay,run_skate,skate,run_busy,run_idle,run_exp}.ps1 (edit the
scratchpad paths at the top). Live diagnostics: files/live_flags.txt (see patch 0010).

## 2026-10-10: fragment cost under Turnip

- New measurement (patch 0011 flags, paused world, Turnip): drawing every draw 3x with identical state
  costs +~30 ms per copy; the same extra copies with a 1x1 scissor cost +~8 ms. So ~8 ms of the ~31 ms
  frame is vertex/setup and ~20+ ms is fragment work: the scene IS largely fragment-bound under
  Turnip. The earlier "render area shrink saves only 0.8 ms" result was invalid: sysmem rendering
  ignores the render area. Leads: anisotropic filtering (default 4x), LRZ engagement, MSAA, shader ALU
  (fp16 / RelaxedPrecision), overdraw. Plan in the handoff, section 9.
- accept30.ps1 (locked-30 acceptance) written, untested.

## 2026-10-10 (session 3, new account): the 50 ms frames were a CPU<->GPU serialisation bug, not GPU load

Mission (HANDOFF2): locked 30 fps in real gameplay, then 60+. Findings and fixes, all measured on the Thor
with Turnip, warm caches, live A/B inside one game session (tools/android/windows/live_ab.ps1):

- New true GPU-work metric: `gpu_busy_us` / `gpu_submissions` / `fence_wait_us` columns in frames.csv
  (per-submission top->bottom timestamps, needs --gpu_timestamp_queries=true). Garage spawn (1758 draws,
  the heaviest view) = only **28.5 ms GPU per 50 ms frame**; with all draws dropped it runs 60 fps. The GPU
  was never the limit. (kgsl sysfs gpubusy/gpu_busy_percentage/clock stats are frozen with this setup:
  the earlier "81 % busy" readings were stale values.)
- 1/4-area scissor (live flag 128) changes nothing in the garage (GPU 28.5 -> 22.1 ms, still 50 ms frames):
  fragment work is ~8.5 ms of the garage frame. Phase A's GPU-side plan was aimed at the wrong thing.
- Per-thread view (simpleperf --trace-offcpu timelines, /proc schedstat): no thread saturated; the command
  processor (CP) slept ~37 % of its time in Turnip's `wait_timestamp_safe`.
- **Root cause:** `CheckSubmissionFenceAndDeviceLoss` polls submission fences with
  `vkWaitForFences(timeout = 0)`. On Turnip's kgsl backend a zero timeout reaches
  IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID as 0 = wait forever, so every "poll" blocked the CP until the
  GPU finished the previous submission (measured: 300 polls = 4.5 s per 150 frames, up to 26 ms each).
  CP and GPU ran in series -> frames missed the 33.3 ms vblank slot.
  **Fix (patch 0012, cvar `vulkan_completion_queries`, default on, live flag 2048 = off):** a
  bottom-of-pipe timestamp query per submission; the poll reads it with a non-WAIT
  vkGetQueryPoolResults (a memory read in Turnip) and only touches the fence once the work is done.
  - Garage spawn: **20.0 -> 29.7 fps** (p50 33.3 ms, 3 % slow frames).
  - Skating (3 x 12 s alternating windows): **26.2 -> 36.6 fps**, frames > 34 ms 28.7 % -> 5.4 %.
- Upload-ahead (cvar `gpu_upload_ahead`, default on, 16 extra pages max each side; live flags 256 off /
  512 = 4 pages / 1024 = 64 pages): a guest write invalidates a whole 256 KB block (~70 per frame), which
  draws used to re-upload ~2.85 pages at a time, each with its own mprotect + copy + command-buffer
  splice (~870 uploads/frame). Uploading the contiguous invalid run around a request cuts that ~9x;
  CP CPU ~20 -> ~14 ms/frame in the garage (16 pages beat 4 and 64).
- Small CP costs removed: live_flags.txt fopen limited to once a second (was every 600 draws, ~1 ms/frame);
  IsReadbackMemexportEnabled no longer does a by-name cvar lookup per draw.
- Diagnostics added (off by default): `--gpu_pacing_trace=true` logs every 150 swaps a timeline of
  vblanks (V), CP batches with thread CPU time (K/k), WAIT_REG_MEM waits (W/w), interrupts (I), swaps (S),
  plus call-site timings (D draw, P fence poll, Q submit, E/e EndSubmission, X swap) and WAIT_REG_MEM
  statistics; `--gpu_diag_trivial_ps=true` replaces every translated pixel shader with a constant colour.
  Observed: the CP waits each frame (WAIT_REG_MEM mem 0x1FCA3004 == 0) until the vblank interrupt clears
  the flag - the game's own vsync; the guest's frame deadlines live in sub_82327EE0 (see
  src/game/src/guest_swap_wait.h). sub_8235DF50 is the main thread's work-queue wait, not a limiter.
- Note: the game is not capped at 30; cheap frames run at 60, so skating now mixes 16.7/33.3 ms frames.
  Next: a 30 fps presentation cap for even pacing, then the remaining 5 % slow frames.

Builds: thps8-m10a..m10k (m10k = this state, now thps8-best.apk).

## 2026-10-10 (session 3, part 2): from "30 on average" to an even 30

Builds thps8-m11a..m11n. Measured with live_ab.ps1 (skating windows), accept30.ps1 (3 x 120 s skating) and a
new annotated per-thread timeline (simpleperf -g --trace-offcpu, labels per ms: CP drawing / uploading /
texture work / vblank wait / cap wait / idle; guest real work / fence spin / job spin).

- **Completion queries, done right (patch 0013).** The 0012 version still blocked: Turnip marks a query
  available when the CP *parses* it (CP_MEM_WRITE), and a reused slot still holds its previous value until
  the GPU reaches the in-command-buffer reset. Now the stamp is the last command of the real command buffer
  and a submission counts as complete only when the slot's value differs from the value it had when the
  slot was armed (and is non-zero). Fence polls that still blocked: ~3-7 ms/frame -> 0.03 ms/frame.
  Skating uncapped: 42 fps, 0.4 % of frames > 34 ms.
- **30 fps swap cap** (`--gpu_swap_fps_cap=30`, now a LaunchConfig default): before a swap the CP waits until
  2 guest vblanks have passed since the previous swap, so the game (which runs uncapped and mixed 16.7 /
  33.3 ms frames) presents at an even cadence.
- **Volatile pages** (`--gpu_volatile_pages`, default on, live flag 4096 = off): pages re-uploaded in 4+
  consecutive frames stay writable and are validated by comparing with a shadow of the last upload (once
  per ring batch), instead of mprotect + guest fault + re-upload every frame. Pages with texture watches or
  GPU-written data never become volatile (a new watch demotes and re-protects them). Garage CP CPU 19.5 ->
  15.0 ms/frame, submissions/frame 3.1 -> 1.7.
- **Hoisted uploads batched per pass**: the copies are collected and inserted in front of the pass once
  (at pass end, or before a texture-load block), instead of splicing the command stream per upload (that
  memmove was 10 % of the CP, quadratic in pass length).
- **SDK built for Armv8.2 on Android**: rexglue_helpers.cmake forced -march=armv8-a per target, overriding
  the app's armv8.2-a, so the runtime and GPU plugin called out-of-line __aarch64_* atomics.
- **Slow frames were wake-up latency, not work.** With the per-frame columns added to frames.csv
  (cp_cpu_us, cp_ring_idle_us, cp_regmem_wait_us, cp_cap_wait_us) the remaining 2-3 % slow frames had
  normal CP work and no ring idle; the pacing trace (dumped automatically on a > 35 ms frame) showed the CP
  resuming 2-5 ms after the vblank that should release it (cap wait or the game's own WAIT_REG_MEM vsync
  wait), and once the vblank itself 2.9 ms late. Fixes: thread priorities from the affinity manager
  (`--android_thread_boost`, default on: GPU VSync nice -12, GPU Commands -10, pinned hot game threads -6);
  the vblank thread sleeps to 1 ms before the edge and spins; the cap wait sleeps to 1.5 ms before the
  target vblank and spins on an atomic; WAIT_REG_MEM polls yield instead of sleeping when a vblank is
  < 1.5 ms away. Skating: frames > 34 ms 3.3 % -> 0.3 %, p90 33.3 ms.
- Findings for later: the game's render thread waits on a GPU fence (sub_823B58A0 / sub_823BF8B0) written
  after the previous frame's swap, and the main thread waits on job completion (sub_821E6928 spin), so the
  guest builds a frame only after the previous swap: guest + CP must fit one 33.3 ms slot. The game issues a
  48-draw frame every ~6 s (looks like a skipped render when behind). Light scenes leave ~20 ms of slack per
  frame (CP busy ~8 ms, render thread ~3 ms) - relevant for 60 fps.

## 2026-10-10 (session 3, part 3): LOCKED 30 - PASS

accept30.ps1 on thps8-m12k (3 x 120 s scripted skating from the garage, warm caches):
run 1 30.0 fps, 0.2 % of frames > 35 ms, p99 33.5 ms - PASS; run 2 30.0 fps, 0.1 %, p99 33.5 - PASS;
run 3 29.9 fps, 0.4 %, p99 33.5 - PASS. Garage spawn 29.98 fps, 0.68 % > 35 ms; paused world 30.00 fps,
0 %. (Before this session: skating 22-28 fps, 40-45 % of frames > 34 ms; garage 20 fps.)

What closed the last gap (all found with new frames.csv columns - swap_time_us, cp_runq_us,
vsync_runq_us - and simpleperf timelines aligned to them):
- **core_ctl.** Qualcomm core_ctl pauses performance cores (prime: min_cpus 0; gold: one of four). The
  affinity manager pinned each hot thread to ONE core, so the command processor sat runnable on a paused
  core for up to 40 ms (cp_runq_us 14-20 ms in slow frames vs 0.5 ms otherwise). `android_affinity_mode=1`
  (default): hot threads get the whole performance set; SDL/audio threads are managed too.
- **Guest busy-waits** (guest_wait_sleep.h, `android_guest_wait_sleep`): the render thread's GPU-fence
  wait (sub_823B58A0 -> sub_823BF8B0, 10-20 ms/frame of spinning) and the main thread's job wait
  (sub_821E6928, spins while the job's busy word is non-zero) now sleep 50 / 20 us between polls, freeing
  the performance cores.
- **Vblank delivery no longer depends on one thread waking on time** (GraphicsSystem::DeliverDueVblanks):
  vblanks sit on a fixed 60 Hz grid; the vsync thread delivers them, and the command processor delivers a
  due one itself while it waits for it (cap wait, WAIT_REG_MEM polls), under one mutex so the guest ISR
  never runs twice or concurrently. The vsync thread had been kept off the CPU for up to 13 ms.
- Also tried and NOT kept: shadow-map update skipping (`gpu_shadow_skip_level`, works, but saves only
  ~1.3 ms of GPU in the garage - left at 0, quality unchanged); far-clip scaling through the cameras
  (SetClippingDistances / camera +204/+208: changes fog/projection, not culling - draw count unchanged,
  hook removed); anisotropic off, LRZ off, gamma RT off (within route noise).
- Engine knowledge: script command table at 0x826D9C68 (`--dev_console=true --exec="script_find lod"`);
  SetClippingDistances = sub_82295B80, SetViewerLODDist = sub_821C9EE8 (per object), SetShadowMapParams =
  sub_82297788; viewport count sub_82322710, viewport camera sub_823229B0(i).
- System perfetto (ftrace) is unusable on this firmware: traced_probes crashes when an ftrace data source
  starts. trace_processor v58.2 is in ~/thp8/tools/perfetto-v58.2 (linux-amd64.zip SHA-256
  32c739f71b2d39721afd294c0b038f2499a44a71b5b6bcdd85b83faca0b240b9) for traces from other sources.

Remaining slow frames (0.1-0.4 %): mostly GPU-bound views with 2000+ draws (GPU 40-50 ms) and a few
command processor overruns. Next: phase D (60+).

## 2026-10-10 (session 3, part 4): phase D - 60 fps on screen with frame generation (locked 30 kept)

thps8-m13f (now thps8-best.apk) shows the game at **59.2 fps while skating** (SurfaceFlinger present
timestamps, display_cadence.ps1: 99.0 % of on-screen intervals 16.7 ms, 1.0 % 33.3 ms) and **57.2 fps** in
the first seconds after the spawn, while the guest stays locked: accept30 with frame generation on by
default - run 1 30.0 fps, 0.3 % > 35 ms, p99 33.5 ms; run 2 30.0 / 0.3 % / 33.5; run 3 30.0 / 0.1 % / 33.5 -
LOCKED 30: PASS.

Why frame generation rather than a real 60: dense views already take close to the whole 33 ms (the
remaining slow frames are GPU-bound views with 2000+ draws), so a native 60 would need GPU and guest CPU
work both cut roughly in half. Generated frames double the shown rate with no change to the game.

How it works (SDK 0015, src/graphics/vulkan/frame_generation.*; GLSL in
tools/android/frame_generation_shaders, rebuilt to frame_generation_shaders.inc with build.sh):
- At each guest swap, compute passes at guest-output resolution: quarter-resolution luma, 8x8 block
  matching (full search around a temporal predictor = last frame's vector), 3x3 vector median, then the
  frame at t = k/factor sampled backward from the previous and forward from the current frame, with a
  cross-fade fallback where the two disagree (occlusion, bad vectors).
- The generated frames and the real one are rendered through the normal swap refresher into a slot of
  guest-output-format images; a **pacer thread** copies them into the presenter one per vblank (FIFO
  presentation), so only the pacer ever waits for the display and the command processor never blocks
  on presentation. If the pacer falls behind, generated frames are dropped, never real ones.
- `gpu_frame_generation` (LaunchConfig default true, with mailbox/immediate/fifo-relaxed disallowed),
  `gpu_frame_generation_factor` (0 = auto: one shown frame per refresh at 30 fps, from
  files/display_hz.txt that GameActivity writes on resume and on every display change),
  `gpu_frame_generation_max_factor` (default 2; 4 = 30 -> 120), `android_display_refresh_hz` (window
  frame-rate vote through ANativeWindow_setFrameRateWithChangeStrategy, default 0 = no vote).
- Quality: clean on the skater, the camera pans and most geometry; thin repetitive structures (fences,
  railings) can shimmer in the generated frames - the temporal predictor reduced it.

**120 Hz**: the Thor's main panel supports 120 Hz, but the system settings peak/min refresh rate are 60,
so SurfaceFlinger keeps the game at 60 whatever the app asks for. That is a system setting for the owner
to change (Settings -> Display -> refresh rate 120 Hz); then run with `--gpu_frame_generation_max_factor=4
--android_display_refresh_hz=120` to get factor 4 (30 -> 120). Not measured on device (the panel never ran
at 120 here).

Turn frame generation off: `--gpu_frame_generation=false --vulkan_allow_present_mode_mailbox=true`.

## 2026-10-10 (session 3, part 5): internal resolution option, frame-rate modes, game speed

- **The game renders at 1040x584**, not 1280x720: sub_8235F2A8 (called once from the D3D device setup
  sub_82360410) calls XGetVideoMode and copies width/height out of a mode table (0x820178F8, 740-byte
  entries) into the presentation parameters. The reported video mode (`--video_mode_width/height`) does
  not change the choice (960x540 run: GPU unchanged).
- `android_render_scale` (guest_resolution.h, 25-100 %, default 100) scales the picked back buffer
  (nearest multiple of 8); everything renders smaller and the presenter stretches it to the screen. The
  HUD scales with it. Garage spawn, same view (~1720 draws), GPU busy mean / p90:
  100 % 28.2 / 36.8 ms, 75 % (784x440) 21.8 / 30.0, 50 % (520x296) 16.8 / 24.1 - about 60 % of the GPU
  time does not depend on resolution (per-draw/vertex cost). CP CPU unchanged (~19 ms in the garage).
- **Game speed is real time, not per frame**: same spawn, same input, screenshots 1.5 s / 2 s later with
  the cap at 30 vs 60 show the skater at the same place. So real frame rates above 30 are usable.
- Uncapped (`--gpu_swap_fps_cap=60`, no frame generation), scripted skating, 60 s:
  100 % 45.7 fps, 75 % 52.7 fps, 50 % 57.0 fps - but on the 60 Hz panel every frame is shown for 16.7 or
  33.3 ms, so anything between 30 and 60 is uneven. A locked 45 would need a 90 Hz-compatible display; a
  locked 40 needs 120 Hz (system setting, owner only).
- Launcher options (SharedPreferences "options", args.txt still overrides): Resolution 100/85/75/60/50 %
  (shows the size) and Frame rate: "30 + frame generation (60 shown)" (default), "Locked 30", "Up to 60
  (uneven)". Verified on device (log shows the chosen size; Up to 60 gives 16.7 ms median frames).
- AMD FSR 1 upscaling exists in the presenter (`--present_effect=fsr`) but is compiled out: it needs the
  FidelityFX SDK (REXGLUE_ENABLE_FIDELITYFX, a FetchContent download) - not done yet.

## 2026-10-10 (session 3, part 6): FSR 1 upscaling on by default; fast-render-path scope

- AMD FSR 1 (EASU upscale + RCAS sharpen) is now built on Android without fetching the FidelityFX SDK:
  its shaders are prebuilt in the SDK tree and the presenter computes the constants itself, so the
  REX_HAS_FIDELITYFX_SDK gate is set for Android in src/ui/CMakeLists.txt (SDK patch 0016). FSR 2/3
  (temporal) still need the SDK runtime plus per-pixel motion vectors and depth - not done.
- Launcher "Upscaler: FSR 1 / bilinear" (default FSR 1) -> `--present_effect=fsr|bilinear`. At 50 %
  (520x296) FSR removes the blocky staircase edges of bilinear. FSR + frame generation while skating:
  59.8 fps on screen, 100 % of intervals 16.7 ms. accept30 with FSR (100 %): 0.5 / 0.2 / 0.3 % of frames
  > 35 ms, p99 33.5 ms - PASS.
- to_gameplay.ps1: the main menu with CAREER highlighted has the loading screen's colours; with FSR the
  boot took longer and the driver waited there forever (then matched the attract video as "gameplay").
  It now presses A if a "loading" screen does not change for ~20 s. All earlier benchmark runs were
  checked: they reached the real garage spawn.
- Scope for a Project 8-specific fast render path: docs/android_fast_render_path_scope.md.

## 2026-10-10 (session 3, part 7): target "real 60 at native resolution" - where the garage GPU time goes

Owner goal: up to 60 fps at native 1040x584, no upscaling, no frame generation (like the Skate 3 port).
Garage spawn (heaviest view), native, cap 60, frame generation off (thps8-m15a, live_ab.ps1 -Idle):
- Baseline 32 fps, GPU busy 27.4 ms. **All draws suppressed (flag 1): exactly 60 fps, GPU 5.4 ms** - the
  game's CPU work and the command processor fit in 16.7 ms; the wall is GPU draw work.
- Each draw twice more with a 1x1 scissor (flag 64): +14.0 ms -> ~7 ms vertex/setup per pass of draws
  (300 k vertices - ~4 us per draw, far above what the hardware needs: vertices are fetched from the
  shared-memory storage buffer and byte-swapped in the shader).
- 1/4-area scissor (flag 128): -6.2 ms -> ~8.3 ms fragment shading.
- Barriers skipped (flag 2): -0.7 ms (not a lever).
- So 27.4 ms ~ 5.4 non-draw (resolves/clears/copies) + 7 vertex/setup + 8.3 fragment + ~6.7 state
  changes / pass boundaries (not repeated by the duplicate-draw flags).
- `gpu_native_residency` (upstream PC option): much worse on Android (GPU 27 -> 44 ms, 22 fps). Off.
Need <= ~15 ms. Tier-1 targets in docs/android_fast_render_path_scope.md map onto these buckets: native
vertex fetch (vertex 7 ms), direct resolves (non-draw 5.4 ms), fewer passes / state changes (6.7 ms),
cheaper translated pixel shaders (fp16 / relaxed precision, 8.3 ms).

## 2026-10-10 (session 3, part 8): native vertex fetch (works, small gain) and what the Skate 3 port really does

- **Native vertex fetch** (SDK 0017, `gpu_native_vertex_fetch`, default OFF, live flag 32768 inverts): vertex
  shader fetches whose index is the untouched vertex index read their words from Vulkan vertex attributes
  (the shared memory buffer bound at each fetch constant's address; raw R32..R32G32B32A32_UINT words, the
  shader's endian swap and unpack unchanged) instead of storage-buffer loads with a 4-way binding switch
  per word (maxStorageBufferRange is 128 MB, so 512 MB = 4 bindings). Shader analysis
  (Shader::native_vertex_fetch_layout) decides per fetch; a translation modification bit selects it per
  draw; the primitive processor makes host-endian index buffers (reset index -> 0xFFFF, index range kept
  for the VGT_MIN/MAX clamp check); the guest base index goes into the draw's vertex offset. Garage: all
  vertex shaders eligible, 100 % of draws native, picture identical - but GPU only 27.2 -> 26.4 ms while the
  index conversion adds ~1.5-2 ms of command processor time, so frames are not faster. Kept off. The vertex
  fetch itself was not the per-draw cost.
- Driver-side checks (env via --android_vulkan_driver_env, applied - logcat "driver env"):
  IR3_SHADER_DEBUG=nouboopt 27.1 -> 28.1 ms (worse), nopreamble no change.
- Command mix per draw (deferred command buffer stats): 2.0 descriptor-set binds, 0.56 pipeline binds,
  0.72 barriers (mostly the hoisted-upload pairs before passes; skipping all barriers saves only 0.7 ms),
  0.30 buffer copies. Draws measured one by one (10-17 us) cost about what they cost in the real frame
  (15.8 us average): the GPU barely overlaps draws.
- **Skate 3 port** (chat.buku.skate3, strings of its librexruntime/libskate3, design reference only): it is
  NOT faster emulation. `skate3_native_render_scene` renders the game scene from the hooked engine
  MeshContext stream with its own Vulkan renderer (own shadows/PCSS, bloom, HDR, SSAO, haze, light
  shafts, ported postfx ucode as HLSL, native FMV), and `native_render_suppress_emulated_draws` /
  `native_render_suppress_mode` switch the emulated GPU passes off during gameplay (only small
  composition passes like lightmap pages still run emulated). That is why it reaches 60.
- Conclusion for "60 at native resolution like Skate 3": the emulator path has no single big lever left
  (costs are spread over per-draw state, vertex setup, fragment work and resolves); matching Skate 3 means
  the same kind of game-specific native renderer for Project 8 (hook the Neversoft engine's render
  submission, reimplement its materials/shaders/post effects), a large multi-week project.

## 2026-10-10 (session 3, part 9): native renderer feasibility - one engine function draws all 3D

Full write-up: docs/android_native_renderer_feasibility.md (probes in tools/android/probes). Key facts:
`sub_82359E88` (per-mesh render callback, ~1,500 calls/frame in the garage) draws the whole 3D scene;
skipping it: draws 1,729 -> 238, GPU 27.4 -> 10.4 ms, 60 fps (CPU side fits). Its render item carries
buffers, declaration, material, counts (D3D9-style). Shader pairs: ~100-130 per frame, top 10 = ~2/3 of
draws, top 20 = ~80 %. Plan: hybrid capture/replay into the emulator's render targets (milestone A),
then cheaper native shaders for the top materials (B), then coverage across levels (C).

## 2026-10-10 (session 4, part 1): Milestone A checkpoint - where the per-draw cost really is; leaner translated pixel shaders (SDK 0018)

**Checkpoint summary (go/no-go for the capture/replay plan): RE-SCOPED, not a dead end.** Before
building the marker plumbing (A2) the per-draw cost was taken apart with live switches (garage spawn,
native, cap 60, FG off, `gpu_busy_us`). The lean replay path of A2/A3 can only remove the GPU work
*around* the draws (~4.5 ms of 27.4); the biggest GPU item was the translated shaders, and generic
translator fixes already took the garage from **27.5 to 22.1 ms GPU (-20 %)** with the same picture
(every level and menu benefits). The real 60 now needs both GPU 22 -> ~15 ms and command processor
CPU ~21 -> ~14 ms. A capture/replay hook is still the strongest CPU lever (IssueDraw is 73 % of the CP
thread), but its savings come from skipping per-draw memory validation, which a generic cache could
also do - compare the two before writing A2. Decision: keep going on measured GPU levers (resolves,
remaining shader cost) and the CP profile; postpone A2.

Garage GPU, 27.4 ms (live switches, each A/B in one session):
| Part | ms | How measured |
|---|---|---|
| Non-draw (resolves 41/frame, clears, copies) | 5.5 | all draws dropped (flag 1) |
| Around the draws: binds 1.2, uploads 1.6, barriers 1.0, rest ~0.7 | 4.5 | draw commands dropped, everything else recorded (flag 1048576): 10.0 ms |
| Draw execution | 17.4 | |
| - translated pixel shaders | 10.4 | trivial PS variant (flag 65536): 16.8 ms |
| - vertex shading + raster | ~3 | null VS variant (131072): 13.8 ms |
| - hardware floor, ~2 us/draw | 3.4 | null VS + no binds, with/without draw commands: 12.9 / 9.5 ms |

What the pixel shader time was (each variant compiled by Turnip, `IR3_SHADER_DEBUG=fs,disasm` via
`--android_vulkan_driver_env`): translated fragment shaders are 1,000-3,000 Adreno instructions (a
native one for the same Xenos code: ~50-100); 37 k `mul` vs 600 fused `mad` (everything is
NoContraction), 39 k `sel` + 16 k `min` (Xenos 0 * x = 0 emulation), and **1,629 `samgq`** - the
quad-serialised sample-with-gradients, because every computed-LOD fetch samples with explicit
gradients scaled by 2^bias. Not the cause (measured, live A/B): anisotropic filtering off (27.2 vs
27.3), gamma RT as sRGB (27.3), forcing early depth on alpha-tested draws (-0.6; 64-74 % of draws are
already early-Z), IEEE multiply + fused mad in pixel shaders (-0.2 alone, -0.4 on top of the fixes
below), IEEE multiply in vertex shaders (0). Textures are native BC (no decompression).

**SDK 0018** (all switches are translation modification bits, so they A/B live without a restart):
- `gpu_implicit_texture_lod` (default ON on Android, live 16777216 inverts): computed-LOD fetches in
  pixel shaders sample with implicit LOD + bias instead of gradients * 2^bias (same filtering; no
  `samgq`). Garage 27.6 -> 25.6 ms.
- `gpu_static_texture_signs` (default ON, live 67108864): the texture sign / gamma class of up to 5
  fetch constants (all unsigned, all gamma, RGB gamma + A unsigned) and a zero result exponent bias
  are pipeline-time constants, so the per-pixel sign branches and switches fold away and the samples
  are no longer inside branches. 25.9 -> 22.0 ms. (Signed or mixed textures keep the dynamic path.)
- Off by default: `gpu_fast_pixel_shader_math` (live 8388608), `gpu_ieee_vertex_shader_mul`
  (134217728) - not worth the loss of exactness.
- Diagnostics (live_flags.txt): 65536 trivial PS, 131072 null VS (+ trivial PS) pipeline variants;
  262144 / 524288 skip descriptor / pipeline binds between null-VS pipelines; 1048576 record
  everything but the draw command; 2097152 drop shared-memory upload copies; 4194304 force the early
  depth hint; 33554432 no texture sampling (coordinates instead). Census logs: "pixel shader depth
  mode census".
- Pictures: garage screenshots with each switch on/off differ only on the idle-animating skater
  (static environment pixel-identical).

Command processor CPU (simpleperf, garage, ~21 ms/frame): IssueDraw 73 % - SharedMemory::RequestRanges
26 % (volatile-page memcmp 8 %, uploads 6 %), texture requests 17 %, UpdateBindings 9 % (Turnip
descriptor writes 3.6 %), primitive processing 5 % (16-bit reset-index scan 2.3 %), mutex wakeups 5.6 %;
register writes only 3 %. The null-shader runs (GPU 11-13 ms) topped out at 43-46 fps: the CP is the
other wall for a real 60.

Build thps8-m16a (= thps8-best.apk; repo default + SDK 0018): locked 30 accept30 3 x PASS (0.4 / 0.0 / 0.1 % > 35 ms, p99
33.5 / 33.4 / 33.5) after a cold first run (1.2 %: new shader variants compiling on the first skate);
**GPU busy per frame at the locked 30: 19.2 ms (m15a) -> 15.6 ms** (fanless headroom).
Tools: tools/android/windows/pass_ab.ps1 (per-flag "gpu pass timing" blocks). Note: pass timing
stamps include idle gaps between submissions - use gpu_busy_us for GPU work.

## 2026-10-10 (session 4, part 2): skating with the leaner shaders; what is left of the GPU and CP frame

- **Skating, uncapped (cap 60, FG off), scripted route, separate launches:** fixes off 44.8 / 45.5 fps,
  on **52.7 / 52.9 fps**; GPU busy 18.6 -> 12.4 ms per frame. Frames that still miss 16.7 ms (14 % of
  frames, ~1,600 draws, dense views): **GPU 20.0 ms and command processor CPU 19.6 ms** - both walls
  must come down ~25 % for a locked real 60 while skating.
- Pixel shader time left (garage, new defaults 21.9 ms): shader reading all its interpolators and
  nothing else (diag 268435456) 19.8 ms; trivial shader 15.4 ms -> the translated shader body now costs
  only ~2.1 ms, **the varyings (9 vec4 interpolators per vertex/pixel) ~4.4 ms** - content cost a
  native shader would pay too. Pixel shader translation is near its floor.
- Garage GPU budget now (21.9 ms): non-draw/resolves 5.5, around the draws (uploads 1.6, barriers 1.0,
  binds 1.2, rest) ~4.5, varyings ~4.4, per-draw floor ~3.4, vertex shading/raster ~3, shader body ~2.1.
- Command processor (simpleperf, garage): IssueDraw 73 %; SharedMemory::RequestRanges 26 % (memcmp of
  "volatile" pages ~30 % of it, uploads ~24 % - incl. a linear SharedMemoryReadInCurrentPass scan,
  memcpy, mprotect); VulkanTextureCache::RequestTextures 17 % (base lookups 7 %, hoisted texture
  upload flush 7 %); UpdateBindings 9 %; Turnip driver 11 % overall (descriptor writes 3.6 %, pipeline
  binds 3.4 %); WAIT_REG_MEM yield 6 %. The CP and the game's main thread both run on the prime core
  (core 7) most of the time.
- `direct_host_resolve` (default true) is only a stub: it preflights and then falls back to the EDRAM
  dump + copy ("until source-image direct shaders land"). Fused RT -> shared-memory resolve shaders
  would save most of the dump half (~2.9 ms of the ~5 ms resolve cost in the garage).
- Shader dump: `--dump_shaders=<files>/shaders` writes ucode + translated SPIR-V; spirv-cross
  (Ubuntu package) turns them into readable GLSL. `IR3_SHADER_DEBUG=fs,disasm` (with the pipeline
  cache moved aside) logs Turnip's per-shader stats to logcat.

## 2026-10-10 (session 4, part 3): the prime core for the command processor; fused resolves (SDK 0020, 0021)

- **Affinity mode 2** (`--android_affinity_mode=2`): only the GPU command processor may run on the
  fastest core; the game's main thread and everything else use the other cores. Before, the CP and
  the main thread were both on core 7 most of the time. Skating uncapped (cap 60, FG off):
  52.9 -> 56.5 fps, CP CPU 10.4 -> 7.6 ms per frame, slow frames halved, p90 33.3 -> 16.7 ms.
  **But at 30 fps it fails the locked-30 test** (2.0 / 1.4 % slow frames): with less load Qualcomm
  core_ctl parks the prime core (its min_cpus is 0) and the CP then waited 13-36 ms in the run
  queue of the crowded mid cores (frames.csv cp_runq_us). So mode 2 is used only by the launcher's
  "up to 60" frame-rate mode (LaunchConfig); the 30 fps modes keep mode 1.
- SDK 0020: the per-pass list of shared memory reads (scanned for every hoisted upload, capped at
  4096 entries) is now a page bitmap. Within noise in the measurements; kept (exact, no overflow).
- **Resolves** (census in the log, "resolve census"): per gameplay frame ~15 full-screen 1040x584
  2x MSAA colour resolves, ~10 quarter-screen, ~8 tiny, ~3 full-screen depth, 2 shadow maps. Each
  went render target -> EDRAM buffer (dump) -> shared memory (copy): skipping only the dump
  (diag 536870912) saved 3.4 ms of the garage's 22 ms. Note: `direct_host_resolve` (default on)
  was a stub that still dumped.
- **SDK 0021, fused resolve** (`--gpu_fused_resolve`, default ON on Android, live 1073741824
  inverts): an 8_8_8_8 render target that alone owns the resolve's EDRAM span (90 % of the
  resolves; the rest are depth) is resolved in one compute pass that reads the render target.
  Shaders in tools/android/resolve_shaders: a lean 8_8_8_8 -> 8_8_8_8 one (no averaging or
  averaging samples 0+1, exponent bias, red/blue swap, endian, 2D/3D tiling - the full copy
  shader's math bit for bit), plus generic fallbacks generated from the SDK's own fast / full
  32bpp copy shaders (decompiled with spirv-cross, EDRAM loads redirected to the render target).
  The first generic version only gained ~0.6-1 ms (a huge shader, per-sample address math); the
  lean one **22.0 -> 18.9 ms in the garage**, and the static parts of the picture are
  bit-identical (max difference 0). The shader header is generated by prepare_sdk.sh (not stored
  in the patch).
- Build thps8-m16d (= thps8-best.apk; defaults: 30 + FG, FSR, mode 1): locked 30 accept30
  3 x PASS (0.1 / 0.2 / 0.2 %). **GPU busy per frame at the locked 30: 19.2 ms (m15a) -> 15.6
  (m16a) -> 13.3 ms (m16d)**, -31 % for the fanless goal.
- Skating uncapped with mode 2 on m16d: 55.2 fps. The remaining slow frames (~9 %) are no longer
  GPU or CP work (GPU 13.7, CP 11.3 ms on average in them) but scheduling: CP run-queue wait ~15 ms
  and vblank thread run-queue wait ~12 ms per slow frame (core_ctl parking cores).
- Turnip `TU_DEBUG=nolrz`: no change (21.8 vs 21.9 ms).

## 2026-10-10 (session 4, part 4): depth resolves fused; CPU scheduling at 60 (SDK 0022)

- SDK 0022: full-screen **D24S8 depth resolves** (2x MSAA, ~3 per frame) also go through the fused
  path (fast copy + the dump's D24S8 packing, roundEven(depth * 0xFFFFFF) << 8 | stencil, and the
  inverse of its 40-sample column swap). Garage with colour + depth fused: **22.0 -> 18.6 ms**;
  95 % of resolves fused (the rest: the two D24FS8 shadow maps, ~0.2 ms, not done). Static picture
  max difference 1 in < 0.1 % of pixels.
- Affinity modes, skating uncapped (cap 60, FG off), run order alternated: mode 1 50.8 fps,
  mode 2 / mode 3 (mode 2 + the vblank thread may use the prime core) 56.4 / 56.4. Mode 3 = no gain.
- The slow frames left in mode 2 are scheduling: per slow frame the command processor waits
  ~13-15 ms in the run queue and the vblank thread ~10-12 ms, while its CPU work is ~11 ms and the
  GPU ~12-14 ms. In mode 1 there is no run-queue wait but the CP needs ~21 ms of CPU in those
  frames (sharing / slower cores). core_ctl: the prime cluster has min_cpus 0, the mid cluster
  min_cpus 3 of 4 (cannot be changed without root).
- **ADPF performance hints** (`--android_perf_hint=true`, SDK 0022 exports
  rex_gpu_set_frame_work_hook; the app reports CP CPU + run-queue time per frame for the CP, main
  and vblank threads, target = frame period): the session is created ("performance hints: session
  created", device HAL supports it), but **no effect** (56.1 / 56.2 fps with, 53.0 / 56.6 without;
  run-queue waits unchanged). Off by default.
- Noise note: back-to-back skating launches alternate fast / slow by ~3 fps regardless of the
  setting (seen in three series) - compare A/B pairs in alternating order, >= 2 each.

## 2026-10-11 (session 5): in-game settings menu, power measurements, cooler defaults (SDK 0024-0025)

Owner request: constant 60 and better thermals (native "up to 60" sat at ~80 C), plus an in-game menu
(swipe from the left / right edge) for resolution / frame rate / FSR / frame generation, then a
"60 + frame generation shown at 120 Hz" mode. Higher-than-native resolution was tried and dropped
at the owner's request (see below).

**New metric: system power.** `tools/android/windows/power.ps1` = USB input power (pinned at its
1.4 A limit while the game runs) minus battery charge power (battery current_now is + while
charging, - while discharging). A proxy (charger losses), but it moves 1:1 with SoC draw and needs
no root. `power_ab.ps1` does live A/B of any hot-reload cvars in ONE session through
files/live_cvars.txt (name=value lines, applied within ~1 s by the affinity thread), printing fps,
slow frames, GPU busy, process CPU cores and watts per window. Rough model from the A/Bs: ~1 W per
busy CPU core, ~4 W for a 100 % busy GPU, on top of ~4.5 W with the game closed (two screens).
Thermal zones: cpu-1-x / gpuss-x run 80-93 C in play; the Thor's own setting performance_mode=1
pins the GPU min clock to 550 MHz (min_pwrlevel 2) - owner's choice, not changed.

Changes (all measured on the Thor; each A/B alternated in one session unless noted):
- **Vblank / swap-cap spinning removed** (SDK 0024 cvars `vblank_spin_us`, `gpu_swap_cap_spin_us`,
  LaunchConfig 0): the vsync thread spun 3 ms before every vblank (18 % of a big core, 23 % of the
  profiled samples in clock_gettime). -0.14 cores, -0.2 W, same pacing; locked 30 still PASS.
- **No-MSAA display mode** (guest_resolution.h `android_video_mode_index`, launcher/menu option
  "Anti-aliasing", default OFF = index 7). The game's own mode table (0x820178F8, 740-byte entries:
  +4 width, +8 height, +12 MSAA 0/1/2 = 1x/2x/4x, +16 EDRAM tiles; index stored at 0x827EFFE8):
  [7] 1040x592 1x, [8] 1040x584 2x (what it picks), [11] 1280x720 1x, [12] 1280x720 2x (2 tiles),
  [13] 4x (4 tiles); the game picks 4/6/8/9/10/11 from a quality float. Forcing [7]: garage at the
  60 cap 44.7 -> 54.7 fps, GPU 17.8 -> 15.6 ms/frame, picture correct (the game sets up every pass
  for it, unlike the emulator-side gpu_force_msaa_off that broke post-processing). [11] 720p 1x
  works (39.6 fps garage); [12] (predicated tiling) renders with black patches - not usable.
- **Affinity mode 5** (default for all frame-rate modes): no game thread on the prime X3 core.
  30 + FG skating 6.68 -> 6.30 W, 60 cap 8.12 -> 7.68 W at the same frame rate (3 alternated pairs
  each); locked 30 accept30 3 x PASS (0.2 / 0.4 / 0.0 %). Mode 2's locked-30 failure does not
  apply (nothing depends on the prime core being online).
- **Vsync wait (WAIT_REG_MEM) sleeps** (SDK 0025 `gpu_regmem_yield_window_us` 300,
  `gpu_regmem_max_sleep_us` 1000; were 1500 / 125): sched_yield from this loop was ~10 % of the
  CP's CPU. Garage at 30 + FG: 1.57 -> 1.49 cores, -0.1 W, slow frames 0.3 -> 0.1 %.
- `event_signal_fast` (SDK 0024, default on): PosixEvent::Signal wakes only real waiters and
  outside the lock. Within noise in the measurements; kept (no extra futex per signal).
- Tried, no gain: guest critical-section spin cap (`rtl_critical_section_spin_cap`, 0 = off),
  ADPF hints for power (`android_perf_hint`, now switchable live; +0.1 W), the remaining CPU is
  real work (workers: vector math sub_82351338; audio mixer sub_82544C30).
- Frame rate modes: 0 = 30 + FG, 1 = 30, 2 = 60, **3 = 60 + FG** (the activity asks the display for
  its 120 Hz mode; FG factor auto = display Hz / cap; below 2 the frame is presented directly).
  Verified at 120 Hz: 98.8 % of on-screen intervals 8.3 ms; costs ~2 guest fps / +1.2 ms GPU vs
  plain 60. 120 Hz needs the system refresh setting (owner enabled it).

**In-game menu** (InGameMenu.java, GameActivity): swipe in from the left or right edge (36 dp edge
zone, 48 dp travel; system gesture exclusion for a 200 dp band per edge) or use the system back
gesture; touch or gamepad (d-pad / hat, A, B closes). Frame rate 30/60, frame generation on/off,
FSR on/off (`present_effect_live` 1/2, read every paint), resolution 100-50 % and anti-aliasing
(restart: "Restart now" relaunches through the launcher). Live settings go through
GameActivity.nativeSetCvar -> rex::cvar::SetFlagByName (live_settings.cpp). Frame generation is
switchable at runtime (`gpu_frame_generation_available`: the generator always exists; FIFO
presentation always). Opening the menu broadcasts XN_SYS_UI (THP8 does not pause on it). Fixed:
an edge swipe left the system bars visible and shrank the picture (re-hidden on close / after
focus and display-mode changes).

Higher than native (dropped by the owner for now): Xenia's draw_resolution_scale=2 renders black
on this setup (2 GB non-sparse scaled-resolve buffer on Turnip; "experimental" in the SDK). The
game's own 1280x720 1x entry works and would be the clean route if revisited.

Builds thps8-c1..c8 (c8 = repo HEAD).

### Session 5, part 2: the "random" drops to an even 30, CP per-draw overhead, prime core for 60

- **Soak, "up to 60" native, 10 min skating** (soak2.ps1): old build m16f 55.7 fps, 11.0 W, last
  minute CPU 94.7 C / GPU 91.4 C; c8 51.7 fps, 7.25 W, 81.0 / 75.7 C. (The two runs skated
  different routes - 670 vs 1020 draws per frame on average - so the fps are not comparable.)
- **Even-30 lock in the 60 fps mode = the game's presentation interval.** sub_82327EE0 (render
  thread frame start) re-applies the interval from a game variable at 0x826E4F68 whenever it
  differs from 0x826E4FB8, through sub_823B8110(device, interval) -> device+13220 (1, 2 or
  0x80000000 immediate). The game sets 2 by itself (log: "present interval: game set 0x2" after
  menus / heavy stretches) and the frame then takes two vblanks however light it is (frames.csv:
  cap wait 15.6 ms + regmem 10 + CP 7.5 = 33.3 ms with 611 draws). Hook guest_present_interval.h
  (`android_present_interval_max`, default 1 in every mode: the swap cap decides the rate).
- **CP overhead (SDK 0026):** the pass-sampled-image std::unordered_set (node allocation per
  texture per draw) -> open-addressing set with generation clear; RequestRanges' per-call vector
  -> thread_local scratch; hoisted blocks (texture loads, uploads) were memmoved in front of the
  pass one by one (cost ~ pass length per hoist) -> kept aside and inserted once at pass end
  (DeferredCommandBuffer::MoveTailTo lazy + CommitPendingSplice). Garage, two alternated launches
  each: **CP 20.1 -> 17.9 ms per frame**, GPU unchanged, picture unchanged.
- **CP on the prime core for 60:** garage (60 cap, live A/B x3): affinity mode 2 = CP 11.9 ms,
  51.6 fps, 8.27 W; mode 5 = CP 17.9 ms, a flat 30 fps, 6.32 W. Per frame mode 2 uses less
  energy (0.16 vs 0.21 J). So the 60 fps modes use mode 2 and the 30 fps modes mode 5 (mode 2
  still fails locked 30: core_ctl parks the prime core at 30). The menu switches it live.
- Garage at 60 is now GPU-bound (16.5 ms with 1x MSAA).

### Session 5, part 3: GPU / CPU levers tried at 60 (garage, 60 cap, CP on the prime core)

- **Alternate-frame shadow maps** (`gpu_shadow_skip_level=1`, LaunchConfig default in the 60 fps
  modes, menu switches it live): 51.6 -> 55.2 fps, GPU 16.2 -> 15.5 ms. Detection tightened in SDK
  0027 to depth-only passes with float-24 depth (1x MSAA, square 1024 / 512 scissor): with the
  game's 1x MSAA display mode the old test could also match square colour passes.
- **Resolution at 60** (garage, 2 alternated launches each): 100 % 55.5 fps, GPU 15.4 ms, 8.44 W;
  85 % + FSR 59.6 fps, GPU 13.9 ms, 8.74 W; 75 % + FSR 59.7 fps, GPU 13.1 ms, 8.2 W. 85 % holds
  the heaviest view at 60.
- **-O3 -mtune=cortex-a715** (build_native.sh default now): CP 11.6 -> 11.4 ms, -0.25 W, slow
  frames 1.0 -> 0.45 % (2 alternated launches each).
- No gain / not possible: RelaxedPrecision interpolators (`gpu_relaxed_interpolators`, Turnip
  ignores it: 16.2 vs 16.3 ms); sched uclamp.max (`android_uclamp_max*`: sched_setattr returns
  EPERM for apps); TU_DEBUG=gmem (17.9 fps - too many passes for tiled rendering), TU_DEBUG=sysmem
  (= default); dropping all hoisted-upload barriers (diag flag 2: -0.5 ms GPU at most, so merging
  them is not worth it). Steady garage command mix per draw: 1.97 descriptor-set binds, 0.92 index
  buffer binds, 0.53 pipeline binds, 0.71 barriers, 0.30 buffer copies.
- Script commands (dev console `script_find`): AllowBoneLOD is a retail stub (prints a warning),
  ToggleLOD / SetViewerLODDist are per object, Occlude_EnableViewportCull is compiled out. A global
  LOD / draw-distance lever would need real reverse engineering of the engine's visibility code.
- Trap: background test scripts started from the PowerShell tool keep running after their caller
  is killed with Stop-Process by command line (it does not match) - stop them with TaskStop, or two
  scripts drive the game at once.

### Session 5, part 4: CP placement, more CP cuts, the two "smooth 60" setups measured

- Hottest sensors at a real 60 (skating, 85 %): prime core cpu-1-8/9/10 76-80 C (it runs the CP),
  mid cores 67-73 C, GPU 66-72 C.
- **Affinity mode 6** (adaptive: CP on the mid cores, moved to the prime core when its CPU time per
  frame exceeds `android_cp_prime_up_pct` = 80 % of the frame period, back below 45 % for 5 s):
  works in the garage, but while skating the CP needs ~2x as long per frame on a mid core
  (~17 vs ~8 ms) and goes straight back. Not used; the 60 fps modes keep mode 2.
- Volatile page demotion after N unchanged checks (`gpu_volatile_demote_unchanged`, SDK 0028,
  default 0): no change in garage or skating - the pages really change.
- **SDK 0029: direct-mapped front cache for texture lookups** (1024 entries, cleared when a
  texture is destroyed): CP 11.35 -> 11.15 ms in the garage.
- accept30 on c17 (30 + FG defaults): PASS 0.1 / 0.0 / 0.1 %.
- **10 min skating soaks (soak2.ps1):** old m16f up to 60 native 55.7 fps, 11.0 W, CPU/GPU
  94.7/91.4 C; c15 real 60 at 85 % + FSR 58.5 fps (2.5 % slow), 7.0 W, 76.0/68.6 C; c18 30 + FG
  (60 shown) at 100 % 5.8 W, 71.4/63.9 C.
- thps8-best.apk = c18 (old best kept as thps8-best-m16f-backup.apk). Owner prefs set to 60 fps,
  FG off, FSR on, 85 %, AA off. HANDOFF5.md written.

## 2026-10-11 (session 6): menus at 60, the creator skater, held vblanks, frame generation and the HUD

Owner report after playing c18: at 60 fps the game sat at ~64 C most of the time but dropped to
~32 fps now and then; frame generation garbles fences, water and some UI; the character creator's
skater is yellow; menus and cutscenes are locked at 30.

**Two kinds of 30 fps drops** (soak_c15_60_85.csv, tools/android/windows/slow_frames.py):
- light frames (190-370 draws) at a flat 30 for ~12 s stretches, CP ring idle 16 ms per frame: the
  game itself waited (see the frame limiter below);
- dense views (1,500-2,000 draws): CP CPU 16-21 ms per frame, then the swap missed the 60 Hz vblank
  and waited 15 ms for the next one (cp_regmem_wait) -> runs of 40-70 frames at 33.3 ms.

**The game's own frame limiter** (guest_counter2_wait.h, cvar `guest_frame_interval_max`, LaunchConfig 1
in every mode): sub_82327EE0's second wait loop (0x823280D0) waits for the vblank counter at
0x82970A08 to reach a deadline, then sets deadline = counter + the interval at 0x82779C00 (copied
from the script variable 0x826E4F68). The session-5 hook only clamped the D3D presentation interval;
the game's loop still waited 2 vblanks per frame whenever the script asked for 2 (menu/loading
stretches, some heavy stretches). The hook now caps the interval used for the deadline. Measured
(same menu flow, two launches): the 12 s stretch before the character creator ran at 30.0 fps with
the cap off and 60.0 with it on; the stretch lasts the same 12 s (menu timing is not frame-based).

**Character creator skater blown-out white / yellow** = guest_vertex_unpack.h (the native replacement
of sub_82354BE0, the 252 -> 496-byte render record unpack, on by default since the desktop's
performance flags). The guest splats the LAST word of four source vectors (`vpermwi128 v, v, 255`)
before the u8x4 decodes at +96 / +144 and the splats at +432 / +448; the native code used word 0. In
the creator that put a specular power (49.0) where an intensity (0.9) belongs. Level materials
mostly have x == w, which is why the byte-exact verification had passed. Fixed (also in the x86
SIMD path); verify mode then compared 5.2 M records across menus, creator and gameplay: 0 mismatches.
(The bug is in the desktop code too.)

**Held vblanks (SDK 0030, `gpu_vblank_hold_us`, LaunchConfig 15000 in the 60 fps mode without FG; the
in-game menu sets it live).** Pacing trace (gpu_pacing_trace) of a CP-bound frame: the CP finished
the frame ~3 ms after the 60 Hz vblank, its vsync WAIT_REG_MEM only passes on a vblank that comes
after it started waiting, so the swap waited for the next one: 17 ms of work -> 33.3 ms frames. Now
the vsync thread does not deliver a due vblank while the CP is executing ring commands (not idle,
not sleeping in a wait); the CP delivers it itself when it reaches a wait (DeliverDueVblanks from
the wait loops), and the 60 Hz grid restarts from that moment (no two vblanks in quick succession;
at most 15 ms hold, then it goes out anyway). Only with no swap cap or a 60 cap (under the 30 cap
the frame normally spans the intermediate vblank). The guest_output is shown at the next 60 Hz
display refresh as before (the presenter repaints the newest frame every refresh), so no 120 Hz
panel is needed.
- CP-bound garage (CP on the mid cores, affinity mode 5, 60 cap): 30.5 -> 57.4 fps (2 alternated
  windows each, live).
- Skating, real 60 setup (85 % + FSR, mode 2): 56.6 -> 59.2 fps, frames > 18 ms 7.3 -> 5.8 %; the
  slow frames left are 18-28 ms (dense views, 1,700 draws, CP 15 ms + run-queue waits), not 33.

**Frame generation and the HUD (SDK 0031, `gpu_frame_generation_pixel_vectors`, default on).**
- fg_copy.comp keeps a per-pixel still count (real frame pairs in a row with the pixel unchanged,
  0-2); fg_interp.comp copies a pixel that is unchanged now and was unchanged in the previous pair:
  static HUD / menu pixels are never warped with the world behind them.
- Every other pixel picks the best of its block's vector, the four neighbouring blocks' vectors and
  zero motion (3-pixel warped-sample error, bias 0.05 towards its own vector).
- Offline evaluation tool tools/android/frame_generation_shaders/fg_eval.py (numpy re-implementation:
  record real 60 fps gameplay with screenrecord, ffmpeg to PNGs at 1040x585, generate frame i+1 from
  i and i+2 and score against the real one): mean error 13.53 -> 13.45, static pixels 1.95 -> 1.69
  (0-255). Falling back to the nearest real frame instead of the cross-fade where the warp fails
  scored worse (13.84 / 13.99), so the cross-fade stays.
- On the device (creator, generated frames only via gpu_frame_generation_debug_interp_only): the
  button bar read "DONF / CHOOSF / ROTATF" with the old version, clean with the new one. Skating
  30 + FG: 6.12 vs 6.13 W, GPU time within noise.
- Fences / water: block matching at 16x16 still mismatches thin periodic structures and non-rigid
  surfaces; those fall back to the cross-fade (soft double image). Real 60 (now steadier) avoids it.

Tools: to_gameplay.ps1 -StopAt <state> (e.g. customize), new screen signatures for the creator / new
career screens (the old ones keyed on the white skater); timeline.py (per-N-seconds fps, draws, CP,
waits from frames.csv); slow_frames.py (runs of slow frames and their columns); fg_eval.py.
Builds thps8-d1..d6.

### Session 6, part 2: validation, thread placement, the global lock

- **d6 (= thps8-best until d8):** accept30 3 x PASS (0.1 / 0.1 / 0.1 %, p99 33.5). 10 min skating soak at
  the owner's 60 setup (85 % + FSR, mode 2): **59.3 fps** (c15: 58.5), frames > 18 ms 4.3 %, > 35 ms
  0.2 %; slow-frame histogram: 66 frames at 32-36 ms in 41,000 (c15: 1,186 - the 30 fps runs are
  gone), the rest 18-28 ms. 7.30 W, last minute CPU 75.0 C / GPU 69.3 C (c15: 7.0 W, 76.0 / 68.6).
- Remaining slow frames (dense views, ~1,460 draws): CP 11.4 ms + run-queue waits (CP 7.4 ms, vsync
  thread 4.2 ms per slow frame).
- **Thread placement bug (thread_affinity.cpp):** the re-rank swap pinned the two hottest threads to
  single cores in modes 2 / 3, and the CP and the main thread swapped every round or two (logcat
  "swapped ..."): half the time the CP was pinned to a mid core and the main thread to the prime
  core. Now only mode 0 pins; modes 2-4 re-apply their normal masks. Skating A/Bs (live,
  `android_affinity_rerank_pin` = old behaviour): 59.7 / 59.4 / 59.5 fps across runs - no measurable
  difference either way; kept for correctness. `android_cp_prime_only` (CP on the prime core only):
  -0.27 W but frames > 18 ms 1.7 -> 6.3 % - off.
- 120 Hz: the system peak_refresh_rate is 60 again (owner setting), so "60 + FG" currently shows 60.
- CP profile at 60 while skating (cp_profile.ps1, d6): IssueDraw 65 % (shared-memory requests 23 %:
  uploads 9 %, write-watch mprotect 7 %, volatile-page memcmp 5.5 %; UpdateBindings 9.5 % with Turnip
  descriptor writes 6.4 %; textures 7.4 %; resolves 5 %), driver replay at submission 14 %, vsync
  waits 5.5 %, and **std::recursive_mutex::unlock 6.9 %** - the global critical region, released by
  SharedMemory::RequestRanges while guest threads wait for it (every kernel object handle lookup
  takes it).
- **SDK 0032 (`gpu_volatile_compare_unlocked`, default on):** volatile pages are compared with their
  shadows before taking the global critical region (shadows, epochs and page_volatile_ are CP-thread
  state; only the valid bits need the lock). Live A/B: skating CP 7.0 -> 6.5 ms/frame, game process
  1.68 -> 1.52 cores, slow frames 0.6 -> 0.4 %; standing 6.8 -> 6.1 ms, 1.48 -> 1.34 cores; power
  within noise (-0.02 / -0.07 W). The unlock share fell 6.9 -> 4.2 %; the rest is MakeRangeValid
  (mprotect under the lock).
- What is left on the CP is mostly real work (uploads of changed guest data, the driver replay,
  texture lookups); each further item is worth < 0.05 W, so they do not move the temperature.

### Session 6, part 3: d9 validated and installed

- thps8-d9 (repo bb99405 + PROGRESS, SDK 0001-0032) = **thps8-best.apk** (installed; old bests kept as
  thps8-best-c18-backup.apk and thps8-best-m16f-backup.apk). accept30 (30 + FG) 3 x PASS
  (0.1 / 0.1 / 0.0 %). 10 min soak at 60 / 85 % + FSR: **59.9 fps, frames > 18 ms 1.0 %, > 35 ms 0.0 %**,
  7.81 W, last minute CPU 79.4 / GPU 72.9 C - a heavier stretch of the scripted route than d6's soak
  (GPU 9.3 vs 8.2 ms per frame), so the temperatures are not comparable between the two.
- CP off the prime core at 60 (affinity mode 5, with held vblanks), live skating A/B x4: 58.4 vs
  59.7 fps, frames > 18 ms 9.7 vs 2.9 %, -0.36 W. Not smooth enough - the 60 fps mode keeps mode 2.
- Native renderer milestone A: not started. Its own checkpoint (session 4) already found that a lean
  replay of the same draws saves at most a few ms of GPU and none of the memory validation; with the
  GPU at ~8-9 ms per frame at 60 that is ~1-1.5 ms. Only milestone B (native shaders for the top
  materials) could take a bigger bite out of the GPU's heat, at multi-week cost and with exactness risk.

### Session 6, part 4: other areas, on-screen judder, the skater's shadow (SDK 0033-0034)

Owner report on d9: other maps lag more; the skater's shadow looks wrong in the lower resolution
modes; sometimes it shows 60 fps but feels like it drops frames.

**Other areas.** Free Skate has no level select (it always starts in Suburbia) and the owner's career
save (backed up to C:\thp8-work\android\save_backup_2026-10-11_1450) is at rank 194. `cheat_zone`
(dev_cheats.h) now takes any zone pack name and loads it through the game's own Free Skate loader
(level_select_change_level with load_z_<zone>); live_cvars.txt lines starting with `!` run a console
command (needs --dev_console=true). Tool zone_bench.ps1 (load each zone, skate 20-30 s, per-zone
numbers + CSV). 60 / 85 % + FSR, d9-era build, 20 s each:

| zone | fps | > 18 ms | > 35 ms | GPU ms | CP ms | draws |
|---|---|---|---|---|---|---|
| houses (Suburbia) | 60.0 | 0.1 % | 0.0 % | 9.2 | 7.5 | 949 |
| factory | 57.7 | 4.7 % | 0.7 % | 10.5 | 9.2 | 1170 |
| ghetto | 59.6 | 1.0 % | 0.2 % | 9.9 | 8.6 | 1016 |
| shops | 57.8 | 3.7 % | 0.6 % | 9.1 | 8.1 | 998 |
| center | 58.7 | 9.1 % | 0.1 % | 10.2 | 8.7 | 1126 |
| citypark | 56.2 | 10.0 % | 0.8 % | 9.5 | 7.7 | 914 |
| cretepark | 59.3 | 6.3 % | 0.0 % | 9.4 | 7.9 | 1072 |
| funpark | 57.3 | 8.0 % | 0.7 % | 10.9 | 9.1 | 1199 |
| outschool | 59.1 | 1.0 % | 0.2 % | 11.5 | 10.0 | 1264 |
| inschool | 59.3 | 3.9 % | 0.1 % | 10.7 | 8.9 | 1164 |
| parking | 59.8 | 1.5 % | 0.0 % | 9.4 | 6.9 | 884 |
| training | 59.0 | 0.6 % | 0.4 % | 5.7 | 3.7 | 280 |

Re-run on e8 (30 s each): shops 57.7 fps / 17 % > 18 ms, center 58.8 / 14 %, citypark 58.5 / 12 %,
factory 59.1 / 8 %. The slow frames (spikes.py) are dense views: ~1,900-2,100 draws, GPU 15.4 ms and
CP 14-16 ms per frame - both at the 16.7 ms limit; they take 18-22 ms (held vblanks keep them from
becoming 33). Not shader compiles or texture loads (< 1 %) or scheduling. Fitting them needs ~15-20 %
less per-draw cost on both the GPU and the CP (native renderer territory).

**"60 fps but it feels like dropped frames" = presentation judder.** New presenter counters (log
"presenter pacing", every 600 paints): with any UI drawer (the fps overlay) the UI thread paints the
newest guest frame once per display refresh, and the guest's own 60 Hz vblank grid ran on a separate
clock, drifting against the display: at a steady 60, 7-22 refreshes per 10 s repeated a frame and as
many guest frames were never shown (frames.csv and SurfaceFlinger both say 60). 30 + FG was worse:
the pacer handed over the real and the generated frame back to back, so ~85 of 600 refreshes per 10 s
repeated and as many frames were dropped.
- SDK 0033 `gpu_vblank_display_lock` (LaunchConfig on): display_vsync.cpp (AChoreographer thread)
  reports every display vsync through `rex_ui_set_display_vsync`; the vsync thread slews the guest
  vblank grid (<= 2 ms per vblank) to a phase half a refresh away from where the presenter takes
  frames (automatic: integrates the measured refresh vs paint phase; fixed phases 20 / 50 / 80 %
  measured 0 / 78 / 4 repeats - the right phase matters, hence automatic). 60 fps skating, live A/B
  x2: repeats 7.3 -> 1.3, never shown 8.5 -> 2.0 per 10 s.
- `gpu_frame_generation_wait_paint` (default on): the FG pacer waits until a paint took a frame before
  handing over the next. 30 + FG: repeats / never shown 84.5 / 86.3 -> 0 / 0 per 10 s; the guest
  still runs a locked 30 (97 % of frames 32-34 ms).

**The skater's shadow (SDK 0034).** At 50 % the shadow looked like a detached blob; at 100 % it was
attached. With only one of the two shadow targets slowed to every 16th frame (`gpu_shadow_skip_mask`,
level 3) the 1024 target turned out to carry the skater's own sun shadow: stale, it trails behind the
skater on the ground. Alternate-frame skipping (60 fps modes) therefore made it lag / jitter by half a
frame of motion. Default mask = 2 (only the 512 target alternates): skating A/B x3 +0.06 W, 59.7 vs
59.8 fps. Checked at 85 % and 50 % while skating: shadow attached. (Not resolution-specific: the 60 fps
modes are the ones the owner uses at lower resolutions. Lower resolutions also make the game's own
quarter-resolution buffers smaller - 264x152 at 100 %, 136x80 at 50 % - so shadows get softer at 50 %.)

Tools: zone_bench.ps1, pacing_ab.ps1, rec_ab.ps1 (screen recordings per live config -> frames),
res_shots.ps1; spikes.py (slow-frame classifier). Builds e1-e8.

- e8 = thps8-best.apk (installed; d9 kept as thps8-best-d9-backup.apk): accept30 3 x PASS (0.0 / 0.1 / 0.0 %, p99 33.8); 10 min soak at 60 not finished (stopped). HANDOFF7.

### Session 7 (2026-10-11): e8 soak, launcher art, project parked

- **e8 10 min soak at 60 / 85 % + FSR** (soak2.ps1, soak_e8_60_85): **59.7 fps, frames > 18 ms 1.6 %,
  > 35 ms 0.0 %**, GPU 10.6 ms/frame, 2.23 cores, 8.38 W, last minute CPU 88.9 / GPU 84.8 C (max 91.9 /
  85.2). Smoothness holds; the route was heavier than d9's soak (GPU 10.6 vs 9.3 ms) and the device ran
  much hotter (d9: 7.81 W, 79.4 / 72.9 C) - not a like-for-like thermal comparison, but heat at a real
  60 remains the open item (native renderer territory, HANDOFF7 section 3).
- **Launcher art** (owner request): app icon + background from the game's own title screen.
  tools/android/make_launcher_art.py <screenshot> [--fill-from Y] [--icon X,Y,SIZE] writes
  android/art-res/ (git-ignored: it is the game's picture); Gradle adds it as a resource folder and
  switches the app icon to it only when present (manifest placeholder appIcon); LauncherActivity draws
  it dimmed behind the controls, status panel on the left half. Made from a clean title-screen capture
  (C:\thp8-work\android\art_caps\067.png, `--fill-from 885 --icon 930,90,880`; the disc has no
  nxeart - THP8 predates the NXE dashboard). Capture helper: scripts\wip\art_caps.ps1.
- thps8-best.apk = **thps8-e8art** (native libraries byte-identical to e8, repackaged with the art;
  e8 kept as thps8-best-e8-backup.apk). Installed, device clean (no args.txt / live files), owner save
  untouched. Owner asked to leave THP8 here; next work is the Raging Blast 2 port (~/rb2/RB2).
