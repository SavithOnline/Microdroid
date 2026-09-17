package com.example.screenshotmover.trigger;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Per-plugin trigger/constraint configuration stored as JSON in microdroid prefs. */
public final class TriggerStore {
    private TriggerStore() {}

    private static SharedPreferences sp(Context ctx) {
        return Store.prefs(ctx);
    }

    public static List<JSONObject> triggers(Context ctx, String id) {
        return parse(sp(ctx).getString("triggers_" + id, null));
    }

    public static List<JSONObject> constraints(Context ctx, String id) {
        return parse(sp(ctx).getString("constraints_" + id, null));
    }

    public static JSONArray toArray(List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        if (list != null) for (JSONObject o : list) arr.put(o);
        return arr;
    }

    public static void setTriggers(Context ctx, String id, JSONArray arr) {
        sp(ctx).edit().putString("triggers_" + id, arr == null ? "[]" : arr.toString()).apply();
    }

    public static void setConstraints(Context ctx, String id, JSONArray arr) {
        sp(ctx).edit().putString("constraints_" + id, arr == null ? "[]" : arr.toString()).apply();
    }

    public static void forget(Context ctx, String id) {
        sp(ctx).edit()
                .remove("triggers_" + id)
                .remove("constraints_" + id)
                .apply();
    }

    private static List<JSONObject> parse(String json) {
        List<JSONObject> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Returns an error message, or null when the array is valid. */
    public static String validateTriggers(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) return "trigger #" + i + " is not an object";
            TriggerType t = TriggerType.byId(o.optString("type", ""));
            if (t == null) return "trigger #" + i + " has unknown type: " + o.optString("type", "");
            if (t == TriggerType.TIME && o.optString("at", "").trim().isEmpty()) {
                return "time trigger #" + i + " needs 'at' (HH:mm)";
            }
            if (t == TriggerType.HTTP_POLL && o.optString("url", "").trim().isEmpty()) {
                return "http_poll trigger #" + i + " needs 'url'";
            }
        }
        return null;
    }

    /** Returns an error message, or null when the array is valid. */
    public static String validateConstraints(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) return "constraint #" + i + " is not an object";
            ConstraintType c = ConstraintType.byId(o.optString("type", ""));
            if (c == null) return "constraint #" + i + " has unknown type: " + o.optString("type", "");
        }
        return null;
    }

    public static List<JSONObject> allTriggers(Context ctx, String id) {
        return triggers(ctx, id);
    }

    /** True when any enabled script has a trigger that needs the resident trigger service. */
    public static boolean needsService(Context ctx) {
        Context app = ctx.getApplicationContext();
        for (String id : PluginManager.scriptIds(app)) {
            if (!Store.isEnabled(app, id)) continue;
            for (JSONObject t : triggers(app, id)) {
                TriggerType type = TriggerType.byId(t.optString("type", ""));
                if (type != null && type.needsService) return true;
            }
        }
        return false;
    }

    /** True when any enabled script has a trigger of this type. */
    public static boolean hasType(Context ctx, TriggerType type) {
        Context app = ctx.getApplicationContext();
        for (String id : PluginManager.scriptIds(app)) {
            if (!Store.isEnabled(app, id)) continue;
            for (JSONObject t : triggers(app, id)) {
                if (type.id.equals(t.optString("type", ""))) return true;
            }
        }
        return false;
    }

    /** Short human summary for the automation card. */
    public static String summary(Context ctx, String id) {
        List<JSONObject> list = triggers(ctx, id);
        if (list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JSONObject t : list) {
            TriggerType type = TriggerType.byId(t.optString("type", ""));
            if (type == null) continue;
            if (n > 0) sb.append(" · ");
            sb.append(type.summary(t));
            n++;
        }
        return n == 0 ? null : sb.toString();
    }
}
