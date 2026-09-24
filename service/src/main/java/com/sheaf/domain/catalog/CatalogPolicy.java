package com.sheaf.domain.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces "schema out, never rows" on catalogs arriving at the service.
 *
 * <p>The client is trusted to profile, not to decide what may leave the machine: whatever it
 * sends, a catalog that could carry bulk cell values is rejected here.
 */
public final class CatalogPolicy {

    public static final int MAX_EXEMPLARS_PER_COLUMN = 5;
    public static final int MAX_EXEMPLAR_LENGTH = 64;
    public static final int MAX_VALIDATION_LIST = 200;
    public static final int MAX_ENTITIES = 500;
    public static final int MAX_COLUMNS_PER_ENTITY = 1_000;

    private CatalogPolicy() {}

    /** Every violation found; an empty list means the catalog is acceptable. */
    public static List<String> violations(WorkbookCatalog catalog) {
        var out = new ArrayList<String>();
        if (catalog.version() != WorkbookCatalog.CURRENT_VERSION) {
            out.add("Unsupported catalog version " + catalog.version()
                    + "; expected " + WorkbookCatalog.CURRENT_VERSION + ".");
        }
        if (catalog.entities() == null) {
            out.add("Catalog has no entities list.");
            return out;
        }
        if (catalog.entities().size() > MAX_ENTITIES) {
            out.add("Catalog has " + catalog.entities().size() + " entities; the limit is " + MAX_ENTITIES + ".");
        }
        for (CatalogEntity e : catalog.entities()) {
            if (e.columns() == null) {
                out.add(e.name() + " has no columns list.");
                continue;
            }
            if (e.columns().size() > MAX_COLUMNS_PER_ENTITY) {
                out.add(e.name() + " has " + e.columns().size() + " columns; the limit is " + MAX_COLUMNS_PER_ENTITY + ".");
            }
            for (CatalogColumn c : e.columns()) {
                String where = e.name() + "." + c.name();
                if (c.exemplars() != null) {
                    if (c.exemplars().size() > MAX_EXEMPLARS_PER_COLUMN) {
                        out.add(where + " carries " + c.exemplars().size() + " exemplars; at most "
                                + MAX_EXEMPLARS_PER_COLUMN + " are allowed.");
                    }
                    if (c.exemplars().stream().anyMatch(x -> x != null && x.length() > MAX_EXEMPLAR_LENGTH)) {
                        out.add(where + " has an exemplar longer than " + MAX_EXEMPLAR_LENGTH + " characters.");
                    }
                }
                if (c.validationList() != null && c.validationList().size() > MAX_VALIDATION_LIST) {
                    out.add(where + " has a validation list of " + c.validationList().size()
                            + " values; the limit is " + MAX_VALIDATION_LIST + ".");
                }
            }
        }
        return out;
    }
}
