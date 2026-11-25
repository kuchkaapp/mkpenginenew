package com.sakuoto.en;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Interpreter for the game's line-oriented scripts.
 *
 * Syntax (as observed in the original engine):
 *   ; or .        comment line
 *   #cmd a b c    command (space separated, case-insensitive, ';' starts a trailing comment)
 *   【Name】       first line of a block = speaker name
 *   any text      message text; a block of text lines is shown together
 *   *             wait for click, then clear the window
 *   *+            wait for click, keep text on screen (next block is appended)
 *   +             continue immediately, appending to the current text
 *   #label x / #goto x / #call file [label] / #return / #return2 / #next file
 *   #if expr / #else / #endif    (nestable)
 *   #set var expr
 *   #select l1 l2 .. + one option text line per label
 */
public final class ScriptEngine {

    /** Everything the interpreter needs from the outside world. */
    public interface Host {
        byte[] loadScriptBytes(String name);
        void layer(String[] a);
        void wipe(String type, int ms);
        void playBgm(String name, boolean loop);
        void stopBgm();
        void playSe(String name, boolean loop);
        void stopSe();
        void playVoice(String name);
        void stopVoice();
        void showText(String speaker, String text, boolean append);
        void clearText();
        void setMessageVisible(boolean v);
        void showSelect(List<String> options);
        void gotoTitle();
        void shake(int ms, boolean h, boolean v);
        void vibrate(int ms);
        void command(String[] a);          // anything not handled here
        boolean isBusy();                  // transition / animation still running
        void log(String s);
    }

    public enum Wait { NONE, TIME, KEY, SELECT, BUSY, END }

    private final Host host;
    private final Map<String, Integer> vars = new HashMap<>();
    /** Global flags (s<N>) that survive new games, e.g. unlocked routes. */
    public final Map<String, Integer> persistent = new HashMap<>();
    private final Random rnd = new Random();

    private List<String> lines = new ArrayList<>();
    private String scriptName = "";
    private int pc;
    private final Deque<Object[]> callStack = new ArrayDeque<>();

    private Wait wait = Wait.NONE;
    private long waitMs;
    private List<String> selectTargets;
    private boolean lastBlockAppendable;

    public boolean skipping;               // fast-forward mode (set by UI)

    public ScriptEngine(Host host) {
        this.host = host;
        reset();
    }

    public void reset() {
        vars.clear();
        vars.putAll(persistent);
        // Original game gates content behind a purchase / download check.
        // All data is bundled locally now, so those flags are fixed.
        vars.put("b", 1);
        vars.put("d", 2);
        vars.put("s2", 1);
        callStack.clear();
        wait = Wait.NONE;
    }

    // ------------------------------------------------------------------ state

    public Wait getWait() { return wait; }
    public String getScriptName() { return scriptName; }
    public int getPc() { return pc; }
    public Map<String, Integer> getVars() { return vars; }
    public List<Object[]> getCallStack() { List<Object[]> l = new ArrayList<>(callStack); java.util.Collections.reverse(l); return l; }
    public void setCallStack(List<Object[]> frames) { callStack.clear(); for (Object[] f : frames) callStack.push(f); }

    public void setVar(String k, int v) {
        k = k.toLowerCase(Locale.ROOT);
        vars.put(k, v);
        if (k.matches("s\\d+")) persistent.put(k, v);
    }

    /** Result of the #map screen: stores the chosen index in f0 and resumes the script. */
    public void onMapResult(int idx) {
        if (wait != Wait.SELECT) return;
        vars.put("f0", idx);
        wait = Wait.NONE;
    }
    public int getVar(String k) { Integer v = vars.get(k.toLowerCase(Locale.ROOT)); return v == null ? 0 : v; }

    public boolean load(String name, String label) {
        byte[] raw = host.loadScriptBytes(name);
        if (raw == null) { host.log("script not found: " + name); return false; }
        lines = decode(raw);
        scriptName = name;
        pc = 0;
        if (label != null && !label.isEmpty()) gotoLabel(label);
        return true;
    }

    public void restore(String name, int counter) {
        if (load(name, null)) pc = Math.min(counter, lines.size());
        wait = Wait.NONE;
    }

