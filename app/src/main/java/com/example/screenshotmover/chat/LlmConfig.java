package com.example.screenshotmover.chat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** Chat/LLM settings (non-secret). API keys live in {@link ApiKeyStore}. */
public final class LlmConfig {
    private LlmConfig() {}

    public static final String MODE_ASK = "ask";
    public static final String MODE_BYPASS = "bypass";

    private static final String PREFS = "microdroid_llm";

    static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String providerId(Context ctx) {
        return sp(ctx).getString("provider", LlmProvider.OPENAI_COMPAT.id);
    }

    public static void setProviderId(Context ctx, String id) {
        sp(ctx).edit().putString("provider", id).apply();
    }

    public static String mode(Context ctx) {
        return sp(ctx).getString("mode", MODE_ASK);
    }

    public static void setMode(Context ctx, String mode) {
        sp(ctx).edit().putString("mode", mode).apply();
    }

    public static boolean bypass(Context ctx) {
        return MODE_BYPASS.equals(mode(ctx));
    }

    public static String baseUrl(Context ctx) {
        return Providers.baseUrl(ctx, providerId(ctx));
    }

    public static void setBaseUrl(Context ctx, String value) {
        setBaseUrlFor(ctx, providerId(ctx), value);
    }

    public static void setBaseUrlFor(Context ctx, String providerId, String value) {
        sp(ctx).edit().putString("base_" + providerId, value).apply();
    }

    public static String model(Context ctx) {
        return Providers.model(ctx, providerId(ctx));
    }

    public static void setModel(Context ctx, String value) {
        setModelFor(ctx, providerId(ctx), value);
    }

    public static void setModelFor(Context ctx, String providerId, String value) {
        sp(ctx).edit().putString("model_" + providerId, value).apply();
    }

    public static String sessionId(Context ctx) {
        SharedPreferences p = sp(ctx);
        String id = p.getString("session_id", null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString("session_id", id).apply();
        }
        return id;
    }

    public static void newSession(Context ctx) {
        sp(ctx).edit().putString("session_id", UUID.randomUUID().toString()).apply();
    }

    public static long chatResetAt(Context ctx) {
        return sp(ctx).getLong("chat_reset_at", 0);
    }

    public static void markChatReset(Context ctx) {
        sp(ctx).edit().putLong("chat_reset_at", System.currentTimeMillis()).apply();
    }

    public static String storedBaseUrl(Context ctx, String providerId) {
        return Providers.baseUrl(ctx, providerId);
    }

    public static String storedModel(Context ctx, String providerId) {
        return Providers.model(ctx, providerId);
    }

    /** Immutable snapshot handed to the HTTP clients. */
    public static final class Config {
        public final String providerId;
        public final String baseUrl;
        public final String model;
        public final String apiKey;
        public final String sessionId;
        public final String wire;

        Config(String providerId, String baseUrl, String model, String apiKey, String sessionId, String wire) {
            this.providerId = providerId;
            this.baseUrl = baseUrl;
            this.model = model;
            this.apiKey = apiKey;
            this.sessionId = sessionId;
            this.wire = wire;
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isEmpty();
        }
    }

    public static Config current(Context ctx) {
        String pid = providerId(ctx);
        return new Config(pid, baseUrl(ctx), model(ctx), ApiKeyStore.get(ctx, pid), sessionId(ctx),
                Providers.wireOf(ctx, pid));
    }
}
