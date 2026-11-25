package com.sakuoto.en;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** UI strings + font selection. Game text itself comes from the (translated) scripts. */
public final class Loc {
    public static final String[] CODES = {"en", "ru", "ja"};
    public static final String[] NAMES = {"English", "Русский", "日本語"};

    private static final Map<String, String[]> T = new HashMap<>();
    private static void add(String key, String en, String ru, String ja) { T.put(key, new String[]{en, ru, ja}); }

    static {
        add("start", "New Game", "Новая игра", "はじめから");
        add("continue", "Continue", "Продолжить", "つづきから");
        add("config", "Settings", "Настройки", "設定");
        add("extra", "Extras", "Дополнительно", "おまけ");
        add("end", "Exit", "Выход", "終了");
        add("cgmode", "CG Gallery", "Галерея CG", "CGモード");
        add("bgmmode", "Music", "Музыка", "音楽モード");
        add("start2", "After Story", "Эпилог", "アフター");
        add("return", "Back", "Назад", "戻る");
        add("save", "Save", "Сохранить", "セーブ");
        add("load", "Load", "Загрузить", "ロード");
        add("log", "Backlog", "История", "ログ");
        add("title", "Title", "В меню", "タイトル");
        add("menu", "Menu", "Меню", "メニュー");
        add("auto", "Auto", "Авто", "オート");
        add("skip", "Skip", "Пропуск", "スキップ");
        add("slot", "Slot", "Слот", "スロット");
        add("empty", "— empty —", "— пусто —", "— 空き —");
        add("close", "Close", "Закрыть", "閉じる");
        add("language", "Language / Язык", "Язык / Language", "言語 / Language");
        add("textspeed", "Text speed", "Скорость текста", "文字速度");
        add("autospeed", "Auto speed", "Скорость авто", "オート速度");
        add("bgmvol", "Music volume", "Громкость музыки", "BGM音量");
        add("sevol", "Effects volume", "Громкость эффектов", "SE音量");
        add("voicevol", "Voice volume", "Громкость голоса", "ボイス音量");
        add("fontsize", "Text size", "Размер текста", "文字サイズ");
        add("font", "Font", "Шрифт", "フォント");
        add("font_roboto", "Roboto (Latin / Cyrillic)", "Roboto (латиница / кириллица)", "Roboto");
        add("font_mplus", "Rounded M+ (original)", "Rounded M+ (оригинал)", "Rounded M+（オリジナル）");
        add("confirm_exit", "Quit the game?", "Выйти из игры?", "ゲームを終了しますか？");
        add("yes", "Yes", "Да", "はい");
        add("no", "No", "Нет", "いいえ");
        add("nodata", "Game data not found.\nCopy the .mkh/.mkp files into this folder, then restart the app:",
                "Данные игры не найдены.\nСкопируйте файлы .mkh/.mkp в эту папку и перезапустите приложение:",
                "ゲームデータが見つかりません。\n.mkh/.mkp ファイルを次のフォルダに置いて再起動してください：");
        add("saved", "Saved", "Сохранено", "セーブしました");
        add("tap", "Tap to continue", "Нажмите для продолжения", "タップで続行");
    }

    private final SharedPreferences prefs;
    private final Context ctx;
    private Typeface roboto, mplus;

    public int lang;           // index into CODES
    public int fontChoice;     // 0 = Roboto, 1 = Rounded M+
    public int textSpeed = 50; // 0..100
    public int autoSpeed = 50;
    public int fontSize = 26;
    public int bgm = 80, se = 80, voice = 100;

    public Loc(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String sys = Locale.getDefault().getLanguage();
        int def = sys.equals("ru") ? 1 : sys.equals("ja") ? 2 : 0;
        lang = prefs.getInt("lang", def);
        fontChoice = prefs.getInt("font", lang == 2 ? 1 : 0);
        textSpeed = prefs.getInt("textSpeed", 50);
        autoSpeed = prefs.getInt("autoSpeed", 50);
        fontSize = prefs.getInt("fontSize", 26);
        bgm = prefs.getInt("bgm", 80); se = prefs.getInt("se", 80); voice = prefs.getInt("voice", 100);
        try { roboto = Typeface.createFromAsset(c.getAssets(), "fonts/Roboto.ttf"); } catch (Exception e) { roboto = Typeface.SANS_SERIF; }
        try { mplus = Typeface.createFromAsset(c.getAssets(), "fonts/RoundedMplus.ttf"); } catch (Exception e) { mplus = Typeface.SANS_SERIF; }
    }

    public void save() {
        prefs.edit().putInt("lang", lang).putInt("font", fontChoice).putInt("textSpeed", textSpeed).putInt("autoSpeed", autoSpeed)
                .putInt("fontSize", fontSize).putInt("bgm", bgm).putInt("se", se).putInt("voice", voice).apply();
    }

    public String t(String key) {
        String[] v = T.get(key);
        return v == null ? key : v[Math.min(lang, 2)];
    }

    /**
     * Typeface for message text. Roboto covers Latin + Cyrillic (proportional, good for English/Russian);
     * Rounded M+ (the original font) covers kana/kanji + Latin + Cyrillic (monospaced-ish).
     * Whichever is chosen, the other is *not* silently used for missing glyphs by Paint on its own,
     * so Japanese text with Roboto would show tofu: the engine switches to M+ automatically for CJK lines.
     */
    public Typeface typeface() { return fontChoice == 1 ? mplus : roboto; }
    public Typeface cjkTypeface() { return mplus; }

    public static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0xFF00 && c <= 0xFFEF) || (c >= 0x3000 && c <= 0x303F)) return true;
        }
        return false;
    }
}