    /**
     * Scripts are plain text by the time they get here (pack contents are de-XORed by PackStorage and the
     * bundled initial/bci scripts are stored decoded). Encoding: UTF-8 (translations) or Shift-JIS (originals).
     */
    static List<String> decode(byte[] b) {
        String text;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            text = new String(b, 3, b.length - 3, Charset.forName("UTF-8"));
        } else if (looksUtf8(b)) {
            text = new String(b, Charset.forName("UTF-8"));
        } else {
            text = new String(b, Charset.forName("Shift_JIS"));
        }
        String[] parts = text.split("\r\n|\n|\r", -1);
        return new ArrayList<>(Arrays.asList(parts));
    }

    private static boolean looksUtf8(byte[] b) {
        int i = 0, multi = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            int n = c < 0x80 ? 0 : (c >> 5) == 6 ? 1 : (c >> 4) == 14 ? 2 : (c >> 3) == 30 ? 3 : -1;
            if (n < 0) return false;
            for (int k = 1; k <= n; k++) {
                if (i + k >= b.length || (b[i + k] & 0xC0) != 0x80) return false;
            }
            if (n > 0) multi++;
            i += n + 1;
        }
        return multi > 0 || isAscii(b);
    }

    private static boolean isAscii(byte[] b) { for (byte x : b) if (x < 0) return false; return true; }

    // ---------------------------------------------------------------- control

    public void onKey() {
        if (wait == Wait.KEY) wait = Wait.NONE;
        else if (wait == Wait.TIME && skipping) wait = Wait.NONE;
    }

    public void onSelect(int index) {
        if (wait != Wait.SELECT) return;
        if (selectTargets != null && index >= 0 && index < selectTargets.size()) {
            String t = selectTargets.get(index);
            gotoLabel(t);
        }
        selectTargets = null;
        host.clearText();
        wait = Wait.NONE;
    }

    public void tick(long dtMs) {
        int guard = 0;
        while (guard++ < 5000) {
            switch (wait) {
                case TIME:
                    waitMs -= dtMs; dtMs = 0;
                    if (waitMs > 0 && !skipping) return;
                    wait = Wait.NONE; break;
                case BUSY:
                    if (host.isBusy() && !skipping) return;
                    wait = Wait.NONE; break;
                case KEY:
                    if (skipping) { wait = Wait.NONE; break; }
                    return;
                case SELECT: case END: return;
                default: break;
            }
            if (!step()) return;
        }
    }

    /** Execute one logical statement. Returns false when nothing more to run right now. */
    private boolean step() {
        while (pc < lines.size()) {
            String line = lines.get(pc);
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i >= line.length()) { pc++; continue; }
            char c = line.charAt(i);
            if (c == ';' || c == '.') { pc++; continue; }
            if (c == '#') { pc++; return runCommand(line.substring(i + 1)); }
            return runText();
        }
        // end of file
        if (!callStack.isEmpty()) { doReturn(); return true; }
        wait = Wait.END;
        return false;
    }

    // ------------------------------------------------------------------- text

    private boolean runText() {
        List<String> block = new ArrayList<>();
        String speaker = null;
        char end = 0; boolean keep = false, cont = false;
        while (pc < lines.size()) {
            String line = lines.get(pc);
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i >= line.length()) { pc++; if (!block.isEmpty()) break; else continue; }
            char c = line.charAt(i);
            if (c == ';' || c == '.') { pc++; continue; }
            if (c == '#') break;
            if (c == '*') {
                pc++;
                keep = i + 1 < line.length() && line.charAt(i + 1) == '+';
                end = '*';
                break;
            }
            if (c == '+') { pc++; cont = true; break; }
            String body = line.substring(i);
            if (block.isEmpty() && speaker == null && body.length() > 2 && body.charAt(0) == '\u3010') {
                int close = body.indexOf('\u3011');
                if (close > 0) { speaker = body.substring(1, close); pc++; continue; }
            }
            block.add(body);
            pc++;
            if (block.size() >= 8) break;
        }
        if (block.isEmpty() && speaker == null) return true;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < block.size(); k++) { if (k > 0) sb.append('\n'); sb.append(block.get(k)); }
        host.showText(speaker, sb.toString(), lastBlockAppendable);
        lastBlockAppendable = keep || cont;
        if (cont) return true;
        wait = Wait.KEY;
        if (end == '*' && !keep) { /* window is cleared by the host when the click arrives */ }
        return false;
    }

    // --------------------------------------------------------------- commands

    private static String[] split(String s) {
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi);
        s = s.trim();
        if (s.isEmpty()) return new String[0];
        return s.split("[ \t]+");
    }

    private boolean runCommand(String raw) {
        String[] a = split(raw);
        if (a.length == 0) return true;
        for (int i = 0; i < a.length; i++) a[i] = a[i].toLowerCase(Locale.ROOT);
        String cmd = a[0];
        switch (cmd) {
            case "label": return true;
            case "goto": gotoLabel(a[1]); return true;
            case "call": case "call2": doCall(a); return true;
            case "next": case "next2": load(a[1], a.length > 2 ? a[2] : null); callStack.clear(); return true;
            case "return": case "return2": doReturn(); return true;
            case "if": doIf(raw.substring(raw.toLowerCase(Locale.ROOT).indexOf("if") + 2).trim()); return true;
            case "else": pc = skipTo(pc, true); return true;       // reached only from a true branch
            case "endif": return true;
            case "set": doSet(a); return true;
            case "wait": case "wait2": case "delay":
                waitMs = a.length > 1 ? parse(a[1]) : 0; wait = Wait.TIME; return false;
            case "keywait": case "keywaitex": wait = Wait.KEY; return false;
            case "layer": host.layer(a);
                if (a.length > 1 && a[1].equals("wipe")) { wait = Wait.BUSY; return false; }
                return true;
            case "wipe": host.wipe(a.length > 1 ? a[1] : "fade", a.length > 2 ? parse(a[2]) : 300);
                wait = Wait.BUSY; return false;
            case "bgm": doBgm(a); return true;
            case "se": case "se2": doSe(a); return true;
            case "voice": if (a.length < 2 || a[1].equals("stop") || a[1].equals("off")) host.stopVoice(); else host.playVoice(a[1]); return true;
            case "mes": host.setMessageVisible(a.length < 2 || !a[1].equals("off")); if (a.length > 1 && a[1].equals("off")) host.clearText(); return true;
            case "select": doSelect(a); return false;
            case "map":
                host.command(a);
                if (a.length > 1 && a[1].equals("start")) { wait = Wait.SELECT; selectTargets = null; return false; }
                return true;
            case "title": host.gotoTitle(); wait = Wait.END; return false;
            case "shake": case "shakeh": case "shakev": case "quake":
                host.shake(a.length > 1 ? parse(a[1]) : 500, !cmd.equals("shakev"), !cmd.equals("shakeh")); return true;
            case "vibration": case "vibrator": host.vibrate(a.length > 1 ? parse(a[1]) : 100); return true;
            // Purchase / download machinery of the original: everything is local now.
            case "buycheck": setVar("b", 1); return true;
            case "clearboughtflag": case "storedialog": return true;
            case "downloadfiles": setVar("d", 2); return true;
            case "autosave": case "offskip": case "menu": case "savepoint": case "savetitle": case "fontsize":
            case "window": case "window2": case "mesmode": case "setselect": case "setmemory": case "endmemory":
            default:
                host.command(a); return true;
        }
    }

    private void doBgm(String[] a) {
        if (a.length < 2 || a[1].equals("stop") || a[1].equals("off")) { host.stopBgm(); return; }
        boolean loop = !(a.length > 2 && a[2].equals("noloop"));
        host.playBgm(a[1], loop);
    }

    private void doSe(String[] a) {
        if (a.length < 2 || a[1].equals("stop") || a[1].equals("off")) { host.stopSe(); return; }
        // "#se2 sys00 sys01 ..." lists candidates, one of which is chosen at random (title jingle variants)
        if (a[0].equals("se2") && a.length > 2) { host.playSe(a[1 + rnd.nextInt(a.length - 1)], false); return; }
        host.playSe(a[1], a.length > 2 && a[2].equals("loop"));
    }

    private void doSelect(String[] a) {
        selectTargets = new ArrayList<>(Arrays.asList(a).subList(1, a.length));
        List<String> opts = new ArrayList<>();
        while (opts.size() < selectTargets.size() && pc < lines.size()) {
            String l = lines.get(pc++).trim();
            if (l.isEmpty() || l.startsWith(";") || l.startsWith(".")) continue;
            if (l.startsWith("#")) { pc--; break; }
            opts.add(l);
        }
        host.showSelect(opts);
        wait = Wait.SELECT;
    }

    private void doCall(String[] a) {
        callStack.push(new Object[]{scriptName, pc});
        String name = a[1];
        String label = a.length > 2 ? a[2] : null;
        if (!load(name, label)) { Object[] f = callStack.pop(); }
    }

    private void doReturn() {
        if (callStack.isEmpty()) { wait = Wait.END; return; }
        Object[] f = callStack.pop();
        load((String) f[0], null);
        pc = (Integer) f[1];
    }

    private void gotoLabel(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).trim();
            if (s.length() > 6 && s.charAt(0) == '#' && s.substring(1).toLowerCase(Locale.ROOT).startsWith("label")) {
                String[] p = split(s.substring(1));
                if (p.length > 1 && p[1].equalsIgnoreCase(l)) { pc = i + 1; return; }
            }
        }
        host.log("label not found: " + label + " in " + scriptName);
    }

    // ------------------------------------------------------------- if / else

    private void doIf(String expr) {
        if (eval(expr) != 0) return;
        pc = skipTo(pc, false);
    }

    /** Skip forward to the matching #else (consume it) or #endif. If toEndifOnly, ignore #else. */
    private int skipTo(int from, boolean toEndifOnly) {
        int depth = 0;
        for (int i = from; i < lines.size(); i++) {
            String s = lines.get(i).trim();
            if (!s.startsWith("#")) continue;
            String w = s.substring(1).trim().split("[ \t;]+")[0].toLowerCase(Locale.ROOT);
            if (w.equals("if")) depth++;
            else if (w.equals("endif")) { if (depth == 0) return i + 1; depth--; }
            else if (w.equals("else") && depth == 0 && !toEndifOnly) return i + 1;
        }
        return lines.size();
    }

    private void doSet(String[] a) {
        if (a.length < 3) return;
        StringBuilder e = new StringBuilder();
        for (int i = 2; i < a.length; i++) e.append(a[i]);
        setVar(a[1], eval(e.toString()));
    }

    private int parse(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return eval(s); } }

    // ------------------------------------------------------------ expressions

    public int eval(String s) { return new Parser(s).parseExpr(0); }

    private final class Parser {
        final String s; int p;
        Parser(String s) { this.s = s.replace(" ", "").toLowerCase(Locale.ROOT); }

        private int prec(String op) {
            switch (op) {
                case "||": return 1; case "&&": return 2; case "|": return 3; case "^": return 4; case "&": return 5;
                case "==": case "!=": return 6; case "<": case "<=": case ">": case ">=": return 7;
                case "+": case "-": return 9; case "*": case "/": case "%": return 10;
            }
            return -1;
        }

        private String peekOp() {
            if (p >= s.length()) return null;
            String two = p + 1 < s.length() ? s.substring(p, p + 2) : "";
            if (two.equals("||") || two.equals("&&") || two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) return two;
            String one = s.substring(p, p + 1);
            return prec(one) > 0 ? one : null;
        }

        int parseExpr(int minPrec) {
            int lhs = unary();
            while (true) {
                String op = peekOp();
                if (op == null) return lhs;
                int pr = prec(op);
                if (pr < minPrec || pr <= 0) return lhs;
                p += op.length();
                int rhs = parseExpr(pr + 1);
                lhs = apply(op, lhs, rhs);
            }
        }

        int apply(String op, int l, int r) {
            switch (op) {
                case "||": return (l != 0 || r != 0) ? 1 : 0;
                case "&&": return (l != 0 && r != 0) ? 1 : 0;
                case "|": return l | r; case "^": return l ^ r; case "&": return l & r;
                case "==": return l == r ? 1 : 0; case "!=": return l != r ? 1 : 0;
                case "<": return l < r ? 1 : 0; case "<=": return l <= r ? 1 : 0;
                case ">": return l > r ? 1 : 0; case ">=": return l >= r ? 1 : 0;
                case "+": return l + r; case "-": return l - r; case "*": return l * r;
                case "/": return r == 0 ? 0 : l / r; case "%": return r == 0 ? 0 : l % r;
            }
            return 0;
        }

        int unary() {
            if (p >= s.length()) return 0;
            char c = s.charAt(p);
            if (c == '!') { p++; return unary() == 0 ? 1 : 0; }
            if (c == '-') { p++; return -unary(); }
            if (c == '~') { p++; return ~unary(); }
            if (c == '(') { p++; int v = parseExpr(0); if (p < s.length() && s.charAt(p) == ')') p++; return v; }
            if (Character.isDigit(c)) {
                int st = p;
                while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
                return Integer.parseInt(s.substring(st, p));
            }
            if (Character.isLetter(c) || c == '_') {
                int st = p;
                while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_')) p++;
                String id = s.substring(st, p);
                if (id.equals("rnd")) {
                    int n = 1;
                    if (p < s.length() && s.charAt(p) == '(') { p++; n = parseExpr(0); if (p < s.length() && s.charAt(p) == ')') p++; }
                    return n <= 0 ? 0 : rnd.nextInt(n);
                }
                if (id.equals("true")) return 1;
                if (id.equals("false")) return 0;
                // indirect access like s[2] or s(a)
                if (p < s.length() && (s.charAt(p) == '[')) {
                    p++; int idx = parseExpr(0); if (p < s.length() && s.charAt(p) == ']') p++;
                    id = id + idx;
                }
                Integer v = vars.get(id);
                return v == null ? 0 : v;
            }
            p++;
            return 0;
        }
    }
}
