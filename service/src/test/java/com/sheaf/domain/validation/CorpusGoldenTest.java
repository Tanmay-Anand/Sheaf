package com.sheaf.domain.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlanHasher;
import com.sheaf.domain.types.TypeEnvironment;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression suite that matters: every corpus plan in corpus/plans/ (an unbound plan, as a model
 * writes it) binds and type-checks as recorded, with exactly the recorded output table type,
 * warnings, edit impact, bound sink and bound tables. Regenerate with corpus/tools/generate_plans.py.
 */
class CorpusGoldenTest {

    private static final Path PLANS = Path.of("..", "corpus", "plans");
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private static final TypeEnvironment ENV = CorpusEnvironment.build();

    private static List<String> strings(JsonNode array) {
        if (array == null) return List.of();
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static List<Path> goldenFiles() throws IOException {
        try (var files = Files.list(PLANS)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> every_corpus_plan_binds_and_checks_as_recorded() throws IOException {
        return goldenFiles().stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            JsonNode golden = JSON.readTree(file.toFile());
            JsonNode expect = golden.get("expect");
            var checked = TypeChecker.parseBindAndCheck(JSON.writeValueAsString(golden.get("plan")), ENV);
            CheckResult r = checked.check();

            List<String> errors = r.errors().stream().map(d -> d.code().name()).toList();
            if (!expect.get("valid").asBoolean()) {
                assertThat(errors).as(golden.get("question").asText()).isEqualTo(strings(expect.get("errors")));
                return;
            }
            assertThat(r.errors()).as(golden.get("question").asText()).isEmpty();
            assertThat(checked.valid()).isTrue();
            assertThat(checked.planHash()).startsWith("sha256:");
            assertThat(r.output()).isNotNull();
            assertThat(r.output().render()).isEqualTo(strings(expect.get("output")));
            assertThat(r.warnings().stream().map(d -> d.code().name()).toList())
                    .isEqualTo(strings(expect.get("warnings")));

            Plan plan = checked.envelope().plan();
            if (expect.has("sink")) {
                var sink = ((Plan.QueryPlan) plan).sink();
                assertThat((JsonNode) JSON.valueToTree(sink)).isEqualTo(withoutNulls(expect.get("sink")));
            }
            if (expect.has("entities")) {
                var bindings = switch (plan) {
                    case Plan.QueryPlan q -> q.bindings();
                    case Plan.EditPlan e -> e.bindings();
                };
                assertThat(bindings.entities().stream().map(b -> b.id()).toList()).isEqualTo(strings(expect.get("entities")));
                bindings.entities().forEach(b -> assertThat(b.columns()).isNotEmpty());
            }

            JsonNode impact = expect.get("impact");
            if (impact != null) {
                ImpactReport i = r.impact();
                assertThat(i).isNotNull();
                assertThat(i.added()).isEqualTo(strings(impact.get("added")));
                assertThat(i.removed()).isEqualTo(strings(impact.get("removed")));
                assertThat(i.overwritten()).isEqualTo(strings(impact.get("overwritten")));
                assertThat(i.moved()).isEqualTo(strings(impact.get("moved")));
                assertThat(i.renamed().stream().map(x -> x.from() + " -> " + x.to()).toList())
                        .isEqualTo(strings(impact.get("renamed")));
                assertThat(i.dependentsNeedingConsent().stream()
                        .map(x -> x.column() + " <- " + x.dependent().location()
                                + " (" + x.dependent().refClass() + ", " + x.cause() + ")").toList())
                        .isEqualTo(strings(impact.get("dependentsNeedingConsent")));
                assertThat(i.removesRows()).isEqualTo(impact.has("removesRows") && impact.get("removesRows").asBoolean());
            }
        }));
    }

    @Test
    void every_golden_plan_round_trips_through_the_unbound_wire_format_unchanged() throws IOException {
        List<String> mismatched = new ArrayList<>();
        for (Path file : goldenFiles()) {
            JsonNode plan = JSON.readTree(file.toFile()).get("plan");
            var parsed = PlanParser.parseUnbound(JSON.writeValueAsString(plan));
            assertThat(parsed.diagnostics()).as(file.toString()).isEmpty();
            JsonNode back = JSON.valueToTree(parsed.value());
            if (!withoutNulls(back).equals(withoutNulls(plan))) mismatched.add(file.getFileName().toString());
        }
        assertThat(mismatched).isEmpty();
    }

    @Test
    void every_bound_plan_round_trips_and_keeps_its_hash() throws IOException {
        for (Path file : goldenFiles()) {
            JsonNode plan = JSON.readTree(file.toFile()).get("plan");
            var checked = TypeChecker.parseBindAndCheck(JSON.writeValueAsString(plan), ENV);
            var json = JSON.writeValueAsString(checked.envelope().plan());
            var replayed = PlanParser.parsePlan(json);
            assertThat(replayed.diagnostics()).as(file.toString()).isEmpty();
            assertThat(PlanHasher.hash(PlanEnvelope.of(replayed.value()))).as(file.toString()).isEqualTo(checked.planHash());
        }
    }

    /** An absent optional field and an explicit null mean the same thing on the wire. */
    private static JsonNode withoutNulls(JsonNode n) {
        if (n.isObject()) {
            var out = JSON.createObjectNode();
            n.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) out.set(e.getKey(), withoutNulls(e.getValue()));
            });
            return out;
        }
        if (n.isArray()) {
            var out = JSON.createArrayNode();
            n.forEach(x -> out.add(withoutNulls(x)));
            return out;
        }
        return n;
    }
}
