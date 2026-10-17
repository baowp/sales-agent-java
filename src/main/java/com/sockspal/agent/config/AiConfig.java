package com.sockspal.agent.config;

import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.sockspal.agent.agent.SalesTools;

@Configuration
public class AiConfig {

    /** Prefix the agent loop looks for to report a failed tool call. */
    public static final String TOOL_ERROR_PREFIX = "ERROR: ";

    @Bean
    LlmProvider llmProvider(Environment env) {
        return LlmProvider.resolve(env);
    }

    /**
     * One chat model for the configured provider. Its default options carry the model, the temperature and
     * the tool definitions, so every request advertises the same five tools.
     * In Spring AI 2.x, {@code ChatModel.call()} makes exactly one request and never runs tools itself:
     * the loop lives in {@code SalesAgent}.
     */
    @Bean
    ChatModel chatModel(LlmProvider provider, SalesTools tools, AgentProperties props) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(provider.apiKey().isBlank() ? "none" : provider.apiKey()) // local servers ignore the key
                .model(provider.model())
                .temperature(0.2)
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .maxRetries(0) // SalesAgent retries itself so it can show "retrying" in the UI
                .toolCallbacks(ToolCallbacks.from(tools))
                .build();
        return OpenAiChatModel.builder().options(options).build();
    }

    /**
     * Runs the tool calls the model asks for. A tool that throws doesn't stop the turn: the error text
     * goes back to the model as the tool result, so it can tell the customer instead of guessing.
     */
    @Bean
    public ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(AiConfig::toolErrorMessage)
                .build();
    }

    static String toolErrorMessage(ToolExecutionException ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return TOOL_ERROR_PREFIX + cause.getMessage() + ". Do not guess this data.";
    }
}
