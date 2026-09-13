package com.example.screenshotmover.automation;

import android.content.Context;

/**
 * One automation module in Microdroid.
 *
 * To add a new automation (3 steps):
 *  1. Create a class implementing this interface.
 *  2. Register it in Automations.all().
 *  3. Rebuild — UI, scheduler and adb API pick it up automatically.
 */
public interface Automation {
    /** Stable id used in adb (e.g. "screenshots"). Lowercase, no spaces. */
    String id();
    /** Short display name. */
    String name();
    /** One-line description shown in UI. */
    String description();
    /**
     * Do the work. Runs on a background thread.
     * @return human-readable status (shown in UI + stored in prefs).
     */
    String run(Context ctx);
}
