package com.sockspal.agent.config;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code llm.*} settings from application.yml: which provider to use, the presets, and optional overrides.
 * API keys are not stored here; the presets reference them as {@code ${GROQ_API_KEY:}} etc. from .env.
 */
@ConfigurationProperties("llm")
public record LlmProperties(
        String provider,
        @Nullable String model,
        @Nullable String baseUrl,
        @Nullable String apiKey,
        double temperature,
        @Nullable String embeddingModel,
        Map<String, Preset> presets) {

    public record Preset(@Nullable String baseUrl, @Nullable String model, @Nullable String apiKey,
                         @Nullable String embeddingModel) {}

    /** The embedding model for the sales-team search, or "" when neither the override nor the preset sets one. */
    public String resolveEmbeddingModel() {
        Preset preset = presets.get(provider == null ? "" : provider.trim().toLowerCase());
        return firstSet(embeddingModel, preset == null ? null : preset.embeddingModel());
    }

    /** Picks the preset and applies the overrides. Fails at startup with a clear message if something is missing. */
    public LlmProvider resolve() {
        String name = provider == null ? "" : provider.trim().toLowerCase();
        Preset preset = presets.get(name);
        if (preset == null) {
            throw new IllegalStateException("Unknown llm.provider \"%s\". Options: %s"
                    .formatted(name, String.join(", ", presets.keySet())));
        }
        String url = firstSet(baseUrl, preset.baseUrl()).replaceAll("/+$", "");
        String modelId = firstSet(model, preset.model());
        String key = firstSet(apiKey, preset.apiKey());
        if (url.isEmpty()) throw new IllegalStateException("Set LLM_BASE_URL (llm.base-url) for provider \"" + name + "\".");
        if (modelId.isEmpty()) throw new IllegalStateException("Set LLM_MODEL (llm.model) for provider \"" + name + "\".");
        if (key.isEmpty() && !name.equals("custom")) {
            throw new IllegalStateException("Missing API key for \"%s\": add it to .env (see application.yml llm.presets.%s.api-key)."
                    .formatted(name, name));
        }
        return new LlmProvider(name, url, modelId, key);
    }

    private static String firstSet(@Nullable String override, @Nullable String fallback) {
        if (override != null && !override.isBlank()) return override.trim();
        return fallback == null ? "" : fallback.trim();
    }
}
