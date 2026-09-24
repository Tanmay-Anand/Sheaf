package com.sheaf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/check: parse, bind and check a plan against the pane's catalog. */
@SpringBootTest
@AutoConfigureMockMvc
class CheckControllerTest {

    @Autowired MockMvc mockMvc;

    private static final String CATALOG = """
            {
              "version": 2, "generatedAt": "2026-09-25T10:00:00Z", "dateSystem": "1900",
              "entities": [{
                "id": "r:ws-1:A1", "name": "Sales", "kind": "region", "sheetId": "ws-1", "sheet": "Sales", "address": "Sales!A1:B4",
                "headerRow": 1, "headerRows": 1, "firstDataRow": 2, "lastDataRow": 4, "dataRowCount": 3, "skippedRows": [],
                "contentHash": "abc",
                "columns": [
                  { "id": "h:1", "name": "Region", "letter": "A", "index": 0, "kind": "categorical", "nullable": false, "nullRate": 0.0,
                    "distinctCount": 2, "keyCandidate": false, "formula": false, "mayContainErrors": false, "numbersStoredAsText": false,
                    "dependents": [], "warnings": [] },
                  { "id": "h:2", "name": "Amount", "letter": "B", "index": 1, "kind": "currency", "unit": "INR", "nullable": false,
                    "nullRate": 0.0, "distinctCount": 3, "keyCandidate": false, "formula": false, "mayContainErrors": false,
                    "numbersStoredAsText": false, "dependents": [], "warnings": [] }
                ]
              }],
              "joinCandidates": [], "untraceable": [], "corrections": [], "warnings": []
            }
            """;

    private String request(String plan) {
        return "{\"plan\": " + plan + ", \"catalog\": " + CATALOG + "}";
    }

    @Test
    void a_valid_plan_comes_back_bound_hashed_and_typed() throws Exception {
        mockMvc.perform(post("/api/check").contentType(MediaType.APPLICATION_JSON).content(request("""
                        {"kind":"query","source":"sales","steps":[
                          {"op":"aggregate","groupBy":["region"],"measures":[{"fn":"sum","of":"amount","as":"total"}]}],
                         "sink":{"mode":"newSheet","name":"Sales"},"params":[]}
                        """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.planHash").value(startsWith("sha256:")))
                .andExpect(jsonPath("$.envelope.irVersion").value("1.2"))
                .andExpect(jsonPath("$.envelope.plan.source").value("Sales"))
                .andExpect(jsonPath("$.envelope.plan.sink.name").value("Sales 2"))
                .andExpect(jsonPath("$.envelope.plan.bindings.entities[0].id").value("r:ws-1:A1"))
                .andExpect(jsonPath("$.envelope.plan.bindings.entities[0].sheetId").value("ws-1"))
                .andExpect(jsonPath("$.output[0].name").value("Region"))
                .andExpect(jsonPath("$.output[1].type").value("currency:INR"));
    }

    @Test
    void an_invalid_plan_is_a_normal_answer_with_diagnostics() throws Exception {
        mockMvc.perform(post("/api/check").contentType(MediaType.APPLICATION_JSON).content(request("""
                        {"kind":"query","source":"Sales","steps":[{"op":"sort","by":[{"col":"amont","dir":"desc"}]}],
                         "sink":{"mode":"newSheet"},"params":[]}
                        """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("E_UNKNOWN_COLUMN"))
                .andExpect(jsonPath("$.diagnostics[0].pointer").value("/steps/0/by/0/col"))
                .andExpect(jsonPath("$.diagnostics[0].context.candidates[0]").value("Amount"));
    }

    @Test
    void pasted_text_is_accepted_and_parse_errors_are_diagnostics() throws Exception {
        mockMvc.perform(post("/api/check").contentType(MediaType.APPLICATION_JSON).content(request("\"not json\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("E_PARSE_INVALID_JSON"));
    }
}
