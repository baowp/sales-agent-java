package com.sockspal.agent.memory;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import tools.jackson.databind.JsonNode;

/**
 * What {@code get_customer_history} returns to the model: one visitor's RFQs and recent earlier chats, cut down
 * so a long history doesn't fill the context window. Only ever built for the visitor's own browser token.
 */
public record CustomerHistory(List<PastRfq> rfqs, List<PastConversation> earlierConversations) {

    private static final int RFQS = 10;
    private static final int TURNS_PER_CONVERSATION = 8;
    private static final int CUSTOMER_CHARS = 400;
    private static final int AGENT_CHARS = 600;

    /** {@code request} is what the customer confirmed: contact, company, destination, Incoterm, items. */
    public record PastRfq(String rfqNumber, String status, String date, JsonNode request) {}

    public record PastConversation(String date, List<PastMessage> messages) {}

    public record PastMessage(String customer, String agent) {}

    public static CustomerHistory load(ChatStore store, String visitorId, String currentConversationId, int conversations) {
        List<PastRfq> rfqs = store.rfqsOfVisitor(visitorId, RFQS).stream()
                .map(r -> new PastRfq(r.id(), r.status(), r.at().toLocalDate().toString(), r.request()))
                .toList();
        List<PastConversation> past = store.recentConversations(visitorId, currentConversationId, conversations).stream()
                .map(c -> new PastConversation(c.startedAt().toLocalDate().toString(),
                        c.turns().subList(Math.max(0, c.turns().size() - TURNS_PER_CONVERSATION), c.turns().size()).stream()
                                .map(t -> new PastMessage(cut(t.question(), CUSTOMER_CHARS), cut(t.answer(), AGENT_CHARS)))
                                .toList()))
                .toList();
        return new CustomerHistory(rfqs, past);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return rfqs.isEmpty() && earlierConversations.isEmpty();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
