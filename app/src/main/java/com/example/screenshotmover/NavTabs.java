package com.example.screenshotmover;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/** Wires the bottom bar to the four root tabs; existing tab activities are reused. */
public final class NavTabs {
    private NavTabs() {}

    public static void bind(final Activity activity, final int currentItemId) {
        BottomNavigationView nav = activity.findViewById(R.id.bottom_nav);
        if (nav == null) return;
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == currentItemId) return true;
            Class<?> target = target(id);
            if (target == null) return false;
            start(activity, target);
            // false = "do not select this item": this screen keeps its own tab highlighted
            // while the target screen fades in with the tapped tab, so the bar never bounces.
            return false;
        });
        nav.setSelectedItemId(currentItemId);
    }

    /** Switch to a root tab with a short crossfade instead of the system zoom/slide. */
    public static void start(Activity activity, Class<?> target) {
        Intent i = new Intent(activity, target);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(i);
        applyEnterTransition(activity);
    }

    /**
     * The launch override above only covers freshly created activities; a reused
     * instance (REORDER_TO_FRONT) is animated by the system. Re-applying it here on
     * every resume keeps the tab switch a fade instead of the system slide.
     */
    public static void applyEnterTransition(Activity activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,
                    R.anim.tab_fade_in, R.anim.tab_hold);
        } else {
            activity.overridePendingTransition(R.anim.tab_fade_in, R.anim.tab_hold);
        }
    }

    /** Re-apply the tab this activity belongs to (call from onResume). */
    public static void sync(Activity activity, int currentItemId) {
        final BottomNavigationView nav = activity.findViewById(R.id.bottom_nav);
        if (nav == null) return;
        nav.post(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()
                    && nav.getSelectedItemId() != currentItemId) {
                nav.setSelectedItemId(currentItemId);
            }
        });
    }

    private static Class<?> target(int id) {
        if (id == R.id.nav_scripts) return MainActivity.class;
        if (id == R.id.nav_chat) return com.example.screenshotmover.chat.ChatActivity.class;
        if (id == R.id.nav_logs) return LogsActivity.class;
        if (id == R.id.nav_settings) return SettingsActivity.class;
        return null;
    }
}
