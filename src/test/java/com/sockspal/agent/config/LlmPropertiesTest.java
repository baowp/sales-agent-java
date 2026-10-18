package com.sockspal.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/** Binds the real application.yml, with .env-style values supplied by the test instead of a file. */
class LlmPropertiesTest {

    private static Binder binder(Map<String, Object> dotEnv) throws IOException {
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("dotenv", dotEnv));
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    private static LlmProperties llm(Map<String, Object> dotEnv) throws IOException {
        return binder(dotEnv).bind("llm", LlmProperties.class).get();
    }

    @Test
    void defaultsToBailianWithItsKeyFromDotEnv() throws IOException {
        LlmProperties llm = llm(Map.of("DASHSCOPE_API_KEY", "test-key"));

        assertThat(llm.presets()).hasSize(9).containsKeys("groq", "bailian", "glm", "custom");
        assertThat(llm.temperature()).isEqualTo(0.2);
        assertThat(llm.resolve()).isEqualTo(new LlmProvider("bailian",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.7-flash-2026-07-15", "test-key"));
    }

    @Test
    void embeddingModelComesFromThePresetAndCanBeOverriddenOrMissing() throws IOException {
        assertThat(llm(Map.of()).resolveEmbeddingModel()).isEqualTo("text-embedding-v4");
        assertThat(llm(Map.of("LLM_EMBEDDING_MODEL", "text-embedding-v3")).resolveEmbeddingModel()).isEqualTo("text-embedding-v3");
        assertThat(llm(Map.of("LLM_PROVIDER", "groq")).resolveEmbeddingModel()).isEmpty(); // keyword search only
    }

    @Test
    void dotEnvCanSwitchProviderAndOverrideModel() throws IOException {
        LlmProvider p = llm(Map.of("LLM_PROVIDER", "groq", "GROQ_API_KEY", "g-key", "LLM_MODEL", "llama-4")).resolve();

        assertThat(p).isEqualTo(new LlmProvider("groq", "https://api.groq.com/openai/v1", "llama-4", "g-key"));
    }

    @Test
    void customProviderNeedsUrlAndModelButNoKey() throws IOException {
        LlmProvider p = llm(Map.of("LLM_PROVIDER", "custom",
                "LLM_BASE_URL", "http://localhost:11434/v1/", "LLM_MODEL", "qwen3")).resolve();

        assertThat(p).isEqualTo(new LlmProvider("custom", "http://localhost:11434/v1", "qwen3", ""));
        assertThatThrownBy(() -> llm(Map.of("LLM_PROVIDER", "custom")).resolve())
                .hasMessage("Set LLM_BASE_URL (llm.base-url) for provider \"custom\".");
    }

    @Test
    void missingKeyAndUnknownProviderFailWithClearMessages() throws IOException {
        assertThatThrownBy(() -> llm(Map.of()).resolve())
                .hasMessage("Missing API key for \"bailian\": add it to .env (see application.yml llm.presets.bailian.api-key).");
        assertThatThrownBy(() -> llm(Map.of("LLM_PROVIDER", "openai", "LLM_API_KEY", "x")).resolve())
                .hasMessage("Unknown llm.provider \"openai\". Options: groq, gemini, mistral, openrouter, glm, bailian, nvidia, ollama, custom");
    }

    @Test
    void salesSettingsStillBindWithDefaults() throws IOException {
        AgentProperties sales = binder(Map.of("RFQ_MODE", "submit")).bind("sales", AgentProperties.class).get();

        assertThat(sales.maxSteps()).isEqualTo(12);
        assertThat(sales.sockspalApi()).isEqualTo("https://api.sockspal.com/api/v1");
        assertThat(sales.submitRfqs()).isTrue();
    }
}
