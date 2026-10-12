package org.project8recomp.android;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

import org.libsdl.app.SDLActivity;

/**
 * The game's window. SDL3's SDLActivity loads the native libraries and runs SDL_main (native/src/
 * android_main.cpp) on its own thread.
 *
 * Runs in its own process (":game", see the manifest): native libraries cannot be reloaded inside a
 * process, so every game session must start from a clean one.
 */
public class GameActivity extends SDLActivity {

    // SDL3 itself is linked statically into librexruntime.so, and its JNI methods live there, so the
    // runtime has to be loaded explicitly; the last entry is the library holding SDL_main.
    /**
     * Custom GPU drivers (Mesa Turnip folders) are put by the user into files/drivers/<name>/ of the
     * external app folder; a library can only be dlopen'd from internal storage, so copy them over
     * (when missing or a different size) before the native side starts.
     */
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        try {
            // Drivers bundled in the APK (assets/drivers/<name>/<file>).
            android.content.res.AssetManager assets = getAssets();
            String[] bundled = assets.list("drivers");
            if (bundled != null) {
                for (String name : bundled) {
                    String[] files = assets.list("drivers/" + name);
                    if (files == null) {
                        continue;
                    }
                    java.io.File dst = new java.io.File(getFilesDir(), "drivers/" + name);
                    //noinspection ResultOfMethodCallIgnored
                    dst.mkdirs();
                    for (String file : files) {
                        java.io.File out = new java.io.File(dst, file);
                        long want;
                        try (android.content.res.AssetFileDescriptor afd =
                                     assets.openFd("drivers/" + name + "/" + file)) {
                            want = afd.getLength();
                        } catch (java.io.IOException compressed) {
                            want = -1;  // stored compressed: no length without reading it
                        }
                        if (out.isFile() && want >= 0 && out.length() == want) {
                            continue;
                        }
                        try (java.io.InputStream in = assets.open("drivers/" + name + "/" + file);
                             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                os.write(buf, 0, n);
                            }
                        }
                        android.util.Log.i("thps8", "bundled driver file copied: " + out);
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("thps8", "bundled driver copy failed", t);
        }
        try {
            java.io.File src = new java.io.File(LaunchConfig.root(this), "drivers");
            java.io.File[] folders = src.listFiles();
            if (folders != null) {
                for (java.io.File folder : folders) {
                    java.io.File[] files = folder.listFiles();
                    if (files == null) {
                        continue;
                    }
                    java.io.File dst = new java.io.File(getFilesDir(), "drivers/" + folder.getName());
                    //noinspection ResultOfMethodCallIgnored
                    dst.mkdirs();
                    for (java.io.File f : files) {
                        java.io.File out = new java.io.File(dst, f.getName());
                        if (!f.isFile() || (out.isFile() && out.length() == f.length())) {
                            continue;
                        }
                        try (java.io.InputStream in = new java.io.FileInputStream(f);
                             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                os.write(buf, 0, n);
                            }
                        }
                        android.util.Log.i("thps8", "driver file copied: " + out);
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("thps8", "driver copy failed", t);
        }
        super.onCreate(savedInstanceState);
        if (mLayout != null && !mBrokenLibraries) {
            menu = new InGameMenu(this, mLayout, LaunchConfig.renderScale(this), new InGameMenu.Native() {
                @Override
                public boolean setCvar(String name, String value) {
                    try {
                        return nativeSetCvar(name, value);
                    } catch (UnsatisfiedLinkError e) {
                        return false;  // native side not loaded yet
                    }
                }

                @Override
                public void shown(boolean open) {
                    try {
                        nativeSystemUi(open);
                    } catch (UnsatisfiedLinkError ignored) {
                    }
                    hideSystemBars();
                    if (!open && mSurface != null) {
                        mSurface.requestFocus();
                    }
                }

                @Override
                public void fpsModeChanged(int mode) {
                    requestDisplayRate(mode == 3);
                    getWindow().getDecorView().postDelayed(GameActivity.this::hideSystemBars, 800);
                }
            });
            // Let the edge swipes through to the app instead of the system back gesture (Android
            // allows up to 200 dp per edge; elsewhere the back gesture opens the menu too).
            final View decor = getWindow().getDecorView();
            // An edge swipe also reveals the system bars, and they stayed (shrinking the picture):
            // hide them again shortly after they appear.
            decor.setOnSystemUiVisibilityChangeListener(visibility -> {
                if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                    decor.postDelayed(this::hideSystemBars, 1500);
                }
            });
            decor.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int w = r - l, h = b - t, band = dp(200), edge = dp(EDGE_DP);
                int top = Math.max(0, h / 2 - band / 2);
                v.setSystemGestureExclusionRects(Arrays.asList(
                        new Rect(0, top, edge, top + band), new Rect(w - edge, top, w, top + band)));
            });
        }
    }

