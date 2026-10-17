package com.sockspal.agent.config;

import java.util.Map;

import org.springframework.core.env.Environment;

/**
 * Presets for free-tier, OpenAI-compatible chat APIs that support tool calling.
 * Model IDs change often; override with LLM_MODEL in .env if a default is retired.
 */
public record LlmProvider(String name, String baseUrl, String model, String apiKey) {

    record Preset(String baseUrl, String keyEnv, String model) {}

    static final Map<String, Preset> PRESETS = Map.of(
            "groq", new Preset("https://api.groq.com/openai/v1", "GROQ_API_KEY", "openai/gpt-oss-120b"),
            "gemini", new Preset("https://generativelanguage.googleapis.com/v1beta/openai", "GEMINI_API_KEY", "gemini-2.5-flash"),
            "mistral", new Preset("https://api.mistral.ai/v1", "MISTRAL_API_KEY", "mistral-small-latest"),
            "openrouter", new Preset("https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", "openrouter/free"),
            // Z AI (Zhipu) GLM. Mainland China endpoint: https://open.bigmodel.cn/api/paas/v4
            "glm", new Preset("https://api.z.ai/api/paas/v4", "ZAI_API_KEY", "glm-5.3-flash"),
            // Alibaba Cloud Bailian (百炼 / DashScope), Beijing region. Free quota is per model.
            "bailian", new Preset("https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", "qwen3.7-flash-2026-07-15"),
            "nvidia", new Preset("https://integrate.api.nvidia.com/v1", "NVIDIA_API_KEY", "openai/gpt-oss-120b"),
            "ollama", new Preset("https://ollama.com/v1", "OLLAMA_API_KEY", "gpt-oss:120b"),
            // Local Ollama / LM Studio / anything else: set LLM_BASE_URL + LLM_MODEL.
            "custom", new Preset("", "LLM_API_KEY", ""));

    public static LlmProvider resolve(Environment env) {
        String name = env.getProperty("LLM_PROVIDER", "groq").toLowerCase();
        Preset preset = PRESETS.get(name);
        if (preset == null) {
            throw new IllegalStateException("Unknown LLM_PROVIDER \"%s\". Options: %s"
                    .formatted(name, String.join(", ", PRESETS.keySet())));
        }
        String baseUrl = env.getProperty("LLM_BASE_URL", preset.baseUrl()).replaceAll("/+$", "");
        String model = env.getProperty("LLM_MODEL", preset.model());
        String apiKey = env.getProperty("LLM_API_KEY", env.getProperty(preset.keyEnv(), ""));
        if (baseUrl.isBlank()) throw new IllegalStateException("Set LLM_BASE_URL for the custom provider.");
        if (model.isBlank()) throw new IllegalStateException("Set LLM_MODEL for the custom provider.");
        if (apiKey.isBlank() && !name.equals("custom")) {
            throw new IllegalStateException("Missing API key: set %s (or LLM_API_KEY) in .env".formatted(preset.keyEnv()));
        }
        return new LlmProvider(name, baseUrl, model, apiKey);
    }
}
