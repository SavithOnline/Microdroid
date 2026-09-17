package com.example.screenshotmover.chat;

/** Supported LLM wire formats and their defaults. */
public enum LlmProvider {
    OPENAI_COMPAT("openai", "OpenAI-compatible", "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-5"),
    GEMINI("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
    OPENCODE_GO("opencode-go", "OpenCode Go", "https://opencode.ai/zen/go/v1", "deepseek-v4.1-flash");

    public final String id;
    public final String label;
    public final String defaultBaseUrl;
    public final String defaultModel;

    LlmProvider(String id, String label, String defaultBaseUrl, String defaultModel) {
        this.id = id;
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
    }

    public static LlmProvider byId(String id) {
        for (LlmProvider p : values()) {
            if (p.id.equals(id)) return p;
        }
        return OPENAI_COMPAT;
    }
}
