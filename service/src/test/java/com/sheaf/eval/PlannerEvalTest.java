package com.sheaf.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheaf.adapters.llm.anthropic.AnthropicModel;
import com.sheaf.adapters.llm.gemini.GeminiModel;
import com.sheaf.adapters.llm.ollama.OllamaModel;
import com.sheaf.adapters.llm.openaicompat.OpenAiCompatibleModel;
import com.sheaf.application.planning.LanguageModelPort;
import com.sheaf.application.planning.Planner;
import com.sheaf.domain.validation.CorpusEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * The accuracy baseline (M5 exit criterion): every corpus question against a live model, scored and
 * written to target/eval/. Costs money, so it runs only when asked, and stops at a budget:
 *
 * <pre>mvn test -Dtest=PlannerEvalTest -Dsheaf.eval.model=anthropic/claude-sonnet-4.6 -Dsheaf.eval.budget=1.00</pre>
 *
 * Other providers: -Dsheaf.eval.provider=anthropic|openai|gemini|ollama (and -Dsheaf.eval.baseUrl=...
 * for an Ollama elsewhere). A local model costs nothing: the budget only guards paid providers.
 * Keys come from SHEAF_OPEN_ROUTER_API_KEY / SHEAF_ANTHROPIC_API_KEY / SHEAF_OPENAI_API_KEY /
 * SHEAF_GEMINI_API_KEY, in the environment or the repository's .env; they are never printed.
 */
