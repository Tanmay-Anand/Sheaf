package com.sheaf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlanHasher;
import com.sheaf.domain.ir.Sink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thin vertical slice: POST /api/plan → hardcoded plan → {envelope, planHash, meta}.
 * Proves the round trip, the types, the JSON serialisation and the hash are all correct.
 * Runs in-process (MockMvc), no real server needed, works on all platforms.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private JsonNode ask(String question) throws Exception {
        String body = mockMvc.perform(post("/api/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("question", question))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void plan_endpoint_returns_an_envelope_its_hash_and_provenance() throws Exception {
        JsonNode record = ask("total revenue by region");

        PlanEnvelope envelope = objectMapper.treeToValue(record.get("envelope"), PlanEnvelope.class);
        assertThat(envelope.irVersion()).isEqualTo(PlanEnvelope.IR_VERSION);
        var query = (Plan.QueryPlan) envelope.plan();
        assertThat(query.source()).isEqualTo("Orders");
        assertThat(query.steps()).hasSize(3);
        assertThat(query.sink()).isInstanceOf(Sink.NewSheetSink.class);

        assertThat(record.get("planHash").asText()).isEqualTo(PlanHasher.hash(envelope)).startsWith("sha256:");
        assertThat(record.get("meta").get("modelId").asText()).isEqualTo("hardcoded-m1");
        // Provenance sits outside the hashed envelope.
        assertThat(record.get("envelope").has("meta")).isFalse();
        assertThat(record.get("envelope").get("plan").has("meta")).isFalse();
    }

    @Test
    void the_envelope_serialises_and_deserialises_without_loss_and_the_hash_is_stable() throws Exception {
        JsonNode first = ask("anything");
        JsonNode second = ask("something else");

        PlanEnvelope envelope = objectMapper.treeToValue(first.get("envelope"), PlanEnvelope.class);
        assertThat((JsonNode) objectMapper.valueToTree(envelope)).isEqualTo(first.get("envelope"));
        // Same plan, generated at different moments: same hash.
        assertThat(second.get("planHash")).isEqualTo(first.get("planHash"));
    }
}
