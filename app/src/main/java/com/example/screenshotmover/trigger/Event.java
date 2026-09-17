package com.example.screenshotmover.trigger;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;

/** Trigger event payload handed to scripts as `ev` (a Map). */
public class Event extends LinkedHashMap<String, String> {

    public Event(String type) {
        put("type", type);
    }

    public Event(String type, String... kv) {
        put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) put(kv[i], kv[i + 1]);
    }

    public String type() {
        return get("type");
    }

    public int intValue(String key, int def) {
        try {
            return Integer.parseInt(get(key));
        } catch (Exception e) {
            return def;
        }
    }

    public boolean boolValue(String key, boolean def) {
        String v = get(key);
        if (v == null) return def;
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public String str(String key, String def) {
        String v = get(key);
        return v == null ? def : v;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            for (String k : keySet()) o.put(k, get(k));
        } catch (Exception ignored) {}
        return o;
    }

    public static Event fromJson(JSONObject o) {
        Event ev = new Event(o == null ? "unknown" : o.optString("type", "unknown"));
        if (o != null) {
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                ev.put(k, o.optString(k, ""));
            }
        }
        return ev;
    }
}
