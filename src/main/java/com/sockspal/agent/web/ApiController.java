package com.sockspal.agent.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.sockspal.agent.agent.AgentEvent;
import com.sockspal.agent.agent.SalesAgent;
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.config.LlmProvider;
import com.sockspal.agent.sockspal.ApiException;
import com.sockspal.agent.sockspal.Catalog;
import com.sockspal.agent.sockspal.RfqService;

import tools.jackson.databind.json.JsonMapper;

/** Same endpoints as the Node.js server.js, so the same page works against either backend. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    public record Info(String provider, String model, String rfqMode, @Nullable String fetchedAt,
                       List<Catalog.ProductSummary> products, @Nullable String catalogError, long rfqs) {}

    public record AskRequest(@Nullable String question, @Nullable String sessionId) {}

    public record ResetRequest(@Nullable String sessionId) {}

    private final SalesAgent agent;
    private final Catalog catalog;
    private final RfqService rfqs;
    private final LlmProvider provider;
    private final AgentProperties props;
    private final JsonMapper json;
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>(); // sessionId -> history

    public ApiController(SalesAgent agent, Catalog catalog, RfqService rfqs, LlmProvider provider,
                         AgentProperties props, JsonMapper json) {
        this.agent = agent;
        this.catalog = catalog;
        this.rfqs = rfqs;
        this.provider = provider;
        this.props = props;
        this.json = json;
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
    public ResponseEntity<StreamingResponseBody> ask(@RequestBody AskRequest req) {
        String question = req.question() == null ? "" : req.question().trim();
        if (question.isEmpty()) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(out -> send(out, Map.of("error", "Empty message")));
        }

        String sessionId = req.sessionId() != null && sessions.containsKey(req.sessionId())
                ? req.sessionId() : UUID.randomUUID().toString();
        List<Message> history = sessions.computeIfAbsent(sessionId, id -> agent.newHistory());

        StreamingResponseBody body = out -> {
            send(out, Map.of("type", "session", "sessionId", sessionId));
            synchronized (history) { // one message at a time per conversation
                int mark = history.size();
                try {
                    String text = agent.ask(history, question, e -> send(out, e));
                    send(out, Map.of("type", "answer", "text", text));
                } catch (RuntimeException e) {
                    history.subList(mark, history.size()).clear(); // drop the failed turn
                    send(out, Map.of("type", "error", "message", String.valueOf(e.getMessage())));
                }
            }
        };
        return ResponseEntity.ok().contentType(NDJSON).header("Cache-Control", "no-cache").body(body);
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
