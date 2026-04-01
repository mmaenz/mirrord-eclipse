package dev.mirrord.eclipse.config;

import dev.mirrord.eclipse.Activator;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helpers for discovering and resolving mirrord configuration files within Eclipse projects.
 *
 * <h3>Config file naming conventions (mirrored from the VSCode extension)</h3>
 * <ul>
 *   <li>{@code .mirrord/mirrord.json}</li>
 *   <li>{@code .mirrord/*.mirrord.{json,toml,yml,yaml}}</li>
 *   <li>{@code *mirrord.{json,toml,yml,yaml}} anywhere in the project</li>
 * </ul>
 */
public final class MirrordConfigManager {

    private static final String[] CONFIG_EXTENSIONS = {"json", "toml", "yml", "yaml"};
    private static final String MIRRORD_DIR = ".mirrord";

    private MirrordConfigManager() {}

    /**
     * Finds the default mirrord config file for a project.
     *
     * Searches the project's {@code .mirrord/} directory first (alphabetical order),
     * then falls back to any {@code *mirrord.*} file at the project root.
     *
     * @return absolute file-system path, or {@code null} if none found
     */
    public static String findDefaultConfig(IProject project) {
        if (project == null || !project.isAccessible()) return null;

        IPath projectPath = project.getLocation();
        if (projectPath == null) return null;

        File projectDir = projectPath.toFile();
        File mirrordDir = new File(projectDir, MIRRORD_DIR);

        // 1. .mirrord/ directory
        if (mirrordDir.isDirectory()) {
            File[] files = mirrordDir.listFiles(MirrordConfigManager::isMirrordConfigFile);
            if (files != null && files.length > 0) {
                Arrays.sort(files); // alphabetical → deterministic selection
                return files[0].getAbsolutePath();
            }
        }

        // 2. Project root
        File[] rootFiles = projectDir.listFiles(f ->
                f.isFile() && f.getName().contains("mirrord") && isMirrordConfigFile(f));
        if (rootFiles != null && rootFiles.length > 0) {
            Arrays.sort(rootFiles);
            return rootFiles[0].getAbsolutePath();
        }

        return null;
    }

    /**
     * Returns all mirrord config files found across all open workspace projects.
     */
    public static List<String> findAllConfigs() {
        List<String> results = new ArrayList<>();
        try {
            IWorkspace ws = ResourcesPlugin.getWorkspace();
            IWorkspaceRoot root = ws.getRoot();
            for (IProject project : root.getProjects()) {
                if (!project.isOpen()) continue;
                IPath loc = project.getLocation();
                if (loc == null) continue;
                collectMirrordConfigs(loc.toFile(), results);
            }
        } catch (Exception e) {
            Activator.logger().warn("Error scanning for mirrord configs: " + e.getMessage());
        }
        return results;
    }

    /**
     * Creates a default {@code .mirrord/mirrord.json} in the given project if one doesn't exist.
     *
     * @return absolute path to the created file, or {@code null} on failure
     */
    public static String createDefaultConfig(IProject project) {
        if (project == null) return null;
        IPath projectPath = project.getLocation();
        if (projectPath == null) return null;

        File mirrordDir = new File(projectPath.toFile(), MIRRORD_DIR);
        if (!mirrordDir.exists()) mirrordDir.mkdirs();

        File configFile = new File(mirrordDir, "mirrord.json");
        if (configFile.exists()) return configFile.getAbsolutePath();

        try {
            java.nio.file.Files.writeString(configFile.toPath(), DEFAULT_CONFIG_CONTENT);
            // Refresh Eclipse workspace so the file is visible
            try { project.refreshLocal(IResource.DEPTH_INFINITE, null); }
            catch (CoreException ignored) {}
            return configFile.getAbsolutePath();
        } catch (java.io.IOException e) {
            Activator.logger().error("Failed to create default mirrord config: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static void collectMirrordConfigs(File dir, List<String> results) {
        if (dir == null || !dir.isDirectory()) return;
        File mirrordDir = new File(dir, MIRRORD_DIR);
        if (mirrordDir.isDirectory()) {
            File[] files = mirrordDir.listFiles(MirrordConfigManager::isMirrordConfigFile);
            if (files != null) {
                for (File f : files) results.add(f.getAbsolutePath());
            }
        }
        File[] rootFiles = dir.listFiles(f ->
                f.isFile() && f.getName().contains("mirrord") && isMirrordConfigFile(f));
        if (rootFiles != null) {
            for (File f : rootFiles) results.add(f.getAbsolutePath());
        }
    }

    private static boolean isMirrordConfigFile(File f) {
        if (!f.isFile()) return false;
        String name = f.getName().toLowerCase();
        for (String ext : CONFIG_EXTENSIONS) {
            if (name.endsWith("." + ext)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Default config content (mirrors the VSCode extension default)
    // -----------------------------------------------------------------------

    private static final String DEFAULT_CONFIG_CONTENT = """
            {
              "feature": {
                "network": {
                  "incoming": "mirror",
                  "outgoing": true
                },
                "fs": "read",
                "env": true
              }
            }
            """;
}
