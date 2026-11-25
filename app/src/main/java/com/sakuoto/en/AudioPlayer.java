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
    private MediaPlayer bgm, se, voice;
    private String bgmName = "";
    public float bgmVol = 0.8f, seVol = 0.8f, voiceVol = 1f;
    public boolean voiceEnabled = true;

    public AudioPlayer(PackStorage s) { storage = s; }

    private byte[] find(String base) {
        base = base.toLowerCase();
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        byte[] b = storage.readAny("data", base, ".resogg", ".snd", ".ogg", "");
        return PackStorage.fixMedia(b);
    }

    private MediaPlayer make(byte[] data, boolean loop, float vol) {
        if (data == null) return null;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(new MemSource(data));
            mp.setLooping(loop);
            mp.setVolume(vol, vol);
            mp.prepare();
            mp.start();
            return mp;
        } catch (IOException | IllegalStateException e) { return null; }
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
