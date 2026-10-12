package org.project8recomp.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The in-game settings panel: frame rate, frame generation, upscaler and resolution while playing.
 * Opened by swiping in from the left or right screen edge (GameActivity); touch or gamepad
 * (d-pad + A, B closes).
 *
 * Frame rate, frame generation and the upscaler change at once (GameActivity.nativeSetCvar sets the
 * engine's live settings); the resolution is chosen by the game once at startup, so a new one is
 * saved and applied by "Restart now". Every choice is also saved for the next launch (the same
 * preferences the launcher's buttons use).
 */
final class InGameMenu {
    interface Native {
        boolean setCvar(String name, String value);

        /** The panel was shown / hidden (the activity pauses the game and re-hides the system bars). */
        void shown(boolean open);

        /** The frame rate mode changed: the activity asks the display for 120 Hz in mode 3. */
        void fpsModeChanged(int mode);
    }

    private final Activity activity;
    private final Native nativeSide;
    private final int startScale;       // resolution the running game was started with
    private final boolean startMsaa;    // ... and its anti-aliasing
    private final FrameLayout overlay;  // full screen: dims the game, closes on a tap outside
    private final LinearLayout panel;
    private final LinearLayout fpsRow, fgRow, upscalerRow, scaleRow, aaRow;
    private final TextView fgNote, scaleNote;
    private final Button restartButton;
    private boolean open;

    InGameMenu(Activity activity, ViewGroup parent, int startScale, Native nativeSide) {
        this.activity = activity;
        this.nativeSide = nativeSide;
        this.startScale = startScale;
        this.startMsaa = LaunchConfig.msaa(activity);

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(Color.argb(90, 0, 0, 0));
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> close());

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 20, 22, 28));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.argb(255, 70, 76, 90));
        panel.setBackground(bg);
        panel.setClickable(true);  // taps inside do not reach the overlay

        TextView title = text("Settings", 20, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title);

        fpsRow = row("Frame rate");
        fgRow = row("Frame generation");
        fgNote = note();
        panel.addView(fgNote);
        upscalerRow = row("FSR upscaling");
        scaleRow = row("Resolution");
        aaRow = row("Anti-aliasing");
        scaleNote = note();
        panel.addView(scaleNote);

        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(0, dp(10), 0, 0);
        restartButton = choice("Restart now", false);
        restartButton.setOnClickListener(v -> restart());
        Button closeButton = choice("Close", false);
        closeButton.setOnClickListener(v -> close());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        blp.setMargins(dp(2), 0, dp(2), 0);
        bottom.addView(restartButton, blp);
        bottom.addView(closeButton, new LinearLayout.LayoutParams(blp));
        panel.addView(bottom);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(430),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START);
        lp.setMargins(dp(16), dp(16), dp(16), dp(16));
        overlay.addView(scroll, lp);
        parent.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        refresh();
    }

    boolean isOpen() {
        return open;
    }

    /** Opens on the side the swipe came from. */
    void open(boolean fromRight) {
        View scroll = (View) panel.getParent();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        lp.gravity = Gravity.CENTER_VERTICAL | (fromRight ? Gravity.END : Gravity.START);
        scroll.setLayoutParams(lp);
        refresh();
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        scroll.setTranslationX(fromRight ? dp(60) : -dp(60));
        scroll.setAlpha(0f);
        scroll.animate().translationX(0).alpha(1f).setDuration(160).start();
        open = true;
        View first = fpsRow.getChildAt(1);
        if (first != null) {
            first.requestFocus();
        }
        nativeSide.shown(true);
    }

    void close() {
        if (!open) {
            return;
        }
        overlay.setVisibility(View.GONE);
        open = false;
        nativeSide.shown(false);
    }

    /** Gamepad / keyboard while open. Returns true when consumed (nothing reaches the game). */
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                close();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                View focused = overlay.findFocus();
                if (focused != null) {
                    focused.performClick();
                }
            }
            return true;
        }
        return false;
    }

    // --- settings ------------------------------------------------------------------------------

    private void setFpsMode(int mode) {
        LaunchConfig.setFpsMode(activity, mode);
        applyFpsMode(mode);
        refresh();
    }

    private void applyFpsMode(int mode) {
        // Same mapping as LaunchConfig.buildArguments.
        boolean sixty = LaunchConfig.sixty(mode);
        nativeSide.setCvar("gpu_swap_fps_cap", sixty ? "60" : "30");
        nativeSide.setCvar("android_affinity_mode", sixty ? "2" : "5");
        nativeSide.setCvar("gpu_shadow_skip_level", sixty ? "1" : "0");
        nativeSide.setCvar("gpu_frame_generation", LaunchConfig.frameGeneration(mode) ? "true" : "false");
        nativeSide.setCvar("gpu_vblank_hold_us", sixty && !LaunchConfig.frameGeneration(mode) ? "15000" : "0");
        nativeSide.fpsModeChanged(mode);
    }

    private void setFsr(boolean on) {
        LaunchConfig.setFsr(activity, on);
        nativeSide.setCvar("present_effect_live", on ? "2" : "1");
        refresh();
    }

    private void setMsaa(boolean on) {
        LaunchConfig.setMsaa(activity, on);
        refresh();
    }

    private void setScale(int scale) {
        LaunchConfig.setRenderScale(activity, scale);
        refresh();
    }

    private void restart() {
        Intent intent = new Intent(activity, LauncherActivity.class);
        intent.putExtra("play", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
        // The game process cannot unload its native libraries: end it; the launcher starts a new one.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void refresh() {
        int mode = LaunchConfig.fpsMode(activity);
        boolean fsr = LaunchConfig.fsr(activity);
        int scale = LaunchConfig.renderScale(activity);

        boolean sixty = LaunchConfig.sixty(mode);
        boolean fg = LaunchConfig.frameGeneration(mode);
        fill(fpsRow, new String[] {"30", "60"}, sixty ? 1 : 0, i -> setFpsMode(modeFor(i == 1, fg)));
        fill(fgRow, new String[] {"On", "Off"}, fg ? 0 : 1, i -> setFpsMode(modeFor(sixty, i == 0)));
        float hz = activity.getWindowManager().getDefaultDisplay().getRefreshRate();
        fgNote.setText(mode == 3 ? "60 fps game + a generated frame after each: 120 shown on a 120 Hz screen"
                        + (hz < 100 ? " (screen at " + Math.round(hz) + " Hz now: enable 120 Hz in the system display settings)." : ".")
                : mode == 2 ? "Real 60 fps, no generated frames."
                : mode == 0 ? "30 fps game, every second frame generated: 60 shown, coolest smooth option."
                : "Locked 30 fps, no generated frames.");
        fill(upscalerRow, new String[] {"On", "Off"}, fsr ? 0 : 1, i -> setFsr(i == 0));
        final int[] scales = LaunchConfig.RENDER_SCALES;
        fill(scaleRow, labels(scales), indexOf(scales, scale), i -> setScale(scales[i]));
        boolean msaa = LaunchConfig.msaa(activity);
        fill(aaRow, new String[] {"2x MSAA", "Off"}, msaa ? 0 : 1, i -> setMsaa(i == 0));
        boolean pending = scale != startScale || msaa != startMsaa;
        scaleNote.setText(LaunchConfig.renderSize(scale, msaa) + (scale == 100 ? " (native)" : "")
                + (pending ? "  -  resolution / anti-aliasing apply after a restart (unsaved progress is lost)" : "")
                + "\nLower resolution or anti-aliasing off = cooler and faster (off: ~12 % less GPU work).");
        restartButton.setVisibility(pending ? View.VISIBLE : View.GONE);
    }

    /** 0 = 30 + frame generation, 1 = 30, 2 = 60, 3 = 60 + frame generation (LaunchConfig). */
    private static int modeFor(boolean sixty, boolean frameGeneration) {
        return sixty ? (frameGeneration ? 3 : 2) : (frameGeneration ? 0 : 1);
    }

    private static String[] labels(int[] scales) {
        String[] out = new String[scales.length];
        for (int i = 0; i < scales.length; i++) {
            out[i] = scales[i] + "%";
        }
        return out;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    // --- views ---------------------------------------------------------------------------------

    private interface Pick {
        void on(int index);
    }

    private LinearLayout row(String label) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, 0);
        TextView name = text(label, 14, Color.rgb(200, 205, 215));
        r.addView(name, new LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(r);
        return r;
    }

    /** Replaces a row's choice buttons, keeping the focused position when it had focus. */
    private void fill(LinearLayout r, String[] labels, int selected, Pick pick) {
        int focusedIndex = -1;
        for (int i = 1; i < r.getChildCount(); i++) {
            if (r.getChildAt(i).hasFocus()) {
                focusedIndex = i - 1;
            }
        }
        while (r.getChildCount() > 1) {
            r.removeViewAt(1);
        }
        for (int i = 0; i < labels.length; i++) {
            Button b = choice(labels[i], i == selected);
            final int index = i;
            b.setOnClickListener(v -> pick.on(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            r.addView(b, lp);
            if (i == focusedIndex) {
                b.requestFocus();
            }
        }
    }

    private Button choice(String label, boolean selected) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setTextColor(selected ? Color.BLACK : Color.WHITE);
        b.setFocusable(true);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, shape(selected
                ? Color.rgb(140, 220, 255) : Color.rgb(70, 80, 100), Color.WHITE));
        states.addState(new int[] {android.R.attr.state_pressed}, shape(Color.rgb(90, 100, 120), 0));
        states.addState(new int[] {}, shape(selected ? Color.rgb(110, 200, 245) : Color.rgb(48, 54, 66), 0));
        b.setBackground(states);
        b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        if (stroke != 0) {
            d.setStroke(dp(2), stroke);
        }
        return d;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView note() {
        TextView t = text("", 11, Color.rgb(150, 156, 170));
        t.setPadding(dp(128), dp(2), 0, 0);
        return t;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
