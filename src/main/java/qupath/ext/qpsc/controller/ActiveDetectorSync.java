package qupath.ext.qpsc.controller;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.service.microscope.MicroscopeController;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;

/**
 * Records the camera Micro-Manager has loaded as the camera in use, each time the server
 * accepts the configuration. On a microscope with two cameras (each with its own Micro-Manager
 * configuration), this is what selects the config's {@code per_detector} values, the
 * acquisition profiles and the camera orientation for the camera actually in use. The server
 * makes the same match ({@code _find_detector_id}) for its own copy of the config.
 */
public final class ActiveDetectorSync {

    private static final Logger logger = LoggerFactory.getLogger(ActiveDetectorSync.class);
    private static final AtomicBoolean registered = new AtomicBoolean(false);

    private ActiveDetectorSync() {}

    /** Wires the listener once. Safe to call repeatedly. */
    public static void ensureRegistered() {
        if (registered.compareAndSet(false, true)) {
            MicroscopeSocketClient.addConfiguredListener(ActiveDetectorSync::onServerConfigured);
        }
    }

    /** Runs inside the socket lock: hand the server call to another thread. */
    private static void onServerConfigured() {
        Thread t = new Thread(ActiveDetectorSync::syncNow, "qpsc-active-detector");
        t.setDaemon(true);
        t.start();
    }

    private static void syncNow() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            return;
        }
        Collection<String> declared = mgr.getHardwareDetectors();
        if (declared.size() < 2) {
            return;
        }
        String camera;
        try {
            camera = MicroscopeController.getInstance().getCameraName();
        } catch (Exception e) {
            logger.warn("Could not ask the server which camera is loaded: {}", e.getMessage());
            return;
        }
        String detector = detectorForCamera(mgr.getMergedDetectorSection(), declared, camera);
        if (detector == null) {
            logger.warn(
                    "Micro-Manager's camera '{}' matches none of the config's detectors {} (id_detector.<id>.device)",
                    camera,
                    declared);
            return;
        }
        if (!detector.equals(PersistentPreferences.getLastDetector())) {
            logger.info("Camera in use: {} (Micro-Manager camera '{}')", detector, camera);
            Platform.runLater(() -> PersistentPreferences.setLastDetector(detector));
        }
    }

    /**
     * The declared detector whose {@code id_detector.<id>.device} is Micro-Manager's camera
     * label, or null when none (or the label is empty).
     */
    static String detectorForCamera(Map<String, Object> detectorSection, Collection<String> declared, String camera) {
        if (camera == null || camera.isBlank() || detectorSection == null) {
            return null;
        }
        for (String id : declared) {
            if (detectorSection.get(id) instanceof Map<?, ?> spec && camera.equals(spec.get("device"))) {
                return id;
            }
        }
        return null;
    }
}
