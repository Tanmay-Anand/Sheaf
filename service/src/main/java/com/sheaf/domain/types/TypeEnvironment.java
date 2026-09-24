package com.sheaf.domain.types;

import com.sheaf.domain.catalog.ColumnDependent;
import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.Predicate;
import com.sheaf.domain.ir.Step;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the binder and type checker may resolve a name against. Nothing outside this
 * environment can appear in a valid plan: this is where "the model can only reference what exists"
 * is enforced.
 *
 * <p>Built by the semantic model (M8) and, before that, from the workbook catalog
 * ({@link CatalogEnvironment}). Tests build it by hand.
 *
 * @param entities   Tables by display name; looked up case-insensitively.
 * @param templates  Imported templates by template id.
 * @param sheetNames Every sheet name in the workbook, for new-sheet collision checks.
 * @param untraceableReferences The workbook has references Sheaf can't follow (INDIRECT, OFFSET,
 *                              external links), so "nothing depends on this" can't be certain.
 */
public record TypeEnvironment(
        Map<String, EntitySchema> entities,
        List<JoinEdge> joins,
        Map<String, Metric> metrics,
        Map<String, TimeDimension> timeDimensions,
        Map<String, TemplateSchema> templates,
        Set<String> sheetNames,
        boolean untraceableReferences
) {
    public TypeEnvironment {
        entities = Map.copyOf(entities);
        joins = List.copyOf(joins);
        metrics = Map.copyOf(metrics);
        timeDimensions = Map.copyOf(timeDimensions);
        templates = Map.copyOf(templates);
        sheetNames = Set.copyOf(sheetNames);
    }

    /** Case-insensitive, like every name in Excel. */
    public Optional<EntitySchema> entity(String name) {
        EntitySchema exact = entities.get(name);
        if (exact != null) return Optional.of(exact);
        String k = TableType.key(name);
        return entities.values().stream().filter(e -> TableType.key(e.name()).equals(k)).findFirst();
    }

    /**
     * An entity and its columns, as the checker sees them.
     *
     * @param id      Stable catalog id.
     * @param sheetId Excel worksheet id.
     */
    public record EntitySchema(String id, String name, String sheetId, List<ColumnSchema> columns) {
        public TableType tableType() {
            return TableType.of(columns.stream()
                    .map(c -> new ColumnType(c.name(), c.type(), c.nullable(), c.cardinality(),
                            new ColumnType.Origin(name, c.name()), c.formula(), c.mayContainErrors(), c.numbersStoredAsText()))
                    .toList());
        }

        public Optional<ColumnSchema> column(String name) {
            String k = TableType.key(name);
            return columns.stream().filter(c -> TableType.key(c.name()).equals(k)).findFirst();
        }
    }

    /**
     * @param unique     No nulls and every value distinct: usable as the key of a lookup.
     * @param dependents Everything in the workbook that reads this column (from the catalog).
     */
    public record ColumnSchema(
            String id,
            String name,
            ScalarType type,
            boolean nullable,
            @Nullable Integer cardinality,
            boolean formula,
            boolean unique,
            boolean mayContainErrors,
            boolean numbersStoredAsText,
            List<ColumnDependent> dependents
    ) {
        public ColumnSchema {
            dependents = List.copyOf(dependents);
        }

        public static ColumnSchema of(String name, ScalarType type) {
            return new ColumnSchema(name, name, type, false, null, false, false, false, false, List.of());
        }
    }

    /** A join edge. Only approved edges can appear in a valid plan. */
    public record JoinEdge(String fromEntity, String fromColumn, String toEntity, String toColumn, boolean approved) {}

    /**
     * A named measure (ir-spec semantic model): the aggregate that periodCompare evaluates.
     *
     * @param filters Applied within every period evaluation (e.g. Revenue excludes returns).
     */
    public record Metric(String name, String entity, Step.AggFn fn, String of, List<Predicate> filters, ScalarType type) {}

    public record TimeDimension(String name, String entity, String column, Set<Step.Grain> grains, @Nullable String weekStart) {}

    /** The header of an imported template, by id. */
    public record TemplateSchema(String templateId, String name, int headerRow, int firstDataRow, List<TemplateColumn> columns) {}

    /**
     * @param expected       What the column's number format asks for; null (General) accepts anything.
     *                       {@code string} means explicitly text-formatted ("@").
     * @param validationList Allowed values from a list validation rule, or null.
     * @param formula        Filled down by the template itself; never written by a plan.
     */
    public record TemplateColumn(String name, @Nullable ScalarType expected, @Nullable List<String> validationList, boolean formula) {}
}
