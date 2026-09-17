package com.example.screenshotmover.chat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Built-in plus user-added model providers. Custom ones live as JSON in the chat prefs. */
public final class Providers {
    private Providers() {}

    static final String CUSTOM_KEY = "custom_providers";

    public static final class Def {
        public final String id;
        public final String label;
        public final String wire;
        public final String defaultBaseUrl;
        public final String defaultModel;
        public final boolean custom;

        Def(String id, String label, String wire, String defaultBaseUrl, String defaultModel, boolean custom) {
            this.id = id;
            this.label = label;
            this.wire = wire;
            this.defaultBaseUrl = defaultBaseUrl;
            this.defaultModel = defaultModel;
            this.custom = custom;
        }
    }

    public static List<Def> all(Context ctx) {
        List<Def> out = new ArrayList<>();
        for (LlmProvider p : LlmProvider.values()) {
            out.add(new Def(p.id, p.label, p.id, p.defaultBaseUrl, p.defaultModel, false));
        }
        for (JSONObject o : customJson(ctx)) {
            out.add(new Def(o.optString("id"), o.optString("label"),
                    o.optString("wire", LlmProvider.OPENAI_COMPAT.id),
                    o.optString("baseUrl"), o.optString("model"), true));
        }
        return out;
    }

    public static Def byId(Context ctx, String id) {
        if (id != null) {
            for (Def d : all(ctx)) {
                if (id.equals(d.id)) return d;
            }
        }
        return new Def(id == null ? LlmProvider.OPENAI_COMPAT.id : id,
                id == null ? "OpenAI-compatible" : id,
                LlmProvider.OPENAI_COMPAT.id, "", "", false);
    }

    public static String labelOf(Context ctx, String id) {
        return byId(ctx, id).label;
    }

    public static String wireOf(Context ctx, String id) {
        return byId(ctx, id).wire;
    }

    /** Stored base URL override, else the provider default. */
    public static String baseUrl(Context ctx, String id) {
        String stored = LlmConfig.sp(ctx).getString("base_" + id, null);
        if (stored != null && !stored.isEmpty()) return stored;
        return byId(ctx, id).defaultBaseUrl;
    }

    /** Stored model override, else the provider default. */
    public static String model(Context ctx, String id) {
        String stored = LlmConfig.sp(ctx).getString("model_" + id, null);
        if (stored != null && !stored.isEmpty()) return stored;
        return byId(ctx, id).defaultModel;
    }

    public static String add(Context ctx, String label, String wire, String baseUrl, String model) {
        String id = uniqueId(ctx, slug(label));
        try {
            JSONArray arr = customArray(ctx);
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", label == null || label.trim().isEmpty() ? id : label.trim());
            o.put("wire", wire);
            o.put("baseUrl", baseUrl == null ? "" : baseUrl);
            o.put("model", model == null ? "" : model);
            arr.put(o);
            LlmConfig.sp(ctx).edit().putString(CUSTOM_KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
        return id;
    }

    public static void remove(Context ctx, String id) {
        JSONArray arr = customArray(ctx);
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) out.put(o);
        }
        LlmConfig.sp(ctx).edit()
                .putString(CUSTOM_KEY, out.toString())
                .remove("base_" + id)
                .remove("model_" + id)
                .apply();
        ApiKeyStore.put(ctx, id, "");
        if (id.equals(LlmConfig.providerId(ctx))) {
            LlmConfig.setProviderId(ctx, LlmProvider.OPENAI_COMPAT.id);
        }
    }

    public static void saveConfig(Context ctx, String id, String baseUrl, String model) {
        LlmConfig.setBaseUrlFor(ctx, id, baseUrl);
        LlmConfig.setModelFor(ctx, id, model);
    }

    public static String wireLabel(String wire) {
        return LlmProvider.byId(wire).label;
    }

    private static JSONArray customArray(Context ctx) {
        try {
            return new JSONArray(LlmConfig.sp(ctx).getString(CUSTOM_KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static List<JSONObject> customJson(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = customArray(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    private static String slug(String label) {
        String s = label == null ? "" : label.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        if (s.length() > 20) s = s.substring(0, 20);
        if (s.length() < 2) s = "provider";
        return "custom_" + s;
    }

    private static String uniqueId(Context ctx, String base) {
        List<Def> all = all(ctx);
        String id = base;
        int n = 2;
        while (exists(all, id)) {
            String suffix = "_" + n++;
            id = base.length() + suffix.length() > 32
                    ? base.substring(0, 32 - suffix.length()) + suffix
                    : base + suffix;
        }
        return id;
    }

    private static boolean exists(List<Def> all, String id) {
        for (Def d : all) {
            if (d.id.equals(id)) return true;
        }
        return false;
    }
}
