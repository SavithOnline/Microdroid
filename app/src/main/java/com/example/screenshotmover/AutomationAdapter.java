package com.example.screenshotmover;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.screenshotmover.automation.Automation;
import com.example.screenshotmover.core.Store;
import com.example.screenshotmover.plugin.ScriptAutomationHolder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

/** Minimal card list for automations. Status text is bound on demand. */
public class AutomationAdapter extends RecyclerView.Adapter<AutomationAdapter.Holder> {

    public interface Listener {
        void onRun(String id);
        void onToggle(String id, boolean on);
        void onInterval(String id);
        void onMore(String id, boolean isScript);
    }

    public static class Item {
        final Automation automation;
        final boolean isScript;
        Item(Automation a) {
            automation = a;
            isScript = a instanceof ScriptAutomationHolder;
        }
    }

    private final Listener listener;
    private List<Item> items;

    public AutomationAdapter(List<Item> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void setItems(List<Item> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_automation, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Item item = items.get(position);
        Automation a = item.automation;
        android.content.Context ctx = h.itemView.getContext();

        h.name.setText(a.name());
        h.badge.setText(ctx.getString(item.isScript ? R.string.script_badge : R.string.builtin_badge));
        h.desc.setText(a.description());

        boolean on = Store.isEnabled(ctx, a.id());
        int iv = Store.getInterval(ctx, a.id());
        String trig = com.example.screenshotmover.trigger.TriggerStore.summary(ctx, a.id());
        String schedule = trig != null
                ? trig
                : ctx.getString(R.string.schedule_every, fmtInterval(iv));
        h.status.setText(ctx.getString(R.string.status_line,
                ctx.getString(on ? R.string.status_on : R.string.status_off),
                schedule,
                ctx.getString(R.string.last_status, Store.getLast(ctx, a.id()))));

        h.enable.setOnCheckedChangeListener(null);
        h.enable.setChecked(on);
        h.enable.setOnCheckedChangeListener((btn, checked) -> listener.onToggle(a.id(), checked));

        h.interval.setText(ctx.getString(R.string.every_interval, fmtInterval(iv)));
        h.interval.setOnClickListener(v -> listener.onInterval(a.id()));
        h.run.setOnClickListener(v -> listener.onRun(a.id()));
        h.more.setOnClickListener(v -> listener.onMore(a.id(), item.isScript));
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    public static String fmtInterval(int min) {
        if (min < 60) return min + "m";
        if (min % 60 == 0) return (min / 60) + "h";
        return (min / 60) + "h" + (min % 60) + "m";
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name, badge, desc, status;
        final MaterialButton run, interval, more;
        final MaterialSwitch enable;
        Holder(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.tv_name);
            badge = v.findViewById(R.id.tv_badge);
            desc = v.findViewById(R.id.tv_desc);
            status = v.findViewById(R.id.tv_status);
            run = v.findViewById(R.id.btn_run);
            enable = v.findViewById(R.id.switch_enable);
            interval = v.findViewById(R.id.btn_interval);
            more = v.findViewById(R.id.btn_more);
        }
    }
}
