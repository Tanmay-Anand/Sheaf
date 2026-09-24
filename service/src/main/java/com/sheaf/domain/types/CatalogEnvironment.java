package com.sheaf.domain.types;

import com.sheaf.domain.catalog.CatalogColumn;
import com.sheaf.domain.catalog.CatalogEntity;
import com.sheaf.domain.catalog.WorkbookCatalog;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link TypeEnvironment} straight from the workbook catalog, for plans over physical
 * tables before any semantic model exists (edit plans in M6; queries until M8). It knows the
 * tables, column ids and types, cardinalities, keys, formula columns, error flags, dependents and
 * approved joins. It has no metrics, time dimensions or category orders, so periodCompare and
 * ordinal sorts are refused until the semantic model supplies them.
 */
public final class CatalogEnvironment {

    /** Categories with at most this many values are small enough for exemplars to list them all. */
    private static final int COMPLETE_DOMAIN = 5;

    private CatalogEnvironment() {}

    public static TypeEnvironment from(WorkbookCatalog catalog) {
        Map<String, TypeEnvironment.EntitySchema> entities = new LinkedHashMap<>();
        Set<String> sheets = new LinkedHashSet<>();
        for (CatalogEntity e : catalog.entities()) {
            sheets.add(e.sheet());
            entities.put(e.name(), new TypeEnvironment.EntitySchema(e.id(), e.name(), e.sheetId(),
                    e.columns().stream().map(CatalogEnvironment::column).toList()));
        }
        var joins = catalog.joinCandidates().stream()
                .map(j -> new TypeEnvironment.JoinEdge(j.fromEntity(), j.fromColumn(), j.toEntity(), j.toColumn(), j.approved()))
                .toList();
        return new TypeEnvironment(entities, joins, Map.of(), Map.of(), Map.of(), sheets, !catalog.untraceable().isEmpty());
    }

    private static TypeEnvironment.ColumnSchema column(CatalogColumn c) {
        return new TypeEnvironment.ColumnSchema(c.id(), c.name(), type(c), c.nullable(), c.distinctCount(), c.formula(),
                c.keyCandidate(), c.mayContainErrors(), c.numbersStoredAsText(), c.dependents());
    }

    static ScalarType type(CatalogColumn c) {
        return switch (c.kind()) {
            case NUMBER, EMPTY -> ScalarType.NUMBER;
            case CURRENCY -> new ScalarType.CurrencyType(c.unit());
            case PERCENT -> ScalarType.PERCENT;
            case DATE -> ScalarType.DATE;
            case DATETIME -> ScalarType.DATETIME;
            case STRING -> ScalarType.STRING;
            case BOOLEAN -> ScalarType.BOOLEAN;
            case CATEGORICAL -> {
                // Exemplars list the whole domain only when it is small enough to fit in them.
                List<String> members = c.exemplars() != null && c.distinctCount() <= COMPLETE_DOMAIN
                        && c.exemplars().size() == c.distinctCount() ? List.copyOf(c.exemplars()) : null;
                yield new ScalarType.CategoricalType(c.name(), members, ScalarType.Ordering.none);
            }
        };
    }
}
