package org.project8recomp.android;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Launcher background from the player's own game: the Project 8 disc has no dashboard art package, so
 * on early launches the game's own title screen is captured from the screen (PixelCopy of the game
 * surface) and saved as the launcher background. Nothing from the game ships in the APK.
 *
 * Frames are taken from 25 s after start, every 4 s up to 2 minutes; the first one that is neither
 * dark nor flat (loading screens, fades) is kept.
 */
final class TitleCapture {
    private TitleCapture() {}

    static File file(android.content.Context context) {
        return new File(context.getFilesDir(), "art/launcher_bg.jpg");
    }

    static void start(android.content.Context context, SurfaceView view) {
        File out = file(context);
        if (out.isFile() || view == null) return;
        Handler handler = new Handler(Looper.getMainLooper());
        final long started = System.currentTimeMillis();
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (out.isFile() || System.currentTimeMillis() - started > 120_000) return;
            int w = view.getWidth(), h = view.getHeight();
            if (w <= 0 || h <= 0 || !view.getHolder().getSurface().isValid()) {
                handler.postDelayed(tick[0], 4000);
                return;
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(view, bmp, result -> {
                    if (result == PixelCopy.SUCCESS && interesting(bmp)) {
                        // The on-screen fps counter sits in the top right: leave that strip out.
                        Bitmap pic = LaunchConfig.showFps(context)
                                ? Bitmap.createBitmap(bmp, 0, bmp.getHeight() / 10, bmp.getWidth(), bmp.getHeight() * 9 / 10)
                                : bmp;
                        save(pic, out);
                    } else {
                        handler.postDelayed(tick[0], 4000);
                    }
                }, handler);
            } catch (Throwable t) {
                android.util.Log.w("thps8", "title capture", t);
            }
        };
        handler.postDelayed(tick[0], 25_000);
    }

    /** Bright and varied enough to be a real picture (not a fade, a loading screen or the black boot). */
    private static boolean interesting(Bitmap b) {
        int n = 0;
        double sum = 0, sq = 0;
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 32; x++) {
                int c = b.getPixel(x * (b.getWidth() - 1) / 31, y * (b.getHeight() - 1) / 17);
                double l = 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
                sum += l;
                sq += l * l;
                n++;
            }
        }
        double mean = sum / n, std = Math.sqrt(Math.max(0, sq / n - mean * mean));
        return mean > 40 && std > 25;
    }

    private static void save(Bitmap b, File out) {
        try {
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
            int w = Math.min(1280, b.getWidth());
            Bitmap scaled = Bitmap.createScaledBitmap(b, w, b.getHeight() * w / b.getWidth(), true);
            File tmp = new File(out.getPath() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, os);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(out);
            android.util.Log.i("thps8", "launcher background captured from the title screen");
        } catch (Exception e) {
            android.util.Log.w("thps8", "title capture save", e);
        }
    }
}
