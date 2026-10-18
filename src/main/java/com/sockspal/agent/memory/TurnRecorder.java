package com.sockspal.agent.memory;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sockspal.agent.agent.AgentEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sits between the agent loop and the web stream for one customer message: passes every event on, notes which
 * tools ran, and saves each RFQ as soon as it has an id (it exists even if the rest of the turn fails).
 * Saving problems are logged, never shown to the customer.
 */
public class TurnRecorder implements Consumer<AgentEvent> {

    private static final Logger log = LoggerFactory.getLogger(TurnRecorder.class);

    private final ChatStore store;
    private final ChatSearch search;
    private final String conversationId;
    private final Consumer<AgentEvent> next;
    private final Set<String> tools = new LinkedHashSet<>();
    private @Nullable JsonNode lastRfqRequest;

    public TurnRecorder(ChatStore store, ChatSearch search, String conversationId, Consumer<AgentEvent> next) {
        this.store = store;
        this.search = search;
        this.conversationId = conversationId;
        this.next = next;
    }

    @Override
    public void accept(AgentEvent event) {
        try {
            switch (event) {
                case AgentEvent.ToolCall call -> {
                    tools.add(call.name());
                    if (call.name().equals("create_rfq")) lastRfqRequest = call.args();
                }
                case AgentEvent.RfqSaved rfq -> store.saveRfq(rfq.id(), conversationId, rfq.status(), request());
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warn("Could not save {} for conversation {}: {}", event.getClass().getSimpleName(), conversationId, e.getMessage());
        }
        next.accept(event);
    }

    /** Saves the answered message and queues it for the search index. */
    public void finish(String question, String answer) {
        try {
            store.saveTurn(conversationId, question, answer, tools);
            search.indexLater();
        } catch (RuntimeException e) {
            log.warn("Could not save turn for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    /** The create_rfq arguments without the confirmation flag. */
    private JsonNode request() {
        if (lastRfqRequest instanceof ObjectNode args) {
            ObjectNode copy = args.deepCopy();
            copy.remove("customerConfirmed");
            return copy;
        }
        return lastRfqRequest == null ? JsonNodeFactory.instance.objectNode() : lastRfqRequest;
    }
}
