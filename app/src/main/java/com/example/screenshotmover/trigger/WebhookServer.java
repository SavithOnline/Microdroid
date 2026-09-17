package com.example.screenshotmover.trigger;

import android.content.Context;

import com.example.screenshotmover.core.Store;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Tiny LAN HTTP endpoint: /trigger?secret=x&k=v (LAN only; no cloud relay). */
public final class WebhookServer {
    private WebhookServer() {}

    public static final int DEFAULT_PORT = 8765;

    private static volatile boolean running;
    private static volatile ServerSocket server;
    private static Thread thread;

    public static synchronized void start(Context ctx) {
        if (running) return;
        final Context app = ctx.getApplicationContext();
        try {
            int port = Store.prefs(app).getInt("webhook_port", DEFAULT_PORT);
            server = new ServerSocket(port);
            running = true;
            thread = new Thread(() -> {
                while (running) {
                    try (Socket s = server.accept()) {
                        handle(app, s);
                    } catch (Exception e) {
                        if (running) sleep(300);
                    }
                }
            }, "microdroid-webhook");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            running = false;
        }
    }

    public static synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {}
        server = null;
    }

    public static boolean isRunning() {
        return running;
    }

    private static void handle(Context app, Socket s) {
        try {
            s.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String request = r.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int len = 0;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        len = Integer.parseInt(line.substring("content-length:".length()).trim());
                    } catch (Exception ignored) {}
                }
            }
            String body = "";
            if (len > 0) {
                char[] buf = new char[Math.min(len, 100_000)];
                int read = 0;
                while (read < buf.length) {
                    int n = r.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read);
            }

            Event ev = new Event("webhook");
            String pathOnly = path;
            int q = path.indexOf('?');
            if (q >= 0) {
                pathOnly = path.substring(0, q);
                for (String kv : path.substring(q + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    ev.put(decode(kv.substring(0, eq)), decode(kv.substring(eq + 1)));
                }
            }
            ev.put("path", pathOnly);
            if (!body.isEmpty()) ev.put("body", body.length() > 10_000 ? body.substring(0, 10_000) : body);
            TriggerEngine.dispatch(app, ev);

            byte[] resp = ("OK " + pathOnly).getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                    + resp.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(resp);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
