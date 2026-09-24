package com.sheaf.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.sheaf.application.CheckReport;
import com.sheaf.domain.catalog.WorkbookCatalog;
import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.commit.CommitRequest;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlannerResponse;
import com.sheaf.domain.ir.UnboundPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Build-time tool: generates JSON Schema from the domain records Java owns and writes them to
 * {@code contract/schema/}. Invoked by the Maven exec plugin during {@code prepare-package}.
 *
 * <p>Every record component is {@code required} unless annotated {@link Nullable}, which is
 * what makes the generated TypeScript types strict. Domain types get their simple class name as
 * {@code title} so the generated TypeScript interfaces carry readable names.
 *
 * <p>CI diffs the committed snapshot against a fresh generation; a divergence means a Java
 * record changed without a corresponding TypeScript type regeneration.
 */
public class SchemaGeneratorMain {

    /**
     * Every contract document, by file name. The unbound plan and the planner response are what a
     * model may write; the envelope (bound plan) is what is stored and run; the commit request is what
     * the user decides; the check report is what the service answers when it checks a plan; the
     * catalog is what the pane knows about the workbook.
     */
    private static final Map<String, Class<?>> ROOTS = Map.of(
            "plan.schema.json", PlanEnvelope.class,
            "unbound-plan.schema.json", UnboundPlan.class,
            "planner-response.schema.json", PlannerResponse.class,
            "commit-request.schema.json", CommitRequest.class,
            "check-report.schema.json", CheckReport.class,
            "catalog.schema.json", WorkbookCatalog.class
    );

    public static void main(String[] args) throws IOException {
        Path outputDir = args.length > 0
                ? Path.of(args[0])
                : Path.of("../contract/schema");

        Files.createDirectories(outputDir);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        // Sorted so the output order (and the build log) is deterministic.
        for (String fileName : ROOTS.keySet().stream().sorted().toList()) {
            Path outputFile = outputDir.resolve(fileName);
            mapper.writeValue(outputFile.toFile(), schemaFor(ROOTS.get(fileName)));
            System.out.println("[schema-gen] Written: " + outputFile.toAbsolutePath());
        }
    }

    /** The JSON Schema for one root type, exactly as written to the contract. */
    public static JsonNode schemaFor(Class<?> root) {
        JsonNode schemaJson = GENERATOR.generateSchema(root);
        // A sealed root (Plan = QueryPlan | EditPlan) is an anyOf with no title of its own;
        // name it so the generated TypeScript type is Plan, not PlanSchema.
        if (schemaJson instanceof ObjectNode o && !o.has("title")) o.put("title", root.getSimpleName());
        return schemaJson;
    }

    private static final SchemaGenerator GENERATOR = generator();

    private static SchemaGenerator generator() {
        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(new JacksonModule(JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE));

        configBuilder.forFields()
                .withRequiredCheck(field -> field.getAnnotationConsideringFieldAndGetter(Nullable.class) == null);

        configBuilder.forTypesInGeneral()
                .withTitleResolver(scope -> {
                    Class<?> type = scope.getType().getErasedType();
                    return type.getName().startsWith("com.sheaf.domain.") ? type.getSimpleName() : null;
                });

        SchemaGeneratorConfig config = configBuilder.build();
        return new SchemaGenerator(config);
    }
}
