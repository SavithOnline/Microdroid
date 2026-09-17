package com.example.screenshotmover.trigger;

import android.content.Context;

import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;
import com.example.screenshotmover.plugin.ScriptAutomationHolder;

import org.json.JSONObject;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Matches events against enabled plugins, evaluates constraints and runs the scripts. */
public final class TriggerEngine {
    private TriggerEngine() {}

    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    public static void dispatch(Context ctx, Event ev) {
        final Context app = ctx.getApplicationContext();
        for (String id : PluginManager.scriptIds(app)) {
            if (!Store.isEnabled(app, id)) continue;
            List<JSONObject> triggers = TriggerStore.triggers(app, id);
            if (triggers.isEmpty()) continue;
            boolean matched = false;
            for (JSONObject cfg : triggers) {
                TriggerType t = TriggerType.byId(cfg.optString("type", ""));
                if (t != null && t.matches(cfg, ev)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) continue;
            String fail = ConstraintEvaluator.firstFailure(app, TriggerStore.constraints(app, id));
            if (fail != null) {
                PluginManager.appendLog(app, id, "trigger " + ev.type() + " skipped (" + fail + ")");
                continue;
            }
            fire(app, id, ev);
        }
    }

    /** Runs one plugin's event handler on the shared executor (serialized per plugin). */
    public static void fire(final Context app, final String id, final Event ev) {
        if (!RUNNING.add(id)) {
            PluginManager.appendLog(app, id, "trigger " + ev.type() + " skipped (already running)");
            return;
        }
        EXEC.execute(() -> {
            try {
                Store.prefs(app).edit()
                        .putInt("fired_" + id, Store.prefs(app).getInt("fired_" + id, 0) + 1)
                        .putLong("last_fired_" + id, System.currentTimeMillis())
                        .apply();
                String status = new ScriptAutomationHolder(app, id).runEvent(app, ev);
                Store.setLast(app, id, status);
            } catch (Throwable t) {
                Store.setLast(app, id, "Error: " + t);
            } finally {
                RUNNING.remove(id);
            }
        });
    }
}
