package com.example.screenshotmover.chat;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.screenshotmover.R;
import com.example.screenshotmover.plugin.PluginManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * In-app chat agent: talks to a user-configured LLM (OpenAI-compatible / Anthropic / Gemini)
 * and can create, edit, delete, run and schedule Microdroid scripts.
 *
 * Two modes: Ask (every mutation needs a tap) and Bypass (applies without asking;
 * deletes and schedule changes still ask).
 */
public class ChatActivity extends AppCompatActivity implements ChatAdapter.Listener {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final List<ChatMessage> history = new ArrayList<>();

    private ChatAdapter adapter;
    private RecyclerView list;
    private EditText input;
    private TextView status;
    private TextView empty;
    private MaterialToolbar toolbar;

    private volatile LlmClient activeClient;
    private volatile boolean stopRequested;
    private volatile boolean running;
    private volatile boolean destroyed;
    private volatile CountDownLatch confirmLatch;
    private volatile boolean confirmResult;

    private long loadedResetAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_chat);

        toolbar = findViewById(R.id.toolbar_chat);
        toolbar.setOnMenuItemClickListener(this::onMenu);
        com.example.screenshotmover.NavTabs.bind(this, com.example.screenshotmover.R.id.nav_chat);

        list = findViewById(R.id.chat_list);
        input = findViewById(R.id.et_chat_input);
        status = findViewById(R.id.tv_chat_status);
        empty = findViewById(R.id.tv_chat_empty);

        adapter = new ChatAdapter(history, this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        history.addAll(ChatStore.load(this));
        loadedResetAt = LlmConfig.chatResetAt(this);
        refreshList();
        updateModeTitle();
        updateEmpty();

        findViewById(R.id.btn_chat_send).setOnClickListener(v -> onSend());
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.example.screenshotmover.NavTabs.applyEnterTransition(this);
        com.example.screenshotmover.NavTabs.sync(this, com.example.screenshotmover.R.id.nav_chat);
        long reset = LlmConfig.chatResetAt(this);
        if (reset > loadedResetAt) {
            loadedResetAt = reset;
            synchronized (history) {
                history.clear();
                history.addAll(ChatStore.load(this));
            }
            refreshList();
            updateEmpty();
        }
        updateModeTitle();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopRequested = true;
        LlmClient c = activeClient;
        if (c != null) c.cancel();
        CountDownLatch latch = confirmLatch;
        if (latch != null) latch.countDown();
        io.shutdownNow();
        disk.shutdownNow();
        super.onDestroy();
    }

    // ---------- send + agent loop ----------

    private void onSend() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (running) {
            toast("Still working - press Stop first.");
            return;
        }
        LlmConfig.Config cfg = LlmConfig.current(this);
        if (!cfg.hasKey()) {
            toast("Set a provider and API key first");
            LlmSettingsDialog.show(this);
            return;
        }
        input.setText("");
        addMessage(new ChatMessage(ChatMessage.ROLE_USER, text));
        stopRequested = false;
        setRunning(true);
        io.execute(this::agentLoop);
    }

    private void agentLoop() {
        boolean usedTools = false;
        try {
            int iterations = 0;
            while (!stopRequested && !destroyed && iterations++ < 8) {
                LlmConfig.Config cfg = LlmConfig.current(this);
                if (!cfg.hasKey()) {
                    appendAssistantNote("Error: no API key configured.");
                    break;
                }

                final List<ChatMessage> request;
                final ChatMessage assistant = new ChatMessage(ChatMessage.ROLE_ASSISTANT);
                synchronized (history) {
                    request = new ArrayList<>(history);
                    history.add(assistant);
                }
                runOnUiThread(() -> {
                    refreshList();
                    scrollBottom();
                });

                final StringBuilder streamed = new StringBuilder();
                final List<ToolCall> callList = new ArrayList<>();
                final String[] err = {null};
                final CountDownLatch done = new CountDownLatch(1);

                LlmClient client = LlmClient.create(cfg);
                activeClient = client;
                client.streamChat(AgentTools.systemPrompt(this), request, AgentTools.specs(),
                        new LlmClient.Listener() {
                            @Override
                            public void onText(String delta) {
                                streamed.append(delta);
                                assistant.text = streamed.toString();
                                runOnUiThread(() -> {
                                    refreshList();
                                    scrollBottomIfNear();
                                });
                            }

                            @Override
                            public void onToolCalls(List<ToolCall> calls) {
                                callList.clear();
                                callList.addAll(calls);
                            }

                            @Override
                            public void onError(String message) {
                                err[0] = message;
                            }

                            @Override
                            public void onDone() {
                                done.countDown();
                            }
                        });
                try {
                    done.await(10, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                activeClient = null;
                if (stopRequested || destroyed) break;

                assistant.text = streamed.toString();
                if (err[0] != null) {
                    assistant.text = "Error: " + err[0];
                    runOnUiThread(ChatActivity.this::refreshList);
                    persist();
                    break;
                }
                if (!callList.isEmpty()) {
                    assistant.toolCalls = new ArrayList<>(callList);
                    usedTools = true;
                }
                runOnUiThread(ChatActivity.this::refreshList);
                persist();

                if (callList.isEmpty()) {
                    if (!usedTools) {
                        String script = extractScript(assistant.text);
                        if (script != null) {
                            assistant.pendingScript = script;
                            runOnUiThread(ChatActivity.this::refreshList);
                        }
                    }
                    break;
                }

                for (ToolCall call : callList) {
                    if (stopRequested || destroyed) break;
                    JSONObject args = call.args();
                    String result;
                    boolean error;
                    if (allowed(call.name, args)) {
                        setStatus("Running " + call.name + "...");
                        result = AgentTools.run(this, call.name, args);
                        error = result.startsWith("ERROR:");
                    } else {
                        result = "User rejected the action.";
                        error = true;
                    }
                    addMessage(ChatMessage.toolResult(call, result, error));
                    setStatus("Working...");
                }
            }
        } catch (Throwable t) {
            appendAssistantNote("Error: " + Http.truncate(String.valueOf(t)));
        } finally {
            setRunning(false);
        }
    }

    /** Confirmation policy: read-only never asks; bypass skips ask for save/run; deletes/schedules always ask. */
    private boolean allowed(String name, JSONObject args) {
        if (AgentTools.isReadOnly(name)) return true;
        if (LlmConfig.bypass(this) && !AgentTools.alwaysConfirm(name)) return true;
        if (AgentTools.SAVE.equals(name)) {
            return confirm("Save script", preview(args.optString("source", "")));
        }
        if (AgentTools.DELETE.equals(name)) {
            return confirm("Delete script \"" + args.optString("id", "") + "\"?",
                    "Removes its source, schedule, log and state. This cannot be undone.");
        }
        if (AgentTools.RUN.equals(name)) {
            return confirm("Run \"" + args.optString("id", "") + "\" now?",
                    "The script runs on this phone with full file access.");
        }
        if (AgentTools.SCHEDULE.equals(name)) {
            return confirm(getString(R.string.confirm_schedule_title, args.optString("id", "")),
                    scheduleSummary(args));
        }
        return confirm("Run tool " + name, args.toString());
    }

    /** Human summary of a set_schedule tool call for the confirmation dialog. */
    private String scheduleSummary(JSONObject args) {
        StringBuilder sb = new StringBuilder();
        if (args.has("enabled")) {
            sb.append(getString(args.optBoolean("enabled")
                    ? R.string.schedule_enabled : R.string.schedule_disabled));
        }
        if (args.has("interval_min")) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(getString(R.string.schedule_interval,
                    com.example.screenshotmover.AutomationAdapter.fmtInterval(args.optInt("interval_min"))));
        }
        return sb.length() == 0 ? args.toString() : sb.toString();
    }

    private String preview(String source) {
        String id = null;
        try {
            id = PluginManager.inspect(source).id;
        } catch (Exception ignored) {}
        if (id != null) {
            String old = PluginManager.readSource(this, id);
            if (old != null) {
                return "Update \"" + id + "\"  (- old, + new)\n\n" + Http.block(DiffUtil.diff(old, source), 6000);
            }
        }
        return "Create script" + (id != null ? " \"" + id + "\"" : "") + "\n\n" + Http.block(source, 6000);
    }

    private boolean confirm(String title, String body) {
        if (destroyed) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        confirmLatch = latch;
        confirmResult = false;
        final TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        final ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Apply", (d, w) -> {
                    confirmResult = true;
                    latch.countDown();
                })
                .setNegativeButton("Reject", (d, w) -> latch.countDown())
                .setOnCancelListener(d -> latch.countDown())
                .show());
        try {
            latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (confirmLatch == latch) confirmLatch = null;
        return confirmResult;
    }

    /** Fallback for models without tool calling: pull a fenced script block out of the reply. */
    private static String extractScript(String text) {
        if (text == null || text.isEmpty()) return null;
        int start = text.indexOf("```");
        while (start >= 0) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) return null;
            int end = text.indexOf("```", nl);
            if (end < 0) return null;
            String block = text.substring(nl + 1, end).trim();
            if (block.contains("id()") && block.contains("run(")) return block;
            start = text.indexOf("```", end + 3);
        }
        return null;
    }

    // ---------- menu ----------

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_chat_mode) {
            if (LlmConfig.bypass(this)) {
                LlmConfig.setMode(this, LlmConfig.MODE_ASK);
                updateModeTitle();
                toast("Mode: Ask");
            } else {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Enable bypass mode?")
                        .setMessage("The agent will save, edit and run scripts without asking. Deletes and schedule changes still ask.")
                        .setPositiveButton("Enable", (d, w) -> {
                            LlmConfig.setMode(this, LlmConfig.MODE_BYPASS);
                            updateModeTitle();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
            return true;
        }
        if (id == R.id.menu_chat_new) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("New chat?")
                    .setMessage("Clears this conversation.")
                    .setPositiveButton("Clear", (d, w) -> {
                        synchronized (history) {
                            history.clear();
                        }
                        ChatStore.clear(this);
                        LlmConfig.newSession(this);
                        refreshList();
                        updateEmpty();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        if (id == R.id.menu_chat_stop) {
            stop();
            return true;
        }
        return false;
    }

    private void stop() {
        stopRequested = true;
        LlmClient c = activeClient;
        if (c != null) c.cancel();
        CountDownLatch latch = confirmLatch;
        if (latch != null) latch.countDown();
        setRunning(false);
    }

    private void updateModeTitle() {
        MenuItem item = toolbar.getMenu().findItem(R.id.menu_chat_mode);
        if (item != null) item.setTitle(LlmConfig.bypass(this)
                ? R.string.chat_mode_bypass : R.string.chat_mode_ask);
    }

    // ---------- adapter callback (fallback "Save script" button) ----------

    @Override
    public void onSaveCandidate(ChatMessage message) {
        final String source = message.pendingScript;
        if (source == null || source.isEmpty()) return;
        message.pendingScript = null;
        refreshList();
        io.execute(() -> {
            if (!confirm("Save script", preview(source))) return;
            JSONObject args = new JSONObject();
            try {
                args.put("source", source);
            } catch (Exception ignored) {}
            final String result = AgentTools.run(this, AgentTools.SAVE, args);
            runOnUiThread(() -> toast(result.startsWith("ERROR:") ? result : "Saved."));
        });
    }

    // ---------- helpers ----------

    private void addMessage(ChatMessage m) {
        synchronized (history) {
            history.add(m);
        }
        runOnUiThread(() -> {
            refreshList();
            updateEmpty();
            scrollBottom();
        });
        persist();
    }

    private void appendAssistantNote(String note) {
        addMessage(new ChatMessage(ChatMessage.ROLE_ASSISTANT, note));
    }

    private void persist() {
        final List<ChatMessage> copy;
        synchronized (history) {
            copy = new ArrayList<>(history);
        }
        disk.execute(() -> ChatStore.save(this, copy));
    }

    private void refreshList() {
        adapter.notifyDataSetChanged();
    }

    private void scrollBottom() {
        if (!history.isEmpty()) list.scrollToPosition(history.size() - 1);
    }

    /** Follow streaming text only when the user is already near the newest message. */
    private void scrollBottomIfNear() {
        int size = history.size();
        if (size == 0) return;
        RecyclerView.LayoutManager lm = list.getLayoutManager();
        int last = lm instanceof LinearLayoutManager
                ? ((LinearLayoutManager) lm).findLastVisibleItemPosition() : -1;
        if (last < 0 || last >= size - 3) list.scrollToPosition(size - 1);
    }

    private void updateEmpty() {
        empty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void setRunning(boolean r) {
        running = r;
        runOnUiThread(() -> {
            MenuItem stopItem = toolbar.getMenu().findItem(R.id.menu_chat_stop);
            if (stopItem != null) stopItem.setVisible(r);
            setStatus(r ? "Working..." : null);
        });
    }

    private void setStatus(String s) {
        runOnUiThread(() -> {
            if (s == null) {
                status.setVisibility(View.GONE);
            } else {
                status.setText(s);
                status.setVisibility(View.VISIBLE);
            }
        });
    }

    private void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }
}
