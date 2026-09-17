package com.example.screenshotmover.trigger;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Optional accessibility service giving scripts tap/swipe/type/find control over other apps. */
public class MicrodroidAccessibilityService extends AccessibilityService {

    private static volatile MicrodroidAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastPackage;

    public static MicrodroidAccessibilityService get() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
            CharSequence pkg = event.getPackageName();
            if (pkg == null) return;
            String name = pkg.toString();
            if (name.equals(getPackageName())) return;
            if (lastPackage == null) {
                lastPackage = name;
                return;
            }
            if (!name.equals(lastPackage)) {
                String old = lastPackage;
                lastPackage = name;
                TriggerEngine.dispatch(this, new Event("app", "action", "opened", "package", name));
                TriggerEngine.dispatch(this, new Event("app", "action", "closed", "package", old));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}

    // ---------- script-facing operations (safe to call from background threads) ----------

    public boolean tap(final int x, final int y) {
        Boolean r = runMain(() -> {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
            return dispatchGesture(g, null, null);
        });
        return r != null && r;
    }

    public boolean swipe(final int x1, final int y1, final int x2, final int y2, final int durationMs) {
        Boolean r = runMain(() -> {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(50, durationMs))).build();
            return dispatchGesture(g, null, null);
        });
        return r != null && r;
    }

    public boolean typeText(final String text) {
        Boolean r = runMain(() -> {
            AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node == null) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                node = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
            if (node == null) return false;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text == null ? "" : text);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        });
        return r != null && r;
    }

    public boolean pressBack() {
        Boolean r = runMain(() -> performGlobalAction(GLOBAL_ACTION_BACK));
        return r != null && r;
    }

    public boolean home() {
        Boolean r = runMain(() -> performGlobalAction(GLOBAL_ACTION_HOME));
        return r != null && r;
    }

    public boolean scroll(final boolean forward) {
        Boolean r = runMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo scrollable = findScrollable(root);
            if (scrollable == null) return false;
            return scrollable.performAction(forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        });
        return r != null && r;
    }

    public boolean clickText(final String text) {
        Boolean r = runMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo node = findByText(root, text, false);
            if (node == null) return false;
            AccessibilityNodeInfo clickable = node;
            while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
            if (clickable == null) return false;
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        });
        return r != null && r;
    }

    public boolean findText(final String text) {
        Boolean r = runMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root != null && findByText(root, text, false) != null;
        });
        return r != null && r;
    }

    public String currentApp() {
        String r = runMain(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return "";
            return root.getPackageName().toString();
        });
        return r == null ? "" : r;
    }

    // ---------- helpers ----------

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(node);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 500) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            if (n.isScrollable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return null;
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo node, String text, boolean exact) {
        if (text == null || text.isEmpty()) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(node);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 800) {
            AccessibilityNodeInfo n = queue.poll();
            if (n == null) continue;
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String hay = (t == null ? "" : t.toString()) + " " + (d == null ? "" : d.toString());
            if (exact ? hay.trim().equals(text) : hay.contains(text)) return n;
            for (int i = 0; i < n.getChildCount(); i++) queue.add(n.getChild(i));
        }
        return null;
    }

    private <T> T runMain(java.util.concurrent.Callable<T> task) {
        final AtomicReference<T> ref = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                ref.set(task.call());
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }
}
