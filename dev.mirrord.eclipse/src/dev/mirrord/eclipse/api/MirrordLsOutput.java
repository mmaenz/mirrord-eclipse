package dev.mirrord.eclipse.api;

import java.util.List;

/**
 * Full output of {@code mirrord ls}, including targets and namespace info.
 *
 * Older CLI versions return only an array of path strings; the parser converts that to this form
 * with every target marked available and no namespace information.
 */
public class MirrordLsOutput {

    /** Targets found in the queried namespace. Never null, may be empty. */
    public final List<MirrordTarget> targets;

    /** The namespace that was queried. {@code null} if the CLI does not support namespace listing. */
    public final String currentNamespace;

    /** All namespaces visible to the user. {@code null} if not supported by the CLI version. */
    public final List<String> namespaces;

    public MirrordLsOutput(List<MirrordTarget> targets, String currentNamespace, List<String> namespaces) {
        this.targets = targets != null ? targets : List.of();
        this.currentNamespace = currentNamespace;
        this.namespaces = namespaces != null ? namespaces : List.of();
    }

    /**
     * Parses the JSON output of {@code mirrord ls}.
     *
     * Handles both formats:
     * <ul>
     *   <li>New: {@code {"targets":[{"path":"…","available":true}],"current_namespace":"…","namespaces":["…"]}}</li>
     *   <li>Old: {@code ["deployment/app","pod/xyz"]}  (array of plain strings)</li>
     * </ul>
     */
    public static MirrordLsOutput fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new MirrordLsOutput(List.of(), null, null);
        }

        Object parsed = dev.mirrord.eclipse.util.SimpleJsonParser.parse(json.trim());

        if (parsed instanceof List<?> rawList) {
            // Old format: plain string array
            List<MirrordTarget> targets = rawList.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> new MirrordTarget((String) o, true))
                    .toList();
            return new MirrordLsOutput(targets, null, null);
        }

        if (parsed instanceof java.util.Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> obj = (java.util.Map<String, Object>) rawMap;

            // targets
            List<MirrordTarget> targets = dev.mirrord.eclipse.util.SimpleJsonParser
                    .getList(obj, "targets")
                    .stream()
                    .filter(o -> o instanceof java.util.Map<?, ?>)
                    .map(o -> {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> t = (java.util.Map<String, Object>) o;
                        String path = dev.mirrord.eclipse.util.SimpleJsonParser.getString(t, "path");
                        boolean available = dev.mirrord.eclipse.util.SimpleJsonParser
                                .getBoolean(t, "available", true);
                        return new MirrordTarget(path != null ? path : "", available);
                    })
                    .toList();

            // namespaces
            List<String> namespaces = dev.mirrord.eclipse.util.SimpleJsonParser
                    .getList(obj, "namespaces")
                    .stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .toList();

            String currentNs = dev.mirrord.eclipse.util.SimpleJsonParser
                    .getString(obj, "current_namespace");

            return new MirrordLsOutput(targets, currentNs, namespaces.isEmpty() ? null : namespaces);
        }

        return new MirrordLsOutput(List.of(), null, null);
    }
}
