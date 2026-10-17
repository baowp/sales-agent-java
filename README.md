# SocksPal Sales Agent (Java)

The Java version of [`../sales-agent`](../sales-agent): the same features, prompt, tools and web page, built with **JDK 25, Spring Boot 4.1.1 and Spring AI 2.0.1**. The agent loop is written out in [`SalesAgent.java`](src/main/java/com/sockspal/agent/agent/SalesAgent.java) rather than hidden inside `ChatClient`.

## Setup

```bash
copy .env.example .env      # then paste one provider's API key (same keys as the Node.js version)
mvn package                 # builds target/sales-agent-1.0.0.jar and runs the tests
```

## Use

```bash
java -jar target/sales-agent-1.0.0.jar                       # web UI at http://localhost:3002
java -jar target/sales-agent-1.0.0.jar --cli                 # chat in the terminal
java -jar target/sales-agent-1.0.0.jar --cli "500 black soccer socks, price?"
java -jar target/sales-agent-1.0.0.jar --cli -q "..."        # hide the tool-call trace
```

Run it from this folder: `.env` and `rfqs/` are resolved from the working directory. Port 3002 lets it run next to the Node.js version on 3001.

## The loop

In Spring AI 2.x, `ChatModel.call()` makes exactly one request and returns the model's tool calls without running them. (The 1.x `internalToolExecutionEnabled(false)` option no longer exists; the automatic loop now lives in `ChatClient`'s `ToolCallingAdvisor`, which this project doesn't use.) So `SalesAgent.ask()` runs the loop itself:

```java
for (int step = 0; step < maxSteps; step++) {
    Prompt prompt = new Prompt(List.copyOf(history), options);
    ChatResponse response = callModel(prompt, events);                // 1. one LLM round trip
    if (!response.hasToolCalls()) return answer;                       // 2. done
    events: ToolCall(name, args) for each call                         //    "Priced 500 pairs…" in the UI
    List<Message> next = toolCallingManager                            // 3. run the @Tool methods
            .executeToolCalls(prompt, response).conversationHistory();
    history = next;                                                    // 4. append results, loop
}
```

Because the loop is ours, it can:
- stream each tool call and failure to the UI as it happens,
- stop after `MAX_STEPS` and force a final answer,
- retry 429/5xx/timeouts with visible "retrying" notices (the SDK's own retries are off),
- answer an invented tool name with an error the model can recover from. `executeToolCalls` would throw instead.

## Where things are

| Node.js (`../sales-agent`) | Java | What it does |
|---|---|---|
| `providers.js` | `config/LlmProvider.java` | Provider presets, same env variables |
| `core.js` `chat()` | `config/AiConfig.java` (`OpenAiChatModel`) + `SalesAgent.callModel()` | One LLM call, with retries |
| `core.js` `TOOLS` + `runTool()` | `agent/SalesTools.java` (`@Tool` methods) | Tool definitions and their code |
| `core.js` `ask()` | `agent/SalesAgent.java` | The loop |
| `core.js` `SYSTEM_PROMPT` | `agent/SystemPrompt.java` | Rules for the model |
| `sockspal.js` | `sockspal/SocksPalClient.java`, `Catalog.java`, `RfqService.java`, `HtmlText.java` | SocksPal API, quote math, RFQs |
| `server.js` | `web/ApiController.java` | Same endpoints, NDJSON streaming |
| `agent.js` | `cli/ChatCli.java` | Terminal chat |
| `public/index.html` | `src/main/resources/static/index.html` | Same page; reads camelCase fields |
| `knowledge/*.md` | `src/main/resources/knowledge/*.md` | Policy summaries |

How a tool name reaches code: `AiConfig` registers `ToolCallbacks.from(salesTools)` in the chat model's default options, so every request lists the five tools. When the model replies `calculate_quote(...)`, `ToolCallingManager` finds the callback with that name, parses the JSON arguments into the method's parameters, and calls `SalesTools.calculateQuote()`.

## Differences from the Node.js version

- **Argument names are camelCase** (`quantityPairs`, `customerConfirmed`), because Spring AI builds the schema from Java parameter names. The model doesn't care; the page reads the new names.
- **Policy topics are an enum** (`WHOLESALE_FAQ`, ...) instead of a list read from the folder.
- **Policy text returns as plain markdown** via a custom `ToolCallResultConverter`. The default converter would JSON-quote it.
- **Startup takes 10–15 s** (Spring context, the OpenAI Java SDK and its Kotlin runtime). The server pays this once; the CLI pays it on every run. The first run after a build can be much slower while antivirus scans the 90 MB jar.

## Tests

`mvn test` runs 16 tests without any network or API key:
- `CatalogTest`: tier pricing, MOQ, price breaks, colour stock, product matching, HTML attribute tables, API-down error. Uses `src/test/resources/fixtures/wholesale-products.json`, a snapshot of the live catalog, so live prices can differ.
- `SalesToolsTest`: the generated tool schemas, plain-text policies, and RFQ confirmation, checks and saved payload.
- `SalesAgentLoopTest`: the real loop, `ToolCallingManager` and tools driven by a scripted model. Covers tool results fed back, an invented tool name, a failing tool, and the step limit.
- `ApiControllerTest`: `/api/info` and the `/api/ask` NDJSON stream.

## RFQ modes

The same as the Node.js version: `RFQ_MODE=draft` (default) saves to `rfqs/`; `RFQ_MODE=submit` also POSTs to SocksPal and creates a real RFQ. Submit mode hasn't been run against the live API. `rfqs/` holds customer names and emails.
