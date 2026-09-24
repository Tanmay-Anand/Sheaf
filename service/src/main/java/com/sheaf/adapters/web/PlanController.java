package com.sheaf.adapters.web;

import com.sheaf.domain.ir.Bindings;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlanHasher;
import com.sheaf.domain.ir.PlanMeta;
import com.sheaf.domain.ir.Predicate;
import com.sheaf.domain.ir.Sink;
import com.sheaf.domain.ir.Step;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
class PlanController {

    /**
     * Thin vertical slice until the planner lands (M5): accepts any question, ignores it, and returns
     * a hardcoded plan in the v1.2 response shape — the hashed envelope, its hash, and provenance
     * kept outside the hash.
     */
    @PostMapping(value = "/plan",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PlanRecord plan(@RequestBody PlanRequest request) {
        var envelope = PlanEnvelope.of(hardcodedQ1Plan());
        return new PlanRecord(envelope, PlanHasher.hash(envelope), new PlanMeta("hardcoded-m1", "0.0.1", Instant.now()));
    }

    // Q1 from corpus/questions.md: "total revenue by region"
    private Plan hardcodedQ1Plan() {
        var predicate = new Predicate.NePredicate(
                new Predicate.ValueRef.ColRef("status"),
                new Predicate.ValueRef.Lit("returned", null)
        );

        var steps = List.<Step>of(
                new Step.FilterStep(predicate),
                new Step.AggregateStep(
                        List.of("region"),
                        List.of(new Step.AggregateMeasure(Step.AggFn.sum, "amount", null, "revenue"))
                ),
                new Step.SortStep(List.of(new Step.SortKey("revenue", Step.SortDir.desc)))
        );

        // No catalog is bound yet at this stage, so the bindings are empty.
        return new Plan.QueryPlan("Orders", steps, new Sink.NewSheetSink("Analysis", "A1"), List.of(), new Bindings(List.of()));
    }

    record PlanRequest(String question) {}

    /** A plan as the service hands it out: what it is, its identity, and where it came from. */
    record PlanRecord(PlanEnvelope envelope, String planHash, PlanMeta meta) {}
}
