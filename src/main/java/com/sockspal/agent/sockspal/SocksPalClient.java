package com.sockspal.agent.sockspal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sockspal.agent.config.AgentProperties;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * SocksPal public API ({@code https://api.sockspal.com/api/v1}). No login needed.
 * Every response is {@code {"data": ...}} or {@code {"error": {"code", "message"}}}.
 */
@Component
public class SocksPalClient implements ProductSource {

    /** SocksPal JSON is snake_case: {@code sale_price_minor} maps to {@code salePriceMinor}. */
    public static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final RestClient http;

    public SocksPalClient(AgentProperties props) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder()
                .baseUrl(props.sockspalApi().replaceAll("/+$", ""))
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<Product> wholesaleProducts() {
        return JSON.convertValue(request(HttpMethod.GET, "/wholesale/products?limit=100", null),
                new TypeReference<List<Product>>() {});
    }

    /** Creates a real RFQ in the SocksPal admin, exactly like the website's quote form. */
    public JsonNode submitRfq(Object payload) {
        return request(HttpMethod.POST, "/wholesale/rfqs", JSON.writeValueAsString(payload));
    }

    private JsonNode request(HttpMethod method, String path, String body) {
        record Raw(int status, String reason, String body) {}
        Raw raw;
        try {
            var spec = http.method(method).uri(path);
            if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
            raw = spec.exchange((req, res) -> new Raw(res.getStatusCode().value(), res.getStatusText(),
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof HttpTimeoutException;
            throw new ApiException("SocksPal API unreachable (" + (timeout ? "timed out" : describe(e.getCause())) + ")");
        }

        JsonNode json;
        try {
            json = JSON.readTree(raw.body());
        } catch (RuntimeException e) {
            json = JSON.createObjectNode();
        }
        JsonNode error = json.path("error");
        if (raw.status() >= 400 || !error.isMissingNode() && !error.isNull()) {
            String message = error.path("message").asString(raw.reason());
            throw new ApiException("SocksPal API error " + raw.status() + ": " + message);
        }
        return json.path("data");
    }

    private static String describe(Throwable cause) {
        if (cause == null) return "fetch failed";
        if (cause instanceof IOException && (cause.getMessage() == null || cause.getMessage().isBlank())) {
            return cause.getClass().getSimpleName();
        }
        return cause.getMessage();
    }
}
