package com.example.screenshotmover.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Provider-neutral chat message. Tool calls/results are mapped per provider by the clients. */
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";
    public static final String ROLE_SYSTEM = "system";

    public String role;
    public String text = "";
    public List<ToolCall> toolCalls = new ArrayList<>();
    /** Set on ROLE_TOOL messages: the id of the assistant tool call they answer. */
    public String toolCallId;
    public String toolName;
    public boolean toolError;
    /** Not persisted: fenced script block extracted from an assistant reply. */
    public transient String pendingScript;

    public ChatMessage() {}

    public ChatMessage(String role) {
        this.role = role;
    }

    public ChatMessage(String role, String text) {
        this.role = role;
        this.text = text == null ? "" : text;
    }

    public static ChatMessage toolResult(ToolCall call, String result, boolean error) {
        ChatMessage m = new ChatMessage(ROLE_TOOL, result);
        m.toolCallId = call.id;
        m.toolName = call.name;
        m.toolError = error;
        return m;
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("text", text == null ? "" : text);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (ToolCall c : toolCalls) arr.put(c.toJson());
            o.put("toolCalls", arr);
        }
        if (toolCallId != null) o.put("toolCallId", toolCallId);
        if (toolName != null) o.put("toolName", toolName);
        if (toolError) o.put("toolError", true);
        return o;
    }

    public static ChatMessage fromJson(JSONObject o) {
        ChatMessage m = new ChatMessage();
        m.role = o.optString("role", ROLE_ASSISTANT);
        m.text = o.optString("text", "");
        JSONArray arr = o.optJSONArray("toolCalls");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c != null) m.toolCalls.add(ToolCall.fromJson(c));
            }
        }
        m.toolCallId = o.optString("toolCallId", null);
        m.toolName = o.optString("toolName", null);
        m.toolError = o.optBoolean("toolError", false);
        return m;
    }
}
