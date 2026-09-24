package com.sheaf.adapters.web;

import com.sheaf.domain.catalog.CatalogPolicy;
import com.sheaf.domain.catalog.WorkbookCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
class CatalogController {

    /**
     * M2: accepts a workbook catalog, enforces the "schema out, never rows" policy, and returns a
     * summary. M5 uses the accepted catalog for prompt assembly; nothing is stored yet.
     */
    @PostMapping(value = "/catalog",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CatalogSummary> accept(@RequestBody WorkbookCatalog catalog) {
        List<String> violations = CatalogPolicy.violations(catalog);
        if (!violations.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new CatalogSummary(false, 0, 0, 0, 0, violations));
        }
        int columns = catalog.entities().stream().mapToInt(e -> e.columns().size()).sum();
        int dependents = catalog.entities().stream()
                .flatMap(e -> e.columns().stream())
                .mapToInt(c -> c.dependents() == null ? 0 : c.dependents().size())
                .sum();
        return ResponseEntity.ok(new CatalogSummary(
                true,
                catalog.entities().size(),
                columns,
                dependents,
                catalog.joinCandidates() == null ? 0 : catalog.joinCandidates().size(),
                List.of()));
    }

    record CatalogSummary(
            boolean accepted,
            int entities,
            int columns,
            int dependents,
            int joinCandidates,
            List<String> violations
    ) {}
}
