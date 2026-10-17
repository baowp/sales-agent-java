package com.sockspal.agent.config;

/** The LLM the agent talks to, resolved from {@link LlmProperties} (application.yml + .env). */
public record LlmProvider(String name, String baseUrl, String model, String apiKey) {}
