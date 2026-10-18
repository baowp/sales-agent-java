package com.sockspal.agent.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.sockspal.agent.agent.AgentEvent;
import com.sockspal.agent.agent.SalesAgent;
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.config.LlmProvider;
import com.sockspal.agent.sockspal.ApiException;
import com.sockspal.agent.sockspal.Catalog;

/**
 * Terminal chat, active with {@code --cli}.
 *
 * <pre>
 *   --cli                        interactive chat ("reset" starts over, "exit" quits)
 *   --cli "question"             one-shot question
 *   --cli -q ...                 hide the tool-call trace
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "sales.cli", havingValue = "true")
public class ChatCli implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatCli.class);

    private final SalesAgent agent;
    private final Catalog catalog;
    private final LlmProvider provider;
    private final AgentProperties props;

    public ChatCli(SalesAgent agent, Catalog catalog, LlmProvider provider, AgentProperties props) {
        this.agent = agent;
        this.catalog = catalog;
        this.provider = provider;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        boolean verbose = true;
        List<String> words = new ArrayList<>();
        for (String a : args.getSourceArgs()) {
            switch (a) {
                case "--cli" -> { }
                case "-q", "--quiet" -> verbose = false;
                case "-h", "--help" -> {
                    System.out.println("Usage: java -jar sales-agent.jar --cli [--quiet] [\"question\"]");
                    return;
                }
                default -> words.add(a);
            }
        }

        // An interactive console uses its own encoding; pipes and redirects use UTF-8.
        Console console = System.console();
        boolean terminal = console != null && console.isTerminal();
        Charset charset = terminal ? console.charset() : StandardCharsets.UTF_8;
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, charset);

        try {
            catalog.load();
        } catch (ApiException e) {
            err.println("Warning: " + e.getMessage());
        }
        err.printf("SocksPal catalog: %d product(s) | %s: %s | RFQ mode: %s%n", catalog.products().size(),
                provider.name(), provider.model(), props.submitRfqs() ? "submit" : "draft");

        boolean trace = verbose;
        Consumer<AgentEvent> onEvent = event -> {
            switch (event) {
                case AgentEvent.Retry r when r.status().equals("timeout") ->
                        err.printf("  (%s did not respond in time, retrying)%n", provider.name());
                case AgentEvent.Retry r -> err.printf("  (%s from %s, retrying in %ds)%n", r.status(), provider.name(), r.seconds());
                case AgentEvent.ToolError t -> err.printf("  ✗ %s: %s%n", t.name(), t.message());
                case AgentEvent.RfqSaved r -> err.printf("  (RFQ %s: %s)%n", r.id(), r.status());
                case AgentEvent.ToolCall t -> {
                    if (trace) err.printf("  → %s(%s)%n", t.name(), t.args());
                }
            }
        };
        List<Message> history = agent.newHistory();

        if (!words.isEmpty()) {
            out.println(agent.ask(history, String.join(" ", words), onEvent));
            return;
        }

        err.println("Chat as a customer (or \"exit\"; \"reset\" starts a new conversation).\n");
        var in = new BufferedReader(new InputStreamReader(System.in, terminal ? charset : StandardCharsets.UTF_8));
        if (terminal) out.print("> ");
        for (String line; (line = in.readLine()) != null; ) {
            String q = line.trim();
            if (q.equals("exit") || q.equals("quit")) break;
            if (q.equals("reset")) {
                history = agent.newHistory();
                err.println("(conversation cleared)");
            } else if (!q.isEmpty()) {
                if (!terminal) out.println(q); // echo piped input so transcripts read naturally
                int mark = history.size();
                try {
                    out.printf("%n%s%n%n", agent.ask(history, q, onEvent));
                } catch (RuntimeException e) {
                    log.error("Question failed: {}", e.getMessage(), e);
                    err.printf("Error: %s%n%n", e.getMessage());
                    history.subList(mark, history.size()).clear(); // drop the failed turn so the next question starts clean
                }
            }
            if (terminal) out.print("> ");
        }
    }
}
