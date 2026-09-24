package com.sheaf.domain.validation;

import com.sheaf.domain.ir.EditOp;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlanHasher;
import com.sheaf.domain.ir.Sink;
import com.sheaf.domain.ir.SinkIntent;
import com.sheaf.domain.ir.Step;
import com.sheaf.domain.ir.UnboundPlan;
import com.sheaf.domain.types.TypeEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Unbound plan → bound plan: names to ids, sink intent to a concrete sink, nothing the user decides. */
class BinderTest {

    private static final TypeEnvironment ENV = CorpusEnvironment.build();

    private static UnboundPlan.UnboundQuery query(String source, SinkIntent sink, Step... steps) {
        return new UnboundPlan.UnboundQuery(source, List.of(steps), sink, List.of());
    }

    private static Plan.QueryPlan bound(UnboundPlan plan) {
        var r = Binder.bind(plan, ENV);
        assertThat(r.diagnostics()).isEmpty();
        return (Plan.QueryPlan) r.plan();
    }

    @Test
    void table_names_resolve_ignoring_case_to_their_catalog_spelling_and_id() {
        var plan = bound(query("oRdErS", new SinkIntent.NewSheetIntent("Result"),
                new Step.LookupStep("REGIONS", new Step.JoinKey("region", "code"), List.of("region_name"))));
        assertThat(plan.source()).isEqualTo("Orders");
        assertThat(((Step.LookupStep) plan.steps().get(0)).with()).isEqualTo("Regions");
        assertThat(plan.bindings().entities()).extracting(b -> b.id()).containsExactly("t:Orders", "t:Regions");
        assertThat(plan.bindings().entities().get(0).sheetId()).isEqualTo("ws-Orders");
        assertThat(plan.bindings().entities().get(0).columns()).hasSize(12)
                .first().satisfies(c -> assertThat(c.id()).isEqualTo("order_id"));
    }

    @Test
    void two_spellings_of_the_same_request_bind_to_the_same_hash() {
        var a = bound(query("orders", new SinkIntent.NewSheetIntent("Result")));
        var b = bound(query("ORDERS", new SinkIntent.NewSheetIntent("Result")));
        assertThat(PlanHasher.hash(PlanEnvelope.of(a))).isEqualTo(PlanHasher.hash(PlanEnvelope.of(b)));
    }

    @Test
    void an_unknown_table_is_reported_with_ranked_candidates() {
        var r = Binder.bind(query("Order", new SinkIntent.AnchorIntent()), ENV);
        assertThat(r.plan()).isNull();
        var d = r.diagnostics().get(0);
        assertThat(d.code()).isEqualTo(DiagnosticCode.E_UNKNOWN_SOURCE);
        assertThat(d.pointer()).isEqualTo("/source");
        assertThat((List<?>) d.context().get("candidates")).first().isEqualTo("Orders");
    }

    @Test
    void new_sheet_names_are_made_valid_and_unused() {
        Set<String> sheets = Set.of("Orders", "Analysis", "analysis 2");
        assertThat(Binder.sheetName("Revenue", sheets)).isEqualTo("Revenue");
        assertThat(Binder.sheetName("ANALYSIS", sheets)).isEqualTo("ANALYSIS 3");
        assertThat(Binder.sheetName("Q1/Q2: [draft]?", sheets)).isEqualTo("Q1Q2 draft");
        assertThat(Binder.sheetName("'quoted'", sheets)).isEqualTo("quoted");
        assertThat(Binder.sheetName(null, sheets)).isEqualTo(Binder.DEFAULT_SHEET_NAME);
        assertThat(Binder.sheetName("history", sheets)).isEqualTo(Binder.DEFAULT_SHEET_NAME);
        assertThat(Binder.sheetName("x".repeat(40), sheets)).hasSize(31);
        assertThat(Binder.sheetName("a".repeat(31), Set.of("a".repeat(31)))).isEqualTo("a".repeat(29) + " 2");
        for (String n : List.of("ANALYSIS", "Q1/Q2: [draft]?", "'quoted'", "x".repeat(40))) {
            assertThat(QueryChecker.validSheetName(Binder.sheetName(n, sheets))).as(n).isTrue();
        }
    }

    @Test
    void a_new_sheet_intent_becomes_a_sheet_that_starts_at_A1() {
        var plan = bound(query("Orders", new SinkIntent.NewSheetIntent("Analysis")));
        assertThat(plan.sink()).isEqualTo(new Sink.NewSheetSink("Analysis 2", "A1"));
    }

    @Test
    void an_anchor_intent_leaves_the_location_to_the_user() {
        var plan = bound(query("Orders", new SinkIntent.AnchorIntent()));
        assertThat(plan.sink()).isEqualTo(new Sink.AnchorSink());
    }

    @Test
    void a_template_intent_takes_its_header_rows_from_the_imported_template() {
        var plan = bound(query("Contacts", new SinkIntent.TemplateIntent(CorpusEnvironment.EXACT)));
        assertThat(plan.sink()).isEqualTo(new Sink.TemplateSink(CorpusEnvironment.EXACT, 1, 2));

        var r = Binder.bind(query("Contacts", new SinkIntent.TemplateIntent("crm-exakt")), ENV);
        assertThat(r.plan()).isNull();
        assertThat(r.diagnostics().get(0).code()).isEqualTo(DiagnosticCode.E_TEMPLATE_UNKNOWN);
        assertThat(r.diagnostics().get(0).pointer()).isEqualTo("/sink/templateId");
        assertThat((List<?>) r.diagnostics().get(0).context().get("candidates")).first().isEqualTo(CorpusEnvironment.EXACT);
    }

    @Test
    void an_edit_binds_only_its_target() {
        var r = Binder.bind(new UnboundPlan.UnboundEdit("contacts", List.of(new EditOp.DropColumn("fax")), List.of()), ENV);
        var plan = (Plan.EditPlan) r.plan();
        assertThat(plan.target()).isEqualTo("Contacts");
        assertThat(plan.bindings().entities()).extracting(b -> b.id()).containsExactly("t:Contacts");
    }
}
