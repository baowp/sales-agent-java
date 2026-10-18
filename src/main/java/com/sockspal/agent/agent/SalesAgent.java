package com.sockspal.agent.agent;

import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.config.AiConfig;
import com.sockspal.agent.config.LlmProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

/**
 * The agent loop, written out instead of hidden in {@code ChatClient}:
 *
 * <pre>
 *   ┌─▶ 1. chatModel.call(prompt)                  one LLM round trip (history + tool list)
 *   │   2. no tool calls?  → that's the answer
 *   │   3. toolCallingManager.executeToolCalls()   runs the @Tool methods the model asked for
 *   └── 4. history = conversation + tool results   so the model can read them next round
 * </pre>
 */
@Service
public class SalesAgent {

    private static final Logger log = LoggerFactory.getLogger(SalesAgent.class);
    private static final int MAX_RETRIES = 4;
    private static final int PREVIEW_CHARS = 300;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final LlmProvider provider;
    private final int maxSteps;
    private final Set<String> toolNames;

    public SalesAgent(ChatModel chatModel, ToolCallingManager toolCallingManager, LlmProvider provider,
                      AgentProperties props) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.provider = provider;
        this.maxSteps = props.maxSteps();
        this.toolNames = ((ToolCallingChatOptions) chatModel.getDefaultOptions()).getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition)
                .map(d -> d.name())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public List<Message> newHistory() {
        return new ArrayList<>(List.of(new SystemMessage(SystemPrompt.text(LocalDate.now()))));
    }

    /**
     * Runs one customer message through the loop. Mutates {@code history} (system prompt + earlier turns)
     * and reports progress through {@code events}.
     */
    public String ask(List<Message> history, String question, Consumer<AgentEvent> events) {
        long started = System.nanoTime();
        log.info("Question: {}", preview(question));
        history.add(new UserMessage(question));
        // Model name, temperature and the five tool definitions (set up in AiConfig).
        ChatOptions options = chatModel.getDefaultOptions();

        for (int step = 1; step <= maxSteps; step++) {
            // 1. One round trip: the whole conversation plus the tool list.
            Prompt prompt = new Prompt(List.copyOf(history), options);
            log.info("Step {}/{}: calling {} with {} messages", step, maxSteps, provider.model(), history.size());
            long callStarted = System.nanoTime();
            ChatResponse response = callModel(prompt, events);
            AssistantMessage reply = response.getResult().getOutput();
            log.info("Step {}: model replied in {} ms ({})", step, millisSince(callStarted), usage(response));

            // 2. No tool calls: the reply is the answer to the customer.
            if (!response.hasToolCalls()) {
                history.add(reply);
                String text = textOrDefault(reply, "(no answer)");
                log.info("Step {}: final answer after {} ms: {}", step, millisSince(started), preview(text));
                return text;
            }

            // 3. Run the tools the model asked for. Spring AI parses the JSON arguments and calls the
            //    matching @Tool method in SalesTools; this code decides when, and sees every call.
            for (ToolCall call : reply.getToolCalls()) {
                log.info("Step {}: tool call {}({})", step, call.name(), call.arguments());
                events.accept(new AgentEvent.ToolCall(call.name(), parseArgs(call.arguments())));
            }
            long toolsStarted = System.nanoTime();
            List<Message> next = runTools(prompt, response, reply);
            log.info("Step {}: {} tool call(s) finished in {} ms", step, reply.getToolCalls().size(),
                    millisSince(toolsStarted));

            // 4. The new history is the prompt + the model's tool request + the tool results.
            //    Loop so the model can read the results and answer or ask for more.
            history.clear();
            history.addAll(next);
            report(step, (ToolResponseMessage) next.getLast(), events);
        }

        log.warn("Step limit {} reached, asking the model to answer with what it has", maxSteps);
        history.add(new UserMessage("Step limit reached. Reply to the customer now with what you have."));
        AssistantMessage last = callModel(new Prompt(List.copyOf(history), options), events).getResult().getOutput();
        String text = textOrDefault(last, "(no answer within step limit)");
        log.info("Final answer after {} ms: {}", millisSince(started), preview(text));
        // Store text only: a tool request without its results would break the next turn.
        history.add(AssistantMessage.builder().content(text).build());
        return text;
    }

    /** Step 3. Unknown tool names make executeToolCalls throw, so they're answered with an error instead. */
    private List<Message> runTools(Prompt prompt, ChatResponse response, AssistantMessage reply) {
        List<String> unknown = reply.getToolCalls().stream()
                .map(ToolCall::name).filter(name -> !toolNames.contains(name)).toList();
        if (!unknown.isEmpty()) {
            log.warn("Model asked for unknown tool(s) {}; none were run", unknown);
            return failAll(prompt, reply, "Unknown tool %s. Available tools: %s. No tools were run; call them again"
                    .formatted(String.join(", ", unknown), String.join(", ", toolNames)));
        }
        try {
            return toolCallingManager.executeToolCalls(prompt, response).conversationHistory();
        } catch (RuntimeException e) {
            // e.g. arguments that aren't valid JSON. Exceptions inside a tool are handled by
            // AiConfig.toolErrorMessage and come back as normal tool results.
            log.warn("Tool calls could not be run: {}", e.getMessage());
            return failAll(prompt, reply, e.getMessage());
        }
    }

    private static List<Message> failAll(Prompt prompt, AssistantMessage reply, String message) {
        List<ToolResponse> responses = reply.getToolCalls().stream()
                .map(c -> new ToolResponse(c.id(), c.name(), AiConfig.TOOL_ERROR_PREFIX + message + ". Do not guess this data."))
                .toList();
        List<Message> next = new ArrayList<>(prompt.getInstructions());
        next.add(reply);
        next.add(ToolResponseMessage.builder().responses(responses).build());
        return next;
    }

    /** Turns tool results into UI events: failures, and the RFQ id once one is saved. */
    private static void report(int step, ToolResponseMessage results, Consumer<AgentEvent> events) {
        for (ToolResponse r : results.getResponses()) {
            String data = r.responseData();
            log.debug("Step {}: tool result {} (full): {}", step, r.name(), data);
            if (data.startsWith(AiConfig.TOOL_ERROR_PREFIX)) {
                String message = data.substring(AiConfig.TOOL_ERROR_PREFIX.length()).replaceFirst("\\. Do not guess this data\\.$", "");
                log.warn("Step {}: tool {} failed: {}", step, r.name(), message);
                events.accept(new AgentEvent.ToolError(r.name(), message));
                continue;
            }
            log.info("Step {}: tool result {} ({} chars): {}", step, r.name(), data.length(), preview(data));
            if (r.name().equals("create_rfq")) {
                JsonNode json = parseArgs(data);
                if (json.hasNonNull("id") && json.hasNonNull("status")) {
                    events.accept(new AgentEvent.RfqSaved(json.get("id").asString(), json.get("status").asString()));
                }
            }
        }
    }

    /** Step 1, with retries on rate limits (429), server errors (5xx) and timeouts. */
    private ChatResponse callModel(Prompt prompt, Consumer<AgentEvent> events) {
        for (int attempt = 0; ; attempt++) {
            try {
                return chatModel.call(prompt);
            } catch (RuntimeException e) {
                OpenAIServiceException http = cause(e, OpenAIServiceException.class);
                boolean retryable = http != null && (http.statusCode() == 429 || http.statusCode() >= 500);
                if (retryable && attempt < MAX_RETRIES) {
                    long waitMs = Math.min(retryAfterMs(http).orElse(2000L << attempt), 60_000L);
                    log.warn("{} returned HTTP {}, retry {}/{} in {} ms", provider.name(), http.statusCode(),
                            attempt + 1, MAX_RETRIES, waitMs);
                    events.accept(new AgentEvent.Retry(String.valueOf(http.statusCode()), Math.round(waitMs / 1000.0)));
                    sleep(waitMs);
                    continue;
                }
                if (http == null && isTimeout(e) && attempt < MAX_RETRIES) {
                    log.warn("{} timed out, retry {}/{}", provider.name(), attempt + 1, MAX_RETRIES);
                    events.accept(new AgentEvent.Retry("timeout", 0));
                    continue;
                }
                if (http != null) {
                    String body = String.valueOf(http.body());
                    throw new IllegalStateException("%s API error %d: %s".formatted(provider.name(), http.statusCode(),
                            body.substring(0, Math.min(body.length(), 500))), e);
                }
                throw e;
            }
        }
    }

    private static Optional<Long> retryAfterMs(OpenAIServiceException e) {
        return e.headers().values("retry-after").stream().findFirst().flatMap(v -> {
            try {
                return Optional.of((long) (Double.parseDouble(v.trim()) * 1000));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        });
    }

    private static boolean isTimeout(Throwable e) {
        return Stream.iterate(e, t -> t != null, Throwable::getCause).anyMatch(t ->
                t instanceof InterruptedIOException || t instanceof HttpTimeoutException
                        || t instanceof OpenAIRetryableException
                        || t instanceof OpenAIIoException && String.valueOf(t.getMessage()).toLowerCase().contains("timeout"));
    }

    private static <T extends Throwable> T cause(Throwable e, Class<T> type) {
        return Stream.iterate(e, t -> t != null, Throwable::getCause).filter(type::isInstance).map(type::cast)
                .findFirst().orElse(null);
    }

    private static JsonNode parseArgs(String json) {
        try {
            return json == null || json.isBlank() ? JSON.createObjectNode() : JSON.readTree(json);
        } catch (RuntimeException e) {
            return StringNode.valueOf(json);
        }
    }

    /** One line, cut to PREVIEW_CHARS. Full tool results are logged at DEBUG. */
    private static String preview(String text) {
        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() <= PREVIEW_CHARS ? line
                : line.substring(0, PREVIEW_CHARS) + "... (" + line.length() + " chars)";
    }

    private static String usage(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        return usage == null ? "no token usage"
                : "tokens in=%s out=%s".formatted(usage.getPromptTokens(), usage.getCompletionTokens());
    }

    private static long millisSince(long nanoTime) {
        return (System.nanoTime() - nanoTime) / 1_000_000;
    }

    private static String textOrDefault(AssistantMessage message, String fallback) {
        String text = message.getText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
