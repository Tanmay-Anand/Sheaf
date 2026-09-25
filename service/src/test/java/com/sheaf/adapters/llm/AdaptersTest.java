package com.sheaf.adapters.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheaf.adapters.llm.anthropic.AnthropicModel;
import com.sheaf.adapters.llm.gemini.GeminiModel;
import com.sheaf.adapters.llm.ollama.OllamaModel;
import com.sheaf.application.planning.LanguageModelPort;
import com.sheaf.application.planning.LanguageModelPort.Message;
import com.sheaf.application.planning.LanguageModelPort.ModelException;
import com.sheaf.application.planning.LanguageModelPort.ModelException.Kind;
import com.sheaf.application.planning.LanguageModelPort.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Anthropic, Gemini and Ollama adapters against responses in each provider's documented shape
 * (checked against their docs on 2026-09-25), served locally: no live keys, no network.
 */
class AdaptersTest {

    private static final String KEY = "test-key-0123456789abcdef";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Request REQUEST = new Request("m-1", "SYSTEM",
            List.of(new Message("user", "q"), new Message("assistant", "a"), new Message("user", "fix it")), 700, 0.0);

    private FakeServer server;

    @BeforeEach
    void start() throws Exception {
        server = new FakeServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static void failsWith(LanguageModelPort model, Kind kind) {
        assertThatThrownBy(() -> model.complete(REQUEST)).isInstanceOfSatisfying(ModelException.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.getMessage()).doesNotContain(KEY);
        });
    }

    // ── Anthropic ─────────────────────────────────────────────────────────────

    @Test
    void anthropic_sends_system_apart_and_reads_only_text_blocks() throws Exception {
        server.on("/v1/messages", 200, """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-6","stop_reason":"end_turn",
                 "content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"{\\"response\\":"},{"type":"text","text":"\\"refuse\\"}"}],
                 "usage":{"input_tokens":900,"output_tokens":40}}
                """);
        var reply = new AnthropicModel(server.url(), KEY, Duration.ofSeconds(5)).complete(REQUEST);
        assertThat(reply.text()).isEqualTo("{\"response\":\"refuse\"}");
        assertThat(reply.promptTokens()).isEqualTo(900);
        assertThat(reply.completionTokens()).isEqualTo(40);
        var seen = server.last();
        assertThat(seen.header("x-api-key")).isEqualTo(KEY);
        assertThat(seen.header("anthropic-version")).isEqualTo("2023-06-01");
        var body = JSON.readTree(seen.body());
        assertThat(body.get("system").asText()).isEqualTo("SYSTEM");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(700);
        assertThat(body.get("messages")).hasSize(3);
        assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("assistant");
    }

    @Test
    void anthropic_errors_map_by_type() {
        var model = new AnthropicModel(server.url(), KEY, Duration.ofSeconds(5));
        record Case(int status, String type, String message, Kind kind) {}
        for (Case c : List.of(
                new Case(401, "authentication_error", "invalid x-api-key " + KEY, Kind.INVALID_KEY),
                new Case(402, "billing_error", "check billing", Kind.NO_CREDIT),
                new Case(400, "invalid_request_error", "Your credit balance is too low", Kind.NO_CREDIT),
                new Case(429, "rate_limit_error", "slow down", Kind.RATE_LIMITED),
                new Case(529, "overloaded_error", "Overloaded", Kind.UNAVAILABLE),
                new Case(404, "not_found_error", "model: claude-x", Kind.MODEL_NOT_FOUND),
                new Case(400, "invalid_request_error", "prompt is too long: 250000 tokens > 200000 maximum", Kind.CONTEXT_TOO_LONG),
                new Case(504, "timeout_error", "timed out", Kind.TIMEOUT))) {
            server.on("/v1/messages", c.status(), "{\"type\":\"error\",\"error\":{\"type\":\"" + c.type() + "\",\"message\":\"" + c.message() + "\"}}");
            failsWith(model, c.kind());
        }
    }

    @Test
    void anthropic_says_so_when_the_answer_was_cut_off() {
        server.on("/v1/messages", 200, "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"…\"}],\"stop_reason\":\"max_tokens\",\"usage\":{}}");
        assertThatThrownBy(() -> new AnthropicModel(server.url(), KEY, Duration.ofSeconds(5)).complete(REQUEST))
                .hasMessageContaining("cut off");
    }

    @Test
    void anthropic_lists_models() {
        server.on("/v1/models", 200, "{\"data\":[{\"id\":\"claude-sonnet-4-6\"},{\"id\":\"claude-haiku-4-5\"}]}");
        assertThat(new AnthropicModel(server.url(), KEY, Duration.ofSeconds(5)).models()).containsExactly("claude-sonnet-4-6", "claude-haiku-4-5");
    }

    // ── Gemini ────────────────────────────────────────────────────────────────

    @Test
    void gemini_keeps_the_key_out_of_the_url_and_asks_for_json() throws Exception {
        server.on("/v1beta/models/gemini-3.8-flash:generateContent", 200, """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"thinking…","thought":true},{"text":"{\\"response\\":\\"clarify\\"}"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":800,"candidatesTokenCount":30},"modelVersion":"gemini-3.8-flash"}
                """);
        var reply = new GeminiModel(server.url(), KEY, Duration.ofSeconds(5))
                .complete(new Request("gemini-3.8-flash", "SYSTEM", REQUEST.messages(), 700, 0.0));
        assertThat(reply.text()).isEqualTo("{\"response\":\"clarify\"}");
        assertThat(reply.promptTokens()).isEqualTo(800);
        var seen = server.last();
        assertThat(seen.path()).doesNotContain(KEY).doesNotContain("key=");
        assertThat(seen.header("x-goog-api-key")).isEqualTo(KEY);
        var body = JSON.readTree(seen.body());
        assertThat(body.at("/systemInstruction/parts/0/text").asText()).isEqualTo("SYSTEM");
        assertThat(body.at("/contents/1/role").asText()).isEqualTo("model");
        assertThat(body.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(body.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(700);
    }

    @Test
    void gemini_errors_map_by_status() {
        var model = new GeminiModel(server.url(), KEY, Duration.ofSeconds(5));
        record Case(int code, String status, String message, Kind kind) {}
        for (Case c : List.of(
                new Case(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.", Kind.INVALID_KEY),
                new Case(429, "RESOURCE_EXHAUSTED", "Quota exceeded", Kind.RATE_LIMITED),
                new Case(404, "NOT_FOUND", "models/m-1 is not found", Kind.MODEL_NOT_FOUND),
                new Case(400, "INVALID_ARGUMENT", "The input token count exceeds the maximum number of tokens allowed", Kind.CONTEXT_TOO_LONG),
                new Case(503, "UNAVAILABLE", "The model is overloaded", Kind.UNAVAILABLE))) {
            server.on("/v1beta/models/m-1:generateContent", c.code(),
                    "{\"error\":{\"code\":" + c.code() + ",\"status\":\"" + c.status() + "\",\"message\":\"" + c.message() + "\"}}");
            failsWith(model, c.kind());
        }
    }

    @Test
    void gemini_lists_only_models_that_generate() {
        server.on("/v1beta/models", 200, """
                {"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"]},
                           {"name":"models/text-embedding-9","supportedGenerationMethods":["embedContent"]}]}
                """);
        assertThat(new GeminiModel(server.url(), KEY, Duration.ofSeconds(5)).models()).containsExactly("gemini-3.8-flash");
    }

    // ── Ollama ────────────────────────────────────────────────────────────────

    @Test
    void ollama_needs_no_key_and_asks_for_json_without_streaming() throws Exception {
        server.on("/api/chat", 200, """
                {"model":"qwen3:8b","message":{"role":"assistant","content":"{\\"response\\":\\"refuse\\"}"},"done":true,
                 "prompt_eval_count":1500,"eval_count":60}
                """);
        var model = new OllamaModel(server.url(), Duration.ofSeconds(5));
        assertThat(model.configured()).isTrue();
        var reply = model.complete(new Request("qwen3:8b", "SYSTEM", List.of(new Message("user", "q")), 700, 0.0));
        assertThat(reply.text()).isEqualTo("{\"response\":\"refuse\"}");
        assertThat(reply.cost()).isEqualTo(0.0);
        var body = JSON.readTree(server.last().body());
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.get("format").asText()).isEqualTo("json");
        assertThat(body.at("/options/num_predict").asInt()).isEqualTo(700);
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(server.last().header("Authorization")).isNull();
    }

    @Test
    void ollama_reports_an_unpulled_model_and_lists_local_ones() {
        server.on("/api/chat", 404, "{\"error\":\"model \\\"nope\\\" not found, try pulling it first\"}");
        failsWith(new OllamaModel(server.url(), Duration.ofSeconds(5)), Kind.MODEL_NOT_FOUND);
        server.on("/api/tags", 200, "{\"models\":[{\"name\":\"qwen3:8b\"},{\"name\":\"llama4:scout\"}]}");
        assertThat(new OllamaModel(server.url(), Duration.ofSeconds(5)).models()).containsExactly("qwen3:8b", "llama4:scout");
    }

    @Test
    void a_stopped_ollama_is_unavailable_not_a_crash() {
        var model = new OllamaModel("http://127.0.0.1:1", Duration.ofSeconds(2));
        assertThatThrownBy(model::models).isInstanceOfSatisfying(ModelException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }
}
