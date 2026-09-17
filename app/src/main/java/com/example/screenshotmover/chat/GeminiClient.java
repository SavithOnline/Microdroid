package com.example.screenshotmover.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/** Native Gemini generateContent client (streaming + function calling). */
public class GeminiClient extends BaseClient {

    GeminiClient(LlmConfig.Config cfg) {
        super(cfg);
    }

    @Override
    public void streamChat(String system, List<ChatMessage> history, List<ToolSpec> tools, Listener l) {
        try {
            String url = Http.trimSlash(cfg.baseUrl) + "/models/" + cfg.model
                    + ":streamGenerateContent?alt=sse";
            JSONObject body = new JSONObject();
            if (system != null && !system.isEmpty()) {
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", system));
                body.put("systemInstruction", new JSONObject().put("parts", parts));
            }
            body.put("contents", contents(history));
            if (tools != null && !tools.isEmpty()) {
                JSONArray decls = new JSONArray();
                for (ToolSpec t : tools) decls.put(t.gemini());
                body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decls)));
            }
            body.put("generationConfig", new JSONObject().put("temperature", 0.2));

            HttpURLConnection c = Http.postJson(url, body, "x-goog-api-key", cfg.apiKey);
            setConn(c);
            int code = c.getResponseCode();
            if (code != 200) {
                l.onError(Http.statusMessage(code, Http.errorBody(c)));
                return;
            }

            List<ToolCall> calls = new ArrayList<>();
            int callIndex = 0;
            try (SseReader reader = new SseReader(c.getInputStream())) {
                SseReader.Event e;
                while (!isCancelled() && (e = reader.next()) != null) {
                    String data = e.data.trim();
                    if (data.isEmpty()) continue;
                    JSONObject o;
                    try {
                        o = new JSONObject(data);
                    } catch (Exception bad) {
                        continue;
                    }
                    if (o.has("error")) {
                        JSONObject err = o.optJSONObject("error");
                        l.onError(err == null ? "Gemini error" : Http.truncate(err.optString("message", err.toString())));
                        return;
                    }
                    JSONObject feedback = o.optJSONObject("promptFeedback");
                    if (feedback != null && !feedback.optString("blockReason", "").isEmpty()) {
                        l.onError("Blocked by Gemini: " + feedback.optString("blockReason"));
                        return;
                    }
                    JSONArray cands = o.optJSONArray("candidates");
                    if (cands == null) continue;
                    for (int i = 0; i < cands.length(); i++) {
                        JSONObject cand = cands.optJSONObject(i);
                        if (cand == null) continue;
                        JSONObject content = cand.optJSONObject("content");
                        if (content == null) continue;
                        JSONArray parts = content.optJSONArray("parts");
                        if (parts == null) continue;
                        for (int p = 0; p < parts.length(); p++) {
                            JSONObject part = parts.optJSONObject(p);
                            if (part == null) continue;
                            String text = part.optString("text", "");
                            if (!text.isEmpty()) l.onText(text);
                            JSONObject fc = part.optJSONObject("functionCall");
                            if (fc != null) {
                                ToolCall tc = new ToolCall("call_" + (callIndex++), fc.optString("name", ""));
                                JSONObject args = fc.optJSONObject("args");
                                tc.arguments = args == null ? "{}" : args.toString();
                                calls.add(tc);
                            }
                        }
                    }
                }
            }
            if (!isCancelled() && !calls.isEmpty()) l.onToolCalls(calls);
        } catch (Exception ex) {
            if (!isCancelled()) l.onError(OpenAiCompatibleClient.describe(ex));
        } finally {
            l.onDone();
        }
    }

    private static JSONArray contents(List<ChatMessage> history) throws Exception {
        JSONArray out = new JSONArray();
        int i = 0;
        while (i < history.size()) {
            ChatMessage m = history.get(i);
            if (ChatMessage.ROLE_SYSTEM.equals(m.role)) {
                i++;
                continue;
            }
            if (ChatMessage.ROLE_TOOL.equals(m.role)) {
                JSONArray parts = new JSONArray();
                while (i < history.size() && ChatMessage.ROLE_TOOL.equals(history.get(i).role)) {
                    ChatMessage t = history.get(i);
                    JSONObject payload = new JSONObject();
                    if (t.toolError) payload.put("error", t.text == null ? "" : t.text);
                    else payload.put("result", t.text == null ? "" : t.text);
                    JSONObject fr = new JSONObject();
                    fr.put("name", t.toolName == null ? "" : t.toolName);
                    fr.put("response", payload);
                    parts.put(new JSONObject().put("functionResponse", fr));
                    i++;
                }
                out.put(new JSONObject().put("role", "user").put("parts", parts));
                continue;
            }
            if (ChatMessage.ROLE_ASSISTANT.equals(m.role)) {
                JSONArray parts = new JSONArray();
                if (m.text != null && !m.text.isEmpty()) parts.put(new JSONObject().put("text", m.text));
                if (m.toolCalls != null) {
                    for (ToolCall tc : m.toolCalls) {
                        JSONObject fc = new JSONObject();
                        fc.put("name", tc.name);
                        fc.put("args", tc.args());
                        parts.put(new JSONObject().put("functionCall", fc));
                    }
                }
                if (parts.length() == 0) parts.put(new JSONObject().put("text", ""));
                out.put(new JSONObject().put("role", "model").put("parts", parts));
                i++;
                continue;
            }
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", m.text == null ? "" : m.text));
            out.put(new JSONObject().put("role", "user").put("parts", parts));
            i++;
        }
        return out;
    }
}
