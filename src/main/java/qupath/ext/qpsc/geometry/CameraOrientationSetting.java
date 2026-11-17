package qupath.ext.qpsc.geometry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.config.BundledScopeInstaller;
import qupath.ext.qpsc.config.ConfigYamlEditor;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * The camera orientation of the camera in use, {@code hardware.camera_orientation.<detector>} in
 * the microscope config. A fact about how each camera is mounted, so it lives with the
 * microscope and with the camera: a QuPath that switches microscopes, or a microscope that
 * switches cameras, takes the right one along. Calibrate Directions writes it.
 *
 * <p>The orientation used to be one QuPath preference per install. The first time a config has
 * no value for the camera in use, that preference's value is copied in, once per config file;
 * after that the preference is not read.
 */
public final class CameraOrientationSetting {

    private static final Logger logger = LoggerFactory.getLogger(CameraOrientationSetting.class);

    /** Map keyed by detector id, under the {@code hardware} block. */
    public static final String BLOCK = "camera_orientation";

    /** Config files the old preference was copied into, separated by '|'. */
    static final String COPIED_KEY = "qpsc.cameraOrientationCopiedTo";

    /** The preference this setting replaced; read only for the one-time copy. */
    private static final StringProperty FORMER_PREFERENCE =
            PathPrefs.createPersistentPreference("cameraOrientationProperty", "NORMAL");

    private static final List<Runnable> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile String lastWarned = null;

    private CameraOrientationSetting() {}

    /**
     * The orientation of the camera in use. {@link CameraOrientation#NORMAL} when no config is
     * loaded, or when the config has no value for that camera (logged once).
     */
    public static CameraOrientation current() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            return CameraOrientation.NORMAL;
        }
        String detector = MicroscopeConfigManager.activeDetectorForWriting();
        CameraOrientation found = read(mgr.getSection("hardware", BLOCK), detector);
        if (found != null) {
            return found;
        }
        CameraOrientation copied = copyFormerPreferenceOnce(mgr, detector);
        if (copied != null) {
            return copied;
        }
        String key = mgr.getConfigPath() + "|" + detector;
        if (!key.equals(lastWarned)) {
            lastWarned = key;
            logger.warn(
                    "hardware.{}.{} is not set in {}; using NORMAL. Run Live Viewer > Calibrate Directions to set it.",
                    BLOCK,
                    detector == null ? "<camera in use not known yet>" : detector,
                    mgr.getConfigPath());
        }
        return CameraOrientation.NORMAL;
    }

    /** The orientation recorded for {@code detectorId}, else the camera in use's. */
    public static CameraOrientation forDetector(String detectorId) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr != null && detectorId != null) {
            CameraOrientation found = read(mgr.getSection("hardware", BLOCK), detectorId);
            if (found != null) {
                return found;
            }
        }
        return current();
    }

    /**
     * The value for {@code detector} in a {@code camera_orientation} block; with no camera named,
     * the only value when there is exactly one. Null when absent or not an orientation (pure; tested).
     */
    static CameraOrientation read(Map<String, Object> block, String detector) {
        if (block == null || block.isEmpty()) {
            return null;
        }
        Object value = detector != null
                ? block.get(detector)
                : block.size() == 1 ? block.values().iterator().next() : null;
        return parse(value == null ? null : value.toString());
    }

    /** The orientation named by {@code value} (case and surrounding space ignored), or null. */
    public static CameraOrientation parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CameraOrientation.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static CameraOrientation copyFormerPreferenceOnce(MicroscopeConfigManager mgr, String detector) {
        String configPath = mgr.getConfigPath();
        if (detector == null || configPath == null) {
            return null;
        }
        // The bundled demo and offline configs declare their own; the rig's value does not belong there.
        for (BundledScopeInstaller.Bundle bundle : BundledScopeInstaller.Bundle.values()) {
            if (BundledScopeInstaller.isBundleConfigPath(bundle, configPath)) {
                return null;
            }
        }
        List<String> done = Arrays.asList(
                PersistentPreferences.getStringPreference(COPIED_KEY, "").split("\\|"));
        if (done.contains(configPath)) {
            return null;
        }
        CameraOrientation former = parse(FORMER_PREFERENCE.get());
        if (former == null) {
            former = CameraOrientation.NORMAL;
        }
        try {
            write(mgr, detector, former);
            String list = PersistentPreferences.getStringPreference(COPIED_KEY, "");
            PersistentPreferences.setStringPreference(
                    COPIED_KEY, list.isEmpty() ? configPath : list + "|" + configPath);
            logger.info(
                    "Camera orientation {} copied from the QuPath preference into {} for {} (one time; the config "
                            + "holds it from now on)",
                    former,
                    configPath,
                    detector);
            return former;
        } catch (IOException e) {
            logger.warn("Could not copy the camera orientation into {}: {}", configPath, e.getMessage());
            return former;
        }
    }

    /**
     * Writes {@code orientation} for the camera in use to the active config, reloads it and tells
     * the listeners.
     *
     * @return true when the file changed
     * @throws IOException when no config is loaded, the camera in use is not known, or the write failed
     */
    public static boolean save(CameraOrientation orientation) throws IOException {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null || mgr.getConfigPath() == null) {
            throw new IOException("no microscope config is loaded");
        }
        String detector = MicroscopeConfigManager.activeDetectorForWriting();
        if (detector == null) {
            throw new IOException("which camera is in use is not known yet; connect to the microscope first");
        }
        boolean changed = write(mgr, detector, orientation);
        CHANGE_LISTENERS.forEach(Runnable::run);
        return changed;
    }

    private static boolean write(MicroscopeConfigManager mgr, String detector, CameraOrientation orientation)
            throws IOException {
        Path path = Path.of(mgr.getConfigPath());
        String[] block = {"hardware", BLOCK};
        ConfigYamlEditor.Result r = ConfigYamlEditor.setNestedChildScalar(path, block, detector, orientation.name());
        if (!r.changed && r.message.endsWith(" not found")) {
            r = ConfigYamlEditor.appendMapEntry(
                    path, new String[] {"hardware"}, BLOCK, Map.of(detector, orientation.name()));
        }
        logger.info("Camera orientation for {} written to {}: {} ({})", detector, path, orientation, r.message);
        mgr.reload();
        return r.changed;
    }

    /** Runs {@code listener} after {@link #save} changes an orientation. Not called on a microscope switch. */
    public static void addChangeListener(Runnable listener) {
        CHANGE_LISTENERS.add(listener);
    }
}
