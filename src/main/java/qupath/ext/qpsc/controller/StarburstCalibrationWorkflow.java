package qupath.ext.qpsc.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.CalibrationResultDialog;
import qupath.ext.qpsc.ui.CalibrationResultDialog.CalibrationResultData;
import qupath.ext.qpsc.ui.StarburstCalibrationDialog;
import qupath.ext.qpsc.ui.StarburstCalibrationDialog.StarburstCalibrationParams;
import qupath.fx.dialogs.Dialogs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * StarburstCalibrationWorkflow - Create hue-to-angle calibration from a starburst slide.
 *
 * This workflow provides a way to calibrate PPM hue-to-angle mapping using a
 * starburst/sunburst calibration slide with oriented colored rectangles:
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
public class StarburstCalibrationWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(StarburstCalibrationWorkflow.class);

    /**
     * Main entry point for starburst calibration workflow.
     * Shows UI for parameter input, then executes calibration.
     */
    public static void run() {
        logger.info("Starting starburst calibration workflow");

        Platform.runLater(() -> {
            try {
                // Show dialog and collect parameters
                StarburstCalibrationDialog.showDialog()
                    .thenAccept(params -> {
                        if (params != null) {
                            logger.info("Starburst calibration parameters received");

                            // Execute calibration
                            CompletableFuture.runAsync(() -> {
                                executeCalibration(params);
                            }).exceptionally(ex -> {
                                logger.error("Starburst calibration failed", ex);
                                Platform.runLater(() -> {
                                    Dialogs.showErrorMessage("Starburst Calibration Error",
                                            "Failed to execute calibration: " + ex.getMessage());
                                });
                                return null;
                            });
                        } else {
                            logger.info("Starburst calibration cancelled by user");
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Error in starburst calibration dialog", ex);
                        Platform.runLater(() -> Dialogs.showErrorMessage("Calibration Error",
                                "Failed to show calibration dialog: " + ex.getMessage()));
                        return null;
                    });

            } catch (Exception e) {
                logger.error("Failed to start starburst calibration workflow", e);
                Dialogs.showErrorMessage("Calibration Error",
                        "Failed to start starburst calibration: " + e.getMessage());
            }
        });
    }

    /**
     * Executes the starburst calibration via socket communication.
     *
     * @param params Calibration parameters from dialog
     */
    private static void executeCalibration(StarburstCalibrationParams params) {
        logger.info("Executing starburst calibration:");
        logger.info("  Output folder: {}", params.outputFolder());
        logger.info("  Modality: {}", params.modality());
        logger.info("  Expected rectangles: {}", params.expectedRectangles());
        logger.info("  Saturation threshold: {}", params.saturationThreshold());
        logger.info("  Value threshold: {}", params.valueThreshold());
        if (params.calibrationName() != null) {
            logger.info("  Calibration name: {}", params.calibrationName());
        }

        // Create progress dialog
        Alert progressDialog = new Alert(Alert.AlertType.INFORMATION);
        progressDialog.setTitle("Calibration In Progress");
        progressDialog.setHeaderText("Starburst Calibration Running");

        javafx.scene.control.Label progressLabel = new javafx.scene.control.Label(
                "Acquiring calibration image and processing...\n\n" +
                "This typically completes in a few seconds."
        );
        progressLabel.setStyle("-fx-font-size: 12px;");

        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10);
        content.getChildren().add(progressLabel);
        progressDialog.getDialogPane().setContent(content);

        progressDialog.getButtonTypes().clear();
        progressDialog.getButtonTypes().add(ButtonType.CANCEL);

        // Show progress dialog
        Platform.runLater(() -> progressDialog.show());

        try {
            // Get microscope configuration
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            String serverHost = QPPreferenceDialog.getMicroscopeServerHost();
            int serverPort = QPPreferenceDialog.getMicroscopeServerPort();

            // Connect to microscope server
            logger.info("Connecting to microscope server at {}:{}", serverHost, serverPort);
            MicroscopeSocketClient socketClient = new MicroscopeSocketClient(serverHost, serverPort);
            socketClient.connect();

            logger.info("Sending starburst calibration command...");

            // Call the socket client method
            String resultJson = socketClient.runStarburstCalibration(
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

            // Close progress dialog
            Platform.runLater(() -> progressDialog.close());

            // Show results dialog
            CalibrationResultDialog.showResult(resultData);

        } catch (Exception e) {
            logger.error("Starburst calibration failed", e);

            // Close progress dialog
            Platform.runLater(() -> progressDialog.close());

            // Show error result
            CalibrationResultData errorResult = CalibrationResultData.failure(e.getMessage());
            CalibrationResultDialog.showResult(errorResult);
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

            if (success) {
                double rSquared = result.has("r_squared") ?
                        result.get("r_squared").getAsDouble() : 0.0;
                int rectanglesDetected = result.has("rectangles_detected") ?
                        result.get("rectangles_detected").getAsInt() : 0;
                String plotPath = result.has("plot_path") ?
                        result.get("plot_path").getAsString() : null;
                String calibrationPath = result.has("calibration_path") ?
                        result.get("calibration_path").getAsString() : null;
                String imagePath = result.has("image_path") ?
                        result.get("image_path").getAsString() : null;

                List<String> warnings = new ArrayList<>();
                if (result.has("warnings") && result.get("warnings").isJsonArray()) {
                    JsonArray warningsArray = result.getAsJsonArray("warnings");
                    for (int i = 0; i < warningsArray.size(); i++) {
                        warnings.add(warningsArray.get(i).getAsString());
                    }
                }

                return CalibrationResultData.success(rSquared, rectanglesDetected,
                        plotPath, calibrationPath, imagePath, warnings);
            } else {
                String error = result.has("error") ?
                        result.get("error").getAsString() : "Unknown error";
                return CalibrationResultData.failure(error);
            }
        } catch (Exception e) {
            logger.error("Failed to parse calibration result JSON", e);
            return CalibrationResultData.failure("Failed to parse server response: " + e.getMessage());
        }
    }
}
