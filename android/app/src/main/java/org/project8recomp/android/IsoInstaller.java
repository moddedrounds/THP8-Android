package org.project8recomp.android;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Installs the game from the user's own disc image: reads the XDVDFS file system straight out of the
 * picked document (plain XISO or a full XGD2 / XGD3 / XGD1 image), extracts every file into the app's
 * game folder and checks the result before it replaces anything: the executable must be the supported
 * release (SHA-256 of default.xex) and no file may be cut short or zero-filled (an incomplete copy of
 * the image). Nothing is downloaded; the image never leaves the device.
 */
final class IsoInstaller {
    interface Listener {
        void progress(long done, long total, String file);
    }

    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private static final int SECTOR = 2048;
    private static final long[] PARTITIONS = {0L, 0x2080000L, 0xFD90000L, 0x18300000L};
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    // Console update payload on the disc; the game never reads it.
    private static final String SKIP_DIR = "$SystemUpdate";

    private static final class Entry {
        final String path;
        final long sector;
        final long size;

        Entry(String path, long sector, long size) {
            this.path = path;
            this.sector = sector;
            this.size = size;
        }
    }

    /** Runs on a worker thread. Returns a short summary; throws Failure with a user-facing message. */
    static String install(Context context, Uri uri, File dest, String[] acceptedSha256, Listener listener)
            throws Failure {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                throw new Failure("Could not open the selected file.");
            }
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 FileChannel ch = in.getChannel()) {
                return extract(ch, dest, acceptedSha256, listener);
            }
        } catch (IOException e) {
            throw new Failure("Reading the disc image failed: " + e.getMessage());
        }
    }

    private static String extract(FileChannel ch, File dest, String[] accepted, Listener listener)
            throws IOException, Failure {
        long imageSize = ch.size();
        long base = -1, rootSector = 0, rootSize = 0;
        for (long off : PARTITIONS) {
            ByteBuffer b = read(ch, off + 32L * SECTOR, 28);
            if (b == null) continue;
            byte[] m = new byte[MAGIC.length];
            b.get(m);
            if (java.util.Arrays.equals(m, MAGIC)) {
                base = off;
                rootSector = b.getInt() & 0xFFFFFFFFL;
                rootSize = b.getInt() & 0xFFFFFFFFL;
                break;
            }
        }
        if (base < 0) {
            throw new Failure("This is not an Xbox 360 disc image (no XDVDFS file system found).");
        }

        List<Entry> files = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        walk(ch, base, rootSector, rootSize, "", files, dirs, 0);
        long total = 0;
        boolean hasXex = false;
        for (Entry e : files) {
            total += e.size;
            if (base + e.sector * SECTOR + e.size > imageSize) {
                throw new Failure("The disc image is incomplete: " + e.path
                        + " runs past the end of the file. Copy or dump the disc again.");
            }
            if (e.path.equals("default.xex")) hasXex = true;
        }
        if (!hasXex) {
            throw new Failure("No default.xex on this disc image - is it the right game?");
        }

        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        long free = parent != null ? parent.getUsableSpace() : 0;
        if (free < total + (256L << 20)) {
            throw new Failure(String.format(Locale.US,
                    "Not enough free space: the game needs %.1f GB, %.1f GB are free.",
                    total / 1e9, free / 1e9));
        }

        File tmp = new File(dest.getPath() + ".installing");
        deleteTree(tmp);
        tmp.mkdirs();
        for (String d : dirs) {
            new File(tmp, d).mkdirs();
        }

        String xexSha = null;
        long done = 0;
        ByteBuffer buf = ByteBuffer.allocateDirect(4 << 20);
        byte[] heap = new byte[4 << 20];
        try {
            for (Entry e : files) {
                File out = new File(tmp, e.path);
                MessageDigest sha = e.path.equals("default.xex") ? MessageDigest.getInstance("SHA-256") : null;
                boolean headZero = true;
                boolean headChecked = false;
                byte[] tail = new byte[0];
                try (FileOutputStream os = new FileOutputStream(out)) {
                    long pos = base + e.sector * SECTOR;
                    long left = e.size;
                    while (left > 0) {
                        buf.clear();
                        buf.limit((int) Math.min(buf.capacity(), left));
                        int n = ch.read(buf, pos);
                        if (n <= 0) {
                            throw new Failure("The disc image ended early while reading " + e.path + ".");
                        }
                        buf.flip();
                        buf.get(heap, 0, n);
                        os.write(heap, 0, n);
                        if (sha != null) sha.update(heap, 0, n);
                        if (!headChecked) {
                            headZero = allZero(heap, 0, Math.min(n, SECTOR));
                            headChecked = true;
                        }
                        if (left - n < SECTOR) {
                            int keep = (int) Math.min(n, SECTOR);
                            tail = java.util.Arrays.copyOfRange(heap, n - keep, n);
                        }
                        pos += n;
                        left -= n;
                        done += n;
                        listener.progress(done, total, e.path);
                    }
                }
                // A copy that stopped part-way leaves zeros where data should be. Real files of this
                // size never start with a zero sector, and big ones never end in one.
                if (e.size >= 65536 && (headZero || (e.size >= (16L << 20) && allZero(tail, 0, tail.length)))) {
                    throw new Failure("The disc image is incomplete: " + e.path
                            + " is empty (all zeros). Copy or dump the disc again.");
                }
                if (sha != null) xexSha = hex(sha.digest());
            }
        } catch (Failure | IOException f) {
            deleteTree(tmp);
            throw f;
        } catch (java.security.NoSuchAlgorithmException nsa) {
            deleteTree(tmp);
            throw new IOException(nsa);
        }

        boolean ok = false;
        for (String a : accepted) {
            if (a.equalsIgnoreCase(xexSha)) ok = true;
        }
        if (!ok) {
            deleteTree(tmp);
            throw new Failure("This disc is not the supported release of the game.\n"
                    + "default.xex SHA-256: " + xexSha);
        }

        // Swap by renaming, so a failure never leaves the device without a working install (an older
        // folder copied in with adb can hold files the app is not allowed to delete one by one).
        File old = new File(dest.getPath() + ".old");
        deleteTree(old);
        if (dest.exists() && !dest.renameTo(old)) {
            deleteTree(tmp);
            throw new Failure("Could not replace the existing game folder " + dest + ".");
        }
        if (!tmp.renameTo(dest)) {
            if (old.exists()) old.renameTo(dest);
            deleteTree(tmp);
            throw new Failure("Could not move the extracted game into place.");
        }
        deleteTree(old);
        return String.format(Locale.US, "Installed %d files (%.2f GB). default.xex verified.",
                files.size(), total / 1e9);
    }

    private static void walk(FileChannel ch, long base, long sector, long size, String path,
                             List<Entry> files, List<String> dirs, int depth) throws IOException, Failure {
        if (size == 0) return;
        if (depth > 32 || size > (64L << 20)) {
            throw new Failure("The disc's directory table is damaged.");
        }
        ByteBuffer table = read(ch, base + sector * SECTOR, (int) size);
        if (table == null) {
            throw new Failure("The disc image is incomplete (a directory is missing).");
        }
        node(ch, base, table, 0, path, files, dirs, depth, new HashSet<>());
    }

    private static void node(FileChannel ch, long base, ByteBuffer t, int o, String path, List<Entry> files,
                             List<String> dirs, int depth, Set<Integer> seen) throws IOException, Failure {
        if (seen.contains(o) || o + 14 > t.limit()) return;
        seen.add(o);
        int left = t.getShort(o) & 0xFFFF;
        int right = t.getShort(o + 2) & 0xFFFF;
        long start = t.getInt(o + 4) & 0xFFFFFFFFL;
        long length = t.getInt(o + 8) & 0xFFFFFFFFL;
        int attr = t.get(o + 12) & 0xFF;
        int nlen = t.get(o + 13) & 0xFF;
        if (left == 0xFFFF && right == 0xFFFF && start == 0xFFFFFFFFL) return;
        if (o + 14 + nlen > t.limit()) return;
        byte[] nb = new byte[nlen];
        for (int i = 0; i < nlen; i++) nb[i] = t.get(o + 14 + i);
        String name = new String(nb, StandardCharsets.ISO_8859_1);
        if (name.contains("/") || name.contains("\\") || name.equals("..") || name.equals(".")) {
            throw new Failure("The disc's directory table is damaged.");
        }
        if (left != 0) node(ch, base, t, left * 4, path, files, dirs, depth, seen);
        String rel = path.isEmpty() ? name : path + "/" + name;
        if ((attr & 0x10) != 0) {
            if (!(path.isEmpty() && name.equalsIgnoreCase(SKIP_DIR))) {
                dirs.add(rel);
                walk(ch, base, start, length, rel, files, dirs, depth + 1);
            }
        } else {
            files.add(new Entry(rel, start, length));
        }
        if (right != 0) node(ch, base, t, right * 4, path, files, dirs, depth, seen);
    }

    private static ByteBuffer read(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int n = ch.read(b, pos + b.position());
            if (n <= 0) return null;
        }
        b.flip();
        return b;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
