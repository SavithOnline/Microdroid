package com.example.screenshotmover.trigger;

import android.content.Context;

import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Periodic URL checks; fires when the body changes (or when it contains a marker). */
public final class HttpPollTrigger {
    private HttpPollTrigger() {}

    private static final ScheduledExecutorService EXEC = Executors.newScheduledThreadPool(2);
    private static final Map<String, ScheduledFuture<?>> TASKS = new ConcurrentHashMap<>();
    private static final int MIN_MINUTES = 5;

    public static synchronized void sync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        for (ScheduledFuture<?> f : TASKS.values()) f.cancel(false);
        TASKS.clear();

        for (String id : PluginManager.scriptIds(app)) {
            if (!Store.isEnabled(app, id)) continue;
            java.util.List<JSONObject> triggers = TriggerStore.triggers(app, id);
            for (int i = 0; i < triggers.size(); i++) {
                JSONObject cfg = triggers.get(i);
                if (!TriggerType.HTTP_POLL.id.equals(cfg.optString("type", ""))) continue;
                String url = cfg.optString("url", "").trim();
                if (url.isEmpty()) continue;
                int minutes = (int) TriggerType.parse(cfg.optString("interval_min", "15"), 15);
                if (minutes < MIN_MINUTES) minutes = MIN_MINUTES;
                final String key = id + "#" + i;
                final String pluginId = id;
                final JSONObject config = cfg;
                ScheduledFuture<?> f = EXEC.scheduleWithFixedDelay(
                        () -> poll(app, pluginId, key, config), 10, minutes, TimeUnit.MINUTES);
                TASKS.put(key, f);
            }
        }
    }

    private static void poll(Context app, String id, String key, JSONObject cfg) {
        String url = cfg.optString("url", "").trim();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("User-Agent", "Microdroid/2.0");
            int code = c.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 && c.getErrorStream() != null ? c.getErrorStream() : c.getInputStream(),
                    StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            c.disconnect();
            String body = sb.length() > 20_000 ? sb.substring(0, 20_000) : sb.toString();
            String hash = Integer.toHexString(body.hashCode()) + ":" + body.length();

            String contains = cfg.optString("contains", "").trim();
            String prefKey = "poll_" + id + "_" + key.replace(id + "#", "");
            String prev = Store.prefs(app).getString(prefKey, "");

            boolean fire;
            if (!contains.isEmpty()) {
                fire = body.toLowerCase().contains(contains.toLowerCase()) && !hash.equals(prev);
            } else {
                fire = !prev.isEmpty() && !hash.equals(prev);
            }
            Store.prefs(app).edit().putString(prefKey, hash).apply();
            if (fire) {
                TriggerEngine.dispatch(app, new Event("http_poll", "url", url, "body", body));
            }
        } catch (Exception ignored) {}
    }

    public static synchronized void stopAll() {
        for (ScheduledFuture<?> f : TASKS.values()) f.cancel(false);
        TASKS.clear();
    }
}
