package org.project8recomp.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where things live and which command-line settings the game starts with.
 *
 * Everything is under the app's external files folder (Android/data/&lt;package&gt;/files), which needs
 * no storage permission and is reachable over USB / adb:
 *
 *   game/    the extracted disc (contains default.xex), copied here by the user
 *   saves/   the game's user data
 *   cache/   shader and pipeline caches
 *   logs/    the game's log, the Vulkan probe report
 *   perf/    per-frame timing CSV
 *   args.txt optional, one setting per line (e.g. --vsync=false); overrides the defaults below
 */
final class LaunchConfig {
    private LaunchConfig() {}

    static File root(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir != null ? dir : context.getFilesDir();
    }

    static File dir(Context context, String name) {
        File dir = new File(root(context), name);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    static File gameDataRoot(Context context) {
        return new File(root(context), "game");
    }

    static boolean gameInstalled(Context context) {
        return new File(gameDataRoot(context), "default.xex").isFile();
    }

    // The 17 "Performance" settings the desktop launcher turns on by default
    // (src/launcher/src/settings.cpp, Settings::PerformanceFlags). Keep in sync with that list.
    //
    // Android differences, measured on the Thor (Adreno 740): the four "native residency" flags
    // are left OFF (they copy vertex data between draws, which splits render passes: on a tiled GPU
    // that cost 40% of the intro's frame rate), and the Android-only settings below are added.
    private static final String[] PERFORMANCE = {
        "--gpu_cp_fastpath=true",
        "--primitive_processor_cache_min_indices=4096",
        "--gpu_sampler_set_reuse=true",
        "--gpu_texture_request_reuse=true",
        "--gpu_texture_last_view_cache=true",
        "--gpu_texture_descriptor_set_adjacent_reuse=true",
        "--timer_queue_blocking_wait=true",
        "--guest_ring_wait_backoff=true",
        "--guest_ring_wait_event=true",
        "--guest_swap_wait=true",
        "--guest_u8x4_unpack_native=true",
        "--guest_vertex_unpack_native=true",
        "--guest_vertex_unpack_simd=true",
        // Android / tiled-GPU settings (SDK patches 0002 and 0003).
        "--gpu_hoist_shared_memory_uploads=true",  // keep render passes open across uploads
        "--gpu_hoist_texture_loads=true",          // ... and across texture loads (patch 0007)
        // Leaner translated pixel shaders (SDK 0018): implicit-LOD sampling instead of explicit
        // gradients (Adreno's gradient sample is slow), and texture signedness / gamma / exponent
        // bias as pipeline-time constants instead of per-pixel branches. Same picture; garage GPU
        // 27.5 -> 22.0 ms.
        "--gpu_implicit_texture_lod=true",
        "--gpu_static_texture_signs=true",
        // Resolves of 8_8_8_8 render targets read the host render target in one compute pass
        // instead of dumping it to the EDRAM buffer first (SDK 0021; garage GPU 21.9 -> 18.9 ms,
        // static picture bit-identical).
        "--gpu_fused_resolve=true",
        // Mesa Turnip, bundled in the APK (tools/android/fetch_turnip.sh): renders without the stock
        // driver's blue-haze bug and skates as fast or faster. Falls back to the stock driver when
        // the bundle is missing or twice failed to start. Override with --android_vulkan_driver=
        // (empty) in args.txt to force the stock driver.
        "--android_vulkan_driver=turnip",
        // CPU waste found by profiling the Thor (guest threads spinning on frame counters and in
        // polling waits) and the scheduler stacking the hottest threads on the one prime core.
        "--guest_counter2_wait=true",
        "--kernel_wait_poll_backoff_us=100",
        "--android_thread_affinity=true",
        // 30 fps modes: no game thread on the prime (X3) core - the mid cores do the work for less
        // power (30 + FG: 6.68 -> 6.30 W, locked 30 passes). The 60 fps modes override this below.
        "--android_affinity_mode=5",
        // Present every second guest vblank: the game runs uncapped when a frame is cheap, which
        // mixed 16.7 and 33.3 ms frames while skating. With the CP<->GPU serialisation fixed
        // (SDK 0012/0013) the frame fits 33.3 ms, so this gives a locked, even 30 fps.
        "--gpu_swap_fps_cap=30",
        // Frame generation: a motion-compensated frame between every two guest frames, presented by
        // a pacer thread with FIFO presentation, so the locked 30 is shown at 60 (SDK 0015).
        // Turn off with --gpu_frame_generation=false (and allow mailbox presentation again).
        "--gpu_frame_generation=true",
        "--vulkan_allow_present_mode_mailbox=false",
        "--vulkan_allow_present_mode_immediate=false",
        "--vulkan_allow_present_mode_fifo_relaxed=false",
        // NOT gpu_force_msaa_off: it saves ~3 ms of GPU time but breaks the game's post-processing
        // (a blue-grey blur over the whole screen in gameplay; resolves read data laid out for 2x
        // MSAA). The warning "Incorrect resolve sample selected for 1-sample color" marks it.
    };

    // Player options chosen in the launcher (SharedPreferences "options"); args.txt still wins.
    static final int[] RENDER_SCALES = {100, 85, 75, 60, 50};

    static int renderScale(Context context) {
        return context.getSharedPreferences("options", Context.MODE_PRIVATE).getInt("render_scale", 100);
    }

    // Frame rate modes: 0 = locked 30 shown at 60 with frame generation (default), 1 = locked 30,
    // 2 = up to 60 (real frames; on a 60 Hz screen frames that miss 16.7 ms are shown for 33.3 ms),
    // 3 = up to 60 + frame generation: 120 shown on a 120 Hz screen (the game asks the display for
    // 120 Hz; needs the system's refresh rate setting at 120, else it is the same as mode 2).
    static final String[] FPS_MODES = {"30 + frame generation (60 shown)", "Locked 30", "Up to 60 (uneven)",
            "60 + frame generation (120 shown)"};

    static boolean sixty(int fpsMode) {
        return fpsMode == 2 || fpsMode == 3;
    }

    static boolean frameGeneration(int fpsMode) {
        return fpsMode == 0 || fpsMode == 3;
    }

    static int fpsMode(Context context) {
        return context.getSharedPreferences("options", Context.MODE_PRIVATE).getInt("fps_mode", 0);
    }

    static void setRenderScale(Context context, int scale) {
        context.getSharedPreferences("options", Context.MODE_PRIVATE).edit().putInt("render_scale", scale).apply();
    }

    // Upscaler from the internal resolution to the screen: bilinear, or AMD FSR 1 (EASU + RCAS).
    static boolean fsr(Context context) {
        return context.getSharedPreferences("options", Context.MODE_PRIVATE).getBoolean("fsr", true);
    }

    static void setFsr(Context context, boolean on) {
        context.getSharedPreferences("options", Context.MODE_PRIVATE).edit().putBoolean("fsr", on).apply();
    }

    // Anti-aliasing: the game's own display mode table (guest_resolution.h) has 1040x584 with 2x
    // MSAA (what it picks) and 1040x592 without MSAA. Without it the heaviest view (garage) runs
    // 44.7 -> 54.7 fps uncapped, GPU 17.8 -> 15.6 ms per frame; the game sets up everything for the
    // mode itself (unlike forcing MSAA off in the GPU emulation, which broke post-processing).
    static boolean msaa(Context context) {
        return context.getSharedPreferences("options", Context.MODE_PRIVATE).getBoolean("msaa", false);
    }

    static void setMsaa(Context context, boolean on) {
        context.getSharedPreferences("options", Context.MODE_PRIVATE).edit().putBoolean("msaa", on).apply();
    }

    // On-screen frame rate ("30 fps", the game's own rate) in the top right corner.
    static boolean showFps(Context context) {
        return context.getSharedPreferences("options", Context.MODE_PRIVATE).getBoolean("show_fps", false);
    }

    static void setShowFps(Context context, boolean on) {
        context.getSharedPreferences("options", Context.MODE_PRIVATE).edit().putBoolean("show_fps", on).apply();
    }

    static void setFpsMode(Context context, int mode) {
        context.getSharedPreferences("options", Context.MODE_PRIVATE).edit().putInt("fps_mode", mode).apply();
    }

    /** Internal resolution at a scale, rounded like guest_resolution.h (the game's own is 1040x584). */
    static String renderSize(int scale) {
        return renderSize(scale, true);
    }

    /** Without MSAA the game's own mode is 1040x592 (display mode table entry 7). */
    static String renderSize(int scale, boolean msaa) {
        if (scale >= 100) {
            return msaa ? "1040x584" : "1040x592";
        }
        int w = Math.max(320, (1040 * scale / 100 + 4) & ~7);
        int h = Math.max(176, ((msaa ? 584 : 592) * scale / 100 + 4) & ~7);
        return w + "x" + h;
    }

    /** Name part of "--name=value", used to let args.txt replace a default instead of repeating it. */
    private static String optionName(String arg) {
        int eq = arg.indexOf('=');
        return eq < 0 ? arg : arg.substring(0, eq);
    }

    static String[] buildArguments(Context context) {
        File root = root(context);
        // The CLI parser rejects a repeated non-boolean option, so later sources replace earlier ones.
        Map<String, String> args = new LinkedHashMap<>();
        List<String> defaults = new ArrayList<>();
        defaults.add("--game_data_root=" + gameDataRoot(context));
        defaults.add("--user_data_root=" + dir(context, "saves"));
        defaults.add("--cache_root=" + dir(context, "cache"));
        defaults.add("--log_file=" + new File(dir(context, "logs"), "thps_p8.log"));
        defaults.add("--log_level=info");
        defaults.add("--log_flush_interval=2");
        defaults.add("--perf_log_csv=" + new File(dir(context, "perf"), "frames.csv"));
        defaults.add("--gpu_plugin=xenos");
        defaults.add("--mnk_mode=true");
        // Without this the window is not fullscreen: the system bars stay on screen and the picture
        // is letterboxed inside the remaining area.
        defaults.add("--fullscreen=true");
        // Adreno has no sparse binding; the framebuffer-object render target path is the portable one
        // (the same choice the SDK's own ARM64 Linux launcher makes).
        defaults.add("--vulkan_sparse_shared_memory=false");
        defaults.add("--render_target_path_vulkan=fbo");
        for (String flag : PERFORMANCE) {
            defaults.add(flag);
        }
        defaults.add("--android_render_scale=" + Math.min(100, renderScale(context)));
        if (!msaa(context)) {
            defaults.add("--android_video_mode_index=7");
        }
        // No busy-waiting before vblanks / the frame cap (was 3 ms per vblank each on a fast core;
        // measured: -0.14 cores, same frame pacing).
        defaults.add("--vblank_spin_us=0");
        defaults.add("--gpu_swap_cap_spin_us=0");
        // The game's vsync wait (WAIT_REG_MEM): sleep up to 1 ms per poll and yield only in the last
        // 0.3 ms before a vblank (was: 125 us sleeps and 1.5 ms of yielding before every vblank).
        defaults.add("--gpu_regmem_yield_window_us=300");
        defaults.add("--gpu_regmem_max_sleep_us=1000");
        // The game switches itself to presenting every second vblank (a script variable) and then
        // stays at an even 30 however light the frame is; the frame rate cap decides instead.
        defaults.add("--android_present_interval_max=1");
        // ... and its own frame limiter waits that many vblanks per frame (menus, cutscenes and some
        // heavy stretches use 2 = a flat 30 even when the frame is light); the cap decides there too.
        defaults.add("--guest_frame_interval_max=1");
        // The guest 60 Hz vblank follows the display's own vsync (AChoreographer, display_vsync.cpp)
        // at the phase where the presenter shows every guest frame exactly once. Unlocked, the two
        // clocks drifted: at a steady 60, 7-22 refreshes per 10 s repeated a frame and as many guest
        // frames were never shown (judder the fps counter cannot show). SDK 0033.
        defaults.add("--gpu_vblank_display_lock=true");
        defaults.add("--present_effect=" + (fsr(context) ? "fsr" : "bilinear"));
        if (showFps(context)) {
            defaults.add("--show_debug_overlay=true");
        }
        // The in-game menu switches frame generation and the frame rate while the game runs, so the
        // generator always exists and presentation stays FIFO (the generator's pacing needs it).
        defaults.add("--gpu_frame_generation_available=true");
        int fpsMode = fpsMode(context);
        if (!frameGeneration(fpsMode)) {
            defaults.add("--gpu_frame_generation=false");
        }
        if (sixty(fpsMode)) {
            defaults.add("--gpu_swap_fps_cap=60");
            // The GPU command processor alone on the prime core: in the heaviest view (garage) it
            // needs 11.9 ms per frame there vs 17.9 ms on a mid core - 51.6 fps instead of 30, and
            // less energy per frame (0.16 vs 0.21 J).
            defaults.add("--android_affinity_mode=2");
            // The two shadow maps are redrawn on alternate frames: each still updates 30 times a
            // second (as at the game's own 30 fps). Garage 51.2 -> 54.6 fps, GPU 16.3 -> 15.6 ms.
            defaults.add("--gpu_shadow_skip_level=1");
            if (!frameGeneration(fpsMode)) {
                // A frame still being emulated at a vblank holds that vblank until it is ready (up
                // to 15 ms; SDK 0030), so a frame that needs 18 ms shows after 18 ms instead of
                // dropping to 30. Skating 56.6 -> 59.2 fps; command-processor-bound garage 30 -> 57.
                defaults.add("--gpu_vblank_hold_us=15000");
            }
        }
        for (String arg : defaults) {
            args.put(optionName(arg), arg);
        }

        File override = new File(root, "args.txt");
        if (override.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(override))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        args.put(optionName(line), line);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("thps8", "could not read args.txt", e);
            }
        }
        return args.values().toArray(new String[0]);
    }
}
