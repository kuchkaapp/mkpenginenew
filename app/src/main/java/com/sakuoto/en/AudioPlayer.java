package com.sakuoto.en;

import android.media.MediaDataSource;
import android.media.MediaPlayer;

import java.io.IOException;

/** BGM / SE / voice playback. Audio bytes come from the packs (Ogg Vorbis) and are streamed from memory. */
public final class AudioPlayer {

    private static final class MemSource extends MediaDataSource {
        private final byte[] d;
        MemSource(byte[] d) { this.d = d; }
        @Override public int readAt(long pos, byte[] buf, int off, int size) {
            if (pos >= d.length) return -1;
            int n = (int) Math.min(size, d.length - pos);
            System.arraycopy(d, (int) pos, buf, off, n);
            return n;
        }
        @Override public long getSize() { return d.length; }
        @Override public void close() { }
    }

    private final PackStorage storage;
    private final java.io.File cacheDir;
    private MediaPlayer bgm, se, voice;
    private String bgmName = "";
    public float bgmVol = 0.8f, seVol = 0.8f, voiceVol = 1f;
    public boolean voiceEnabled = true;

    public AudioPlayer(PackStorage s, java.io.File cache) { storage = s; cacheDir = cache; }

    private byte[] find(String base) {
        base = base.toLowerCase();
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        byte[] b = storage.readAny("data", base, ".resogg", ".snd", ".ogg", "");
        return PackStorage.fixMedia(b);
    }

    private MediaPlayer make(byte[] data, boolean loop, float vol) {
        if (data == null) { android.util.Log.w("Audio", "sound file not found"); return null; }
        MediaPlayer mp = null;
        try {                                   // 1) stream from memory
            mp = new MediaPlayer();
            mp.setDataSource(new MemSource(data));
            mp.setLooping(loop); mp.setVolume(vol, vol);
            mp.prepare(); mp.start();
            return mp;
        } catch (Exception e) {
            android.util.Log.w("Audio", "memory source failed, trying temp file", e);
            if (mp != null) mp.release();
        }
        try {                                   // 2) fallback: temp file
            java.io.File f = java.io.File.createTempFile("snd", ".ogg", cacheDir);
            f.deleteOnExit();
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(data); }
            mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setLooping(loop); mp.setVolume(vol, vol);
            mp.prepare(); mp.start();
            final java.io.File ff = f;
            mp.setOnCompletionListener(m -> ff.delete());
            return mp;
        } catch (Exception e) {
            android.util.Log.e("Audio", "cannot play sound", e);
            if (mp != null) mp.release();
            return null;
        }
    }

    private static void kill(MediaPlayer mp) {
        if (mp == null) return;
        try { mp.stop(); } catch (Exception ignored) { }
        mp.release();
    }

    public synchronized void playBgm(String name, boolean loop) {
        if (name.equals(bgmName) && bgm != null) return;
        kill(bgm);
        bgmName = name;
        bgm = make(find(name), loop, bgmVol);
    }

    public synchronized String currentBgm() { return bgm == null ? "" : bgmName; }

    public synchronized void stopBgm() { kill(bgm); bgm = null; bgmName = ""; }

    public synchronized void playSe(String name, boolean loop) {
        if (!loop) { MediaPlayer old = se; se = make(find(name), false, seVol); if (old != null && old.isPlaying()) { /* let it finish */ old.setOnCompletionListener(MediaPlayer::release); } else kill(old); return; }
        kill(se); se = make(find(name), true, seVol);
    }

    public synchronized void stopSe() { kill(se); se = null; }

    public synchronized void playVoice(String name) {
        if (!voiceEnabled) return;
        kill(voice);
        voice = make(find(name), false, voiceVol);
    }

    public synchronized void stopVoice() { kill(voice); voice = null; }

    public synchronized void setBgmVolume(float v) { bgmVol = v; if (bgm != null) bgm.setVolume(v, v); }

    public synchronized void pause() { try { if (bgm != null) bgm.pause(); } catch (Exception ignored) { } }
    public synchronized void resume() { try { if (bgm != null) bgm.start(); } catch (Exception ignored) { } }

    public synchronized void release() { kill(bgm); kill(se); kill(voice); bgm = se = voice = null; }
}
