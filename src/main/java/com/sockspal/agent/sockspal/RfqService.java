package com.sockspal.agent.sockspal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.sockspal.agent.config.AgentProperties;

import tools.jackson.databind.JsonNode;

/**
 * Builds the same payload as the site's /request-quote form. Saves it to rfqs/; with RFQ_MODE=submit it is
 * also sent to SocksPal, which creates a real RFQ.
 */
@Service
public class RfqService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** What the agent collected from the customer. */
    public record RfqRequest(String contactName, String email, @Nullable String companyName, @Nullable String phone,
                             String countryCode, @Nullable String destination, @Nullable String requestedIncoterm,
                             @Nullable String message, List<Item> items) {

        public record Item(
                @JsonPropertyDescription("Product slug, or a description for a custom/unlisted product") String product,
                @JsonPropertyDescription("Number of pairs") int quantityPairs,
                @Nullable String sizeRequirements,
                @Nullable String colorRequirements,
                @Nullable String logoRequirements,
                @Nullable String packagingRequirements) {}
    }

    /** The website form's JSON (sent snake_case). */
    record Payload(String companyName, String contactName, String email, String phone, String countryCode,
                   String destination, String requestedIncoterm, String message, boolean isBusinessBuyer,
                   List<PayloadItem> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PayloadItem(@Nullable Long productId, @Nullable String requestedProductName, int quantityPairs,
                       String sizeRequirements, String colorRequirements, String logoRequirements,
                       String packagingRequirements) {}

    private final Catalog catalog;
    private final SocksPalClient client;
    private final Path dir;
    private final boolean submit;

    @Autowired
    public RfqService(Catalog catalog, SocksPalClient client, AgentProperties props) {
        this(catalog, client, Path.of(props.rfqDir()), props.submitRfqs());
    }

    public RfqService(Catalog catalog, @Nullable SocksPalClient client, Path dir, boolean submit) {
        this.catalog = catalog;
        this.client = client;
        this.dir = dir;
        this.submit = submit;
    }

    public Path dir() {
        return dir;
    }

    public Map<String, Object> create(RfqRequest in) {
        List<PayloadItem> items = new ArrayList<>();
        for (RfqRequest.Item item : Objects.requireNonNullElse(in.items(), List.<RfqRequest.Item>of())) {
            Product product = null;
            try {
                product = catalog.find(item.product());
            } catch (ApiException e) {
                // custom / unlisted product
            }
            items.add(new PayloadItem(
                    product != null ? product.id() : null,
                    product != null ? null : item.product(),
                    item.quantityPairs(),
                    orEmpty(item.sizeRequirements()), orEmpty(item.colorRequirements()),
                    orEmpty(item.logoRequirements()), orEmpty(item.packagingRequirements())));
        }
        var payload = new Payload(orEmpty(in.companyName()), orEmpty(in.contactName()), orEmpty(in.email()),
                orEmpty(in.phone()), orEmpty(in.countryCode()).toUpperCase(), orEmpty(in.destination()),
                orEmpty(in.requestedIncoterm()), orEmpty(in.message()), true, items);

        List<String> missing = new ArrayList<>();
        if (payload.companyName().isBlank()) missing.add("company_name");
        if (payload.contactName().isBlank()) missing.add("contact_name");
        if (payload.email().isBlank()) missing.add("email");
        if (payload.countryCode().isBlank()) missing.add("country_code");
        if (!missing.isEmpty()) return error("Missing required fields: " + String.join(", ", missing) + ". Ask the customer for them.");
        if (!EMAIL.matcher(payload.email()).matches()) return error("Email address looks invalid. Ask the customer to check it.");
        if (!payload.countryCode().matches("[A-Z]{2}")) return error("country_code must be a 2-letter ISO code such as US, GB or DE.");
        if (items.isEmpty() || items.stream().anyMatch(i -> i.quantityPairs() <= 0)) {
            return error("Each item needs a product and a quantity in pairs.");
        }

        Instant now = Instant.now();
        String id = "rfq-" + now.toString().replaceAll("[:.]", "-");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("created_at", now.toString());
        record.put("mode", submit ? "submit" : "draft");
        record.put("payload", payload);

        String submitError = null;
        if (submit && client != null) {
            try {
                record.put("response", client.submitRfq(payload));
            } catch (ApiException e) {
                submitError = e.getMessage();
                record.put("submit_error", submitError);
            }
        }
        save(id, record);

        Map<String, Object> result = new LinkedHashMap<>();
        if (record.get("response") instanceof JsonNode response) {
            // Only what the customer needs: SocksPal's RFQ number and what it recorded. The full reply
            // (which echoes the payload) stays in the saved file.
            result.put("id", response.hasNonNull("rfq_number") ? response.get("rfq_number").asString() : id);
            result.put("status", "submitted_to_sockspal");
            if (response.hasNonNull("status")) result.put("salesStatus", response.get("status").asString());
            result.put("replyTo", payload.email());
            result.put("received", response.path("items").valueStream()
                    .map(i -> i.path("quantity_pairs").asInt() + " pairs " + i.path("product_name").asString())
                    .toList());
            result.put("nextStep", "Sent. Give the customer the id above as their RFQ number, exactly as written. Say "
                    + "the SocksPal sales team has received it and will reply by email to replyTo. Do not promise "
                    + "a reply time or a final price.");
        } else if (submit) {
            result.put("id", id);
            result.put("status", "saved_locally_submit_failed");
            result.put("error", submitError != null ? submitError : "No SocksPal API client configured");
            result.put("nextStep", "NOT sent: SocksPal did not receive it. Tell the customer it could not be sent "
                    + "and ask them to use https://www.sockspal.com/request-quote or email sockspal@163.com.");
        } else {
            result.put("id", id);
            result.put("status", "draft_saved_not_sent");
            result.put("nextStep", "NOT sent: draft mode only saves it on this server. Do not say it was submitted. "
                    + "Tell the customer it is saved as a draft, and that for a guaranteed reply they can use "
                    + "https://www.sockspal.com/request-quote or email sockspal@163.com.");
        }
        return result;
    }

    private void save(String id, Map<String, Object> record) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(id + ".json"), SocksPalClient.JSON.writeValueAsString(record));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s.trim();
    }
}
