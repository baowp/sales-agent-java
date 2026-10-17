package com.sockspal.agent.sockspal;

import java.util.List;

import org.jspecify.annotations.Nullable;

/** A product from {@code GET /wholesale/products}. JSON is snake_case; see {@link SocksPalClient#JSON}. */
public record Product(
        long id,
        String name,
        String slug,
        String categoryName,
        @Nullable String description,
        @Nullable String brand,
        @Nullable String origin,
        int priceMinor,
        int salePriceMinor,
        String currencyCode,
        List<Sku> skus,
        @Nullable WholesaleProfile wholesaleProfile) {

    /** One colour/size variant and its ready stock. */
    public record Sku(long id, String sku, String size, String color, int availableQuantity, boolean isActive) {}

    public record WholesaleProfile(
            boolean isAvailable,
            int minimumOrderQuantityPairs,
            int productionLeadTimeDays,
            String currencyCode,
            int samplePriceMinor,
            List<PriceTier> priceTiers) {}

    /** {@code maximumQuantityPairs} is null for the open-ended top tier. */
    public record PriceTier(int minimumQuantityPairs, @Nullable Integer maximumQuantityPairs, int unitPriceMinor) {

        public String range() {
            return maximumQuantityPairs == null
                    ? minimumQuantityPairs + "+"
                    : minimumQuantityPairs + "-" + maximumQuantityPairs;
        }
    }

    public List<Sku> activeSkus() {
        return skus.stream().filter(Sku::isActive).toList();
    }

    public List<String> colors() {
        return activeSkus().stream().map(Sku::color).distinct().toList();
    }

    public boolean offersWholesale() {
        return wholesaleProfile != null && wholesaleProfile.isAvailable();
    }
}
