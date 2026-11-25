package com.sakuoto.en;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Layered 960x540 canvas. Layer commands change a "staged" set; a wipe commits it
 * to the visible set with a transition.
 *
 *   #layer set <layer> <image> x y scaleX scaleY rotate opacity time [sync|nosync] [front|back]
 *   #layer delete <layer|all>
 *   #layer wipe <type> <ms>
 *   #layer initialize
 *   #layer move/opacity/scale/rotate <layer> ...   (applied instantly; time argument ignored)
 */
public final class Scene {
    public static final int W = 960, H = 540;

    static final class Layer {
        String name, image; Bitmap bmp;
        float x, y, sx = 1, sy = 1, rot; int alpha = 255; int z;
        Layer copy() {
            Layer l = new Layer();
            l.name = name; l.image = image; l.bmp = bmp; l.x = x; l.y = y; l.sx = sx; l.sy = sy; l.rot = rot; l.alpha = alpha; l.z = z;
            return l;
        }
    }

    private final PackStorage storage;
    private final List<Layer> staged = new ArrayList<>();
    private List<Layer> shown = new ArrayList<>();

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(96 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    // transition
    private Bitmap oldFrame, newFrame;
    private String wipeType = "fade";
    private long wipeLen, wipeT;
    private boolean wiping;
    private int[] rule; private int ruleW, ruleH; private Bitmap maskBmp; private int[] maskPx;

    // quake
    private long shakeT; private int shakeAmp; private boolean shakeH, shakeV;

    public Scene(PackStorage s) { storage = s; }

    public boolean isBusy() { return wiping; }
    public void skipTransitions() { wiping = false; shakeT = 0; releaseFrames(); }

    // ------------------------------------------------------------- commands

    public void command(String[] a) {
        if (a.length < 2) return;
        switch (a[1]) {
            case "initialize": staged.clear(); shown = new ArrayList<>(); tweens.clear(); return;
            case "delete": delete(a.length > 2 ? a[2] : "all"); return;
            case "set": set(a); return;
            case "sync": syncLayer(a.length > 2 ? a[2] : "all"); return;
            case "wipe": wipe(a.length > 2 ? a[2] : "fade", a.length > 3 ? num(a[3], 300) : 300); return;
            case "move": tween(a, 0); return;
            case "opacity": case "opacity2": tween(a, 1); return;
            case "scale": tween(a, 2); return;
            case "rotate": tween(a, 3); return;
            case "quake": quake(a.length > 3 ? num(a[3], 8) : 8, a.length > 4 ? num(a[4], 8) : 8, a.length > 6 ? num(a[6], 500) : 500); return;
            case "stopaction": shakeT = 0; return;
            default: return;     // camera, moveaction, opacityaction, back, fade: not implemented
        }
    }

    // --- animated property changes: #layer move <n> x y ms | opacity <n> a ms | scale <n> sx sy ms | rotate <n> deg ms
    private static final class Tween { String name; int kind; float f1, f2, t1, t2; long t, dur; boolean init; }
    private final List<Tween> tweens = new ArrayList<>();

    private void tween(String[] a, int kind) {
        if (a.length < 4) return;
        Tween w = new Tween(); w.name = a[2]; w.kind = kind;
        int msIdx;
        switch (kind) {
            case 0: w.t1 = fnum(a[3], 0); w.t2 = a.length > 4 ? fnum(a[4], 0) : 0; msIdx = 5; break;
            case 1: w.t1 = fnum(a[3], 255); msIdx = 4; break;
            case 2: w.t1 = fnum(a[3], 1); w.t2 = a.length > 4 ? fnum(a[4], w.t1) : w.t1; msIdx = 5; break;
            default: w.t1 = fnum(a[3], 0); msIdx = 4;
        }
        w.dur = a.length > msIdx ? num(a[msIdx], 0) : 0;
        for (int i = tweens.size() - 1; i >= 0; i--) if (tweens.get(i).name.equals(w.name) && tweens.get(i).kind == kind) tweens.remove(i);
        tweens.add(w);
        if (w.dur <= 0) { stepTween(w, 1f); tweens.remove(w); }
    }

    private void stepTween(Tween w, float p) {
        Layer s = find(staged, w.name), v = find(shown, w.name);
        if (s == null && v == null) return;
        Layer ref = s != null ? s : v;
        if (!w.init) {
            switch (w.kind) {
                case 0: w.f1 = ref.x; w.f2 = ref.y; break;
                case 1: w.f1 = ref.alpha; break;
                case 2: w.f1 = ref.sx; w.f2 = ref.sy; break;
                default: w.f1 = ref.rot;
            }
            w.init = true;
        }
        for (Layer l : new Layer[]{s, v}) {
            if (l == null) continue;
            switch (w.kind) {
                case 0: l.x = w.f1 + (w.t1 - w.f1) * p; l.y = w.f2 + (w.t2 - w.f2) * p; break;
                case 1: l.alpha = Math.max(0, Math.min(255, (int) (w.f1 + (w.t1 - w.f1) * p))); break;
                case 2: l.sx = w.f1 + (w.t1 - w.f1) * p; l.sy = w.f2 + (w.t2 - w.f2) * p; break;
                default: l.rot = w.f1 + (w.t1 - w.f1) * p;
            }
        }
    }

    /** Make one staged layer (or all) visible immediately, without a wipe. */
    private void syncLayer(String name) {
        if (name.equals("all")) { commitNow(); return; }
        Layer s = find(staged, name);
        for (int i = shown.size() - 1; i >= 0; i--) if (shown.get(i).name.equals(name)) shown.remove(i);
        if (s != null) shown.add(s.copy());
        Collections.sort(shown, new Comparator<Layer>() { public int compare(Layer p, Layer q) { return p.z - q.z; } });
    }

    private void quake(int ax, int ay, int dur) {
        shakeAmp = Math.max(2, Math.max(ax, ay) / 2); shakeH = ax > 0; shakeV = ay > 0; shakeT = Math.max(100, dur);
    }

    private void delete(String name) {
        if (name.equals("all")) { staged.clear(); return; }
        for (int i = staged.size() - 1; i >= 0; i--) if (staged.get(i).name.equals(name)) staged.remove(i);
    }

    private void set(String[] a) {
        if (a.length < 4) return;
        Layer l = find(staged, a[2]);
        if (l == null) { l = new Layer(); l.name = a[2]; l.z = defaultZ(a[2]); staged.add(l); }
        l.image = a[3];
        l.bmp = loadImage(a[3], a[2]);
        l.x = a.length > 4 ? num(a[4], 0) : 0;
        l.y = a.length > 5 ? num(a[5], 0) : 0;
        l.sx = a.length > 6 ? fnum(a[6], 1f) : 1f;
        l.sy = a.length > 7 ? fnum(a[7], 1f) : 1f;
        l.rot = a.length > 8 ? num(a[8], 0) : 0;
        l.alpha = a.length > 9 ? Math.max(0, Math.min(255, num(a[9], 255))) : 255;
        boolean sync = false;
        for (int i = 10; i < a.length; i++) {
            if (a[i].equals("sync")) sync = true;
            else if (a[i].equals("front")) l.z = 1000;
            else if (a[i].equals("back")) l.z = -1;
        }
        if (sync) commitNow();
    }

    private static int defaultZ(String n) {
        switch (n) {
            case "bg": return 0; case "ev": return 1; case "map": case "map0": case "map1": return 2;
            case "chr": case "char": return 10; case "face": return 20; case "mask": return 900; case "front": return 1000;
            default: return 5;
        }
    }

    private static Layer find(List<Layer> l, String n) { for (Layer x : l) if (x.name.equals(n)) return x; return null; }
    private static int num(String s, int d) { try { return (int) Float.parseFloat(s); } catch (Exception e) { return d; } }
    private static float fnum(String s, float d) { try { return Float.parseFloat(s); } catch (Exception e) { return d; } }

    /** Commit staged layers instantly (no transition). */
    public void commitNow() {
        shown = snapshot(staged);
    }

    public void wipe(String type, int ms) {
        if (wiping) skipTransitions();
        if (ms <= 0 || type == null) { commitNow(); return; }
        releaseFrames();
        oldFrame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        drawLayers(new Canvas(oldFrame), shown, 1f);
        shown = snapshot(staged);
        newFrame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        drawLayers(new Canvas(newFrame), shown, 1f);
        wipeType = type.toLowerCase(Locale.ROOT); wipeLen = ms; wipeT = 0; wiping = true;
        rule = null;
        if (!wipeType.equals("fade")) loadRule(wipeType);
    }

    private List<Layer> snapshot(List<Layer> src) {
        List<Layer> out = new ArrayList<>();
        for (Layer l : src) out.add(l.copy());
        Collections.sort(out, new Comparator<Layer>() { public int compare(Layer p, Layer q) { return p.z - q.z; } });
        return out;
    }

    public void shake(int ms, boolean h, boolean v) { shakeT = ms; shakeAmp = 12; shakeH = h; shakeV = v; }

    // ------------------------------------------------------------- update/draw

    public void update(long dt) {
        for (int i = tweens.size() - 1; i >= 0; i--) {
            Tween w = tweens.get(i);
            w.t += dt;
            float p = Math.min(1f, (float) w.t / Math.max(1, w.dur));
            stepTween(w, p);
            if (p >= 1f) tweens.remove(i);
        }
        if (wiping) { wipeT += dt; if (wipeT >= wipeLen) { wiping = false; releaseFrames(); } }
        if (shakeT > 0) shakeT -= dt;
    }

    private void releaseFrames() {
        if (oldFrame != null) { oldFrame.recycle(); oldFrame = null; }
        if (newFrame != null) { newFrame.recycle(); newFrame = null; }
    }

    public void draw(Canvas c) {
        c.drawColor(Color.BLACK);
        c.save();
        if (shakeT > 0) {
            float dx = shakeH ? (float) Math.sin(shakeT * 0.09) * shakeAmp : 0;
            float dy = shakeV ? (float) Math.cos(shakeT * 0.11) * shakeAmp : 0;
            c.translate(dx, dy);
        }
        if (wiping && oldFrame != null && newFrame != null) {
            float p = Math.min(1f, (float) wipeT / wipeLen);
            c.drawBitmap(oldFrame, 0, 0, null);
            drawTransition(c, p);
        } else {
            drawLayers(c, shown, 1f);
        }
        c.restore();
    }

    private void drawTransition(Canvas c, float p) {
        switch (wipeType) {
            case "ltor": case "rule_left": case "rule_left2": case "rule_left3": case "hout":
                clipDraw(c, new Rect(0, 0, (int) (W * p), H)); return;
            case "rtol": case "rule_right": case "rule_right2": case "rule_right3":
                clipDraw(c, new Rect((int) (W * (1 - p)), 0, W, H)); return;
            case "ttob": case "rule_top": case "vout":
                clipDraw(c, new Rect(0, 0, W, (int) (H * p))); return;
            case "btot": case "rule_bottom":
                clipDraw(c, new Rect(0, (int) (H * (1 - p)), W, H)); return;
            case "boxin": case "rule_center": case "hshutter": case "vshutter": {
                int hw = (int) (W / 2 * p), hh = (int) (H / 2 * p);
                clipDraw(c, new Rect(W / 2 - hw, H / 2 - hh, W / 2 + hw, H / 2 + hh)); return;
            }
            default:
                if (rule != null) { drawRule(c, p); return; }
                paint.setAlpha((int) (255 * p)); c.drawBitmap(newFrame, 0, 0, paint); paint.setAlpha(255);
        }
    }

    private void clipDraw(Canvas c, Rect r) { c.save(); c.clipRect(r); c.drawBitmap(newFrame, 0, 0, null); c.restore(); }

    // Luminance-mask ("rule") transition
    private void loadRule(String name) {
        Bitmap b = loadImageImpl(name, "rule", false);
        if (b == null) return;
        ruleW = b.getWidth(); ruleH = b.getHeight();
        rule = new int[ruleW * ruleH];
        b.getPixels(rule, 0, ruleW, 0, 0, ruleW, ruleH);
        for (int i = 0; i < rule.length; i++) rule[i] = rule[i] & 0xFF;      // blue channel as luminance (rules are grayscale)
        maskBmp = Bitmap.createBitmap(ruleW, ruleH, Bitmap.Config.ARGB_8888);
        maskPx = new int[ruleW * ruleH];
    }

    private void drawRule(Canvas c, float p) {
        float edge = 32f, t = p * (255f + edge);
        for (int i = 0; i < rule.length; i++) {
            float a = (t - rule[i]) / edge;
            int v = a <= 0 ? 0 : a >= 1 ? 255 : (int) (a * 255);
            maskPx[i] = v << 24;
        }
        maskBmp.setPixels(maskPx, 0, ruleW, 0, 0, ruleW, ruleH);
        int sc = c.saveLayer(0, 0, W, H, null);
        c.drawBitmap(newFrame, 0, 0, null);
        Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG);
        mp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawBitmap(maskBmp, null, new Rect(0, 0, W, H), mp);
        c.restoreToCount(sc);
    }

