package com.sheaf.domain.validation;

import com.sheaf.domain.commit.CommitRequest;
import com.sheaf.domain.commit.CommitRequest.AnchorChoice;
import com.sheaf.domain.commit.CommitRequest.OnDependents;
import com.sheaf.domain.commit.CommitRequest.ParamValue;
import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.Bindings;
import com.sheaf.domain.ir.EditOp;
import com.sheaf.domain.ir.ParamDecl;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlanHasher;
import com.sheaf.domain.ir.Predicate;
import com.sheaf.domain.ir.Sink;
import com.sheaf.domain.ir.Step;
import com.sheaf.domain.ir.ValueType;
import com.sheaf.domain.types.TypeEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The user's choices at commit time against the checked plan: consent, anchor, parameters, identity. */
class ConsentCheckTest {

    private static final TypeEnvironment ENV = CorpusEnvironment.build();
    private static final Plan DROP_LTV = TypeCheckerTest.edit("Contacts", new EditOp.DropColumn("lifetime_value"));

    private static List<Diagnostic> check(Plan plan, CommitRequest req) {
        String hash = PlanHasher.hash(PlanEnvelope.of(plan));
        var checked = TypeChecker.check(plan, ENV);
        assertThat(checked.valid()).isTrue();
        return ConsentCheck.check(plan, hash, checked, req);
    }

    private static List<String> codes(Plan plan, CommitRequest req) {
        return check(plan, req).stream().map(d -> d.code().name()).toList();
    }

    private static CommitRequest commit(Plan plan, @Nullable AnchorChoice anchor, OnDependents on, ParamValue... params) {
        return new CommitRequest(PlanHasher.hash(PlanEnvelope.of(plan)), anchor, on, false, List.of(params), List.of());
    }

    @Test
    void removing_what_a_formula_reads_is_refused_unless_the_user_chose_to_convert_it() {
        assertThat(codes(DROP_LTV, commit(DROP_LTV, null, OnDependents.block))).containsExactly("E_DROP_HAS_DEPENDENTS");
        assertThat(codes(DROP_LTV, commit(DROP_LTV, null, OnDependents.convertToValues))).isEmpty();
    }

    @Test
    void the_refusal_names_every_dependent_by_address_and_points_at_the_choice() {
        var d = check(DROP_LTV, commit(DROP_LTV, null, OnDependents.block)).get(0);
        assertThat(d.message()).contains("Summary!B2");
        assertThat(d.pointer()).isEqualTo("/onDependents");
    }

    @Test
    void an_edit_nothing_depends_on_needs_no_consent() {
        var drop = TypeCheckerTest.edit("Contacts", new EditOp.DropColumn("fax"));
        assertThat(codes(drop, commit(drop, null, OnDependents.block))).isEmpty();
    }

    @Test
    void a_commit_for_another_plan_is_refused() {
        var req = new CommitRequest("sha256:" + "0".repeat(64), null, OnDependents.convertToValues, false, List.of(), List.of());
        assertThat(codes(DROP_LTV, req)).containsExactly("E_COMMIT_PLAN_MISMATCH");
    }

    @Test
    void writing_into_an_existing_sheet_needs_a_single_cell_chosen_by_the_user() {
        var plan = TypeCheckerTest.query("Orders", new Sink.AnchorSink(),
                new Step.AggregateStep(List.of("region"), List.of(new Step.AggregateMeasure(Step.AggFn.count, "*", null, "n"))));
        assertThat(codes(plan, commit(plan, null, OnDependents.block))).containsExactly("E_SINK_ANCHOR_MISSING");
        assertThat(codes(plan, commit(plan, new AnchorChoice("ws-Summary", "B2:C9"), OnDependents.block))).containsExactly("E_SINK_ANCHOR_INVALID");
        assertThat(codes(plan, commit(plan, new AnchorChoice("ws-Summary", "$B$2"), OnDependents.block))).isEmpty();
    }

    @Test
    void every_declared_parameter_needs_a_value_of_its_type_and_nothing_else_is_accepted() {
        var plan = new Plan.QueryPlan("Orders", List.of(new Step.FilterStep(new Predicate.GtePredicate(
                new Predicate.ValueRef.ColRef("order_date"), new Predicate.ValueRef.ParamRef("asOf")))),
                new Sink.NewSheetSink("Result", "A1"), List.of(new ParamDecl("asOf", ValueType.DATE)), new Bindings(List.of()));

        assertThat(codes(plan, commit(plan, null, OnDependents.block))).containsExactly("E_PARAM_MISSING");
        assertThat(codes(plan, commit(plan, null, OnDependents.block, new ParamValue("asOf", "31/03/2025")))).containsExactly("E_PARAM_TYPE");
        assertThat(codes(plan, commit(plan, null, OnDependents.block, new ParamValue("ASOF", "2025-03-31")))).isEmpty();
        assertThat(codes(plan, commit(plan, null, OnDependents.block, new ParamValue("asOf", "2025-03-31"), new ParamValue("region", "North"))))
                .containsExactly("E_UNKNOWN_PARAM");
    }
}
