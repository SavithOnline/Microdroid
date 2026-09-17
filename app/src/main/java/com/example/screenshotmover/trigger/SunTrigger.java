package com.example.screenshotmover.trigger;

import android.content.Context;
import android.location.Location;

import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Sunrise/sunset triggers computed locally from the last known location. */
public final class SunTrigger {
    private SunTrigger() {}

    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> task;

    public static synchronized void sync(Context ctx) {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (!TriggerStore.hasType(ctx, TriggerType.SUN)) return;
        final Context app = ctx.getApplicationContext();
        task = EXEC.scheduleWithFixedDelay(() -> tick(app), 15, 60, TimeUnit.MINUTES);
    }

    private static void tick(Context app) {
        try {
            Location loc = ConstraintEvaluator.lastLocation(app);
            if (loc == null) return;
            long now = System.currentTimeMillis();
            for (String id : PluginManager.scriptIds(app)) {
                if (!Store.isEnabled(app, id)) continue;
                List<JSONObject> triggers = TriggerStore.triggers(app, id);
                for (int i = 0; i < triggers.size(); i++) {
                    JSONObject cfg = triggers.get(i);
                    if (!TriggerType.SUN.id.equals(cfg.optString("type", ""))) continue;
                    String event = cfg.optString("event", "sunrise");
                    int offset = (int) TriggerType.parse(cfg.optString("offset_min", "0"), 0);
                    long when = nextOccurrence(loc, event, offset);
                    if (when <= 0) continue;
                    long delta = when - now;
                    if (delta < 0 || delta > 61 * 60_000L) continue;
                    String key = "sun_" + id + "_" + i + "_" + (when / 60000);
                    if (Store.prefs(app).getBoolean(key, false)) continue;
                    Store.prefs(app).edit().putBoolean(key, true).apply();
                    EXEC.schedule(() -> TriggerEngine.dispatch(app, new Event("sun", "event", event)),
                            delta, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception ignored) {}
    }

    static long nextOccurrence(Location loc, String event, int offsetMin) {
        try {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 12);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            double[] times = sunTimes(loc.getLatitude(), loc.getLongitude(), cal);
            double utMinutes = "sunset".equals(event) ? times[1] : times[0];
            if (Double.isNaN(utMinutes)) return -1;
            double localMinutes = utMinutes
                    + TimeZone.getDefault().getOffset(cal.getTimeInMillis()) / 60_000.0
                    + offsetMin;
            long epoch = cal.getTimeInMillis() - 12L * 3600_000L + Math.round(localMinutes * 60_000.0);
            if (epoch <= System.currentTimeMillis()) epoch += 24L * 3600_000L;
            return epoch;
        } catch (Exception e) {
            return -1;
        }
    }

    /** {sunriseUtcMinutes, sunsetUtcMinutes} or NaN. NOAA simplified algorithm. */
    static double[] sunTimes(double lat, double lng, Calendar date) {
        double[] out = {Double.NaN, Double.NaN};
        try {
            int n = date.get(Calendar.DAY_OF_YEAR);
            double lngHour = lng / 15.0;
            out[0] = calc(lat, lngHour, n, true);
            out[1] = calc(lat, lngHour, n, false);
        } catch (Exception ignored) {}
        return out;
    }

    private static double calc(double lat, double lngHour, int n, boolean sunrise) {
        double t = n + ((sunrise ? 6 : 18) - lngHour) / 24.0;
        double M = 0.9856 * t - 3.289;
        double L = M + 1.916 * Math.sin(Math.toRadians(M)) + 0.020 * Math.sin(Math.toRadians(2 * M)) + 282.634;
        L = ((L % 360) + 360) % 360;
        double RA = Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(L))));
        RA = ((RA % 360) + 360) % 360;
        double lQuadrant = Math.floor(L / 90) * 90;
        double raQuadrant = Math.floor(RA / 90) * 90;
        RA = (RA + (lQuadrant - raQuadrant)) / 15.0;
        double sinDec = 0.39782 * Math.sin(Math.toRadians(L));
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(Math.toRadians(90.833)) - sinDec * Math.sin(Math.toRadians(lat)))
                / (cosDec * Math.cos(Math.toRadians(lat)));
        if (cosH > 1 || cosH < -1) return Double.NaN;
        double H = sunrise ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH));
        H /= 15.0;
        double T = H + RA - 0.06571 * t - 6.622;
        double UT = T - lngHour;
        UT = ((UT % 24) + 24) % 24;
        return UT * 60.0;
    }
}
