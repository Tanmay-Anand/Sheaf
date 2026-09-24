package com.sheaf.domain.catalog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogPolicyTest {

    private static CatalogColumn column(List<String> exemplars, List<String> validation) {
        return new CatalogColumn("c:K", "status", "K", 0, ScalarKind.CATEGORICAL, null, null,
                false, 0.0, 3, false, false, null, null, exemplars, validation, false, false, List.of(), List.of());
    }

    private static WorkbookCatalog catalog(int version, CatalogColumn... columns) {
        var entity = new CatalogEntity("t:Orders", "Orders", EntityKind.region, "ws-orders", "Orders", "Orders!A1:K76",
                1, 1, 2, 76, 75, List.of(), List.of(columns), "h", null);
        return new WorkbookCatalog(version, Instant.EPOCH, DateSystem.D1900, List.of(entity),
                List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void a_catalog_without_exemplars_is_acceptable() {
        assertThat(CatalogPolicy.violations(catalog(WorkbookCatalog.CURRENT_VERSION, column(null, null)))).isEmpty();
    }

    @Test
    void five_short_exemplars_are_acceptable() {
        var ok = column(List.of("shipped", "returned", "pending", "a", "b"), null);
        assertThat(CatalogPolicy.violations(catalog(WorkbookCatalog.CURRENT_VERSION, ok))).isEmpty();
    }

    @Test
    void long_exemplars_are_rejected_because_they_could_smuggle_row_content() {
        var tooLong = column(List.of("x".repeat(65)), null);
        assertThat(CatalogPolicy.violations(catalog(WorkbookCatalog.CURRENT_VERSION, tooLong)))
                .containsExactly("Orders.status has an exemplar longer than 64 characters.");
    }

    @Test
    void oversized_validation_lists_are_rejected() {
        var big = column(null, Collections.nCopies(201, "v"));
        assertThat(CatalogPolicy.violations(catalog(WorkbookCatalog.CURRENT_VERSION, big)))
                .containsExactly("Orders.status has a validation list of 201 values; the limit is 200.");
    }

    @Test
    void unknown_versions_are_rejected() {
        assertThat(CatalogPolicy.violations(catalog(1, column(null, null))))
                .containsExactly("Unsupported catalog version 1; expected 2.");
    }
}
