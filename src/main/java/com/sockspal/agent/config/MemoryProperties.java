package com.sockspal.agent.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code memory.*} settings from application.yml: saved chats, customer recall and the sales search. */
@ConfigurationProperties("memory")
public record MemoryProperties(@Nullable String searchToken, int recallConversations) {

    public boolean searchEnabled() {
        return searchToken != null && !searchToken.isBlank();
    }
}
