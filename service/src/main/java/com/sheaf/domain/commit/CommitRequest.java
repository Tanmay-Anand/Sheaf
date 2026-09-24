package com.sheaf.domain.commit;

import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * What the user decides, sent by the pane when they press Commit. None of it is in the plan: the
 * model proposes, the user disposes.
 *
 * @param planHash          The hash of the plan the user previewed; the commit is for that plan only.
 * @param anchor            Where an anchor sink writes, chosen by the user; null for other sinks.
 * @param onDependents      What to do with formulas and other readers of removed columns or rows.
 * @param excludeErrorCells The user accepted that cells showing errors are left out of aggregations.
 * @param params            Values for the plan's declared parameters; recorded in the run log.
 * @param regionHashes      Content hashes of the regions evaluated for the preview; the committer
 *                          refuses when the workbook changed since.
 */
public record CommitRequest(
        String planHash,
        @Nullable AnchorChoice anchor,
        OnDependents onDependents,
        Boolean excludeErrorCells,
        List<ParamValue> params,
        List<RegionHash> regionHashes
) {
    /** @param address A single cell in A1 notation: the top-left of the output. */
    public record AnchorChoice(String sheetId, String address) {}

    /** @param value A JSON scalar matching the parameter's declared value type. */
    public record ParamValue(String name, @Nullable Object value) {}

    public record RegionHash(String entityId, String contentHash) {}

    /**
     * {@code block}: refuse while anything still reads what the plan removes.
     * {@code convertToValues}: freeze those readers to their current values first.
     */
    public enum OnDependents { block, convertToValues }
}
