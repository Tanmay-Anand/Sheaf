package com.sheaf.domain.ir;

import java.util.List;

/**
 * The root IR node. A Plan is the artifact produced by the planner, validated by the
 * type checker, evaluated in memory, and committed by the committer.
 *
 * <p>The plan is the artifact. Store it, diff it, replay it.
 */
public record Plan(
        EntityRef source,
        List<Step> steps,
        Sink sink,
        PlanMeta meta
) {}
