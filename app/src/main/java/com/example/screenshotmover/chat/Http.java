package com.example.screenshotmover.chat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small HttpURLConnection helper: POST JSON, SSE-friendly reads, error mapping. */
final class Http {
    private Http() {}

    static final int CONNECT_TIMEOUT_MS = 20_000;
    static final int READ_TIMEOUT_MS = 300_000;

    static HttpURLConnection postJson(String url, JSONObject body, String... headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("User-Agent", "Microdroid/2.0");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            c.setRequestProperty(headers[i], headers[i + 1]);
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }
        return c;
    }

    /** Reads an error body without throwing. */
    static String errorBody(HttpURLConnection c) {
        InputStream in = null;
        try {
            in = c.getErrorStream();
            if (in == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {}
        }
    }

    static String statusMessage(int code, String body) {
        String reason;
        switch (code) {
            case 400: reason = "Bad request (400)"; break;
            case 401: reason = "Invalid API key (401)"; break;
            case 403: reason = "Access denied (403)"; break;
            case 404: reason = "Model or endpoint not found (404)"; break;
            case 429: reason = "Rate limited (429)"; break;
            default: reason = code >= 500 ? "Provider error (HTTP " + code + ")" : "HTTP " + code;
        }
        String snippet = extractMessage(body);
        return snippet.isEmpty() ? reason : reason + ": " + snippet;
    }

    /** Best-effort extraction of a human-readable message from a provider error body. */
    static String extractMessage(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject o = new JSONObject(body);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (!m.isEmpty()) return truncate(m);
            }
            String m = o.optString("message", "");
            if (!m.isEmpty()) return truncate(m);
        } catch (Exception ignored) {}
        return truncate(body);
    }

    static String truncate(String s) {
        s = s.replace('\n', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** Truncate while preserving newlines (for source/diff previews). */
    static String block(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "\n... (truncated)" : s;
    }

    static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
