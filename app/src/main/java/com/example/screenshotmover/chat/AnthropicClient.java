package com.example.screenshotmover.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Native Anthropic Messages API client (streaming + tool use). */
public class AnthropicClient extends BaseClient {

    private static final String API_VERSION = "2023-06-01";

    AnthropicClient(LlmConfig.Config cfg) {
        super(cfg);
    }

    @Override
    public void streamChat(String system, List<ChatMessage> history, List<ToolSpec> tools, Listener l) {
        try {
            String url = Http.trimSlash(cfg.baseUrl) + "/messages";
            JSONObject body = new JSONObject();
            body.put("model", cfg.model);
            body.put("max_tokens", 4096);
            body.put("stream", true);
            body.put("temperature", 0.2);
            if (system != null && !system.isEmpty()) body.put("system", system);
            body.put("messages", messages(history));
            if (tools != null && !tools.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (ToolSpec t : tools) arr.put(t.anthropic());
                body.put("tools", arr);
            }

            HttpURLConnection c = Http.postJson(url, body,
                    "x-api-key", cfg.apiKey,
                    "anthropic-version", API_VERSION,
                    "x-opencode-session", cfg.sessionId,
                    "Accept", "text/event-stream");
            setConn(c);
            int code = c.getResponseCode();
            if (code != 200) {
                l.onError(Http.statusMessage(code, Http.errorBody(c)));
                return;
            }

            Map<Integer, ToolCall> pending = new TreeMap<>();
            boolean stop = false;
            try (SseReader reader = new SseReader(c.getInputStream())) {
                SseReader.Event e;
                while (!isCancelled() && !stop && (e = reader.next()) != null) {
                    String data = e.data.trim();
                    if (data.isEmpty()) continue;
                    JSONObject o;
                    try {
                        o = new JSONObject(data);
                    } catch (Exception bad) {
                        continue;
                    }
                    String type = e.event != null && !e.event.isEmpty()
                            ? e.event : o.optString("type", "");
                    switch (type) {
                        case "content_block_start": {
                            int index = o.optInt("index", 0);
                            JSONObject block = o.optJSONObject("content_block");
                            if (block != null && "tool_use".equals(block.optString("type", ""))) {
                                ToolCall tc = new ToolCall(block.optString("id", ""), block.optString("name", ""));
                                tc.arguments = "";
                                pending.put(index, tc);
                            }
                            break;
                        }
                        case "content_block_delta": {
                            int index = o.optInt("index", 0);
                            JSONObject d = o.optJSONObject("delta");
                            if (d == null) break;
                            String dtype = d.optString("type", "");
                            if ("text_delta".equals(dtype)) {
                                String t = d.optString("text", "");
                                if (!t.isEmpty()) l.onText(t);
                            } else if ("input_json_delta".equals(dtype)) {
                                ToolCall tc = pending.get(index);
                                if (tc != null) tc.arguments += d.optString("partial_json", "");
                            }
                            break;
                        }
                        case "message_stop":
                            stop = true;
                            break;
                        case "error": {
                            JSONObject err = o.optJSONObject("error");
                            l.onError(err == null ? "Anthropic error" : Http.truncate(err.optString("message", err.toString())));
                            return;
                        }
                        default:
                            break;
                    }
                }
            }
            if (!isCancelled() && !pending.isEmpty()) {
                l.onToolCalls(new ArrayList<>(pending.values()));
            }
        } catch (Exception ex) {
            if (!isCancelled()) l.onError(OpenAiCompatibleClient.describe(ex));
        } finally {
            l.onDone();
        }
    }

    private static JSONArray messages(List<ChatMessage> history) throws Exception {
        JSONArray out = new JSONArray();
        int i = 0;
        while (i < history.size()) {
            ChatMessage m = history.get(i);
            if (ChatMessage.ROLE_SYSTEM.equals(m.role)) {
                i++;
                continue;
            }
            if (ChatMessage.ROLE_TOOL.equals(m.role)) {
                JSONArray blocks = new JSONArray();
                while (i < history.size() && ChatMessage.ROLE_TOOL.equals(history.get(i).role)) {
                    ChatMessage t = history.get(i);
                    JSONObject tr = new JSONObject();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", t.toolCallId == null ? "" : t.toolCallId);
                    tr.put("content", t.text == null ? "" : t.text);
                    if (t.toolError) tr.put("is_error", true);
                    blocks.put(tr);
                    i++;
                }
                out.put(new JSONObject().put("role", "user").put("content", blocks));
                continue;
            }
            if (ChatMessage.ROLE_ASSISTANT.equals(m.role)) {
                JSONArray blocks = new JSONArray();
                if (m.text != null && !m.text.isEmpty()) {
                    blocks.put(new JSONObject().put("type", "text").put("text", m.text));
                }
                if (m.toolCalls != null) {
                    for (ToolCall tc : m.toolCalls) {
                        JSONObject tu = new JSONObject();
                        tu.put("type", "tool_use");
                        tu.put("id", tc.id == null || tc.id.isEmpty() ? "toolu_" + blocks.length() : tc.id);
                        tu.put("name", tc.name);
                        tu.put("input", tc.args());
                        blocks.put(tu);
                    }
                }
                if (blocks.length() == 0) blocks.put(new JSONObject().put("type", "text").put("text", ""));
                out.put(new JSONObject().put("role", "assistant").put("content", blocks));
                i++;
                continue;
            }
            JSONArray blocks = new JSONArray();
            blocks.put(new JSONObject().put("type", "text").put("text", m.text == null ? "" : m.text));
            out.put(new JSONObject().put("role", "user").put("content", blocks));
            i++;
        }
        return out;
    }
}
