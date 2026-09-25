package com.sheaf;

import com.sheaf.application.planning.LanguageModelPort;
import com.sheaf.application.planning.LanguageModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Planning over HTTP with a fake provider: store-mode keys, zero-egress, catalog caching, specific failures. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) // the test harness must not print request headers either
@ExtendWith(OutputCaptureExtension.class)
class AskControllerTest {

    static final String PANE_KEY = "sk-pane-9f8e7d6c5b4a";
    static final AtomicReference<String> NEXT = new AtomicReference<>();
    static final AtomicReference<LanguageModelPort.Request> SEEN = new AtomicReference<>();
    static final AtomicReference<String> SEEN_KEY = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class FakeProviders {
        @Bean
        @Primary
        LanguageModels fakeModels() {
            return new LanguageModels() {
                @Override public List<Provider> providers() {
                    return List.of(
                            new Provider("fake", "Fake cloud", true, false, "https://api.fake.test", false, false, "fake-1", List.of("fake-1")),
                            new Provider("ollama", "Ollama", false, true, "http://localhost:11434", false, true, "", List.of()));
                }
                @Override public String defaultProvider() { return "fake"; }
                @Override public boolean zeroEgressForced() { return false; }
                @Override public LanguageModelPort open(Choice choice) {
                    String id = choice.provider() == null ? "fake" : choice.provider();
                    SEEN_KEY.set(choice.apiKey());
                    String endpoint = id.equals("ollama") ? "http://localhost:11434" : "https://api.fake.test";
                    return new LanguageModelPort() {
                        @Override public String provider() { return id; }
                        @Override public String endpoint() { return endpoint; }
                        @Override public boolean configured() { return true; }
                        @Override public List<String> models() { return List.of("fake-1", "fake-2"); }
                        @Override public Reply complete(Request request) {
                            CALLS.incrementAndGet();
                            SEEN.set(request);
                            String reply = NEXT.get();
                            if (reply.startsWith("!")) {
                                throw new ModelException(ModelException.Kind.valueOf(reply.substring(1)), "upstream rejected key " + choice.apiKey());
                            }
                            return new Reply(reply, id + "-model", 900, 80, 0.002);
                        }
                    };
                }
            };
        }
    }

    @Autowired MockMvc mockMvc;

    private static final String PLAN = """
            {"response":"plan","plan":{"kind":"query","source":"Sales","steps":[
              {"op":"aggregate","groupBy":["Region"],"measures":[{"fn":"sum","of":"Amount","as":"total"}]}],
              "sink":{"mode":"newSheet","name":"By region"}}}
            """;

    private static String ask(String question, String extra) {
        return "{\"question\":\"" + question + "\",\"model\":\"fake-1\"" + extra + ",\"catalog\":" + CheckControllerTest.CATALOG + "}";
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        SEEN_KEY.set(null);
    }

    @Test
    void a_question_becomes_a_checked_plan_with_its_run_record_and_what_was_sent(CapturedOutput output) throws Exception {
        NEXT.set(PLAN);
        String body = mockMvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).header("X-Sheaf-Api-Key", PANE_KEY)
                        .content(ask("total by region", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("plan"))
                .andExpect(jsonPath("$.report.valid").value(true))
                .andExpect(jsonPath("$.report.envelope.plan.params").isArray())
                .andExpect(jsonPath("$.run.provider").value("fake"))
                .andExpect(jsonPath("$.run.endpoint").value("https://api.fake.test"))
                .andExpect(jsonPath("$.run.promptVersion").value("planner-v5"))
                .andExpect(jsonPath("$.run.planHash").value(startsWith("sha256:")))
                .andExpect(jsonPath("$.catalogHash").value(startsWith("sha256:")))
                .andExpect(jsonPath("$.sent").value(org.hamcrest.Matchers.containsString("Amount: currency:INR")))
                .andReturn().getResponse().getContentAsString();
        // Store mode: the pane's key reached the adapter for this call, and nowhere else.
        assertThat(SEEN_KEY.get()).isEqualTo(PANE_KEY);
        assertThat(body).doesNotContain(PANE_KEY);
        assertThat(output.getAll()).doesNotContain(PANE_KEY);
    }

    @Test
    void a_failure_is_specific_and_never_echoes_the_key(CapturedOutput output) throws Exception {
        NEXT.set("!NO_CREDIT");
        String body = mockMvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).header("X-Sheaf-Api-Key", PANE_KEY)
                        .content(ask("anything", "")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("NO_CREDIT"))
                .andExpect(jsonPath("$.message").value("The provider account is out of credit."))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("sk-pane");
        assertThat(output.getAll()).doesNotContain(PANE_KEY);
    }

    @Test
    void zero_egress_refuses_any_non_local_provider_before_calling_it() throws Exception {
        NEXT.set(PLAN);
        mockMvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content(ask("q", ",\"zeroEgress\":true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ZERO_EGRESS"));
        assertThat(CALLS.get()).isZero();
        mockMvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content(ask("q", ",\"zeroEgress\":true,\"provider\":\"ollama\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.endpoint").value("http://localhost:11434"));
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void the_catalog_is_sent_once_then_named_by_its_hash() throws Exception {
        NEXT.set(PLAN);
        String first = mockMvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content(ask("q", "")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String hash = com.jayway.jsonpath.JsonPath.read(first, "$.catalogHash");
        mockMvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"model\":\"fake-1\",\"catalogHash\":\"" + hash + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("plan"));
        mockMvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"q\",\"model\":\"fake-1\",\"catalogHash\":\"sha256:unknown\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CATALOG_NEEDED"));
    }

    @Test
    void questions_about_the_workbook_get_an_explanation_not_a_plan() throws Exception {
        NEXT.set("{\"response\":\"explain\",\"answer\":\"Sales has two columns: Region and Amount.\"}");
        mockMvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content(ask("what columns do I have?", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("explain"))
                .andExpect(jsonPath("$.answer").value("Sales has two columns: Region and Amount."));
    }

    @Test
    void providers_can_be_tested_and_listed_and_the_status_carries_no_key() throws Exception {
        mockMvc.perform(post("/api/planner/test").contentType(MediaType.APPLICATION_JSON).header("X-Sheaf-Api-Key", PANE_KEY)
                        .content("{\"provider\":\"fake\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(post("/api/planner/models").contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"fake\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[1]").value("fake-2"));
        String status = mockMvc.perform(get("/api/planner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultProvider").value("fake"))
                .andExpect(jsonPath("$.providers[1].local").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(status).doesNotContain(PANE_KEY);
    }
}
