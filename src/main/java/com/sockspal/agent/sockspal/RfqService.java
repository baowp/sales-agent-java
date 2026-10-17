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
        result.put("id", id);
        if (submitError != null) {
            result.put("status", "saved_locally_submit_failed");
            result.put("error", submitError);
            result.put("nextStep", "Tell the customer the request was saved and a salesperson will follow up, "
                    + "or they can use https://www.sockspal.com/request-quote.");
        } else if (submit) {
            result.put("status", "submitted_to_sockspal");
            result.put("response", record.get("response"));
        } else {
            result.put("status", "draft_saved_for_sales_team");
            result.put("nextStep", "A salesperson reviews the draft and sends the formal quotation by email.");
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
