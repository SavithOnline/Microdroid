package com.example.screenshotmover;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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
    private TextView empty;
    private MaterialToolbar toolbar;

    private static final int[] INTERVAL_CHOICES = {15, 60, 360, 720, 1440};

    /** Refreshes the list when the receiver changes state (e.g. toggle, interval, import). */
    private final BroadcastReceiver stateChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.migrateOnce(this);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onMenu);
        NavTabs.bind(this, R.id.nav_scripts);

        permBanner = findViewById(R.id.perm_banner);
        findViewById(R.id.btn_grant).setOnClickListener(v -> requestAllFiles());

        empty = findViewById(R.id.tv_empty);

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
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, stateChanged,
                new IntentFilter(MicrodroidReceiver.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(stateChanged);
    }

    @Override
    protected void onResume() {
        super.onResume();
        NavTabs.applyEnterTransition(this);
        NavTabs.sync(this, R.id.nav_scripts);
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
        empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        MenuItem adbImport = toolbar.getMenu().findItem(R.id.menu_adb_import);
        if (adbImport != null) adbImport.setChecked(Store.isAdbImportAllowed(this));
    }

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_chat) {
            NavTabs.start(this, com.example.screenshotmover.chat.ChatActivity.class);
            return true;
        }
        if (id == R.id.menu_permissions) {
            startActivity(new Intent(this, com.example.screenshotmover.trigger.PermissionCenterActivity.class));
            return true;
        }
        if (id == R.id.menu_start_all) {
            Intent i = new Intent(MicrodroidReceiver.ACTION_START_SCHEDULER);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            toast(getString(R.string.scheduler_on_toast));
            refresh();
            return true;
        }
        if (id == R.id.menu_stop_all) {
            Intent i = new Intent(MicrodroidReceiver.ACTION_STOP_SCHEDULER);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            toast(getString(R.string.scheduler_off_toast));
            refresh();
            return true;
        }
        if (id == R.id.menu_adb_import) {
            boolean next = !Store.isAdbImportAllowed(this);
            Store.setAdbImportAllowed(this, next);
            item.setChecked(next);
            toast(getString(R.string.adb_imports_toast, getString(next ? R.string.on : R.string.off)));
            return true;
        }
        if (id == R.id.menu_about) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.menu_pc_control)
                    .setMessage(getString(R.string.pc_control_help,
                            MicrodroidReceiver.ACTION_RUN, getPackageName()))
                    .setPositiveButton(R.string.ok, null)
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
        toast(getString(R.string.run_started, id));
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
        String[] labels = getResources().getStringArray(R.array.interval_labels);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.run_every_title)
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
                ? new String[]{getString(R.string.triggers_title), getString(R.string.view_log),
                        getString(R.string.edit_source), getString(R.string.remove)}
                : new String[]{getString(R.string.view_log)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(id)
                .setItems(options, (d, which) -> {
                    if (isScript && which == 0) {
                        Intent i = new Intent(this, com.example.screenshotmover.trigger.TriggersActivity.class);
                        i.putExtra(com.example.screenshotmover.trigger.TriggersActivity.EXTRA_ID, id);
                        startActivity(i);
                    } else if ((isScript && which == 1) || (!isScript && which == 0)) {
                        Intent i = new Intent(this, LogActivity.class);
                        i.putExtra(LogActivity.EXTRA_ID, id);
                        startActivity(i);
                    } else if (isScript && which == 2) {
                        Intent i = new Intent(this, EditActivity.class);
                        i.putExtra(EditActivity.EXTRA_ID, id);
                        startActivity(i);
                    } else if (isScript && which == 3) {
                        confirmRemove(id);
                    }
                })
                .show();
    }

    private void confirmRemove(String id) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.remove_title, id))
                .setMessage(R.string.remove_message)
                .setPositiveButton(R.string.remove, (d, w) -> {
                    Store.setEnabled(this, id, false);
                    PluginManager.remove(this, id);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    boolean hasAllFiles() {
        return android.os.Environment.isExternalStorageManager();
    }

    void requestAllFiles() {
        if (android.os.Environment.isExternalStorageManager()) {
            toast(getString(R.string.already_granted));
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }
}
