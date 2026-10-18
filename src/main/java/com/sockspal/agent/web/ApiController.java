package com.sockspal.agent.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.sockspal.agent.agent.AgentEvent;
import com.sockspal.agent.agent.ChatContext;
import com.sockspal.agent.agent.SalesAgent;
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.config.LlmProvider;
import com.sockspal.agent.memory.ChatSearch;
import com.sockspal.agent.memory.ChatStore;
import com.sockspal.agent.memory.ChatTitler;
import com.sockspal.agent.memory.TurnRecorder;
import com.sockspal.agent.sockspal.ApiException;
import com.sockspal.agent.sockspal.Catalog;
import com.sockspal.agent.sockspal.RfqService;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Same endpoints as the Node.js server.js, so the same page works against either backend. On top of that, every
 * answered message is saved, linked to the browser by a private visitor cookie, so a returning customer's
 * earlier chats and RFQs are available to the agent (get_customer_history) and listed in the side panel.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    static final String VISITOR_COOKIE = "sp_visitor";
    private static final Pattern VISITOR_ID = Pattern.compile("[A-Za-z0-9_-]{22,64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RESUME_TURNS = 20; // saved messages put back into a reopened chat

    public record Info(String provider, String model, String rfqMode, @Nullable String fetchedAt,
                       List<Catalog.ProductSummary> products, @Nullable String catalogError, long rfqs) {}

    public record AskRequest(@Nullable String question, @Nullable String sessionId) {}

    public record ResetRequest(@Nullable String sessionId) {}

    public record SavedTurn(String question, String answer) {}

    public record SavedRfq(String id, String status) {}

    public record SavedChat(String id, String title, List<SavedTurn> turns, List<SavedRfq> rfqs) {}

    /** A live conversation: the full message history (with tool calls) and the browser it belongs to. */
    private record Session(String visitorId, List<Message> history) {}

    private final SalesAgent agent;
    private final Catalog catalog;
    private final RfqService rfqs;
    private final LlmProvider provider;
    private final AgentProperties props;
    private final JsonMapper json;
    private final ChatStore store;
    private final ChatSearch search;
    private final ChatTitler titler;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>(); // sessionId (= conversation id) -> session

    public ApiController(SalesAgent agent, Catalog catalog, RfqService rfqs, LlmProvider provider,
                         AgentProperties props, JsonMapper json, ChatStore store, ChatSearch search, ChatTitler titler) {
        this.agent = agent;
        this.catalog = catalog;
        this.rfqs = rfqs;
        this.provider = provider;
        this.props = props;
        this.json = json;
        this.store = store;
        this.search = search;
        this.titler = titler;
    }

    @GetMapping("/info")
    public Info info() {
        List<Catalog.ProductSummary> products = List.of();
        String catalogError = null;
        try {
            products = catalog.load().summary();
        } catch (ApiException e) {
            catalogError = e.getMessage();
        }
        long count;
        try (var files = Files.list(rfqs.dir())) {
            count = files.filter(f -> f.toString().endsWith(".json")).count();
        } catch (IOException e) {
            count = 0;
        }
        var fetchedAt = catalog.fetchedAt();
        return new Info(provider.name(), provider.model(), props.submitRfqs() ? "submit" : "draft",
                fetchedAt == null ? null : fetchedAt.toString(), products, catalogError, count);
    }

    @PostMapping("/reload")
    public Info reload() {
        try {
            catalog.load(true);
        } catch (ApiException ignored) {
            // info() reports the error
        }
        return info();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody ResetRequest req) {
        if (req.sessionId() != null) sessions.remove(req.sessionId());
        return Map.of("ok", true);
    }

    /** Streams newline-delimited JSON: tool/retry/rfq events, then one answer or error. */
    @PostMapping("/ask")
    public ResponseEntity<StreamingResponseBody> ask(@RequestBody AskRequest req,
                                                     @CookieValue(name = VISITOR_COOKIE, required = false) @Nullable String cookie,
                                                     HttpServletRequest http) {
        String question = req.question() == null ? "" : req.question().trim();
        if (question.isEmpty()) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(out -> send(out, Map.of("error", "Empty message")));
        }

        String visitorId = validVisitor(cookie) != null ? cookie : newVisitorId();
        String sessionId = sessionFor(req.sessionId(), visitorId);
        List<Message> history = sessions.get(sessionId).history();

        StreamingResponseBody body = out -> {
            // Runs on another thread, so the session tag for the log lines is set here.
            MDC.put("session", sessionId.substring(0, 8));
            try {
                send(out, Map.of("type", "session", "sessionId", sessionId));
                String answer = null;
                boolean firstMessage = false;
                synchronized (history) { // one message at a time per conversation
                    int mark = history.size();
                    try {
                        touch(sessionId, visitorId);
                        firstMessage = mark == 1; // only the system prompt so far
                        var recorder = new TurnRecorder(store, search, sessionId, e -> send(out, e));
                        answer = agent.ask(history, question, new ChatContext(visitorId, sessionId), recorder);
                        recorder.finish(question, answer);
                        send(out, Map.of("type", "answer", "text", answer));
                    } catch (RuntimeException e) {
                        log.error("Question failed: {}", e.getMessage(), e);
                        history.subList(mark, history.size()).clear(); // drop the failed turn
                        send(out, Map.of("type", "error", "message", String.valueOf(e.getMessage())));
                    }
                }
                // After the answer, so naming the chat never delays it.
                if (answer != null && firstMessage) {
                    send(out, Map.of("type", "title", "sessionId", sessionId, "title", nameChat(sessionId, question, answer)));
                }
            } finally {
                MDC.remove("session");
            }
        };
        ResponseCookie visitor = ResponseCookie.from(VISITOR_COOKIE, visitorId)
                .httpOnly(true).secure(http.isSecure()).sameSite("Lax").path("/").maxAge(Duration.ofDays(365))
                .build();
        return ResponseEntity.ok().contentType(NDJSON).header("Cache-Control", "no-cache")
                .header(HttpHeaders.SET_COOKIE, visitor.toString()).body(body);
    }

    /** This browser's saved chats for the side panel, most recent first. */
    @GetMapping("/conversations")
    public List<ChatStore.ConversationSummary> conversations(
            @CookieValue(name = VISITOR_COOKIE, required = false) @Nullable String cookie) {
        String visitorId = validVisitor(cookie);
        return visitorId == null ? List.of() : store.conversationsOf(visitorId, 50);
    }

    /** One saved chat, to show it again. Only for the browser that had it. */
    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> conversation(@PathVariable String id,
                                          @CookieValue(name = VISITOR_COOKIE, required = false) @Nullable String cookie) {
        String visitorId = validVisitor(cookie);
        return store.conversation(id)
                .filter(c -> c.visitorId().equals(visitorId))
                .<ResponseEntity<?>>map(c -> ResponseEntity.ok(new SavedChat(c.id(),
                        c.title() != null ? c.title() : c.turns().isEmpty() ? "" : ChatTitler.fallback(c.turns().getFirst().question()),
                        c.turns().stream().map(t -> new SavedTurn(t.question(), t.answer())).toList(),
                        c.rfqs().stream().map(r -> new SavedRfq(r.id(), r.status())).toList())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Chat not found")));
    }

    /**
     * The conversation this message belongs to. A session id only continues a chat from the same browser: one
     * still in memory, or a saved one (after a restart, or picked from the chat list), rebuilt from its saved
     * messages. Anything else starts a new chat.
     */
    private String sessionFor(@Nullable String requested, String visitorId) {
        if (requested != null) {
            Session live = sessions.get(requested);
            if (live != null) {
                if (live.visitorId().equals(visitorId)) return requested;
            } else {
                var saved = savedConversation(requested, visitorId);
                if (saved != null) {
                    sessions.computeIfAbsent(requested, id -> new Session(visitorId, resumedHistory(saved, visitorId)));
                    return requested;
                }
            }
        }
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(visitorId, agent.newHistory(customerNote(visitorId, id))));
        return id;
    }

    private ChatStore.@Nullable Conversation savedConversation(String id, String visitorId) {
        try {
            return store.conversation(id).filter(c -> c.visitorId().equals(visitorId)).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not load conversation {}: {}", id, e.getMessage());
            return null;
        }
    }

    /** System prompt plus the saved questions and answers (tool calls aren't saved; the answers carry the facts). */
    private List<Message> resumedHistory(ChatStore.Conversation saved, String visitorId) {
        List<Message> history = agent.newHistory(customerNote(visitorId, saved.id()));
        List<ChatStore.Turn> turns = saved.turns();
        for (ChatStore.Turn t : turns.subList(Math.max(0, turns.size() - RESUME_TURNS), turns.size())) {
            history.add(new UserMessage(t.question()));
            history.add(AssistantMessage.builder().content(t.answer()).build());
        }
        log.info("Resumed conversation {} with {} saved message(s)", saved.id(), turns.size());
        return history;
    }

    private String nameChat(String conversationId, String question, String answer) {
        String title = titler.title(question, answer);
        try {
            store.setTitle(conversationId, title);
        } catch (RuntimeException e) {
            log.warn("Could not save the title of {}: {}", conversationId, e.getMessage());
        }
        return title;
    }

    private static @Nullable String validVisitor(@Nullable String cookie) {
        return cookie != null && VISITOR_ID.matcher(cookie).matches() ? cookie : null;
    }

    /** One line for the system prompt when this browser has chatted before; null for a new customer. */
    private @Nullable String customerNote(String visitorId, String conversationId) {
        try {
            var summary = store.summary(visitorId, conversationId);
            if (!summary.isReturning()) return null;
            return ("This customer has used this chat before (%d earlier conversation(s), %d RFQ(s)%s). If they refer "
                    + "to an earlier order, quote or their details, call get_customer_history.").formatted(
                    summary.conversations(), summary.rfqs(),
                    summary.lastAt() == null ? "" : ", last on " + summary.lastAt().toLocalDate());
        } catch (RuntimeException e) {
            log.warn("Could not read history for the system prompt: {}", e.getMessage());
            return null;
        }
    }

    private void touch(String conversationId, String visitorId) {
        try {
            store.touch(conversationId, visitorId);
        } catch (RuntimeException e) {
            log.warn("Could not save conversation {}: {}", conversationId, e.getMessage());
        }
    }

    private static String newVisitorId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 32 characters
    }

    private void send(OutputStream out, Object event) {
        String line = event instanceof AgentEvent e
                ? json.writerFor(AgentEvent.class).writeValueAsString(e)
                : json.writeValueAsString(event);
        try {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
