package com.sockspal.agent.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.sockspal.agent.sockspal.Catalog;
import com.sockspal.agent.sockspal.RfqService;
import com.sockspal.agent.sockspal.RfqService.RfqRequest;

/**
 * The five tools the model can ask for. Spring AI turns each {@code @Tool} method into a tool definition
 * (name, description, JSON schema from the parameters) and, when the model asks, parses the arguments and
 * calls the method. Return values go back to the model as JSON.
 */
@Component
public class SalesTools {

    public enum PolicyTopic {
        WHOLESALE_FAQ, WHOLESALE_TERMS, RETAIL_SHIPPING, RETAIL_RETURNS;

        String file() {
            return "knowledge/" + name().toLowerCase(Locale.ROOT) + ".md";
        }
    }

    public enum Incoterm { FCA, FOB, DAP }

    /** Returns policy markdown as-is; the default converter would JSON-quote it into one long escaped line. */
    public static class PlainText implements ToolCallResultConverter {
        @Override
        public String convert(@Nullable Object result, @Nullable Type returnType) {
            return String.valueOf(result);
        }
    }

    private final Catalog catalog;
    private final RfqService rfqs;

    public SalesTools(Catalog catalog, RfqService rfqs) {
        this.catalog = catalog;
        this.rfqs = rfqs;
    }

    @Tool(name = "list_products", description = "List all SocksPal products with category, retail price, "
            + "wholesale MOQ / starting price / lead time, colours, and total ready stock.")
    public Object listProducts(
            @ToolParam(description = "Optional filter: Daily, Pilates or Sport", required = false) @Nullable String category) {
        catalog.load();
        var rows = catalog.summary().stream()
                .filter(p -> category == null || category.isBlank() || p.category().equalsIgnoreCase(category))
                .toList();
        return rows.isEmpty() ? "No products in category \"" + category + "\"." : rows;
    }

    @Tool(name = "get_product", description = "Full details for one product: ready stock per colour/size, retail "
            + "price, all wholesale price tiers, MOQ, sample price, lead time, material attributes, page URL.")
    public Catalog.ProductDetails getProduct(
            @ToolParam(description = "Product slug from list_products (a name also works)") String product) {
        return catalog.details(product);
    }

    @Tool(name = "calculate_quote", description = "Wholesale price estimate for one product and quantity: picks the "
            + "price tier, checks MOQ, computes the goods subtotal, reports price breaks, and optionally checks ready "
            + "stock for a colour and adds a sample. Call once per product.")
    public Object calculateQuote(
            @ToolParam(description = "Product slug") String product,
            @ToolParam(description = "Number of pairs") int quantityPairs,
            @ToolParam(description = "Optional colour to check ready stock for", required = false) @Nullable String color,
            @ToolParam(description = "Add the sample price", required = false) @Nullable Boolean includeSample) {
        return catalog.quote(product, quantityPairs, color, Boolean.TRUE.equals(includeSample));
    }

    @Tool(name = "get_policy", resultConverter = PlainText.class, description = "Read a SocksPal policy or "
            + "knowledge page. WHOLESALE_FAQ covers custom options, quote requirements, MOQ rules, the order process, "
            + "Incoterms and contact details.")
    public String getPolicy(@ToolParam(description = "Which page to read") PolicyTopic topic) {
        try {
            return new ClassPathResource(topic.file()).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Tool(name = "create_rfq", description = "Send a request for quotation to the SocksPal sales team. Only call "
            + "after showing the customer a summary and getting their explicit confirmation.")
    public Object createRfq(
            @ToolParam(description = "true only if the customer confirmed the summary") boolean customerConfirmed,
            @ToolParam(description = "Customer's name") String contactName,
            @ToolParam(description = "Customer's email") String email,
            @ToolParam(description = "Customer's company or organisation name (SocksPal requires it)") String companyName,
            @ToolParam(required = false) @Nullable String phone,
            @ToolParam(description = "2-letter ISO country code of the destination, e.g. US") String countryCode,
            @ToolParam(description = "Destination city, port or address", required = false) @Nullable String destination,
            @ToolParam(required = false) @Nullable Incoterm requestedIncoterm,
            @ToolParam(description = "Summary of the request, timing, intended use, and anything else useful for the "
                    + "salesperson", required = false) @Nullable String message,
            @ToolParam(description = "One entry per product") List<RfqRequest.Item> items) {
        if (!customerConfirmed) {
            return "Not sent: show the customer a summary of the RFQ and ask them to confirm first.";
        }
        return rfqs.create(new RfqRequest(contactName, email, companyName, phone, countryCode, destination,
                requestedIncoterm == null ? null : requestedIncoterm.name(), message, items));
    }
}
