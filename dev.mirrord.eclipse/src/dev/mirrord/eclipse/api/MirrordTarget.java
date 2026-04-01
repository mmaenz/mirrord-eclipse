package dev.mirrord.eclipse.api;

/**
 * A single target returned by {@code mirrord ls}.
 *
 * <pre>
 * { "path": "deployment/my-app", "available": true }
 * </pre>
 */
public class MirrordTarget {

    /** Full target path, e.g. {@code "deployment/my-app"} or {@code "pod/my-app-abc-123"}. */
    public final String path;

    /**
     * Whether this target is currently available (running / not locked by another user).
     * Unavailable targets can still be selected but the agent may fail to attach.
     */
    public final boolean available;

    public MirrordTarget(String path, boolean available) {
        this.path = path;
        this.available = available;
    }

    /** Extracts the resource type prefix, e.g. {@code "deployment"} from {@code "deployment/app"}. */
    public String getType() {
        int slash = path.indexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }

    /** Extracts the resource name, e.g. {@code "app"} from {@code "deployment/app"}. */
    public String getName() {
        int slash = path.indexOf('/');
        return slash > 0 ? path.substring(slash + 1) : path;
    }

    @Override
    public String toString() {
        return path + (available ? "" : " (unavailable)");
    }
}
