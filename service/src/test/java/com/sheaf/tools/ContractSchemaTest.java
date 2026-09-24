package com.sheaf.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.sheaf.domain.commit.CommitRequest;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlannerResponse;
import com.sheaf.domain.ir.UnboundPlan;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema a model is shown must not let it write anything that is the binder's or the user's to
 * decide: no ids, no locations, no consent, no hashes, no provenance.
 */
class ContractSchemaTest {

    private static final Set<String> NOT_THE_MODELS = Set.of(
            "bindings", "id", "sheetId", "anchor", "range", "address", "headerRow", "firstDataRow",
            "onDependents", "planHash", "irVersion", "meta", "modelId", "generatedAt", "excludeErrorCells", "regionHashes");

    private static Set<String> propertyNames(JsonNode schema) {
        Set<String> out = new HashSet<>();
        collect(schema, out);
        return out;
    }

    private static void collect(JsonNode n, Set<String> out) {
        if (n.isObject()) {
            JsonNode props = n.get("properties");
            if (props != null && props.isObject()) props.fieldNames().forEachRemaining(out::add);
            n.elements().forEachRemaining(c -> collect(c, out));
        } else if (n.isArray()) {
            n.elements().forEachRemaining(c -> collect(c, out));
        }
    }

    @Test
    void the_model_facing_schemas_carry_no_binding_consent_or_identity_fields() {
        for (Class<?> root : new Class<?>[]{UnboundPlan.class, PlannerResponse.class}) {
            var names = propertyNames(SchemaGeneratorMain.schemaFor(root));
            assertThat(names).as(root.getSimpleName()).contains("kind", "source", "steps", "sink", "params");
            assertThat(names).as(root.getSimpleName()).doesNotContainAnyElementsOf(NOT_THE_MODELS);
        }
    }

    @Test
    void the_bound_plan_and_the_commit_request_carry_what_the_model_may_not() {
        assertThat(propertyNames(SchemaGeneratorMain.schemaFor(PlanEnvelope.class)))
                .contains("irVersion", "bindings", "sheetId", "anchor", "headerRow")
                .doesNotContain("meta", "onDependents");
        assertThat(propertyNames(SchemaGeneratorMain.schemaFor(CommitRequest.class)))
                .contains("planHash", "onDependents", "anchor", "excludeErrorCells", "params", "regionHashes");
    }

    @Test
    void every_root_has_a_readable_title() {
        assertThat(SchemaGeneratorMain.schemaFor(UnboundPlan.class).get("title").asText()).isEqualTo("UnboundPlan");
        assertThat(SchemaGeneratorMain.schemaFor(PlannerResponse.class).get("title").asText()).isEqualTo("PlannerResponse");
    }
}
