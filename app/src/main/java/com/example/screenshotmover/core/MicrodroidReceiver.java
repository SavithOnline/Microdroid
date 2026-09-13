package com.example.screenshotmover.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
 *   adb shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.MicrodroidReceiver --es automation_id screenshots
 *   adb shell am broadcast -a com.microdroid.ACTION_RUN -n com.example.screenshotmover/.MicrodroidReceiver --es automation_id all
 *   adb shell am broadcast -a com.microdroid.ACTION_ENABLE -n ... --es automation_id storage_report
 *   adb shell am broadcast -a com.microdroid.ACTION_DISABLE -n ... --es automation_id storage_report
 *   adb shell am broadcast -a com.microdroid.ACTION_SET_INTERVAL -n ... --es automation_id screenshots --ei interval_min 360
 *   adb shell am broadcast -a com.microdroid.ACTION_LIST -n ...
 *   adb shell am broadcast -a com.microdroid.ACTION_START_SCHEDULER -n ...
 *   adb shell am broadcast -a com.microdroid.ACTION_STOP_SCHEDULER -n ...
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

    public static final String EXTRA_ID = "automation_id";
    public static final String EXTRA_INTERVAL = "interval_min";
    public static final String EXTRA_PATH = "path";

    @Override
    public void onReceive(Context context, Intent intent) {
        Store.migrateOnce(context);
        String a = intent == null ? "" : String.valueOf(intent.getAction());
        Context app = context.getApplicationContext();

        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            Store.Scheduler.rescheduleEnabled(app);
            return;
        }
        if (ACTION_ENABLE.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (Store.lookup(app, id) == null) return;
            Store.setEnabled(app, id, true);
            Store.Scheduler.schedule(app, id, Store.getInterval(app, id));
            toast(context, id + " enabled");
            return;
        }
        if (ACTION_DISABLE.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            if (Store.lookup(app, id) == null) return;
            Store.setEnabled(app, id, false);
            toast(context, id + " disabled");
            return;
        }
        if (ACTION_SET_INTERVAL.equals(a)) {
            String id = intent.getStringExtra(EXTRA_ID);
            int iv = intent.getIntExtra(EXTRA_INTERVAL, Store.DEFAULT_INTERVAL_MIN);
            if (Store.lookup(app, id) == null) return;
            Store.Scheduler.schedule(app, id, iv);
            Store.setEnabled(app, id, true);
            toast(context, id + " every " + iv + " min");
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
            return;
        }
        if (ACTION_START_SCHEDULER.equals(a)) {
            Store.Scheduler.rescheduleEnabled(app);
            toast(context, "Scheduler ON");
            return;
        }
        if (ACTION_STOP_SCHEDULER.equals(a)) {
            Store.Scheduler.cancelAll(app);
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
                    if ("all".equalsIgnoreCase(target)) {
                        Store.runEnabled(app);
                    } else {
                        Store.runOne(app, target);
                    }
                } finally {
                    pr.finish();
                }
            }).start();
            return;
        }
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
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
