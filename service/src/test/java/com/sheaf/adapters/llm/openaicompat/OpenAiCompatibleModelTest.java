package com.sheaf.adapters.llm.openaicompat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheaf.application.planning.LanguageModelPort.Message;
import com.sheaf.application.planning.LanguageModelPort.ModelException;
import com.sheaf.application.planning.LanguageModelPort.ModelException.Kind;
import com.sheaf.application.planning.LanguageModelPort.Request;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter against recorded OpenRouter responses served locally: no live key, no network.
 * The fake key must never appear in anything the adapter reports.
 */
class OpenAiCompatibleModelTest {

    private static final String KEY = "sk-or-v1-test-0123456789abcdef";
    private static final Request REQUEST = new Request("anthropic/claude-sonnet-4.6", "system text", List.of(new Message("user", "q")), 500, 0.0);

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiCompatibleModel model(String key) {
        return new OpenAiCompatibleModel("openrouter", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/", key, Duration.ofSeconds(5));
    }

    private void respond(int status, String reply) {
        this.status = status;
        this.reply = reply;
    }

    @Test
    void sends_the_system_prompt_and_messages_with_the_key_only_in_the_header() throws Exception {
        respond(200, """
                {"id":"gen-1","model":"anthropic/claude-sonnet-4.6","choices":[{"message":{"role":"assistant","content":"{\\"response\\":\\"refuse\\"}"}}],
                 "usage":{"prompt_tokens":1234,"completion_tokens":56,"total_tokens":1290,"cost":0.00454}}
                """);
        var r = model("\"" + KEY + "\"").complete(REQUEST);
        assertThat(r.text()).isEqualTo("{\"response\":\"refuse\"}");
        assertThat(r.promptTokens()).isEqualTo(1234);
        assertThat(r.completionTokens()).isEqualTo(56);
        assertThat(r.cost()).isEqualTo(0.00454);
        assertThat(auth.get()).isEqualTo("Bearer " + KEY); // quotes from .env removed
        var sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.get("model").asText()).isEqualTo("anthropic/claude-sonnet-4.6");
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(500);
        assertThat(sent.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(sent.get("messages").get(1).get("content").asText()).isEqualTo("q");
        assertThat(body.get()).doesNotContain(KEY);
    }

    @Test
    void each_failure_has_its_own_kind_and_never_carries_the_key() {
        record Case(int status, String body, Kind kind) {}
        List<Case> cases = List.of(
                new Case(401, "{\"error\":{\"code\":401,\"message\":\"Invalid key " + KEY + "\"}}", Kind.INVALID_KEY),
                new Case(402, "{\"error\":{\"code\":402,\"message\":\"Insufficient credits\"}}", Kind.NO_CREDIT),
                new Case(429, "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\"}}", Kind.RATE_LIMITED),
                new Case(408, "{\"error\":{\"code\":408,\"message\":\"Timed out\"}}", Kind.TIMEOUT),
                new Case(400, "{\"error\":{\"code\":400,\"message\":\"This endpoint's maximum context length is 200000 tokens\"}}", Kind.CONTEXT_TOO_LONG),
                new Case(400, "{\"error\":{\"code\":400,\"message\":\"x/y is not a valid model ID\"}}", Kind.MODEL_NOT_FOUND),
                new Case(502, "{\"error\":{\"code\":502,\"message\":\"Provider returned error\"}}", Kind.UNAVAILABLE),
                new Case(200, "{\"error\":{\"code\":429,\"message\":\"Rate limited upstream\"}}", Kind.RATE_LIMITED),
                new Case(200, "<html>oops</html>", Kind.BAD_RESPONSE));
        for (Case c : cases) {
            respond(c.status(), c.body());
            assertThatThrownBy(() -> model(KEY).complete(REQUEST))
                    .as(c.body())
                    .isInstanceOfSatisfying(ModelException.class, e -> {
                        assertThat(e.kind()).isEqualTo(c.kind());
                        assertThat(e.getMessage()).doesNotContain(KEY);
                        assertThat(e.userMessage()).doesNotContain(KEY).isNotBlank();
                    });
        }
    }

    @Test
    void without_a_key_nothing_is_sent() {
        var m = model("  ");
        assertThat(m.configured()).isFalse();
        assertThatThrownBy(() -> m.complete(REQUEST)).isInstanceOfSatisfying(ModelException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.NOT_CONFIGURED));
        assertThat(body.get()).isNull();
    }

    @Test
    void a_slow_provider_is_a_timeout() {
        var slow = new OpenAiCompatibleModel("openrouter", "http://10.255.255.1:81/api/v1", KEY, Duration.ofMillis(300));
        assertThatThrownBy(() -> slow.complete(REQUEST)).isInstanceOfSatisfying(ModelException.class,
                e -> assertThat(e.kind()).isIn(Kind.TIMEOUT, Kind.UNAVAILABLE));
    }
}