    private void drawLayers(Canvas c, List<Layer> layers, float globalAlpha) {
        for (Layer l : layers) {
            if (l.bmp == null) continue;
            m.reset();
            m.postScale(l.sx, l.sy);
            if (l.rot != 0) m.postRotate(l.rot, l.bmp.getWidth() * l.sx / 2f, l.bmp.getHeight() * l.sy / 2f);
            m.postTranslate(l.x, l.y);
            paint.setAlpha((int) (l.alpha * globalAlpha));
            c.drawBitmap(l.bmp, m, paint);
        }
        paint.setAlpha(255);
    }

    // ------------------------------------------------------------- images

    public Bitmap loadImage(String name, String layer) { return loadImageImpl(name, layer, true); }

    private Bitmap loadImageImpl(String name, String layer, boolean fallback) {
        if (name == null || name.isEmpty()) return null;
        String base = name;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.toLowerCase(Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot > 0 && (base.endsWith(".img") || base.endsWith(".png") || base.endsWith(".jpg"))) base = base.substring(0, dot);
        Bitmap b = cache.get(base);
        if (b != null) return b;
        byte[] data = storage.readAny("image", base, ".png", ".jpg", ".img", "");
        if (data == null && !fallback) return null;
        if (data == null) {
            data = storage.readAsset("image/noimage.png");
            if (data == null) return null;
        }
        data = PackStorage.fixMedia(data);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b != null) cache.put(base, b);
        return b;
    }

    public org.json.JSONArray exportLayers() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (Layer l : shown) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("n", l.name).put("i", l.image).put("x", l.x).put("y", l.y).put("sx", l.sx).put("sy", l.sy)
                 .put("r", l.rot).put("a", l.alpha).put("z", l.z);
                arr.put(o);
            }
        } catch (org.json.JSONException ignored) { }
        return arr;
    }

    public void importLayers(org.json.JSONArray arr) {
        staged.clear();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Layer l = new Layer();
            l.name = o.optString("n"); l.image = o.optString("i"); l.bmp = loadImage(l.image, l.name);
            l.x = (float) o.optDouble("x", 0); l.y = (float) o.optDouble("y", 0);
            l.sx = (float) o.optDouble("sx", 1); l.sy = (float) o.optDouble("sy", 1);
            l.rot = (float) o.optDouble("r", 0); l.alpha = o.optInt("a", 255); l.z = o.optInt("z", 0);
            staged.add(l);
        }
        commitNow();
    }

    public Bitmap image(String name) { return loadImage(name, null); }
}
