package com.sheaf.domain.catalog;

import java.time.Instant;
import java.util.List;

/**
 * Everything Sheaf knows about the structure of one workbook: its entities (Excel Tables and
 * detected data regions), their columns, what depends on each column, and candidate joins.
 *
 * <p>Built client-side by the profiler, persisted inside the workbook, and sent to the service
 * for prompt assembly. It carries schema and statistics only, never rows. Exemplars (at most
 * five short values per column) are the single exception and can be switched off.
 *
 * @param dateSystem Whether date serials count from 1900 or 1904 (inferred: Excel's own flag is a
 *                   preview-only API), or unknown when the workbook has no date cells to infer from.
 */
public record WorkbookCatalog(
        int version,
        Instant generatedAt,
        DateSystem dateSystem,
        List<CatalogEntity> entities,
        List<JoinCandidate> joinCandidates,
        List<UntraceableReference> untraceable,
        List<CatalogCorrection> corrections,
        List<String> warnings
) {
    public static final int CURRENT_VERSION = 2;
}
