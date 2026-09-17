package com.example.screenshotmover.chat;

import java.util.List;

/** Streaming chat client for one provider. Calls happen on a background thread. */
public interface LlmClient {

    interface Listener {
        /** Incremental assistant text. */
        void onText(String delta);

        /** Complete tool calls, emitted once the stream ends (only if any). */
        void onToolCalls(List<ToolCall> calls);

        void onError(String message);

        /** Always called last, after onError or onToolCalls (also on cancel). */
        void onDone();
    }

    void streamChat(String system, List<ChatMessage> history, List<ToolSpec> tools, Listener listener);

    /** Aborts an in-flight request. */
    void cancel();

    static LlmClient create(LlmConfig.Config cfg) {
        String wire = cfg.wire == null ? LlmProvider.OPENAI_COMPAT.id : cfg.wire;
        if (LlmProvider.ANTHROPIC.id.equals(wire)) return new AnthropicClient(cfg);
        if (LlmProvider.GEMINI.id.equals(wire)) return new GeminiClient(cfg);
        return new OpenAiCompatibleClient(cfg);
    }
}
