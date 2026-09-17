package com.example.screenshotmover.chat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Chat transcript persistence (files/chat/history.json). */
public final class ChatStore {
    private ChatStore() {}

    private static File file(Context ctx) {
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "chat");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "history.json");
    }

    public static List<ChatMessage> load(Context ctx) {
        List<ChatMessage> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.isFile()) return out;
        try {
            StringBuilder sb = new StringBuilder();
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(ChatMessage.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void save(Context ctx, List<ChatMessage> history) {
        try {
            JSONArray arr = new JSONArray();
            for (ChatMessage m : history) arr.put(m.toJson());
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file(ctx)), StandardCharsets.UTF_8)) {
                w.write(arr.toString());
            }
        } catch (Exception ignored) {}
    }

    public static void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
    }
}
