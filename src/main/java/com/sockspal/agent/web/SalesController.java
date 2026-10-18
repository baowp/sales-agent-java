package com.sockspal.agent.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sockspal.agent.config.MemoryProperties;
import com.sockspal.agent.memory.ChatSearch;
import com.sockspal.agent.memory.ChatStore;

/**
 * The sales team's view of saved chats, used by sales.html. Every call needs
 * {@code Authorization: Bearer <SALES_SEARCH_TOKEN>}; without that setting the API is off.
 * Visitor tokens are shown shortened: the full value would let anyone read that customer's history.
 */
@RestController
@RequestMapping("/api/sales")
public class SalesController {

    public record Status(String mode, String model) {}

    public record TranscriptTurn(OffsetDateTime at, String question, String answer, List<String> tools) {}

    public record Transcript(String id, String visitor, @Nullable String title, OffsetDateTime startedAt, OffsetDateTime updatedAt,
                             List<TranscriptTurn> turns, List<ChatStore.Rfq> rfqs) {}

    private final ChatSearch search;
    private final ChatStore store;
    private final MemoryProperties memory;

    public SalesController(ChatSearch search, ChatStore store, MemoryProperties memory) {
        this.search = search;
        this.store = store;
        this.memory = memory;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) @Nullable String auth) {
        var denied = check(auth);
        return denied != null ? denied
                : ResponseEntity.ok(new Status(search.isSemantic() ? "semantic" : "keyword", search.modelName()));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) @Nullable String auth,
                                    @RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        var denied = check(auth);
        if (denied != null) return denied;
        if (q.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Empty search"));
        return ResponseEntity.ok(search.search(q.trim(), Math.clamp(limit, 1, 50)));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> conversation(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) @Nullable String auth,
                                          @PathVariable String id) {
        var denied = check(auth);
        if (denied != null) return denied;
        return store.conversation(id)
                .<ResponseEntity<?>>map(c -> ResponseEntity.ok(new Transcript(c.id(), shortVisitor(c.visitorId()), c.title(),
                        c.startedAt(), c.updatedAt(),
                        c.turns().stream().map(t -> new TranscriptTurn(t.at(), t.question(), t.answer(), t.tools())).toList(),
                        c.rfqs())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "No such conversation")));
    }

    /** null when allowed, otherwise the error response. */
    private @Nullable ResponseEntity<?> check(@Nullable String auth) {
        if (!memory.searchEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Sales search is off. Set SALES_SEARCH_TOKEN in .env and restart."));
        }
        byte[] expected = ("Bearer " + memory.searchToken().trim()).getBytes(StandardCharsets.UTF_8);
        byte[] given = (auth == null ? "" : auth.trim()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Wrong or missing token"));
        }
        return null;
    }

    private static String shortVisitor(String visitorId) {
        return visitorId.substring(0, Math.min(8, visitorId.length()));
    }
}
