package qupath.ext.qpsc.controller;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Automated test workflow to reproduce MicroManager crash sequence.
 *
 * <p>The crash occurs when running calibration twice in a row:
 * <ol>
 *   <li>Apply and Go for positive angle (sets per-channel exposures + unified gain)</li>
 *   <li>Snap image (calls hardware.snap_image() which does set_live_mode(False) + core.snap_image())</li>
 *   <li>Apply and Go for negative angle (sets per-channel exposures + unified gain)</li>
 *   <li>Snap image -- crash occurs here in snap_image()</li>
 * </ol>
 *
 * <p>Uses the actual SNAP socket command to trigger the real snap_image() code path
 * on the Python server. Loads per-angle calibration values from YAML config to match
 * exactly what the real calibration workflow sends (3-channel exposures + unified gain 5.0).
 *
 * <p>Assumes JAI camera with 40x objective (LOCI_DETECTOR_JAI_001 / LOCI_OBJECTIVE_OLYMPUS_40X_POL_001).
 *
 * @author Mike Nelson
 */
public class ApplyGoSnapTestWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ApplyGoSnapTestWorkflow.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Hardcoded for this debug test -- JAI camera + 40x objective
    private static final String OBJECTIVE = "LOCI_OBJECTIVE_OLYMPUS_40X_POL_001";
    private static final String DETECTOR = "LOCI_DETECTOR_JAI_001";

    /**
     * Entry point -- shows log dialog and starts the test on a background thread.
     */
    public static void run() {
        Platform.runLater(() -> {
            Stage stage = new Stage();
            stage.setTitle("Apply & Go Snap Test");

            TextArea logArea = new TextArea();
            logArea.setEditable(false);
            logArea.setWrapText(true);
            logArea.setPrefRowCount(25);
            logArea.setPrefColumnCount(70);
            logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

            VBox root = new VBox(10, logArea);
            root.setPadding(new Insets(10));

            stage.setScene(new Scene(root, 700, 500));
            stage.show();

            log(logArea, "=== Apply & Go Snap Crash Reproduction Test ===");
            log(logArea, "Purpose: Reproduce MM crash on second snap_image() call");
            log(logArea, "Sequence: Apply+Go(pos) -> SNAP -> Apply+Go(neg) -> SNAP");
            log(logArea, "Hardware: " + OBJECTIVE + " / " + DETECTOR);
            log(logArea, "");

            Thread testThread = new Thread(() -> executeTest(logArea), "ApplyGoSnapTest");
            testThread.setDaemon(true);
            testThread.start();
        });
    }

    /**
     * Runs the 4-step crash reproduction sequence on a background thread.
     */
    @SuppressWarnings("unchecked")
    private static void executeTest(TextArea logArea) {
        MicroscopeController controller = MicroscopeController.getInstance();
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        // --- Load rotation angles from config ---
        log(logArea, "Loading PPM rotation angles from config...");
        Map<String, Double> angles = loadPpmAngles(mgr);
        if (angles.isEmpty()) {
            log(logArea, "[FAIL] No rotation angles found in config.");
            return;
        }

        Double positiveDegrees = angles.get("positive");
        Double negativeDegrees = angles.get("negative");
        if (positiveDegrees == null || negativeDegrees == null) {
            log(logArea, "[FAIL] Config must have 'positive' and 'negative' angle entries.");
            log(logArea, "  Available angles: " + angles.keySet());
            return;
        }
        log(logArea, "  positive = " + positiveDegrees + " deg");
        log(logArea, "  negative = " + negativeDegrees + " deg");

        // --- Load per-angle calibration exposures and gains from YAML ---
        log(logArea, "Loading calibration values from YAML for " + OBJECTIVE + " / " + DETECTOR + "...");

        float[] positiveExposures = loadExposuresForAngle(mgr, "positive");
        float[] negativeExposures = loadExposuresForAngle(mgr, "negative");
        float[] positiveGains = loadGainsForAngle(mgr, "positive");
        float[] negativeGains = loadGainsForAngle(mgr, "negative");

        if (positiveExposures == null || negativeExposures == null) {
            log(logArea, "[FAIL] Could not load per-channel exposures from YAML.");
            return;
        }
        if (positiveGains == null || negativeGains == null) {
            log(logArea, "[FAIL] Could not load gains from YAML.");
            return;
        }

        log(logArea, "  positive exposures: " + Arrays.toString(positiveExposures) + " ms");
        log(logArea, "  positive gains (unified): " + Arrays.toString(positiveGains));
        log(logArea, "  negative exposures: " + Arrays.toString(negativeExposures) + " ms");
        log(logArea, "  negative gains (unified): " + Arrays.toString(negativeGains));

        log(logArea, "");
        log(logArea, "--- Starting crash reproduction sequence ---");
        log(logArea, "  Camera mode: exp_individual=true, gain_individual=false");
        log(logArea, "");

        // === Step 1: Apply & Go for positive angle ===
        log(logArea, "STEP 1: Apply & Go -> positive (" + positiveDegrees + " deg)");
        log(logArea, "  Exposures: " + Arrays.toString(positiveExposures) + " | Gains: " + Arrays.toString(positiveGains));
        try {
            controller.withLiveModeHandling(() ->
                    controller.applyCameraSettingsForAngle("positive", positiveExposures, positiveGains, positiveDegrees));
            log(logArea, "  [PASS] Apply & Go positive completed");
        } catch (Exception e) {
            log(logArea, "  [FAIL] Apply & Go positive failed: " + e.getMessage());
            logger.error("Step 1 failed", e);
            return;
        }

        // === Step 2: Raw snap (calls hardware.snap_image() without resetting camera mode) ===
        log(logArea, "STEP 2: RAWSNAP (calls hardware.snap_image() - no mode reset)");
        try {
            String result = controller.rawSnap();
            log(logArea, "  [PASS] First snap completed: " + result);
        } catch (Exception e) {
            log(logArea, "  [FAIL] First snap failed: " + e.getMessage());
            logger.error("Step 2 failed", e);
            return;
        }

        // === Step 3: Apply & Go for negative angle ===
        log(logArea, "STEP 3: Apply & Go -> negative (" + negativeDegrees + " deg)");
        log(logArea, "  Exposures: " + Arrays.toString(negativeExposures) + " | Gains: " + Arrays.toString(negativeGains));
        try {
            controller.withLiveModeHandling(() ->
                    controller.applyCameraSettingsForAngle("negative", negativeExposures, negativeGains, negativeDegrees));
            log(logArea, "  [PASS] Apply & Go negative completed");
        } catch (Exception e) {
            log(logArea, "  [FAIL] Apply & Go negative failed: " + e.getMessage());
            logger.error("Step 3 failed", e);
            return;
        }

        // === Step 4: Second raw snap -- this is the expected crash point ===
        log(logArea, "STEP 4: RAWSNAP (CRASH EXPECTED HERE - second snap_image() call)");
        try {
            String result = controller.rawSnap();
            log(logArea, "  [PASS] Second snap completed: " + result);
        } catch (Exception e) {
            log(logArea, "  [FAIL] Second snap failed: " + e.getMessage());
            logger.error("Step 4 failed", e);
            return;
        }

        log(logArea, "");
        log(logArea, "=== ALL 4 STEPS PASSED ===");
        log(logArea, "The crash did not occur. Per-channel mode was active during snap_image().");
        log(logArea, "The crash may be specific to the SBCALIB workflow context.");
    }

    /**
     * Loads per-channel exposures [R, G, B] for a specific angle from YAML config.
     * Returns a 3-element float array for per-channel, or null if not found.
     */
    @SuppressWarnings("unchecked")
    private static float[] loadExposuresForAngle(MicroscopeConfigManager mgr, String angleName) {
        try {
            Map<String, Object> exposuresMap = mgr.getModalityExposures("ppm", OBJECTIVE, DETECTOR);
            if (exposuresMap == null) return null;

            Object angleExposures = exposuresMap.get(angleName);
            if (angleExposures instanceof Map<?, ?>) {
                Map<String, Object> expValues = (Map<String, Object>) angleExposures;
                Number r = (Number) expValues.get("r");
                Number g = (Number) expValues.get("g");
                Number b = (Number) expValues.get("b");
                if (r != null && g != null && b != null) {
                    return new float[]{r.floatValue(), g.floatValue(), b.floatValue()};
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load exposures for angle {}: {}", angleName, e.getMessage());
        }
        return null;
    }

    /**
     * Loads gains for a specific angle from YAML config.
     * Returns a 1-element float array with unified_gain value if present,
     * matching the real calibration behavior (unified gain mode).
     */
    @SuppressWarnings("unchecked")
    private static float[] loadGainsForAngle(MicroscopeConfigManager mgr, String angleName) {
        try {
            Object gainsObj = mgr.getProfileSetting("ppm", OBJECTIVE, DETECTOR, "gains", angleName);
            if (gainsObj instanceof Map<?, ?>) {
                Map<String, Object> gainValues = (Map<String, Object>) gainsObj;
                Number unifiedGain = (Number) gainValues.get("unified_gain");
                if (unifiedGain != null) {
                    return new float[]{unifiedGain.floatValue()};
                }
                // Fallback: use first available value
                Number r = (Number) gainValues.get("r");
                if (r != null) {
                    return new float[]{r.floatValue()};
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load gains for angle {}: {}", angleName, e.getMessage());
        }
        return null;
    }

    /**
     * Loads PPM rotation angles from YAML config.
     * Returns a map of angle name to tick value in degrees.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> loadPpmAngles(MicroscopeConfigManager mgr) {
        try {
            List<Map<String, Object>> rotationAngles = mgr.getRotationAngles("ppm");
            if (rotationAngles != null && !rotationAngles.isEmpty()) {
                Map<String, Double> angles = new LinkedHashMap<>();
                for (Map<String, Object> angle : rotationAngles) {
                    String name = (String) angle.get("name");
                    Number tick = (Number) angle.get("tick");
                    if (name != null && tick != null) {
                        angles.put(name, tick.doubleValue());
                    }
                }
                if (!angles.isEmpty()) {
                    logger.info("Loaded {} PPM angles from config: {}", angles.size(), angles);
                    return angles;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load PPM angles from config: {}", e.getMessage());
        }

        logger.warn("No rotation angles found in config");
        return new LinkedHashMap<>();
    }

    /**
     * Thread-safe log append to the TextArea.
     */
    private static void log(TextArea area, String msg) {
        String timestamped = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg;
        logger.info("ApplyGoSnapTest: {}", msg);
        Platform.runLater(() -> {
            area.appendText(timestamped + "\n");
            area.setScrollTop(Double.MAX_VALUE);
        });
    }
}
