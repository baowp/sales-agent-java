package com.sockspal.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import com.sockspal.agent.Fixtures;
import com.sockspal.agent.sockspal.RfqService.RfqRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SalesToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path rfqDir;

    /** The tool definitions the model sees: names, required arguments, enums and nested items. */
    @Test
    void toolSchemasMatchWhatTheModelNeeds() {
        Map<String, JsonNode> schemas = Arrays.stream(ToolCallbacks.from(Fixtures.tools(rfqDir)))
                .map(ToolCallback::getToolDefinition)
                .collect(Collectors.toMap(d -> d.name(), d -> JSON.readTree(d.inputSchema())));
        schemas.forEach((name, schema) -> System.out.println(name + " " + schema));

        assertThat(schemas).containsOnlyKeys("list_products", "get_product", "calculate_quote", "get_policy", "create_rfq");
        assertThat(required(schemas.get("calculate_quote"))).containsExactlyInAnyOrder("product", "quantityPairs");
        assertThat(required(schemas.get("list_products"))).isEmpty();
        assertThat(schemas.get("get_policy").at("/properties/topic/enum").toString())
                .isEqualTo("[\"WHOLESALE_FAQ\",\"WHOLESALE_TERMS\",\"RETAIL_SHIPPING\",\"RETAIL_RETURNS\"]");
        JsonNode rfq = schemas.get("create_rfq");
        assertThat(required(rfq)).containsExactlyInAnyOrder("customerConfirmed", "contactName", "email", "countryCode", "items");
        assertThat(rfq.toString()).contains("quantityPairs", "Product slug, or a description for a custom/unlisted product");
    }

    @Test
    void policyIsReturnedAsPlainMarkdown() {
        String text = Fixtures.tools(rfqDir).getPolicy(SalesTools.PolicyTopic.WHOLESALE_TERMS);
        assertThat(text).startsWith("# International Wholesale Terms of Sale").contains("\n- Scope:");
        assertThat(new SalesTools.PlainText().convert(text, String.class)).isEqualTo(text);
    }

    @Test
    void rfqNeedsConfirmationThenSavesTheWebsitePayload() throws Exception {
        var tools = Fixtures.tools(rfqDir);
        var item = new RfqRequest.Item("soccer-socks", 1000, null, "Black", "Team logo knitted in", null);

        Object refused = tools.createRfq(false, "Test Buyer", "test.buyer@example.com", null, null, "US", "Los Angeles",
                SalesTools.Incoterm.DAP, null, List.of(item));
        assertThat(refused).asString().startsWith("Not sent");
        assertThat(rfqDir).isEmptyDirectory();

        @SuppressWarnings("unchecked")
        var badEmail = (Map<String, Object>) tools.createRfq(true, "Test Buyer", "not-an-email", null, null, "US", null, null, null, List.of(item));
        assertThat(badEmail).containsKey("error");

        @SuppressWarnings("unchecked")
        var saved = (Map<String, Object>) tools.createRfq(true, "Test Buyer", "test.buyer@example.com", null, null, "us",
                "Los Angeles", SalesTools.Incoterm.DAP, "1000 pairs with logo", List.of(item));
        assertThat(saved).containsEntry("status", "draft_saved_for_sales_team");

        Path file = rfqDir.resolve(saved.get("id") + ".json");
        JsonNode record = JSON.readTree(Files.readString(file));
        assertThat(record.at("/payload/country_code").asString()).isEqualTo("US");
        assertThat(record.at("/payload/requested_incoterm").asString()).isEqualTo("DAP");
        assertThat(record.at("/payload/is_business_buyer").asBoolean()).isTrue();
        assertThat(record.at("/payload/items/0/product_id").asLong()).isEqualTo(4);
        assertThat(record.at("/payload/items/0/quantity_pairs").asInt()).isEqualTo(1000);
        assertThat(record.at("/payload/items/0").has("requested_product_name")).isFalse();
    }

    private static List<String> required(JsonNode schema) {
        return schema.path("required").valueStream().map(JsonNode::asString).toList();
    }
}
