package com.sockspal.agent.memory;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Asks the chat model for a 2-6 word title. One short call per new chat; any failure uses the first question. */
public class LlmChatTitler implements ChatTitler {

    private static final Logger log = LoggerFactory.getLogger(LlmChatTitler.class);
    private static final String INSTRUCTIONS = """
            You name chats between a customer and a sock supplier's sales assistant.
            Reply with only a title of 2 to 6 words that says what the customer wants, in the customer's language.
            No quotes, no markdown, no full stop. Example: 500 black soccer socks to LA""";

    private final ChatModel chatModel;
    private final ChatOptions options;

    public LlmChatTitler(ChatModel chatModel) {
        this.chatModel = chatModel;
        // Same model and temperature, but no tools: the reply must be plain text.
        this.options = ((ToolCallingChatOptions) chatModel.getDefaultOptions()).mutate().toolCallbacks(List.of()).build();
    }

    @Override
    public String title(String question, String answer) {
        try {
            String excerpt = "Customer: " + question + "\nAssistant: "
                    + (answer.length() <= 500 ? answer : answer.substring(0, 500));
            String text = chatModel.call(new Prompt(List.of(new SystemMessage(INSTRUCTIONS), new UserMessage(excerpt)), options))
                    .getResult().getOutput().getText();
            String title = text == null ? "" : text.lines().filter(l -> !l.isBlank()).findFirst().orElse("")
                    .replaceAll("[*#\"“”'`]", "").replaceAll("^(?i)title:\\s*", "").replaceAll("[.。]+$", "").trim();
            return title.isEmpty() ? ChatTitler.fallback(question) : ChatTitler.shorten(title);
        } catch (RuntimeException e) {
            log.warn("Could not title the chat, using the first question: {}", e.getMessage());
            return ChatTitler.fallback(question);
        }
    }
}
