package com.example.screenshotmover;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.screenshotmover.chat.LlmConfig;
import com.example.screenshotmover.chat.Providers;
import com.example.screenshotmover.core.MicrodroidReceiver;
import com.example.screenshotmover.core.Store;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Settings tab: AI provider, permissions, scheduler, adb and about. */
public class SettingsActivity extends AppCompatActivity {

    private TextView llmSummary;
    private TextView schedulerState;
    private MaterialButton startScheduler;
    private MaterialButton stopScheduler;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        NavTabs.bind(this, R.id.nav_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_settings);
        toolbar.setTitle(R.string.settings_title);

        llmSummary = findViewById(R.id.tv_llm_summary);
        schedulerState = findViewById(R.id.tv_scheduler_state);
        startScheduler = findViewById(R.id.btn_scheduler_start);
        stopScheduler = findViewById(R.id.btn_scheduler_stop);
        findViewById(R.id.btn_manage_providers).setOnClickListener(v ->
                startActivity(new Intent(this, ProvidersActivity.class)));
        findViewById(R.id.btn_permissions).setOnClickListener(v ->
                startActivity(new Intent(this, com.example.screenshotmover.trigger.PermissionCenterActivity.class)));

        MaterialSwitch adb = findViewById(R.id.switch_adb);
        adb.setChecked(Store.isAdbImportAllowed(this));
        adb.setOnCheckedChangeListener((btn, checked) -> {
            Store.setAdbImportAllowed(this, checked);
            toast(getString(R.string.adb_imports_toast, getString(checked ? R.string.on : R.string.off)));
        });

        startScheduler.setOnClickListener(v -> {
            send(MicrodroidReceiver.ACTION_START_SCHEDULER);
            Store.setSchedulerOn(this, true);
            updateSchedulerState();
        });
        stopScheduler.setOnClickListener(v -> {
            send(MicrodroidReceiver.ACTION_STOP_SCHEDULER);
            Store.setSchedulerOn(this, false);
            updateSchedulerState();
        });

        findViewById(R.id.btn_pc_control).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_pc_control)
                .setMessage(getString(R.string.pc_control_help_full,
                        MicrodroidReceiver.ACTION_RUN, getPackageName()))
                .setPositiveButton(R.string.ok, null)
                .show());

        TextView version = findViewById(R.id.tv_version);
        String versionName = "";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        version.setText(getString(R.string.microdroid_version, versionName));
    }

    @Override
    protected void onResume() {
        super.onResume();
        NavTabs.applyEnterTransition(this);
        NavTabs.sync(this, R.id.nav_settings);
        llmSummary.setText(getString(R.string.provider_summary,
                Providers.labelOf(this, LlmConfig.providerId(this)),
                LlmConfig.model(this), LlmConfig.baseUrl(this)));
        updateSchedulerState();
    }

    private void updateSchedulerState() {
        boolean on = Store.isSchedulerOn(this);
        schedulerState.setText(on ? R.string.scheduler_on : R.string.scheduler_off);
        schedulerState.setTextColor(ContextCompat.getColor(this, on ? R.color.md_success : R.color.md_danger));
        startScheduler.setEnabled(!on);
        stopScheduler.setEnabled(on);
    }

    private void send(String action) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
