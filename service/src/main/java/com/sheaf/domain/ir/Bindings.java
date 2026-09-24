package com.sheaf.domain.ir;

import java.util.List;

/**
 * The binder's record of what every name in a bound plan refers to. The evaluator reads columns by
 * {@code id}, so a plan survives a header being renamed or a table being moved in Excel; the names
 * are what the plan's steps use internally.
 */
public record Bindings(List<EntityBinding> entities) {

    /**
     * @param id      Stable catalog id of the table or region.
     * @param sheetId Excel's worksheet id (survives sheet renames).
     */
    public record EntityBinding(String name, String id, String sheetId, List<ColumnBinding> columns) {}

    public record ColumnBinding(String name, String id) {}
}
