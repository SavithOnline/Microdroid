package com.example.screenshotmover.trigger;

import android.content.Context;

import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;

/** Reconciles resident infrastructure (alarms, service) with the configured triggers. */
public final class TriggerRuntime {
    private TriggerRuntime() {}

    public static void refresh(Context ctx) {
        Context app = ctx.getApplicationContext();
        ScheduleTrigger.rescheduleAll(app);
        GeofenceTrigger.sync(app);
        for (String id : PluginManager.scriptIds(app)) {
            // a script with explicit triggers does not also run on its legacy interval
            if (!TriggerStore.triggers(app, id).isEmpty()) Store.Scheduler.cancel(app, id);
        }
        if (TriggerStore.needsService(app)) TriggerService.start(app);
        else TriggerService.stop(app);
        if (TriggerStore.hasType(app, TriggerType.WEBHOOK)) WebhookServer.start(app);
        else WebhookServer.stop();
        HttpPollTrigger.sync(app);
        SunTrigger.sync(app);
    }
}
