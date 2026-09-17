package com.example.screenshotmover.chat;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.screenshotmover.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Provider/base URL/model/API-key dialog, shared by the Chat and Settings tabs. */
public final class LlmSettingsDialog {
    private LlmSettingsDialog() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public static void show(final AppCompatActivity activity) {
        View v = activity.getLayoutInflater().inflate(R.layout.dialog_llm_config, null);
        final MaterialAutoCompleteTextView actProvider = v.findViewById(R.id.act_provider);
        final TextInputEditText etBase = v.findViewById(R.id.et_llm_base);
        final TextInputEditText etModel = v.findViewById(R.id.et_llm_model);
        final TextInputEditText etKey = v.findViewById(R.id.et_llm_key);
        final TextInputLayout tilKey = v.findViewById(R.id.til_llm_key);
        final TextView warn = v.findViewById(R.id.tv_http_warning);

        final LlmProvider[] providers = LlmProvider.values();
        String[] labels = new String[providers.length];
        for (int i = 0; i < providers.length; i++) labels[i] = providers[i].label;
        actProvider.setSimpleItems(labels);

        final LlmProvider current = LlmProvider.byId(LlmConfig.providerId(activity));
        final String[] selected = {current.id};
        actProvider.setText(current.label, false);
        etBase.setText(LlmConfig.baseUrl(activity));
        etModel.setText(LlmConfig.model(activity));
        tilKey.setHint(ApiKeyStore.has(activity, current.id) ? "API key (stored - leave blank to keep)" : "API key");

        actProvider.setOnItemClickListener((parent, view, pos, rowId) -> {
            LlmProvider p = providers[pos];
            selected[0] = p.id;
            etBase.setText(LlmConfig.storedBaseUrl(activity, p.id));
            etModel.setText(LlmConfig.storedModel(activity, p.id));
            etKey.setText("");
            tilKey.setHint(ApiKeyStore.has(activity, p.id) ? "API key (stored - leave blank to keep)" : "API key");
        });

        final Runnable warnCheck = () -> warn.setVisibility(
                etBase.getText().toString().trim().toLowerCase().startsWith("http://")
                        ? View.VISIBLE : View.GONE);
        etBase.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { warnCheck.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        warnCheck.run();

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Provider settings")
                .setView(v)
                .setPositiveButton("Save", null)
                .setNeutralButton("Test", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                String base = etBase.getText().toString().trim();
                String model = etModel.getText().toString().trim();
                if (base.isEmpty() || model.isEmpty()) {
                    toast(activity, "Base URL and model are required");
                    return;
                }
                String pid = selected[0];
                String key = etKey.getText().toString().trim();
                if (!key.isEmpty()) {
                    if (!ApiKeyStore.put(activity, pid, key)) {
                        toast(activity, "Could not encrypt/store the key");
                        return;
                    }
                } else if (!ApiKeyStore.has(activity, pid)) {
                    toast(activity, "Enter an API key");
                    return;
                }
                boolean providerChanged = !pid.equals(LlmConfig.providerId(activity));
                LlmConfig.setProviderId(activity, pid);
                LlmConfig.setBaseUrl(activity, base);
                LlmConfig.setModel(activity, model);
                if (base.toLowerCase().startsWith("http://")) {
                    toast(activity, "Warning: http:// traffic is unencrypted");
                }
                if (providerChanged) {
                    ChatStore.clear(activity);
                    LlmConfig.markChatReset(activity);
                    toast(activity, "Provider changed - started a new chat");
                }
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x -> {
                String base = etBase.getText().toString().trim();
                String model = etModel.getText().toString().trim();
                if (base.isEmpty() || model.isEmpty()) {
                    toast(activity, "Fill base URL and model first");
                    return;
                }
                String key = etKey.getText().toString().trim();
                if (key.isEmpty()) key = ApiKeyStore.get(activity, selected[0]);
                if (key == null || key.isEmpty()) {
                    toast(activity, "Enter an API key to test");
                    return;
                }
                testConnection(activity, new LlmConfig.Config(selected[0], base, model, key,
                        UUID.randomUUID().toString(), Providers.wireOf(activity, selected[0])));
            });
        });
        dialog.show();
    }

    /** Edit one specific provider (base URL, model, key) without changing the active provider. */
    public static void edit(final AppCompatActivity activity, final String providerId, final Runnable onSaved) {
        View v = activity.getLayoutInflater().inflate(R.layout.dialog_llm_config, null);
        View providerRow = v.findViewById(R.id.til_provider);
        if (providerRow != null) providerRow.setVisibility(View.GONE);
        final TextInputEditText etBase = v.findViewById(R.id.et_llm_base);
        final TextInputEditText etModel = v.findViewById(R.id.et_llm_model);
        final TextInputEditText etKey = v.findViewById(R.id.et_llm_key);
        final TextInputLayout tilKey = v.findViewById(R.id.til_llm_key);
        final TextView warn = v.findViewById(R.id.tv_http_warning);

        etBase.setText(Providers.baseUrl(activity, providerId));
        etModel.setText(Providers.model(activity, providerId));
        tilKey.setHint(ApiKeyStore.has(activity, providerId) ? "API key (stored - leave blank to keep)" : "API key");

        final Runnable warnCheck = () -> warn.setVisibility(
                etBase.getText().toString().trim().toLowerCase().startsWith("http://")
                        ? View.VISIBLE : View.GONE);
        etBase.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { warnCheck.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        warnCheck.run();

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Edit " + Providers.labelOf(activity, providerId))
                .setView(v)
                .setPositiveButton("Save", null)
                .setNeutralButton("Test", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                String base = etBase.getText().toString().trim();
                String model = etModel.getText().toString().trim();
                if (base.isEmpty() || model.isEmpty()) {
                    toast(activity, "Base URL and model are required");
                    return;
                }
                String key = etKey.getText().toString().trim();
                if (!key.isEmpty() && !ApiKeyStore.put(activity, providerId, key)) {
                    toast(activity, "Could not encrypt/store the key");
                    return;
                }
                Providers.saveConfig(activity, providerId, base, model);
                toast(activity, "Saved");
                if (onSaved != null) onSaved.run();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x -> {
                String base = etBase.getText().toString().trim();
                String model = etModel.getText().toString().trim();
                if (base.isEmpty() || model.isEmpty()) {
                    toast(activity, "Fill base URL and model first");
                    return;
                }
                String key = etKey.getText().toString().trim();
                if (key.isEmpty()) key = ApiKeyStore.get(activity, providerId);
                if (key == null || key.isEmpty()) {
                    toast(activity, "Enter an API key to test");
                    return;
                }
                testConnection(activity, new LlmConfig.Config(providerId, base, model, key,
                        UUID.randomUUID().toString(), Providers.wireOf(activity, providerId)));
            });
        });
        dialog.show();
    }

    private static void testConnection(final Activity activity, final LlmConfig.Config cfg) {
        toast(activity, "Testing connection...");
        IO.execute(() -> {
            final StringBuilder text = new StringBuilder();
            final String[] err = {null};
            final CountDownLatch done = new CountDownLatch(1);
            List<ChatMessage> ping = new ArrayList<>();
            ping.add(new ChatMessage(ChatMessage.ROLE_USER, "Reply with the single word OK."));
            LlmClient client = LlmClient.create(cfg);
            client.streamChat("You are a connectivity test.", ping, new ArrayList<>(),
                    new LlmClient.Listener() {
                        @Override public void onText(String delta) { text.append(delta); }
                        @Override public void onToolCalls(List<ToolCall> calls) {}
                        @Override public void onError(String message) { err[0] = message; }
                        @Override public void onDone() { done.countDown(); }
                    });
            try {
                done.await(2, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            final String msg = err[0] != null
                    ? "Failed: " + err[0]
                    : "Connected. Reply: " + Http.truncate(text.toString().trim());
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Connection test")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private static void toast(Activity activity, String m) {
        try {
            Toast.makeText(activity, m, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }
}
