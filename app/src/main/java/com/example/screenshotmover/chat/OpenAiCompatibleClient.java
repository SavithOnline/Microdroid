package com.example.screenshotmover.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OpenAI-compatible /chat/completions client: OpenAI, OpenRouter, Groq, DeepSeek,
 * Mistral, Anthropic/Gemini compatibility endpoints, Ollama/LM Studio, ...
 */
public class OpenAiCompatibleClient extends BaseClient {

    OpenAiCompatibleClient(LlmConfig.Config cfg) {
        super(cfg);
    }

    @Override
    public void streamChat(String system, List<ChatMessage> history, List<ToolSpec> tools, Listener l) {
        try {
            String url = Http.trimSlash(cfg.baseUrl) + "/chat/completions";
            JSONObject body = new JSONObject();
            body.put("model", cfg.model);
            body.put("stream", true);
            body.put("temperature", 0.2);
            JSONArray messages = new JSONArray();
            if (system != null && !system.isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", system));
            }
            for (ChatMessage m : history) messages.put(toWire(m));
            body.put("messages", messages);
            if (tools != null && !tools.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (ToolSpec t : tools) arr.put(t.openAi());
                body.put("tools", arr);
                body.put("tool_choice", "auto");
            }

            HttpURLConnection c = Http.postJson(url, body,
                    "Authorization", "Bearer " + cfg.apiKey,
                    "x-opencode-session", cfg.sessionId,
                    "Accept", "text/event-stream");
            setConn(c);
            int code = c.getResponseCode();
            if (code != 200) {
                l.onError(Http.statusMessage(code, Http.errorBody(c)));
                return;
            }

            Map<Integer, ToolCall> pending = new TreeMap<>();
            boolean done = false;
            try (SseReader reader = new SseReader(c.getInputStream())) {
                SseReader.Event e;
                while (!isCancelled() && !done && (e = reader.next()) != null) {
                    String data = e.data.trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    JSONObject o;
                    try {
                        o = new JSONObject(data);
                    } catch (Exception bad) {
                        continue;
                    }
                    if (o.has("error")) {
                        l.onError(describeError(o.opt("error")));
                        return;
                    }
                    JSONArray choices = o.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject choice = choices.optJSONObject(0);
                    if (choice == null) continue;
                    JSONObject delta = choice.optJSONObject("delta");
                    if (delta == null) {
                        // some servers ignore stream:true and return one full response
                        JSONObject msg = choice.optJSONObject("message");
                        if (msg != null) {
                            String t = msg.isNull("content") ? "" : msg.optString("content", "");
                            if (!t.isEmpty()) l.onText(t);
                            collect(msg.optJSONArray("tool_calls"), pending);
                        }
                        continue;
                    }
                    String text = delta.isNull("content") ? "" : delta.optString("content", "");
                    if (!text.isEmpty()) l.onText(text);
                    collect(delta.optJSONArray("tool_calls"), pending);
                    if ("tool_calls".equals(choice.optString("finish_reason", ""))) {
                        // keep reading until [DONE]; providers usually close right after
                    }
                }
            }
            if (!isCancelled() && !pending.isEmpty()) l.onToolCalls(finalizeCalls(pending));
        } catch (Exception ex) {
            if (!isCancelled()) l.onError(describe(ex));
        } finally {
            l.onDone();
        }
    }

    private static void collect(JSONArray tcs, Map<Integer, ToolCall> pending) {
        if (tcs == null) return;
        for (int i = 0; i < tcs.length(); i++) {
            JSONObject tc = tcs.optJSONObject(i);
            if (tc == null) continue;
            int idx = tc.has("index") ? tc.optInt("index", i) : i;
            ToolCall call = pending.get(idx);
            if (call == null) {
                call = new ToolCall();
                call.arguments = "";
                pending.put(idx, call);
            }
            String id = tc.optString("id", "");
            if (!id.isEmpty()) call.id = id;
            JSONObject fn = tc.optJSONObject("function");
            if (fn != null) {
                String name = fn.optString("name", "");
                if (!name.isEmpty()) call.name = name;
                call.arguments += fn.optString("arguments", "");
            }
        }
    }

    static List<ToolCall> finalizeCalls(Map<Integer, ToolCall> pending) {
        List<ToolCall> out = new ArrayList<>();
        int n = 0;
        for (ToolCall c : pending.values()) {
            if (c.id == null || c.id.isEmpty()) c.id = "call_" + n;
            if (c.arguments == null || c.arguments.isEmpty()) c.arguments = "{}";
            n++;
            out.add(c);
        }
        return out;
    }

    private static JSONObject toWire(ChatMessage m) throws Exception {
        JSONObject o = new JSONObject();
        if (ChatMessage.ROLE_TOOL.equals(m.role)) {
            o.put("role", "tool");
            o.put("tool_call_id", m.toolCallId == null ? "" : m.toolCallId);
            o.put("content", m.text == null ? "" : m.text);
            return o;
        }
        if (ChatMessage.ROLE_ASSISTANT.equals(m.role)) {
            o.put("role", "assistant");
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (ToolCall c : m.toolCalls) {
                    JSONObject fn = new JSONObject();
                    fn.put("name", c.name);
                    fn.put("arguments", c.arguments == null || c.arguments.isEmpty() ? "{}" : c.arguments);
                    JSONObject w = new JSONObject();
                    w.put("id", c.id == null || c.id.isEmpty() ? "call_" + arr.length() : c.id);
                    w.put("type", "function");
                    w.put("function", fn);
                    arr.put(w);
                }
                o.put("tool_calls", arr);
                o.put("content", m.text == null || m.text.isEmpty() ? JSONObject.NULL : m.text);
            } else {
                o.put("content", m.text == null ? "" : m.text);
            }
            return o;
        }
        o.put("role", ChatMessage.ROLE_USER.equals(m.role) ? "user" : m.role);
        o.put("content", m.text == null ? "" : m.text);
        return o;
    }

    private static String describeError(Object err) {
        if (err instanceof JSONObject) {
            String m = ((JSONObject) err).optString("message", "");
            if (!m.isEmpty()) return Http.truncate(m);
            return Http.truncate(err.toString());
        }
        return Http.truncate(String.valueOf(err));
    }

    static String describe(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isEmpty()) msg = ex.getClass().getSimpleName();
        return "Network error: " + Http.truncate(msg);
    }
}
