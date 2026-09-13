package com.example.screenshotmover;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.PluginManager;

/** Last status + run log for one automation. */
public class LogActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "automation_id";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_log);
        com.google.android.material.appbar.MaterialToolbar tb = findViewById(R.id.toolbar_log);
        tb.setNavigationOnClickListener(v -> finish());

        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id == null) id = "?";
        setTitle("Log: " + id);

        TextView tvLast = findViewById(R.id.tv_last);
        TextView tvLog = findViewById(R.id.tv_log);

        tvLast.setText("Last: " + Store.getLast(this, id));
        String log = PluginManager.scriptIds(this).contains(id)
                ? PluginManager.getLog(this, id)
                : "(built-in: last status above)";
        tvLog.setText(log);
    }
}
