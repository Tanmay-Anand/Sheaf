package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * A <b>bound</b> plan: the canonical IR that is type-checked, hashed, stored, evaluated and replayed.
 *
 * <p>The model never writes this directly. It writes an {@link UnboundPlan} (names and sink intent);
 * the binder resolves it against the workbook catalog into a bound plan, adding {@link Bindings}
 * (stable IDs for every table and column the plan can touch) and a concrete {@link Sink}. What the
 * user decides (where an anchor goes, whether to convert dependents, parameter values) is not in
 * the plan at all: it travels in the commit request.
 *
 * <p>Steps, expressions, predicates and edit operations are shared with the unbound form.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Plan.QueryPlan.class, name = "query"),
        @JsonSubTypes.Type(value = Plan.EditPlan.class,  name = "edit")
})
public sealed interface Plan permits Plan.QueryPlan, Plan.EditPlan {

    List<ParamDecl> params();

    Bindings bindings();

    /** Reads {@code source}, runs {@code steps}, writes the result to {@code sink}. */
    @JsonTypeName("query")
    record QueryPlan(String source, List<Step> steps, Sink sink, List<ParamDecl> params, Bindings bindings) implements Plan {}

    /** Changes {@code target} in place through a closed set of column and row operations. */
    @JsonTypeName("edit")
    record EditPlan(String target, List<EditOp> ops, List<ParamDecl> params, Bindings bindings) implements Plan {}
}
