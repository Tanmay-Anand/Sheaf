package com.sheaf.adapters.llm.anthropic;

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
 * Claude, through the Messages API (verified 2026-09-25: x-api-key, anthropic-version 2023-06-01;
 * errors are {type:"error", error:{type, message}}). Only text blocks are read: some models always
 * think, and their thinking blocks are not the answer.
 */
public final class AnthropicModel extends HttpModel {

    static final String VERSION = "2023-06-01";

    public AnthropicModel(String baseUrl, String apiKey, Duration timeout) {
        super("anthropic", baseUrl, apiKey, timeout);
    }

    private Map<String, String> headers() {
        return Map.of("x-api-key", apiKey, "anthropic-version", VERSION, "content-type", "application/json");
    }

    @Override
    public Reply complete(Request request) {
        requireKey();
        ObjectNode body = JSON.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        body.put("system", request.system());
        ArrayNode messages = body.putArray("messages");
        for (Message m : request.messages()) messages.addObject().put("role", m.role()).put("content", m.content());
        Http r = send("POST", "/v1/messages", headers(), body.toString());
        JsonNode root = checked(r);
        var text = new StringBuilder();
        root.path("content").forEach(block -> {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        });
        if (text.isEmpty()) {
            throw failure(Kind.BAD_RESPONSE, r.status(), "max_tokens".equals(root.path("stop_reason").asText())
                    ? "the answer was cut off before any text (max tokens reached)" : "no text in the answer");
        }
        JsonNode usage = root.path("usage");
        return new Reply(text.toString(), root.path("model").asText(request.model()),
                usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0), null);
    }

    @Override
    public List<String> models() {
        requireKey();
        JsonNode root = checked(send("GET", "/v1/models?limit=100", headers(), null));
        List<String> ids = new ArrayList<>();
        root.path("data").forEach(m -> ids.add(m.path("id").asText()));
        return ids;
    }

    private JsonNode checked(Http r) {
        JsonNode root = json(r);
        if (r.status() < 400 && !"error".equals(root.path("type").asText())) return root;
        String type = root.path("error").path("type").asText("");
        String message = root.path("error").path("message").asText(clip(r.body()));
        throw failure(kindOf(r.status(), type, message), r.status(), message);
    }

    static Kind kindOf(int status, String type, String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "authentication_error", "permission_error" -> Kind.INVALID_KEY;
            case "billing_error" -> Kind.NO_CREDIT;
            case "rate_limit_error" -> Kind.RATE_LIMITED;
            case "timeout_error" -> Kind.TIMEOUT;
            case "not_found_error" -> Kind.MODEL_NOT_FOUND;
            case "overloaded_error", "api_error" -> Kind.UNAVAILABLE;
            case "request_too_large" -> Kind.CONTEXT_TOO_LONG;
            default -> {
                if (m.contains("prompt is too long") || m.contains("context")) yield Kind.CONTEXT_TOO_LONG;
                if (m.contains("credit balance") || m.contains("spend limit")) yield Kind.NO_CREDIT;
                if (m.contains("model")) yield Kind.MODEL_NOT_FOUND;
                if (status == 401 || status == 403) yield Kind.INVALID_KEY;
                if (status == 429) yield Kind.RATE_LIMITED;
                yield status >= 500 ? Kind.UNAVAILABLE : Kind.BAD_RESPONSE;
            }
        };
    }
}
