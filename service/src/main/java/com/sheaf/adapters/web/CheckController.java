package com.sheaf.adapters.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheaf.application.PlanChecking;
import com.sheaf.domain.catalog.WorkbookCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
class CheckController {

    private final ObjectMapper json;
    private final PlanChecking checking;

    CheckController(ObjectMapper json, PlanChecking checking) {
        this.json = json;
        this.checking = checking;
    }

    /**
     * Parses, binds and type-checks an unbound plan against the pane's catalog. The pane evaluates
     * and previews the bound plan it gets back; the planner (M5) will produce the plan itself.
     * Invalid plans are a normal answer (200 with diagnostics); only a catalog that breaks the
     * "schema out, never rows" policy is refused.
     */
    @PostMapping(value = "/check",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> check(@RequestBody CheckRequest request) throws Exception {
        List<String> violations = checking.refusals(request.catalog());
        if (!violations.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("violations", violations));
        }
        String plan = request.plan().isTextual() ? request.plan().asText() : json.writeValueAsString(request.plan());
        return ResponseEntity.ok(checking.check(plan, request.catalog()));
    }

    /** @param plan The unbound plan, as a JSON object (or a string holding one, e.g. pasted text). */
    record CheckRequest(JsonNode plan, WorkbookCatalog catalog) {}
}
