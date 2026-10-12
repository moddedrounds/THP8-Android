package org.project8recomp.android;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a custom Vulkan driver from a zip the player picks (the usual Turnip / AdrenoTools package:
 * a driver .so plus an optional meta.json with "name" and "libraryName"). Only the .so files and
 * meta.json are taken, by their bare file names, into &lt;internal files&gt;/drivers/custom/; the
 * previous custom driver is replaced only once the new one is complete. The game then loads it like
 * the bundled drivers, after checking that it finds the GPU (native/src/custom_driver.cpp).
 */
final class DriverImport {
    private DriverImport() {}

    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private static final long MAX_TOTAL = 200L << 20;  // a Turnip build is 10-20 MB

    /** Returns the driver's display name. */
    static String importZip(Context context, Uri uri) throws Failure, IOException {
        File drivers = new File(context.getFilesDir(), "drivers");
        File tmp = new File(drivers, "custom.tmp");
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        String metaJson = null;
        String firstSo = null;
        long total = 0;
        int entries = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(raw)) {
            if (raw == null) throw new Failure("Could not open the file.");
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zip.getNextEntry()) != null) {
                entries++;
                if (e.isDirectory()) continue;
                String name = e.getName();
                name = name.substring(name.lastIndexOf('/') + 1);
                name = name.substring(name.lastIndexOf('\\') + 1);
                boolean so = name.endsWith(".so");
                boolean meta = name.equalsIgnoreCase("meta.json");
                if ((!so && !meta) || name.startsWith(".") || name.isEmpty()) continue;
                if (meta) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int n;
                    while ((n = zip.read(buf)) > 0 && bytes.size() < 65536) bytes.write(buf, 0, n);
                    metaJson = bytes.toString("UTF-8");
                    continue;
                }
                File out = new File(tmp, name);
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_TOTAL) throw new Failure("The zip is too large to be a driver package.");
                        os.write(buf, 0, n);
                    }
                }
                if (firstSo == null) firstSo = name;
            }
        } catch (java.util.zip.ZipException z) {
            deleteTree(tmp);
            throw new Failure("This is not a zip file (pick the driver .zip, e.g. a Turnip release).");
        } catch (Failure | IOException f) {
            deleteTree(tmp);
            throw f;
        }
        if (firstSo == null) {
            deleteTree(tmp);
            throw new Failure(entries == 0
                    ? "This is not a zip file (pick the driver .zip, e.g. a Turnip release)."
                    : "No driver library (.so) in this zip.");
        }
        String display = null;
        String lib = firstSo;
        if (metaJson != null) {
            try {
                JSONObject meta = new JSONObject(metaJson);
                display = meta.optString("name", null);
                String wanted = meta.optString("libraryName", null);
                if (wanted != null && new File(tmp, wanted).isFile()) lib = wanted;
                String version = meta.optString("driverVersion", null);
                if (display != null && version != null && !version.isEmpty()) display += " (" + version + ")";
            } catch (Exception ignored) {
                // meta.json is optional
            }
        }
        if (display == null || display.isEmpty()) display = lib;
        File dest = new File(drivers, "custom");
        deleteTree(dest);
        if (!tmp.renameTo(dest)) {
            deleteTree(tmp);
            throw new IOException("cannot move the driver into place");
        }
        //noinspection ResultOfMethodCallIgnored
        new File(drivers, ".failed_custom").delete();
        context.getSharedPreferences("options", Context.MODE_PRIVATE).edit()
                .putString("custom_driver_name", display)
                .putString("custom_driver_lib", lib)
                .apply();
        return display;
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
