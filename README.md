# SocksPal Sales Agent (Java)

The Java version of [`../sales-agent`](../sales-agent): the same features, prompt, tools and web page, built with **JDK 25, Spring Boot 4.1.1 and Spring AI 2.0.1**. The agent loop is written out in [`SalesAgent.java`](src/main/java/com/sockspal/agent/agent/SalesAgent.java) rather than hidden inside `ChatClient`.

## Setup

```bash
copy .env.example .env      # then paste your provider's API key
mvn package                 # builds target/sales-agent-1.0.0.jar and runs the tests
```

## Configuration

All settings live in [`application.yml`](src/main/resources/application.yml), bound to typed records:

| Section | Record | What it holds |
|---|---|---|
| `llm.*` | `config/LlmProperties` | Provider (default `bailian`), the nine presets (base URL, model), overrides, temperature |
| `sales.*` | `config/AgentProperties` | Step limit, timeouts, SocksPal API URL, catalog cache, RFQ mode and folder |

**API keys are not in `application.yml`**, because it's committed to git and packed into the jar. Each preset references its key by name (`api-key: ${DASHSCOPE_API_KEY:}`), and the value comes from `.env` (gitignored) on your machine, or from a real environment variable or secrets manager on a server.

Ways to override a setting, from highest priority:
1. Command line: `java -jar target/sales-agent-1.0.0.jar --llm.provider=groq`
2. Environment variable: `LLM_PROVIDER=groq` (Spring maps it to `llm.provider`)
3. `.env`: `LLM_PROVIDER=groq` (works through the `${LLM_PROVIDER:bailian}` placeholder)
4. The default in `application.yml`

A wrong setting stops startup with a clear message, such as `Missing API key for "nvidia": add it to .env`. IDEs autocomplete `llm.*` and `sales.*` in `application.yml` from the metadata generated at build time.

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
for (int step = 1; step <= maxSteps; step++) {
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
- answer an invented tool name with an error the model can recover from. `executeToolCalls` would throw instead,
- log every step: the question, each model call (time, tokens), each tool call with its arguments, each tool result, and the final answer.

### Logs

The step log goes to the console (web mode) and to `logs/sales-agent.log` (both modes; the CLI writes only to the file so it doesn't mix into the answers). Web lines carry the first 8 characters of the session id:

```
[2c997f95] SalesAgent : Question: What is the MOQ for soccer socks?
[2c997f95] SalesAgent : Step 1/12: calling qwen3.7-flash-2026-07-15 with 2 messages
[2c997f95] SalesAgent : Step 1: model replied in 5408 ms (tokens in=1890 out=119)
[2c997f95] SalesAgent : Step 1: tool call list_products({"category": "Sport"})
[2c997f95] SalesAgent : Step 1: 1 tool call(s) finished in 15 ms
[2c997f95] SalesAgent : Step 1: tool result list_products (350 chars): [{"slug":"soccer-socks",...
[2c997f95] SalesAgent : Step 2/12: calling qwen3.7-flash-2026-07-15 with 4 messages
[2c997f95] SalesAgent : Step 2: model replied in 2242 ms (tokens in=2129 out=131)
[2c997f95] SalesAgent : Step 2: final answer after 7680 ms: The minimum order quantity (MOQ) for soccer socks is 100 pairs...
```

Tool results and answers are cut to 300 characters. For full tool results, use `--logging.level.com.sockspal.agent=debug`.

## Where things are

| Node.js (`../sales-agent`) | Java | What it does |
|---|---|---|
| `providers.js` | `application.yml` (`llm.*`) + `config/LlmProperties.java` | Provider presets, same key names in `.env` |
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

`mvn test` runs 23 tests without any network or API key:
- `LlmPropertiesTest`: binds the real `application.yml` with test key values: defaults, overrides, the custom provider, and the startup error messages.
- `CatalogTest`: tier pricing, MOQ, price breaks, colour stock, product matching, HTML attribute tables, API-down error. Uses `src/test/resources/fixtures/wholesale-products.json`, a snapshot of the live catalog, so live prices can differ.
- `SalesToolsTest`: the generated tool schemas, plain-text policies, RFQ confirmation, checks and saved payload, and the submit-mode result (SocksPal's RFQ number, never reported as sent when it wasn't).
- `SalesAgentLoopTest`: the real loop, `ToolCallingManager` and tools driven by a scripted model. Covers tool results fed back, an invented tool name, a failing tool, and the step limit.
- `ApiControllerTest`: `/api/info` and the `/api/ask` NDJSON stream.

## RFQ modes

The same as the Node.js version: `RFQ_MODE=draft` (default) saves to `rfqs/`; `RFQ_MODE=submit` also POSTs to SocksPal and creates a real RFQ. SocksPal requires a company name. In submit mode the customer gets SocksPal's `rfq_number` (e.g. `RFQ-20261018-7B8A8159`); the timestamp id is only the local file name. Drafts are never sent, and the agent is told to say so. `rfqs/` holds customer names and emails.
