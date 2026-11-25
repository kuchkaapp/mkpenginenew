package com.sakuoto.en;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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

    private final AssetManager assets;
    private final Map<String, Entry> index = new HashMap<>();
    private int packCount;

    public PackStorage(Context ctx) {
        assets = ctx.getAssets();
        mountAll();
    }

    public int getPackCount() { return packCount; }
    public int getFileCount() { return index.size(); }

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

        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(assets.open("data/" + base + ".mkh")))) {
            int id = in.readInt();
            if (id != PACK_ID) throw new IOException("bad pack id in " + base);
            in.skipBytes(4);                     // version string
            int headerSize = in.readInt();
            in.readInt();                        // fileSize
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
                // Some builds store offsets relative to the end of the header.
                if (e.offset + e.size > packLen && e.offset + headerSize + e.size <= packLen) e.offset += headerSize;
                index.put(new String(nm, 0, len, "UTF-8").toLowerCase(Locale.ROOT), e);
            }
        }
        packCount++;
    }

    /** Raw (already de-XORed) bytes of a file, or null. */
    public byte[] read(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        byte[] b = readAsset("translation/" + key);
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
        return index.containsKey(key) || assetExists("translation/" + key);
    }

    /** Try several candidate names/extensions in order. */
    public byte[] readAny(String dir, String base, String... exts) {
        for (String ext : exts) {
            byte[] b = readAsset(dir + "/" + base + ext);          // loose asset (overrides pack)
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

    /** XOR 0x19 is used by the original for some cached buffers; auto-detect media magic. */
    public static byte[] fixMedia(byte[] b) {
        if (b == null || b.length < 4) return b;
        if (isMedia(b)) return b;
        byte[] c = b.clone();
        for (int i = 0; i < Math.min(c.length, 16); i++) c[i] ^= 0x19;
        if (isMedia(c)) { for (int i = 16; i < c.length; i++) c[i] ^= 0x19; return c; }
        return b;
    }

    private static boolean isMedia(byte[] b) {
        int a = b[0] & 0xFF, c = b[1] & 0xFF;
        return (a == 0x89 && c == 0x50) || (a == 0xFF && c == 0xD8) || (a == 'O' && c == 'g') || (a == 'R' && c == 'I') || (a == 'I' && c == 'D');
    }
}
