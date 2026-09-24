package com.sheaf.domain.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sheaf.domain.ir.Bindings;
import com.sheaf.domain.ir.EditOp;
import com.sheaf.domain.ir.Expr;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlanHasher;
import com.sheaf.domain.ir.Position;
import com.sheaf.domain.ir.Predicate;
import com.sheaf.domain.ir.Sink;
import com.sheaf.domain.ir.Step;
import com.sheaf.domain.types.ScalarType;
import com.sheaf.domain.types.TypeEnvironment;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.statistics.Statistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants over generated plans. Generators mix real column names (some in other letter cases)
 * with "ghost" names that exist nowhere, and random operator sequences; the checker must reject
 * every plan that would reference a ghost, every valid edit's impact must stay inside the columns
 * its operations name, no output may hold two names differing only in case, and a plan's hash
 * must not depend on how its JSON happens to be ordered.
 */
class TypeCheckerPropertyTest {

    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private static final Bindings NONE = new Bindings(List.of());
    private static final List<String> REAL = List.of("id", "region", "amount", "qty", "day", "note");
    private static final List<String> GHOSTS = List.of("ghost_col", "phantom_col");
    private static final TypeEnvironment ENV = new TypeEnvironment(
            Map.of("T", CorpusEnvironment.entity("T",
                    CorpusEnvironment.c("id", ScalarType.STRING, 100),
                    CorpusEnvironment.c("region", CorpusEnvironment.nominal("region", "N", "S"), 2),
                    CorpusEnvironment.c("amount", new ScalarType.CurrencyType("INR")),
                    CorpusEnvironment.c("qty", ScalarType.NUMBER),
                    CorpusEnvironment.c("day", ScalarType.DATE),
                    CorpusEnvironment.nullable("note", ScalarType.STRING))),
            List.of(), Map.of(), Map.of(), Map.of(), Set.of("T"), false);

    // ── generators ─────────────────────────────────────────────────────────────

    private static Arbitrary<String> name() {
        // Mostly real names, some derived names, a few ghosts.
        return Arbitraries.frequencyOf(
                Tuple.of(5, Arbitraries.of(REAL)),
                Tuple.of(1, Arbitraries.of(REAL).map(n -> n.toUpperCase(Locale.ROOT))),
                Tuple.of(2, Arbitraries.of("d0", "d1", "m0")),
                Tuple.of(1, Arbitraries.of(GHOSTS)));
    }

    private static Arbitrary<Expr> expr() {
        return Arbitraries.oneOf(
                name().map(Expr.ColRef::new),
                Arbitraries.integers().between(0, 9).map(i -> (Expr) new Expr.Lit(i, null)),
                Combinators.combine(name(), name()).as((a, b) -> (Expr) new Expr.AddExpr(new Expr.ColRef(a), new Expr.ColRef(b))),
                name().map(Expr.MonthOfExpr::new),
                Combinators.combine(name(), name()).as((a, b) ->
                        (Expr) new Expr.ConcatExpr(List.of(new Expr.ColRef(a), new Expr.ColRef(b)), " ", true)));
    }

    private static Arbitrary<Predicate> predicate() {
        return Arbitraries.oneOf(
                name().map(n -> (Predicate) new Predicate.IsNullPredicate(n)),
                name().map(n -> (Predicate) new Predicate.EqPredicate(new Predicate.ValueRef.ColRef(n), new Predicate.ValueRef.Lit("N", null))),
                Combinators.combine(name(), name()).as((a, b) ->
                        (Predicate) new Predicate.LtPredicate(new Predicate.ValueRef.ColRef(a), new Predicate.ValueRef.ColRef(b))));
    }

