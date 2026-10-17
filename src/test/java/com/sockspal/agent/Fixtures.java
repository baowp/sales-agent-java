package com.sockspal.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

import com.sockspal.agent.agent.SalesTools;
import com.sockspal.agent.sockspal.Catalog;
import com.sockspal.agent.sockspal.Product;
import com.sockspal.agent.sockspal.RfqService;
import com.sockspal.agent.sockspal.SocksPalClient;

import tools.jackson.core.type.TypeReference;

/** A snapshot of the live /wholesale/products response (9 products), so tests don't need the network. */
public final class Fixtures {

    private Fixtures() {}

    public static List<Product> products() {
        try (var in = new ClassPathResource("fixtures/wholesale-products.json").getInputStream()) {
            return SocksPalClient.JSON.convertValue(SocksPalClient.JSON.readTree(in).path("data"),
                    new TypeReference<List<Product>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Catalog catalog() {
        return new Catalog(Fixtures::products, Duration.ofMinutes(5));
    }

    public static SalesTools tools(Path rfqDir) {
        Catalog catalog = catalog();
        return new SalesTools(catalog, new RfqService(catalog, null, rfqDir, false));
    }
}
