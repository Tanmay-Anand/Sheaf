package com.sheaf.domain.ir;

/**
 * The hashed unit: the IR version and the bound plan, nothing else. Provenance ({@link PlanMeta})
 * sits outside, so the same logical plan hashes the same whichever model wrote it and whenever.
 */
public record PlanEnvelope(String irVersion, Plan plan) {

    public static final String IR_VERSION = "1.2";

    public static PlanEnvelope of(Plan plan) {
        return new PlanEnvelope(IR_VERSION, plan);
    }
}
