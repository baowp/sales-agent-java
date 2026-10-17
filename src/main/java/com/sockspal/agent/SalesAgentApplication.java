package com.sockspal.agent;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SocksPal sales agent.
 *
 * <pre>
 *   java -jar sales-agent.jar                    web UI on http://localhost:3002
 *   java -jar sales-agent.jar --cli              chat in the terminal
 *   java -jar sales-agent.jar --cli "question"   one-shot question (-q hides the tool trace)
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SalesAgentApplication {

    public static void main(String[] args) {
        boolean cli = Arrays.asList(args).contains("--cli");
        var context = new SpringApplicationBuilder(SalesAgentApplication.class)
                .web(cli ? WebApplicationType.NONE : WebApplicationType.SERVLET)
                .properties("sales.cli=" + cli)
                .run(args);
        // The chat ends when the CLI runner returns; HTTP client threads shouldn't keep the JVM alive.
        if (cli) System.exit(SpringApplication.exit(context));
    }
}
