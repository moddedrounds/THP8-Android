package org.project8recomp.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Minimal launcher: shows what the device and the install look like, runs the Vulkan probe and
 * starts the game. Built from code so it has no layout or library dependencies; every control is
 * focusable, so the Thor's d-pad and A button drive it.
 *
 * `adb shell am start -n org.project8recomp.android/.LauncherActivity --ez play true` starts the game
 * immediately.
 */
public class LauncherActivity extends Activity {
    private TextView status;
    private String probeReport = "(probe not run yet)";
    private Button playButton;
    private Button resolutionButton;
    private Button frameGenButton;
    private Button upscalerButton;
    private Button showFpsButton;

    private void nextRenderScale() {
        int current = LaunchConfig.renderScale(this);
        int[] scales = LaunchConfig.RENDER_SCALES;
        int next = scales[0];
        for (int i = 0; i < scales.length; i++) {
            if (scales[i] == current) {
                next = scales[(i + 1) % scales.length];
            }
        }
        LaunchConfig.setRenderScale(this, next);
        updateOptionLabels();
    }

    private void updateOptionLabels() {
        int scale = LaunchConfig.renderScale(this);
        resolutionButton.setText("Resolution: " + scale + "% (" + LaunchConfig.renderSize(scale) + ")"
                + (scale == 100 ? " native" : ""));
        upscalerButton.setText("Upscaler: " + (LaunchConfig.fsr(this) ? "FSR 1" : "bilinear"));
        int mode = LaunchConfig.fpsMode(this);
        frameGenButton.setText("Frame rate: " + LaunchConfig.FPS_MODES[Math.max(0, Math.min(mode, LaunchConfig.FPS_MODES.length - 1))]);
        showFpsButton.setText("Show FPS: " + (LaunchConfig.showFps(this) ? "on" : "off"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Background: the game's own title screen (android/art-res, made from a screenshot by
        // tools/android/make_launcher_art.py) when the build has it, dimmed so the text stays readable.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(18, 18, 22));
        int bg = getResources().getIdentifier("launcher_bg", "drawable", getPackageName());
        if (bg != 0) {
            ImageView art = new ImageView(this);
            art.setImageResource(bg);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(art, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            View dim = new View(this);
            dim.setBackgroundColor(Color.argb(90, 0, 0, 0));
            frame.addView(dim, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        frame.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Tony Hawk's Project 8 - Android port");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        root.addView(title);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START);
        playButton = button("Play", v -> startGame());
        Button probe = button("Run Vulkan probe", v -> runProbe());
        Button refresh = button("Refresh", v -> refresh());
        buttons.addView(playButton);
        buttons.addView(probe);
        buttons.addView(refresh);
        root.addView(buttons);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        resolutionButton = button("", v -> nextRenderScale());
        frameGenButton = button("", v -> {
            LaunchConfig.setFpsMode(this, (LaunchConfig.fpsMode(this) + 1) % LaunchConfig.FPS_MODES.length);
            updateOptionLabels();
        });
        upscalerButton = button("", v -> {
            LaunchConfig.setFsr(this, !LaunchConfig.fsr(this));
            updateOptionLabels();
        });
        options.addView(resolutionButton);
        options.addView(upscalerButton);
        showFpsButton = button("", v -> {
            LaunchConfig.setShowFps(this, !LaunchConfig.showFps(this));
            updateOptionLabels();
        });
        options.addView(frameGenButton);
        options.addView(showFpsButton);
        root.addView(options);
        updateOptionLabels();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.argb(170, 10, 10, 14));
        scroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextColor(Color.rgb(210, 220, 210));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextIsSelectable(true);
        scroll.addView(status);
        // Left half only when there is art, so the game's logo on the right stays visible.
        LinearLayout lower = new LinearLayout(this);
        lower.setOrientation(LinearLayout.HORIZONTAL);
        lower.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        if (bg != 0) {
            lower.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        root.addView(lower, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(frame);
        playButton.requestFocus();
        refresh();
        runProbe();

        if (getIntent().getBooleanExtra("play", false)) {
            startGame();
        }
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void startGame() {
        startActivity(new Intent(this, GameActivity.class));
    }

    private void runProbe() {
        probeReport = "(running...)";
        refresh();
        new Thread(() -> {
            String report;
            try {
                report = VulkanProbe.run();
            } catch (Throwable t) {
                report = "Vulkan probe failed to run: " + t;
            }
            final String finalReport = report;
            try (FileWriter writer = new FileWriter(new File(LaunchConfig.dir(this, "logs"), "vulkan_probe.txt"))) {
                writer.write(finalReport);
            } catch (IOException ignored) {
            }
            runOnUiThread(() -> {
                probeReport = finalReport;
                refresh();
            });
        }, "vulkan-probe").start();
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL);
        if (Build.VERSION.SDK_INT >= 31) {
            sb.append("  (SoC ").append(Build.SOC_MODEL).append(')');
        }
        sb.append("\nAndroid ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append("), ABIs ").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n');
        Display display = getWindowManager().getDefaultDisplay();
        sb.append(String.format(Locale.US, "Display: %.0f Hz\n", display.getRefreshRate()));

        File root = LaunchConfig.root(this);
        sb.append("\nData folder: ").append(root).append('\n');
        File xex = new File(LaunchConfig.gameDataRoot(this), "default.xex");
        sb.append("Game data:   ").append(xex.isFile()
                ? "found (default.xex " + xex.length() + " bytes)"
                : "NOT FOUND - copy the extracted disc to " + LaunchConfig.gameDataRoot(this)).append('\n');

        File libs = new File(getApplicationInfo().nativeLibraryDir);
        sb.append("Native libs: ");
        for (String prefix : new String[] {"libmain", "librexruntime", "librexgpu-xenos"}) {
            File[] found = libs.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".so"));
            sb.append(found != null && found.length > 0 ? found[0].getName() + " ok  " : prefix + " MISSING  ");
        }
        sb.append("\nLog file:    ").append(new File(LaunchConfig.dir(this, "logs"), "thps_p8.log")).append('\n');
        sb.append("\n--- Vulkan probe ---\n").append(probeReport);
        status.setText(sb.toString());
    }
}
