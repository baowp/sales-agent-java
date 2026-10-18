package com.sockspal.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sockspal.agent.Fixtures;
import com.sockspal.agent.agent.SalesAgent;
import com.sockspal.agent.agent.SalesAgentLoopTest.ScriptedModel;
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.config.AiConfig;
import com.sockspal.agent.config.LlmProvider;
import com.sockspal.agent.memory.ChatSearch;
import com.sockspal.agent.memory.ChatStore;
import com.sockspal.agent.sockspal.Catalog;
import com.sockspal.agent.sockspal.RfqService;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.sockspal.agent.agent.SalesAgentLoopTest.callTool;
import static com.sockspal.agent.agent.SalesAgentLoopTest.say;

/** The endpoints the web page calls, including the NDJSON stream from /api/ask. */
class ApiControllerTest {

    @TempDir
    Path rfqDir;

    private MockMvc mvc(ScriptedModel model, Catalog catalog) {
        return mvc(model, catalog, Fixtures.chatStore());
    }

    private MockMvc mvc(ScriptedModel model, Catalog catalog, ChatStore store) {
        var props = new AgentProperties(12, 5, "http://unused", 60, "draft", rfqDir.toString());
        var provider = new LlmProvider("fake", "http://localhost", "scripted", "key");
        var agent = new SalesAgent(model, new AiConfig().toolCallingManager(), provider, props);
        var controller = new ApiController(agent, catalog, new RfqService(catalog, null, rfqDir, false), provider, props,
                JsonMapper.builder().build(), store, new ChatSearch(store, null, ""), (q, a) -> "Chat about " + q);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** Posts a question (with the browser's visitor cookie, if any) and returns the finished response. */
    private static MockHttpServletResponse ask(MockMvc mvc, String question, @Nullable Cookie visitor) throws Exception {
        var request = post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.builder().build().writeValueAsString(Map.of("question", question)));
        if (visitor != null) request.cookie(visitor);
        var started = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse();
    }

    /** The visitor cookie from the Set-Cookie header the controller wrote. */
    private static Cookie visitorCookie(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith(ApiController.VISITOR_COOKIE + "=").contains("HttpOnly", "SameSite=Lax", "Max-Age=");
        return new Cookie(ApiController.VISITOR_COOKIE, header.substring(header.indexOf('=') + 1, header.indexOf(';')));
    }

    @Test
    void returningBrowserGetsItsOwnHistoryAndANewBrowserDoesNot() throws Exception {
        ChatStore store = Fixtures.chatStore();
        var model = new ScriptedModel(Fixtures.tools(rfqDir, store))
                .then(say("500 pairs are $225.00."))                          // chat 1
                .then(callTool("h1", "get_customer_history", "{}"))           // chat 2, same browser
                .then(say("Last time you asked about 500 pairs."))
                .then(callTool("h2", "get_customer_history", "{}"))           // chat 3, another browser
                .then(say("I don't see earlier chats."));
        MockMvc mvc = mvc(model, Fixtures.catalog(), store);

        Cookie browser = visitorCookie(ask(mvc, "500 soccer socks?", null));
        assertThat(model.prompts.get(0).getInstructions().getFirst().getText()).doesNotContain("used this chat before");

        Cookie again = visitorCookie(ask(mvc, "Same as last time", browser));
        assertThat(again.getValue()).isEqualTo(browser.getValue());
        assertThat(model.prompts.get(1).getInstructions().getFirst().getText())
                .contains("This customer has used this chat before (1 earlier conversation(s), 0 RFQ(s), last on");
        assertThat(toolResult(model.prompts.get(2))).contains("500 soccer socks?", "500 pairs are $225.00.");

        Cookie other = visitorCookie(ask(mvc, "Same as last time", null));
        assertThat(other.getValue()).isNotEqualTo(browser.getValue());
        assertThat(model.prompts.get(3).getInstructions().getFirst().getText()).doesNotContain("used this chat before");
        assertThat(toolResult(model.prompts.get(4))).contains("No earlier chats or RFQs for this customer.");
    }

    @Test
    void aSessionIdFromAnotherBrowserStartsANewConversation() throws Exception {
        ChatStore store = Fixtures.chatStore();
        var model = new ScriptedModel(Fixtures.tools(rfqDir, store)).then(say("Hi A.")).then(say("Hi B."));
        MockMvc mvc = mvc(model, Fixtures.catalog(), store);
        var first = ask(mvc, "I'm A", null);
        String sessionA = JsonMapper.builder().build().readTree(first.getContentAsString(StandardCharsets.UTF_8).lines()
                .findFirst().orElseThrow()).get("sessionId").asString();

        var started = mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"I'm B\",\"sessionId\":\"" + sessionA + "\"}"))
                .andReturn(); // no cookie: a different browser
        String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(sessionA);
        assertThat(model.prompts.get(1).getInstructions()).extracting(m -> m.getText()).doesNotContain("I'm A");
    }

    @Test
    void chatListShowsTitledChatsOfThisBrowserAndAChatCanBeReopenedAfterARestart() throws Exception {
        ChatStore store = Fixtures.chatStore();
        var model = new ScriptedModel(Fixtures.tools(rfqDir, store))
                .then(say("500 pairs are $225.00."))
                .then(say("1000 pairs are $400.00."));
        MockMvc mvc = mvc(model, Fixtures.catalog(), store);
        var first = ask(mvc, "500 soccer socks?", null);
        Cookie browser = visitorCookie(first);
        var json = JsonMapper.builder().build();
        List<JsonNode> lines = first.getContentAsString(StandardCharsets.UTF_8).lines().map(json::readTree).toList();
        String chatId = lines.getFirst().get("sessionId").asString();
        assertThat(lines.getLast().get("title").asString()).isEqualTo("Chat about 500 soccer socks?");

        mvc.perform(get("/api/conversations").cookie(browser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(chatId))
                .andExpect(jsonPath("$[0].title").value("Chat about 500 soccer socks?"))
                .andExpect(jsonPath("$[0].messages").value(1));
        mvc.perform(get("/api/conversations")).andExpect(jsonPath("$.length()").value(0)); // no cookie, no list
        mvc.perform(get("/api/conversations/" + chatId).cookie(new Cookie(ApiController.VISITOR_COOKIE, "x".repeat(32))))
                .andExpect(status().isNotFound());                                       // another browser
        mvc.perform(get("/api/conversations/" + chatId).cookie(browser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].answer").value("500 pairs are $225.00."));

        // A new controller has no sessions in memory, like after a restart: the saved chat is rebuilt.
        MockMvc restarted = mvc(model, Fixtures.catalog(), store);
        var started = restarted.perform(post("/api/ask").cookie(browser).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"And 1000?\",\"sessionId\":\"" + chatId + "\"}"))
                .andReturn();
        String body = restarted.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"sessionId\":\"" + chatId + "\"", "1000 pairs are $400.00.").doesNotContain("\"title\"");
        assertThat(model.prompts.get(1).getInstructions()).extracting(m -> m.getText())
                .containsSubsequence("500 soccer socks?", "500 pairs are $225.00.", "And 1000?");
        assertThat(store.conversationsOf(browser.getValue(), 10)).singleElement()
                .satisfies(c -> assertThat(c.messages()).isEqualTo(2));
    }

    private static String toolResult(Prompt prompt) {
        return ((ToolResponseMessage) prompt.getInstructions().getLast()).getResponses().getFirst().responseData();
    }

    @Test
    void askStreamsSessionToolStepsAndAnswer() throws Exception {
        var model = new ScriptedModel(Fixtures.tools(rfqDir))
                .then(callTool("c1", "calculate_quote", "{\"product\":\"soccer-socks\",\"quantityPairs\":500}"))
                .then(say("That's $225.00."));
        MockMvc mvc = mvc(model, Fixtures.catalog());

        var started = mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"500 soccer socks?\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var json = JsonMapper.builder().build();
        List<JsonNode> lines = body.lines().map(json::readTree).toList();
        assertThat(lines).extracting(l -> l.get("type").asString()).containsExactly("session", "tool", "answer", "title");
        assertThat(lines.get(0).get("sessionId").asString()).isNotBlank();
        assertThat(lines.get(1).get("name").asString()).isEqualTo("calculate_quote");
        assertThat(lines.get(1).at("/args/quantityPairs").asInt()).isEqualTo(500);
        assertThat(lines.get(2).get("text").asString()).isEqualTo("That's $225.00.");
    }

    @Test
    void emptyQuestionIsRejected() throws Exception {
        MockMvc mvc = mvc(new ScriptedModel(Fixtures.tools(rfqDir)), Fixtures.catalog());
        var started = mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"  \"}"))
                .andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Empty message"));
    }

    @Test
    void infoListsCatalogAndModel() throws Exception {
        mvc(new ScriptedModel(Fixtures.tools(rfqDir)), Fixtures.catalog())
                .perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("fake"))
                .andExpect(jsonPath("$.rfqMode").value("draft"))
                .andExpect(jsonPath("$.products.length()").value(9))
                .andExpect(jsonPath("$.products[0].retailPrice").value("$4.00"));
    }
}
