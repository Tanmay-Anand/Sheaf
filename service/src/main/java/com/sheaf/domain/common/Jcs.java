package com.sheaf.domain.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * RFC 8785 JSON Canonicalization Scheme: one byte-exact serialisation per JSON value, so a hash
 * over it identifies the value, not its formatting. Object members are sorted by UTF-16 code units,
 * there is no whitespace, strings are escaped as ECMAScript's JSON.stringify does, and numbers are
 * written as ECMAScript's Number.prototype.toString writes a double.
 */
public final class Jcs {

    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);

    private Jcs() {}

    public static String canonicalize(JsonNode node) {
        var sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    private static void write(JsonNode n, StringBuilder sb) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            sb.append("null");
        } else if (n.isBoolean()) {
            sb.append(n.booleanValue());
        } else if (n.isNumber()) {
            sb.append(number(n));
        } else if (n.isTextual()) {
            string(n.textValue(), sb);
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                write(n.get(i), sb);
            }
            sb.append(']');
        } else if (n.isObject()) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) fields.add(it.next());
            // String.compareTo compares UTF-16 code units, which is exactly RFC 8785's order.
            fields.sort(Map.Entry.comparingByKey());
            sb.append('{');
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sb.append(',');
                string(fields.get(i).getKey(), sb);
                sb.append(':');
                write(fields.get(i).getValue(), sb);
            }
            sb.append('}');
        } else {
            throw new IllegalArgumentException("Not a JSON value: " + n.getNodeType());
        }
    }

    /** ECMAScript JSON.stringify string escaping. */
    static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** ECMAScript Number.prototype.toString for the double value of the number. */
    static String number(JsonNode n) {
        if (n.isIntegralNumber()) {
            BigInteger v = n.bigIntegerValue();
            if (v.abs().compareTo(MAX_SAFE_INTEGER) <= 0) return v.toString();
        }
        return number(n.doubleValue());
    }

    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("JSON has no " + d);
        if (d == 0) return "0"; // also -0
        String sign = d < 0 ? "-" : "";
        // Since JDK 19, Double.toString yields the shortest digits that round-trip, like ECMAScript.
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int k = digits.length();
        int n = k - bd.scale(); // value = 0.digits × 10^n
        String out;
        if (k <= n && n <= 21) {
            out = digits + "0".repeat(n - k);
        } else if (0 < n && n <= 21) {
            out = digits.substring(0, n) + "." + digits.substring(n);
        } else if (-6 < n && n <= 0) {
            out = "0." + "0".repeat(-n) + digits;
        } else {
            int e = n - 1;
            String exp = (e < 0 ? "-" : "+") + Math.abs(e);
            out = (k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1)) + "e" + exp;
        }
        return sign + out;
    }
}
