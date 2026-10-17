package com.sockspal.agent.sockspal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sockspal.agent.Fixtures;
import com.sockspal.agent.config.AgentProperties;

class CatalogTest {

    private final Catalog catalog = Fixtures.catalog();

    @Test
    void quotePicksTierAndReportsPriceBreak() {
        Map<String, Object> q = catalog.quote("soccer-socks", 500, "black", true);
        assertThat(q).containsEntry("unitPrice", "$0.45")
                .containsEntry("tier", "500-999 pairs")
                .containsEntry("goodsSubtotal", "$225.00")
                .containsEntry("sample", "$20.00")
                .containsEntry("totalWithSample", "$245.00")
                .containsEntry("color", Map.of("color", "Black", "readyStockPairs", 50))
                .containsEntry("productionLeadTimeDays", 2);
        assertThat((String) q.get("priceBreak")).isEqualTo("At 1000+ pairs the unit price drops to $0.40 (1000 pairs = $400.00).");
    }

    @Test
    void priceBreakSaysWhenOrderingMoreCostsLess() {
        assertThat((String) catalog.quote("soccer socks", 950, null, false).get("priceBreak"))
                .endsWith("which costs less than this order).");
    }

    @Test
    void belowMoqOffersSampleInstead() {
        Map<String, Object> q = catalog.quote("men-s-crew-socks", 50, null, false);
        assertThat(q).containsEntry("belowMoq", true).doesNotContainKey("unitPrice");
        assertThat((String) q.get("note")).contains("100-pair MOQ").contains("$10.00");
    }

    @Test
    void unknownColourListsAvailableOnes() {
        assertThat((String) catalog.quote("women-s-non-slip-five-toe-yoga-socks", 280, "teal", false).get("color"))
                .isEqualTo("Colour \"teal\" not found. Available: Black, Light-Gray, Dark-Gray, Purple, Pink, Yellow");
    }

    @Test
    void findMatchesSlugsNamesAndWords() {
        assertThat(catalog.find("kids dinosaur").slug()).isEqualTo("kids-mesh-dinosaur-socks");
        assertThat(catalog.find("halloween").slug()).isEqualTo("halloween-magnetic-socks");
        assertThat(catalog.find("pilates grip socks").slug()).isEqualTo("women-s-yoga-pilates-grip-socks");
        assertThat(catalog.find("4").slug()).isEqualTo("soccer-socks");
        assertThatThrownBy(() -> catalog.find("wool hiking"))
                .isInstanceOf(ApiException.class)
                .hasMessageStartingWith("No product matches \"wool hiking\". Products: women-s-non-slip");
    }

    @Test
    void detailsTurnAttributeTablesIntoLines() {
        String text = catalog.details("kids-mesh-dinosaur-socks").attributes();
        assertThat(text).contains("Item No.: A45", "Packaging: Individually packed by pair",
                "Color / Style | Kids’ Sock Size | Length (cm) | Width (cm) | Height (cm) | Volume (cm³) | Weight (g)",
                "Prehistoric Dinosaur (Mesh) | M: 1–4 years | 18 | 10 | 2 | 360 | 50");
        assertThat(text).doesNotContain("<", "Attribute: Value");
    }

    @Test
    void summaryShowsLowestTierAndReadyStock() {
        var soccer = catalog.load().summary().stream().filter(p -> p.slug().equals("soccer-socks")).findFirst().orElseThrow();
        assertThat(soccer.wholesale()).isEqualTo("MOQ 100 pairs, from $0.40/pair, lead time 2 days");
        assertThat(soccer.retailPrice()).isEqualTo("$2.00");
        assertThat(soccer.readyStockPairs()).isEqualTo(550);
    }

    @Test
    void unreachableApiIsReportedNotHidden() {
        var props = new AgentProperties(12, 120, "http://127.0.0.1:9/api/v1", 60, "draft", "rfqs");
        assertThatThrownBy(() -> new SocksPalClient(props).wholesaleProducts())
                .isInstanceOf(ApiException.class)
                .hasMessageStartingWith("SocksPal API unreachable");
    }
}
