package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * What the model answers with: a plan, a clarifying question, or a legible refusal. Ambiguity
 * ("what's selling well?") and missing data ("vs target") get their own channel instead of a
 * guessed plan. Annotations are never part of the plan's hash.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "response")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PlannerResponse.PlanResponse.class,    name = "plan"),
        @JsonSubTypes.Type(value = PlannerResponse.ClarifyResponse.class, name = "clarify"),
        @JsonSubTypes.Type(value = PlannerResponse.RefuseResponse.class,  name = "refuse")
})
public sealed interface PlannerResponse
        permits PlannerResponse.PlanResponse, PlannerResponse.ClarifyResponse, PlannerResponse.RefuseResponse {

    @JsonTypeName("plan")
    record PlanResponse(UnboundPlan plan, Annotations annotations) implements PlannerResponse {}

    /** @param options Concrete readings the user can pick from, each answerable as a plan. */
    @JsonTypeName("clarify")
    record ClarifyResponse(String question, List<String> options) implements PlannerResponse {}

    /**
     * @param understood       What the model took the request to mean.
     * @param closestSupported What Sheaf can do instead, in the user's terms.
     */
    @JsonTypeName("refuse")
    record RefuseResponse(String understood, List<String> closestSupported) implements PlannerResponse {}

    /**
     * Non-semantic notes shown in the preview, never hashed.
     *
     * @param assumptions Choices the model made that the user may want to know about.
     * @param columns     Per-output-column notes, e.g. for a template mapping review.
     */
    record Annotations(List<String> assumptions, List<ColumnNote> columns) {}

    /** @param confidence 0–1; below the review threshold the column is marked "needs review". */
    record ColumnNote(String column, Double confidence, @Nullable String reason) {}
}
