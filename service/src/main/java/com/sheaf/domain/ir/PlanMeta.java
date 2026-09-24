package com.sheaf.domain.ir;

import java.time.Instant;

/**
 * Provenance of a plan: who wrote it and when. Kept outside the hashed {@link PlanEnvelope}, so it
 * never changes a plan's identity. Used for audit, replay and accuracy tracking.
 */
public record PlanMeta(
        String modelId,
        String promptVersion,
        Instant generatedAt
) {}
