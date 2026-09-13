package com.example.screenshotmover.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

    // Legacy prefs (v1 "mover") -> migrate interval for screenshots once.
    public static void migrateOnce(Context ctx) {
        SharedPreferences neu = prefs(ctx);
        if (neu.getBoolean("migrated_v1", false)) return;
        neu.edit().putBoolean("migrated_v1", true).apply();
    }

    public static boolean isEnabled(Context ctx, String id) {
        return prefs(ctx).getBoolean("enabled_" + id, false);
    }

    public static void setEnabled(Context ctx, String id, boolean on) {
        prefs(ctx).edit().putBoolean("enabled_" + id, on).apply();
        if (!on) Scheduler.cancel(ctx, id);
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
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getBroadcast(ctx, 2000 + Math.abs(automationId.hashCode() % 1000), i, flags);
        }
    }
}
