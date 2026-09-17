package com.example.screenshotmover.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import com.example.screenshotmover.automation.Automation;
import com.example.screenshotmover.automation.Automations;
import com.example.screenshotmover.plugin.PluginManager;
import com.example.screenshotmover.plugin.ScriptAutomationHolder;

import java.util.ArrayList;
import java.util.List;

/** Prefs + per-automation AlarmManager scheduling. */
public final class Store {
    private Store() {}

    public static final int MIN_INTERVAL_MIN = 15;
    public static final int DEFAULT_INTERVAL_MIN = 360;

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("microdroid", Context.MODE_PRIVATE);
    }

    // ---------- one-time upgrade work ----------

    /**
     * Upgrade migration (runs once, flag "migrated_v2"):
     *  - cancels alarms scheduled by earlier builds (old PendingIntent identity)
     *  - carries v1 "mover" prefs over to the screenshots keys
     *  - reschedules enabled automations with the new alarm identity
     * Safe to call from any entry point.
     */
    public static void migrateOnce(Context ctx) {
        SharedPreferences neu = prefs(ctx);
        Context app = ctx.getApplicationContext();
        if (!neu.getBoolean("migrated_v1", false)) {
            neu.edit().putBoolean("migrated_v1", true).apply();
        }
        if (neu.getBoolean("migrated_v2", false)) return;
        try {
            for (String id : knownIds(app)) Scheduler.cancelLegacy(app, id);
            migrateLegacyPrefs(app, neu);
            Scheduler.rescheduleEnabled(app);
            neu.edit().putBoolean("migrated_v2", true).apply();
        } catch (Exception ignored) {
            // flag not set -> retried on the next entry point
        }
    }

    /** v1 "mover" prefs -> screenshots keys, without clobbering existing values. */
    private static void migrateLegacyPrefs(Context app, SharedPreferences neu) {
        SharedPreferences old = app.getSharedPreferences("mover", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = neu.edit();
        if (!neu.contains("interval_screenshots") && old.contains("interval_min")) {
            int iv = old.getInt("interval_min", DEFAULT_INTERVAL_MIN);
            e.putInt("interval_screenshots", Math.max(iv, MIN_INTERVAL_MIN));
        }
        if (!neu.contains("last_screenshots")) {
            String last = old.getString("last_status", old.getString("last", null));
            if (last != null) e.putString("last_screenshots", last);
        }
        e.apply();
    }

    /** Current automations plus ids left behind in prefs (orphans included). */
    private static List<String> knownIds(Context app) {
        List<String> ids = new ArrayList<>();
        for (Automation a : allAutomations(app)) ids.add(a.id());
        for (String key : prefs(app).getAll().keySet()) {
            if (key.startsWith("enabled_")) ids.add(key.substring("enabled_".length()));
            else if (key.startsWith("interval_")) ids.add(key.substring("interval_".length()));
        }
        return ids;
    }

    /** Remove per-automation state after its script has been deleted. */
    public static void forget(Context ctx, String id) {
        prefs(ctx).edit()
                .remove("enabled_" + id)
                .remove("interval_" + id)
                .remove("last_" + id)
                .remove("triggers_" + id)
                .remove("constraints_" + id)
                .apply();
    }

    // ---------- adb plugin import gate ----------

    public static boolean isAdbImportAllowed(Context ctx) {
        return prefs(ctx).getBoolean("allow_adb_import", true);
    }

    public static void setAdbImportAllowed(Context ctx, boolean allow) {
        prefs(ctx).edit().putBoolean("allow_adb_import", allow).apply();
    }

    public static boolean isEnabled(Context ctx, String id) {
        return prefs(ctx).getBoolean("enabled_" + id, false);
    }

    public static void setEnabled(Context ctx, String id, boolean on) {
        prefs(ctx).edit().putBoolean("enabled_" + id, on).apply();
        if (!on) Scheduler.cancel(ctx, id);
    }

    /** Whether repeating alarms are currently armed (drives the Settings indicator). */
    public static boolean isSchedulerOn(Context ctx) {
        return prefs(ctx).getBoolean("scheduler_on", false);
    }

    public static void setSchedulerOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("scheduler_on", on).apply();
    }

    public static int getInterval(Context ctx, String id) {
        return prefs(ctx).getInt("interval_" + id, DEFAULT_INTERVAL_MIN);
    }

    public static void setInterval(Context ctx, String id, int minutes) {
        if (minutes < MIN_INTERVAL_MIN) minutes = MIN_INTERVAL_MIN;
        prefs(ctx).edit().putInt("interval_" + id, minutes).apply();
    }

    public static String getLast(Context ctx, String id) {
        return prefs(ctx).getString("last_" + id, "idle");
    }

    public static void setLast(Context ctx, String id, String s) {
        prefs(ctx).edit().putString("last_" + id, s).apply();
    }

    /** Built-ins + installed script plugins, fresh holders each call. */
    public static List<Automation> allAutomations(Context ctx) {
        List<Automation> out = new ArrayList<>(Automations.all());
        Context app = ctx.getApplicationContext();
        for (String id : PluginManager.scriptIds(app)) {
            out.add(new ScriptAutomationHolder(app, id));
        }
        return out;
    }

    /** Unified lookup by id across built-ins and scripts. */
    public static Automation lookup(Context ctx, String id) {
        if (id == null) return null;
        Automation a = Automations.byId(id);
        if (a != null) return a;
        Context app = ctx.getApplicationContext();
        for (String sid : PluginManager.scriptIds(app)) {
            if (sid.equalsIgnoreCase(id)) return new ScriptAutomationHolder(app, sid);
        }
        return null;
    }

    /** Run one automation synchronously (caller must be on background thread). */
    public static String runOne(Context ctx, String id) {
        Automation a = lookup(ctx, id);
        if (a == null) return "Unknown automation: " + id;
        try {
            String status = a.run(ctx.getApplicationContext());
            setLast(ctx, id, status);
            return status;
        } catch (Exception e) {
            String status = "Error: " + e;
            setLast(ctx, id, status);
            return status;
        }
    }

    /** Run all enabled automations, return combined status. */
    public static String runEnabled(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Automation a : allAutomations(ctx)) {
            if (!isEnabled(ctx, a.id())) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(a.id()).append(": ").append(runOne(ctx, a.id()));
        }
        return sb.length() == 0 ? "No enabled automations" : sb.toString();
    }

    /** Scheduler for per-automation inexact repeating alarms. */
    public static final class Scheduler {
        private Scheduler() {}

        public static void schedule(Context ctx, String automationId, int intervalMin) {
            if (intervalMin < MIN_INTERVAL_MIN) intervalMin = MIN_INTERVAL_MIN;
            setInterval(ctx, automationId, intervalMin);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = alarmIntent(ctx, automationId);
            am.cancel(pi);
            long interval = intervalMin * 60_000L;
            long first = SystemClock.elapsedRealtime() + 60_000L;
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, first, interval, pi);
        }

        public static void cancel(Context ctx, String automationId) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmIntent(ctx, automationId));
        }

        public static void rescheduleEnabled(Context ctx) {
            for (Automation a : Store.allAutomations(ctx)) {
                if (isEnabled(ctx, a.id())) {
                    schedule(ctx, a.id(), getInterval(ctx, a.id()));
                }
            }
        }

        public static void cancelAll(Context ctx) {
            for (Automation a : Store.allAutomations(ctx)) cancel(ctx, a.id());
        }

        static PendingIntent alarmIntent(Context ctx, String automationId) {
            Intent i = new Intent(ctx, MicrodroidReceiver.class);
            i.setAction(MicrodroidReceiver.ACTION_RUN);
            i.putExtra(MicrodroidReceiver.EXTRA_ID, automationId);
            // per-id data URI keeps PendingIntents distinct (extras are ignored for equality)
            i.setData(Uri.parse("microdroid://alarm/" + automationId));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getBroadcast(ctx, 0, i, flags);
        }

        /** Cancel an alarm created by earlier builds (request code derived from the id hash). */
        static void cancelLegacy(Context ctx, String automationId) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, MicrodroidReceiver.class);
            i.setAction(MicrodroidReceiver.ACTION_RUN);
            i.putExtra(MicrodroidReceiver.EXTRA_ID, automationId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(
                    ctx, 2000 + Math.abs(automationId.hashCode() % 1000), i, flags);
            am.cancel(pi);
            pi.cancel();
        }
    }
}
