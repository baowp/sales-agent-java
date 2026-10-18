package com.sockspal.agent.memory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The sales-team search over saved chats. With an embedding model it is semantic: each turn
 * ("Customer: ... Agent: ...") is embedded once, in the background, and a query is ranked by cosine
 * similarity. Without one, or if the embedding call fails, it falls back to a keyword match.
 *
 * <p>Only the sales page uses this. The agent never searches other customers' chats.
 */
public class ChatSearch implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatSearch.class);
    private static final int MAX_EMBED_CHARS = 6000;

    public record Hit(long turnId, String conversationId, String visitor, OffsetDateTime at, String question,
                      String answer, List<String> tools, @Nullable Double score) {}

    /** {@code mode} is "semantic" or "keyword"; {@code note} explains a fallback. */
    public record Result(String mode, List<Hit> hits, @Nullable String note) {}

    private final ChatStore store;
    private final @Nullable EmbeddingModel model;
    private final String modelName;
    /** One thread, so turns are embedded one at a time and in order. */
    private final ExecutorService indexer = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat-index").factory());

    public ChatSearch(ChatStore store, @Nullable EmbeddingModel model, String modelName) {
        this.store = store;
        this.model = model;
        this.modelName = modelName;
    }

    public boolean isSemantic() {
        return model != null;
    }

    public String modelName() {
        return modelName;
    }

    /** Embeds new turns in the background. Called after every saved turn. */
    public void indexLater() {
        if (model != null) indexer.submit(this::indexPending);
    }

    public Result search(String query, int limit) {
        if (model == null) return keyword(query, limit, null);
        try {
            indexer.submit(this::indexPending).get(); // include turns saved since the last background run
            float[] q = model.embed(query);
            var ranked = store.embeddings(modelName).stream()
                    .map(e -> Map.entry(e.turnId(), cosine(q, e.vector())))
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(limit)
                    .toList();
            Map<Long, ChatStore.Turn> turns = store.turns(ranked.stream().map(Map.Entry::getKey).toList());
            return new Result("semantic", ranked.stream()
                    .filter(e -> turns.containsKey(e.getKey()))
                    .map(e -> hit(turns.get(e.getKey()), e.getValue()))
                    .toList(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while indexing", e);
        } catch (ExecutionException | RuntimeException e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            log.warn("Semantic search failed, using keyword search: {}", cause.getMessage());
            return keyword(query, limit, "Embedding model " + modelName + " failed (" + cause.getMessage()
                    + "), so these are keyword matches.");
        }
    }

    private Result keyword(String query, int limit, @Nullable String note) {
        return new Result("keyword", store.keywordSearch(query, limit).stream().map(t -> hit(t, null)).toList(), note);
    }

    /** Embeds every turn that has no vector from the current model. Stops at the first failure (quota, bad key). */
    private int indexPending() {
        int done = 0;
        for (List<ChatStore.Turn> batch; !(batch = store.turnsToEmbed(modelName, 20)).isEmpty(); ) {
            for (ChatStore.Turn turn : batch) {
                try {
                    store.saveEmbedding(turn.id(), modelName, model.embed(text(turn)));
                    done++;
                } catch (RuntimeException e) {
                    log.warn("Could not embed turn {} with {}: {}", turn.id(), modelName, e.getMessage());
                    return done;
                }
            }
        }
        if (done > 0) log.info("Embedded {} chat turn(s) with {}", done, modelName);
        return done;
    }

    static String text(ChatStore.Turn turn) {
        String text = "Customer: " + turn.question() + "\nAgent: " + turn.answer();
        return text.length() <= MAX_EMBED_CHARS ? text : text.substring(0, MAX_EMBED_CHARS);
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return -1;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static Hit hit(ChatStore.Turn t, @Nullable Double score) {
        return new Hit(t.id(), t.conversationId(), t.visitorId().substring(0, Math.min(8, t.visitorId().length())),
                t.at(), t.question(), t.answer(), t.tools(), score);
    }

    @Override
    public void close() {
        indexer.shutdownNow();
    }
}
