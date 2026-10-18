package com.sockspal.agent.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sockspal.agent.agent.SalesTools;
import com.sockspal.agent.memory.ChatSearch;
import com.sockspal.agent.memory.ChatStore;
import com.sockspal.agent.memory.ChatTitler;
import com.sockspal.agent.memory.LlmChatTitler;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /** Prefix the agent loop looks for to report a failed tool call. */
    public static final String TOOL_ERROR_PREFIX = "ERROR: ";

    @Bean
    LlmProvider llmProvider(LlmProperties llm) {
        return llm.resolve();
    }

    /**
     * One chat model for the configured provider. Its default options carry the model, the temperature and
     * the tool definitions, so every request advertises the same six tools.
     * In Spring AI 2.x, {@code ChatModel.call()} makes exactly one request and never runs tools itself:
     * the loop lives in {@code SalesAgent}.
     */
    @Bean
    ChatModel chatModel(LlmProvider provider, LlmProperties llm, SalesTools tools, AgentProperties props) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(provider.apiKey().isBlank() ? "none" : provider.apiKey()) // local servers ignore the key
                .model(provider.model())
                .temperature(llm.temperature())
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .maxRetries(0) // SalesAgent retries itself so it can show "retrying" in the UI
                .toolCallbacks(ToolCallbacks.from(tools))
                .build();
        return OpenAiChatModel.builder().options(options).build();
    }

    /** Titles for the customer's chat list, written by the same chat model. */
    @Bean
    ChatTitler chatTitler(ChatModel chatModel) {
        return new LlmChatTitler(chatModel);
    }

    /**
     * The sales-team search over saved chats. Semantic when the provider has an embedding model
     * (llm.embedding-model or the preset's), keyword-only otherwise. Uses the chat model's URL and key.
     */
    @Bean
    ChatSearch chatSearch(ChatStore store, LlmProvider provider, LlmProperties llm, AgentProperties props) {
        String model = llm.resolveEmbeddingModel();
        if (model.isEmpty()) {
            log.info("No embedding model for {}: the sales-team chat search uses keyword matching", provider.name());
            return new ChatSearch(store, null, "");
        }
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(provider.apiKey().isBlank() ? "none" : provider.apiKey())
                .model(model)
                .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT) // some compatible APIs reject base64
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .build();
        return new ChatSearch(store, OpenAiEmbeddingModel.builder().options(options).build(), model);
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
