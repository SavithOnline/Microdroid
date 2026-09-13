package com.example.screenshotmover.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import bsh.Interpreter;

/**
 * Script plugin storage + on-device execution (BeanShell: Java-like syntax, interpreted).
 * Layout: files/plugins/&lt;id&gt;/Plugin.java + meta.json
 *
 * <p>A plugin file defines four functions (Java-like, untyped):
 * <pre>
 * id() { return "my_plugin"; }
 * name() { return "My plugin"; }
 * description() { return "What it does."; }
 * run(ctx) {
 *   ctx.log("hi");
 *   return "OK";
 * }
 * </pre>
 * {@code ctx} is a {@link PluginContext}. No classes, no imports needed.
 */
public final class PluginManager {
    private PluginManager() {}

    public static final int MAX_LOG_LINES = 200;
    public static final int MAX_FAILURES = 3;

    public static final String TEMPLATE =
            "// Microdroid script (Java-like). Functions id/name/description/run are required.\n" +
            "// ctx: log, list, exists, isFile, isDirectory, length, mkdirs, copy, delete, move, scan, getData, putData\n" +
            "\n" +
            "id() { return \"my_plugin\"; }\n" +
            "name() { return \"My plugin\"; }\n" +
            "description() { return \"What it does.\"; }\n" +
            "run(ctx) {\n" +
            "    ctx.log(\"hello from my plugin\");\n" +
            "    files = ctx.list(\"/storage/emulated/0/Download\");\n" +
            "    n = files == null ? -1 : files.length;\n" +
            "    return \"Download entries: \" + n;\n" +
            "}\n";

    /** Metadata read from a source without running it. */
    public static class Meta {
        public String id, name, description;
    }

    // ---------- storage ----------

    public static File pluginsDir(Context ctx) {
        File d = new File(ctx.getApplicationContext().getFilesDir(), "plugins");
        // noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File dirFor(Context ctx, String id) {
        return new File(pluginsDir(ctx), id);
    }

    public static boolean isValidId(String id) {
        return id != null && id.matches("[a-z0-9_]{2,32}");
    }

    public static List<String> scriptIds(Context ctx) {
        List<String> out = new ArrayList<>();
        File[] dirs = pluginsDir(ctx).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (d.isDirectory() && new File(d, "Plugin.java").isFile()) {
                out.add(d.getName());
            }
        }
        return out;
    }

    public static String readSource(Context ctx, String id) {
        try {
            File f = new File(dirFor(ctx, id), "Plugin.java");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String metaName(Context ctx, String id, String fallback) {
        try {
            File meta = new File(dirFor(ctx, id), "meta.json");
            if (!meta.isFile()) return fallback;
            org.json.JSONObject o = new org.json.JSONObject(readFully(meta));
            return o.optString("name", fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String metaDesc(Context ctx, String id, String fallback) {
        try {
            File meta = new File(dirFor(ctx, id), "meta.json");
            if (!meta.isFile()) return fallback;
            org.json.JSONObject o = new org.json.JSONObject(readFully(meta));
            return o.optString("description", fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String readFully(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ---------- inspect / save ----------

    /** Evaluate id()/name()/description() without running run(). Throws with the script error. */
    public static Meta inspect(String source) throws Exception {
        Interpreter i = new Interpreter();
        try {
            i.eval(source);
        } catch (Exception e) {
            throw new Exception(trimErr(e));
        }
        Meta m = new Meta();
        try {
            Object id = i.eval("id()");
            Object name = i.eval("name()");
            Object desc = i.eval("description()");
            m.id = id == null ? null : String.valueOf(id);
            m.name = name == null ? "" : String.valueOf(name);
            m.description = desc == null ? "" : String.valueOf(desc);
        } catch (Exception e) {
            throw new Exception("id()/name()/description() must be defined: " + trimErr(e));
        }
        if (!isValidId(m.id)) {
            throw new Exception("id() must match [a-z0-9_]{2,32}, got: " + m.id);
        }
        return m;
    }

    /**
     * Validate + save source. Writes Plugin.java + meta.json, returns the id.
     * Throws with the script error message (shown in UI, nothing saved).
     */
    public static String save(Context ctx, String source) throws Exception {
        Meta m = inspect(source); // validate first
        File dir = dirFor(ctx, m.id);
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(new File(dir, "Plugin.java")), StandardCharsets.UTF_8)) {
            w.write(source);
        }
        org.json.JSONObject meta = new org.json.JSONObject();
        meta.put("id", m.id);
        meta.put("name", m.name);
        meta.put("description", m.description);
        meta.put("version", System.currentTimeMillis());
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(new File(dir, "meta.json")), StandardCharsets.UTF_8)) {
            w.write(meta.toString());
        }
        prefs(ctx).edit().remove("fail_" + m.id).apply();
        return m.id;
    }

    /** Execute run(ctx). A fresh interpreter per call (not thread-safe otherwise). */
    public static String execute(String source, PluginContext pluginCtx) throws Exception {
        Interpreter i = new Interpreter();
        try {
            i.eval(source);
        } catch (Exception e) {
            throw new Exception("Load error: " + trimErr(e));
        }
        i.set("ctx", pluginCtx);
        Object out;
        try {
            out = i.eval("run(ctx)");
        } catch (Exception e) {
            throw new Exception(trimErr(e));
        }
        return out == null ? "OK (null)" : String.valueOf(out);
    }

    private static String trimErr(Exception e) {
        String s = e.toString();
        if (s.length() > 300) s = s.substring(0, 300);
        return s.replace('\n', ' ').trim();
    }

    public static boolean remove(Context ctx, String id) {
        try {
            deleteRecursive(dirFor(ctx, id));
            prefs(ctx).edit().remove("fail_" + id).remove("log_" + id).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        // noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------- run logs + failure tracking ----------

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("microdroid", Context.MODE_PRIVATE);
    }

    public static void appendLog(Context ctx, String id, String msg) {
        try {
            SharedPreferences sp = prefs(ctx);
            String prev = sp.getString("log_" + id, "");
            String entry = "[" + System.currentTimeMillis() + "] " + msg + "\n";
            String combined = prev + entry;
            String[] lines = combined.split("\n");
            if (lines.length > MAX_LOG_LINES) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                    sb.append(lines[i]).append('\n');
                }
                combined = sb.toString();
            }
            sp.edit().putString("log_" + id, combined).apply();
        } catch (Exception ignored) {}
    }

    public static String getLog(Context ctx, String id) {
        try {
            return prefs(ctx).getString("log_" + id, "(empty)");
        } catch (Exception e) {
            return "(empty)";
        }
    }

    public static int failCount(Context ctx, String id) {
        try {
            return prefs(ctx).getInt("fail_" + id, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static void noteSuccess(Context ctx, String id) {
        prefs(ctx).edit().remove("fail_" + id).apply();
    }

    static void noteFailure(Context ctx, String id) {
        prefs(ctx).edit().putInt("fail_" + id, failCount(ctx, id) + 1).apply();
    }
}
