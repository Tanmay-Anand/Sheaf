package com.sheaf.adapters.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * sheaf.llm.* in application.yml.
 *
 * @param provider           The provider used when the pane doesn't choose one.
 * @param jsonMode           Ask providers that support it for JSON-only replies.
 * @param zeroEgress         Allow only local models (Ollama on this machine), whatever the pane asks.
 * @param allowCustomBaseUrl Let the pane point a provider at another endpoint. Keep false on a shared
 *                           service: otherwise any user could make it call internal addresses.
 * @param providers          Each provider's endpoint, default model, suggestions, and (self-hosted) key.
 */
@ConfigurationProperties("sheaf.llm")
public record LlmProperties(
        String provider,
        Duration timeout,
        boolean jsonMode,
        boolean zeroEgress,
        boolean allowCustomBaseUrl,
        Map<String, ProviderProperties> providers
) {
    /** @param apiKey The service's own key for this provider; empty when users bring theirs. */
    public record ProviderProperties(String label, String baseUrl, String apiKey, String defaultModel, List<String> models) {}
}