    private static Arbitrary<Step> step() {
        return Arbitraries.oneOf(
                predicate().map(Step.FilterStep::new),
                Combinators.combine(Arbitraries.of("d0", "d1"), expr()).as(Step.DeriveStep::new),
                Combinators.combine(name(), Arbitraries.of(Step.AggFn.sum, Step.AggFn.count, Step.AggFn.max), name())
                        .as((g, fn, of) -> new Step.AggregateStep(List.of(g), List.of(new Step.AggregateMeasure(fn, of, null, "m0")))),
                name().map(n -> new Step.SortStep(List.of(new Step.SortKey(n, Step.SortDir.asc)))),
                Combinators.combine(name(), name()).as((a, b) -> new Step.ProjectStep(List.of(
                        new Step.ProjectColumn("p_" + a, new Expr.ColRef(a)), new Step.ProjectColumn("p2_" + b, new Expr.ColRef(b))))));
    }

    @Provide
    Arbitrary<Plan.QueryPlan> queryPlans() {
        return step().list().ofMinSize(1).ofMaxSize(4)
                .map(steps -> new Plan.QueryPlan("T", steps, new Sink.NewSheetSink("Out", "A1"), List.of(), NONE));
    }

    private static Arbitrary<EditOp> editOp() {
        return Arbitraries.oneOf(
                Combinators.combine(Arbitraries.of("n0", "n1"), expr()).as((as, e) -> new EditOp.AddColumn(as, e, new Position.Last())),
                Combinators.combine(name(), expr()).as((c, e) -> new EditOp.SetColumn(c, e, null)),
                name().map(EditOp.DropColumn::new),
                Combinators.combine(name(), Arbitraries.of("r0", "r1", "amount")).as(EditOp.RenameColumn::new),
                Combinators.combine(name(), name()).as((c, after) -> new EditOp.MoveColumn(c, new Position.After(after))),
                predicate().map(EditOp.DropRows::new));
    }

    @Provide
    Arbitrary<Plan.EditPlan> editPlans() {
        return editOp().list().ofMinSize(1).ofMaxSize(5)
                .map(ops -> new Plan.EditPlan("T", ops, List.of(), NONE));
    }

    // ── properties ─────────────────────────────────────────────────────────────

    @Property(tries = 5000)
    void no_valid_plan_references_a_column_absent_from_the_source_schema(@ForAll("queryPlans") Plan.QueryPlan plan) throws Exception {
        CheckResult r = TypeChecker.check(plan, ENV);
        Statistics.collect(r.valid() ? "valid" : "rejected");
        if (!r.valid()) return;
        String json = JSON.writeValueAsString(plan);
        for (String ghost : GHOSTS) assertThat(json).doesNotContain("\"" + ghost + "\"");
        // Every referenced name is a source column or was introduced by an earlier step.
        Set<String> introduced = new HashSet<>(REAL);
        for (Step s : plan.steps()) {
            for (String ref : referenced(s)) assertThat(introduced).contains(ref.toLowerCase(Locale.ROOT));
            introducedBy(s).forEach(n -> introduced.add(n.toLowerCase(Locale.ROOT)));
        }
    }

