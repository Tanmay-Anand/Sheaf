package com.sheaf.adapters.llm.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sheaf.adapters.llm.HttpModel;
import com.sheaf.application.planning.LanguageModelPort.ModelException.Kind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A local model through Ollama (verified 2026-09-25: POST /api/chat with stream false and
 * format "json"; GET /api/tags lists the pulled models). No key, and with the default endpoint
 * nothing leaves the machine.
 */
public final class OllamaModel extends HttpModel {

    public OllamaModel(String baseUrl, Duration timeout) {
        super("ollama", baseUrl, "", timeout);
    }

    @Override
    protected boolean needsKey() {
        return false;
    }

    @Override
    public Reply complete(Request request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", request.model());
        body.put("stream", false);
        body.put("format", "json");
        ObjectNode options = body.putObject("options");
        options.put("temperature", request.temperature());
        options.put("num_predict", request.maxTokens());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        for (Message m : request.messages()) messages.addObject().put("role", m.role()).put("content", m.content());
        Http r = send("POST", "/api/chat", Map.of("Content-Type", "application/json"), body.toString());
        JsonNode root = checked(r);
        String text = root.path("message").path("content").asText("");
        if (text.isEmpty()) throw failure(Kind.BAD_RESPONSE, r.status(), "no message content");
        return new Reply(text, root.path("model").asText(request.model()),
                root.path("prompt_eval_count").asInt(0), root.path("eval_count").asInt(0), 0.0);
    }

    @Override
    public List<String> models() {
        JsonNode root = checked(send("GET", "/api/tags", Map.of(), null));
        List<String> names = new ArrayList<>();
        root.path("models").forEach(m -> names.add(m.path("name").asText()));
        return names;
    }

    private JsonNode checked(Http r) {
        JsonNode root = json(r);
        if (r.status() < 400 && !root.has("error")) return root;
        String message = root.path("error").asText(clip(r.body()));
        String m = message.toLowerCase(Locale.ROOT);
        Kind kind = r.status() == 404 || m.contains("not found") ? Kind.MODEL_NOT_FOUND
                : m.contains("context") ? Kind.CONTEXT_TOO_LONG
                : r.status() >= 500 ? Kind.UNAVAILABLE : Kind.BAD_RESPONSE;
        throw failure(kind, r.status(), message);
    }
}
