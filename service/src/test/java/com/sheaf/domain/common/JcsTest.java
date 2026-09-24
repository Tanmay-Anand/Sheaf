package com.sheaf.domain.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 8785 canonical JSON, checked against the RFC's own examples and ECMAScript number formatting. */
class JcsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String jcs(String json) throws Exception {
        return Jcs.canonicalize(JSON.readTree(json));
    }

    @Test
    void members_are_sorted_by_utf16_code_units_and_whitespace_is_dropped() throws Exception {
        assertThat(jcs("{ \"b\": 1, \"a\": [ true, null ], \"A\": {} }")).isEqualTo("{\"A\":{},\"a\":[true,null],\"b\":1}");
        // RFC 8785 §3.2.3: by UTF-16 code units, so U+20AC sorts after U+0080 and before U+1F600 (a surrogate pair).
        String input = "{\"😀\":1,\"€\":2,\"\u0080\":3,\"1\":4}";
        assertThat(jcs(input)).isEqualTo("{\"1\":4,\"\u0080\":3,\"€\":2,\"😀\":1}");
    }

    @Test
    void strings_are_escaped_as_json_stringify_does() throws Exception {
        String input = "\"\\u0000\\b\\f\\n\\r\\t\\u001f\\\"\\\\/é\"";
        assertThat(jcs(input)).isEqualTo("\"\\u0000\\b\\f\\n\\r\\t\\u001f\\\"\\\\/é\"");
    }

    @Test
    void numbers_are_written_as_ecmascript_writes_a_double() throws Exception {
        assertThat(jcs("[0, -0, 1.0, 100, 1e2, 0.1, 1e21, 1.5e-7, 0.000001, 123456789012345680000, 9007199254740991, 2e-3]"))
                .isEqualTo("[0,0,1,100,100,0.1,1e+21,1.5e-7,0.000001,123456789012345680000,9007199254740991,0.002]");
    }

    @Test
    void the_rfc_number_examples_canonicalise_exactly() throws Exception {
        // RFC 8785 §3.2.2, the "numbers" member of the sample input.
        assertThat(jcs("{\"numbers\": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001]}"))
                .isEqualTo("{\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27]}");
    }
}
