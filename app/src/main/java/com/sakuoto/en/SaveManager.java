package com.sakuoto.en;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/** All Android-native dialogs (save/load, settings, music, CG) + persistence. UI methods must run on the UI thread. */
public final class SaveManager {
    static final int SLOTS = 20;

    private final Activity act;
    private final GameView gv;
    private final File dir;
    private final SharedPreferences prefs;

    public SaveManager(Activity a, GameView g) {
        act = a; gv = g;
        dir = new File(a.getFilesDir(), "save");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        prefs = a.getSharedPreferences("progress", Context.MODE_PRIVATE);
    }

    public boolean extrasUnlocked() { return prefs.getBoolean("extras", false); }
    public void markExtrasUnlocked() { prefs.edit().putBoolean("extras", true).apply(); }

    private Loc L() { return gv.loc; }

    // ------------------------------------------------------------- persistence

    private File slotFile(int slot) { return new File(dir, slot == 0 ? "auto.json" : "slot" + slot + ".json"); }

    public boolean save(int slot) {
        try {
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("script", gv.engine.getScriptName());
            o.put("pc", gv.engine.getPc());
            JSONArray st = new JSONArray();
            for (Object[] f : gv.engine.getCallStack()) st.put(new JSONArray().put((String) f[0]).put((Integer) f[1]));
            o.put("stack", st);
            JSONObject vars = new JSONObject();
            for (java.util.Map.Entry<String, Integer> e : gv.engine.getVars().entrySet()) vars.put(e.getKey(), e.getValue());
            o.put("vars", vars);
            o.put("layers", gv.scene.exportLayers());
            o.put("bgm", gv.audio.currentBgm());
            o.put("speaker", gv.getSpeaker());
            o.put("text", gv.getFullText());
            File f = slotFile(slot);
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(o.toString().getBytes("UTF-8")); }
            return true;
        } catch (Exception e) { return false; }
    }

    private JSONObject read(int slot) {
        File f = slotFile(slot);
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { in.read(b); }
            return new JSONObject(new String(b, "UTF-8"));
        } catch (Exception e) { return null; }
    }

    public void load(final int slot) {
        final JSONObject o = read(slot);
        if (o == null) return;
        gv.enqueue(() -> {
            try {
                gv.mode(GameView.Mode.GAME);
                gv.engine.reset();
                JSONObject vars = o.getJSONObject("vars");
                for (Iterator<String> it = vars.keys(); it.hasNext(); ) { String k = it.next(); gv.engine.setVar(k, vars.getInt(k)); }
                List<Object[]> stack = new ArrayList<>();
                JSONArray st = o.getJSONArray("stack");
                for (int i = 0; i < st.length(); i++) stack.add(new Object[]{st.getJSONArray(i).getString(0), st.getJSONArray(i).getInt(1)});
                gv.engine.restore(o.getString("script"), o.getInt("pc"));
                gv.engine.setCallStack(stack);
                gv.scene.importLayers(o.getJSONArray("layers"));
                String bgm = o.optString("bgm", "");
                if (bgm.isEmpty()) gv.audio.stopBgm(); else gv.audio.playBgm(bgm, true);
                gv.restoreText(o.optString("speaker"), o.optString("text"));
            } catch (JSONException ignored) { }
        });
    }

    // ----------------------------------------------------------------- dialogs

    private AlertDialog.Builder builder() { return new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert); }

    private void immersive(Dialog d) {
        Window w = d.getWindow();
        if (w != null) w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        d.show();
        if (w != null) {
            w.getDecorView().setSystemUiVisibility(act.getWindow().getDecorView().getSystemUiVisibility());
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public void showInGameMenu() {
        final String[] items = {L().t("save"), L().t("load"), L().t("log"), L().t("config"), L().t("title"), L().t("close")};
        immersive(builder().setTitle(L().t("menu")).setItems(items, (d, which) -> {
            switch (which) {
                case 0: showSaveDialog(); break;
                case 1: showLoadDialog(true); break;
                case 2: showBacklog(); break;
                case 3: showSettings(); break;
                case 4: gv.returnToTitle(); break;
                default: break;
            }
        }).create());
    }

    private String[] slotLabels(boolean withAuto) {
        List<String> out = new ArrayList<>();
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int i = withAuto ? 0 : 1; i <= SLOTS; i++) {
            JSONObject o = read(i);
            String name = i == 0 ? "Auto" : L().t("slot") + " " + i;
            out.add(name + "   " + (o == null ? L().t("empty") : df.format(new Date(o.optLong("time"))) + "  " + o.optString("script")));
        }
        return out.toArray(new String[0]);
    }

    public void showSaveDialog() {
        final String[] labels = slotLabels(false);
        immersive(builder().setTitle(L().t("save")).setItems(labels, (d, w) -> {
            if (save(w + 1)) gv.showNotice(L().t("saved"));
        }).create());
    }

    public void showLoadDialog(final boolean inGame) {
        final String[] labels = slotLabels(true);
        immersive(builder().setTitle(L().t("load")).setItems(labels, (d, w) -> load(w)).create());
    }

    public void showBacklog() {
        StringBuilder sb = new StringBuilder();
        List<String> bl = gv.getBacklog();
        for (int i = Math.max(0, bl.size() - 60); i < bl.size(); i++) sb.append(bl.get(i)).append("\n\n");
        TextView tv = new TextView(act);
        tv.setText(sb.toString()); tv.setTextSize(16); tv.setPadding(32, 24, 32, 24);
        tv.setTypeface(L().typeface());
        ScrollView sv = new ScrollView(act); sv.addView(tv);
        immersive(builder().setTitle(L().t("log")).setView(sv).setPositiveButton(L().t("close"), null).create());
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    public void confirmExit() {
        immersive(builder().setMessage(L().t("confirm_exit"))
                .setPositiveButton(L().t("yes"), (d, w) -> act.finish())
                .setNegativeButton(L().t("no"), null).create());
    }

    // --------------------------------------------------------------- settings

    public void showSettings() {
        final Loc l = L();
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 16, 40, 16);

        root.addView(label(l.t("language")));
        Spinner lang = new Spinner(act);
        lang.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, Loc.NAMES));
        lang.setSelection(l.lang);
        root.addView(lang);

        root.addView(label(l.t("font")));
        Spinner font = new Spinner(act);
        font.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, new String[]{l.t("font_roboto"), l.t("font_mplus")}));
        font.setSelection(l.fontChoice);
        root.addView(font);

        lang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }
                l.lang = pos; font.setSelection(pos == 2 ? 1 : 0);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        root.addView(label(l.t("fontsize")));
        final SeekBar fs = seek(root, l.fontSize - 16, 20);
        root.addView(label(l.t("textspeed")));
        final SeekBar ts = seek(root, l.textSpeed, 100);
        root.addView(label(l.t("autospeed")));
        final SeekBar as = seek(root, l.autoSpeed, 100);
        root.addView(label(l.t("bgmvol")));
        final SeekBar bg = seek(root, l.bgm, 100);
        root.addView(label(l.t("sevol")));
        final SeekBar se = seek(root, l.se, 100);
        root.addView(label(l.t("voicevol")));
        final SeekBar vo = seek(root, l.voice, 100);

        ScrollView sv = new ScrollView(act); sv.addView(root);
        immersive(builder().setTitle(l.t("config")).setView(sv)
                .setPositiveButton(l.t("close"), (d, w) -> {
                    l.fontChoice = font.getSelectedItemPosition();
                    l.fontSize = fs.getProgress() + 16; l.textSpeed = ts.getProgress(); l.autoSpeed = as.getProgress();
                    l.bgm = bg.getProgress(); l.se = se.getProgress(); l.voice = vo.getProgress();
                    l.save(); gv.applyAudioPrefs();
                }).create());
    }

    private TextView label(String s) { TextView t = new TextView(act); t.setText(s); t.setTextSize(14); t.setPadding(0, 18, 0, 4); return t; }

    private SeekBar seek(LinearLayout root, int value, int max) {
        SeekBar s = new SeekBar(act); s.setMax(max); s.setProgress(Math.max(0, Math.min(max, value)));
        root.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    // ----------------------------------------------------------- music / CG

    private List<String> textLines(String asset) {
        byte[] b = gv.storage.read(asset);
        if (b == null) b = gv.storage.readAsset("script/" + asset);
        if (b == null) return new ArrayList<>();
        String s;
        try {
            s = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) { s = new String(b, Charset.forName("Shift_JIS")); }
        List<String> out = new ArrayList<>();
        for (String ln : s.split("\r?\n")) out.add(ln.replace("\uFEFF", ""));
        return out;
    }

    public void showJukebox() {
        final List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        for (String ln : textLines("listbgm.txt")) {
            if (!ln.startsWith("data:")) continue;
            String[] f = ln.substring(5).trim().split(",", 2);
            ids.add(f[0].trim()); names.add(f.length > 1 ? f[1].trim() : f[0].trim());
        }
        immersive(builder().setTitle(L().t("bgmmode")).setItems(names.toArray(new String[0]), (d, w) -> gv.enqueue(() -> gv.audio.playBgm(ids.get(w), true)))
                .setNegativeButton(L().t("close"), null).create());
    }

    public void showCgList() {
        final List<String[]> sets = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        int n = 1;
        for (String ln : textLines("listcg.txt")) {
            ln = ln.trim(); if (ln.isEmpty()) continue;
            String[] f = ln.split(",");
            if (f.length < 2) continue;
            String[] imgs = java.util.Arrays.copyOfRange(f, 1, f.length);
            sets.add(imgs); names.add("CG " + (n++));
        }
        immersive(builder().setTitle(L().t("cgmode")).setItems(names.toArray(new String[0]), (d, w) -> showCg(sets.get(w)))
                .setNegativeButton(L().t("close"), null).create());
    }

    private void showCg(final String[] imgs) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int[] idx = {0};
        final Runnable show = () -> {
            Bitmap b = gv.scene.image(imgs[idx[0]]);
            iv.setImageBitmap(b);
        };
        show.run();
        iv.setOnClickListener(v -> { idx[0]++; if (idx[0] >= imgs.length) d.dismiss(); else show.run(); });
        d.setContentView(iv);
        d.show();
    }
}
