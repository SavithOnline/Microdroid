package com.example.screenshotmover;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.screenshotmover.chat.ApiKeyStore;
import com.example.screenshotmover.chat.LlmConfig;
import com.example.screenshotmover.chat.LlmSettingsDialog;
import com.example.screenshotmover.chat.Providers;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/** Manage built-in and custom model providers (add, edit, set active, delete). */
public class ProvidersActivity extends AppCompatActivity {

    private final List<Providers.Def> defs = new ArrayList<>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_providers);
        MaterialToolbar toolbar = findViewById(R.id.toolbar_providers);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.providers_list);
        adapter = new Adapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btn_add_provider).setOnClickListener(v -> addProvider());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        defs.clear();
        defs.addAll(Providers.all(this));
        adapter.notifyDataSetChanged();
    }

    private void showActions(final Providers.Def d) {
        boolean active = d.id.equals(LlmConfig.providerId(this));
        final List<String> options = new ArrayList<>();
        if (!active) options.add("Set as active");
        options.add("Edit");
        if (d.custom) options.add("Delete");
        new MaterialAlertDialogBuilder(this)
                .setTitle(d.label)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String sel = options.get(which);
                    if ("Set as active".equals(sel)) {
                        LlmConfig.setProviderId(this, d.id);
                        toast(d.label + " is now active");
                        reload();
                    } else if ("Edit".equals(sel)) {
                        LlmSettingsDialog.edit(this, d.id, this::reload);
                    } else if ("Delete".equals(sel)) {
                        confirmDelete(d);
                    }
                })
                .show();
    }

    private void confirmDelete(final Providers.Def d) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + d.label + "?")
                .setMessage("Removes the provider, its stored API key and settings.")
                .setPositiveButton("Delete", (x, y) -> {
                    Providers.remove(this, d.id);
                    toast("Deleted " + d.label);
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addProvider() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, dp(8));
        TextInputLayout tilLabel = new TextInputLayout(this);
        tilLabel.setHint("Name");
        TextInputEditText etLabel = new TextInputEditText(this);
        tilLabel.addView(etLabel);
        box.addView(tilLabel);

        MaterialAutoCompleteTextView actWire = new MaterialAutoCompleteTextView(this);
        actWire.setSimpleItems(new String[]{"OpenAI-compatible", "Anthropic", "Gemini"});
        TextInputLayout tilWire = new TextInputLayout(this);
        tilWire.setHint("API format");
        tilWire.addView(actWire);
        box.addView(tilWire);

        TextInputLayout tilBase = new TextInputLayout(this);
        tilBase.setHint("Base URL");
        TextInputEditText etBase = new TextInputEditText(this);
        etBase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        tilBase.addView(etBase);
        box.addView(tilBase);

        TextInputLayout tilModel = new TextInputLayout(this);
        tilModel.setHint("Model");
        TextInputEditText etModel = new TextInputEditText(this);
        tilModel.addView(etModel);
        box.addView(tilModel);

        TextInputLayout tilKey = new TextInputLayout(this);
        tilKey.setHint("API key");
        TextInputEditText etKey = new TextInputEditText(this);
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilKey.addView(etKey);
        box.addView(tilKey);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Add provider")
                .setView(scroll)
                .setPositiveButton("Add", (d, w) -> {
                    String label = etLabel.getText().toString().trim();
                    String base = etBase.getText().toString().trim();
                    String model = etModel.getText().toString().trim();
                    String key = etKey.getText().toString().trim();
                    if (label.isEmpty() || base.isEmpty() || model.isEmpty()) {
                        toast("Name, base URL and model are required");
                        return;
                    }
                    String wire = "openai";
                    String wireLabel = actWire.getText().toString().trim();
                    if ("Anthropic".equals(wireLabel)) wire = "anthropic";
                    else if ("Gemini".equals(wireLabel)) wire = "gemini";
                    String id = Providers.add(this, label, wire, base, model);
                    if (!key.isEmpty()) ApiKeyStore.put(this, id, key);
                    toast("Added " + label);
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_provider, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Providers.Def d = defs.get(position);
            boolean active = d.id.equals(LlmConfig.providerId(ProvidersActivity.this));
            h.label.setText(d.label + (d.custom ? "  (" + Providers.wireLabel(d.wire) + ")" : ""));
            h.active.setVisibility(active ? View.VISIBLE : View.GONE);
            h.summary.setText(getString(R.string.provider_endpoint,
                    Providers.baseUrl(ProvidersActivity.this, d.id),
                    Providers.model(ProvidersActivity.this, d.id)));
            h.key.setText(getString(R.string.api_key_status, getString(
                    ApiKeyStore.has(ProvidersActivity.this, d.id)
                            ? R.string.key_stored : R.string.key_not_set)));
            h.itemView.setOnClickListener(v -> showActions(d));
        }

        @Override
        public int getItemCount() {
            return defs.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView label, summary, key, active;

            Holder(@NonNull View v) {
                super(v);
                label = v.findViewById(R.id.tv_provider_label);
                summary = v.findViewById(R.id.tv_provider_summary);
                key = v.findViewById(R.id.tv_provider_key);
                active = v.findViewById(R.id.tv_provider_active);
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }
}
