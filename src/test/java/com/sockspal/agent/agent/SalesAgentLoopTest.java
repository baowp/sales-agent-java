package com.sockspal.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;

import com.sockspal.agent.Fixtures;
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.config.AiConfig;
import com.sockspal.agent.config.LlmProvider;

/** Drives the real loop, real ToolCallingManager and real tools with a scripted model. */
public class SalesAgentLoopTest {

    @TempDir
    Path rfqDir;

    /** Replies from a script and records every prompt it receives. */
    public static final class ScriptedModel implements ChatModel {
        final Deque<AssistantMessage> script = new ArrayDeque<>();
        final List<Prompt> prompts = new ArrayList<>();
        final ChatOptions options;

        public ScriptedModel(SalesTools tools) {
            this.options = ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(tools)).build();
        }

        public ScriptedModel then(AssistantMessage reply) {
            script.add(reply);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(script.removeFirst())));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return options;
        }
    }

    public static AssistantMessage callTool(String id, String name, String args) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new ToolCall(id, "function", name, args))).build();
    }

    public static AssistantMessage say(String text) {
        return AssistantMessage.builder().content(text).build();
    }

    private SalesAgent agent(ChatModel model, int maxSteps) {
        return new SalesAgent(model, new AiConfig().toolCallingManager(),
                new LlmProvider("fake", "http://localhost", "scripted", "key"),
                new AgentProperties(maxSteps, 5, "http://unused", 60, "draft", rfqDir.toString()));
    }

    @Test
    void runsToolsFeedsResultsBackAndRecoversFromBadCalls() {
        var model = new ScriptedModel(Fixtures.tools(rfqDir))
                .then(callTool("c1", "calculate_quote", "{\"product\":\"soccer-socks\",\"quantityPairs\":500,\"color\":\"Black\"}"))
                .then(callTool("c2", "get_price", "{}"))                                   // a tool that doesn't exist
                .then(callTool("c3", "get_product", "{\"product\":\"wool hiking socks\"}")) // a tool that throws
                .then(say("500 pairs of black soccer socks are $0.45/pair, $225.00 in total."));
        SalesAgent agent = agent(model, 12);
        List<AgentEvent> events = new ArrayList<>();
        List<Message> history = agent.newHistory();

        String answer = agent.ask(history, "Price for 500 black soccer socks?", events::add);

        assertThat(answer).isEqualTo("500 pairs of black soccer socks are $0.45/pair, $225.00 in total.");
        assertThat(model.prompts).hasSize(4); // one LLM round trip per loop step

        // Step 2 saw the real quote computed by Catalog.quote()
        String quoteResult = toolResult(model.prompts.get(1));
        assertThat(quoteResult).contains("\"unitPrice\":\"$0.45\"", "\"goodsSubtotal\":\"$225.00\"", "\"readyStockPairs\":50");

        // Step 3 was told the tool name was wrong, step 4 that the product wasn't found
        assertThat(toolResult(model.prompts.get(2))).startsWith("ERROR: Unknown tool get_price. Available tools: calculate_quote, create_rfq");
        assertThat(toolResult(model.prompts.get(3))).startsWith("ERROR: No product matches \"wool hiking socks\"")
                .endsWith("Do not guess this data.");

        assertThat(events).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("ToolCall", "ToolCall", "ToolError", "ToolCall", "ToolError");
        assertThat(events.get(0)).isInstanceOfSatisfying(AgentEvent.ToolCall.class,
                e -> assertThat(e.args().get("quantityPairs").asInt()).isEqualTo(500));

        // history: system, user, then (assistant tool request + tool results) x3, then the answer
        assertThat(history).extracting(m -> m.getMessageType().name())
                .containsExactly("SYSTEM", "USER", "ASSISTANT", "TOOL", "ASSISTANT", "TOOL", "ASSISTANT", "TOOL", "ASSISTANT");
    }

    @Test
    void stepLimitForcesAnAnswer() {
        var model = new ScriptedModel(Fixtures.tools(rfqDir))
                .then(callTool("c1", "list_products", "{}"))
                .then(callTool("c2", "list_products", "{\"category\":\"Sport\"}"))
                .then(say("Here is what I found so far."));
        SalesAgent agent = agent(model, 2);
        List<Message> history = agent.newHistory();

        assertThat(agent.ask(history, "What do you sell?", e -> { })).isEqualTo("Here is what I found so far.");
        assertThat(model.prompts.getLast().getInstructions().getLast().getText()).startsWith("Step limit reached.");
    }

    private static String toolResult(Prompt prompt) {
        Message last = prompt.getInstructions().getLast();
        assertThat(last).isInstanceOf(ToolResponseMessage.class);
        return ((ToolResponseMessage) last).getResponses().getFirst().responseData();
    }
}
