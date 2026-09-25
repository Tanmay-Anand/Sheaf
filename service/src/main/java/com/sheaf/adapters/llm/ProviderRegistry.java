package com.sheaf.adapters.llm;

import com.sheaf.adapters.llm.anthropic.AnthropicModel;
import com.sheaf.adapters.llm.gemini.GeminiModel;
import com.sheaf.adapters.llm.ollama.OllamaModel;
import com.sheaf.adapters.llm.openaicompat.OpenAiCompatibleModel;
import com.sheaf.application.planning.LanguageModelPort;
import com.sheaf.application.planning.LanguageModels;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Opens the adapter a request asks for. A key sent by the pane is used for that request only; with
 * none, the service's own key for the provider (self-hosted) is used, if it has one.
 */
public final class ProviderRegistry implements LanguageModels {

    /** Adapters, by provider id. "openai-compatible" is any other OpenAI-style endpoint (a base URL is required). */
    static final Set<String> KNOWN = Set.of("openrouter", "openai", "anthropic", "gemini", "ollama", "openai-compatible");

    private final LlmProperties props;

    public ProviderRegistry(LlmProperties props) {
        this.props = props;
    }

    private LlmProperties.ProviderProperties config(String id) {
        var p = props.providers().get(id);
        if (!KNOWN.contains(id) || p == null) throw new ProviderRefused("UNKNOWN_PROVIDER", "Sheaf doesn't know the provider '" + id + "'.");
        return p;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public List<Provider> providers() {
        return props.providers().entrySet().stream()
                .filter(e -> KNOWN.contains(e.getKey()))
                .map(e -> {
                    var p = e.getValue();
                    String id = e.getKey();
                    boolean needsKey = !id.equals("ollama") && !id.equals("openai-compatible");
                    return new Provider(id, p.label(), needsKey, isLocal(p.baseUrl()), p.baseUrl() == null ? "" : p.baseUrl(),
                            !blank(p.apiKey()), props.allowCustomBaseUrl() || blank(p.baseUrl()),
                            p.defaultModel() == null ? "" : p.defaultModel(), p.models() == null ? List.of() : p.models());
                })
                .toList();
    }

    @Override
    public String defaultProvider() {
        return props.provider();
    }

    @Override
    public boolean zeroEgressForced() {
        return props.zeroEgress();
    }

    @Override
    public LanguageModelPort open(Choice choice) {
        String id = blank(choice.provider()) ? props.provider() : choice.provider().toLowerCase(Locale.ROOT);
        var p = config(id);
        String base = p.baseUrl();
        if (!blank(choice.baseUrl()) && !choice.baseUrl().strip().equals(base)) {
            if (!props.allowCustomBaseUrl()) {
                throw new ProviderRefused("CUSTOM_BASE_URL_DISABLED", "This Sheaf service doesn't allow custom endpoints.");
            }
            base = choice.baseUrl().strip();
        }
        if (blank(base)) throw new ProviderRefused("BASE_URL_REQUIRED", "Give the endpoint's base URL (e.g. http://localhost:1234/v1).");
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new ProviderRefused("BAD_BASE_URL", "'" + base + "' isn't a URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        // Keys travel only over HTTPS, except to this machine.
        if (!scheme.equals("https") && !(scheme.equals("http") && isLocal(base))) {
            throw new ProviderRefused("INSECURE_BASE_URL", "Use an https:// endpoint (http:// is allowed only for this machine).");
        }
        String key = !blank(choice.apiKey()) ? choice.apiKey() : p.apiKey();
        var timeout = props.timeout();
        return switch (id) {
            case "anthropic" -> new AnthropicModel(base, key, timeout);
            case "gemini" -> new GeminiModel(base, key, timeout);
            case "ollama" -> new OllamaModel(base, timeout);
            case "openai-compatible" -> new OpenAiCompatibleModel(id, base, key, timeout, props.jsonMode(), false);
            default -> new OpenAiCompatibleModel(id, base, key, timeout, props.jsonMode(), true);
        };
    }

    /** This machine: localhost, 127.0.0.0/8 or ::1. */
    public static boolean isLocal(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
