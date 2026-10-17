package com.sockspal.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code sales.*} settings from application.yml (each can be overridden from .env). */
@ConfigurationProperties("sales")
public record AgentProperties(
        int maxSteps,
        int requestTimeoutSeconds,
        String sockspalApi,
        int catalogCacheSeconds,
        String rfqMode,
        String rfqDir) {

    public boolean submitRfqs() {
        return "submit".equalsIgnoreCase(rfqMode);
    }
}
