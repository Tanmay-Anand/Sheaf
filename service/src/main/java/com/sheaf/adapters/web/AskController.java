package com.sheaf.adapters.web;

import com.sheaf.application.PlanChecking;
import com.sheaf.application.planning.LanguageModelPort.ModelException;
import com.sheaf.application.planning.LanguageModels;
import com.sheaf.application.planning.Planning;
import com.sheaf.domain.catalog.WorkbookCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Planning over HTTP. A key the pane sends (store mode) arrives only in the {@value #KEY_HEADER}
 * header, never in a body or URL, is used for that one provider call, and is never stored, logged or
 * returned.
 */
@RestController
@RequestMapping("/api")
class AskController {

    static final String KEY_HEADER = "X-Sheaf-Api-Key";

    private final Planning planning;
    private final PlanChecking checking;

    AskController(Planning planning, PlanChecking checking) {
        this.planning = planning;
        this.checking = checking;
    }

    /** Providers, their defaults and suggested models, and whether the service holds a key for each (never the key). */
    @GetMapping(value = "/planner", produces = MediaType.APPLICATION_JSON_VALUE)
    Planning.Status status() {
        return planning.status();
    }

    /** @param baseUrl Only for providers whose endpoint the user sets (Ollama elsewhere, OpenAI-compatible). */
    record ProviderRequest(String provider, String baseUrl, boolean zeroEgress) {}

    private static LanguageModels.Choice choice(String provider, String baseUrl, String key) {
        return new LanguageModels.Choice(provider, baseUrl, key);
    }

    /** The provider's models, where it lists them. */
    @PostMapping(value = "/planner/models", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, List<String>> models(@RequestBody ProviderRequest r, @RequestHeader(value = KEY_HEADER, required = false) String key) {
        return Map.of("models", planning.modelList(choice(r.provider(), r.baseUrl(), key), r.zeroEgress()));
    }

    /** "Test connection": proves endpoint and key without paying for a completion. */
    @PostMapping(value = "/planner/test", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> test(@RequestBody ProviderRequest r, @RequestHeader(value = KEY_HEADER, required = false) String key) {
        planning.test(choice(r.provider(), r.baseUrl(), key), r.zeroEgress());
        return Map.of("ok", true);
    }

    /**
     * @param catalog     The catalog; may be omitted when {@code catalogHash} names one sent before.
     * @param catalogHash From a previous answer; the service answers 409 when it no longer has it.
     */
    record AskRequest(String question, WorkbookCatalog catalog, String catalogHash, String model, String provider, String baseUrl,
                      boolean zeroEgress) {}

    /**
     * A question about the workbook → a checked plan, a clarifying question, an explanation or a
     * refusal. Only the catalog (schema, statistics, and sample values if the user allows them)
     * leaves the pane.
     */
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> ask(@RequestBody AskRequest request, @RequestHeader(value = KEY_HEADER, required = false) String key) {
        if (request.catalog() != null) {
            List<String> violations = checking.refusals(request.catalog());
            if (!violations.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("violations", violations));
            }
        }
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "EMPTY_QUESTION", "message", "Ask a question about the workbook."));
        }
        return ResponseEntity.ok(planning.ask(new Planning.Ask(request.question(), request.catalog(), request.catalogHash(), request.model(),
                choice(request.provider(), request.baseUrl(), key), request.zeroEgress())));
    }

    /** A provider failure, with a specific message and never the key. */
    @ExceptionHandler(ModelException.class)
    ResponseEntity<Map<String, String>> modelFailure(ModelException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_CONFIGURED -> HttpStatus.UNAUTHORIZED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.kind().name(), "message", e.userMessage(), "detail", e.getMessage()));
    }

    @ExceptionHandler(LanguageModels.ProviderRefused.class)
    ResponseEntity<Map<String, String>> refused(LanguageModels.ProviderRefused e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(Planning.CatalogNeeded.class)
    ResponseEntity<Map<String, String>> catalogNeeded(Planning.CatalogNeeded e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "CATALOG_NEEDED", "message", e.getMessage()));
    }
}
