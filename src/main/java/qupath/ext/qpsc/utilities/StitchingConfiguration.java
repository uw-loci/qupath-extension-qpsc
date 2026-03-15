package qupath.ext.qpsc.utilities;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

/**
 * Centralized configuration for stitching operations across all workflows.
 * Provides consistent stitching parameters, settings, and validation.
 */
public class StitchingConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(StitchingConfiguration.class);

    /** Compression types that are only valid for OME-TIFF, not OME-ZARR. */
    private static final Set<String> TIFF_ONLY_COMPRESSION = Set.of("J2K", "J2K_LOSSY");

    /**
     * Immutable record containing all stitching configuration parameters
     */
    public record StitchingParams(
            String compressionType,
            int downsampleFactor,
            double qualityFactor,
            StitchingConfig.OutputFormat outputFormat) {}

    /**
     * Result of stitching settings validation.
     *
     * @param valid true if settings are compatible
     * @param message human-readable description of the problem (null if valid)
     */
    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

    /**
     * Validates that the current stitching preferences are compatible.
     * Call this before starting acquisition to catch problems early.
     *
     * @return ValidationResult indicating whether settings are valid
     */
    public static ValidationResult validateCurrentSettings() {
        OMEPyramidWriter.CompressionType compression = QPPreferenceDialog.getCompressionTypeProperty();
        StitchingConfig.OutputFormat outputFormat = QPPreferenceDialog.getOutputFormatProperty();

        if (outputFormat == null) {
            // tiles-to-pyramid not available - stitching won't run, but that's not a blocking error
            return ValidationResult.ok();
        }

        return validateSettings(String.valueOf(compression), outputFormat);
    }

    /**
     * Validates that a compression type and output format are compatible.
     *
     * @param compressionType compression type name (e.g., "J2K", "LZW")
     * @param outputFormat target output format
     * @return ValidationResult indicating whether settings are valid
     */
    public static ValidationResult validateSettings(String compressionType, StitchingConfig.OutputFormat outputFormat) {
        if (outputFormat != StitchingConfig.OutputFormat.OME_ZARR) {
            return ValidationResult.ok(); // All compression types work with OME-TIFF
        }

        if (TIFF_ONLY_COMPRESSION.contains(compressionType)) {
            return ValidationResult.error(String.format(
                    "%s compression is not compatible with OME-ZARR output format.\n\n"
                            + "Please change either:\n"
                            + "  - Compression to LZW, ZLIB, or DEFAULT (Edit -> Preferences -> QPSC)\n"
                            + "  - Output format to OME-TIFF",
                    compressionType));
        }

        return ValidationResult.ok();
    }

    /**
     * Creates a standard stitching configuration using current preferences.
     * This ensures consistent stitching parameters across all workflows.
     *
     * @return StitchingParams with current preference values
     */
    public static StitchingParams getStandardConfiguration() {
        String compressionType = String.valueOf(QPPreferenceDialog.getCompressionTypeProperty());
        int downsampleFactor = 1; // Standard downsample factor for all workflows
        double qualityFactor = 0.85; // Standard quality factor for compression
        StitchingConfig.OutputFormat outputFormat = QPPreferenceDialog.getOutputFormatProperty();

        return new StitchingParams(compressionType, downsampleFactor, qualityFactor, outputFormat);
    }

    /**
     * Creates a custom stitching configuration with specified parameters.
     * Use this for specialized stitching requirements.
     *
     * @param compressionType The compression type (e.g., "JPEG", "LZW", "JPEG2000")
     * @param downsampleFactor Downsample factor for the stitched image
     * @param qualityFactor Quality factor for lossy compression (0.0 to 1.0)
     * @param outputFormat Output format (OME_TIFF or OME_ZARR)
     * @return StitchingParams with custom values
     */
    public static StitchingParams createCustomConfiguration(
            String compressionType,
            int downsampleFactor,
            double qualityFactor,
            StitchingConfig.OutputFormat outputFormat) {
        return new StitchingParams(compressionType, downsampleFactor, qualityFactor, outputFormat);
    }

    /**
     * Gets the appropriate compression type based on modality requirements.
     * Some modalities may require lossless compression while others can use lossy.
     *
     * @param modalityBase The base modality name (e.g., "ppm", "brightfield")
     * @return Appropriate compression type for the modality
     */
    public static String getModalitySpecificCompression(String modalityBase) {
        // For now, use the standard compression from preferences
        // In the future, this could be extended to check modality-specific requirements
        // from the microscope configuration
        return String.valueOf(QPPreferenceDialog.getCompressionTypeProperty());
    }

    /**
     * Validates stitching settings in a retry loop, giving the user a chance to
     * fix incompatible preferences (e.g. J2K + OME-ZARR) instead of aborting
     * with an error.
     *
     * <p>If settings are already valid, returns immediately. Otherwise shows a
     * warning dialog explaining the issue and offering "Open Preferences" / "Retry"
     * / "Cancel" buttons. The loop continues until settings are valid or the user
     * cancels.</p>
     *
     * <p>This method blocks the calling thread (it is intended to be called from
     * a background/workflow thread). UI dialogs are dispatched on the FX thread
     * and the caller waits via {@link CountDownLatch}.</p>
     *
     * @return {@code true} if settings are now valid, {@code false} if the user cancelled
     */
    public static boolean validateWithRetry() {
        while (true) {
            var result = validateCurrentSettings();
            if (result.valid()) {
                return true;
            }

            logger.warn("Stitching settings invalid: {}", result.message());

            // Show warning dialog on FX thread and wait for user response
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean userWantsRetry = new AtomicBoolean(false);

            Platform.runLater(() -> {
                ButtonType retryButton = new ButtonType("Retry", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelButton = new ButtonType("Cancel Acquisition", ButtonBar.ButtonData.CANCEL_CLOSE);

                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Incompatible Stitching Settings");
                alert.setHeaderText("Please fix the stitching settings before continuing");
                alert.setContentText(result.message()
                        + "\n\nAdjust the settings in Edit -> Preferences -> QPSC, then click Retry.");
                alert.getDialogPane().setMinWidth(500);
                alert.getButtonTypes().setAll(retryButton, cancelButton);

                var response = alert.showAndWait();
                userWantsRetry.set(response.isPresent() && response.get() == retryButton);
                latch.countDown();
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            if (!userWantsRetry.get()) {
                logger.info("User cancelled due to invalid stitching settings");
                return false;
            }

            logger.info("User requested retry after adjusting stitching settings");
        }
    }

    /**
     * Async version of {@link #validateWithRetry()} for use in
     * CompletableFuture-based workflows.
     *
     * <p>Returns a future that completes with {@code true} when settings are
     * valid, or {@code false} if the user cancels.</p>
     *
     * @return CompletableFuture with validation result
     */
    public static CompletableFuture<Boolean> validateWithRetryAsync() {
        return CompletableFuture.supplyAsync(StitchingConfiguration::validateWithRetry);
    }
}
