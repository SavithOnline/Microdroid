package com.example.screenshotmover.trigger;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.screenshotmover.MainActivity;
import com.example.screenshotmover.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

/** Status + grant buttons for every special access the trigger engine can use. */
public class PermissionCenterActivity extends AppCompatActivity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_permissions);
        MaterialToolbar toolbar = findViewById(R.id.toolbar_perms);
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.perm_list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        render();
    }

    private void render() {
        list.removeAllViews();
        add(R.string.perm_all_files, R.string.perm_all_files_sub,
                Environment.isExternalStorageManager(),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        add(R.string.perm_battery, R.string.perm_battery_sub,
                isIgnoringBattery(),
                () -> {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                });

        add(R.string.perm_notif_access, R.string.perm_notif_access_sub,
                hasNotificationAccess(),
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        add(R.string.perm_accessibility, R.string.perm_accessibility_sub,
                hasAccessibility(),
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        add(R.string.perm_overlay, R.string.perm_overlay_sub,
                Settings.canDrawOverlays(this),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        add(R.string.perm_usage, R.string.perm_usage_sub,
                hasUsageAccess(),
                () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        add(R.string.perm_location, R.string.perm_location_sub,
                granted(Manifest.permission.ACCESS_FINE_LOCATION),
                () -> ask(Manifest.permission.ACCESS_FINE_LOCATION));

        add(R.string.perm_bg_location, R.string.perm_bg_location_sub,
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                () -> ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION));

        add(R.string.perm_sms, R.string.perm_sms_sub,
                granted(Manifest.permission.RECEIVE_SMS),
                () -> ask(Manifest.permission.RECEIVE_SMS));

        add(R.string.perm_phone, R.string.perm_phone_sub,
                granted(Manifest.permission.READ_PHONE_STATE),
                () -> ask(Manifest.permission.READ_PHONE_STATE));

        add(R.string.perm_calendar, R.string.perm_calendar_sub,
                granted(Manifest.permission.READ_CALENDAR),
                () -> ask(Manifest.permission.READ_CALENDAR));

        add(R.string.perm_write_settings, R.string.perm_write_settings_sub,
                Settings.System.canWrite(this),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));

        add(R.string.perm_dnd, R.string.perm_dnd_sub,
                hasDndAccess(),
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));

        if (Build.VERSION.SDK_INT >= 31) {
            add(R.string.perm_exact_alarms, R.string.perm_exact_alarms_sub,
                    canExactAlarms(),
                    () -> startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName()))));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(R.string.perm_notifications, R.string.perm_notifications_sub,
                    granted(Manifest.permission.POST_NOTIFICATIONS),
                    () -> ask(Manifest.permission.POST_NOTIFICATIONS));
        }
    }

    // ---------- row builder ----------

    private void add(int titleRes, int subtitleRes, boolean granted, Runnable action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_permission, list, false);
        TextView t = row.findViewById(R.id.tv_perm_title);
        TextView s = row.findViewById(R.id.tv_perm_sub);
        TextView state = row.findViewById(R.id.tv_perm_state);
        MaterialButton btn = row.findViewById(R.id.btn_perm_action);
        t.setText(titleRes);
        s.setText(subtitleRes);
        state.setText(granted ? R.string.perm_granted : R.string.perm_not_granted);
        state.setTextColor(ContextCompat.getColor(this, granted ? R.color.md_success : R.color.md_danger));
        btn.setText(granted ? R.string.perm_open : R.string.perm_grant);
        btn.setOnClickListener(v -> action.run());
        list.addView(row);
    }

    // ---------- status helpers ----------

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void ask(String permission) {
        try {
            requestPermissions(new String[]{permission}, 1001);
        } catch (Exception e) {
            toast("Could not request: " + permission);
        }
    }

    private boolean isIgnoringBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasNotificationAccess() {
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasAccessibility() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(getPackageName())
                    && enabled.contains(MicrodroidAccessibilityService.class.getSimpleName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasDndAccess() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationPolicyAccessGranted();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canExactAlarms() {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            return am == null || am.canScheduleExactAlarms();
        } catch (Exception e) {
            return true;
        }
    }

    private void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
