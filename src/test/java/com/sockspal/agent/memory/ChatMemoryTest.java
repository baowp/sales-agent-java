package com.sockspal.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.sockspal.agent.Fixtures;
import com.sockspal.agent.agent.AgentEvent;

import tools.jackson.databind.json.JsonMapper;

/** Saved chats: what a returning customer's agent can see, RFQ capture, and the sales search. */
class ChatMemoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Counts a few words: enough to check ranking and storage without a real embedding API. */
    static final class WordCountEmbeddings implements EmbeddingModel {
        static final List<String> WORDS = List.of("grip", "pilates", "soccer", "black", "return", "shipping", "logo");
        final List<String> calls = new ArrayList<>();
        boolean failing;

        @Override
        public float[] embed(String text) {
            if (failing) throw new IllegalStateException("quota exceeded");
            calls.add(text);
            String lower = text.toLowerCase(Locale.ROOT);
            float[] v = new float[WORDS.size()];
            for (int i = 0; i < v.length; i++) v[i] = lower.split(WORDS.get(i), -1).length - 1;
            return v;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> out = new ArrayList<>();
            for (String text : request.getInstructions()) out.add(new Embedding(embed(text), out.size()));
            return new EmbeddingResponse(out);
        }
    }

    @Test
    void historyIsOnlyThisVisitorsEarlierChatsAndRfqs() {
        ChatStore store = Fixtures.chatStore();
        store.touch("c-old", "visitor-a");
        store.saveTurn("c-old", "500 black soccer socks to LA?", "That's $225.00 at $0.45/pair.", List.of("calculate_quote"));
        store.saveRfq("RFQ-1", "c-old", "submitted_to_sockspal", JSON.readTree("{\"companyName\":\"Football Club\"}"));
        store.touch("c-empty", "visitor-a");                         // opened but never answered: not history
        store.touch("c-other", "visitor-b");
        store.saveTurn("c-other", "Someone else's question", "Someone else's answer", List.of());
        store.touch("c-now", "visitor-a");
        store.saveTurn("c-now", "Same as last time but 1000 pairs", "Let me check.", List.of());

        var history = CustomerHistory.load(store, "visitor-a", "c-now", 3);

        assertThat(history.earlierConversations()).hasSize(1);
        assertThat(history.earlierConversations().getFirst().messages())
                .containsExactly(new CustomerHistory.PastMessage("500 black soccer socks to LA?", "That's $225.00 at $0.45/pair."));
        assertThat(history.rfqs()).extracting(CustomerHistory.PastRfq::rfqNumber).containsExactly("RFQ-1");
        assertThat(history.rfqs().getFirst().request().get("companyName").asString()).isEqualTo("Football Club");
        assertThat(JSON.writeValueAsString(history)).doesNotContain("Someone else", "\"empty\"");

        assertThat(store.summary("visitor-a", "c-now")).isEqualTo(
                new ChatStore.VisitorSummary(1, 1, store.conversation("c-old").orElseThrow().updatedAt()));
        assertThat(store.summary("visitor-new", "c-x").isReturning()).isFalse();
    }

    @Test
    void recorderSavesRfqWithoutTheConfirmationFlagAndTheTurnWithItsTools() {
        ChatStore store = Fixtures.chatStore();
        store.touch("c1", "visitor-a");
        List<AgentEvent> forwarded = new ArrayList<>();
        var recorder = new TurnRecorder(store, new ChatSearch(store, null, ""), "c1", forwarded::add);

        recorder.accept(new AgentEvent.ToolCall("create_rfq",
                JSON.readTree("{\"customerConfirmed\":true,\"companyName\":\"Football Club\",\"items\":[]}")));
        recorder.accept(new AgentEvent.RfqSaved("RFQ-20261018-7B8A8159", "submitted_to_sockspal"));
        recorder.finish("yes", "Your RFQ number is RFQ-20261018-7B8A8159.");

        assertThat(forwarded).hasSize(2);
        var saved = store.conversation("c1").orElseThrow();
        assertThat(saved.rfqs()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("RFQ-20261018-7B8A8159");
            assertThat(r.request().has("customerConfirmed")).isFalse();
            assertThat(r.request().get("companyName").asString()).isEqualTo("Football Club");
        });
        assertThat(saved.turns()).singleElement().satisfies(t -> assertThat(t.tools()).containsExactly("create_rfq"));
    }

    @Test
    void semanticSearchRanksByEmbeddingAndReembedsWhenTheModelChanges() {
        ChatStore store = Fixtures.chatStore();
        store.touch("c1", "visitor-aaaaaaaaaaaa");
        store.saveTurn("c1", "Do you have grip socks for a pilates studio?", "Yes, our Pilates grip socks...", List.of());
        store.saveTurn("c1", "Can I return unsold stock?", "Wholesale orders can't be returned...", List.of());
        store.touch("c2", "visitor-bbbbbbbbbbbb");
        store.saveTurn("c2", "Price for 500 black soccer socks?", "$0.45/pair.", List.of());
        var model = new WordCountEmbeddings();

        var result = new ChatSearch(store, model, "words-v1").search("pilates grip", 2);

        assertThat(result.mode()).isEqualTo("semantic");
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().getFirst().question()).startsWith("Do you have grip socks");
        assertThat(result.hits().getFirst().visitor()).isEqualTo("visitor-");   // shortened, never the full token
        assertThat(result.hits().getFirst().score()).isGreaterThan(result.hits().get(1).score());
        assertThat(model.calls).hasSize(4);                                      // 3 turns indexed once, plus the query
        assertThat(model.calls.getFirst()).startsWith("Customer: Do you have grip socks").contains("\nAgent: Yes");

        new ChatSearch(store, model, "words-v1").search("black soccer", 1);
        assertThat(model.calls).hasSize(5);                                      // nothing re-embedded
        new ChatSearch(store, model, "words-v2").search("black soccer", 1);
        assertThat(model.calls).hasSize(9);                                      // new model: all 3 again
    }

    @Test
    void searchFallsBackToKeywordsWithoutOrWhenTheEmbeddingModelFails() {
        ChatStore store = Fixtures.chatStore();
        store.touch("c1", "visitor-a");
        store.saveTurn("c1", "Do you print a LOGO on 100% cotton socks?", "Yes, custom logos are possible.", List.of());
        store.saveTurn("c1", "Shipping to Germany?", "Freight is quoted by the sales team.", List.of());

        var keyword = new ChatSearch(store, null, "").search("logo on 100%", 10);
        assertThat(keyword.mode()).isEqualTo("keyword");
        assertThat(keyword.hits()).extracting(ChatSearch.Hit::question).containsExactly("Do you print a LOGO on 100% cotton socks?");
        assertThat(new ChatSearch(store, null, "").search("50%", 10).hits()).isEmpty(); // % is literal, not a wildcard

        var model = new WordCountEmbeddings();
        model.failing = true;
        var fallback = new ChatSearch(store, model, "words-v1").search("shipping", 10);
        assertThat(fallback.mode()).isEqualTo("keyword");
        assertThat(fallback.note()).contains("quota exceeded");
        assertThat(fallback.hits()).extracting(ChatSearch.Hit::question).containsExactly("Shipping to Germany?");
    }
}
