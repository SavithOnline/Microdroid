package com.example.screenshotmover.plugin;

import android.content.Context;

import com.example.screenshotmover.automation.Automation;
import com.example.screenshotmover.core.Store;

/**
 * Adapts a script plugin to the Automation interface.
 * The current source is interpreted on every run (scripts are small; always fresh).
 * Consecutive failures auto-disable after MAX_FAILURES.
 */
public class ScriptAutomationHolder implements Automation {

    private final Context app;
    private final String pluginId;

    public ScriptAutomationHolder(Context ctx, String pluginId) {
        this.app = ctx.getApplicationContext();
        this.pluginId = pluginId;
    }

    @Override public String id() { return pluginId; }

    @Override public String name() {
        return PluginManager.metaName(app, pluginId, pluginId + " (script)");
    }

    @Override public String description() {
        return PluginManager.metaDesc(app, pluginId, "Script plugin. Tap More > Edit to view source.");
    }

    /** True for UI badge. */
    public boolean isScript() { return true; }

    @Override public String run(Context ctx) {
        Context a = ctx.getApplicationContext();
        String source = PluginManager.readSource(a, pluginId);
        if (source == null) return "Error: source missing for " + pluginId;
        try {
            String status = PluginManager.execute(source, new PluginContextImpl(a, pluginId));
            PluginManager.noteSuccess(a, pluginId);
            PluginManager.appendLog(a, pluginId, status);
            return status;
        } catch (Throwable t) {
            String msg = "Script error: " + t;
            if (msg.length() > 300) msg = msg.substring(0, 300);
            PluginManager.appendLog(a, pluginId, msg);
            PluginManager.noteFailure(a, pluginId);
            int fails = PluginManager.failCount(a, pluginId);
            if (fails >= PluginManager.MAX_FAILURES) {
                Store.setEnabled(a, pluginId, false);
                PluginManager.appendLog(a, pluginId,
                        "Auto-disabled after " + fails + " consecutive failures.");
                return msg + " (auto-disabled)";
            }
            return msg;
        }
    }
}
