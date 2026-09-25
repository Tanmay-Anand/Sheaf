package com.sheaf.adapters.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sheaf.adapters.llm.HttpModel;
import com.sheaf.application.planning.LanguageModelPort.ModelException.Kind;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gemini, through generateContent (verified 2026-09-25: key in the x-goog-api-key header, never the
 * URL; systemInstruction; roles user/model; JSON mode via responseMimeType).
 */
public final class GeminiModel extends HttpModel {

    public GeminiModel(String baseUrl, String apiKey, Duration timeout) {
        super("gemini", baseUrl, apiKey, timeout);
    }

    private Map<String, String> headers() {
        return Map.of("x-goog-api-key", apiKey, "Content-Type", "application/json");
    }

    static String modelPath(String model) {
        String id = model.startsWith("models/") ? model.substring("models/".length()) : model;
        return "/v1beta/models/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20") + ":generateContent";
    }

    @Override
    public Reply complete(Request request) {
        requireKey();
        ObjectNode body = JSON.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.system());
        ArrayNode contents = body.putArray("contents");
        for (Message m : request.messages()) {
            ObjectNode c = contents.addObject();
            c.put("role", "assistant".equals(m.role()) ? "model" : "user");
            c.putArray("parts").addObject().put("text", m.content());
        }
        ObjectNode config = body.putObject("generationConfig");
        config.put("temperature", request.temperature());
        config.put("maxOutputTokens", request.maxTokens());
        config.put("responseMimeType", "application/json");
        Http r = send("POST", modelPath(request.model()), headers(), body.toString());
        JsonNode root = checked(r);
        JsonNode candidate = root.path("candidates").path(0);
        var text = new StringBuilder();
        candidate.path("content").path("parts").forEach(p -> {
            if (!p.path("thought").asBoolean(false)) text.append(p.path("text").asText(""));
        });
        if (text.isEmpty()) {
            String reason = candidate.path("finishReason").asText(root.path("promptFeedback").path("blockReason").asText("no candidates"));
            throw failure(Kind.BAD_RESPONSE, r.status(), "no text in the answer (" + reason + ")");
        }
        JsonNode usage = root.path("usageMetadata");
        return new Reply(text.toString(), root.path("modelVersion").asText(request.model()),
                usage.path("promptTokenCount").asInt(0), usage.path("candidatesTokenCount").asInt(0), null);
    }

    @Override
    public List<String> models() {
        requireKey();
        JsonNode root = checked(send("GET", "/v1beta/models?pageSize=200", headers(), null));
        List<String> ids = new ArrayList<>();
        root.path("models").forEach(m -> {
            boolean generates = false;
            for (JsonNode method : m.path("supportedGenerationMethods")) generates |= "generateContent".equals(method.asText());
            if (generates) ids.add(m.path("name").asText().replaceFirst("^models/", ""));
        });
        return ids;
    }

    private JsonNode checked(Http r) {
        JsonNode root = json(r);
        JsonNode error = root.path("error");
        if (r.status() < 400 && error.isMissingNode()) return root;
        int code = error.path("code").asInt(r.status());
        String status = error.path("status").asText("");
        String message = error.path("message").asText(clip(r.body()));
        throw failure(kindOf(code, status, message), code, message);
    }

    static Kind kindOf(int code, String status, String message) {
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("api key not valid") || m.contains("api_key_invalid") || "UNAUTHENTICATED".equals(status)
                || "PERMISSION_DENIED".equals(status) || code == 401 || code == 403) return Kind.INVALID_KEY;
        if ("RESOURCE_EXHAUSTED".equals(status) || code == 429) {
            return m.contains("billing") || m.contains("prepayment") ? Kind.NO_CREDIT : Kind.RATE_LIMITED;
        }
        if (m.contains("exceeds the maximum number of tokens") || m.contains("input token count")) return Kind.CONTEXT_TOO_LONG;
        if ("NOT_FOUND".equals(status) || code == 404) return Kind.MODEL_NOT_FOUND;
        if ("DEADLINE_EXCEEDED".equals(status) || code == 504) return Kind.TIMEOUT;
        if (code >= 500) return Kind.UNAVAILABLE;
        return Kind.BAD_RESPONSE;
    }
}
