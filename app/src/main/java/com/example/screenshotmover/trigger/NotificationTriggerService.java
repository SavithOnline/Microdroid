package com.example.screenshotmover.trigger;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Turns posted/removed notifications into trigger events. */
public class NotificationTriggerService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        dispatch(sbn, "posted");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        dispatch(sbn, "removed");
    }

    private void dispatch(StatusBarNotification sbn, String action) {
        try {
            if (sbn == null || sbn.getPackageName() == null) return;
            if (getPackageName().equals(sbn.getPackageName())) return;
            String title = "";
            String text = "";
            Notification n = sbn.getNotification();
            Bundle extras = n == null ? null : n.extras;
            if (extras != null) {
                CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence x = extras.getCharSequence(Notification.EXTRA_TEXT);
                if (t != null) title = t.toString();
                if (x != null) text = x.toString();
            }
            TriggerEngine.dispatch(this, new Event("notification",
                    "action", action,
                    "package", sbn.getPackageName(),
                    "title", title,
                    "text", text));
        } catch (Exception ignored) {}
    }
}
