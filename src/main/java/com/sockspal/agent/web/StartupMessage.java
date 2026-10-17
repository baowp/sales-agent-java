package com.sockspal.agent.web;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import com.sockspal.agent.config.LlmProvider;
import com.sockspal.agent.sockspal.ApiException;
import com.sockspal.agent.sockspal.Catalog;

/** Prints the UI address once the server is up (Spring's own logging is set to warn). */
@Component
class StartupMessage implements ApplicationListener<WebServerInitializedEvent> {

    private final Catalog catalog;
    private final LlmProvider provider;

    StartupMessage(Catalog catalog, LlmProvider provider) {
        this.catalog = catalog;
        this.provider = provider;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        try {
            catalog.load();
        } catch (ApiException e) {
            System.err.println("Warning: " + e.getMessage());
        }
        System.out.printf("SocksPal Sales Agent UI: http://localhost:%d  (%d products | %s: %s)%n",
                event.getWebServer().getPort(), catalog.products().size(), provider.name(), provider.model());
    }
}
