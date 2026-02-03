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
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
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
 *   <li>Apply and Go for positive angle (e.g., +7 degrees)</li>
 *   <li>Snap image (triggers studio.live().set_live_mode(False))</li>
 *   <li>Apply and Go for negative angle (e.g., -7 degrees)</li>
 *   <li>Snap image -- crash occurs here at set_live_mode(False)</li>
 * </ol>
 *
 * <p>This test uses setLiveMode(true) then setLiveMode(false) as a snap proxy,
 * since the crash is in the same code path (studio.live().set_live_mode(False)).
 * If this does not crash MM, the issue is deeper in pycromanager's core.snap_image()
 * and would need escalation to using the full SBCALIB command.
 *
 * <p>No user input needed beyond clicking the menu item. Reads current camera
 * state from the server and rotation angles from YAML config.
 *
 * @author Mike Nelson
 */
public class ApplyGoSnapTestWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ApplyGoSnapTestWorkflow.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

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
            logArea.setPrefRowCount(20);
            logArea.setPrefColumnCount(60);
            logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

            VBox root = new VBox(10, logArea);
            root.setPadding(new Insets(10));

            stage.setScene(new Scene(root, 600, 400));
            stage.show();

            log(logArea, "=== Apply & Go Snap Crash Reproduction Test ===");
            log(logArea, "Purpose: Reproduce MM crash on second live mode toggle");
            log(logArea, "Sequence: Apply+Go(pos) -> snap proxy -> Apply+Go(neg) -> snap proxy");
            log(logArea, "");

            Thread testThread = new Thread(() -> executeTest(logArea), "ApplyGoSnapTest");
            testThread.setDaemon(true);
            testThread.start();
        });
    }

    /**
     * Runs the 4-step crash reproduction sequence on a background thread.
     */
    private static void executeTest(TextArea logArea) {
        MicroscopeController controller = MicroscopeController.getInstance();

        // --- Load rotation angles from config ---
        log(logArea, "Loading PPM rotation angles from config...");
        Map<String, Double> angles = loadPpmAngles();
        if (angles.isEmpty()) {
            log(logArea, "[FAIL] No rotation angles found in config. Cannot run test.");
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

        // --- Read current camera state from server ---
        // The real crash scenario uses 3 individual exposures + 1 unified gain.
        // applyCameraSettingsForAngle uses array length to set camera mode:
        //   exposures.length == 3 -> individual exposure mode
        //   gains.length == 1    -> unified gain mode
        // This mode switching is key to reproducing the crash.
        log(logArea, "Reading current camera exposures and gains from server...");
        float[] exposures;
        float[] gains;
        try {
            MicroscopeSocketClient.ExposuresResult expResult = controller.getExposures();
            MicroscopeSocketClient.GainsResult gainResult = controller.getGains();

            // Always use 3 per-channel exposures to match real calibration behavior.
            // If server returns unified, replicate to 3 channels.
            if (expResult.isPerChannel()) {
                exposures = new float[]{
                        (float) expResult.red(),
                        (float) expResult.green(),
                        (float) expResult.blue()
                };
            } else {
                float exp = (float) expResult.unified();
                exposures = new float[]{exp, exp, exp};
            }

            // Always use 1 unified gain to match real calibration behavior.
            // If server returns per-channel, use red as representative value.
            if (gainResult.isPerChannel()) {
                gains = new float[]{(float) gainResult.red()};
            } else {
                gains = new float[]{(float) gainResult.red()};
            }

            log(logArea, "  Exposures (3-channel): " + Arrays.toString(exposures) + " ms");
            log(logArea, "  Gains (unified): " + Arrays.toString(gains));
            log(logArea, "  This forces exp_individual=true, gain_individual=false (matches calibration)");
        } catch (IOException e) {
            log(logArea, "[FAIL] Could not read camera state: " + e.getMessage());
            logger.error("Failed to read camera state for snap test", e);
            return;
        }

        log(logArea, "");
        log(logArea, "--- Starting crash reproduction sequence ---");
        log(logArea, "");

        // === Step 1: Apply & Go for positive angle ===
        log(logArea, "STEP 1: Apply & Go -> positive (" + positiveDegrees + " deg)");
        try {
            controller.withLiveModeHandling(() ->
                    controller.applyCameraSettingsForAngle("positive", exposures, gains, positiveDegrees));
            log(logArea, "  [PASS] Apply & Go positive completed");
        } catch (Exception e) {
            log(logArea, "  [FAIL] Apply & Go positive failed: " + e.getMessage());
            logger.error("Step 1 failed", e);
            return;
        }

        // === Step 2: Snap proxy (live mode toggle) ===
        log(logArea, "STEP 2: Snap proxy (setLiveMode true -> wait -> setLiveMode false)");
        try {
            controller.setLiveMode(true);
            log(logArea, "  Live mode ON");
            Thread.sleep(500);
            controller.setLiveMode(false);
            log(logArea, "  Live mode OFF");
            Thread.sleep(200);
            log(logArea, "  [PASS] First snap proxy completed");
        } catch (Exception e) {
            log(logArea, "  [FAIL] First snap proxy failed: " + e.getMessage());
            logger.error("Step 2 failed", e);
            return;
        }

        // === Step 3: Apply & Go for negative angle ===
        log(logArea, "STEP 3: Apply & Go -> negative (" + negativeDegrees + " deg)");
        try {
            controller.withLiveModeHandling(() ->
                    controller.applyCameraSettingsForAngle("negative", exposures, gains, negativeDegrees));
            log(logArea, "  [PASS] Apply & Go negative completed");
        } catch (Exception e) {
            log(logArea, "  [FAIL] Apply & Go negative failed: " + e.getMessage());
            logger.error("Step 3 failed", e);
            return;
        }

        // === Step 4: Snap proxy again -- this is the expected crash point ===
        log(logArea, "STEP 4: Snap proxy (CRASH EXPECTED HERE - second live mode toggle)");
        try {
            controller.setLiveMode(true);
            log(logArea, "  Live mode ON");
            Thread.sleep(500);
            controller.setLiveMode(false);
            log(logArea, "  Live mode OFF");
            Thread.sleep(200);
            log(logArea, "  [PASS] Second snap proxy completed");
        } catch (Exception e) {
            log(logArea, "  [FAIL] Second snap proxy failed: " + e.getMessage());
            logger.error("Step 4 failed", e);
            return;
        }

        log(logArea, "");
        log(logArea, "=== ALL 4 STEPS PASSED ===");
        log(logArea, "The crash is NOT in the Apply & Go + live toggle path.");
        log(logArea, "Next step: escalate to using full SBCALIB command to isolate");
        log(logArea, "whether the crash is in pycromanager's core.snap_image() call.");
    }

    /**
     * Loads PPM rotation angles from YAML config.
     * Returns a map of angle name to tick value in degrees.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> loadPpmAngles() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
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
