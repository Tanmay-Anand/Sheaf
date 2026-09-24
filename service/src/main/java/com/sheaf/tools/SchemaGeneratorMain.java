package com.sheaf.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.sheaf.domain.catalog.WorkbookCatalog;
import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.Plan;

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

    private static final Map<String, Class<?>> ROOTS = Map.of(
            "plan.schema.json", Plan.class,
            "catalog.schema.json", WorkbookCatalog.class
    );

    public static void main(String[] args) throws IOException {
        Path outputDir = args.length > 0
                ? Path.of(args[0])
                : Path.of("../contract/schema");

        Files.createDirectories(outputDir);

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
        SchemaGenerator generator = new SchemaGenerator(config);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        // Sorted so the output order (and the build log) is deterministic.
        for (String fileName : ROOTS.keySet().stream().sorted().toList()) {
            JsonNode schemaJson = generator.generateSchema(ROOTS.get(fileName));
            Path outputFile = outputDir.resolve(fileName);
            mapper.writeValue(outputFile.toFile(), schemaJson);
            System.out.println("[schema-gen] Written: " + outputFile.toAbsolutePath());
        }
    }
}
