package com.example.screenshotmover.trigger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.example.screenshotmover.MainActivity;
import com.example.screenshotmover.R;

import java.util.HashSet;
import java.util.Set;

/** Resident service: watches system state and turns it into trigger events. */
public class TriggerService extends Service {

    public static final String CHANNEL_ID = "microdroid_triggers";
    private static final int NOTIF_ID = 4711;

    private BroadcastReceiver batteryReceiver;
    private BroadcastReceiver screenReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;
    private SensorManager sensors;
    private SensorEventListener sensorListener;
    private Handler handler;
    private Runnable usagePoller;
    private Runnable calendarPoller;

    private String lastTransport = "";
    private String lastSsid;
    private String lastForeground;
    private long lastShake;
    private long lastFlip;
    private long lastProximity;
    private boolean proximityNear;
    private final Set<Long> firedCalendar = new HashSet<>();

    public static void start(Context ctx) {
        try {
            ctx.getApplicationContext().startForegroundService(new Intent(ctx, TriggerService.class));
        } catch (Exception ignored) {}
    }

    public static void stop(Context ctx) {
        try {
            ctx.getApplicationContext().stopService(new Intent(ctx, TriggerService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, notification());
        addOverlay();
        registerBattery();
        registerScreen();
        registerNetwork();
        registerSensors();
        handler = new Handler(Looper.getMainLooper());
        startPollers();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        try { if (batteryReceiver != null) unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
        try { if (sensors != null && sensorListener != null) sensors.unregisterListener(sensorListener); } catch (Exception ignored) {}
        if (handler != null) {
            if (usagePoller != null) handler.removeCallbacks(usagePoller);
            if (calendarPoller != null) handler.removeCallbacks(calendarPoller);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- notification ----------

    private void createChannel() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Automation triggers",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Watches system events while trigger automations are enabled.");
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {}
    }

    private Notification notification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Microdroid triggers active")
                .setContentText("Watching system events for your automations")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ---------- system listeners ----------

    private View overlay;

    /** 1px invisible overlay: exempts the app from background activity-start limits (launchApp/openUrl). */
    private void addOverlay() {
        try {
            if (!Settings.canDrawOverlays(this)) return;
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            overlay = new View(this);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(overlay, lp);
        } catch (Exception ignored) {
            overlay = null;
        }
    }

    private void removeOverlay() {
        try {
            if (overlay != null) {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) wm.removeView(overlay);
            }
        } catch (Exception ignored) {}
        overlay = null;
    }

    private void registerBattery() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                int status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                int pct = scale > 0 ? level * 100 / scale : level;
                TriggerEngine.dispatch(TriggerService.this,
                        new Event("battery", "level", String.valueOf(pct), "charging", charging ? "1" : "0"));
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void registerScreen() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    TriggerEngine.dispatch(TriggerService.this, new Event("screen", "action", "on"));
                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    TriggerEngine.dispatch(TriggerService.this, new Event("screen", "action", "off"));
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
    }

    private void registerNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { onNetwork(); }
                @Override public void onLost(Network network) { onNetwork(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { onNetwork(); }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    private void onNetwork() {
        String transport = ConstraintEvaluator.currentTransport(this);
        if (!transport.equals(lastTransport)) {
            lastTransport = transport;
            TriggerEngine.dispatch(this, new Event("network", "transport", transport));
        }
        String ssid = ConstraintEvaluator.currentSsid(this);
        if ("wifi".equals(transport) && ssid != null && !ssid.equals(lastSsid)) {
            lastSsid = ssid;
            TriggerEngine.dispatch(this, new Event("wifi", "action", "connected", "ssid", ssid));
        } else if (!"wifi".equals(transport) && lastSsid != null) {
            TriggerEngine.dispatch(this, new Event("wifi", "action", "disconnected", "ssid", lastSsid));
            lastSsid = null;
        }
    }

    private void registerSensors() {
        try {
            sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sensors == null) return;
            sensorListener = new SensorEventListener() {
                @Override public void onSensorChanged(SensorEvent e) {
                    if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                        float x = e.values[0], y = e.values[1], z = e.values[2];
                        float g = (float) Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
                        long now = System.currentTimeMillis();
                        if (g > 2.4f && now - lastShake > 2500) {
                            lastShake = now;
                            TriggerEngine.dispatch(TriggerService.this, new Event("sensor", "gesture", "shake"));
                        } else if (z < -8.5f && now - lastFlip > 5000) {
                            lastFlip = now;
                            TriggerEngine.dispatch(TriggerService.this, new Event("sensor", "gesture", "flip"));
                        }
                    } else if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                        boolean near = e.values.length > 0 && e.values[0] < e.sensor.getMaximumRange();
                        long now = System.currentTimeMillis();
                        if (near != proximityNear && now - lastProximity > 1500) {
                            proximityNear = near;
                            lastProximity = now;
                            TriggerEngine.dispatch(TriggerService.this,
                                    new Event("sensor", "gesture", "proximity", "near", near ? "1" : "0"));
                        }
                    }
                }

                @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };
            Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) sensors.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_NORMAL);
            Sensor prox = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (prox != null) sensors.registerListener(sensorListener, prox, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception ignored) {}
    }

    // ---------- pollers ----------

    private void startPollers() {
        usagePoller = new Runnable() {
            @Override public void run() {
                pollForegroundApp();
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(usagePoller, 3000);

        calendarPoller = new Runnable() {
            @Override public void run() {
                pollCalendar();
                handler.postDelayed(this, 60_000);
            }
        };
        handler.postDelayed(calendarPoller, 5000);
    }

    private void pollForegroundApp() {
        try {
            if (MicrodroidAccessibilityService.isConnected()) return;
            String pkg = ConstraintEvaluator.currentForegroundPackage(this);
            if (pkg == null || pkg.equals(getPackageName())) {
                if (pkg != null) lastForeground = pkg;
                return;
            }
            if (lastForeground == null) {
                lastForeground = pkg;
                return;
            }
            if (!pkg.equals(lastForeground)) {
                String old = lastForeground;
                lastForeground = pkg;
                TriggerEngine.dispatch(this, new Event("app", "action", "opened", "package", pkg));
                TriggerEngine.dispatch(this, new Event("app", "action", "closed", "package", old));
            }
        } catch (Exception ignored) {}
    }

    private void pollCalendar() {
        try {
            long now = System.currentTimeMillis();
            long to = now + 60_000;
            String[] proj = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END};
            Cursor c = getContentResolver().query(
                    CalendarContract.Instances.CONTENT_URI.buildUpon()
                            .appendPath(String.valueOf(now)).appendPath(String.valueOf(to)).build(),
                    proj, null, null, null);
            if (c == null) return;
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String title = c.getString(1);
                    long begin = c.getLong(2);
                    long end = c.getLong(3);
                    if (begin >= now && begin < to && firedCalendar.add(id * 2)) {
                        TriggerEngine.dispatch(this, new Event("calendar", "when", "starts",
                                "title", title == null ? "" : title));
                    }
                    if (end >= now && end < to && firedCalendar.add(id * 2 + 1)) {
                        TriggerEngine.dispatch(this, new Event("calendar", "when", "ends",
                                "title", title == null ? "" : title));
                    }
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {}
    }
}
