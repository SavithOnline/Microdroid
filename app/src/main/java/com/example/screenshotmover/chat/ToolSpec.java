package com.example.screenshotmover.chat;

import org.json.JSONObject;

/** One tool the agent may call, with per-provider serialization. */
public final class ToolSpec {
    public final String name;
    public final String description;
    public final JSONObject parameters;

    public ToolSpec(String name, String description, JSONObject parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public JSONObject openAi() {
        JSONObject o = new JSONObject();
        try {
            JSONObject f = new JSONObject();
            f.put("name", name);
            f.put("description", description);
            f.put("parameters", parameters);
            o.put("type", "function");
            o.put("function", f);
        } catch (Exception ignored) {}
        return o;
    }

    public JSONObject anthropic() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("description", description);
            o.put("input_schema", parameters);
        } catch (Exception ignored) {}
        return o;
    }

    public JSONObject gemini() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("description", description);
            o.put("parameters", parameters);
        } catch (Exception ignored) {}
        return o;
    }
}
