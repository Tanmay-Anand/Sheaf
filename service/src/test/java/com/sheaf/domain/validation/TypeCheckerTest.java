package com.sheaf.domain.validation;

import com.sheaf.domain.catalog.CatalogColumn;
import com.sheaf.domain.catalog.CatalogEntity;
import com.sheaf.domain.catalog.ColumnDependent;
import com.sheaf.domain.catalog.DateSystem;
import com.sheaf.domain.catalog.DependentClass;
import com.sheaf.domain.catalog.DependentKind;
import com.sheaf.domain.catalog.EntityKind;
import com.sheaf.domain.catalog.JoinCandidate;
import com.sheaf.domain.catalog.ScalarKind;
import com.sheaf.domain.catalog.UntraceableKind;
import com.sheaf.domain.catalog.UntraceableReference;
import com.sheaf.domain.catalog.WorkbookCatalog;
import com.sheaf.domain.ir.Bindings;
import com.sheaf.domain.ir.EditOp;
import com.sheaf.domain.ir.Expr;
import com.sheaf.domain.ir.ParamDecl;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.Position;
import com.sheaf.domain.ir.Predicate;
import com.sheaf.domain.ir.Sink;
import com.sheaf.domain.ir.Step;
import com.sheaf.domain.ir.ValueType;
import com.sheaf.domain.types.CatalogEnvironment;
import com.sheaf.domain.types.ScalarType;
import com.sheaf.domain.types.TypeEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TypeCheckerTest {

    /**
     * Error codes covered by targeted tests rather than invalid-plans.json: they need a bound plan the
     * binder would never produce (sink names, anchors), an environment the corpus doesn't have, or a
     * commit request (see ConsentCheckTest).
     */
    static final Set<String> CODES_COVERED_THERE = Set.of(
            "E_JOIN_KEY_TYPE_MISMATCH",
            "E_SINK_NEW_SHEET_NAME_MISSING", "E_SINK_SHEET_NAME_INVALID", "E_SINK_SHEET_NAME_TAKEN", "E_SINK_ANCHOR_INVALID",
            "E_COMMIT_PLAN_MISMATCH", "E_SINK_ANCHOR_MISSING", "E_DROP_HAS_DEPENDENTS", "E_PARAM_MISSING", "E_PARAM_TYPE");

    private static final TypeEnvironment ENV = CorpusEnvironment.build();
    private static final Sink SHEET = new Sink.NewSheetSink("Result", "A1");
    private static final Bindings NONE = new Bindings(List.of());

    static Plan.QueryPlan query(String source, Step... steps) {
        return new Plan.QueryPlan(source, List.of(steps), SHEET, List.of(), NONE);
    }

    static Plan.QueryPlan query(String source, Sink sink, Step... steps) {
        return new Plan.QueryPlan(source, List.of(steps), sink, List.of(), NONE);
    }

    static Plan.EditPlan edit(String target, EditOp... ops) {
        return new Plan.EditPlan(target, List.of(ops), List.of(), NONE);
    }

    private static Predicate eq(String col, Object value) {
        return new Predicate.EqPredicate(new Predicate.ValueRef.ColRef(col), new Predicate.ValueRef.Lit(value, null));
    }

    private static List<String> codes(CheckResult r) {
        return r.diagnostics().stream().map(d -> d.code().name()).toList();
    }

    // ── catalog-backed environment ─────────────────────────────────────────────

    private static CatalogColumn column(String name, String letter, ScalarKind kind, boolean formula, boolean errors,
                                        boolean numbersAsText, List<ColumnDependent> deps) {
        return new CatalogColumn("c:" + letter, name, letter, 0, kind, kind == ScalarKind.CURRENCY ? "INR" : null, null, false, 0, 10,
                false, formula, null, null, null, null, errors, numbersAsText, deps, List.of());
    }

    static WorkbookCatalog catalog(boolean untraceable) {
        var sales = new CatalogEntity("r:ws1:A3", "Sheet1", EntityKind.region, "ws1", "Sheet1", "Sheet1!A3:I22", 3, 1, 4, 22, 11, List.of(), List.of(
                column("Region", "A", ScalarKind.CATEGORICAL, false, false, false, List.of()),
                column("Qty", "E", ScalarKind.NUMBER, false, false, true, List.of()),
                column("Amount", "G", ScalarKind.CURRENCY, true, true, false,
                        List.of(new ColumnDependent(DependentKind.formula, "Sheet1!L5", "=SUM(G5:G19)", DependentClass.exclusive, true),
                                new ColumnDependent(DependentKind.chartSeries, "Sheet1 · chart \"Sales\" · series \"Amount\"",
                                        "=Sheet1!$G$5:$G$19", DependentClass.exclusive, true)))),
                "h", null);
        return new WorkbookCatalog(WorkbookCatalog.CURRENT_VERSION, Instant.EPOCH, DateSystem.D1900, List.of(sales), List.<JoinCandidate>of(),
                untraceable ? List.of(new UntraceableReference(UntraceableKind.indirect, "Sheet1!M2", "INDIRECT builds its reference at run time")) : List.of(),
                List.of(), List.of());
    }

    @Test
    void dropping_a_column_with_dependents_is_valid_but_names_every_dependent_for_consent() {
        var env = CatalogEnvironment.from(catalog(false));
        var r = TypeChecker.check(edit("Sheet1", new EditOp.DropColumn("Amount")), env);

        assertThat(r.valid()).isTrue();
        assertThat(codes(r)).containsExactly("W_DEPENDENTS_NEED_CONSENT");
        assertThat(r.warnings().get(0).message()).contains("Sheet1!L5").contains("Sheet1 · chart \"Sales\" · series \"Amount\"");
        assertThat(r.impact().dependentsNeedingConsent()).extracting(x -> x.dependent().location())
                .containsExactly("Sheet1!L5", "Sheet1 · chart \"Sales\" · series \"Amount\"");
        assertThat(r.impact().dependentsNeedingConsent()).extracting(ImpactReport.AffectedDependent::cause)
                .containsOnly(ImpactReport.Cause.columnRemoved);
    }

    @Test
    void deleting_rows_needs_consent_only_from_dependents_that_name_fixed_rows() {
        var env = CatalogEnvironment.from(catalog(false));
        var r = TypeChecker.check(edit("Sheet1", new EditOp.DropRows(eq("Region", "North"))), env);
        assertThat(r.valid()).isTrue();
        assertThat(r.impact().removesRows()).isTrue();
        assertThat(r.impact().dependentsNeedingConsent()).extracting(ImpactReport.AffectedDependent::cause)
                .containsOnly(ImpactReport.Cause.rowsRemoved).hasSize(2);

        // Summary!B2 follows the whole Contacts column (not fixed rows): deleting rows is what it is for.
        var contacts = TypeChecker.check(edit("Contacts", new EditOp.DropRows(eq("status", "test"))), ENV);
        assertThat(contacts.impact().dependentsNeedingConsent()).isEmpty();
        assertThat(codes(contacts)).isEmpty();
    }

    @Test
    void a_rename_keeps_the_column_identity_so_its_dependents_still_need_consent_for_a_later_drop() {
        var r = TypeChecker.check(edit("Contacts",
                new EditOp.RenameColumn("lifetime_value", "ltv"), new EditOp.DropColumn("ltv")), ENV);
        assertThat(codes(r)).containsExactly("W_DEPENDENTS_NEED_CONSENT");
        assertThat(r.impact().dependentsNeedingConsent()).extracting(x -> x.dependent().location()).containsExactly("Summary!B2");
        assertThat(r.impact().removed()).containsExactly("lifetime_value");
    }

    @Test
    void a_case_only_rename_is_allowed() {
        var r = TypeChecker.check(edit("Contacts", new EditOp.RenameColumn("email", "Email")), ENV);
        assertThat(r.errors()).isEmpty();
        assertThat(r.output().names()).contains("Email").doesNotContain("email");
    }

    @Test
    void untraceable_references_are_disclosed_when_an_edit_removes_something() {
        var env = CatalogEnvironment.from(catalog(true));
        var r = TypeChecker.check(edit("Sheet1", new EditOp.DropColumn("Qty")), env);
        assertThat(r.valid()).isTrue();
        assertThat(codes(r)).containsExactly("W_UNTRACEABLE_REFERENCES");
        assertThat(r.impact().untraceableReferences()).isTrue();
    }

    @Test
    void overwriting_a_formula_column_warns() {
        var env = CatalogEnvironment.from(catalog(false));
        var r = TypeChecker.check(edit("Sheet1", new EditOp.SetColumn("Amount", new Expr.Lit(0, null), null)), env);
        assertThat(codes(r)).containsExactly("W_OVERWRITES_FORMULAS");
        assertThat(r.impact().formulaColumnsOverwritten()).containsExactly("Amount");
    }

    @Test
    void an_added_column_goes_exactly_where_its_position_says() {
        var r = TypeChecker.check(edit("Regions",
                new EditOp.AddColumn("first", new Expr.Lit("x", null), new Position.First()),
                new EditOp.AddColumn("mid", new Expr.Lit("y", null), new Position.After("ZONE")),
                new EditOp.AddColumn("end", new Expr.Lit("z", null), new Position.Last())), ENV);
        assertThat(r.errors()).isEmpty();
        assertThat(r.output().names()).containsExactly("first", "code", "region_name", "zone", "mid", "manager", "hq_city", "end");
    }

    @Test
    void aggregating_a_column_with_error_cells_or_numbers_stored_as_text_warns() {
        var env = CatalogEnvironment.from(catalog(false));
        var r = TypeChecker.check(query("Sheet1", new Step.AggregateStep(List.of("Region"), List.of(
                new Step.AggregateMeasure(Step.AggFn.sum, "Amount", null, "total"),
                new Step.AggregateMeasure(Step.AggFn.sum, "Qty", null, "units")))), env);
        assertThat(r.valid()).isTrue();
        assertThat(codes(r)).containsExactly("W_MAY_CONTAIN_ERRORS", "W_NUMBERS_STORED_AS_TEXT");
        assertThat(r.warnings().get(0).pointer()).isEqualTo("/steps/0/measures/0/of");
    }

    @Test
    void catalog_environments_have_no_metrics_yet_so_period_compare_is_refused_with_a_clear_hint() {
        var env = CatalogEnvironment.from(catalog(false));
        var r = TypeChecker.check(query("Sheet1",
                new Step.PeriodCompareStep("Revenue", "Date", Step.Grain.quarter, "2025-Q1", "2024-Q4", null)), env);
        assertThat(codes(r)).containsExactly("E_UNKNOWN_METRIC");
        assertThat(r.errors().get(0).hint()).isEqualTo("No metrics are defined for Sheet1 yet.");
    }

    // ── joins and lookups ──────────────────────────────────────────────────────

    @Test
    void an_approved_join_unions_both_tables_and_a_left_join_makes_the_right_side_nullable() {
        var r = TypeChecker.check(query("Orders",
                new Step.JoinStep("Regions", new Step.JoinKey("region", "code"), Step.JoinKind.left),
                new Step.ProjectStep(List.of(
                        new Step.ProjectColumn("region", new Expr.ColRef("region")),
                        new Step.ProjectColumn("region_name", new Expr.ColRef("region_name"))))), ENV);
        assertThat(r.errors()).isEmpty();
        assertThat(r.output().render()).containsExactly("region: categorical", "region_name: string?");
    }

    @Test
    void join_keys_of_different_kinds_are_refused_even_on_an_approved_edge() {
        var env = new TypeEnvironment(
                Map.of("A", CorpusEnvironment.entity("A", CorpusEnvironment.c("qty", ScalarType.NUMBER)),
                        "B", CorpusEnvironment.entity("B", CorpusEnvironment.c("code", ScalarType.STRING))),
                List.of(new TypeEnvironment.JoinEdge("A", "qty", "B", "code", true)), Map.of(), Map.of(), Map.of(), Set.of("A", "B"), false);
        var r = TypeChecker.check(query("A", new Step.JoinStep("B", new Step.JoinKey("qty", "code"), Step.JoinKind.inner)), env);
        assertThat(codes(r)).containsExactly("E_JOIN_KEY_TYPE_MISMATCH");
    }

    @Test
    void colliding_names_keep_the_left_spelling_and_get_their_table_in_brackets_on_the_right() {
        var env = new TypeEnvironment(
                Map.of("A", CorpusEnvironment.entity("A", CorpusEnvironment.c("id", ScalarType.STRING), CorpusEnvironment.c("name", ScalarType.STRING)),
                        "B", CorpusEnvironment.entity("B", CorpusEnvironment.c("ID", ScalarType.STRING), CorpusEnvironment.c("name", ScalarType.STRING))),
                List.of(new TypeEnvironment.JoinEdge("A", "id", "B", "ID", true)), Map.of(), Map.of(), Map.of(), Set.of("A", "B"), false);
        var r = TypeChecker.check(query("A", new Step.JoinStep("B", new Step.JoinKey("id", "ID"), Step.JoinKind.inner)), env);
        assertThat(r.errors()).isEmpty();
        assertThat(r.output().names()).containsExactly("id", "name", "ID (B)", "name (B)");
    }

    @Test
    void a_lookup_keeps_every_row_and_adds_only_the_taken_columns_as_nullable() {
        var r = TypeChecker.check(query("Orders",
                new Step.LookupStep("regions", new Step.JoinKey("region", "code"), List.of("manager"))), ENV);
        assertThat(r.errors()).isEmpty();
        assertThat(r.output().names()).hasSize(13).endsWith("manager");
        assertThat(r.output().render()).last().isEqualTo("manager: string?");
    }

    // ── warnings, suggestions, pointers ────────────────────────────────────────

    @Test
    void typos_get_a_did_you_mean_and_ranked_candidates() {
        var r = TypeChecker.check(query("Orders", new Step.FilterStep(eq("stauts", "returned"))), ENV);
        var d = r.errors().get(0);
        assertThat(d.message()).isEqualTo("Column 'stauts' does not exist. Did you mean 'status'?");
        assertThat(d.context()).containsEntry("suggestion", "status");
        assertThat((List<?>) d.context().get("candidates")).first().isEqualTo("status");
        assertThat((List<?>) d.context().get("candidates")).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void column_names_ignore_case_as_excel_does() {
        var r = TypeChecker.check(query("Orders", new Step.FilterStep(eq("STATUS", "returned")),
                new Step.SortStep(List.of(new Step.SortKey("Amount", Step.SortDir.desc)))), ENV);
        assertThat(r.errors()).isEmpty();
        assertThat(r.output().names()).contains("status", "amount");
    }

    @Test
    void a_value_outside_a_known_category_warns_but_stays_valid() {
        var r = TypeChecker.check(query("Orders", new Step.FilterStep(eq("status", "canceled"))), ENV);
        assertThat(r.valid()).isTrue();
        assertThat(codes(r)).containsExactly("W_CATEGORICAL_LITERAL_NOT_IN_DOMAIN");
    }

    @Test
    void limit_without_sort_and_empty_pipelines_warn() {
        assertThat(codes(TypeChecker.check(query("Orders", new Step.LimitStep(5)), ENV))).containsExactly("W_LIMIT_WITHOUT_SORT");
        assertThat(codes(TypeChecker.check(query("Orders"), ENV))).containsExactly("W_TRIVIAL_PLAN");
    }

    @Test
    void checking_stops_at_the_first_failing_step_and_points_at_the_field() {
        var r = TypeChecker.check(query("Orders",
                new Step.FilterStep(eq("ghost", 1)),
                new Step.SortStep(List.of(new Step.SortKey("phantom", Step.SortDir.asc)))), ENV);
        assertThat(codes(r)).containsExactly("E_UNKNOWN_COLUMN");
        assertThat(r.errors().get(0).step()).isZero();
        assertThat(r.errors().get(0).pointer()).isEqualTo("/steps/0/predicate/left/col");
    }

    @Test
    void a_numeric_literal_adapts_to_currency_and_a_typed_date_literal_to_a_date_column() {
        var r = TypeChecker.check(query("Orders",
                new Step.FilterStep(new Predicate.GtPredicate(new Predicate.ValueRef.ColRef("amount"), new Predicate.ValueRef.Lit(1000, null))),
                new Step.FilterStep(new Predicate.GtePredicate(new Predicate.ValueRef.ColRef("order_date"),
                        new Predicate.ValueRef.Lit("2025-01-01", ValueType.DATE))),
                new Step.DeriveStep("with_fee", new Expr.AddExpr(new Expr.ColRef("amount"), new Expr.Lit(50, null)))), ENV);
        assertThat(r.errors()).isEmpty();
        assertThat(r.output().find("with_fee").orElseThrow().type()).isEqualTo(new ScalarType.CurrencyType("INR"));
    }

    @Test
    void a_declared_parameter_is_typed_like_a_literal_of_its_value_type() {
        var plan = new Plan.QueryPlan("Orders", List.of(new Step.FilterStep(new Predicate.GtePredicate(
                new Predicate.ValueRef.ColRef("order_date"), new Predicate.ValueRef.ParamRef("asOf")))),
                SHEET, List.of(new ParamDecl("asOf", ValueType.DATE)), NONE);
        assertThat(TypeChecker.check(plan, ENV).errors()).isEmpty();

        var wrong = new Plan.QueryPlan("Orders", plan.steps(), SHEET, List.of(new ParamDecl("asOf", ValueType.BOOLEAN)), NONE);
        assertThat(codes(TypeChecker.check(wrong, ENV))).containsExactly("E_TYPE_INCOMPARABLE");
    }

    // ── sinks (bound plans; the binder never produces these, a stored plan could) ──

    @Test
    void new_sheet_sinks_need_a_valid_unused_name_and_a_cell() {
        Step agg = new Step.AggregateStep(List.of("region"), List.of(new Step.AggregateMeasure(Step.AggFn.count, "*", null, "n")));
        assertThat(codes(TypeChecker.check(query("Orders", new Sink.NewSheetSink(" ", "A1"), agg), ENV)))
                .containsExactly("E_SINK_NEW_SHEET_NAME_MISSING");
        assertThat(codes(TypeChecker.check(query("Orders", new Sink.NewSheetSink("Q1/Q2", "A1"), agg), ENV)))
                .containsExactly("E_SINK_SHEET_NAME_INVALID");
        assertThat(codes(TypeChecker.check(query("Orders", new Sink.NewSheetSink("x".repeat(32), "A1"), agg), ENV)))
                .containsExactly("E_SINK_SHEET_NAME_INVALID");
        assertThat(codes(TypeChecker.check(query("Orders", new Sink.NewSheetSink("history", "A1"), agg), ENV)))
                .containsExactly("E_SINK_SHEET_NAME_INVALID");
        assertThat(codes(TypeChecker.check(query("Orders", new Sink.NewSheetSink("ANALYSIS", "A1"), agg), ENV)))
                .containsExactly("E_SINK_SHEET_NAME_TAKEN");
        assertThat(codes(TypeChecker.check(query("Orders", new Sink.NewSheetSink("Fresh", "A1:B2"), agg), ENV)))
                .containsExactly("E_SINK_ANCHOR_INVALID");
    }
}
