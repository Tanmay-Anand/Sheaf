package com.sheaf.domain.validation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.Expr;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.PlannerResponse;
import com.sheaf.domain.ir.UnboundPlan;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sheaf.domain.validation.DiagnosticCode.*;

/**
 * JSON → IR records, or diagnostics saying exactly what is wrong and where (as a JSON Pointer).
 * The wire format is the one the contract schema describes; unknown fields are rejected rather than
 * ignored, so a misspelled field never silently becomes a default. Every record component not
 * marked {@link Nullable} is required, numbers included (they are boxed, so an absent one is
 * reported, never read as 0).
 */
public final class PlanParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private PlanParser() {}

    public record ParseResult<T>(@Nullable T value, List<Diagnostic> diagnostics) {
        public ParseResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** What the model writes. */
    public static ParseResult<UnboundPlan> parseUnbound(String json) {
        return parse(json, UnboundPlan.class);
    }

    /** A stored or replayed plan. */
    public static ParseResult<Plan> parsePlan(String json) {
        return parse(json, Plan.class);
    }

    /** A model's whole answer: plan, clarify or refuse. */
    public static ParseResult<PlannerResponse> parseResponse(String json) {
        return parse(json, PlannerResponse.class);
    }

    public static <T> ParseResult<T> parse(String json, Class<T> type) {
        T value;
        try {
            value = MAPPER.readValue(json, type);
        } catch (InvalidTypeIdException e) {
            String path = path(e);
            String id = e.getTypeId();
            return fail(id == null ? E_PARSE_MISSING_FIELD : E_PARSE_UNKNOWN_VARIANT, path,
                    id == null ? "'" + path + "' is missing its discriminator (kind, op, mode, type, at or response)."
                            : "'" + id + "' is not a known variant at '" + path + "'.",
                    "Use one of the variants the contract schema lists for this position.", "received", id);
        } catch (UnrecognizedPropertyException e) {
            String path = path(e);
            return fail(E_PARSE_UNKNOWN_FIELD, path, "'" + e.getPropertyName() + "' is not a field here.",
                    "Allowed fields: " + e.getKnownPropertyIds() + ".", "received", e.getPropertyName(),
                    "candidates", e.getKnownPropertyIds() == null ? null : Diags.candidates(e.getPropertyName(),
                            e.getKnownPropertyIds().stream().map(String::valueOf).toList()));
        } catch (InvalidFormatException e) {
            String path = path(e);
            String allowed = e.getTargetType() != null && e.getTargetType().isEnum()
                    ? " Allowed values: " + Arrays.toString(e.getTargetType().getEnumConstants()) + "." : "";
            return fail(E_PARSE_INVALID_VALUE, path, "'" + e.getValue() + "' is not a valid value for '" + path + "'." + allowed,
                    "Use a value of the type the schema declares for this field." + allowed, "received", String.valueOf(e.getValue()));
        } catch (JsonMappingException e) {
            String path = path(e);
            return fail(E_PARSE_INVALID_VALUE, path, "The value at '" + path + "' has the wrong shape: " + e.getOriginalMessage(),
                    "Check the field against the contract schema.");
        } catch (JsonProcessingException e) {
            return fail(E_PARSE_INVALID_JSON, "", "This is not valid JSON: " + e.getOriginalMessage(),
                    "Return a single JSON object and nothing else.");
        }

        List<Diagnostic> missing = new ArrayList<>();
        requireFields(value, "", missing);
        return new ParseResult<>(missing.isEmpty() ? value : null, missing);
    }

    private static <T> ParseResult<T> fail(DiagnosticCode code, String pointer, String message, String hint, Object... context) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        for (int i = 0; i + 1 < context.length; i += 2) if (context[i + 1] != null) ctx.put((String) context[i], context[i + 1]);
        return new ParseResult<>(null, List.of(new Diagnostic(code, pointer, null, null, message, hint, ctx)));
    }

    /** The failing location as a JSON Pointer, e.g. /steps/2/op. */
    private static String path(JsonMappingException e) {
        var sb = new StringBuilder();
        for (JsonMappingException.Reference r : e.getPath()) {
            if (r.getIndex() >= 0) sb.append('/').append(r.getIndex());
            else if (r.getFieldName() != null) sb.append('/').append(r.getFieldName().replace("~", "~0").replace("/", "~1"));
        }
        return sb.toString();
    }

    /** Every record component not marked {@link Nullable} must be present (non-null). */
    private static void requireFields(Object node, String pointer, List<Diagnostic> out) {
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) requireFields(list.get(i), pointer + "/" + i, out);
            return;
        }
        if (node == null || !node.getClass().isRecord()) return;
        for (RecordComponent rc : node.getClass().getRecordComponents()) {
            Object value;
            try {
                value = rc.getAccessor().invoke(node);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            String name = wireName(rc);
            String here = pointer + "/" + name;
            if (value == null) {
                if (rc.isAnnotationPresent(Nullable.class) || rc.getType().isPrimitive()) continue;
                if (node instanceof Expr.CaseExpr && name.equals("else")) {
                    out.add(new Diagnostic(E_CASE_NO_ELSE, here, null, "derive", "case expressions must have an else clause.",
                            "Add an 'else' value for rows that match no condition. Use a null literal if null is acceptable.", Map.of()));
                } else {
                    out.add(new Diagnostic(E_PARSE_MISSING_FIELD, here, null, null, "Required field '" + here + "' is missing.",
                            "Add '" + name + "'; the contract schema marks it required.", Map.of()));
                }
                continue;
            }
            requireFields(value, here, out);
        }
    }

    private static String wireName(RecordComponent rc) {
        // @JsonProperty doesn't target record components; it lands on the accessor.
        var p = rc.getAccessor().getAnnotation(JsonProperty.class);
        return p != null && !p.value().isEmpty() ? p.value() : rc.getName();
    }
}
