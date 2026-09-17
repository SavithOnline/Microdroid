package com.example.screenshotmover.trigger;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Build;

import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;

import org.json.JSONObject;

import java.util.List;

/** Registers platform proximity alerts for LOCATION triggers (no Play Services dependency). */
public final class GeofenceTrigger {
    private GeofenceTrigger() {}

    public static final String ACTION_GEOFENCE = "com.microdroid.ACTION_GEOFENCE";
    public static final String EXTRA_REQ = "req";
    private static final int BASE_REQUEST = 4100;

    @android.annotation.SuppressLint("MissingPermission")
    public static void sync(Context ctx) {
        Context app = ctx.getApplicationContext();
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        for (String id : PluginManager.scriptIds(app)) {
            for (int i = 0; i < 32; i++) {
                PendingIntent old = pendingIntent(app, id, i, false);
                if (old != null) {
                    try {
                        lm.removeProximityAlert(old);
                    } catch (Exception ignored) {}
                }
            }
        }

        for (String id : PluginManager.scriptIds(app)) {
            if (!Store.isEnabled(app, id)) continue;
            List<JSONObject> triggers = TriggerStore.triggers(app, id);
            for (int i = 0; i < triggers.size(); i++) {
                JSONObject cfg = triggers.get(i);
                if (!TriggerType.LOCATION.id.equals(cfg.optString("type", ""))) continue;
                double lat = TriggerType.parse(cfg.optString("lat", "0"), 0);
                double lng = TriggerType.parse(cfg.optString("lng", "0"), 0);
                float radius = (float) TriggerType.parse(cfg.optString("radius_m", "200"), 200);
                if (radius < 50) radius = 50;
                if (lat == 0 && lng == 0) continue;
                try {
                    lm.addProximityAlert(lat, lng, radius, -1, pendingIntent(app, id, i, true));
                } catch (Exception ignored) {}
            }
        }
    }

    private static PendingIntent pendingIntent(Context ctx, String id, int index, boolean create) {
        Intent i = new Intent(ctx, TriggerReceiver.class);
        i.setAction(ACTION_GEOFENCE);
        i.putExtra(TriggerStore.class.getName(), id + "#" + index);
        i.setData(android.net.Uri.parse("microdroid://geo/" + id + "/" + index));
        int flags = create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, BASE_REQUEST + index, i, flags);
    }
}
