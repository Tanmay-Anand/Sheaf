package com.sheaf.adapters.llm.openaicompat;

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
 * Any endpoint speaking the OpenAI chat-completions API: OpenRouter (the default), OpenAI itself,
 * Azure OpenAI, Groq, Mistral, DeepSeek, Together, LM Studio, vLLM. Plain JDK HTTP, no provider SDK.
 * JSON mode ({@code response_format: json_object}) makes every reply parse as JSON.
 */
public final class OpenAiCompatibleModel extends HttpModel {

    private final boolean jsonMode;
    private final boolean keyRequired;

    public OpenAiCompatibleModel(String provider, String baseUrl, String apiKey, Duration timeout) {
        this(provider, baseUrl, apiKey, timeout, false, true);
    }

    public OpenAiCompatibleModel(String provider, String baseUrl, String apiKey, Duration timeout, boolean jsonMode, boolean keyRequired) {
        super(provider, baseUrl, apiKey, timeout);
        this.jsonMode = jsonMode;
        this.keyRequired = keyRequired;
    }

    @Override
    protected boolean needsKey() {
        return keyRequired;
    }

    private Map<String, String> headers() {
        return apiKey.isEmpty()
                ? Map.of("Content-Type", "application/json", "X-Title", "Sheaf")
                : Map.of("Authorization", "Bearer " + apiKey, "Content-Type", "application/json", "X-Title", "Sheaf");
    }

    @Override
    public Reply complete(Request request) {
        requireKey();
        Http r = send("POST", "/chat/completions", headers(), body(request));
        return parse(r.status(), r.body(), request.model());
    }

    @Override
    public List<String> models() {
        requireKey();
        Http r = send("GET", "/models", headers(), null);
        JsonNode root = checked(r);
        List<String> ids = new ArrayList<>();
        root.path("data").forEach(m -> ids.add(m.path("id").asText()));
        return ids;
    }

    /** OpenRouter's model list is public, so it can't prove a key; its /key endpoint can. */
    @Override
    public void verify() {
        requireKey();
        if (!baseUrl.contains("openrouter.ai")) {
            models();
            return;
        }
        checked(send("GET", "/key", headers(), null));
    }

    String body(Request request) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", request.model());
        root.put("max_tokens", request.maxTokens());
        root.put("temperature", request.temperature());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        for (Message m : request.messages()) messages.addObject().put("role", m.role()).put("content", m.content());
        if (jsonMode) root.putObject("response_format").put("type", "json_object");
        // OpenRouter reports each call's cost when asked; other endpoints ignore the field.
        root.putObject("usage").put("include", true);
        return root.toString();
    }

    private JsonNode checked(Http r) {
        JsonNode root = json(r);
        JsonNode error = root.path("error");
        if (r.status() >= 400 || !error.isMissingNode() && !error.isNull()) {
            int code = error.path("code").isInt() ? error.path("code").asInt() : r.status();
            String message = error.isObject() ? error.path("message").asText(clip(r.body())) : clip(r.body());
            throw failure(kindOf(code, message), code, message);
        }
        return root;
    }

    Reply parse(int status, String body, String requestedModel) {
        JsonNode root = checked(new Http(status, body));
        JsonNode choice = root.path("choices").path(0);
        String text = choice.path("message").path("content").asText(null);
        if (text == null) throw failure(Kind.BAD_RESPONSE, status, "no message content");
        JsonNode usage = root.path("usage");
        Double cost = usage.path("cost").isNumber() ? usage.path("cost").asDouble() : null;
        return new Reply(text, root.path("model").asText(requestedModel),
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), cost);
    }

    static Kind kindOf(int code, String message) {
        String m = message.toLowerCase(Locale.ROOT);
        if (code == 401 || code == 403) return Kind.INVALID_KEY;
        if (code == 402 || m.contains("insufficient credits") || m.contains("insufficient_quota")) return Kind.NO_CREDIT;
        if (code == 429) return Kind.RATE_LIMITED;
        if (code == 408) return Kind.TIMEOUT;
        if (m.contains("context length") || m.contains("context window") || m.contains("maximum context") || m.contains("too many tokens")) {
            return Kind.CONTEXT_TOO_LONG;
        }
        if (code == 404 || m.contains("not a valid model") || m.contains("no endpoints found") || m.contains("model not found")
                || m.contains("does not exist")) {
            return Kind.MODEL_NOT_FOUND;
        }
        if (code >= 500) return Kind.UNAVAILABLE;
        return Kind.BAD_RESPONSE;
    }
}
