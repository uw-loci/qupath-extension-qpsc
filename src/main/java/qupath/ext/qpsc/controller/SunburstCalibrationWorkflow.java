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
import qupath.ext.qpsc.ui.ThresholdPreviewDialog;
import qupath.fx.dialogs.Dialogs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SunburstCalibrationWorkflow - Create hue-to-angle calibration from a PPM reference slide.
 *
 * This workflow provides a way to calibrate PPM hue-to-angle mapping using a
 * PPM reference slide with sunburst/fan pattern:
 * <ol>
 *   <li>User positions calibration slide under microscope</li>
 *   <li>Dialog collects parameters (modality, detection thresholds, radial sampling)</li>
 *   <li>Single image acquired using modality's exposure settings</li>
 *   <li>ppm_library's RadialCalibrator samples along spokes and fits regression</li>
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
        logger.info("  Number of spokes: {}", params.expectedSpokes());
        logger.info("  Saturation threshold: {}", params.saturationThreshold());
        logger.info("  Value threshold: {}", params.valueThreshold());
        logger.info("  Radius inner: {}", params.radiusInner());
        logger.info("  Radius outer: {}", params.radiusOuter());
        if (params.calibrationName() != null) {
            logger.info("  Calibration name: {}", params.calibrationName());
        }

        try {
            // Get microscope configuration
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();

            // Use shared MicroscopeController connection (don't create new one)
            // This avoids CONFIG conflicts with existing connections
            MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

            // Ensure we're connected
            if (!MicroscopeController.getInstance().isConnected()) {
                logger.info("Not connected to microscope server, connecting...");
                MicroscopeController.getInstance().connect();
            }

            logger.info("Sending sunburst calibration command...");

            // Call the socket client method
            String resultJson = socketClient.runSunburstCalibration(
                    configFileLocation,
                    params.outputFolder(),
                    params.modality(),
                    params.expectedSpokes(),
                    params.saturationThreshold(),
                    params.valueThreshold(),
                    params.calibrationName(),
                    params.radiusInner(),
                    params.radiusOuter(),
                    null, null, null
            );

            logger.info("Received calibration result JSON");

            // Parse JSON result
            CalibrationResultData resultData = parseCalibrationResult(resultJson);

            // Build center retry callback if we have an image path
            String resultImagePath = resultData.imagePath();
            CalibrationResultDialog.CenterRetryCallback centerRetryCallback = null;
            if (resultImagePath != null) {
                centerRetryCallback = (cy, cx) -> retryWithCenter(params, resultImagePath, cy, cx);
            }

            // Build threshold tuning callback if we have an image path
            Runnable tuneThresholdsCallback = null;
            if (resultImagePath != null) {
                tuneThresholdsCallback = buildTuneThresholdsCallback(params, resultImagePath);
            }

            // Close progress dialog and show results (on FX thread)
            // Pass redo callback to re-launch the workflow from the parameter dialog
            final CalibrationResultDialog.CenterRetryCallback finalCenterRetry = centerRetryCallback;
            final Runnable finalTuneThresholds = tuneThresholdsCallback;
            Platform.runLater(() -> {
                progressDialog.close();
                CalibrationResultDialog.showResult(resultData,
                        SunburstCalibrationWorkflow::run, finalCenterRetry, finalTuneThresholds);
            });

        } catch (Exception e) {
            logger.error("Sunburst calibration failed", e);

            // Close progress dialog and show error (on FX thread)
            Platform.runLater(() -> {
                progressDialog.close();
                CalibrationResultData errorResult = CalibrationResultData.failure(
                        e.getMessage(), null, null, params.outputFolder());
                CalibrationResultDialog.showResult(errorResult,
                        SunburstCalibrationWorkflow::run, null, null);
            });
        }
        // Note: Don't disconnect - we're using the shared MicroscopeController connection
    }

    /**
     * Builds a callback that opens the ThresholdPreviewDialog and re-runs calibration
     * with the tuned thresholds using the existing image (no re-acquisition needed).
     *
     * @param params Original calibration parameters
     * @param imagePath Path to the existing calibration image
     * @return Runnable callback for the "Tune Thresholds" button
     */
    private static Runnable buildTuneThresholdsCallback(SunburstCalibrationParams params, String imagePath) {
        return () -> {
            ThresholdPreviewDialog.showDialog(
                    imagePath,
                    params.saturationThreshold(),
                    params.valueThreshold()
            ).thenAccept(thresholdResult -> {
                if (thresholdResult == null) {
                    logger.info("Threshold tuning cancelled");
                    return;
                }

                logger.info("Re-running calibration with tuned thresholds: sat={}, val={}",
                        thresholdResult.saturationThreshold(), thresholdResult.valueThreshold());

                // Create updated params with new thresholds
                SunburstCalibrationParams updatedParams = new SunburstCalibrationParams(
                        params.outputFolder(),
                        params.modality(),
                        params.expectedSpokes(),
                        thresholdResult.saturationThreshold(),
                        thresholdResult.valueThreshold(),
                        params.calibrationName(),
                        params.radiusInner(),
                        params.radiusOuter()
                );

                // Run calibration with existing image (skip acquisition)
                Platform.runLater(() -> {
                    Alert progressDialog = new Alert(Alert.AlertType.INFORMATION);
                    progressDialog.setTitle("Re-analyzing Calibration");
                    progressDialog.setHeaderText("PPM Reference Slide Calibration");

                    Label progressLabel = new Label(
                            String.format("Re-analyzing with tuned thresholds (sat=%.3f, val=%.3f)...",
                                    thresholdResult.saturationThreshold(),
                                    thresholdResult.valueThreshold())
                    );
                    progressLabel.setStyle("-fx-font-size: 12px;");

                    VBox content = new VBox(10);
                    content.getChildren().add(progressLabel);
                    progressDialog.getDialogPane().setContent(content);
                    progressDialog.getButtonTypes().clear();
                    progressDialog.getButtonTypes().add(ButtonType.CANCEL);
                    progressDialog.show();

                    CompletableFuture.runAsync(() -> {
                        executeCalibrationWithExistingImage(updatedParams, imagePath, progressDialog);
                    }).exceptionally(ex -> {
                        logger.error("Threshold-tuned calibration failed", ex);
                        Platform.runLater(() -> {
                            progressDialog.close();
                            Dialogs.showErrorMessage("PPM Calibration Error",
                                    "Failed to re-run calibration: " + ex.getMessage());
                        });
                        return null;
                    });
                });
            }).exceptionally(ex -> {
                logger.error("Threshold preview dialog failed", ex);
                return null;
            });
        };
    }

    /**
     * Executes calibration using an existing image (skips acquisition).
     * Called from background thread - all UI updates must use Platform.runLater.
     *
     * @param params Calibration parameters (with updated thresholds)
     * @param imagePath Path to the existing calibration image
     * @param progressDialog Progress dialog to close when done
     */
    private static void executeCalibrationWithExistingImage(SunburstCalibrationParams params,
                                                             String imagePath, Alert progressDialog) {
        try {
            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

            if (!MicroscopeController.getInstance().isConnected()) {
                MicroscopeController.getInstance().connect();
            }

            // Pass the existing image path so the server skips acquisition
            String resultJson = socketClient.runSunburstCalibration(
                    configFileLocation,
                    params.outputFolder(),
                    params.modality(),
                    params.expectedSpokes(),
                    params.saturationThreshold(),
                    params.valueThreshold(),
                    params.calibrationName(),
                    params.radiusInner(),
                    params.radiusOuter(),
                    imagePath, null, null
            );

            CalibrationResultData resultData = parseCalibrationResult(resultJson);

            // Build callbacks for the new result
            String resultImagePath = resultData.imagePath() != null ?
                    resultData.imagePath() : imagePath;

            CalibrationResultDialog.CenterRetryCallback centerRetryCallback =
                    (cy, cx) -> retryWithCenter(params, resultImagePath, cy, cx);

            Runnable tuneThresholdsCallback = buildTuneThresholdsCallback(params, resultImagePath);

            Platform.runLater(() -> {
                progressDialog.close();
                CalibrationResultDialog.showResult(resultData,
                        SunburstCalibrationWorkflow::run, centerRetryCallback, tuneThresholdsCallback);
            });

        } catch (Exception e) {
            logger.error("Calibration with existing image failed", e);
            Platform.runLater(() -> {
                progressDialog.close();
                CalibrationResultData errorResult = CalibrationResultData.failure(
                        e.getMessage(), imagePath, null, params.outputFolder());

                Runnable tuneCallback = buildTuneThresholdsCallback(params, imagePath);
                CalibrationResultDialog.CenterRetryCallback retryCallback =
                        (cy, cx) -> retryWithCenter(params, imagePath, cy, cx);

                CalibrationResultDialog.showResult(errorResult,
                        SunburstCalibrationWorkflow::run, retryCallback, tuneCallback);
            });
        }
    }

    /**
     * Retries calibration using the same image but with a manually selected center point.
     * Shows a progress dialog while re-analyzing, then displays results with the same callbacks.
     *
     * @param params Original calibration parameters
     * @param imagePath Path to the existing calibration image
     * @param centerY Y pixel coordinate of the manually selected center
     * @param centerX X pixel coordinate of the manually selected center
     */
    private static void retryWithCenter(SunburstCalibrationParams params, String imagePath,
                                         int centerY, int centerX) {
        logger.info("Retrying calibration with manual center: Y={}, X={}, image={}", centerY, centerX, imagePath);

        Platform.runLater(() -> {
            Alert progressDialog = new Alert(Alert.AlertType.INFORMATION);
            progressDialog.setTitle("Re-analyzing Calibration");
            progressDialog.setHeaderText("PPM Reference Slide Calibration");

            Label progressLabel = new Label(
                    String.format("Re-analyzing with manually selected center (Y=%d, X=%d)...",
                            centerY, centerX)
            );
            progressLabel.setStyle("-fx-font-size: 12px;");

            VBox content = new VBox(10);
            content.getChildren().add(progressLabel);
            progressDialog.getDialogPane().setContent(content);

            progressDialog.getButtonTypes().clear();
            progressDialog.getButtonTypes().add(ButtonType.CANCEL);

            progressDialog.show();

            CompletableFuture.runAsync(() -> {
                try {
                    String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    MicroscopeSocketClient socketClient = MicroscopeController.getInstance().getSocketClient();

                    if (!MicroscopeController.getInstance().isConnected()) {
                        MicroscopeController.getInstance().connect();
                    }

                    String resultJson = socketClient.runSunburstCalibration(
                            configFileLocation,
                            params.outputFolder(),
                            params.modality(),
                            params.expectedSpokes(),
                            params.saturationThreshold(),
                            params.valueThreshold(),
                            params.calibrationName(),
                            params.radiusInner(),
                            params.radiusOuter(),
                            imagePath, centerY, centerX
                    );

                    CalibrationResultData resultData = parseCalibrationResult(resultJson);

                    // Build callbacks for further retries
                    String resultImagePath = resultData.imagePath() != null ?
                            resultData.imagePath() : imagePath;
                    CalibrationResultDialog.CenterRetryCallback centerRetryCallback =
                            (cy, cx) -> retryWithCenter(params, resultImagePath, cy, cx);
                    Runnable tuneThresholdsCallback = buildTuneThresholdsCallback(params, resultImagePath);

                    Platform.runLater(() -> {
                        progressDialog.close();
                        CalibrationResultDialog.showResult(resultData,
                                SunburstCalibrationWorkflow::run, centerRetryCallback, tuneThresholdsCallback);
                    });

                } catch (Exception e) {
                    logger.error("Center retry calibration failed", e);
                    Platform.runLater(() -> {
                        progressDialog.close();
                        CalibrationResultData errorResult = CalibrationResultData.failure(
                                e.getMessage(), imagePath, null, params.outputFolder());
                        CalibrationResultDialog.CenterRetryCallback retryCallback =
                                (cy, cx) -> retryWithCenter(params, imagePath, cy, cx);
                        Runnable tuneCallback = buildTuneThresholdsCallback(params, imagePath);
                        CalibrationResultDialog.showResult(errorResult,
                                SunburstCalibrationWorkflow::run, retryCallback, tuneCallback);
                    });
                }
            }).exceptionally(ex -> {
                logger.error("Center retry calibration failed unexpectedly", ex);
                Platform.runLater(() -> {
                    progressDialog.close();
                    Dialogs.showErrorMessage("PPM Calibration Error",
                            "Failed to retry calibration: " + ex.getMessage());
                });
                return null;
            });
        });
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
                int spokesDetected = result.has("spokes_detected") ?
                        result.get("spokes_detected").getAsInt() : 0;
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

                return CalibrationResultData.success(rSquared, spokesDetected,
                        plotPath, calibrationPath, imagePath, maskPath, warnings);
            } else {
                String error = result.has("error") ?
                        result.get("error").getAsString() : "Unknown error";
                String outputFolder = result.has("output_folder") &&
                        !result.get("output_folder").isJsonNull() ?
                        result.get("output_folder").getAsString() : null;
                return CalibrationResultData.failure(error, imagePath, maskPath, outputFolder);
            }
        } catch (Exception e) {
            logger.error("Failed to parse calibration result JSON", e);
            return CalibrationResultData.failure("Failed to parse server response: " + e.getMessage(),
                    null, null, null);
        }
    }
}
