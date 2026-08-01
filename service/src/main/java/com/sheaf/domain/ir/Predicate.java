package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * Closed predicate grammar for filter expressions and conditional aggregates.
 *
 * <p>Serialisation note: the "op" discriminator produces JSON like
 * {@code {"op":"eq","left":{"type":"col","col":"status"},"right":{"type":"lit","value":"returned"}}}.
 * A prettier wire format (matching the IR spec's {@code {"eq":["status","returned"]}})
 * will be added via custom serialisers in M3.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Predicate.EqPredicate.class,       name = "eq"),
        @JsonSubTypes.Type(value = Predicate.NePredicate.class,       name = "ne"),
        @JsonSubTypes.Type(value = Predicate.LtPredicate.class,       name = "lt"),
        @JsonSubTypes.Type(value = Predicate.LtePredicate.class,      name = "lte"),
        @JsonSubTypes.Type(value = Predicate.GtPredicate.class,       name = "gt"),
        @JsonSubTypes.Type(value = Predicate.GtePredicate.class,      name = "gte"),
        @JsonSubTypes.Type(value = Predicate.InPredicate.class,       name = "in"),
        @JsonSubTypes.Type(value = Predicate.NotInPredicate.class,    name = "notIn"),
        @JsonSubTypes.Type(value = Predicate.IsNullPredicate.class,   name = "isNull"),
        @JsonSubTypes.Type(value = Predicate.IsNotNullPredicate.class, name = "isNotNull"),
        @JsonSubTypes.Type(value = Predicate.AndPredicate.class,      name = "and"),
        @JsonSubTypes.Type(value = Predicate.OrPredicate.class,       name = "or"),
        @JsonSubTypes.Type(value = Predicate.NotPredicate.class,      name = "not")
})
public sealed interface Predicate
        permits Predicate.EqPredicate, Predicate.NePredicate,
                Predicate.LtPredicate, Predicate.LtePredicate,
                Predicate.GtPredicate, Predicate.GtePredicate,
                Predicate.InPredicate, Predicate.NotInPredicate,
                Predicate.IsNullPredicate, Predicate.IsNotNullPredicate,
                Predicate.AndPredicate, Predicate.OrPredicate, Predicate.NotPredicate {

    // ── Binary comparison ──────────────────────────────────────────────────────

    @JsonTypeName("eq")  record EqPredicate(ValueRef left, ValueRef right)  implements Predicate {}
    @JsonTypeName("ne")  record NePredicate(ValueRef left, ValueRef right)  implements Predicate {}
    @JsonTypeName("lt")  record LtPredicate(ValueRef left, ValueRef right)  implements Predicate {}
    @JsonTypeName("lte") record LtePredicate(ValueRef left, ValueRef right) implements Predicate {}
    @JsonTypeName("gt")  record GtPredicate(ValueRef left, ValueRef right)  implements Predicate {}
    @JsonTypeName("gte") record GtePredicate(ValueRef left, ValueRef right) implements Predicate {}

    // ── Set membership ─────────────────────────────────────────────────────────

    @JsonTypeName("in")    record InPredicate(String col, List<Object> values)    implements Predicate {}
    @JsonTypeName("notIn") record NotInPredicate(String col, List<Object> values) implements Predicate {}

    // ── Null checks ────────────────────────────────────────────────────────────

    @JsonTypeName("isNull")    record IsNullPredicate(String col)    implements Predicate {}
    @JsonTypeName("isNotNull") record IsNotNullPredicate(String col) implements Predicate {}

    // ── Logical combinators ────────────────────────────────────────────────────

    @JsonTypeName("and") record AndPredicate(List<Predicate> clauses) implements Predicate {}
    @JsonTypeName("or")  record OrPredicate(List<Predicate> clauses)  implements Predicate {}
    @JsonTypeName("not") record NotPredicate(Predicate clause)        implements Predicate {}

    // ── Value reference (column or literal) ───────────────────────────────────

    /**
     * A value used in a binary predicate: either a column reference or a literal.
     * The bare-string shorthand for column references (e.g. {@code "status"} meaning
     * {@code {"type":"col","col":"status"}}) requires a custom deserialiser added in M3.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ValueRef.ColRef.class, name = "col"),
            @JsonSubTypes.Type(value = ValueRef.Lit.class,    name = "lit")
    })
    sealed interface ValueRef permits ValueRef.ColRef, ValueRef.Lit {
        record ColRef(String col)   implements ValueRef {}
        record Lit(Object value)    implements ValueRef {}
    }
}
