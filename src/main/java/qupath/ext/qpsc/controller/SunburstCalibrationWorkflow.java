package qupath.ext.qpsc.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.CalibrationResultDialog;
import qupath.ext.qpsc.ui.CalibrationResultDialog.CalibrationResultData;
import qupath.ext.qpsc.ui.SunburstCalibrationDialog;
import qupath.ext.qpsc.ui.SunburstCalibrationDialog.SunburstCalibrationParams;
import qupath.fx.dialogs.Dialogs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SunburstCalibrationWorkflow - Create hue-to-angle calibration from a PPM reference slide.
 *
 * This workflow provides a way to calibrate PPM hue-to-angle mapping using a
 * PPM reference slide with sunburst pattern (oriented colored rectangles):
 * <ol>
 *   <li>User positions calibration slide under microscope</li>
 *   <li>Dialog collects parameters (modality, detection thresholds)</li>
 *   <li>Single image acquired using modality's exposure settings</li>
 *   <li>ppm_library's SunburstCalibrator detects rectangles and fits regression</li>
 *   <li>Results displayed with R-squared metric and visualization plot</li>
 *   <li>Calibration saved for use in PPM analysis</li>
 * </ol>
 *
 * The calibration creates a linear regression mapping hue values (0-1) to
 * orientation angles (0-180 degrees), which is used to convert PPM images
 * from color to orientation maps.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class SunburstCalibrationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(SunburstCalibrationWorkflow.class);

    /**
     * Main entry point for sunburst calibration workflow.
     * Shows UI for parameter input, then executes calibration.
     */
    public static void run() {
        logger.info("Starting sunburst calibration workflow");

        Platform.runLater(() -> {
            try {
                // Show dialog and collect parameters
                SunburstCalibrationDialog.showDialog()
                    .thenAccept(params -> {
                        if (params != null) {
                            logger.info("Sunburst calibration parameters received");
                            // Start calibration with progress dialog (all on FX thread initially)
                            startCalibrationWithProgress(params);
                        } else {
                            logger.info("Sunburst calibration cancelled by user");
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in sunburst calibration dialog", ex);
                        Platform.runLater(() -> Dialogs.showErrorMessage("Calibration Error",
                                "Failed to show calibration dialog: " + ex.getMessage()));
                        return null;
                    });

            } catch (Exception e) {
                logger.error("Failed to start sunburst calibration workflow", e);
                Dialogs.showErrorMessage("Calibration Error",
                        "Failed to start sunburst calibration: " + e.getMessage());
            }
        });
    }

    /**
     * Creates progress dialog on FX thread, then runs calibration on background thread.
     * This ensures all UI components are created on the correct thread.
     *
     * @param params Calibration parameters from dialog
     */
    private static void startCalibrationWithProgress(SunburstCalibrationParams params) {
        // Must be called on FX thread - create progress dialog here
        Alert progressDialog = new Alert(Alert.AlertType.INFORMATION);
        progressDialog.setTitle("Calibration In Progress");
        progressDialog.setHeaderText("PPM Reference Slide Calibration");

        Label progressLabel = new Label(
                "Acquiring calibration image and processing...\n\n" +
                "This typically completes in a few seconds."
        );
        progressLabel.setStyle("-fx-font-size: 12px;");

        VBox content = new VBox(10);
        content.getChildren().add(progressLabel);
        progressDialog.getDialogPane().setContent(content);

        progressDialog.getButtonTypes().clear();
        progressDialog.getButtonTypes().add(ButtonType.CANCEL);

        // Show progress dialog (non-blocking)
        progressDialog.show();

        // Now run the actual calibration on a background thread
        CompletableFuture.runAsync(() -> {
            executeCalibration(params, progressDialog);
        }).exceptionally(ex -> {
            logger.error("Sunburst calibration failed", ex);
            Platform.runLater(() -> {
                progressDialog.close();
                Dialogs.showErrorMessage("PPM Calibration Error",
                        "Failed to execute calibration: " + ex.getMessage());
            });
            return null;
        });
    }

    /**
     * Executes the sunburst calibration via socket communication.
     * Called from background thread - all UI updates must use Platform.runLater.
     *
     * @param params Calibration parameters from dialog
     * @param progressDialog Progress dialog to close when done (created on FX thread)
     */
    private static void executeCalibration(SunburstCalibrationParams params, Alert progressDialog) {
        logger.info("Executing sunburst calibration:");
        logger.info("  Output folder: {}", params.outputFolder());
        logger.info("  Modality: {}", params.modality());
        logger.info("  Expected rectangles: {}", params.expectedRectangles());
        logger.info("  Saturation threshold: {}", params.saturationThreshold());
        logger.info("  Value threshold: {}", params.valueThreshold());
        if (params.calibrationName() != null) {
            logger.info("  Calibration name: {}", params.calibrationName());
        }

        MicroscopeSocketClient socketClient = null;
        try {
            // Get microscope configuration
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            String serverHost = QPPreferenceDialog.getMicroscopeServerHost();
            int serverPort = QPPreferenceDialog.getMicroscopeServerPort();

            // Connect to microscope server
            logger.info("Connecting to microscope server at {}:{}", serverHost, serverPort);
            socketClient = new MicroscopeSocketClient(serverHost, serverPort);
            socketClient.connect();

            logger.info("Sending sunburst calibration command...");

            // Call the socket client method
            String resultJson = socketClient.runSunburstCalibration(
                    configFileLocation,
                    params.outputFolder(),
                    params.modality(),
                    params.expectedRectangles(),
                    params.saturationThreshold(),
                    params.valueThreshold(),
                    params.calibrationName()
            );

            logger.info("Received calibration result JSON");

            // Parse JSON result
            CalibrationResultData resultData = parseCalibrationResult(resultJson);

            // Close progress dialog and show results (on FX thread)
            Platform.runLater(() -> {
                progressDialog.close();
                CalibrationResultDialog.showResult(resultData);
            });

        } catch (Exception e) {
            logger.error("Sunburst calibration failed", e);

            // Close progress dialog and show error (on FX thread)
            Platform.runLater(() -> {
                progressDialog.close();
                CalibrationResultData errorResult = CalibrationResultData.failure(e.getMessage());
                CalibrationResultDialog.showResult(errorResult);
            });
        } finally {
            // Always disconnect the socket client to free the connection
            if (socketClient != null) {
                try {
                    socketClient.disconnect();
                    logger.info("Disconnected from microscope server");
                } catch (Exception e) {
                    logger.warn("Error disconnecting from microscope server", e);
                }
            }
        }
    }

    /**
     * Parses the JSON result from the server into a CalibrationResultData object.
     *
     * @param json JSON string from server
     * @return Parsed calibration result data
     */
    private static CalibrationResultData parseCalibrationResult(String json) {
        try {
            Gson gson = new Gson();
            JsonObject result = gson.fromJson(json, JsonObject.class);

            boolean success = result.has("success") && result.get("success").getAsBoolean();

            // Always extract image and mask paths for debugging
            String imagePath = result.has("image_path") && !result.get("image_path").isJsonNull() ?
                    result.get("image_path").getAsString() : null;
            String maskPath = result.has("mask_path") && !result.get("mask_path").isJsonNull() ?
                    result.get("mask_path").getAsString() : null;

            if (success) {
                double rSquared = result.has("r_squared") ?
                        result.get("r_squared").getAsDouble() : 0.0;
                int rectanglesDetected = result.has("rectangles_detected") ?
                        result.get("rectangles_detected").getAsInt() : 0;
                String plotPath = result.has("plot_path") ?
                        result.get("plot_path").getAsString() : null;
                String calibrationPath = result.has("calibration_path") ?
                        result.get("calibration_path").getAsString() : null;

                List<String> warnings = new ArrayList<>();
                if (result.has("warnings") && result.get("warnings").isJsonArray()) {
                    JsonArray warningsArray = result.getAsJsonArray("warnings");
                    for (int i = 0; i < warningsArray.size(); i++) {
                        warnings.add(warningsArray.get(i).getAsString());
                    }
                }

                return CalibrationResultData.success(rSquared, rectanglesDetected,
                        plotPath, calibrationPath, imagePath, maskPath, warnings);
            } else {
                String error = result.has("error") ?
                        result.get("error").getAsString() : "Unknown error";
                return CalibrationResultData.failure(error, imagePath, maskPath);
            }
        } catch (Exception e) {
            logger.error("Failed to parse calibration result JSON", e);
            return CalibrationResultData.failure("Failed to parse server response: " + e.getMessage());
        }
    }
}
