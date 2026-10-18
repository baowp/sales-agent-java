package com.sockspal.agent.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved chats (see schema.sql): conversations linked by the visitor's browser token, their turns, and the RFQs
 * created in them. Used for customer recall ({@code get_customer_history}) and the sales-team search.
 */
@Repository
public class ChatStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TURN_COLUMNS = "t.id, t.conversation_id, c.visitor_id, t.question, t.answer, t.tools, t.created_at";

    public record Turn(long id, String conversationId, String visitorId, String question, String answer,
                       List<String> tools, OffsetDateTime at) {}

    public record Rfq(String id, String conversationId, String status, JsonNode request, OffsetDateTime at) {}

    public record Conversation(String id, String visitorId, @Nullable String title, OffsetDateTime startedAt,
                               OffsetDateTime updatedAt, List<Turn> turns, List<Rfq> rfqs) {}

    /** One row in the customer's chat list. Untitled chats use their first question. */
    public record ConversationSummary(String id, String title, OffsetDateTime updatedAt, int messages) {}

    /** What the system prompt says about a returning customer. */
    public record VisitorSummary(int conversations, int rfqs, @Nullable OffsetDateTime lastAt) {
        public boolean isReturning() {
            return conversations > 0 || rfqs > 0;
        }
    }

    public record Embedding(long turnId, float[] vector) {}

    private final JdbcClient jdbc;

    public ChatStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the conversation row on its first message, afterwards bumps updated_at. */
    public void touch(String conversationId, String visitorId) {
        OffsetDateTime now = now();
        int updated = jdbc.sql("UPDATE conversation SET updated_at = ? WHERE id = ?").params(now, conversationId).update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO conversation (id, visitor_id, started_at, updated_at) VALUES (?, ?, ?, ?)")
                    .params(conversationId, visitorId, now, now).update();
        }
    }

    /** Saves one answered customer message and returns its id. */
    public long saveTurn(String conversationId, String question, String answer, Collection<String> tools) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO turn (conversation_id, question, answer, tools, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(conversationId, question, answer, String.join(",", tools), now())
                .update(key, "ID");
        return key.getKeyAs(Long.class);
    }

    /** Records an RFQ the agent created ({@code request} = the create_rfq arguments). */
    public void saveRfq(String id, String conversationId, String status, JsonNode request) {
        jdbc.sql("MERGE INTO rfq (id, conversation_id, status, request, created_at) KEY (id) VALUES (?, ?, ?, ?, ?)")
                .params(id, conversationId, status, JSON.writeValueAsString(request), now()).update();
    }

    public void setTitle(String conversationId, String title) {
        jdbc.sql("UPDATE conversation SET title = ? WHERE id = ?").params(title, conversationId).update();
    }

    /** The visitor's chats with at least one answered message, most recently used first. */
    public List<ConversationSummary> conversationsOf(String visitorId, int limit) {
        return jdbc.sql("""
                        SELECT c.id, c.updated_at,
                               COALESCE(c.title, (SELECT t.question FROM turn t WHERE t.conversation_id = c.id
                                                   ORDER BY t.id LIMIT 1)) AS title,
                               (SELECT COUNT(*) FROM turn t WHERE t.conversation_id = c.id) AS messages
                          FROM conversation c
                         WHERE c.visitor_id = ? AND EXISTS (SELECT 1 FROM turn t WHERE t.conversation_id = c.id)
                         ORDER BY c.updated_at DESC LIMIT ?""")
                .params(visitorId, limit)
                .query((rs, n) -> new ConversationSummary(rs.getString("id"), ChatTitler.shorten(rs.getString("title")),
                        rs.getObject("updated_at", OffsetDateTime.class), rs.getInt("messages")))
                .list();
    }

    /** The visitor's earlier conversations that have at least one answered message, and their RFQs. */
    public VisitorSummary summary(String visitorId, String currentConversationId) {
        return jdbc.sql("""
                        SELECT (SELECT COUNT(*) FROM conversation c WHERE c.visitor_id = :v AND c.id <> :cur
                                  AND EXISTS (SELECT 1 FROM turn t WHERE t.conversation_id = c.id)) AS conversations,
                               (SELECT COUNT(*) FROM rfq r JOIN conversation c ON c.id = r.conversation_id
                                 WHERE c.visitor_id = :v) AS rfqs,
                               (SELECT MAX(c.updated_at) FROM conversation c WHERE c.visitor_id = :v AND c.id <> :cur
                                  AND EXISTS (SELECT 1 FROM turn t WHERE t.conversation_id = c.id)) AS last_at""")
                .param("v", visitorId).param("cur", currentConversationId)
                .query((rs, n) -> new VisitorSummary(rs.getInt("conversations"), rs.getInt("rfqs"),
                        rs.getObject("last_at", OffsetDateTime.class)))
                .single();
    }

    /** The visitor's most recent earlier conversations, newest first, with their turns and RFQs. */
    public List<Conversation> recentConversations(String visitorId, String excludeConversationId, int limit) {
        List<String> ids = jdbc.sql("""
                        SELECT c.id FROM conversation c WHERE c.visitor_id = ? AND c.id <> ?
                           AND EXISTS (SELECT 1 FROM turn t WHERE t.conversation_id = c.id)
                         ORDER BY c.updated_at DESC LIMIT ?""")
                .params(visitorId, excludeConversationId, limit).query(String.class).list();
        return ids.stream().map(this::conversation).flatMap(Optional::stream).toList();
    }

    /** RFQs from any of the visitor's conversations (including the current one), newest first. */
    public List<Rfq> rfqsOfVisitor(String visitorId, int limit) {
        return jdbc.sql("""
                        SELECT r.* FROM rfq r JOIN conversation c ON c.id = r.conversation_id
                         WHERE c.visitor_id = ? ORDER BY r.created_at DESC LIMIT ?""")
                .params(visitorId, limit).query(ChatStore::rfq).list();
    }

    public Optional<Conversation> conversation(String id) {
        return jdbc.sql("SELECT * FROM conversation WHERE id = ?").param(id)
                .query((rs, n) -> new Conversation(rs.getString("id"), rs.getString("visitor_id"), rs.getString("title"),
                        rs.getObject("started_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                        jdbc.sql("SELECT " + TURN_COLUMNS + " FROM turn t JOIN conversation c ON c.id = t.conversation_id"
                                + " WHERE t.conversation_id = ? ORDER BY t.id").param(id).query(ChatStore::turn).list(),
                        jdbc.sql("SELECT * FROM rfq WHERE conversation_id = ? ORDER BY created_at").param(id)
                                .query(ChatStore::rfq).list()))
                .optional();
    }

    /** Turns with no embedding from {@code model} yet (new, or made by a model that has since been changed). */
    public List<Turn> turnsToEmbed(String model, int limit) {
        return jdbc.sql("SELECT " + TURN_COLUMNS + " FROM turn t JOIN conversation c ON c.id = t.conversation_id"
                        + " WHERE t.embedding IS NULL OR t.embedding_model <> ? ORDER BY t.id LIMIT ?")
                .params(model, limit).query(ChatStore::turn).list();
    }

    public void saveEmbedding(long turnId, String model, float[] vector) {
        ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asFloatBuffer().put(vector);
        jdbc.sql("UPDATE turn SET embedding = ?, embedding_model = ? WHERE id = ?")
                .params(bytes.array(), model, turnId).update();
    }

    /** Every turn's vector from {@code model}. Searched in memory, which is fine up to tens of thousands of turns. */
    public List<Embedding> embeddings(String model) {
        return jdbc.sql("SELECT id, embedding FROM turn WHERE embedding IS NOT NULL AND embedding_model = ?").param(model)
                .query((rs, n) -> {
                    var buffer = ByteBuffer.wrap(rs.getBytes("embedding")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                    float[] vector = new float[buffer.remaining()];
                    buffer.get(vector);
                    return new Embedding(rs.getLong("id"), vector);
                })
                .list();
    }

    public Map<Long, Turn> turns(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql("SELECT " + TURN_COLUMNS + " FROM turn t JOIN conversation c ON c.id = t.conversation_id"
                        + " WHERE t.id IN (:ids)")
                .param("ids", ids).query(ChatStore::turn).list().stream()
                .collect(Collectors.toMap(Turn::id, Function.identity()));
    }

    /** Case-insensitive substring match on the question or answer, newest first. */
    public List<Turn> keywordSearch(String text, int limit) {
        String like = "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("SELECT " + TURN_COLUMNS + " FROM turn t JOIN conversation c ON c.id = t.conversation_id"
                        + " WHERE LOWER(t.question) LIKE :q ESCAPE '\\' OR LOWER(t.answer) LIKE :q ESCAPE '\\'"
                        + " ORDER BY t.id DESC LIMIT :limit")
                .param("q", like).param("limit", limit).query(ChatStore::turn).list();
    }

    private static Turn turn(ResultSet rs, int row) throws SQLException {
        String tools = rs.getString("tools");
        return new Turn(rs.getLong("id"), rs.getString("conversation_id"), rs.getString("visitor_id"),
                rs.getString("question"), rs.getString("answer"),
                tools.isEmpty() ? List.of() : Arrays.asList(tools.split(",")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static Rfq rfq(ResultSet rs, int row) throws SQLException {
        return new Rfq(rs.getString("id"), rs.getString("conversation_id"), rs.getString("status"),
                JSON.readTree(rs.getString("request")), rs.getObject("created_at", OffsetDateTime.class));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
