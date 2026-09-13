package com.example.screenshotmover;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.screenshotmover.automation.Automation;
import com.example.screenshotmover.core.MicrodroidReceiver;
import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

/** Microdroid home: minimal Material3 automation list. */
public class MainActivity extends AppCompatActivity implements AutomationAdapter.Listener {

    private AutomationAdapter adapter;
    private View permBanner;

    private static final int[] INTERVAL_CHOICES = {15, 60, 360, 720, 1440};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.migrateOnce(this);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onMenu);

        permBanner = findViewById(R.id.perm_banner);
        findViewById(R.id.btn_grant).setOnClickListener(v -> requestAllFiles());

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AutomationAdapter(items(), this);
        list.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> {
            Intent i = new Intent(this, EditActivity.class);
            i.putExtra(EditActivity.EXTRA_ID, (String) null);
            i.putExtra(EditActivity.EXTRA_SOURCE, PluginManager.TEMPLATE);
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private List<AutomationAdapter.Item> items() {
        List<AutomationAdapter.Item> out = new ArrayList<>();
        for (Automation a : Store.allAutomations(this)) out.add(new AutomationAdapter.Item(a));
        return out;
    }

    private void refresh() {
        permBanner.setVisibility(hasAllFiles() ? View.GONE : View.VISIBLE);
        adapter.setItems(items());
    }

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_start_all) {
            Intent i = new Intent(MicrodroidReceiver.ACTION_START_SCHEDULER);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            toast("Scheduler ON");
            refresh();
            return true;
        }
        if (id == R.id.menu_stop_all) {
            Intent i = new Intent(MicrodroidReceiver.ACTION_STOP_SCHEDULER);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            toast("Scheduler OFF");
            refresh();
            return true;
        }
        if (id == R.id.menu_about) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("PC control (adb)")
                    .setMessage(
                            "RUN:\nadb shell am broadcast -a " + MicrodroidReceiver.ACTION_RUN
                            + " -n " + getPackageName() + "/.core.MicrodroidReceiver --es automation_id screenshots\n\n"
                            + "IMPORT .java:\n... ACTION_PLUGIN_IMPORT --es path /storage/emulated/0/Download/x.java\n\n"
                            + "All commands need explicit -n on Android 8+.")
                    .setPositiveButton("OK", null)
                    .show();
            return true;
        }
        return false;
    }

    // ---- adapter callbacks: broadcasts keep scheduler/adb as single source of truth ----

    @Override
    public void onRun(String id) {
        Intent i = new Intent(MicrodroidReceiver.ACTION_RUN);
        i.setPackage(getPackageName());
        i.putExtra(MicrodroidReceiver.EXTRA_ID, id);
        sendBroadcast(i);
        toast(id + " started…");
    }

    @Override
    public void onToggle(String id, boolean on) {
        Intent i = new Intent(on ? MicrodroidReceiver.ACTION_ENABLE : MicrodroidReceiver.ACTION_DISABLE);
        i.setPackage(getPackageName());
        i.putExtra(MicrodroidReceiver.EXTRA_ID, id);
        sendBroadcast(i);
        refresh();
    }

    @Override
    public void onInterval(String id) {
        String[] labels = {"15 min", "1 hour", "6 hours", "12 hours", "24 hours"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("Run every…")
                .setItems(labels, (d, which) -> {
                    Intent i = new Intent(MicrodroidReceiver.ACTION_SET_INTERVAL);
                    i.setPackage(getPackageName());
                    i.putExtra(MicrodroidReceiver.EXTRA_ID, id);
                    i.putExtra(MicrodroidReceiver.EXTRA_INTERVAL, INTERVAL_CHOICES[which]);
                    sendBroadcast(i);
                    refresh();
                })
                .show();
    }

    @Override
    public void onMore(String id, boolean isScript) {
        String[] options = isScript
                ? new String[]{"View log", "Edit source", "Remove"}
                : new String[]{"View log"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(id)
                .setItems(options, (d, which) -> {
                    String sel = options[which];
                    if ("View log".equals(sel)) {
                        Intent i = new Intent(this, LogActivity.class);
                        i.putExtra(LogActivity.EXTRA_ID, id);
                        startActivity(i);
                    } else if ("Edit source".equals(sel)) {
                        Intent i = new Intent(this, EditActivity.class);
                        i.putExtra(EditActivity.EXTRA_ID, id);
                        startActivity(i);
                    } else if ("Remove".equals(sel)) {
                        confirmRemove(id);
                    }
                })
                .show();
    }

    private void confirmRemove(String id) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove " + id + "?")
                .setMessage("Deletes its source, schedule and log. Built-ins cannot be removed.")
                .setPositiveButton("Remove", (d, w) -> {
                    Store.setEnabled(this, id, false);
                    PluginManager.remove(this, id);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
        return true;
    }

    void requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            toast("Already granted");
        }
    }

    void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }
}
