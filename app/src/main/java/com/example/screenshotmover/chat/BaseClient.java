package com.example.screenshotmover.chat;

import java.net.HttpURLConnection;

/** Shared cancel/disconnect plumbing for the provider clients. */
abstract class BaseClient implements LlmClient {

    protected final LlmConfig.Config cfg;
    private volatile HttpURLConnection conn;
    protected volatile boolean cancelled;

    BaseClient(LlmConfig.Config cfg) {
        this.cfg = cfg;
    }

    protected void setConn(HttpURLConnection c) {
        this.conn = c;
    }

    protected boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
