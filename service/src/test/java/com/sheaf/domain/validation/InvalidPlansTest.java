package com.sheaf.domain.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheaf.domain.types.TypeEnvironment;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hand-crafted invalid plans (src/test/resources/invalid-plans.json): each must fail, and the
 * first error must be exactly the recorded code, because the code is the repair signal.
 */
class InvalidPlansTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeEnvironment ENV = CorpusEnvironment.build();

    private static List<JsonNode> cases() throws IOException {
        try (InputStream in = InvalidPlansTest.class.getResourceAsStream("/invalid-plans.json")) {
            return StreamSupport.stream(JSON.readTree(in).spliterator(), false).toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> each_invalid_plan_fails_with_its_code() throws IOException {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.get("expect").asText() + " · " + c.get("name").asText(), () -> {
            JsonNode plan = c.get("plan");
            String json = plan.isTextual() ? plan.asText() : JSON.writeValueAsString(plan);
            CheckResult r = TypeChecker.parseBindAndCheck(json, ENV).check();
            assertThat(r.valid()).isFalse();
            assertThat(r.errors().get(0).code().name()).isEqualTo(c.get("expect").asText());
            assertThat(r.errors().get(0).hint()).isNotBlank();
        }));
    }

    @Test
    void the_suite_covers_enough_codes_and_enough_edit_and_template_plans() throws IOException {
        var all = cases();
        long editOrTemplate = all.stream().filter(c -> {
            JsonNode p = c.get("plan");
            return p.isObject() && ("edit".equals(p.path("kind").asText()) || "template".equals(p.path("sink").path("mode").asText()));
        }).count();
        assertThat(all).hasSizeGreaterThanOrEqualTo(60);
        assertThat(editOrTemplate).isGreaterThanOrEqualTo(15);

        // Every error code in the taxonomy is exercised by at least one invalid plan or a targeted test.
        Set<String> covered = new HashSet<>();
        all.forEach(c -> covered.add(c.get("expect").asText()));
        covered.addAll(TypeCheckerTest.CODES_COVERED_THERE);
        var missing = Arrays.stream(DiagnosticCode.values()).filter(DiagnosticCode::isError).map(Enum::name)
                .filter(n -> !covered.contains(n)).toList();
        assertThat(missing).isEmpty();
    }
}
