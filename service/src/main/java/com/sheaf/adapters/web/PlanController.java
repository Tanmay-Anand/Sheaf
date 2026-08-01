package com.sheaf.adapters.web;

import com.sheaf.domain.ir.EntityRef;
import com.sheaf.domain.ir.Plan;
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
     * M1 thin vertical slice: accepts any question, ignores it, returns a hardcoded valid Plan.
     * No LLM, no Excel reads. Proves the round trip, the types, the CORS config, and the sideload.
     */
    @PostMapping(value = "/plan",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Plan plan(@RequestBody PlanRequest request) {
        return hardcodedQ1Plan();
    }

    // Q1 from corpus/questions.md: "total revenue by region"
    private Plan hardcodedQ1Plan() {
        var predicate = new Predicate.NePredicate(
                new Predicate.ValueRef.ColRef("status"),
                new Predicate.ValueRef.Lit("returned")
        );

        var steps = List.<Step>of(
                new Step.FilterStep(predicate),
                new Step.AggregateStep(
                        List.of("region"),
                        List.of(new Step.AggregateMeasure("sum", "amount", null, "revenue"))
                ),
                new Step.SortStep(List.of(new Step.SortKey("revenue", "desc")))
        );

        var sink = new Sink.NewSheetSink("Analysis", "A1");

        var meta = new PlanMeta(
                "sha256-placeholder-m1",
                "hardcoded-m1",
                "0.0.1",
                Instant.now()
        );

        return new Plan(new EntityRef("Orders"), steps, sink, meta);
    }

    record PlanRequest(String question) {}
}
