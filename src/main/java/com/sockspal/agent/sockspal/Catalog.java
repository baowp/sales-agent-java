package com.sockspal.agent.sockspal;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.sockspal.Product.PriceTier;
import com.sockspal.agent.sockspal.Product.Sku;
import com.sockspal.agent.sockspal.Product.WholesaleProfile;

/**
 * The SocksPal catalog: products, stock, wholesale price tiers, and the quote math.
 * All prices and stock the agent quotes come from here, never from the model.
 */
@Component
public class Catalog {

    public static final String SITE = "https://www.sockspal.com";

    private final ProductSource source;
    private final Duration cacheFor;
    private volatile List<Product> products = List.of();
    private volatile @Nullable Instant fetchedAt;

    @Autowired
    public Catalog(SocksPalClient client, AgentProperties props) {
        this(client, Duration.ofSeconds(props.catalogCacheSeconds()));
    }

    public Catalog(ProductSource source, Duration cacheFor) {
        this.source = source;
        this.cacheFor = cacheFor;
    }

    // ---- results (serialized to JSON for the model and the web UI) ----

    public record ProductSummary(String slug, String name, String category, String retailPrice,
                                 String wholesale, List<String> colors, int readyStockPairs) {}

    public record ProductDetails(String slug, String name, String category, @Nullable String brand,
                                 @Nullable String origin, String url, Retail retail, List<Stock> readyStock,
                                 Object wholesale, String attributes, String dataFetchedAt) {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Retail(String price, @Nullable String listPrice, String shopUrl) {}

        public record Stock(String sku, String color, String size, int availablePairs) {}

        public record Wholesale(int moqPairs, int productionLeadTimeDays, String samplePrice, List<Tier> priceTiers) {}

        public record Tier(String pairs, String unitPrice) {}
    }

    // ---- loading ----

    public synchronized Catalog load(boolean force) {
        Instant at = fetchedAt;
        if (!force && !products.isEmpty() && at != null && at.plus(cacheFor).isAfter(Instant.now())) return this;
        products = source.wholesaleProducts();
        fetchedAt = Instant.now();
        return this;
    }

    public Catalog load() {
        return load(false);
    }

    public List<Product> products() {
        return products;
    }

    public @Nullable Instant fetchedAt() {
        return fetchedAt;
    }

    // ---- lookups ----

    /** Accepts a slug, numeric id, or (part of) a product name. */
    public Product find(String ref) {
        load();
        String q = norm(ref);
        if (q.isEmpty()) throw new ApiException("Product name is empty. Products: " + slugs());
        List<String> words = Arrays.stream(q.split(" ")).map(Catalog::stem).toList();
        return first(p -> p.slug().equals(ref) || String.valueOf(p.id()).equals(ref))
                .or(() -> first(p -> norm(p.slug()).equals(q) || norm(p.name()).equals(q)))
                .or(() -> first(p -> norm(p.name()).contains(q) || norm(p.slug()).contains(q)))
                .or(() -> first(p -> haystack(p).containsAll(words)))
                .orElseThrow(() -> new ApiException("No product matches \"%s\". Products: %s".formatted(ref, slugs())));
    }

    public List<ProductSummary> summary() {
        return products.stream().map(p -> {
            WholesaleProfile w = p.wholesaleProfile();
            String wholesale = p.offersWholesale()
                    ? "MOQ %d pairs, from %s/pair, lead time %d days".formatted(w.minimumOrderQuantityPairs(),
                    money(w.priceTiers().stream().mapToInt(PriceTier::unitPriceMinor).min().orElse(0), w.currencyCode()),
                    w.productionLeadTimeDays())
                    : "not offered";
            int ready = p.activeSkus().stream().mapToInt(Sku::availableQuantity).sum();
            return new ProductSummary(p.slug(), p.name(), p.categoryName(), money(p.salePriceMinor(), p.currencyCode()),
                    wholesale, p.colors(), ready);
        }).toList();
    }

    public ProductDetails details(String ref) {
        Product p = find(ref);
        WholesaleProfile w = p.wholesaleProfile();
        var retail = new ProductDetails.Retail(
                money(p.salePriceMinor(), p.currencyCode()),
                p.salePriceMinor() != p.priceMinor() ? money(p.priceMinor(), p.currencyCode()) : null,
                SITE + "/shop/products/" + p.slug());
        var stock = p.activeSkus().stream()
                .map(s -> new ProductDetails.Stock(s.sku(), s.color(), s.size(), s.availableQuantity()))
                .toList();
        Object wholesale = p.offersWholesale()
                ? new ProductDetails.Wholesale(w.minimumOrderQuantityPairs(), w.productionLeadTimeDays(),
                money(w.samplePriceMinor(), w.currencyCode()),
                w.priceTiers().stream().map(t -> new ProductDetails.Tier(t.range(), money(t.unitPriceMinor(), w.currencyCode()))).toList())
                : "Wholesale not offered for this product";
        String attributes = HtmlText.toText(p.description());
        return new ProductDetails(p.slug(), p.name(), p.categoryName(), p.brand(), p.origin(),
                SITE + "/wholesale/" + p.slug(), retail, stock, wholesale,
                attributes.substring(0, Math.min(attributes.length(), 2500)), String.valueOf(fetchedAt));
    }

