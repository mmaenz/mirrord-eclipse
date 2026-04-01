package dev.mirrord.eclipse.binary;

import dev.mirrord.eclipse.Activator;
import dev.mirrord.eclipse.MirrordPluginConstants;
import dev.mirrord.eclipse.api.MirrordAPI;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages the mirrord binary: location resolution, version checking, and download.
 *
 * <h3>Resolution order (mirrors the VSCode extension)</h3>
 * <ol>
 *   <li>User-configured path in Eclipse preferences ({@code mirrord.binaryPath})</li>
 *   <li>System {@code PATH} ({@code which mirrord})</li>
 *   <li>Plugin state directory ({@code <workspace>/.metadata/.plugins/dev.mirrord.eclipse/mirrord})</li>
 *   <li>Download from GitHub releases if auto-update is enabled</li>
 * </ol>
 */
public class BinaryManager {

    private static final String BINARY_NAME = "mirrord";

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /**
     * Returns the path to a usable mirrord binary, downloading it if necessary.
     *
     * @param configuredPath custom binary path from the launch config (may be empty/null)
     * @param autoUpdate     whether to check for and download updates
     * @param monitor        progress monitor (may be null)
     * @return absolute path to the mirrord binary
     * @throws CoreException if the binary cannot be found or downloaded
     */
    public static String getMirrordBinary(String configuredPath,
                                          boolean autoUpdate,
                                          IProgressMonitor monitor) throws CoreException {
        SubMonitor sub = SubMonitor.convert(monitor, "Resolving mirrord binary", 30);

        // 1. User-configured path takes top priority
        if (configuredPath != null && !configuredPath.isBlank()) {
            File f = new File(configuredPath);
            if (!f.exists() || !f.canExecute()) {
                throw new CoreException(new Status(IStatus.ERROR,
                        MirrordPluginConstants.PLUGIN_ID,
                        "Configured mirrord binary not found or not executable: " + configuredPath));
            }
            sub.worked(30);
            return configuredPath;
        }

        // 2. Fetch latest version (or null if no network / not wanted)
        sub.subTask("Checking latest mirrord version...");
        String latestVersion = null;
        if (autoUpdate) {
            try {
                latestVersion = fetchLatestVersion();
                Activator.logger().info("Latest mirrord version: " + latestVersion);
            } catch (Exception e) {
                Activator.logger().warn("Could not fetch latest mirrord version: " + e.getMessage());
            }
        }
        sub.worked(10);

        // 3. Check system PATH
        sub.subTask("Looking for mirrord in PATH...");
        String pathBinary = findInPath();
        if (pathBinary != null) {
            if (latestVersion == null || versionMatches(pathBinary, latestVersion)) {
                sub.worked(20);
                return pathBinary;
            }
            // Version mismatch – fall through to download
            Activator.logger().info("mirrord in PATH is outdated; will download " + latestVersion);
        }
        sub.worked(5);

        // 4. Check plugin state dir for a cached binary
        sub.subTask("Checking cached mirrord binary...");
        String stateDir = Activator.stateLocation();
        String cachedPath = stateDir + File.separator + BINARY_NAME;
        File cached = new File(cachedPath);
        if (cached.exists() && cached.canExecute()) {
            if (latestVersion == null || versionMatches(cachedPath, latestVersion)) {
                sub.worked(15);
                return cachedPath;
            }
        }
        sub.worked(5);

        // 5. Download
        if (latestVersion == null) {
            throw new CoreException(new Status(IStatus.ERROR,
                    MirrordPluginConstants.PLUGIN_ID,
                    "mirrord binary not found. Please install mirrord or configure the binary path " +
                            "in Run Configurations → mirrord → Binary path."));
        }

        sub.subTask("Downloading mirrord " + latestVersion + "...");
        downloadBinary(latestVersion, cachedPath, sub.split(10));
        return cachedPath;
    }

    // -----------------------------------------------------------------------
    // Version fetching
    // -----------------------------------------------------------------------

