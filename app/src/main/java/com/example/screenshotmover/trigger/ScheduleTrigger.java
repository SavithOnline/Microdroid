package com.example.screenshotmover.trigger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

/** Exact time-of-day / day-of-week alarms for TIME triggers. */
public final class ScheduleTrigger {
    private ScheduleTrigger() {}

    public static final String ACTION_TRIGGER_ALARM = "com.microdroid.ACTION_TRIGGER_ALARM";
    public static final String EXTRA_PLUGIN = "plugin_id";
    public static final String EXTRA_INDEX = "trigger_index";
    public static final String EXTRA_AT = "at";
    public static final String EXTRA_DAYS = "days";

    public static void schedulePlugin(Context ctx, String id) {
        cancelPlugin(ctx, id);
        if (!Store.isEnabled(ctx, id)) return;
        List<JSONObject> triggers = TriggerStore.triggers(ctx, id);
        for (int i = 0; i < triggers.size(); i++) {
            JSONObject cfg = triggers.get(i);
            if (!TriggerType.TIME.id.equals(cfg.optString("type", ""))) continue;
            scheduleOne(ctx, id, i, cfg.optString("at", ""), cfg.optString("days", ""));
        }
    }

    public static void scheduleOne(Context ctx, String id, int index, String at, String days) {
        int hm = ConstraintEvaluator.parseHm(at);
        if (hm < 0) return;
        long next = nextOccurrence(hm, days == null ? "" : days);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(ctx, id, index, at, days, true);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            }
        } catch (Exception e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    public static void rescheduleAll(Context ctx) {
        Context app = ctx.getApplicationContext();
        for (String id : PluginManager.scriptIds(app)) {
            schedulePlugin(app, id);
        }
    }

    public static void cancelPlugin(Context ctx, String id) {
        for (int i = 0; i < 32; i++) {
            PendingIntent pi = alarmIntent(ctx, id, i, "", "", false);
            if (pi != null) pi.cancel();
        }
    }

    /** Next epoch millis matching HH:mm on one of the ISO day numbers (Mon=1..Sun=7, empty = any). */
    public static long nextOccurrence(int hm, String days) {
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (hm <= nowMin) cal.add(Calendar.DAY_OF_YEAR, 1);
        int guard = 0;
        while (!ConstraintEvaluator.dayMatches(days, cal.getTimeInMillis()) && guard++ < 8) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        cal.set(Calendar.HOUR_OF_DAY, hm / 60);
        cal.set(Calendar.MINUTE, hm % 60);
        return cal.getTimeInMillis();
    }

    private static PendingIntent alarmIntent(Context ctx, String id, int index,
                                             String at, String days, boolean create) {
        Intent i = new Intent(ctx, TriggerReceiver.class);
        i.setAction(ACTION_TRIGGER_ALARM);
        i.putExtra(EXTRA_PLUGIN, id);
        i.putExtra(EXTRA_INDEX, index);
        i.putExtra(EXTRA_AT, at == null ? "" : at);
        i.putExtra(EXTRA_DAYS, days == null ? "" : days);
        i.setData(Uri.parse("microdroid://time/" + id + "/" + index));
        int flags = create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, 3000 + index, i, flags);
    }
}
