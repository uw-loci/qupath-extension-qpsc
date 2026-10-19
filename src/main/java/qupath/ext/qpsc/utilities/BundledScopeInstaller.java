package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Installs the microscope configurations that ship inside the QPSC JAR.
 *
 * <p>Two bundles exist. {@link Bundle#OFFLINE} is the "Offline / Analysis" placeholder
 * QPSC selects on a fresh install so the extension loads cleanly with no microscope and
 * no command server; it is marked {@code microscope.placeholder: true} and runs the menus
 * in analysis-only mode. {@link Bundle#DEMO} is the simulated microscope behind
 * <em>Connect to a Demo Microscope...</em>: a complete, acquisition-capable config whose
 * device names match Micro-Manager's Demo adapter, marked {@code microscope.demo: true}
 * and driven by {@code microscope-command-server --demo}.
 *
 * <p>Each bundle is extracted to its own folder under the QuPath user directory.
 * Extraction is idempotent: an existing config is never overwritten, so edits (and the
 * calibration data QPSC writes into these files) survive restarts and updates. The one
 * exception is a newer bundle: a config whose {@code microscope.bundle_version} is lower
 * than the one inside the JAR (absent counts as 1) cannot follow the new bundle's devices
 * and modalities, so its files are moved to a {@code previous-<timestamp>} folder beside
 * the new ones and the bundle is extracted again.
 */
public final class BundledScopeInstaller {

    private static final Logger logger = LoggerFactory.getLogger(BundledScopeInstaller.class);

    /** Classpath root of the bundled templates; each bundle lives in a subfolder named after it. */
    private static final String TEMPLATES_BASE = "/qupath/ext/qpsc/templates/";

    /** Filename of the hardware resources sidecar, under {@code resources/} next to a config. */
    public static final String RESOURCES_FILENAME = "resources_LOCI.yml";

    /** Classpath location of the shared LOCI resources template (the sidecar for bundles without their own). */
    private static final String RESOURCES_TEMPLATE = TEMPLATES_BASE + RESOURCES_FILENAME;

    /** {@code bundle_version: N} anywhere in a config (it lives in the microscope block). */
    private static final Pattern BUNDLE_VERSION =
            Pattern.compile("^\\s*bundle_version:\\s*(\\d+)\\s*(#.*)?$", Pattern.MULTILINE);

    /** A bundled microscope: where it unpacks to, what its files are called, and its sidecars. */
    public enum Bundle {
        /** The analysis-only placeholder selected on a fresh install. */
        OFFLINE("qpsc-offline-microscope", "Offline", List.of("imageprocessing_Offline.yml")),
        /** The simulated microscope used by Connect to a Demo Microscope. */
        DEMO("qpsc-demo-microscope", "Demo", List.of("imageprocessing_Demo.yml", "autofocus_Demo.yml"));

        private final String dirName;
        private final String suffix;
        private final List<String> sidecars;

        Bundle(String dirName, String suffix, List<String> sidecars) {
            this.dirName = dirName;
            this.suffix = suffix;
            this.sidecars = sidecars;
        }

        /** Folder under the QuPath user directory that holds this bundle. */
        public String dirName() {
            return dirName;
        }

        /** The {@code <Name>} in {@code config_<Name>.yml}; sidecars are resolved by this suffix. */
        public String suffix() {
            return suffix;
        }

        /** The main config filename. */
        public String configFileName() {
            return "config_" + suffix + ".yml";
        }

        /** Sidecar YAMLs shipped next to the config (imageprocessing, autofocus). */
        public List<String> sidecars() {
            return sidecars;
        }

        /** Classpath folder holding this bundle's files. */
        public String resourceBase() {
            return TEMPLATES_BASE + name().toLowerCase(Locale.ROOT) + "/";
        }

        /** Classpath location of this bundle's own hardware resources file, if it ships one. */
        public String ownResources() {
            return resourceBase() + RESOURCES_FILENAME;
        }
    }

    private BundledScopeInstaller() {}

    /**
     * What {@link #install} did: the config's path, and where an outdated bundle's files were
     * moved ({@code null} when nothing was replaced). A caller that already has this config
     * loaded must reload it when {@code setAside} is not null.
     */
    public record Installation(Path config, Path setAside) {}

    /**
     * Ensures {@code bundle} exists on disk and returns the path to its main config. Creates
     * the folder on first call; returns the existing path on later calls without overwriting.
     *
     * @param bundle which bundled microscope to install
     * @return absolute path to {@code config_<Name>.yml}
     * @throws IOException if the bundle cannot be written
     */
    public static Path ensureInstalled(Bundle bundle) throws IOException {
        return install(bundle).config();
    }

    /**
     * {@link #ensureInstalled}, also saying whether an outdated copy was replaced.
     *
     * @param bundle which bundled microscope to install
     * @return the config path and, if an older bundle was set aside, where it went
     * @throws IOException if the bundle cannot be written
     */
    public static Installation install(Bundle bundle) throws IOException {
        return installIn(bundle, resolveUserDir());
    }

    /** {@link #install} against an explicit user directory (tests). */
    static Installation installIn(Bundle bundle, Path userDir) throws IOException {
        Path dir = userDir.resolve(bundle.dirName());
        Files.createDirectories(dir);
        Path configPath = dir.resolve(bundle.configFileName());
        Path kept = null;

        if (Files.exists(configPath)) {
            int installed = bundleVersion(Files.readString(configPath));
            int shipped = bundleVersion(readResource(bundle.resourceBase() + bundle.configFileName()));
            if (installed >= shipped) {
                logger.debug("{} microscope config already present at {}", bundle, configPath);
                return new Installation(configPath.toAbsolutePath(), null);
            }
            kept = setAsidePrevious(bundle, dir);
            logger.warn(
                    "The {} microscope bundle in {} is version {}; this QPSC ships version {}. "
                            + "Its files were moved to {} and the new bundle was written.",
                    bundle,
                    dir,
                    installed,
                    shipped,
                    kept);
        }

        logger.info("Creating bundled {} microscope config at {}", bundle, configPath);

        copyResource(bundle.resourceBase() + bundle.configFileName(), configPath);
        for (String sidecar : bundle.sidecars()) {
            copyResource(bundle.resourceBase() + sidecar, dir.resolve(sidecar));
        }

        // resources/resources_LOCI.yml keeps the on-disk layout identical to real configs.
        // A bundle that ships its own resources file (the demo: its detector, objective and
        // stage live there, because the command server lets that file's id_* sections
        // replace the config's) gets that one; otherwise the shared LOCI template is copied
        // so no missing-sidecar warning fires, with a minimal stub if even that is absent.
        Path resourcesDir = dir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Path resourcesFile = resourcesDir.resolve("resources_LOCI.yml");
        if (!copyResourceIfPresent(bundle.resourceBase() + RESOURCES_FILENAME, resourcesFile)
                && !copyResourceIfPresent(RESOURCES_TEMPLATE, resourcesFile)) {
            Files.writeString(
                    resourcesFile,
                    "# LOCI shared hardware resources (bundled " + bundle.suffix() + " microscope)\n"
                            + "# The detector is defined inline in " + bundle.configFileName() + ",\n"
                            + "# so no entries are required here.\n"
                            + "id_stage: {}\n"
                            + "id_detector: {}\n"
                            + "id_objective: {}\n");
        }

        return new Installation(configPath.toAbsolutePath(), kept);
    }

    /**
     * Whether {@code path} is where this installer puts {@code bundle}'s config (the right
     * filename inside the right folder). Used to detect a stale selection whose backing file
     * has gone missing so it can be re-created, and to recognise the demo config.
     *
     * @param bundle the bundle to test against
     * @param path a configured microscope-config path (may be null/blank)
     * @return true if the path is that bundle's install location
     */
    public static boolean isBundleConfigPath(Bundle bundle, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Path p = Paths.get(path);
        Path parent = p.getParent();
        return p.getFileName() != null
                && bundle.configFileName().equals(p.getFileName().toString())
                && parent != null
                && parent.getFileName() != null
                && bundle.dirName().equals(parent.getFileName().toString());
    }

    /**
     * The {@code bundle_version} a config declares, or 1 when it declares none (every bundle
     * shipped before the key existed is version 1).
     */
    static int bundleVersion(String configText) {
        if (configText == null) {
            return 1;
        }
        Matcher m = BUNDLE_VERSION.matcher(configText);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    /**
     * Moves an outdated bundle's files (config, sidecars, resources file) into a new
     * {@code previous-<timestamp>} folder inside {@code dir}; anything else in the folder,
     * such as data written there, stays where it is.
     */
    private static Path setAsidePrevious(Bundle bundle, Path dir) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path kept = dir.resolve("previous-" + stamp);
        Files.createDirectories(kept);
        List<String> names = new ArrayList<>();
        names.add(bundle.configFileName());
        names.addAll(bundle.sidecars());
        for (String name : names) {
            Path f = dir.resolve(name);
            if (Files.exists(f)) {
                Files.move(f, kept.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path resources = dir.resolve("resources").resolve(RESOURCES_FILENAME);
        if (Files.exists(resources)) {
            Files.createDirectories(kept.resolve("resources"));
            Files.move(resources, kept.resolve("resources").resolve(RESOURCES_FILENAME));
        }
        return kept;
    }

    /** A bundled text resource, or null if absent. */
    private static String readResource(String resource) throws IOException {
        try (InputStream is = BundledScopeInstaller.class.getResourceAsStream(resource)) {
            return is == null ? null : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Resolve the QuPath user directory, with sensible fallbacks. */
    private static Path resolveUserDir() {
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
        return userPath;
    }

    /** Copy a required bundled resource to {@code dest}, failing if it is missing. */
    private static void copyResource(String resource, Path dest) throws IOException {
        if (!copyResourceIfPresent(resource, dest)) {
            throw new IOException("Bundled resource not found on classpath: " + resource);
        }
    }

    /** Copy a bundled resource if present; returns false if the resource is absent. */
    private static boolean copyResourceIfPresent(String resource, Path dest) throws IOException {
        try (InputStream is = BundledScopeInstaller.class.getResourceAsStream(resource)) {
            if (is == null) {
                return false;
            }
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("Wrote {}", dest);
            return true;
        }
    }
}
