package com.example.screenshotmover;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.screenshotmover.plugin.PluginManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Merged run/trigger log feed across all scripts. */
public class LogsActivity extends AppCompatActivity {

    private static final int MAX_ENTRIES = 300;

    private static class Entry {
        long ts;
        String id;
        String text;
    }

    private final List<Entry> entries = new ArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("MMM d HH:mm", Locale.getDefault());
    private Adapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_logs);
        NavTabs.bind(this, R.id.nav_logs);

        RecyclerView list = findViewById(R.id.logs_list);
        empty = findViewById(R.id.tv_logs_empty);
        adapter = new Adapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        NavTabs.applyEnterTransition(this);
        NavTabs.sync(this, R.id.nav_logs);
        reload();
    }

    private void reload() {
        entries.clear();
        for (String id : PluginManager.scriptIds(this)) {
            String log = PluginManager.getLog(this, id);
            if (log == null || log.isEmpty()) continue;
            for (String line : log.split("\n")) {
                Entry e = parse(id, line);
                if (e != null) entries.add(e);
            }
        }
        Collections.sort(entries, (a, b) -> Long.compare(b.ts, a.ts));
        if (entries.size() > MAX_ENTRIES) entries.subList(MAX_ENTRIES, entries.size()).clear();
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private static Entry parse(String id, String line) {
        try {
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                if (end > 1) {
                    Entry e = new Entry();
                    e.id = id;
                    e.ts = Long.parseLong(line.substring(1, end).trim());
                    e.text = line.substring(end + 1).trim();
                    return e;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_log_entry, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Entry e = entries.get(position);
            h.id.setText(e.id);
            h.time.setText(fmt.format(new Date(e.ts)));
            h.text.setText(e.text);
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(LogsActivity.this, LogActivity.class);
                i.putExtra(LogActivity.EXTRA_ID, e.id);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView id, time, text;

            Holder(@NonNull View v) {
                super(v);
                id = v.findViewById(R.id.tv_log_id);
                time = v.findViewById(R.id.tv_log_time);
                text = v.findViewById(R.id.tv_log_text);
            }
        }
    }
}
