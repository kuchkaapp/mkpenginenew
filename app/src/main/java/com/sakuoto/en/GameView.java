package com.sakuoto.en;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Vibrator;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Owns the render thread, the script engine, the scene, the message window, the title menu and choice buttons. */
public final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable, ScriptEngine.Host {

    enum Mode { TITLE, GAME }

    private final Activity act;
    final PackStorage storage;
    final Loc loc;
    final Scene scene;
    final AudioPlayer audio;
    final ScriptEngine engine;
    final SaveManager saves;

    private Thread thread;
    private volatile boolean running;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private volatile Mode mode = Mode.TITLE;

    // viewport (letterboxed 960x540)
    private float scale = 1, offX, offY;

    // message window
    private String speaker = "", fullText = "";
    private int shownChars;
    private float charAcc;
    private boolean messageVisible = true;
    private boolean windowHidden;
    private final List<String> backlog = new ArrayList<>();
    private String lastBlock = "";
    private boolean autoMode;
    private long autoTimer;

    // choices
    private final List<String> choices = new ArrayList<>();
    private final List<RectF> choiceRects = new ArrayList<>();

    // title
    private final List<TitleItem> titleItems = new ArrayList<>();
    private boolean titleExtra;
    private String titleImage = "title", titleBgm = "bgm000";
    private String[] titleSe = {"sys10", "sys11", "sys12", "sys13", "sys14", "sys15", "sys16"};

    static final class TitleItem { String id, image; int group, x, y; boolean enabled = true; Bitmap bmp; Rect hit = new Rect(); }

    // ui rects (virtual coordinates)
    private final RectF btnMenu = new RectF(900, 8, 952, 52), btnSkip = new RectF(838, 8, 890, 52), btnAuto = new RectF(776, 8, 828, 52), btnHide = new RectF(714, 8, 766, 52);

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint np = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Vibrator vib;
    private String startScript = "start", afterScript = "after";
    private String notice = ""; private long noticeT;

    public GameView(Activity a, PackStorage s, Loc l) {
        super(a);
        act = a; storage = s; loc = l;
        scene = new Scene(s);
        audio = new AudioPlayer(s);
        engine = new ScriptEngine(this);
        saves = new SaveManager(a, this);
        vib = (Vibrator) a.getSystemService(Context.VIBRATOR_SERVICE);
        getHolder().addCallback(this);
        setFocusable(true);
        applyAudioPrefs();
        loadGameIni();
        loadTitleConfig();
    }

    public void applyAudioPrefs() {
        audio.bgmVol = loc.bgm / 100f; audio.seVol = loc.se / 100f; audio.voiceVol = loc.voice / 100f;
        audio.setBgmVolume(audio.bgmVol);
    }

    private void loadGameIni() {
        byte[] b = storage.readAsset("script/game.ini");
        if (b == null) return;
        for (String line : new String(b, Charset.forName("UTF-8")).split("\r?\n")) {
            String[] kv = line.split("=", 2);
            if (kv.length < 2) continue;
            String k = kv[0].trim().toLowerCase(Locale.ROOT), v = kv[1].trim();
            if (k.equals("start")) startScript = v;
            else if (k.equals("after")) afterScript = v;
        }
    }

    /** Parses listtitle.txt: [menu] blocks (first = before clear, second = extras unlocked), [image], [bgm], [se]. */
    private void loadTitleConfig() {
        byte[] b = storage.readAsset("script/listtitle.txt");
        if (b == null) return;
        String[] lines = new String(b, Charset.forName("Shift_JIS")).split("\r?\n");
        String sec = ""; int menuNo = 0;
        List<TitleItem> first = new ArrayList<>(), second = new ArrayList<>();
        for (String ln : lines) {
            ln = ln.trim();
            if (ln.isEmpty() || ln.startsWith(";")) continue;
            if (ln.startsWith("[")) { sec = ln; if (sec.equals("[menu]")) menuNo++; continue; }
            int c = ln.indexOf(':'); if (c < 0) continue;
            String k = ln.substring(0, c).trim(), v = ln.substring(c + 1).trim();
            String[] f = v.split("\\s*,\\s*");
            if (sec.equals("[menu]") && f.length >= 4) {
                TitleItem it = new TitleItem();
                it.id = k; it.image = f[0];
                try { it.group = Integer.parseInt(f[1]); it.x = Integer.parseInt(f[2]); it.y = Integer.parseInt(f[3]); } catch (Exception e) { continue; }
                for (int i = 4; i < f.length; i++) if (f[i].equals("disabled")) it.enabled = false;
                (menuNo == 1 ? first : second).add(it);
            } else if (sec.equals("[image]") && k.equals("file")) titleImage = v;
            else if (sec.equals("[bgm]") && k.equals("file")) titleBgm = v;
            else if (sec.equals("[se]") && k.equals("file")) titleSe = f;
        }
        titleItems.clear();
        titleItems.addAll(saves.extrasUnlocked() && !second.isEmpty() ? second : first);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override public void surfaceCreated(SurfaceHolder h) { running = true; thread = new Thread(this, "game"); thread.start(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        scale = Math.min(w / (float) Scene.W, hh / (float) Scene.H);
        offX = (w - Scene.W * scale) / 2f; offY = (hh - Scene.H * scale) / 2f;
    }
    @Override public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        try { if (thread != null) thread.join(1500); } catch (InterruptedException ignored) { }
    }

    public void onPause() { audio.pause(); }
    public void onResume() { audio.resume(); }
    public void destroy() { running = false; audio.release(); }

    public void post(Runnable r) { queue.add(r); }

    // --------------------------------------------------------------- main loop

    @Override public void run() {
        long last = System.nanoTime();
        if (storage.getPackCount() == 0 && storage.readAsset("script/initial.src") == null) { /* nothing to run */ }
        post(this::bootstrap);
        while (running) {
            long now = System.nanoTime();
            long dt = Math.min(100, (now - last) / 1_000_000L);
            last = now;
            Runnable r; while ((r = queue.poll()) != null) r.run();
            update(dt);
            SurfaceHolder h = getHolder();
            Canvas c = h.lockCanvas();
            if (c != null) {
                try { render(c); } finally { h.unlockCanvasAndPost(c); }
            }
            long spent = (System.nanoTime() - now) / 1_000_000L;
            if (spent < 16) try { Thread.sleep(16 - spent); } catch (InterruptedException ignored) { }
        }
    }

    private void bootstrap() {
        // The original runs "initial" (logo -> licence check -> #title). Keep that sequence.
        mode = Mode.GAME;
        engine.reset();
        engine.load("initial", null);
    }

    private void update(long dt) {
        scene.update(dt);
        if (noticeT > 0) noticeT -= dt;
        if (mode == Mode.GAME) {
            engine.skipping = skipHeld;
            if (skipHeld) scene.skipTransitions();
            if (shownChars < fullText.length()) {
                float cps = 8 + loc.textSpeed * 1.2f;
                charAcc += cps * dt / 1000f;
                int add = (int) charAcc; charAcc -= add;
                shownChars = skipHeld ? fullText.length() : Math.min(fullText.length(), shownChars + add);
            }
            boolean textDone = shownChars >= fullText.length();
            if (engine.getWait() == ScriptEngine.Wait.KEY && !textDone) { /* wait for typewriter before accepting key */ }
            if (autoMode && engine.getWait() == ScriptEngine.Wait.KEY && textDone) {
                autoTimer += dt;
                if (autoTimer > 600 + (100 - loc.autoSpeed) * 30L + fullText.length() * 25L) advance();
            } else autoTimer = 0;
            if (textDone || engine.getWait() != ScriptEngine.Wait.KEY) engine.tick(dt);
            else if (skipHeld) engine.tick(dt);
        }
    }

    private volatile boolean skipHeld;

    private void advance() {
        autoTimer = 0;
        if (shownChars < fullText.length()) { shownChars = fullText.length(); return; }
        if (engine.getWait() == ScriptEngine.Wait.KEY) { engine.onKey(); }
    }

    // ---------------------------------------------------------------- rendering

    private void render(Canvas c) {
        c.drawColor(Color.BLACK);
        c.save();
        c.translate(offX, offY);
        c.scale(scale, scale);
        c.clipRect(0, 0, Scene.W, Scene.H);
        scene.draw(c);
        if (mode == Mode.TITLE) drawTitle(c);
        else drawGameUi(c);
        if (noticeT > 0) { drawCentered(c, notice, 480, 40, 26, Color.WHITE, true); }
        c.restore();
    }

    private void drawCentered(Canvas c, String s, float cx, float y, float size, int color, boolean shadow) {
        tp.setTypeface(Loc.hasCjk(s) ? loc.cjkTypeface() : loc.typeface());
        tp.setTextSize(size); tp.setColor(color); tp.setTextAlign(Paint.Align.CENTER);
        if (shadow) { tp.setShadowLayer(4, 0, 0, Color.BLACK); }
        c.drawText(s, cx, y, tp);
        tp.clearShadowLayer(); tp.setTextAlign(Paint.Align.LEFT);
    }

    private void drawTitle(Canvas c) {
        for (TitleItem it : titleItems) {
            if (it.group != (titleExtra ? 1 : 0)) continue;
            if (it.bmp == null) it.bmp = scene.image(it.image);
            int w = it.bmp != null ? it.bmp.getWidth() : 260, h = it.bmp != null ? it.bmp.getHeight() : 56;
            it.hit.set(it.x, it.y, it.x + w, it.y + h);
            p.setAlpha(it.enabled ? 255 : 90);
            if (it.bmp != null) c.drawBitmap(it.bmp, it.x, it.y, p);
            p.setAlpha(255);
            // Localised label on top of the (Japanese) button art
            String label = loc.t(it.id);
            p.setColor(0xB0000000); c.drawRoundRect(it.x, it.y, it.x + w, it.y + h, 10, 10, p);
            drawCentered(c, label, it.x + w / 2f, it.y + h / 2f + 9, 28, it.enabled ? Color.WHITE : 0x88FFFFFF, false);
        }
        drawCentered(c, "v1.0-en", 900, 530, 14, 0x88FFFFFF, false);
    }

    private void drawGameUi(Canvas c) {
        if (messageVisible && !windowHidden && (!fullText.isEmpty() || !speaker.isEmpty())) drawMessage(c);
        if (!choices.isEmpty()) drawChoices(c);
        if (!windowHidden) { drawBtn(c, btnMenu, "≡"); drawBtn(c, btnSkip, "»"); drawBtn(c, btnAuto, autoMode ? "A●" : "A"); drawBtn(c, btnHide, "▢"); }
    }

    private void drawBtn(Canvas c, RectF r, String s) {
        p.setColor(0x66000000); c.drawRoundRect(r, 10, 10, p);
        drawCentered(c, s, r.centerX(), r.centerY() + 9, 26, Color.WHITE, false);
    }

    private void drawMessage(Canvas c) {
        Bitmap win = scene.image("meswin");
        float wx = 66, wy = 350;
        if (win != null) { p.setAlpha(230); c.drawBitmap(win, wx, wy, p); p.setAlpha(255); }
        else { p.setColor(0xCC000000); c.drawRoundRect(wx, wy, wx + 828, wy + 176, 14, 14, p); }

        float size = loc.fontSize;
        String shown = fullText.substring(0, Math.min(shownChars, fullText.length()));
        TextPaint paint = tp;
        paint.setTypeface(Loc.hasCjk(fullText) ? loc.cjkTypeface() : loc.typeface());
        paint.setTextSize(size); paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.LEFT);
        paint.setShadowLayer(3, 1, 1, 0xFF000000);
        float x = wx + 40, y = wy + 38, maxW = 828 - 80, lineH = size * 1.35f;
        int maxLines = 4;
        List<String> wrapped = wrap(shown, paint, maxW);
        for (int i = 0; i < wrapped.size() && i < maxLines + 2; i++) c.drawText(wrapped.get(i), x, y + i * lineH, paint);
        paint.clearShadowLayer();

        if (!speaker.isEmpty()) {
            Bitmap nb = scene.image("meswinname");
            float nx = wx + 20, ny = wy - 34;
            if (nb != null) c.drawBitmap(nb, nx, ny, p);
            np.setTypeface(Loc.hasCjk(speaker) ? loc.cjkTypeface() : loc.typeface());
            np.setTextSize(24); np.setColor(Color.WHITE); np.setShadowLayer(3, 1, 1, Color.BLACK);
            c.drawText(speaker, nx + 24, ny + 33, np);
            np.clearShadowLayer();
        }
        if (shownChars >= fullText.length() && engine.getWait() == ScriptEngine.Wait.KEY) {
            long t = System.currentTimeMillis();
            if ((t / 450) % 2 == 0) { p.setColor(Color.WHITE); c.drawCircle(wx + 828 - 36, wy + 176 - 30, 6, p); }
        }
    }

    /** Greedy word wrap that also breaks CJK text at any character. */
    static List<String> wrap(String text, Paint paint, float maxW) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            int i = 0, n = para.length();
            while (i < n) {
                int j = i;
                if (Loc.hasCjk(String.valueOf(para.charAt(i)))) j = i + 1;
                else { while (j < n && para.charAt(j) != ' ' && !Loc.hasCjk(String.valueOf(para.charAt(j)))) j++; if (j < n && para.charAt(j) == ' ') j++; }
                String word = para.substring(i, j);
                if (paint.measureText(line + word) > maxW && line.length() > 0) { out.add(line.toString().replaceAll(" +$", "")); line.setLength(0); }
                // single word longer than the line: hard break
                while (paint.measureText(word) > maxW && word.length() > 1) {
                    int k = paint.breakText(word, true, maxW, null);
                    if (k <= 0) break;
                    out.add(word.substring(0, k)); word = word.substring(k);
                }
                line.append(word);
                i = j;
            }
            out.add(line.toString());
        }
        return out;
    }

    private void drawChoices(Canvas c) {
        choiceRects.clear();
        int n = choices.size();
        float bw = 640, bh = 64, gap = 18;
        float total = n * bh + (n - 1) * gap;
        float y0 = (Scene.H - total) / 2f - 20;
        Bitmap sel = scene.image("select");
        for (int i = 0; i < n; i++) {
            RectF r = new RectF((Scene.W - bw) / 2f, y0 + i * (bh + gap), (Scene.W + bw) / 2f, y0 + i * (bh + gap) + bh);
            choiceRects.add(r);
            if (sel != null) c.drawBitmap(sel, null, r, p);
            else { p.setColor(0xCC202040); c.drawRoundRect(r, 14, 14, p); }
            String s = choices.get(i);
            float size = 26;
            tp.setTypeface(Loc.hasCjk(s) ? loc.cjkTypeface() : loc.typeface()); tp.setTextSize(size);
            while (tp.measureText(s) > bw - 40 && size > 14) { size -= 1; tp.setTextSize(size); }
            drawCentered(c, s, r.centerX(), r.centerY() + size / 3f, size, Color.WHITE, true);
        }
    }

    // -------------------------------------------------------------------- input

    @Override public boolean onTouchEvent(MotionEvent e) {
        final float vx = (e.getX() - offX) / scale, vy = (e.getY() - offY) / scale;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (mode == Mode.GAME && btnSkip.contains(vx, vy) && choices.isEmpty()) { skipHeld = true; return true; }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            skipHeld = false;
            if (a == MotionEvent.ACTION_UP) { final float fx = vx, fy = vy; post(() -> tap(fx, fy)); }
        }
        return true;
    }

    private void tap(float x, float y) {
        if (mode == Mode.TITLE) { titleTap(x, y); return; }
        if (windowHidden) { windowHidden = false; return; }
        if (!choices.isEmpty()) {
            for (int i = 0; i < choiceRects.size(); i++) if (choiceRects.get(i).contains(x, y)) {
                choices.clear(); engine.onSelect(i); return;
            }
            return;
        }
        if (btnSkip.contains(x, y)) return;
        if (btnMenu.contains(x, y)) { act.runOnUiThread(() -> saves.showInGameMenu()); return; }
        if (btnAuto.contains(x, y)) { autoMode = !autoMode; return; }
        if (btnHide.contains(x, y)) { windowHidden = true; return; }
        advance();
    }

    private void titleTap(float x, float y) {
        for (TitleItem it : titleItems) {
            if (it.group != (titleExtra ? 1 : 0) || !it.enabled || !it.hit.contains((int) x, (int) y)) continue;
            if (titleSe.length > 0) audio.playSe(titleSe[(int) (Math.random() * titleSe.length)], false);
            switch (it.id) {
                case "start": startGame(startScript); break;
                case "start2": startGame(afterScript); break;
                case "continue": act.runOnUiThread(() -> saves.showLoadDialog(false)); break;
                case "config": act.runOnUiThread(() -> saves.showSettings()); break;
                case "extra": titleExtra = true; break;
                case "return": titleExtra = false; break;
                case "bgmmode": act.runOnUiThread(() -> saves.showJukebox()); break;
                case "cgmode": act.runOnUiThread(() -> saves.showCgList()); break;
                case "end": act.runOnUiThread(() -> saves.confirmExit()); break;
                default: break;
            }
            return;
        }
    }

    public void startGame(String script) {
        mode = Mode.GAME;
        engine.reset();
        scene.command(new String[]{"layer", "initialize"});
        clearText(); choices.clear();
        audio.stopBgm();
        engine.load(script, null);
    }

    public boolean onBack() {
        if (mode == Mode.GAME && choices.isEmpty()) { act.runOnUiThread(() -> saves.showInGameMenu()); return true; }
        if (mode == Mode.TITLE && titleExtra) { titleExtra = false; return true; }
        return false;
    }

    void mode(Mode m) { mode = m; }

    public void showNotice(String s) { notice = s; noticeT = 1800; }

    public void returnToTitle() {
        post(() -> { gotoTitle(); });
    }

    // ------------------------------------------------------- state for saving

    String getSpeaker() { return speaker; }
    String getFullText() { return fullText; }
    void restoreText(String sp, String tx) { speaker = sp; fullText = tx; shownChars = tx.length(); }
    List<String> getBacklog() { return backlog; }

    // ============================ ScriptEngine.Host ============================

    @Override public byte[] loadScriptBytes(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".src")) n = n.substring(0, n.length() - 4);
        byte[] b = storage.read(n + ".src");               // translation/ override or pack
        if (b == null) b = storage.readAsset("script/" + n + ".src");   // scripts bundled with the app (initial, bci, bci2)
        if (b == null) b = storage.read(n);
        return b;
    }

    @Override public void layer(String[] a) { scene.command(a); }
    @Override public void wipe(String type, int ms) { scene.wipe(type, ms); }
    @Override public void playBgm(String n, boolean loop) { audio.playBgm(n, loop); }
    @Override public void stopBgm() { audio.stopBgm(); }
    @Override public void playSe(String n, boolean loop) { audio.playSe(n, loop); }
    @Override public void stopSe() { audio.stopSe(); }
    @Override public void playVoice(String n) { audio.playVoice(n); }

    @Override public void showText(String sp, String text, boolean append) {
        scene.commitNow();
        if (sp != null) speaker = sp; else if (!append) speaker = "";
        fullText = append && !fullText.isEmpty() ? fullText + "\n" + text : text;
        shownChars = append ? Math.max(0, fullText.length() - text.length()) : 0;
        charAcc = 0; lastBlock = text;
        backlog.add((speaker.isEmpty() ? "" : speaker + ": ") + text);
        if (backlog.size() > 200) backlog.remove(0);
    }

    @Override public void clearText() { fullText = ""; speaker = ""; shownChars = 0; }
    @Override public void setMessageVisible(boolean v) { messageVisible = v; }

    @Override public void showSelect(List<String> options) {
        scene.commitNow();
        choices.clear(); choices.addAll(options);
    }

    @Override public void gotoTitle() {
        mode = Mode.TITLE; titleExtra = false;
        loadTitleConfig();
        clearText(); choices.clear();
        scene.command(new String[]{"layer", "initialize"});
        scene.command(new String[]{"layer", "set", "bg", titleImage, "0", "0", "1", "1", "0", "255", "0", "sync"});
        audio.playBgm(titleBgm, true);
    }

    @Override public void shake(int ms, boolean h, boolean v) { scene.shake(ms, h, v); }
    @Override public void vibrate(int ms) { if (vib != null && vib.hasVibrator()) vib.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)); }

    @Override public void command(String[] a) {
        switch (a[0]) {
            case "staff": saves.markExtrasUnlocked(); break;
            case "savepoint": case "autosave": break;
            default: break;
        }
    }

    @Override public boolean isBusy() { return scene.isBusy(); }
    @Override public void log(String s) { android.util.Log.w("Script", s); }
}
