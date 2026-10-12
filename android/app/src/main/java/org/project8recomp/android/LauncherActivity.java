package org.project8recomp.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.WindowManager;
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
    private Button installButton;
    private TextView installStatus;
    private volatile boolean installing;
    private Button updateButton;
    private UpdateChecker.Info pendingUpdate;
    private volatile boolean updating;

    private static final int PICK_ISO = 1;
    // SHA-256 of the supported release's default.xex. The installer refuses any other executable.
    static final String[] ACCEPTED_XEX_SHA256 = {"cfc732340e55defda400e25f03231aa9bb65fd9545b618212f69a4952384a5dd"};

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

        installStatus = new TextView(this);
        installStatus.setTextColor(Color.rgb(255, 220, 120));
        installStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        installStatus.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        root.addView(installStatus);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START);
        playButton = button("Play", v -> startGame());
        Button probe = button("Run Vulkan probe", v -> runProbe());
        Button refresh = button("Refresh", v -> refresh());
        installButton = button("Select my Project 8 ISO", v -> pickIso());
        buttons.addView(playButton);
        buttons.addView(installButton);
        if (UpdateChecker.enabled(this)) {
            updateButton = button("Check for updates", v -> onUpdateButton());
            buttons.addView(updateButton);
        }
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
        updateInstallLabels();
        (gameInstalled() ? playButton : installButton).requestFocus();
        refresh();
        runProbe();
        if (UpdateChecker.enabled(this) && UpdateChecker.autoCheckDue(this)) {
            checkForUpdates(false);
        }

        if (getIntent().getBooleanExtra("play", false) && gameInstalled()) {
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

    private boolean gameInstalled() {
        return LaunchConfig.gameInstalled(this);
    }

    private void startGame() {
        if (installing) return;
        if (!gameInstalled()) {
            installStatus.setText("Install the game first: tap \"" + installButton.getText()
                    + "\" and pick the disc image you made from your own copy.");
            installButton.requestFocus();
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
    }

    private void pickIso() {
        if (installing) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_ISO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ISO || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        installing = true;
        playButton.setEnabled(false);
        installButton.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        installStatus.setText("Reading the disc image...");
        new Thread(() -> {
            String result;
            boolean ok;
            final long[] lastUi = {0};
            try {
                result = IsoInstaller.install(this, uri, LaunchConfig.gameDataRoot(this), ACCEPTED_XEX_SHA256,
                        (done, total, file) -> {
                            long now = System.currentTimeMillis();
                            if (now - lastUi[0] < 250) return;
                            lastUi[0] = now;
                            String line = String.format(Locale.US, "Installing... %d%%  (%.1f / %.1f GB)  %s",
                                    total > 0 ? (int) (done * 100 / total) : 0, done / 1e9, total / 1e9, file);
                            runOnUiThread(() -> installStatus.setText(line));
                        });
                ok = true;
            } catch (IsoInstaller.Failure f) {
                result = f.getMessage();
                ok = false;
            } catch (Throwable t) {
                result = "Install failed: " + t;
                ok = false;
            }
            final String msg = result;
            final boolean success = ok;
            runOnUiThread(() -> {
                installing = false;
                playButton.setEnabled(true);
                installButton.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                installStatus.setText(success ? msg + " Press Play." : "Not installed: " + msg);
                updateInstallLabels();
                refresh();
                (success ? playButton : installButton).requestFocus();
            });
        }, "iso-install").start();
    }

    private void onUpdateButton() {
        if (updating || installing) return;
        if (pendingUpdate != null) {
            downloadAndInstall(pendingUpdate);
        } else {
            checkForUpdates(true);
        }
    }

    private void checkForUpdates(boolean userAsked) {
        updating = true;
        updateButton.setText("Checking...");
        new Thread(() -> {
            UpdateChecker.Info info = null;
            String error = null;
            try {
                info = UpdateChecker.check(this);
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final UpdateChecker.Info found = info;
            final String err = error;
            runOnUiThread(() -> {
                updating = false;
                pendingUpdate = found;
                if (found != null) {
                    updateButton.setText("Update to v" + found.versionName);
                    installStatus.setText("Update available: v" + found.versionName
                            + (found.notes.isEmpty() ? "" : " - " + found.notes) + "  (tap \"Update to v"
                            + found.versionName + "\")");
                } else {
                    updateButton.setText("Check for updates");
                    if (userAsked) {
                        installStatus.setText(err != null ? "Could not check for updates: " + err
                                : "You have the latest version (v" + UpdateChecker.installedVersionName(this) + ").");
                    }
                }
            });
        }, "update-check").start();
    }

    private void downloadAndInstall(UpdateChecker.Info info) {
        updating = true;
        updateButton.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String msg;
            try {
                final long[] lastUi = {0};
                java.io.File apk = UpdateChecker.download(this, info, (done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUi[0] < 250) return;
                    lastUi[0] = now;
                    String line = String.format(Locale.US, "Downloading v%s... %d%%  (%.0f / %.0f MB)", info.versionName,
                            total > 0 ? (int) (done * 100 / total) : 0, done / 1e6, total / 1e6);
                    runOnUiThread(() -> installStatus.setText(line));
                });
                UpdateChecker.install(this, apk);
                msg = "Download verified. Confirm the update in the Android dialog; your game and saves are kept.";
            } catch (Throwable t) {
                msg = "Update failed: " + t.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                updating = false;
                updateButton.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                installStatus.setText(m);
            });
        }, "update-download").start();
    }

    private void updateInstallLabels() {
        installButton.setText(gameInstalled() ? "Reinstall from ISO" : "Select my Project 8 ISO");
        if (!gameInstalled() && !installing && installStatus.getText().length() == 0) {
            installStatus.setText("First time: tap \"Select my Project 8 ISO\" and pick the disc image you made"
                    + " from your own copy of the game. It is extracted and checked on this device.");
        }
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

        sb.append("App version: ").append(UpdateChecker.installedVersionName(this))
                .append(UpdateChecker.enabled(this) ? "" : " (development build, no update checks)").append('\n');
        File root = LaunchConfig.root(this);
        sb.append("\nData folder: ").append(root).append('\n');
        File xex = new File(LaunchConfig.gameDataRoot(this), "default.xex");
        sb.append("Game data:   ").append(xex.isFile()
                ? "found (default.xex " + xex.length() + " bytes)"
                : "NOT INSTALLED - use \"Select my ISO\"").append('\n');

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
