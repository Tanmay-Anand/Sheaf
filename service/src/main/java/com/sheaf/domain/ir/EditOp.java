package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sheaf.domain.common.Nullable;

/**
 * An in-place change to an entity. Edits address columns and row predicates, never cells.
 *
 * <p>"Merge A and B into C" is not an operation of its own: it is {@code addColumn(C, concat(A, B))}
 * followed by {@code dropColumn(A)} and {@code dropColumn(B)} when the sources should go.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EditOp.AddColumn.class,    name = "addColumn"),
        @JsonSubTypes.Type(value = EditOp.SetColumn.class,    name = "setColumn"),
        @JsonSubTypes.Type(value = EditOp.DropColumn.class,   name = "dropColumn"),
        @JsonSubTypes.Type(value = EditOp.RenameColumn.class, name = "renameColumn"),
        @JsonSubTypes.Type(value = EditOp.MoveColumn.class,   name = "moveColumn"),
        @JsonSubTypes.Type(value = EditOp.DropRows.class,     name = "dropRows")
})
public sealed interface EditOp
        permits EditOp.AddColumn, EditOp.SetColumn, EditOp.DropColumn,
                EditOp.RenameColumn, EditOp.MoveColumn, EditOp.DropRows {

    /** Adds a computed column at {@code position}. */
    @JsonTypeName("addColumn")
    record AddColumn(String as, Expr expr, Position position) implements EditOp {}

    /** Overwrites a column's values; with {@code where}, only in rows matching the predicate. */
    @JsonTypeName("setColumn")
    record SetColumn(String col, Expr expr, @Nullable Predicate where) implements EditOp {}

    @JsonTypeName("dropColumn")
    record DropColumn(String col) implements EditOp {}

    @JsonTypeName("renameColumn")
    record RenameColumn(String col, String to) implements EditOp {}

    @JsonTypeName("moveColumn")
    record MoveColumn(String col, Position position) implements EditOp {}

    /** Deletes every row matching {@code where}. The only operation that changes row count. */
    @JsonTypeName("dropRows")
    record DropRows(Predicate where) implements EditOp {}
}
