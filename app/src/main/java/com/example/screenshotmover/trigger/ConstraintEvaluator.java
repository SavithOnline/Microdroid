package com.example.screenshotmover.trigger;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.text.format.DateFormat;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

/** Evaluates constraints against current system state; returns the first failure reason or null. */
public final class ConstraintEvaluator {
    private ConstraintEvaluator() {}

    public static String firstFailure(Context ctx, List<JSONObject> constraints) {
        for (JSONObject c : constraints) {
            ConstraintType type = ConstraintType.byId(c.optString("type", ""));
            if (type == null) continue;
            String fail = check(ctx, type, c);
            if (fail != null) return fail;
        }
        return null;
    }

    private static String check(Context ctx, ConstraintType type, JSONObject c) {
        switch (type) {
            case TIME_WINDOW: {
                if (!dayMatches(c.optString("days", ""), System.currentTimeMillis())) return null;
                String from = c.optString("from", "").trim();
                String to = c.optString("to", "").trim();
                if (from.isEmpty() || to.isEmpty()) return null;
                int now = minutesNow();
                int a = parseHm(from), b = parseHm(to);
                boolean in = a <= b ? (now >= a && now <= b) : (now >= a || now <= b);
                return in ? null : "time window " + from + "-" + to;
            }
            case BATTERY_MIN: {
                int min = (int) TriggerType.parse(c.optString("min", "0"), 0);
                int level = batteryLevel(ctx);
                return level >= min ? null : "battery " + level + "% < " + min + "%";
            }
            case CHARGING: {
                boolean want = "yes".equals(c.optString("state", "yes"));
                return isCharging(ctx) == want ? null : ("charging=" + want);
            }
            case SCREEN: {
                boolean on = isScreenOn(ctx);
                boolean want = "on".equals(c.optString("state", "on"));
                return on == want ? null : "screen " + (on ? "on" : "off");
            }
            case WIFI_SSID: {
                String ssid = currentSsid(ctx);
                String want = c.optString("ssid", "").trim();
                if (ssid == null) return "not on Wi-Fi";
                return ssid.equalsIgnoreCase(want) ? null : "wifi " + ssid;
            }
            case NETWORK: {
                String want = c.optString("transport", "any");
                String have = currentTransport(ctx);
                return "any".equals(want) || want.equals(have) ? null : "network " + have;
            }
            case FOREGROUND_APP: {
                String pkg = currentForegroundPackage(ctx);
                String want = c.optString("package", "").trim();
                if (pkg == null) return "foreground app unknown";
                return want.equals(pkg) ? null : "foreground " + pkg;
            }
            case HEADPHONES: {
                boolean want = "yes".equals(c.optString("state", "yes"));
                return hasHeadphones(ctx) == want ? null : ("headphones=" + want);
            }
            case DND: {
                boolean on = isDndOn(ctx);
                boolean want = "on".equals(c.optString("state", "on"));
                return on == want ? null : ("dnd=" + (on ? "on" : "off"));
            }
            case LOCATION: {
                double lat = TriggerType.parse(c.optString("lat", "0"), 0);
                double lng = TriggerType.parse(c.optString("lng", "0"), 0);
                double radius = TriggerType.parse(c.optString("radius_m", "200"), 200);
                Location loc = lastLocation(ctx);
                if (loc == null) return "location unknown";
                float[] d = new float[1];
                Location.distanceBetween(lat, lng, loc.getLatitude(), loc.getLongitude(), d);
                return d[0] <= radius ? null : ((int) d[0]) + "m away";
            }
            default:
                return null;
        }
    }

    // ---------- shared system state helpers ----------

    public static boolean dayMatches(String daysCsv, long when) {
        if (daysCsv == null || daysCsv.trim().isEmpty()) return true;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(when);
        int iso = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
        for (String s : daysCsv.split(",")) {
            try {
                if (Integer.parseInt(s.trim()) == iso) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static int minutesNow() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    public static int parseHm(String hm) {
        try {
            String[] p = hm.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    public static int batteryLevel(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return scale > 0 ? level * 100 / scale : level;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isCharging(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return false;
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isScreenOn(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isUnlocked(Context ctx) {
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && !km.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    public static String currentSsid(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network n = cm.getActiveNetwork();
            if (n == null) return null;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return null;
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null || ssid.contains("unknown")) return null;
            return ssid.replace("\"", "");
        } catch (Exception e) {
            return null;
        }
    }

    public static String currentTransport(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "none";
            Network n = cm.getActiveNetwork();
            if (n == null) return "none";
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return "none";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "mobile";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            return "other";
        } catch (Exception e) {
            return "none";
        }
    }

    public static boolean hasHeadphones(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isDndOn(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (Exception e) {
            return false;
        }
    }

    public static String currentForegroundPackage(Context ctx) {
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents events = usm.queryEvents(now - 10_000, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    public static Location lastLocation(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }
}
