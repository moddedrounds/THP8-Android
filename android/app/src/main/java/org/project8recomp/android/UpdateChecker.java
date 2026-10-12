package org.project8recomp.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * In-app updates for release builds. The repository hosts a small update.json (version code and
 * name, APK URL, SHA-256, notes); the launcher compares it with the installed version, downloads the
 * APK, checks its SHA-256 and hands it to Android's package installer, which asks the user to confirm
 * and only accepts an APK signed with the same key. The installed game and saves are untouched.
 *
 * Builds without an update URL (app.updateUrl Gradle property) never check.
 */
final class UpdateChecker {
    static final class Info {
        final long versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final long size;
        final String notes;

        Info(JSONObject j) throws Exception {
            versionCode = j.getLong("versionCode");
            versionName = j.getString("versionName");
            apkUrl = j.getString("apk");
            sha256 = j.getString("sha256").toLowerCase(Locale.US);
            size = j.optLong("size", 0);
            notes = j.optString("notes", "");
        }
    }

    interface Progress {
        void progress(long done, long total);
    }

    private static final long AUTO_INTERVAL_MS = 6L * 3600 * 1000;
    private static final String PREFS = "updates";

    static String manifestUrl(Context c) {
        int id = c.getResources().getIdentifier("update_manifest_url", "string", c.getPackageName());
        return id == 0 ? "" : c.getString(id);
    }

    static boolean enabled(Context c) {
        return manifestUrl(c).startsWith("https://");
    }

    /** True when an automatic check is due (at most every 6 hours). */
    static boolean autoCheckDue(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return System.currentTimeMillis() - p.getLong("last_check", 0) > AUTO_INTERVAL_MS;
    }

    static long installedVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Worker thread. Returns the newer release, or null when this build is current. */
    static Info check(Context c) throws Exception {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_check", System.currentTimeMillis()).apply();
        HttpURLConnection conn = open(manifestUrl(c) + "?t=" + System.currentTimeMillis() / 60000);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && bo.size() < (1 << 20)) bo.write(b, 0, n);
            Info info = new Info(new JSONObject(bo.toString("UTF-8")));
            if (!info.apkUrl.startsWith("https://")) throw new IOException("update URL is not https");
            return info.versionCode > installedVersionCode(c) ? info : null;
        } finally {
            conn.disconnect();
        }
    }

    /** Worker thread. Downloads and verifies the APK; returns the file. */
    static File download(Context c, Info info, Progress progress) throws Exception {
        File dir = new File(c.getCacheDir(), "update");
        IsoInstaller.deleteTree(dir);
        dir.mkdirs();
        File apk = new File(dir, "update.apk");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        HttpURLConnection conn = open(info.apkUrl);
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            long total = conn.getContentLengthLong() > 0 ? conn.getContentLengthLong() : info.size;
            long done = 0;
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                sha.update(b, 0, n);
                done += n;
                progress.progress(done, total);
            }
        } finally {
            conn.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte x : sha.digest()) hex.append(String.format(Locale.US, "%02x", x));
        if (!hex.toString().equals(info.sha256)) {
            IsoInstaller.deleteTree(dir);
            throw new IOException("the download is corrupt (checksum mismatch) - try again");
        }
        return apk;
    }

    /** Hands the verified APK to the system installer; Android then asks the user to confirm. */
    static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("update.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(pending.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "android-port-updater");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        return conn;
    }
}
