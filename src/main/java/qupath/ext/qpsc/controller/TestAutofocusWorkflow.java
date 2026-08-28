package qupath.ext.qpsc.controller;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.ThemeColors;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;

/**
 * TestAutofocusWorkflow - Runs autofocus test at current microscope position
 *
 * <p>This workflow provides interactive autofocus testing with diagnostic output:
 * <ol>
 *   <li>Reads current objective from config</li>
 *   <li>Uses settings from autofocus_{microscope}.yml</li>
 *   <li>Performs autofocus scan at current XY position</li>
 *   <li>Generates diagnostic plot showing focus curve</li>
 *   <li>Displays results and opens plot for review</li>
 * </ol>
 *
 * <p>Key features:
 * <ul>
 *   <li>Uses saved autofocus settings from config file</li>
 *   <li>Comprehensive diagnostic plotting</li>
 *   <li>Shows raw vs interpolated focus peaks</li>
 *   <li>Reports Z shift from starting position</li>
 *   <li>Helps troubleshoot autofocus issues</li>
 * </ul>
 *
 * <p>This workflow is designed to be called from:
 * <ul>
 *   <li>Autofocus Editor dialog (test button)</li>
 *   <li>Standalone menu item for quick testing</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class TestAutofocusWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(TestAutofocusWorkflow.class);

    /**
     * Main entry point for standard autofocus test workflow.
     * Uses default output path and reads objective from config.
     */
    public static void runStandard() {
        String defaultOutputPath = getDefaultOutputPath();
        runStandard(defaultOutputPath, null); // null = read from config
    }

    /**
     * Main entry point for sweep autofocus test workflow.
     * Uses default output path and reads objective from config.
     */
    public static void runSweep() {
        String defaultOutputPath = getDefaultOutputPath();
        runSweep(defaultOutputPath, null); // null = read from config
    }

    /**
     * Get default output path for autofocus tests based on config file location.
     * Creates autofocus_tests folder at same level as config file.
     *
     * @return Path to autofocus_tests directory, or user.home fallback if config not found
     */
    public static String getDefaultOutputPath() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                File configFile = new File(configPath);
                if (configFile.exists()) {
                    // Get parent directory of config file
                    File configDir = configFile.getParentFile();

                    // Create autofocus_tests subdirectory
                    File outputDir = new File(configDir, "autofocus_tests");

                    logger.info("Using autofocus test output path: {}", outputDir.getAbsolutePath());
                    return outputDir.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to determine config-based output path, using fallback", e);
        }

        // Fallback to user home directory
        String fallbackPath = System.getProperty("user.home") + File.separator + "autofocus_tests";
        logger.info("Using fallback autofocus test output path: {}", fallbackPath);
        return fallbackPath;
    }

    /**
     * Run STANDARD autofocus test with specified output path and objective.
     * Tests the symmetric-sweep autofocus algorithm.
     *
     * @param outputPath Directory where diagnostic plots will be saved
     * @param objectiveOverride Objective to use, or null to read from config
     */
    public static void runStandard(String outputPath, String objectiveOverride) {
        logger.info("Starting STANDARD autofocus test workflow");
        runTest(outputPath, false, objectiveOverride); // false = standard
    }

    /**
     * Run SWEEP autofocus test with specified output path and objective.
     * Tests the sweep-based focus correction used during acquisitions.
     *
     * @param outputPath Directory where diagnostic plots will be saved
     * @param objectiveOverride Objective to use, or null to read from config
     */
    public static void runSweep(String outputPath, String objectiveOverride) {
        logger.info("Starting SWEEP autofocus test workflow");
        runTest(outputPath, true, objectiveOverride); // true = sweep (was adaptive)
    }

    /**
     * Internal method to run autofocus test.
     *
     * @param outputPath Directory where diagnostic plots will be saved
     * @param isAdaptive True for adaptive autofocus, false for standard
     * @param objectiveOverride Objective to use, or null to read from config
     */
    private static void runTest(String outputPath, boolean isAdaptive, String objectiveOverride) {
        String testType = isAdaptive ? "adaptive" : "standard";
        logger.info("Starting {} autofocus test workflow", testType);

        Platform.runLater(() -> {
            try {
                // Validate microscope connection
                if (!MicroscopeController.getInstance().isConnected()) {
                    boolean connect = Dialogs.showConfirmDialog(
                            "Connection Required", "Microscope server is not connected. Connect now?");

                    if (connect) {
                        try {
                            MicroscopeController.getInstance().userTriggeredConnect();
                        } catch (IOException e) {
                            logger.error("Failed to connect to microscope server", e);
                            Dialogs.showErrorMessage(
                                    "Connection Failed", "Could not connect to microscope server: " + e.getMessage());
                            return;
                        }
                    } else {
                        logger.info("Autofocus test cancelled - no connection");
                        return;
                    }
                }

                // Get configuration
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                if (configPath == null || configPath.isEmpty()) {
                    Dialogs.showErrorMessage(
                            "Configuration Error", "No microscope configuration file set in preferences.");
                    return;
                }

                File configFile = new File(configPath);
                if (!configFile.exists()) {
                    Dialogs.showErrorMessage(
                            "Configuration Error", "Microscope configuration file not found: " + configPath);
                    return;
                }

                // Determine objective to use
                String objective;
                if (objectiveOverride != null && !objectiveOverride.isEmpty()) {
                    // Use objective from UI selection
                    objective = objectiveOverride;
                    logger.info("Using objective from UI selection: {}", objective);
                } else {
                    // Read from config file
                    MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
                    objective = getCurrentObjective(configManager);

                    if (objective == null) {
                        Dialogs.showErrorMessage(
                                "Configuration Error",
                                "Could not determine current objective from configuration.\n"
                                        + "Please check microscope/objective_in_use setting.");
                        return;
                    }
                    logger.info("Using objective from config: {}", objective);
                }

                logger.info("Testing {} autofocus for objective: {}", testType, objective);

                // Show confirmation dialog
                String dialogTitle = isAdaptive ? "Test Adaptive Autofocus" : "Test Standard Autofocus";
                String algorithmDesc = isAdaptive
                        ? "The microscope will perform a sweep autofocus (same algorithm used during acquisitions)."
                        : "The microscope will perform a symmetric Z-sweep centered on current position.";

                boolean proceed = Dialogs.showConfirmDialog(
                        dialogTitle,
                        String.format(
                                "Test %s autofocus at current position?\n\n" + "Objective: %s\n"
                                        + "Settings: autofocus_%s.yml\n"
                                        + "Output: %s\n\n"
                                        + "%s",
                                testType,
                                objective,
                                extractMicroscopeName(configFile.getName()),
                                outputPath,
                                algorithmDesc));

                if (!proceed) {
                    logger.info("{} autofocus test cancelled by user", testType);
                    return;
                }

                // Execute test in background
                CompletableFuture.runAsync(() -> {
                            executeAutofocusTest(configPath, outputPath, objective, isAdaptive);
                        })
                        .exceptionally(ex -> {
                            logger.error("Autofocus test failed", ex);
                            Platform.runLater(() -> {
                                Dialogs.showErrorMessage(
                                        "Autofocus Test Error", "Failed to execute autofocus test: " + ex.getMessage());
                            });
                            return null;
                        });

            } catch (Exception e) {
                logger.error("Failed to start autofocus test workflow", e);
                Dialogs.showErrorMessage("Autofocus Test Error", "Failed to start autofocus test: " + e.getMessage());
            }
        });
    }

    /**
     * Execute autofocus test via socket communication.
     *
     * @param isAdaptive True for adaptive autofocus, false for standard
     */
    private static void executeAutofocusTest(
            String configPath, String outputPath, String objective, boolean isAdaptive) {
        String testType = isAdaptive ? "adaptive" : "standard";
        logger.info("Executing {} autofocus test for objective: {}", testType, objective);

        MicroscopeController mc = MicroscopeController.getInstance();

        try {
            MicroscopeSocketClient socketClient = mc.getSocketClient();

            if (!mc.isConnected()) {
                logger.info("Connecting to microscope server for autofocus test");
                mc.userTriggeredConnect();
            }

            File outputDir = new File(outputPath);
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw new IOException("Failed to create output directory: " + outputPath);
            }

            // The AF test moves Z and snaps images. withAllLiveViewingOff
            // stops both MM live mode and the QPSC Live Viewer's continuous
            // acquisition before the run and restores them in a finally
            // block, so the Live Viewer status updates correctly and frames
            // resume after the test.
            mc.withAllLiveViewingOff(() -> {
                logger.info("Starting {} autofocus test", testType);

                Map<String, String> result;
                if (isAdaptive) {
                    result = socketClient.testAdaptiveAutofocus(configPath, outputPath, objective);
                } else {
                    result = socketClient.testAutofocus(configPath, outputPath, objective);
                }

                logger.info("{} autofocus test completed successfully", testType);

                String plotPath = result.get("plot_path");
                String initialZ = result.get("initial_z");
                String finalZ = result.get("final_z");
                String zShift = result.get("z_shift");

                Platform.runLater(
                        () -> showAutofocusResultDialog(testType, initialZ, finalZ, zShift, plotPath, outputPath));
            });

        } catch (Exception e) {
            logger.error("Autofocus test failed", e);
            Platform.runLater(() -> Dialogs.showErrorMessage(
                    "Autofocus Test Failed", "Failed to execute autofocus test: " + e.getMessage()));
        }
    }

    /**
     * Get current objective from microscope configuration.
     *
     * @param configManager Configuration manager instance
     * @return Objective identifier or null if not found
     */
    @SuppressWarnings("unchecked")
    public static String getCurrentObjective(MicroscopeConfigManager configManager) {
        // 1. Ask the hardware. The pixel size MicroManager reports identifies the objective,
        //    and the SERVER resolves it the same way ("resolved objective ... via pixel-match"),
        //    so the two ends agree by construction. This has to come first: the config field
        //    below is never populated at runtime on these rigs, so everything that trusted it
        //    was really running on the first-in-list fallback.
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller != null && controller.isConnected()) {
                double pixelSize = controller.getSocketClient().getMicroscopePixelSize();
                var match = configManager.findHardwareByPixelSize(
                        pixelSize, MicroscopeConfigManager.DEFAULT_PIXEL_SIZE_TOLERANCE_UM);
                if (match.isPresent()) {
                    logger.debug(
                            "Objective resolved as {} via MicroManager pixel size {} um/px",
                            match.get().objectiveId(),
                            pixelSize);
                    return match.get().objectiveId();
                }
                logger.warn(
                        "MicroManager pixel size {} um/px matches no configured objective; "
                                + "falling back to the session's selection",
                        pixelSize);
            }
        } catch (Exception e) {
            logger.debug("Could not resolve the objective from the MicroManager pixel size ({})", e.getMessage());
        }

        // 2. What the operator chose for this session. Wrong only if they picked wrongly, which
        //    is a different and much more visible kind of wrong.
        String fromState = qupath.ext.qpsc.state.ObjectiveState.getInstance().getObjective();
        if (fromState != null && !fromState.isEmpty()) {
            return fromState;
        }

        try {
            Map<String, Object> config = configManager.getAllConfig();
            Map<String, Object> microscope = (Map<String, Object>) config.get("microscope");

            if (microscope != null) {
                Object objectiveInUse = microscope.get("objective_in_use");
                if (objectiveInUse != null) {
                    return objectiveInUse.toString();
                }
            }

            // 3. Last resort, and a genuinely dangerous answer -- hence WARN, not INFO. It is
            //    a GUESS with no evidence behind it, and the callers feed it straight into
            //    per-objective exposure tables. On PPM the first entry is the 10x, whose
            //    uncrossed exposure is ~4x the 20x one: applying it at 20x saturates the
            //    sensor and autofocus then reports "99.4% of pixels saturated" three steps
            //    later, with nothing pointing back here. Observed on the 2026-08-27 run.
            java.util.List<Map<String, Object>> hardwareObjectives = configManager.getHardwareObjectives();
            if (!hardwareObjectives.isEmpty()) {
                Object objectiveId = hardwareObjectives.get(0).get("id");
                if (objectiveId != null) {
                    logger.warn(
                            "GUESSING objective '{}' -- it is simply the first in the hardware list. "
                                    + "MicroManager could not be asked, no objective is selected for this session, "
                                    + "and microscope.objective_in_use is not set. Per-objective exposures and "
                                    + "autofocus settings will be wrong if this is not what is mounted.",
                            objectiveId);
                    return objectiveId.toString();
                }
            }

        } catch (Exception e) {
            logger.error("Error reading current objective from config", e);
        }

        return null;
    }

    /**
     * Show autofocus result dialog with improved formatting and readability.
     * Uses larger, bold text with system colors for better dark mode support.
     */
    private static void showAutofocusResultDialog(
            String testType, String initialZ, String finalZ, String zShift, String plotPath, String outputPath) {
        // Create custom dialog for better formatting
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("Autofocus Test Complete");
        alert.setHeaderText(testType.substring(0, 1).toUpperCase() + testType.substring(1)
                + " autofocus test completed successfully!");

        // Build formatted message with larger, bold text
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10);
        content.setPadding(new javafx.geometry.Insets(10));

        if (initialZ != null && finalZ != null && zShift != null) {
            // Create labels with larger font using theme-adaptive text color
            javafx.scene.control.Label resultsLabel = new javafx.scene.control.Label("Results:");
            resultsLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -fx-text-base-color;");

            javafx.scene.text.TextFlow resultsFlow = new javafx.scene.text.TextFlow();
            javafx.scene.text.Text initialText =
                    new javafx.scene.text.Text(String.format("Initial Z: %s um\n", initialZ));
            javafx.scene.text.Text finalText = new javafx.scene.text.Text(String.format("Final Z: %s um\n", finalZ));
            javafx.scene.text.Text shiftText = new javafx.scene.text.Text(String.format("Z Shift: %s um", zShift));

            // Use theme-adaptive text color that works in both light and dark modes
            initialText.setStyle("-fx-font-size: 12px; -fx-fill: -fx-text-base-color;");
            finalText.setStyle("-fx-font-size: 12px; -fx-fill: -fx-text-base-color;");
            shiftText.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-fill: -fx-text-base-color;");

            resultsFlow.getChildren().addAll(initialText, finalText, shiftText);
            content.getChildren().addAll(resultsLabel, resultsFlow);

            // Warn if large shift
            try {
                double shift = Double.parseDouble(zShift);
                if (Math.abs(shift) > 5.0) {
                    javafx.scene.control.Label warningLabel =
                            new javafx.scene.control.Label("WARNING: Large Z shift detected!");
                    warningLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; " + "-fx-text-fill: "
                            + ThemeColors.ERROR + ";"); // Soft red for visibility

                    javafx.scene.control.Label warningDetail =
                            new javafx.scene.control.Label("Starting position may have been out of focus.");
                    warningDetail.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-base-color;");

                    content.getChildren().addAll(new javafx.scene.control.Separator(), warningLabel, warningDetail);
                }
            } catch (NumberFormatException e) {
                // Ignore parsing error
            }
        }

        if (plotPath != null) {
            javafx.scene.control.Label plotLabel = new javafx.scene.control.Label("Diagnostic plot saved:");
            plotLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: -fx-text-base-color;");

            javafx.scene.control.Label plotPathLabel = new javafx.scene.control.Label(plotPath);
            plotPathLabel.setStyle(
                    "-fx-font-size: 11px; -fx-font-family: monospace; -fx-text-fill: -fx-text-base-color;");
            plotPathLabel.setWrapText(true);

            content.getChildren().addAll(new javafx.scene.control.Separator(), plotLabel, plotPathLabel);
        }

        // Show the folder where the CSV + plot were saved, so the operator can find
        // the diagnostic data even when the fast sweep test skips the inline plot.
        if (outputPath != null) {
            javafx.scene.control.Label folderLabel = new javafx.scene.control.Label("Results folder:");
            folderLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: -fx-text-base-color;");
            javafx.scene.control.Label folderPathLabel = new javafx.scene.control.Label(outputPath);
            folderPathLabel.setStyle(
                    "-fx-font-size: 11px; -fx-font-family: monospace; -fx-text-fill: -fx-text-base-color;");
            folderPathLabel.setWrapText(true);
            content.getChildren().addAll(new javafx.scene.control.Separator(), folderLabel, folderPathLabel);
        }

        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setMinWidth(500);

        // Buttons: "Open Results Folder" (whenever we know the output dir -- the CSV
        // always lands there, even when the fast sweep test skips the inline plot),
        // "Open Plot" (only when a plot was produced), then "Close". The two "open"
        // buttons consume their action so the dialog stays open -- the operator can
        // open the folder AND the plot without re-running the test.
        javafx.scene.control.ButtonType openFolderType = new javafx.scene.control.ButtonType(
                "Open Results Folder", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        javafx.scene.control.ButtonType openPlotType =
                new javafx.scene.control.ButtonType("Open Plot", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        javafx.scene.control.ButtonType closeType =
                new javafx.scene.control.ButtonType("Close", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);

        java.util.List<javafx.scene.control.ButtonType> buttons = new java.util.ArrayList<>();
        if (outputPath != null) {
            buttons.add(openFolderType);
        }
        if (plotPath != null) {
            buttons.add(openPlotType);
        }
        buttons.add(closeType);
        alert.getButtonTypes().setAll(buttons);

        if (outputPath != null) {
            javafx.scene.control.Button openFolderButton =
                    (javafx.scene.control.Button) alert.getDialogPane().lookupButton(openFolderType);
            openFolderButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                e.consume(); // keep the dialog open
                openInDesktop(new File(outputPath), "results folder");
            });
        }
        if (plotPath != null) {
            javafx.scene.control.Button openPlotButton =
                    (javafx.scene.control.Button) alert.getDialogPane().lookupButton(openPlotType);
            openPlotButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                e.consume(); // keep the dialog open
                openInDesktop(new File(plotPath), "diagnostic plot");
            });
        }

        alert.showAndWait();
    }

    /**
     * Opens a file or folder in the OS file manager / default application, with
     * consistent error reporting. No-op with an error dialog when the target is
     * missing or the platform has no Desktop support (e.g. a headless server).
     *
     * @param target the file or directory to open
     * @param label  short human name for the target, used in error messages
     */
    private static void openInDesktop(File target, String label) {
        try {
            if (!target.exists()) {
                Dialogs.showErrorMessage(
                        "Not Found", "The autofocus " + label + " was not found:\n" + target.getAbsolutePath());
                return;
            }
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Dialogs.showErrorMessage(
                        "Unsupported",
                        "Opening the " + label + " is not supported on this system.\n" + target.getAbsolutePath());
                return;
            }
            Desktop.getDesktop().open(target);
            logger.info("Opened autofocus {}: {}", label, target.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to open autofocus {}", label, e);
            Dialogs.showErrorMessage("Error", "Could not open the " + label + ": " + e.getMessage());
        }
    }

    /**
     * Extract microscope name from config filename.
     * E.g., "config_PPM.yml" -> "PPM"
     */
    private static String extractMicroscopeName(String configFilename) {
        // Remove extension
        String nameWithoutExt = configFilename.replaceFirst("\\.[^.]+$", "");

        // Remove "config_" prefix if present
        if (nameWithoutExt.startsWith("config_")) {
            return nameWithoutExt.substring(7);
        }

        return nameWithoutExt;
    }
}
