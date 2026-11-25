package com.sakuoto.en;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the game's data packs straight out of the APK (assets/data/*.mkh + *.mkp).
 *
 * Pack index (.mkh), big-endian:
 *   int  id = 0x6d6b7000 ("mkp\0")
 *   byte[4] version
 *   int  headerSize, int fileSize, int count, int, int, int   (reserved)
 *   count x entry (32 bytes):
 *       int  offset ^ 0xA7A7A7A7
 *       int  size   ^ 0xA7A7A7A7
 *       byte nameLen ^ 0xA7
 *       byte[23] name ^ 0xA7
 * File bytes inside the .mkp are XOR 0xA7.
 *
 * Lookup order for any file name:
 *   1. assets/translation/<name>   (plain files, so translators can override anything)
 *   2. assets/<dir>/<name>         (loose files bundled with the app, e.g. image/logo.jpg)
 *   3. mounted packs
 */
public final class PackStorage {
    private static final int PACK_ID = 0x6d6b7000;
    private static final int KEY = 0xA7A7A7A7;

    private static final class Entry {
        PackFile pack; long offset; int size;
    }

    private static final class PackFile {
        String assetName; AssetFileDescriptor afd; FileChannel ch; long base;
    }

    private final Map<String, File> looseFiles = new HashMap<>();      // any file under the media folder, by lowercase name
    private final Map<String, File> translationFiles = new HashMap<>(); // files under media/translation/
    private File mediaDir;          // e.g. /storage/emulated/0/Android/media/com.sakuoto.en  (resolved at runtime)
    public String getMediaPath() { return mediaDir == null ? "" : mediaDir.getAbsolutePath(); }

    private final AssetManager assets;
    private final Map<String, Entry> index = new HashMap<>();
    private int packCount;

    public PackStorage(Context ctx) {
        assets = ctx.getAssets();
        File[] dirs = ctx.getExternalMediaDirs();      // app-private media folder, no permission needed on Android 10+
        if (dirs != null) for (File d : dirs) if (d != null) { mediaDir = d; break; }
        if (mediaDir != null) //noinspection ResultOfMethodCallIgnored
            mediaDir.mkdirs();
        indexLoose(mediaDir, false);
        mountMedia();
        mountAll();
    }

    public int getPackCount() { return packCount; }
    public int getFileCount() { return index.size(); }

