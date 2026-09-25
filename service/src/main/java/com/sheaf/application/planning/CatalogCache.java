package com.sheaf.application.planning;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sheaf.domain.catalog.WorkbookCatalog;
import com.sheaf.domain.common.Jcs;
import com.sheaf.domain.common.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recently seen catalogs by content hash, so the pane sends a workbook's catalog once and then only
 * its hash. In memory, bounded, per service instance: a miss (restart, eviction, another instance)
 * just asks the pane for the catalog again.
 */
@Component
public class CatalogCache {

    static final int CAPACITY = 32;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private final Map<String, WorkbookCatalog> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, WorkbookCatalog> eldest) {
            return size() > CAPACITY;
        }
    };

    /** The catalog's identity: SHA-256 of its RFC 8785 canonical JSON. */
    public static String hash(WorkbookCatalog catalog) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Jcs.canonicalize(MAPPER.valueToTree(catalog)).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized String put(WorkbookCatalog catalog) {
        String h = hash(catalog);
        entries.put(h, catalog);
        return h;
    }

    public synchronized @Nullable WorkbookCatalog get(String hash) {
        return entries.get(hash);
    }
}