    @Property(tries = 5000)
    void no_valid_edit_plan_has_an_impact_outside_the_columns_its_operations_name(@ForAll("editPlans") Plan.EditPlan plan) {
        CheckResult r = TypeChecker.check(plan, ENV);
        Statistics.collect(r.valid() ? "valid" : "rejected");
        if (!r.valid()) return;
        Set<String> named = new HashSet<>();
        for (EditOp op : plan.ops()) {
            switch (op) {
                case EditOp.AddColumn a -> named.add(a.as());
                case EditOp.SetColumn s -> named.add(s.col());
                case EditOp.DropColumn d -> named.add(d.col());
                case EditOp.RenameColumn n -> {
                    named.add(n.col());
                    named.add(n.to());
                }
                case EditOp.MoveColumn m -> named.add(m.col());
                case EditOp.DropRows d -> { }
            }
        }
        // A column's identity survives renames: rename qty→r0 then drop r0 removes "qty".
        // Close the named set backwards over renames, so the original names count as named too.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (EditOp op : plan.ops()) {
                if (op instanceof EditOp.RenameColumn n && named.contains(n.to()) && named.add(n.col())) grew = true;
            }
        }
        var i = r.impact();
        List<String> touched = new ArrayList<>();
        touched.addAll(i.added());
        touched.addAll(i.removed());
        touched.addAll(i.overwritten());
        touched.addAll(i.moved());
        i.renamed().forEach(x -> {
            touched.add(x.from());
            touched.add(x.to());
        });
        assertThat(lower(named)).containsAll(lower(touched));
    }

    @Property(tries = 2000)
    void no_valid_plan_outputs_two_columns_whose_names_differ_only_in_case(@ForAll("queryPlans") Plan.QueryPlan plan) {
        CheckResult r = TypeChecker.check(plan, ENV);
        if (!r.valid()) return;
        var names = r.output().names();
        assertThat(new HashSet<>(lower(names))).hasSameSizeAs(names);
    }

    @Property(tries = 2000)
    void the_plan_hash_depends_only_on_the_plan_not_on_key_order(@ForAll("queryPlans") Plan.QueryPlan plan, @ForAll long seed) throws Exception {
        String hash = PlanHasher.hash(PlanEnvelope.of(plan));
        // The same plan as another writer might serialise it: every object's keys in a random order.
        String shuffled = JSON.writeValueAsString(shuffleKeys(JSON.valueToTree(plan), new Random(seed)));
        var replayed = PlanParser.parsePlan(shuffled);
        assertThat(replayed.diagnostics()).isEmpty();
        assertThat(PlanHasher.hash(PlanEnvelope.of(replayed.value()))).isEqualTo(hash).startsWith("sha256:");
    }

    private static JsonNode shuffleKeys(JsonNode n, Random random) {
        if (n.isObject()) {
            List<String> keys = new ArrayList<>();
            n.fieldNames().forEachRemaining(keys::add);
            Collections.shuffle(keys, random);
            ObjectNode out = JSON.createObjectNode();
            for (String k : keys) out.set(k, shuffleKeys(n.get(k), random));
            return out;
        }
        if (n.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            n.forEach(x -> out.add(shuffleKeys(x, random)));
            return out;
        }
        return n;
    }

    private static List<String> lower(java.util.Collection<String> names) {
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
    }

    // ── reference collection for the query property ───────────────────────────

    private static List<String> referenced(Step s) {
        List<String> out = new ArrayList<>();
        switch (s) {
            case Step.FilterStep f -> collect(f.predicate(), out);
            case Step.DeriveStep d -> collect(d.expr(), out);
            case Step.AggregateStep a -> {
                out.addAll(a.groupBy());
                a.measures().forEach(m -> {
                    if (!"*".equals(m.of())) out.add(m.of());
                });
            }
            case Step.SortStep so -> so.by().forEach(k -> out.add(k.col()));
            case Step.ProjectStep p -> p.columns().forEach(c -> collect(c.expr(), out));
            default -> { }
        }
        return out;
    }

    private static List<String> introducedBy(Step s) {
        return switch (s) {
            case Step.DeriveStep d -> List.of(d.as());
            case Step.AggregateStep a -> a.measures().stream().map(Step.AggregateMeasure::as).toList();
            case Step.ProjectStep p -> p.columns().stream().map(Step.ProjectColumn::as).toList();
            default -> List.of();
        };
    }

    private static void collect(Expr e, List<String> out) {
        switch (e) {
            case Expr.ColRef c -> out.add(c.col());
            case Expr.AddExpr a -> {
                collect(a.a(), out);
                collect(a.b(), out);
            }
            case Expr.MonthOfExpr m -> out.add(m.col());
            case Expr.ConcatExpr c -> c.parts().forEach(p -> collect(p, out));
            default -> { }
        }
    }

    private static void collect(Predicate p, List<String> out) {
        switch (p) {
            case Predicate.IsNullPredicate n -> out.add(n.col());
            case Predicate.EqPredicate eq -> {
                if (eq.left() instanceof Predicate.ValueRef.ColRef c) out.add(c.col());
            }
            case Predicate.LtPredicate lt -> {
                if (lt.left() instanceof Predicate.ValueRef.ColRef c) out.add(c.col());
                if (lt.right() instanceof Predicate.ValueRef.ColRef c) out.add(c.col());
            }
            default -> { }
        }
    }
}
