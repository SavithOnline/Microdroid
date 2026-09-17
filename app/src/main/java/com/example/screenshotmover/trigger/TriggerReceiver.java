package com.example.screenshotmover.trigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.example.screenshotmover.core.Store;

/** Static receiver for system broadcast triggers and time alarms. */
public class TriggerReceiver extends BroadcastReceiver {

    public static final String ACTION_INTENT_TRIGGER = "com.microdroid.ACTION_INTENT_TRIGGER";
    public static final String ACTION_WEBHOOK = "com.microdroid.ACTION_WEBHOOK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Store.migrateOnce(context);
        final Context app = context.getApplicationContext();
        String a = String.valueOf(intent.getAction());

        if (ScheduleTrigger.ACTION_TRIGGER_ALARM.equals(a)) {
            handleTimeAlarm(app, intent);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) {
            TriggerRuntime.refresh(app);
            TriggerEngine.dispatch(app, new Event("boot"));
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            TriggerRuntime.refresh(app);
            return;
        }
        if (Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            ScheduleTrigger.rescheduleAll(app);
            return;
        }
        if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
            TriggerEngine.dispatch(app, new Event("power", "action", "connected"));
            return;
        }
        if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
            TriggerEngine.dispatch(app, new Event("power", "action", "disconnected"));
            return;
        }
        if (Intent.ACTION_HEADSET_PLUG.equals(a)) {
            boolean plugged = intent.getIntExtra("state", 0) == 1;
            TriggerEngine.dispatch(app, new Event("headset", "state", plugged ? "plugged" : "unplugged"));
            return;
        }
        if (Intent.ACTION_USER_PRESENT.equals(a)) {
            TriggerEngine.dispatch(app, new Event("unlock"));
            return;
        }
        if (Intent.ACTION_MEDIA_BUTTON.equals(a)) {
            TriggerEngine.dispatch(app, new Event("media"));
            return;
        }
        if (Intent.ACTION_PACKAGE_ADDED.equals(a) || Intent.ACTION_PACKAGE_REMOVED.equals(a)
                || Intent.ACTION_PACKAGE_REPLACED.equals(a)) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            String pkg = intent.getData() == null ? "" : intent.getData().getSchemeSpecificPart();
            String action = Intent.ACTION_PACKAGE_REMOVED.equals(a) ? "removed" : "installed";
            TriggerEngine.dispatch(app, new Event("package", "action", action, "package", pkg == null ? "" : pkg));
            return;
        }
        if (ACTION_INTENT_TRIGGER.equals(a)) {
            TriggerEngine.dispatch(app, new Event("broadcast",
                    "action", intent.getStringExtra("trigger_action") == null ? "" : intent.getStringExtra("trigger_action")));
            return;
        }
        if (ACTION_WEBHOOK.equals(a)) {
            Bundle extras = intent.getExtras();
            Event ev = new Event("webhook");
            if (extras != null) {
                for (String k : extras.keySet()) {
                    Object v = extras.get(k);
                    if (v != null) ev.put(k, String.valueOf(v));
                }
            }
            TriggerEngine.dispatch(app, ev);
            return;
        }
        if ("android.provider.Telephony.SMS_RECEIVED".equals(a)) {
            handleSms(app, intent);
            return;
        }
        if ("android.intent.action.PHONE_STATE".equals(a)) {
            handleCall(app, intent);
            return;
        }
        if (GeofenceTrigger.ACTION_GEOFENCE.equals(a)) {
            boolean entering = intent.getBooleanExtra(
                    android.location.LocationManager.KEY_PROXIMITY_ENTERING, true);
            TriggerEngine.dispatch(app, new Event("location",
                    "action", entering ? "enter" : "exit"));
        }
    }

    private void handleTimeAlarm(Context app, Intent intent) {
        String id = intent.getStringExtra(ScheduleTrigger.EXTRA_PLUGIN);
        String at = intent.getStringExtra(ScheduleTrigger.EXTRA_AT);
        String days = intent.getStringExtra(ScheduleTrigger.EXTRA_DAYS);
        int index = intent.getIntExtra(ScheduleTrigger.EXTRA_INDEX, 0);
        if (id == null || id.isEmpty()) return;
        TriggerEngine.dispatch(app, new Event("time", "at", at == null ? "" : at, "days", days == null ? "" : days));
        ScheduleTrigger.scheduleOne(app, id, index, at == null ? "" : at, days == null ? "" : days);
    }

    private void handleSms(Context app, Intent intent) {
        try {
            Object[] pdus = (Object[]) intent.getExtras().get("pdus");
            if (pdus == null || pdus.length == 0) return;
            android.telephony.SmsMessage msg = android.telephony.SmsMessage.createFromPdu((byte[]) pdus[0]);
            TriggerEngine.dispatch(app, new Event("sms",
                    "from", msg.getDisplayOriginatingAddress() == null ? "" : msg.getDisplayOriginatingAddress(),
                    "body", msg.getDisplayMessageBody() == null ? "" : msg.getDisplayMessageBody()));
        } catch (Exception ignored) {}
    }

    private void handleCall(Context app, Intent intent) {
        try {
            String state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE);
            if (android.telephony.TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                TriggerEngine.dispatch(app, new Event("call", "state", "incoming",
                        "number", String.valueOf(intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER))));
            } else if (android.telephony.TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                boolean missed = false; // best-effort: ringing followed by idle without offhook
                TriggerEngine.dispatch(app, new Event("call", "state", missed ? "missed" : "ended"));
            }
        } catch (Exception ignored) {}
    }
}
