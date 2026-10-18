package com.sockspal.agent.agent;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Who the agent is talking to, handed to tools through Spring AI's {@link ToolContext} (never sent to the model).
 * The web chat sets both ids; the CLI uses {@link #NONE}.
 */
public record ChatContext(@Nullable String visitorId, @Nullable String conversationId) {

    public static final ChatContext NONE = new ChatContext(null, null);

    private static final String KEY = "chat";

    /** Always non-empty: Spring AI refuses to call a tool that takes a ToolContext with an empty one. */
    Map<String, Object> toMap() {
        return Map.of(KEY, this);
    }

    static ChatContext from(@Nullable ToolContext context) {
        return context != null && context.getContext().get(KEY) instanceof ChatContext c ? c : NONE;
    }
}
