package com.sheaf.application;

import com.sheaf.domain.catalog.CatalogPolicy;
import com.sheaf.domain.catalog.WorkbookCatalog;
import com.sheaf.domain.types.CatalogEnvironment;
import com.sheaf.domain.validation.TypeChecker;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Checks a plan against the workbook the pane described: parse, bind to the catalog's ids, type-check,
 * hash. The one entry point adapters use, so they never reach into the type checker directly.
 */
@Service
public class PlanChecking {

    /** Why the catalog can't be used (it could carry row data); empty when it can. */
    public List<String> refusals(WorkbookCatalog catalog) {
        return CatalogPolicy.violations(catalog);
    }

    /** @param unboundJson What the model (or the user, in the dev pane) wrote. */
    public CheckReport check(String unboundJson, WorkbookCatalog catalog) {
        return CheckReport.of(TypeChecker.parseBindAndCheck(unboundJson, CatalogEnvironment.from(catalog)));
    }
}
