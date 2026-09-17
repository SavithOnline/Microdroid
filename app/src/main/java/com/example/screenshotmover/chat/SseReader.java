package com.example.screenshotmover.chat;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Minimal text/event-stream parser (works for OpenAI, Anthropic and Gemini SSE). */
public final class SseReader implements Closeable {

    public static final class Event {
        public final String event;
        public final String data;

        Event(String event, String data) {
            this.event = event;
            this.data = data;
        }
    }

    private final BufferedReader reader;

    public SseReader(InputStream in) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Next event, or null at end of stream. */
    public Event next() throws IOException {
        String event = null;
        StringBuilder data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data != null) return new Event(event, data.toString());
                event = null;
                continue;
            }
            if (line.startsWith(":")) continue;
            if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                String v = line.substring(5);
                if (v.startsWith(" ")) v = v.substring(1);
                if (data == null) data = new StringBuilder();
                else data.append('\n');
                data.append(v);
            }
        }
        return data == null ? null : new Event(event, data.toString());
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
