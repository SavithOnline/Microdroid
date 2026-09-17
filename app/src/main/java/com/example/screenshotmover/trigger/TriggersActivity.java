package com.example.screenshotmover.trigger;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.screenshotmover.R;
import com.example.screenshotmover.core.Store;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Per-automation trigger and constraint editor. */
public class TriggersActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "automation_id";

    private String pluginId;
    private final List<JSONObject> triggers = new ArrayList<>();
    private final List<JSONObject> constraints = new ArrayList<>();
    private LinearLayout listTriggers;
    private LinearLayout listConstraints;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_triggers);
        pluginId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_triggers);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setSubtitle(pluginId);

        listTriggers = findViewById(R.id.list_triggers);
        listConstraints = findViewById(R.id.list_constraints);

        triggers.addAll(TriggerStore.triggers(this, pluginId));
        constraints.addAll(TriggerStore.constraints(this, pluginId));
        render();

        findViewById(R.id.btn_add_trigger).setOnClickListener(v -> addTrigger());
        findViewById(R.id.btn_add_constraint).setOnClickListener(v -> addConstraint());
    }

    // ---------- rendering ----------

    private void render() {
        renderList(listTriggers, triggers, true);
        renderList(listConstraints, constraints, false);
        int fired = Store.prefs(this).getInt("fired_" + pluginId, 0);
        long last = Store.prefs(this).getLong("last_fired_" + pluginId, 0);
        String when = last > 0 ? android.text.format.DateFormat.format("MMM d, HH:mm", last).toString() : "never";
        TextView stats = findViewById(R.id.tv_trigger_stats);
        stats.setText(getString(TriggerStore.needsService(this)
                ? R.string.trigger_stats_watching : R.string.trigger_stats, fired, when));
    }

    private void renderList(LinearLayout host, List<JSONObject> items, boolean isTrigger) {
        host.removeAllViews();
        if (items.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.none);
            tv.setTextColor(MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurfaceVariant));
            host.addView(tv);
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            JSONObject cfg = items.get(i);
            String label;
            if (isTrigger) {
                TriggerType t = TriggerType.byId(cfg.optString("type", ""));
                label = t == null ? cfg.optString("type") : t.summary(cfg);
            } else {
                ConstraintType c = ConstraintType.byId(cfg.optString("type", ""));
                label = c == null ? cfg.optString("type") : c.summary(cfg);
            }
            TextView row = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_trigger_row, host, false);
            row.setText(label);
            row.setOnClickListener(v -> confirmRemove(items, index, isTrigger));
            host.addView(row);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(MaterialColors.getColor(host,
                    com.google.android.material.R.attr.colorOutlineVariant));
            host.addView(divider);
        }
    }

    private void confirmRemove(List<JSONObject> items, int index, boolean isTrigger) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remove " + (isTrigger ? "trigger" : "constraint") + "?")
                .setPositiveButton("Remove", (d, w) -> {
                    if (index >= 0 && index < items.size()) items.remove(index);
                    persist();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- add flows ----------

    private void addTrigger() {
        TriggerType[] types = TriggerType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = types[i].label;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Add trigger")
                .setItems(labels, (d, w) -> showForm(types[w].label, types[w].fields, cfg -> {
                    try {
                        JSONObject out = new JSONObject();
                        out.put("type", types[w].id);
                        copy(cfg, out);
                        triggers.add(out);
                        String err = TriggerStore.validateTriggers(TriggerStore.toArray(triggers));
                        if (err != null) {
                            triggers.remove(out);
                            toast(err);
                            return;
                        }
                        persist();
                        render();
                    } catch (Exception e) {
                        toast("Bad trigger: " + e.getMessage());
                    }
                }))
                .show();
    }

    private void addConstraint() {
        ConstraintType[] types = ConstraintType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = types[i].label;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Add constraint")
                .setItems(labels, (d, w) -> showForm(types[w].label, types[w].fields, cfg -> {
                    try {
                        JSONObject out = new JSONObject();
                        out.put("type", types[w].id);
                        copy(cfg, out);
                        constraints.add(out);
                        String err = TriggerStore.validateConstraints(TriggerStore.toArray(constraints));
                        if (err != null) {
                            constraints.remove(out);
                            toast(err);
                            return;
                        }
                        persist();
                        render();
                    } catch (Exception e) {
                        toast("Bad constraint: " + e.getMessage());
                    }
                }))
                .show();
    }

    private interface OnSubmit {
        void submit(JSONObject values) throws Exception;
    }

    /** Generic form builder driven by FieldSpec metadata. */
    private void showForm(String title, FieldSpec[] fields, OnSubmit onSubmit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, dp(8));

        final List<EditText> editors = new ArrayList<>();
        for (FieldSpec f : fields) {
            if (f.kind == FieldSpec.CHOICE) {
                MaterialAutoCompleteTextView act = new MaterialAutoCompleteTextView(this);
                TextInputLayout til = new TextInputLayout(this);
                til.setHint(f.label);
                til.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
                til.addView(act);
                String[] display = new String[f.choices.length];
                for (int i = 0; i < f.choices.length; i++) {
                    display[i] = f.choices[i].isEmpty() ? "(any)" : f.choices[i];
                }
                act.setSimpleItems(display);
                act.setTag(f.key);
                box.addView(til);
                editors.add(act);
            } else {
                TextInputEditText et = new TextInputEditText(this);
                TextInputLayout til = new TextInputLayout(this);
                til.setHint(f.label);
                int it = f.kind == FieldSpec.NUMBER ? InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                        : InputType.TYPE_CLASS_TEXT;
                et.setInputType(it);
                til.addView(et);
                box.addView(til);
                editors.add(et);
            }
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Add", (d, w) -> {
                    try {
                        JSONObject out = new JSONObject();
                        for (int i = 0; i < fields.length; i++) {
                            String v = editors.get(i).getText().toString().trim();
                            if ("(any)".equals(v)) v = "";
                            if (!v.isEmpty()) out.put(fields[i].key, v);
                        }
                        onSubmit.submit(out);
                    } catch (Exception e) {
                        toast("Invalid value: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- persistence ----------

    private void persist() {
        JSONArray t = TriggerStore.toArray(triggers);
        JSONArray c = TriggerStore.toArray(constraints);
        TriggerStore.setTriggers(this, pluginId, t);
        TriggerStore.setConstraints(this, pluginId, c);
        TriggerRuntime.refresh(this);
    }

    private void copy(JSONObject from, JSONObject to) throws Exception {
        java.util.Iterator<String> it = from.keys();
        while (it.hasNext()) {
            String k = it.next();
            to.put(k, from.opt(k));
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
