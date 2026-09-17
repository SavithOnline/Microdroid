package com.example.screenshotmover;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.screenshotmover.plugin.PluginManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Import / create / edit a script plugin.
 * Save compiles on a background thread; compiler errors are shown, nothing is saved on failure.
 */
public class EditActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "plugin_id";
    public static final String EXTRA_SOURCE = "source_prefill";

    private String editingId; // null = new
    private EditText etSource;
    private TextView tvId, tvError;
    private View btnDelete;

    private final ActivityResultLauncher<Intent> pickFile =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != RESULT_OK || res.getData() == null) return;
                Uri uri = res.getData().getData();
                if (uri == null) return;
                try (InputStream in = getContentResolver().openInputStream(uri);
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                    etSource.setText(sb.toString());
                    hideError();
                } catch (Exception e) {
                    showError("Cannot read file: " + e);
                }
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_edit);

        etSource = findViewById(R.id.et_source);
        tvId = findViewById(R.id.tv_id);
        tvError = findViewById(R.id.tv_error);
        btnDelete = findViewById(R.id.btn_delete);

        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.toolbar_edit)).setNavigationOnClickListener(v -> finish());

        editingId = getIntent().getStringExtra(EXTRA_ID);
        String prefill = getIntent().getStringExtra(EXTRA_SOURCE);

        if (editingId != null) {
            tvId.setText(getString(R.string.editing_script, editingId));
            String src = PluginManager.readSource(this, editingId);
            etSource.setText(src == null ? "" : src);
            btnDelete.setVisibility(View.VISIBLE);
        } else {
            tvId.setText(R.string.new_automation_hint);
            etSource.setText(prefill == null ? PluginManager.TEMPLATE : prefill);
            btnDelete.setVisibility(View.GONE);
        }

        findViewById(R.id.btn_import).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/java", "application/octet-stream"});
            pickFile.launch(i);
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        btnDelete.setOnClickListener(v -> confirmDelete());
    }

    private void save() {
        String source = etSource.getText().toString();
        if (source.trim().isEmpty()) {
            showError(getString(R.string.source_empty));
            return;
        }
        toast(getString(R.string.compiling));
        new Thread(() -> {
            try {
                String id = PluginManager.save(EditActivity.this, source, editingId);
                runOnUiThread(() -> {
                    hideError();
                    toast(getString(R.string.saved_script, id));
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                String msg = getString(R.string.compile_error, e);
                runOnUiThread(() -> showError(msg));
            }
        }).start();
    }

    private void confirmDelete() {
        if (editingId == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.delete_script_title, editingId))
                .setMessage(R.string.delete_script_message)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    com.example.screenshotmover.core.Store.setEnabled(this, editingId, false);
                    PluginManager.remove(this, editingId);
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showError(String m) {
        tvError.setText(m);
        tvError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }
}