    // --- in-game menu ------------------------------------------------------------------------------

    /** Sets an engine setting by name while the game runs (android/native/src/live_settings.cpp). */
    static native boolean nativeSetCvar(String name, String value);

    /** Tells the game the Xbox Guide opened / closed (it pauses gameplay). */
    static native void nativeSystemUi(boolean open);

    /** The same full-screen flags SDL sets (SDLActivity COMMAND_CHANGE_WINDOW_STYLE). */
    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.systemBars());
            }
        }
    }

    private static final int EDGE_DP = 36;      // a swipe must start this close to the left/right edge
    private static final int SWIPE_DP = 48;     // ... and travel this far inwards
    private InGameMenu menu;
    private boolean edgeTracking, edgeFromRight, swallowGesture;
    private float edgeDownX, edgeDownY;
    private float hatX, hatY;

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void openMenu(boolean fromRight) {
        if (menu != null && !menu.isOpen()) {
            menu.open(fromRight);
        }
    }

    private void closeMenuIfOpen() {
        if (menu != null && menu.isOpen()) {
            menu.close();
        }
        if (mSurface != null) {
            mSurface.requestFocus();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (menu == null) {
            return super.dispatchTouchEvent(ev);
        }
        final int action = ev.getActionMasked();
        if (swallowGesture) {
            // The rest of the swipe that opened the menu.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                swallowGesture = false;
            }
            return true;
        }
        if (menu.isOpen()) {
            boolean handled = super.dispatchTouchEvent(ev);
            if (!menu.isOpen() && mSurface != null) {
                mSurface.requestFocus();
            }
            return handled;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            int width = getWindow().getDecorView().getWidth();
            float x = ev.getX();
            edgeTracking = x < dp(EDGE_DP) || x > width - dp(EDGE_DP);
            edgeFromRight = x > width / 2f;
            edgeDownX = x;
            edgeDownY = ev.getY();
        } else if (action == MotionEvent.ACTION_MOVE && edgeTracking) {
            float dx = edgeFromRight ? edgeDownX - ev.getX() : ev.getX() - edgeDownX;
            float dy = Math.abs(ev.getY() - edgeDownY);
            if (dx > dp(SWIPE_DP) && dx > dy) {
                edgeTracking = false;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                swallowGesture = true;
                openMenu(edgeFromRight);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            edgeTracking = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (menu != null && menu.isOpen()) {
            if (menu.onKey(event)) {
                if (!menu.isOpen() && mSurface != null) {
                    mSurface.requestFocus();
                }
                return true;
            }
            // D-pad and the rest: to the focused menu button, never to the game.
            getWindow().superDispatchKeyEvent(event);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (menu != null && menu.isOpen()) {
            // Sticks stay away from the game; a d-pad reported as a hat moves the menu focus.
            if ((ev.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    && ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float x = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
                float y = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
                int key = 0;
                if (x < -0.5f && hatX >= -0.5f) key = KeyEvent.KEYCODE_DPAD_LEFT;
                else if (x > 0.5f && hatX <= 0.5f) key = KeyEvent.KEYCODE_DPAD_RIGHT;
                else if (y < -0.5f && hatY >= -0.5f) key = KeyEvent.KEYCODE_DPAD_UP;
                else if (y > 0.5f && hatY <= 0.5f) key = KeyEvent.KEYCODE_DPAD_DOWN;
                hatX = x;
                hatY = y;
                if (key != 0) {
                    getWindow().superDispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                    getWindow().superDispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                }
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onBackPressed() {
        // The system back gesture is a swipe in from either edge too: it opens / closes the menu
        // instead of leaving the game.
        if (menu == null) {
            super.onBackPressed();
        } else if (menu.isOpen()) {
            closeMenuIfOpen();
        } else {
            openMenu(false);
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {runtimeLibraryName(this), "main"};
    }

    /**
     * The SDK suffixes its library names with the build configuration ("rexruntimerd" for a
     * RelWithDebInfo build, plain "rexruntime" for Release); use whichever was packaged.
     */
    static String runtimeLibraryName(Context context) {
        java.io.File dir = new java.io.File(context.getApplicationInfo().nativeLibraryDir);
        java.io.File[] found = dir.listFiles((d, name) -> name.startsWith("librexruntime") && name.endsWith(".so"));
        if (found != null && found.length > 0) {
            String name = found[0].getName();
            return name.substring(3, name.length() - 3);   // strip "lib" and ".so"
        }
        return "rexruntime";
    }

    @Override
    protected String[] getArguments() {
        return LaunchConfig.buildArguments(this);
    }

    /**
     * Experiment: when files/highrefresh.txt exists, ask for the display's fastest mode at the
     * current resolution (the Thor's panel runs 60 Hz unless the app asks for 120).
     */
    /**
     * Writes the current display refresh rate (rounded Hz) to files/display_hz.txt; frame generation
     * reads it to show 2 frames per guest frame at 60 Hz and 4 at 120 Hz.
     */
    private void writeDisplayHz() {
        try {
            float hz = getWindowManager().getDefaultDisplay().getRefreshRate();
            java.io.File f = new java.io.File(LaunchConfig.root(this), "display_hz.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f)) {
                w.write(Integer.toString(Math.round(hz)));
            }
        } catch (Throwable t) {
            android.util.Log.w("thps8", "display_hz failed", t);
        }
    }

    private android.hardware.display.DisplayManager.DisplayListener displayListener;

    @Override
    protected void onResume() {
        super.onResume();
        writeDisplayHz();
        if (displayListener == null) {
            displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) { writeDisplayHz(); }
            };
            ((android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE))
                    .registerDisplayListener(displayListener, null);
        }
        requestDisplayRate(LaunchConfig.fpsMode(this) == 3
                || new java.io.File(LaunchConfig.root(this), "highrefresh.txt").exists());
        // A display mode switch can bring the system bars back: hide them again once it settled.
        getWindow().getDecorView().postDelayed(this::hideSystemBars, 800);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && menu != null) {
            hideSystemBars();
        }
    }

    /**
     * Asks for the display's 120 Hz mode (60 fps + frame generation shows 120) or its 60 Hz mode
     * (everything else: no point refreshing faster than the frames, and it costs power). The
     * system's peak refresh rate setting still caps what is granted.
     */
    private void requestDisplayRate(boolean high) {
        try {
            android.view.Display display = getWindowManager().getDefaultDisplay();
            android.view.Display.Mode current = display.getMode();
            float want = high ? 120f : 60f;
            int best = current.getModeId();
            float bestDiff = Float.MAX_VALUE;
            for (android.view.Display.Mode m : display.getSupportedModes()) {
                if (m.getPhysicalWidth() == current.getPhysicalWidth()
                        && m.getPhysicalHeight() == current.getPhysicalHeight()
                        && Math.abs(m.getRefreshRate() - want) < bestDiff) {
                    best = m.getModeId();
                    bestDiff = Math.abs(m.getRefreshRate() - want);
                }
            }
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (lp.preferredDisplayModeId != best) {
                lp.preferredDisplayModeId = best;
                getWindow().setAttributes(lp);
                android.util.Log.i("thps8", "preferred display mode " + best + " (" + want + " Hz wanted)");
            }
        } catch (Throwable t) {
            android.util.Log.w("thps8", "display rate request failed", t);
        }
    }

    /**
     * Called from native code (the SDK's Android runtime resolves this exact name and signature when
     * it starts, and refuses to start without it). Opens a content:// document and hands back its
     * file descriptor. Not used while the game is read from an extracted folder, but required.
     */
    public static ParcelFileDescriptor openContentFd(String uri, String mode) {
        try {
            Context context = SDLActivity.getContext();
            return context.getContentResolver().openFileDescriptor(Uri.parse(uri), mode);
        } catch (Exception e) {
            android.util.Log.e("thps8", "openContentFd failed for " + uri, e);
            return null;
        }
    }
}
