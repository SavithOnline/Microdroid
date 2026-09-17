package com.example.screenshotmover.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.example.screenshotmover.automation.Automation;
import com.example.screenshotmover.plugin.PluginManager;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Generic Microdroid adb API. All broadcasts must be EXPLICIT on Android 8+
 * (use -n com.example.screenshotmover/.MicrodroidReceiver from PC).
 *
 *   adb shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.MicrodroidReceiver --es automation_id my_plugin
 *   adb shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.MicrodroidReceiver --es automation_id all
 *   adb shell am broadcast -a com.microdroid.ACTION_ENABLE -n ... --es automation_id my_plugin
 *   adb shell am broadcast -a com.microdroid.ACTION_DISABLE -n ... --es automation_id my_plugin
 *   adb shell am broadcast -a com.microdroid.ACTION_SET_INTERVAL -n ... --es automation_id my_plugin --ei interval_min 360
 *   adb shell am broadcast -a com.microdroid.ACTION_LIST -n ...
 *   adb shell am broadcast -a com.microdroid.ACTION_START_SCHEDULER -n ...
 *   adb shell am broadcast -a com.microdroid.ACTION_STOP_SCHEDULER -n ...
 *
 * Plugin imports need the "Allow ADB imports" pref (menu toggle, ON by default).
 */
