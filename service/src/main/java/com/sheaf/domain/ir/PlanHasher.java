package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sheaf.domain.common.Jcs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@code planHash} = "sha256:" + hex SHA-256 of the RFC 8785 canonical JSON of the envelope. Absent
 * optional fields are omitted (never written as null), so a plan's identity doesn't depend on how it
 * was serialised, which model wrote it, or when.
 */
public final class PlanHasher {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private PlanHasher() {}

    public static String hash(PlanEnvelope envelope) {
        String canonical = Jcs.canonicalize(MAPPER.valueToTree(envelope));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
