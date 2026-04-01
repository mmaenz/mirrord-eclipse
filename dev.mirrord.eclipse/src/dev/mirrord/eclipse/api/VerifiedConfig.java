package dev.mirrord.eclipse.api;

import dev.mirrord.eclipse.util.SimpleJsonParser;

import java.util.List;
import java.util.Map;

/**
 * Result of {@code mirrord verify-config --ide <path>}.
 *
 * <pre>
 * Success: {"type":"Success","config":{"path":{"deployment":"app","container":"app"},"namespace":"default"},"warnings":[]}
 * Fail:    {"type":"Fail","errors":["error text"]}
 * </pre>
 */
public class VerifiedConfig {

    public enum Kind { SUCCESS, FAIL }

    public final Kind kind;

    /** Warnings to display to the user (Success case). May be empty. */
    public final List<String> warnings;

    /** Errors (Fail case). */
    public final List<String> errors;

    /** Whether a target path is set in the config (Success case). */
    public final boolean targetSet;

    /** Kubernetes namespace from config, or {@code null}. */
    public final String namespace;

    private VerifiedConfig(Kind kind, boolean targetSet, String namespace,
                           List<String> warnings, List<String> errors) {
        this.kind = kind;
        this.targetSet = targetSet;
        this.namespace = namespace;
        this.warnings = warnings != null ? warnings : List.of();
        this.errors = errors != null ? errors : List.of();
    }

    public boolean isSuccess() { return kind == Kind.SUCCESS; }

    public static VerifiedConfig fromJson(String json) {
        Map<String, Object> obj = SimpleJsonParser.parseObject(json);
        String type = SimpleJsonParser.getString(obj, "type");

        if ("Fail".equals(type)) {
            List<String> errors = SimpleJsonParser.getList(obj, "errors")
                    .stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .toList();
            return new VerifiedConfig(Kind.FAIL, false, null, List.of(), errors);
        }

        // Success
        List<String> warnings = SimpleJsonParser.getList(obj, "warnings")
                .stream()
                .filter(o -> o instanceof String)
                .map(o -> (String) o)
                .toList();

        Map<String, Object> config = SimpleJsonParser.getMap(obj, "config");
        String namespace = SimpleJsonParser.getString(config, "namespace");

        // target is set when "path" key exists and is non-null
        Object pathObj = config.get("path");
        boolean targetSet = (pathObj != null);

        return new VerifiedConfig(Kind.SUCCESS, targetSet, namespace, warnings, List.of());
    }
}
