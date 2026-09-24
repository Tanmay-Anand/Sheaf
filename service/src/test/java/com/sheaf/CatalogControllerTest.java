package com.sheaf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/catalog: accepts schema-only catalogs, rejects anything that could carry rows. */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogControllerTest {

    @Autowired MockMvc mockMvc;

    private static String catalogWithExemplars(String exemplarsJson) {
        return """
                {
                  "version": 2,
                  "generatedAt": "2026-09-24T10:00:00Z",
                  "dateSystem": "1900",
                  "entities": [{
                    "id": "t:Orders", "name": "Orders", "kind": "region", "sheetId": "{8C1A}", "sheet": "Orders", "address": "Orders!A1:C4",
                    "headerRow": 1, "headerRows": 1, "firstDataRow": 2, "lastDataRow": 4,
                    "dataRowCount": 3, "skippedRows": [], "contentHash": "abc123",
                    "columns": [{
                      "id": "c:1", "name": "region", "letter": "A", "index": 0, "kind": "categorical",
                      "nullable": false, "nullRate": 0.0, "distinctCount": 3, "keyCandidate": false,
                      "formula": false, "exemplars": %s,
                      "mayContainErrors": false, "numbersStoredAsText": false,
                      "dependents": [{"kind": "formula", "location": "Summary!B2", "detail": "=COUNTA(Orders!A:A)",
                                      "refClass": "wholeColumn", "fixedRows": false}],
                      "warnings": []
                    }]
                  }],
                  "joinCandidates": [],
                  "untraceable": [],
                  "corrections": [],
                  "warnings": []
                }
                """.formatted(exemplarsJson);
    }

    @Test
    void accepts_a_schema_only_catalog_and_summarises_it() throws Exception {
        mockMvc.perform(post("/api/catalog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(catalogWithExemplars("[\"North\", \"South\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.entities").value(1))
                .andExpect(jsonPath("$.columns").value(1))
                .andExpect(jsonPath("$.dependents").value(1));
    }

    @Test
    void rejects_a_catalog_carrying_more_than_five_exemplars_per_column() throws Exception {
        mockMvc.perform(post("/api/catalog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(catalogWithExemplars("[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.violations[0]").value(
                        "Orders.region carries 6 exemplars; at most 5 are allowed."));
    }
}
