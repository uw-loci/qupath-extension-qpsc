package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Installs the bundled "Offline / Analysis" placeholder microscope configuration.
 *
 * <p>On a fresh install QPSC has no microscope config selected, which makes
 * configuration validation fail and disables every workflow behind a warning
 * dialog. To let the extension install cleanly with no microscope and no command
 * server attached, this helper extracts a self-contained placeholder config
 * (bundled in the JAR) to the QuPath user directory so the extension has a valid
 * config to point at. The placeholder marks itself with
 * {@code microscope.placeholder: true}; QPSC reads that flag
 * ({@link MicroscopeConfigManager#isOfflineScope()}) to run in analysis-only mode
 * with hardware/acquisition menus disabled.
 *
 * <p>Extraction is idempotent: an existing {@code config_Offline.yml} is never
 * overwritten, so any edits the user makes survive restarts.
 */
public final class OfflineScopeInstaller {

    private static final Logger logger = LoggerFactory.getLogger(OfflineScopeInstaller.class);

    /** Subdirectory of the QuPath user directory that holds the offline config bundle. */
    static final String OFFLINE_DIR_NAME = "qpsc-offline-microscope";

    /** Bundled config filename. Its "Offline" suffix drives sidecar resolution. */
    static final String CONFIG_FILENAME = "config_Offline.yml";

    /** Classpath location of the bundled offline config resources. */
    private static final String BUNDLE_BASE = "/qupath/ext/qpsc/templates/offline/";

    /** Classpath location of the shared LOCI resources template (reused for the sidecar). */
    private static final String RESOURCES_TEMPLATE = "/qupath/ext/qpsc/templates/resources_LOCI.yml";

    private OfflineScopeInstaller() {}

    /**
     * Ensures the offline placeholder config exists on disk and returns the path to
     * {@code config_Offline.yml}. Creates the bundle on first call; returns the
     * existing path on subsequent calls without overwriting.
     *
     * @return absolute path to the offline {@code config_Offline.yml}
     * @throws IOException if the bundle cannot be written
     */
    public static Path ensureInstalled() throws IOException {
        Path dir = resolveOfflineDir();
        Files.createDirectories(dir);
        Path configPath = dir.resolve(CONFIG_FILENAME);

        if (Files.exists(configPath)) {
            logger.debug("Offline microscope config already present at {}", configPath);
            return configPath.toAbsolutePath();
        }

        logger.info("Creating offline / analysis placeholder microscope config at {}", configPath);

        // Main config + imageprocessing sidecar (sidecar name must match the
        // "Offline" suffix so MicroscopeConfigManager resolves it).
        copyResource(BUNDLE_BASE + CONFIG_FILENAME, configPath);
        copyResource(BUNDLE_BASE + "imageprocessing_Offline.yml", dir.resolve("imageprocessing_Offline.yml"));

        // resources/resources_LOCI.yml keeps the on-disk layout identical to real
        // configs. The offline detector is defined inline in the config, so the
        // shared resources file is not strictly required, but copying it avoids a
        // missing-sidecar warning. A minimal stub is fine if the template is absent.
        Path resourcesDir = dir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Path resourcesFile = resourcesDir.resolve("resources_LOCI.yml");
        if (!copyResourceIfPresent(RESOURCES_TEMPLATE, resourcesFile)) {
            Files.writeString(
                    resourcesFile,
                    "# LOCI shared hardware resources (offline placeholder)\n"
                            + "# The offline microscope defines its detector inline in config_Offline.yml,\n"
                            + "# so no entries are required here.\n"
                            + "id_stage: {}\n"
                            + "id_detector: {}\n"
                            + "id_objective: {}\n");
        }

        return configPath.toAbsolutePath();
    }

    /**
     * Whether {@code path} looks like the offline placeholder config this installer
     * manages (file named {@code config_Offline.yml} inside a
     * {@code qpsc-offline-microscope} directory). Used to detect a stale selection
     * whose backing file has gone missing so it can be re-created.
     *
     * @param path a configured microscope-config path (may be null/blank)
     * @return true if the path is the offline placeholder config location
     */
    public static boolean isOfflineConfigPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Path p = Paths.get(path);
        Path parent = p.getParent();
        return p.getFileName() != null
                && CONFIG_FILENAME.equals(p.getFileName().toString())
                && parent != null
                && parent.getFileName() != null
                && OFFLINE_DIR_NAME.equals(parent.getFileName().toString());
    }

    /** Resolve {@code <QuPath user dir>/qpsc-offline-microscope}, with sensible fallbacks. */
    private static Path resolveOfflineDir() {
        Path userPath = null;
        try {
            UserDirectoryManager udm = UserDirectoryManager.getInstance();
            if (udm != null) {
                userPath = udm.getUserPath();
            }
        } catch (Exception e) {
            logger.debug("UserDirectoryManager unavailable: {}", e.getMessage());
        }
        if (userPath == null) {
            try {
                userPath = PathPrefs.getDefaultQuPathUserDirectory();
            } catch (Exception e) {
                logger.debug("Default QuPath user directory unavailable: {}", e.getMessage());
            }
        }
        if (userPath == null) {
            userPath = Paths.get(System.getProperty("user.home", "."), "QuPath");
        }
        return userPath.resolve(OFFLINE_DIR_NAME);
    }

    /** Copy a required bundled resource to {@code dest}, failing if it is missing. */
    private static void copyResource(String resource, Path dest) throws IOException {
        if (!copyResourceIfPresent(resource, dest)) {
            throw new IOException("Bundled offline resource not found on classpath: " + resource);
        }
    }

    /** Copy a bundled resource if present; returns false if the resource is absent. */
    private static boolean copyResourceIfPresent(String resource, Path dest) throws IOException {
        try (InputStream is = OfflineScopeInstaller.class.getResourceAsStream(resource)) {
            if (is == null) {
                return false;
            }
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("Wrote {}", dest);
            return true;
        }
    }
}
