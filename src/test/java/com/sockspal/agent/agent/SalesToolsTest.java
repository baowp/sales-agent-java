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
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.sockspal.RfqService;
import com.sockspal.agent.sockspal.RfqService.RfqRequest;
import com.sockspal.agent.sockspal.SocksPalClient;

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

        assertThat(schemas).containsOnlyKeys("list_products", "get_product", "calculate_quote", "get_policy", "create_rfq",
                "get_customer_history");
        assertThat(schemas.get("get_customer_history").path("properties").isEmpty()).isTrue(); // ToolContext is not an argument
        assertThat(required(schemas.get("calculate_quote"))).containsExactlyInAnyOrder("product", "quantityPairs");
        assertThat(required(schemas.get("list_products"))).isEmpty();
        assertThat(schemas.get("get_policy").at("/properties/topic/enum").toString())
                .isEqualTo("[\"WHOLESALE_FAQ\",\"WHOLESALE_TERMS\",\"RETAIL_SHIPPING\",\"RETAIL_RETURNS\"]");
        JsonNode rfq = schemas.get("create_rfq");
        assertThat(required(rfq)).containsExactlyInAnyOrder("customerConfirmed", "contactName", "email", "companyName", "countryCode", "items");
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

        Object refused = tools.createRfq(false, "Test Buyer", "test.buyer@example.com", "Test FC", null, "US", "Los Angeles",
                SalesTools.Incoterm.DAP, null, List.of(item));
        assertThat(refused).asString().startsWith("Not sent");
        assertThat(rfqDir).isEmptyDirectory();

        @SuppressWarnings("unchecked")
        var badEmail = (Map<String, Object>) tools.createRfq(true, "Test Buyer", "not-an-email", "Test FC", null, "US", null, null, null, List.of(item));
        assertThat(badEmail).containsKey("error");

        @SuppressWarnings("unchecked")
        var noCompany = (Map<String, Object>) tools.createRfq(true, "Test Buyer", "test.buyer@example.com", " ", null, "US", null, null, null, List.of(item));
        assertThat(noCompany).extractingByKey("error").asString().contains("company_name");
        assertThat(rfqDir).isEmptyDirectory();

        @SuppressWarnings("unchecked")
        var saved = (Map<String, Object>) tools.createRfq(true, "Test Buyer", "test.buyer@example.com", "Test FC", null, "us",
                "Los Angeles", SalesTools.Incoterm.DAP, "1000 pairs with logo", List.of(item));
        assertThat(saved).containsEntry("status", "draft_saved_not_sent");

        Path file = rfqDir.resolve(saved.get("id") + ".json");
        JsonNode record = JSON.readTree(Files.readString(file));
        assertThat(record.at("/payload/country_code").asString()).isEqualTo("US");
        assertThat(record.at("/payload/requested_incoterm").asString()).isEqualTo("DAP");
        assertThat(record.at("/payload/is_business_buyer").asBoolean()).isTrue();
        assertThat(record.at("/payload/items/0/product_id").asLong()).isEqualTo(4);
        assertThat(record.at("/payload/items/0/quantity_pairs").asInt()).isEqualTo(1000);
        assertThat(record.at("/payload/items/0").has("requested_product_name")).isFalse();
    }

    /** Submit mode hands the customer SocksPal's RFQ number, not the local file name, and a short summary. */
    @Test
    void submittedRfqReportsSocksPalsNumber() throws Exception {
        // Shape of a real POST /wholesale/rfqs reply (trimmed).
        JsonNode reply = SocksPalClient.JSON.readTree("""
                {"id": 4, "rfq_number": "RFQ-20261018-7B8A8159", "email": "test.buyer@example.com", "status": "new",
                 "items": [{"product_id": 4, "product_name": "Football socks", "quantity_pairs": 1000}]}""");
        var client = new SocksPalClient(new AgentProperties(12, 5, "http://unused", 60, "submit", rfqDir.toString())) {
            @Override
            public JsonNode submitRfq(Object payload) {
                return reply;
            }
        };
        var tools = new SalesTools(Fixtures.catalog(), new RfqService(Fixtures.catalog(), client, rfqDir, true),
                Fixtures.chatStore(), Fixtures.memory());
        var item = new RfqRequest.Item("soccer-socks", 1000, null, "Black", null, null);

        @SuppressWarnings("unchecked")
        var sent = (Map<String, Object>) tools.createRfq(true, "Test Buyer", "test.buyer@example.com", "Test FC", null,
                "US", "Los Angeles", SalesTools.Incoterm.FCA, null, List.of(item));
        assertThat(sent).containsEntry("id", "RFQ-20261018-7B8A8159")
                .containsEntry("status", "submitted_to_sockspal")
                .containsEntry("salesStatus", "new")
                .containsEntry("replyTo", "test.buyer@example.com")
                .containsEntry("received", List.of("1000 pairs Football socks"))
                .doesNotContainKey("response");
        try (var files = Files.list(rfqDir)) {
            JsonNode record = JSON.readTree(Files.readString(files.findFirst().orElseThrow()));
            assertThat(record.at("/response/rfq_number").asString()).isEqualTo("RFQ-20261018-7B8A8159");
        }
    }

    @Test
    void submitModeWithoutClientIsNotReportedAsSent() {
        var tools = new SalesTools(Fixtures.catalog(), new RfqService(Fixtures.catalog(), null, rfqDir, true),
                Fixtures.chatStore(), Fixtures.memory());
        var item = new RfqRequest.Item("soccer-socks", 1000, null, null, null, null);

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) tools.createRfq(true, "Test Buyer", "test.buyer@example.com", "Test FC", null,
                "US", null, null, null, List.of(item));
        assertThat(result).containsEntry("status", "saved_locally_submit_failed");
    }

    private static List<String> required(JsonNode schema) {
        return schema.path("required").valueStream().map(JsonNode::asString).toList();
    }
}
