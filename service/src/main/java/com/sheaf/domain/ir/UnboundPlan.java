package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * What the model writes: table and column <i>names</i>, operations, and where the result should go
 * as an intent. It carries no locations, no row numbers, no IDs and no consent; the binder and the
 * user supply those.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UnboundPlan.UnboundQuery.class, name = "query"),
        @JsonSubTypes.Type(value = UnboundPlan.UnboundEdit.class,  name = "edit")
})
public sealed interface UnboundPlan permits UnboundPlan.UnboundQuery, UnboundPlan.UnboundEdit {

    /** Absent means none; the bound plan always carries the (possibly empty) list. */
    @Nullable List<ParamDecl> params();

    @JsonTypeName("query")
    record UnboundQuery(String source, List<Step> steps, SinkIntent sink, @Nullable List<ParamDecl> params) implements UnboundPlan {}

    @JsonTypeName("edit")
    record UnboundEdit(String target, List<EditOp> ops, @Nullable List<ParamDecl> params) implements UnboundPlan {}
}
