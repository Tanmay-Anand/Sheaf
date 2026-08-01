package com.sheaf.domain.ir;

import java.time.Instant;

/** Provenance attached to every plan. Used for audit, replay, and accuracy tracking. */
public record PlanMeta(
        String planHash,
        String modelId,
        String promptVersion,
        Instant generatedAt
) {}
