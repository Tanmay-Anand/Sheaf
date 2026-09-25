package com.sheaf.application.planning;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sheaf.domain.catalog.WorkbookCatalog;
import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.types.CatalogEnvironment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Planning as the web adapter sees it: which providers exist, whether a choice works, and a question
 * plus the pane's catalog (or its hash) in, an outcome out. Zero-egress mode is enforced here, before
 * any adapter makes a request.
 */
@Service
public class Planning {

    private final LanguageModels models;
    private final CatalogCache catalogs;

    public Planning(LanguageModels models, CatalogCache catalogs) {
        this.models = models;
        this.catalogs = catalogs;
    }

    public record Status(String defaultProvider, boolean zeroEgressForced, String promptVersion, List<LanguageModels.Provider> providers) {}

    public Status status() {
        return new Status(models.defaultProvider(), models.zeroEgressForced(), PlannerPrompt.VERSION, models.providers());
    }

    /** The catalog the service no longer has; the pane sends it again in full. */
    public static final class CatalogNeeded extends RuntimeException {
        public CatalogNeeded() {
            super("Send the catalog again: the service doesn't have it (restarted, or it was evicted).");
        }
    }

    /**
     * @param catalog     The catalog, or null to use the one with {@code catalogHash} sent earlier.
     * @param zeroEgress  The pane's zero-egress switch; the service's setting can force it on.
     */
    public record Ask(String question, @Nullable WorkbookCatalog catalog, @Nullable String catalogHash, @Nullable String model,
                      LanguageModels.Choice choice, boolean zeroEgress) {}

    /** The planner's outcome, plus the catalog's hash so the pane can send only the hash next time. */
    public record Answer(@JsonUnwrapped Planner.Outcome outcome, String catalogHash) {}

    private LanguageModelPort open(LanguageModels.Choice choice, boolean zeroEgress) {
        LanguageModelPort model = models.open(choice);
        if ((zeroEgress || models.zeroEgressForced()) && !isLocal(model.endpoint())) {
            throw new LanguageModels.ProviderRefused("ZERO_EGRESS",
                    "Zero-egress mode is on: only a model on this machine (Ollama at localhost) can be used.");
        }
        return model;
    }

    static boolean isLocal(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Runs a provider call and removes the request's key from any failure message, whatever the
     * adapter put in it: defence in depth behind each adapter's own scrubbing.
     */
    private static <T> T scrubbed(LanguageModels.Choice choice, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (LanguageModelPort.ModelException e) {
            String key = choice.apiKey();
            if (key == null || key.isBlank() || e.getMessage() == null || !e.getMessage().contains(key.strip())) throw e;
            throw new LanguageModelPort.ModelException(e.kind(), e.getMessage().replace(key.strip(), "[key]"));
        }
    }

    public List<String> modelList(LanguageModels.Choice choice, boolean zeroEgress) {
        return scrubbed(choice, () -> open(choice, zeroEgress).models());
    }

    /** Proves the provider, endpoint and key work, without paying for a completion. */
    public void test(LanguageModels.Choice choice, boolean zeroEgress) {
        scrubbed(choice, () -> {
            open(choice, zeroEgress).verify();
            return null;
        });
    }

    public Answer ask(Ask ask) {
        WorkbookCatalog catalog = ask.catalog();
        String hash;
        if (catalog != null) {
            hash = catalogs.put(catalog);
        } else {
            catalog = ask.catalogHash() == null ? null : catalogs.get(ask.catalogHash());
            if (catalog == null) throw new CatalogNeeded();
            hash = ask.catalogHash();
        }
        LanguageModelPort model = open(ask.choice(), ask.zeroEgress());
        String providerId = ask.choice().provider() == null || ask.choice().provider().isBlank() ? models.defaultProvider() : ask.choice().provider();
        String modelId = ask.model() == null || ask.model().isBlank()
                ? models.providers().stream().filter(p -> p.id().equals(providerId)).map(LanguageModels.Provider::defaultModel).findFirst().orElse("")
                : ask.model().strip();
        if (modelId.isBlank()) throw new LanguageModels.ProviderRefused("MODEL_REQUIRED", "Choose a model for " + providerId + ".");
        WorkbookCatalog c = catalog;
        var outcome = scrubbed(ask.choice(), () -> new Planner(model).plan(ask.question(), CatalogEnvironment.from(c), PlannerPrompt.exemplars(c), modelId));
        return new Answer(outcome, hash);
    }
}
