package com.sheaf.domain.catalog;

/**
 * A join proposed from value overlap. Never usable in a plan until approved.
 *
 * @param overlap     Share of distinct {@code from} values found in {@code to}.
 * @param confidence  0 to 1: overlap, discounted when there is little evidence.
 * @param cardinality {@code many-to-one} or {@code one-to-one}.
 */
public record JoinCandidate(
        String fromEntity,
        String fromColumn,
        String toEntity,
        String toColumn,
        String cardinality,
        double overlap,
        double confidence,
        boolean approved
) {}
