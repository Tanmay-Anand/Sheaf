package com.sheaf.domain.validation;

import com.sheaf.domain.ir.PlannerResponse;
import com.sheaf.domain.ir.UnboundPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A model's whole answer: plan, clarify or refuse; and parse errors that point at the exact field. */
class PlanParserTest {

    @Test
    void a_plan_response_carries_an_unbound_plan_and_annotations_outside_it() {
        var r = PlanParser.parseResponse("""
                {"response":"plan",
                 "plan":{"kind":"query","source":"Orders","steps":[],"sink":{"mode":"newSheet","name":"Revenue"},"params":[]},
                 "annotations":{"assumptions":["'returned' orders are excluded"],
                                "columns":[{"column":"revenue","confidence":0.9,"reason":null}]}}
                """);
        assertThat(r.diagnostics()).isEmpty();
        var plan = (PlannerResponse.PlanResponse) r.value();
        assertThat(plan.plan()).isInstanceOf(UnboundPlan.UnboundQuery.class);
        assertThat(plan.annotations().columns().get(0).confidence()).isEqualTo(0.9);
    }

    @Test
    void clarify_and_refuse_are_first_class_answers() {
        var clarify = PlanParser.parseResponse("""
                {"response":"clarify","question":"Selling well by revenue or by units?","options":["by revenue","by units"]}
                """);
        assertThat(clarify.value()).isInstanceOf(PlannerResponse.ClarifyResponse.class);

        var refuse = PlanParser.parseResponse("""
                {"response":"refuse","understood":"revenue vs target","closestSupported":["revenue by month"]}
                """);
        assertThat(((PlannerResponse.RefuseResponse) refuse.value()).closestSupported()).containsExactly("revenue by month");
    }

    @Test
    void errors_point_at_the_field_as_a_json_pointer() {
        var missing = PlanParser.parseUnbound("""
                {"kind":"query","source":"Orders","steps":[{"op":"filter","predicate":{"op":"eq","left":{"type":"col","col":"a"}}}],
                 "sink":{"mode":"newSheet"},"params":[]}
                """);
        assertThat(missing.value()).isNull();
        assertThat(missing.diagnostics()).extracting(Diagnostic::pointer).containsExactly("/steps/0/predicate/right");

        var unknown = PlanParser.parseUnbound("""
                {"kind":"query","source":"Orders","steps":[{"op":"sort","by":[{"col":"a","dirn":"asc"}]}],
                 "sink":{"mode":"anchor"},"params":[]}
                """);
        var d = unknown.diagnostics().get(0);
        assertThat(d.code()).isEqualTo(DiagnosticCode.E_PARSE_UNKNOWN_FIELD);
        assertThat(d.pointer()).isEqualTo("/steps/0/by/0/dirn");
        assertThat((List<?>) d.context().get("candidates")).first().isEqualTo("dir");
    }
}
