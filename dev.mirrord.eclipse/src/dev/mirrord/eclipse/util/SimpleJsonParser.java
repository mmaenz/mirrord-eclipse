package dev.mirrord.eclipse.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser.
 *
 * Returns:
 *   JSON object  → {@code Map<String, Object>}
 *   JSON array   → {@code List<Object>}
 *   JSON string  → {@code String}
 *   JSON number  → {@code Double}
 *   JSON boolean → {@code Boolean}
 *   JSON null    → {@code null}
 *
 * Only handles the simple, well-formed JSON structures produced by the mirrord CLI.
 * Not a fully standards-compliant parser (no Unicode escape, no float edge cases).
 */
public final class SimpleJsonParser {

    private final String src;
    private int pos;

    private SimpleJsonParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    /** Parse a JSON string and return the root value. */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        return new SimpleJsonParser(json.trim()).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object result = parse(json);
        return result instanceof Map ? (Map<String, Object>) result : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        Object result = parse(json);
        return result instanceof List ? (List<Object>) result : List.of();
    }

    // ------------------------------------------------------------------
    // Typed accessor helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static String getString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof String ? (String) v : null;
    }

    public static boolean getBoolean(Map<String, Object> obj, String key, boolean def) {
        Object v = obj.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    // ------------------------------------------------------------------
    // Core parser
    // ------------------------------------------------------------------

    private Object parseValue() {
        skipWhitespace();
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseJsonObject();
            case '[' -> parseJsonArray();
            case '"' -> parseString();
            case 't' -> { pos += 4; yield Boolean.TRUE; }
            case 'f' -> { pos += 5; yield Boolean.FALSE; }
            case 'n' -> { pos += 4; yield null; }
            default  -> parseNumber();
        };
    }

    private Map<String, Object> parseJsonObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') { pos++; return map; }
        while (pos < src.length()) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char next = peek();
            if (next == '}') { pos++; break; }
            if (next == ',') { pos++; }
        }
        return map;
    }

    private List<Object> parseJsonArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') { pos++; return list; }
        while (pos < src.length()) {
            list.add(parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ']') { pos++; break; }
            if (next == ',') { pos++; }
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < src.length()) {
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'u'  -> {
                        // Basic 4-hex Unicode escape
                        if (pos + 4 <= src.length()) {
                            String hex = src.substring(pos, pos + 4);
                            try { sb.append((char) Integer.parseInt(hex, 16)); }
                            catch (NumberFormatException ignored) { sb.append('?'); }
                            pos += 4;
                        }
                    }
                    default   -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object parseNumber() {
        int start = pos;
        // consume optional sign
        if (pos < src.length() && src.charAt(pos) == '-') pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E'
                    || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String numStr = src.substring(start, pos);
        try {
            return Double.parseDouble(numStr);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private void expect(char c) {
        if (pos < src.length() && src.charAt(pos) == c) { pos++; }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : 0;
    }
}
