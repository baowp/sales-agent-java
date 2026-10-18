package com.sockspal.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sockspal.agent.Fixtures;
import com.sockspal.agent.config.MemoryProperties;
import com.sockspal.agent.memory.ChatSearch;
import com.sockspal.agent.memory.ChatStore;

/** The sales-team API: off without a token, refused with a wrong one, and never shows a full visitor token. */
class SalesControllerTest {

    private final ChatStore store = Fixtures.chatStore();

    private MockMvc mvc(String token) {
        return MockMvcBuilders.standaloneSetup(new SalesController(new ChatSearch(store, null, ""), store,
                new MemoryProperties(token, 3))).build();
    }

    @Test
    void offWithoutATokenAndRefusesAWrongOne() throws Exception {
        mvc("").perform(get("/api/sales/search").param("q", "socks").header("Authorization", "Bearer "))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Sales search is off. Set SALES_SEARCH_TOKEN in .env and restart."));
        mvc("s3cret").perform(get("/api/sales/search").param("q", "socks")).andExpect(status().isUnauthorized());
        mvc("s3cret").perform(get("/api/sales/search").param("q", "socks").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchesAndShowsTranscriptsWithShortVisitorIds() throws Exception {
        store.touch("c1", "AbCdEfGhIjKlMnOpQrStUvWxYz012345");
        store.saveTurn("c1", "Grip socks for pilates?", "Yes, Pilates grip socks.", List.of("list_products"));
        MockMvc mvc = mvc("s3cret");

        mvc.perform(get("/api/sales/status").header("Authorization", "Bearer s3cret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("keyword"));
        mvc.perform(get("/api/sales/search").param("q", "pilates").header("Authorization", "Bearer s3cret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].conversationId").value("c1"))
                .andExpect(jsonPath("$.hits[0].visitor").value("AbCdEfGh"))
                .andExpect(jsonPath("$.hits[0].tools[0]").value("list_products"));
        mvc.perform(get("/api/sales/conversations/c1").header("Authorization", "Bearer s3cret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitor").value("AbCdEfGh"))
                .andExpect(jsonPath("$.turns[0].answer").value("Yes, Pilates grip socks."));
        mvc.perform(get("/api/sales/conversations/nope").header("Authorization", "Bearer s3cret"))
                .andExpect(status().isNotFound());
    }
}
