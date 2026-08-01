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
import com.sheaf.domain.ir.Plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Build-time tool: generates JSON Schema from the IR Java classes and writes it to
 * {@code contract/schema/plan.schema.json}. Invoked by the Maven exec plugin during
 * the {@code prepare-package} phase.
 *
 * <p>CI diffs the committed snapshot against a fresh generation; a divergence means
 * a Java record changed without a corresponding TypeScript type regeneration.
 */
public class SchemaGeneratorMain {

    public static void main(String[] args) throws IOException {
        Path outputDir = args.length > 0
                ? Path.of(args[0])
                : Path.of("../contract/schema");

        Files.createDirectories(outputDir);

        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(new JacksonModule());

        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator generator = new SchemaGenerator(config);

        JsonNode schemaJson = generator.generateSchema(Plan.class);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path outputFile = outputDir.resolve("plan.schema.json");
        mapper.writeValue(outputFile.toFile(), schemaJson);

        System.out.println("[schema-gen] Written: " + outputFile.toAbsolutePath());
    }
}
