package com.example.screenshotmover.chat;

import org.json.JSONObject;

/** One tool invocation requested by the model. */
public class ToolCall {
    /** Provider-assigned id when available (OpenAI call_*, Anthropic toolu_*); synthetic for Gemini. */
    public String id = "";
    public String name = "";
    /** Raw JSON object with the arguments (complete once streaming finishes). */
    public String arguments = "{}";

    public ToolCall() {}

    public ToolCall(String id, String name) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
    }

    public JSONObject args() {
        try {
            return new JSONObject(arguments == null || arguments.isEmpty() ? "{}" : arguments);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("arguments", arguments);
        return o;
    }

    public static ToolCall fromJson(JSONObject o) {
        ToolCall c = new ToolCall();
        c.id = o.optString("id", "");
        c.name = o.optString("name", "");
        c.arguments = o.optString("arguments", "{}");
        return c;
    }
}
