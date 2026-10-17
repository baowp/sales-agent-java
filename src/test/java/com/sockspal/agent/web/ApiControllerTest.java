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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sockspal.agent.Fixtures;
import com.sockspal.agent.agent.SalesAgent;
import com.sockspal.agent.agent.SalesAgentLoopTest.ScriptedModel;
import com.sockspal.agent.config.AgentProperties;
import com.sockspal.agent.config.AiConfig;
import com.sockspal.agent.config.LlmProvider;
import com.sockspal.agent.sockspal.Catalog;
import com.sockspal.agent.sockspal.RfqService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.sockspal.agent.agent.SalesAgentLoopTest.callTool;
import static com.sockspal.agent.agent.SalesAgentLoopTest.say;

/** The endpoints the web page calls, including the NDJSON stream from /api/ask. */
class ApiControllerTest {

    @TempDir
    Path rfqDir;

    private MockMvc mvc(ScriptedModel model, Catalog catalog) {
        var props = new AgentProperties(12, 5, "http://unused", 60, "draft", rfqDir.toString());
        var provider = new LlmProvider("fake", "http://localhost", "scripted", "key");
        var agent = new SalesAgent(model, new AiConfig().toolCallingManager(), provider, props);
        var controller = new ApiController(agent, catalog, new RfqService(catalog, null, rfqDir, false), provider, props,
                JsonMapper.builder().build());
        return MockMvcBuilders.standaloneSetup(controller).build();
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
        assertThat(lines).extracting(l -> l.get("type").asString()).containsExactly("session", "tool", "answer");
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