    private void indexLoose(File dir, boolean inTranslation) {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { indexLoose(f, inTranslation || f.getName().equalsIgnoreCase("translation")); continue; }
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".mkh") || n.endsWith(".mkp")) continue;
            (inTranslation ? translationFiles : looseFiles).put(n, f);
        }
    }

    /** Packs the user copied into Android/media/<package>/ (preferred over packs embedded in the APK). */
    private void mountMedia() {
        if (mediaDir == null) return;
        File[] fs = mediaDir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (!n.toLowerCase(Locale.ROOT).endsWith(".mkh")) continue;
            File mkp = new File(mediaDir, n.substring(0, n.length() - 4) + ".mkp");
            if (!mkp.exists()) {                      // tolerate different letter case
                File[] all = mediaDir.listFiles();
                if (all != null) for (File c : all) if (c.getName().equalsIgnoreCase(mkp.getName())) { mkp = c; break; }
            }
            if (!mkp.exists()) continue;
            try {
                PackFile pf = new PackFile();
                pf.ch = new RandomAccessFile(mkp, "r").getChannel();
                pf.base = 0;
                try (InputStream in = new java.io.FileInputStream(f)) { readIndex(in, pf, mkp.length()); }
                packCount++;
            } catch (IOException e) { android.util.Log.e("Pack", "media mount " + n, e); }
        }
    }

    private void mountAll() {
        try {
            String[] list = assets.list("data");
            if (list == null) return;
            for (String f : list) {
                if (f.toLowerCase(Locale.ROOT).endsWith(".mkh")) {
                    String base = f.substring(0, f.length() - 4);
                    try { mount(base); } catch (IOException e) { android.util.Log.e("Pack", "mount " + base, e); }
                }
            }
        } catch (IOException ignored) { }
    }

    private void mount(String base) throws IOException {
        PackFile pf = new PackFile();
        pf.assetName = "data/" + base + ".mkp";
        pf.afd = assets.openFd(pf.assetName);   // requires uncompressed asset (see noCompress in build.gradle)
        pf.base = pf.afd.getStartOffset();
        pf.ch = new FileInputStream(pf.afd.getFileDescriptor()).getChannel();
        long packLen = pf.afd.getLength();

        try (InputStream hin = assets.open("data/" + base + ".mkh")) { readIndex(hin, pf, packLen); }
        packCount++;
    }

    private void readIndex(InputStream hin, PackFile pf, long packLen) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(hin))) {
            int id = in.readInt();
            if (id != PACK_ID) throw new IOException("bad pack id");
            in.skipBytes(4);
            int headerSize = in.readInt();
            in.readInt();
            int count = in.readInt();
            in.skipBytes(12);
            byte[] nm = new byte[23];
            for (int i = 0; i < count; i++) {
                int off = in.readInt() ^ KEY;
                int size = in.readInt() ^ KEY;
                int len = (in.read() ^ 0xA7) & 0xFF;
                in.readFully(nm);
                for (int k = 0; k < nm.length; k++) nm[k] ^= (byte) 0xA7;
                if (len <= 0 || len > 23 || nm[0] == 0) continue;
                Entry e = new Entry();
                e.pack = pf; e.offset = off; e.size = size;
                if (e.offset + e.size > packLen && e.offset + headerSize + e.size <= packLen) e.offset += headerSize;
                index.putIfAbsent(new String(nm, 0, len, "UTF-8").toLowerCase(Locale.ROOT), e);
            }
        }
    }

    /** Loose file from the media folder (translation/ first, then anywhere under the folder), or null. */
    private byte[] readMedia(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        int sl = key.lastIndexOf('/');
        if (sl >= 0) key = key.substring(sl + 1);
        File f = translationFiles.get(key);
        if (f == null) f = looseFiles.get(key);
        if (f == null) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return b;
        } catch (IOException e) { return null; }
    }

    /** Raw (already de-XORed) bytes of a file, or null. */
    public byte[] read(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        byte[] b = readMedia(key);
        if (b != null) return b;
        b = readAsset("translation/" + key);
        if (b != null) return b;
        Entry e = index.get(key);
        if (e == null) return null;
        try {
            ByteBuffer buf = ByteBuffer.allocate(e.size);
            long pos = e.pack.base + e.offset;
            while (buf.hasRemaining()) {
                int n = e.pack.ch.read(buf, pos + buf.position());
                if (n < 0) break;
            }
            byte[] out = buf.array();
            for (int i = 0; i < out.length; i++) out[i] ^= (byte) 0xA7;
            return out;
        } catch (IOException ex) { return null; }
    }

    public boolean exists(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return index.containsKey(key) || looseFiles.containsKey(key) || translationFiles.containsKey(key) || assetExists("translation/" + key);
    }

    /** Try several candidate names/extensions in order. */
    public byte[] readAny(String dir, String base, String... exts) {
        for (String ext : exts) {
            byte[] b = readMedia(base + ext);                       // loose file in Android/media/<pkg>/
            if (b != null) return b;
            b = readAsset(dir + "/" + base + ext);                  // loose asset bundled in the APK
            if (b != null) return b;
            b = read(base + ext);
            if (b != null) return b;
        }
        return null;
    }

    public byte[] readAsset(String path) {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] tmp = new byte[16384]; int n;
            while ((n = in.read(tmp)) > 0) bos.write(tmp, 0, n);
            return bos.toByteArray();
        } catch (IOException e) { return null; }
    }

    private boolean assetExists(String path) {
        try (InputStream in = assets.open(path)) { return true; } catch (IOException e) { return false; }
    }

    /**
     * Some packs store plain files and others XOR 0xA7 (the original decides per pack/file), and some buffers use 0x19.
     * read() always applies 0xA7, so here we detect the real container by its magic bytes and undo/redo as needed.
     */
    public static byte[] fixMedia(byte[] b) {
        if (b == null || b.length < 4) return b;
        if (isMedia(b)) return b;
        for (int key : new int[]{0xA7, 0x19}) {
            byte[] h = new byte[4];
            for (int i = 0; i < 4; i++) h[i] = (byte) (b[i] ^ key);
            if (isMedia(h)) {
                byte[] c = b.clone();
                for (int i = 0; i < c.length; i++) c[i] ^= (byte) key;
                return c;
            }
        }
        return b;
    }

    private static boolean isMedia(byte[] b) {
        int a = b[0] & 0xFF, c = b[1] & 0xFF, d = b[2] & 0xFF;
        return (a == 0x89 && c == 0x50 && d == 0x4E)                // PNG
                || (a == 0xFF && c == 0xD8)                          // JPEG
                || (a == 'O' && c == 'g' && d == 'g')                // Ogg
                || (a == 'R' && c == 'I' && d == 'F')                // RIFF/WAV
                || (a == 'I' && c == 'D' && d == '3')                // MP3 ID3
                || (a == 0xFF && (c & 0xE0) == 0xE0);               // raw MP3 frame
    }
}