    /**
     * Fetches the latest supported mirrord version from the version endpoint.
     * Response is a plain-text version string, e.g. {@code "3.68.0"}.
     */
    public static String fetchLatestVersion() throws IOException {
        String os = getOsPlatform();
        String pluginVersion = getPluginVersion();
        String url = MirrordPluginConstants.VERSION_ENDPOINT
                + "?source=1"
                + "&version=" + URLEncoder.encode(pluginVersion, StandardCharsets.UTF_8)
                + "&platform=" + URLEncoder.encode(os, StandardCharsets.UTF_8)
                + "&background=false";

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(3_000);
        conn.setRequestProperty("Accept", "text/plain");

        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("Version endpoint returned HTTP " + code);

        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    // -----------------------------------------------------------------------
    // Binary discovery helpers
    // -----------------------------------------------------------------------

    private static String findInPath() {
        // Try common locations and the PATH via ProcessBuilder
        String[] candidates = {"mirrord", "/usr/local/bin/mirrord", "/usr/bin/mirrord"};
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.isAbsolute() && f.exists() && f.canExecute()) return candidate;
        }

        // Use 'which' on Unix-like systems
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            try {
                Process p = new ProcessBuilder("which", "mirrord").start();
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!out.isBlank()) {
                    File f = new File(out);
                    if (f.exists() && f.canExecute()) return out;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean versionMatches(String binaryPath, String wanted) {
        try {
            MirrordAPI api = new MirrordAPI(binaryPath);
            String actual = api.getBinaryVersion();
            return wanted.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------------

    private static void downloadBinary(String version, String destPath, IProgressMonitor monitor)
            throws CoreException {
        String url = getDownloadUrl(version);
        Activator.logger().info("Downloading mirrord from: " + url);

        if (monitor != null) monitor.subTask("Downloading mirrord " + version + "...");

        try {
            // Follow redirects (GitHub releases use redirects)
            HttpURLConnection conn = openWithRedirects(url, 5);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new CoreException(new Status(IStatus.ERROR,
                        MirrordPluginConstants.PLUGIN_ID,
                        "Download failed with HTTP " + code + " from " + url));
            }

            Path dest = Path.of(destPath);
            Files.createDirectories(dest.getParent());

            long totalBytes = conn.getContentLengthLong();
            long downloaded = 0;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(destPath)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (monitor != null && totalBytes > 0) {
                        monitor.subTask(String.format("Downloading mirrord %s… %d%%",
                                version, (downloaded * 100) / totalBytes));
                    }
                }
            }

            // Make executable
            new File(destPath).setExecutable(true, false);
            Activator.logger().info("Downloaded mirrord " + version + " to " + destPath);

        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR,
                    MirrordPluginConstants.PLUGIN_ID,
                    "Failed to download mirrord binary: " + e.getMessage(), e));
        }
    }

    private static HttpURLConnection openWithRedirects(String url, int maxRedirects)
            throws IOException {
        String currentUrl = url;
        for (int i = 0; i < maxRedirects; i++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(currentUrl).toURL()
                    .openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) return conn;
            if (code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == 307 || code == 308) {
                currentUrl = conn.getHeaderField("Location");
                conn.disconnect();
                if (currentUrl == null) throw new IOException("Redirect with no Location header");
            } else {
                return conn; // Let caller handle non-200 codes
            }
        }
        throw new IOException("Too many redirects for " + url);
    }

    // -----------------------------------------------------------------------
    // Platform helpers
    // -----------------------------------------------------------------------

    private static String getDownloadUrl(String version) {
        String base = MirrordPluginConstants.GITHUB_RELEASE_BASE + "/" + version + "/";
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if (os.contains("mac")) {
            return base + "mirrord_mac_universal";
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            // Linux
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                return base + "mirrord_linux_aarch64";
            }
            return base + "mirrord_linux_x86_64";
        } else {
            // Windows is not supported by mirrord
            throw new UnsupportedOperationException(
                    "mirrord does not support Windows. Please use WSL.");
        }
    }

    private static String getOsPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac"))  return "darwin";
        if (os.contains("win"))  return "win32";
        return "linux";
    }

    private static String getPluginVersion() {
        // Read from bundle version; fall back to "unknown"
        try {
            return Activator.getDefault().getBundle().getVersion().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
