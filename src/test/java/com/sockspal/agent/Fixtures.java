package com.sockspal.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.sockspal.agent.agent.SalesTools;
import com.sockspal.agent.config.MemoryProperties;
import com.sockspal.agent.memory.ChatStore;
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

    /** A fresh in-memory database with the real schema.sql. */
    public static ChatStore chatStore() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(db);
        return new ChatStore(JdbcClient.create(db));
    }

    public static MemoryProperties memory() {
        return new MemoryProperties("", 3);
    }

    public static SalesTools tools(Path rfqDir) {
        return tools(rfqDir, chatStore());
    }

    public static SalesTools tools(Path rfqDir, ChatStore store) {
        Catalog catalog = catalog();
        return new SalesTools(catalog, new RfqService(catalog, null, rfqDir, false), store, memory());
    }
}