    /** Deterministic wholesale quote: tier lookup, MOQ check, price-break hint, stock check. */
    public Map<String, Object> quote(String ref, int quantity, @Nullable String color, boolean includeSample) {
        Product p = find(ref);
        WholesaleProfile w = p.wholesaleProfile();
        Map<String, Object> result = new LinkedHashMap<>();
        if (!p.offersWholesale()) {
            result.put("product", p.name());
            result.put("error", "Wholesale is not offered for this product. Suggest an RFQ for a custom request.");
            return result;
        }
        if (quantity <= 0) return Map.of("error", "Quantity must be a positive number of pairs.");

        String cur = w.currencyCode();
        List<PriceTier> tiers = w.priceTiers().stream().sorted(Comparator.comparingInt(PriceTier::minimumQuantityPairs)).toList();
        int moq = w.minimumOrderQuantityPairs();
        result.put("product", p.name());
        result.put("slug", p.slug());
        result.put("quantityPairs", quantity);
        result.put("moqPairs", moq);

        if (quantity < moq) {
            result.put("belowMoq", true);
            result.put("note", "Below the %d-pair MOQ. Quote the MOQ instead or offer a sample (%s)."
                    .formatted(moq, money(w.samplePriceMinor(), cur)));
            return result;
        }

        PriceTier tier = tiers.reversed().stream().filter(t -> quantity >= t.minimumQuantityPairs()).findFirst().orElseThrow();
        int subtotal = quantity * tier.unitPriceMinor();
        result.put("unitPrice", money(tier.unitPriceMinor(), cur));
        result.put("tier", tier.range() + " pairs");
        result.put("goodsSubtotal", money(subtotal, cur));

        tiers.stream().filter(t -> t.minimumQuantityPairs() > quantity).findFirst().ifPresent(next -> {
            int nextTotal = next.minimumQuantityPairs() * next.unitPriceMinor();
            result.put("priceBreak", "At %d+ pairs the unit price drops to %s (%d pairs = %s%s).".formatted(
                    next.minimumQuantityPairs(), money(next.unitPriceMinor(), cur), next.minimumQuantityPairs(),
                    money(nextTotal, cur), nextTotal <= subtotal ? ", which costs less than this order" : ""));
        });

        if (includeSample) {
            result.put("sample", money(w.samplePriceMinor(), cur));
            result.put("totalWithSample", money(subtotal + w.samplePriceMinor(), cur));
        }

        if (color != null && !color.isBlank()) {
            List<Sku> skus = p.activeSkus().stream().filter(s -> norm(s.color()).equals(norm(color))).toList();
            result.put("color", skus.isEmpty()
                    ? "Colour \"%s\" not found. Available: %s".formatted(color, String.join(", ", p.colors()))
                    : Map.of("color", skus.getFirst().color(),
                    "readyStockPairs", skus.stream().mapToInt(Sku::availableQuantity).sum()));
        }

        result.put("productionLeadTimeDays", w.productionLeadTimeDays());
        result.put("excludes", "Freight, destination duties, import taxes and bank fees. Freight depends on the "
                + "Incoterm (FCA, FOB or DAP) and is quoted by the sales team in the formal quotation.");
        result.put("binding", false);
        result.put("dataFetchedAt", String.valueOf(fetchedAt));
        return result;
    }

    // ---- helpers ----

    public static String money(int minor, String currency) {
        String amount = String.format(Locale.ROOT, "%.2f", minor / 100.0);
        return "USD".equals(currency) ? "$" + amount : currency + " " + amount;
    }

    private Optional<Product> first(Predicate<Product> test) {
        return products.stream().filter(test).findFirst();
    }

    private String slugs() {
        return products.stream().map(Product::slug).collect(Collectors.joining(", "));
    }

    private static List<String> haystack(Product p) {
        return Arrays.stream(norm(p.name() + " " + p.slug() + " " + p.categoryName()).split(" ")).map(Catalog::stem).toList();
    }

    private static String norm(@Nullable String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String stem(String word) {
        return word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }
}
