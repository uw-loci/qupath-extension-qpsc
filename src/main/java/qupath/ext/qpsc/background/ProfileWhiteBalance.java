package qupath.ext.qpsc.background;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * The white balance a background profile keeps beside its backgrounds.
 *
 * <p>A white balance depends on what is on the stage as much as a background does: the
 * carrier, the slide glass, the medium. So each background profile keeps one
 * {@value #FILE_NAME} in each white-balance-mode folder,
 * {@code <profile>/<detector>/<family>/<mag>/<per_angle|simple>/}, beside the backgrounds
 * collected with it. The calibration writes it (the server, given {@code --wb-folder});
 * acquisitions, background collection and the QPSC readers use it in place of the
 * scope-wide calibration in the imageprocessing YAML, which stays as the latest
 * calibration of any profile and the fallback when a profile has none.
 *
 * <p>Its {@code white_balance} block has the same keys as
 * {@code imaging_profiles.<modality>.<objective>.<detector>} in the imageprocessing YAML:
 * {@code exposures_ms}, {@code gains}, {@code wb_last_modified}, and for simple
 * {@code simple_wb}. Only PPM's per-angle and simple modes keep one.
 */
public final class ProfileWhiteBalance {

    private static final Logger logger = LoggerFactory.getLogger(ProfileWhiteBalance.class);

    /** The file in each white-balance-mode folder of a background profile. */
    public static final String FILE_NAME = "white_balance.yml";

    /** The detector-profile keys a profile's white balance replaces. */
    private static final Set<String> WB_KEYS = Set.of("exposures_ms", "gains", "simple_wb", "wb_last_modified");

    private record Cached(long modified, long length, Map<String, Object> block) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private ProfileWhiteBalance() {}

    /** Whether {@code key} is a white balance key a profile's file can hold. */
    public static boolean isWhiteBalanceKey(String key) {
        return key != null && WB_KEYS.contains(key);
    }

    /** Whether this modality and white balance mode keep a white balance per profile (PPM per-angle and simple). */
    public static boolean appliesTo(String modality, String wbMode) {
        if (modality == null || wbMode == null) {
            return false;
        }
        boolean ppm = "ppm".equals(BackgroundSettingsReader.modalityFamily(modality));
        return ppm && ("per_angle".equals(wbMode) || "simple".equals(wbMode));
    }

    /**
     * The folder a profile keeps this white balance in (the backgrounds' folder for the mode),
     * or null when it does not apply or the profile folder is unknown.
     *
     * @param profileFolder the active background profile's folder
     * @param wbMode the white balance mode's protocol name ("per_angle" or "simple")
     */
    public static String folder(
            String profileFolder, String modality, String objective, String detector, String wbMode) {
        if (profileFolder == null || !appliesTo(modality, wbMode)) {
            return null;
        }
        return BackgroundSettingsReader.resolveBackgroundFolder(profileFolder, modality, objective, detector, wbMode);
    }

    /** {@value #FILE_NAME} in that folder, or null. */
    public static File file(String profileFolder, String modality, String objective, String detector, String wbMode) {
        String folder = folder(profileFolder, modality, objective, detector, wbMode);
        return folder == null ? null : new File(folder, FILE_NAME);
    }

    /** Whether the profile has a white balance for this modality, objective, detector and mode. */
    public static boolean exists(
            String profileFolder, String modality, String objective, String detector, String wbMode) {
        File f = file(profileFolder, modality, objective, detector, wbMode);
        return f != null && f.isFile();
    }

    /**
     * The {@code white_balance} block of a profile's file, or null when there is no file or
     * it cannot be read. Cached until the file changes.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        String key = file.getAbsolutePath();
        long modified = file.lastModified();
        long length = file.length();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.modified() == modified && cached.length() == length) {
            return cached.block();
        }
        Map<String, Object> block = null;
        try (InputStream in = new FileInputStream(file)) {
            Object data = new Yaml().load(in);
            if (data instanceof Map<?, ?> map && map.get("white_balance") instanceof Map<?, ?> wb && !wb.isEmpty()) {
                block = (Map<String, Object>) wb;
            }
        } catch (Exception e) {
            logger.warn("Could not read the profile white balance {}: {}", file, e.getMessage());
        }
        CACHE.put(key, new Cached(modified, length, block));
        return block;
    }

    /** One value from a profile's white balance, by the same path as the imageprocessing YAML; null when absent. */
    public static Object value(File file, String... path) {
        Object current = load(file);
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    /**
     * Copies the scope-wide PPM white balance into a background profile, one
     * {@value #FILE_NAME} per objective, detector and mode, so an existing profile starts from
     * the calibration in use today instead of needing a new one. A file the profile already
     * has is left alone. Per-angle gets the scope-wide per-angle exposures and gains; simple
     * also its {@code simple_wb} block, and is skipped when there is none.
     *
     * @param scopeWide {@code imaging_profiles.ppm} of the imageprocessing YAML (objective -> detector -> settings)
     * @param profileFolder the profile's folder
     * @return the files written
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<File> copyScopeWide(Map<String, Object> scopeWide, String profileFolder) {
        java.util.List<File> written = new java.util.ArrayList<>();
        if (scopeWide == null || profileFolder == null) {
            return written;
        }
        for (var objectiveEntry : scopeWide.entrySet()) {
            if (!(objectiveEntry.getValue() instanceof Map<?, ?> detectors)) {
                continue;
            }
            for (var detectorEntry : ((Map<String, Object>) detectors).entrySet()) {
                if (!(detectorEntry.getValue() instanceof Map<?, ?> settings)
                        || !(settings.get("exposures_ms") instanceof Map<?, ?> exposures)
                        || exposures.isEmpty()) {
                    continue;
                }
                for (String mode : new String[] {"per_angle", "simple"}) {
                    if ("simple".equals(mode) && !(settings.get("simple_wb") instanceof Map<?, ?>)) {
                        continue;
                    }
                    File target = file(profileFolder, "ppm", objectiveEntry.getKey(), detectorEntry.getKey(), mode);
                    if (target == null || target.exists()) {
                        continue;
                    }
                    Map<String, Object> block = new java.util.LinkedHashMap<>();
                    for (String key : new String[] {"exposures_ms", "gains", "wb_last_modified"}) {
                        if (settings.get(key) != null) {
                            block.put(key, settings.get(key));
                        }
                    }
                    if ("simple".equals(mode)) {
                        block.put("simple_wb", settings.get("simple_wb"));
                    }
                    if (write(target, mode, objectiveEntry.getKey(), detectorEntry.getKey(), block)) {
                        written.add(target);
                    }
                }
            }
        }
        return written;
    }

    private static boolean write(
            File target, String mode, String objective, String detector, Map<String, Object> block) {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("version", 1);
        record.put("mode", mode);
        record.put("modality", "ppm");
        record.put("objective", objective);
        record.put("detector", detector);
        record.put("calibrated_at", block.getOrDefault("wb_last_modified", ""));
        record.put("copied_from", "scope-wide imageprocessing YAML");
        record.put("white_balance", block);
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                logger.warn("Could not create {}", parent);
                return false;
            }
            String text = "# White balance of this background profile, copied from the scope-wide calibration.\n"
                    + new Yaml(options).dump(record);
            java.nio.file.Files.writeString(target.toPath(), text, java.nio.charset.StandardCharsets.UTF_8);
            logger.info("Copied the scope-wide {} white balance into {}", mode, target);
            return true;
        } catch (Exception e) {
            logger.warn("Could not write {}: {}", target, e.getMessage());
            return false;
        }
    }
}
