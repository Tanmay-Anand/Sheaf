package com.sheaf.application.planning;

import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * The model providers the service can reach, and a way to open one for a request. Which provider,
 * endpoint and key to use is the user's choice per request; keys the pane sends are used for that
 * request only and never stored.
 */
public interface LanguageModels {

    /**
     * @param needsKey        Whether a key is required (Ollama needs none).
     * @param local           Runs on the user's machine by default: the only kind zero-egress mode allows.
     * @param serverKey       The service holds a key for it (self-hosted), so the pane may send none.
     * @param customBaseUrl   The pane may point it at another endpoint (e.g. an OpenAI-compatible server).
     */
    record Provider(String id, String label, boolean needsKey, boolean local, String defaultBaseUrl, boolean serverKey,
                    boolean customBaseUrl, String defaultModel, List<String> suggestedModels) {}

    /**
     * @param apiKey A key sent by the pane for this request (store mode); null to use the service's own.
     */
    record Choice(String provider, @Nullable String baseUrl, @Nullable String apiKey) {}

    List<Provider> providers();

    String defaultProvider();

    /** The service is configured to allow only local models. */
    boolean zeroEgressForced();

    /** @throws ProviderRefused for an unknown provider or a base URL the service doesn't allow. */
    LanguageModelPort open(Choice choice);

    /** Why a provider choice can't be used; never a secret in the message. */
    final class ProviderRefused extends RuntimeException {
        private final String code;

        public ProviderRefused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