public class MicrodroidReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN = "com.microdroid.ACTION_RUN";
    public static final String ACTION_ENABLE = "com.microdroid.ACTION_ENABLE";
    public static final String ACTION_DISABLE = "com.microdroid.ACTION_DISABLE";
    public static final String ACTION_SET_INTERVAL = "com.microdroid.ACTION_SET_INTERVAL";
    public static final String ACTION_LIST = "com.microdroid.ACTION_LIST";
    public static final String ACTION_START_SCHEDULER = "com.microdroid.ACTION_START_SCHEDULER";
    public static final String ACTION_STOP_SCHEDULER = "com.microdroid.ACTION_STOP_SCHEDULER";
    public static final String ACTION_PLUGIN_IMPORT = "com.microdroid.ACTION_PLUGIN_IMPORT";
    public static final String ACTION_PLUGIN_REMOVE = "com.microdroid.ACTION_PLUGIN_REMOVE";
    public static final String ACTION_SET_TRIGGERS = "com.microdroid.ACTION_SET_TRIGGERS";
    public static final String ACTION_SET_CONSTRAINTS = "com.microdroid.ACTION_SET_CONSTRAINTS";
    public static final String ACTION_EVENT = "com.microdroid.ACTION_EVENT";
    /** Local-only signal sent after state changes so the UI can refresh. Not in the manifest. */
    public static final String ACTION_STATE_CHANGED = "com.microdroid.ACTION_STATE_CHANGED";

    public static final String EXTRA_ID = "automation_id";
    public static final String EXTRA_INTERVAL = "interval_min";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TRIGGERS = "triggers";
    public static final String EXTRA_CONSTRAINTS = "constraints";
    public static final String EXTRA_TRIGGERS_B64 = "triggers_b64";
    public static final String EXTRA_CONSTRAINTS_B64 = "constraints_b64";
    public static final String EXTRA_EVENT_TYPE = "event_type";
    public static final String EXTRA_EVENT_JSON = "event_json";
    public static final String EXTRA_EVENT_JSON_B64 = "event_json_b64";

    @Override
    public void onReceive(Context context, Intent intent) {
        Store.migrateOnce(context);
        String a = intent == null ? "" : String.valueOf(intent.getAction());
        Context app = context.getApplicationContext();

        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            Store.Scheduler.rescheduleEnabled(app);
            Store.setSchedulerOn(app, true);
            return;
        }
        if (ACTION_ENABLE.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (Store.lookup(app, id) == null) return;
            Store.setEnabled(app, id, true);
            Store.Scheduler.schedule(app, id, Store.getInterval(app, id));
            Store.setSchedulerOn(app, true);
            com.example.screenshotmover.trigger.TriggerRuntime.refresh(app);
            toast(context, id + " enabled");
            notifyChanged(app);
            return;
        }
        if (ACTION_DISABLE.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (Store.lookup(app, id) == null) return;
            Store.setEnabled(app, id, false);
            com.example.screenshotmover.trigger.TriggerRuntime.refresh(app);
            toast(context, id + " disabled");
            notifyChanged(app);
            return;
        }
        if (ACTION_SET_INTERVAL.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            int iv = intent.getIntExtra(EXTRA_INTERVAL, Store.DEFAULT_INTERVAL_MIN);
            if (Store.lookup(app, id) == null) return;
            Store.Scheduler.schedule(app, id, iv);
            Store.setEnabled(app, id, true);
            Store.setSchedulerOn(app, true);
            toast(context, id + " every " + iv + " min");
            notifyChanged(app);
            return;
        }
        if (ACTION_SET_TRIGGERS.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (Store.lookup(app, id) == null) return;
            String json = intent.getStringExtra(EXTRA_TRIGGERS);
            if (json == null) json = decodeB64(intent.getStringExtra(EXTRA_TRIGGERS_B64));
            applyTriggerJson(app, id, json, true);
            return;
        }
        if (ACTION_SET_CONSTRAINTS.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (Store.lookup(app, id) == null) return;
            String json = intent.getStringExtra(EXTRA_CONSTRAINTS);
            if (json == null) json = decodeB64(intent.getStringExtra(EXTRA_CONSTRAINTS_B64));
            applyTriggerJson(app, id, json, false);
            return;
        }
        if (ACTION_EVENT.equals(a)) {
            String type = intent.getStringExtra(EXTRA_EVENT_TYPE);
            if (type == null || type.isEmpty()) {
                toast(context, "Missing --es event_type");
                return;
            }
            org.json.JSONObject o = null;
            String json = intent.getStringExtra(EXTRA_EVENT_JSON);
            if (json == null) json = decodeB64(intent.getStringExtra(EXTRA_EVENT_JSON_B64));
            if (json != null && !json.trim().isEmpty()) {
                try {
                    o = new org.json.JSONObject(json);
                } catch (Exception e) {
                    toast(context, "Bad event_json: " + e.getMessage());
                    return;
                }
            }
            com.example.screenshotmover.trigger.Event ev =
                    com.example.screenshotmover.trigger.Event.fromJson(o);
            ev.put("type", type);
            final com.example.screenshotmover.trigger.Event dispatchEv = ev;
            new Thread(() -> com.example.screenshotmover.trigger.TriggerEngine.dispatch(app, dispatchEv)).start();
            toast(context, "Event " + type);
            return;
        }
        if (ACTION_LIST.equals(a)) {
            StringBuilder sb = new StringBuilder();
            for (Automation x : Store.allAutomations(app)) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(x.id()).append(" enabled=").append(Store.isEnabled(app, x.id()))
                  .append(" interval=").append(Store.getInterval(app, x.id()));
            }
            Store.prefs(app).edit().putString("list", sb.toString()).apply();
            toast(context, sb.toString());
            return;
        }
        if (ACTION_PLUGIN_IMPORT.equals(a)) {
            if (!Store.isAdbImportAllowed(app)) {
                String msg = "Import blocked: enable 'Allow ADB imports' in Microdroid";
                Store.prefs(app).edit().putString("last_import", msg).apply();
                toast(context, msg);
                return;
            }
            String path = intent.getStringExtra(EXTRA_PATH);
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    String status = importFile(app, path);
                    Store.prefs(app).edit().putString("last_import", status).apply();
                    toast(context, status);
                } finally {
                    pr.finish();
                }
                notifyChanged(app);
            }).start();
            return;
        }
        if (ACTION_PLUGIN_REMOVE.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (id == null || !PluginManager.scriptIds(app).contains(id)) {
                toast(context, "Not a script plugin: " + id);
                return;
            }
            Store.setEnabled(app, id, false);
            PluginManager.remove(app, id);
            toast(context, "Removed " + id);
            notifyChanged(app);
            return;
        }
        if (ACTION_START_SCHEDULER.equals(a)) {
            Store.Scheduler.rescheduleEnabled(app);
            Store.setSchedulerOn(app, true);
            toast(context, "Scheduler ON");
            return;
        }
        if (ACTION_STOP_SCHEDULER.equals(a)) {
            Store.Scheduler.cancelAll(app);
            Store.setSchedulerOn(app, false);
            toast(context, "Scheduler OFF");
            return;
        }
        if (ACTION_RUN.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (id == null || id.isEmpty()) id = "all";
            final String target = id;
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    String status = "all".equalsIgnoreCase(target)
                            ? Store.runEnabled(app)
                            : Store.runOne(app, target);
                    toast(context, status);
                } finally {
                    pr.finish();
                }
                notifyChanged(app);
            }).start();
            return;
        }
    }

    /** Validate + persist trigger/constraint JSON from adb. */
    private void applyTriggerJson(Context app, String id, String json, boolean triggers) {        try {
            org.json.JSONArray arr = new org.json.JSONArray(json == null ? "[]" : json);
            String err = triggers
                    ? com.example.screenshotmover.trigger.TriggerStore.validateTriggers(arr)
                    : com.example.screenshotmover.trigger.TriggerStore.validateConstraints(arr);
            if (err != null) {
                Store.prefs(app).edit().putString("last_triggers", err).apply();
                toast(app, err);
                return;
            }
            if (triggers) {
                com.example.screenshotmover.trigger.TriggerStore.setTriggers(app, id, arr);
            } else {
                com.example.screenshotmover.trigger.TriggerStore.setConstraints(app, id, arr);
            }
            com.example.screenshotmover.trigger.TriggerRuntime.refresh(app);
            toast(app, (triggers ? "Triggers" : "Constraints") + " set for " + id);
            notifyChanged(app);
        } catch (Exception e) {
            toast(app, "Bad JSON: " + e.getMessage());
        }
    }

    private static String decodeB64(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return new String(android.util.Base64.decode(s.trim(), android.util.Base64.DEFAULT),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static void toast(Context ctx, String msg) {
        final Context app = ctx.getApplicationContext();
        try {
            MAIN.post(() -> {
                try {
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /** Tell the UI (same app only) that automation state changed. */
    private static void notifyChanged(Context app) {
        try {
            Intent i = new Intent(ACTION_STATE_CHANGED);
            i.setPackage(app.getPackageName());
            app.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    /** Read a .java file and install it as a script plugin. Runs off the main thread. */
    static String importFile(Context app, String path) {
        if (path == null || path.isEmpty()) return "Import: missing --es path";
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(path), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            String id = PluginManager.save(app, sb.toString());
            return "Imported " + id;
        } catch (Exception e) {
            return "Import failed: " + e;
        }
    }
}
