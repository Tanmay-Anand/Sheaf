package com.sheaf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheaf.domain.ir.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thin vertical slice: POST /api/plan → hardcoded Plan → JSON response.
 * Proves the round trip, the types, and the JSON serialisation are all correct.
 * Runs in-process (MockMvc), no real server needed, works on all platforms.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void plan_endpoint_returns_a_valid_plan() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/plan")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"question":"total revenue by region"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        Plan plan = objectMapper.readValue(result.getResponse().getContentAsString(), Plan.class);

        assertThat(plan.source().ref()).isEqualTo("Orders");
        assertThat(plan.steps()).hasSize(3);
        assertThat(plan.sink()).isNotNull();
        assertThat(plan.meta()).isNotNull();
        assertThat(plan.meta().modelId()).isEqualTo("hardcoded-m1");
    }

    @Test
    void plan_serialises_and_deserialises_without_loss() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/plan")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"question":"anything"}
                                        """))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();

        // Deserialise back to Plan and re-serialise; the two JSON strings must be equal.
        Plan plan = objectMapper.readValue(json, Plan.class);
        String rejson = objectMapper.writeValueAsString(plan);

        assertThat(objectMapper.readTree(rejson)).isEqualTo(objectMapper.readTree(json));
    }
}