@EnabledIfSystemProperty(named = "sheaf.eval.model", matches = ".+")
class PlannerEvalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Out of scope in corpus/questions.md: a guessed plan is a failure; clarify or refuse passes. */
    private static final Map<String, String> OUT_OF_SCOPE = Map.of(
            "q16", "running total of revenue by week",
            "q17", "what's selling well?",
            "q18", "show me this quarter's performance vs target",
            "q19", "which product categories will grow fastest next quarter?",
            "q20", "can you make the results table look nicer with better formatting?",
            "q32", "make the headers bold");

    private static String key(String variable) throws IOException {
        String env = System.getenv(variable);
        if (env != null && !env.isBlank()) return env;
        for (Path p : List.of(Path.of("..", ".env"), Path.of(".env"))) {
            if (!Files.exists(p)) continue;
            for (String line : Files.readAllLines(p)) {
                String t = line.strip();
                if (t.startsWith(variable + "=")) return t.substring(t.indexOf('=') + 1).strip();
            }
        }
        throw new IllegalStateException(variable + " is not set (environment or .env).");
    }

    private static LanguageModelPort open(String provider, String baseUrl) throws IOException {
        return switch (provider) {
            case "ollama" -> new OllamaModel(baseUrl.isEmpty() ? "http://localhost:11434" : baseUrl, Duration.ofSeconds(600));
            case "anthropic" -> new AnthropicModel("https://api.anthropic.com", key("SHEAF_ANTHROPIC_API_KEY"), Duration.ofSeconds(120));
            case "gemini" -> new GeminiModel("https://generativelanguage.googleapis.com", key("SHEAF_GEMINI_API_KEY"), Duration.ofSeconds(120));
            case "openai" -> new OpenAiCompatibleModel("openai", "https://api.openai.com/v1", key("SHEAF_OPENAI_API_KEY"), Duration.ofSeconds(120), true, true);
            default -> new OpenAiCompatibleModel("openrouter", "https://openrouter.ai/api/v1", key("SHEAF_OPEN_ROUTER_API_KEY"), Duration.ofSeconds(120), true, true);
        };
    }

    record Row(String id, String question, String expected, String got, boolean pass, int calls, double cost, String note) {}

    @Test
    void corpus_accuracy() throws IOException {
        String modelId = System.getProperty("sheaf.eval.model");
        double budget = Double.parseDouble(System.getProperty("sheaf.eval.budget", "1.00"));
        String only = System.getProperty("sheaf.eval.only", "");
        String provider = System.getProperty("sheaf.eval.provider", "openrouter");
        var model = open(provider, System.getProperty("sheaf.eval.baseUrl", ""));
        var planner = new Planner(model);
        var env = CorpusEnvironment.build();

        record Q(String id, String text, String expected, List<String> output) {}
        List<Q> questions = new ArrayList<>();
        try (var files = Files.list(Path.of("..", "corpus", "plans"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode g = JSON.readTree(f.toFile());
                List<String> out = StreamSupport.stream(g.path("expect").path("output").spliterator(), false)
                        .map(n -> n.asText().substring(0, n.asText().indexOf(": "))).toList();
                questions.add(new Q(f.getFileName().toString().replace(".json", ""), g.get("question").asText(), "plan", out));
            }
        }
        OUT_OF_SCOPE.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> questions.add(new Q(e.getKey(), e.getValue(), e.getKey().equals("q17") ? "clarify" : "refuse", List.of())));

        List<Row> rows = new ArrayList<>();
        double spent = 0;
        for (Q q : questions) {
            if (!only.isEmpty() && !List.of(only.split(",")).contains(q.id())) continue;
            if (spent >= budget) {
                rows.add(new Row(q.id(), q.text(), q.expected(), "skipped", false, 0, 0, "budget reached"));
                continue;
            }
            Planner.Outcome out;
            try {
                out = planner.plan(q.text(), env, Map.of(), modelId);
            } catch (LanguageModelPort.ModelException e) {
                rows.add(new Row(q.id(), q.text(), q.expected(), "error", false, 0, 0, e.kind() + ": " + e.getMessage()));
                if (e.kind() == LanguageModelPort.ModelException.Kind.NO_CREDIT || e.kind() == LanguageModelPort.ModelException.Kind.INVALID_KEY) break;
                continue;
            }
            double cost = out.run().cost() == null ? 0 : out.run().cost();
            spent += cost;
            String got = out.kind().name();
            boolean pass = q.expected().equals("plan") ? got.equals("plan") : (got.equals("clarify") || got.equals("refuse"));
            String note = "";
            if (got.equals("plan") && !q.output().isEmpty()) {
                var names = out.report().output().stream().map(c -> c.name()).toList();
                note = names.equals(q.output()) ? "same columns as golden" : "columns " + names;
            } else if (got.equals("failed")) {
                note = out.diagnostics().stream().map(d -> d.code().name() + " " + d.pointer()).toList().toString();
            } else if (got.equals("refuse")) {
                note = "understood: " + out.understood();
            } else if (got.equals("clarify")) {
                note = out.question();
            }
            if (!out.repairs().isEmpty()) note += " · repaired: " + out.repairs();
            rows.add(new Row(q.id(), q.text(), q.expected(), got, pass, out.run().calls(), cost, note));
        }

        long inScope = rows.stream().filter(r -> r.expected().equals("plan") && !r.got().equals("skipped")).count();
        long inScopePass = rows.stream().filter(r -> r.expected().equals("plan") && r.pass()).count();
        long outScope = rows.stream().filter(r -> !r.expected().equals("plan") && !r.got().equals("skipped")).count();
        long outScopePass = rows.stream().filter(r -> !r.expected().equals("plan") && r.pass()).count();
        var sb = new StringBuilder("# Planner accuracy: " + provider + " / " + modelId + " (" + com.sheaf.application.planning.PlannerPrompt.VERSION + ")\n\n");
        sb.append(String.format("In scope: %d/%d valid plans within %d repairs (%.0f%%). Out of scope: %d/%d clarified or refused. Cost: $%.4f.%n%n",
                inScopePass, inScope, Planner.MAX_REPAIRS, inScope == 0 ? 0.0 : 100.0 * inScopePass / inScope, outScopePass, outScope, spent));
        sb.append("| # | Question | Expected | Got | Pass | Calls | Cost | Note |\n|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %d | $%.4f | %s |%n", r.id(), r.question(), r.expected(), r.got(),
                    r.pass() ? "✓" : "✗", r.calls(), r.cost(), r.note().replace("|", "/")));
        }
        Path out = Path.of("target", "eval", provider + "_" + modelId.replace('/', '_').replace(':', '_') + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb);
    }
}
