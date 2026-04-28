package qupath.ext.qpsc.modality.ppm;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.BackgroundValidationResult;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityMenuItem;
import qupath.ext.qpsc.modality.ppm.ui.PPMAngleSelectionController;
import qupath.ext.qpsc.modality.ppm.ui.PPMBoundingBoxUI;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.lib.images.ImageData;

/**
 * Modality handler for Polarized light Microscopy (PPM) multi-angle acquisition sequences.
 *
 * <p>This handler manages PPM imaging workflows that require multiple polarizer rotation
 * angles with specific exposure times for each angle. PPM is commonly used for analyzing
 * birefringent materials, collagen organization, and other optically anisotropic structures.</p>
 *
 * <p>The handler supports decimal exposure times for precise control of illumination duration
 * at each polarizer angle. Typical PPM sequences include crossed polarizers (0 deg), positive
 * and negative angles (+/-5 deg), and uncrossed polarizers (~90 deg) with different exposure requirements.</p>
 *
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li>Automatic angle sequence loading from microscope configuration</li>
 *   <li>Decimal precision exposure times (e.g., 1.2ms, 500.0ms, 0.8ms)</li>
 *   <li>User-customizable angle overrides via {@link PPMBoundingBoxUI}</li>
 *   <li>Integration with {@link RotationManager} for hardware-specific angle conversion</li>
 *   <li>Post-processing directory discovery for birefringence and sum images</li>
 *   <li>Background validation with angle-specific exposure mismatch detection</li>
 *   <li>Dynamic menu contributions for PPM-specific calibration workflows</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 * @see qupath.ext.qpsc.modality.ModalityHandler
 * @see qupath.ext.qpsc.modality.ppm.ui.PPMBoundingBoxUI
 * @see RotationManager
 */
public class PPMModalityHandler implements ModalityHandler {

    private static final Logger logger = LoggerFactory.getLogger(PPMModalityHandler.class);

    /** PPM expects every angle's tile to fill the dynamic range -- dim tiles indicate stale WB calibration. */
    @Override
    public boolean expectsUniformBrightness() {
        return true;
    }

    /**
     * PPM uncrossed (~90 deg) is intentionally bright, often acting as
     * background reference -- saturation there is normal/expected. Small
     * polarisation angles (+/-7) and crossed (0) are low-signal where
     * saturation indicates a real defect (sample artefact, calibration
     * drift). Stays in lock-step with the Python helper
     * {@code _saturation_role_for(modality, angle)} which uses the same
     * 2-degree tolerance for uncrossed detection.
     */
    @Override
    public SaturationRole classifyAngleSaturation(double angleDeg) {
        if (Math.abs(Math.abs(angleDeg) - 90.0) < 2.0) {
            return SaturationRole.SIGNAL_HIGH;
        }
        return SaturationRole.SIGNAL_LOW;
    }

    /**
     * PPM treats the JAI's R/G/B as three filtered views of the same field;
     * the user does not care which channel saturated, only that any did.
     * Aggregating into a single worst-channel column matches that mental
     * model and keeps the QuPath measurement table tidy.
     */
    @Override
    public ChannelDisplay channelDisplay(boolean rgbCamera) {
        return ChannelDisplay.AGGREGATE;
    }

    /**
     * Retrieves PPM rotation angles and their associated decimal exposure times.
     *
     * <p>This method loads the PPM angle sequence from the microscope configuration
     * via {@link RotationManager}. The returned angles include both the hardware tick
     * values and precise decimal exposure times for each polarizer position.</p>
     *
     * @param modalityName the PPM modality identifier (e.g., "ppm_20x", "ppm_40x")
     * @param objective the objective ID for hardware-specific parameter lookup
     * @param detector the detector ID for hardware-specific parameter lookup
     * @return a future containing the angle-exposure pairs for this PPM configuration
     */
    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector, String wbMode) {
        RotationManager rotationManager = new RotationManager(modalityName, objective, detector);
        return rotationManager.getRotationTicksWithExposure(modalityName, wbMode);
    }

    @Override
    public String getDisplayName() {
        return "PPM";
    }

    /**
     * Returns BRIGHTFIELD_H_E as the preferred image type for PPM acquisitions.
     */
    @Override
    public Optional<ImageData.ImageType> getImageType() {
        return Optional.of(ImageData.ImageType.BRIGHTFIELD_H_E);
    }

    /**
     * Creates the PPM-specific UI component for angle parameter customization.
     */
    @Override
    public Optional<BoundingBoxUI> createBoundingBoxUI() {
        return Optional.of(new PPMBoundingBoxUI());
    }

    /**
     * Applies user-specified angle overrides while preserving original decimal exposure times.
     *
     * <p>Only positive and negative tick angles are subject to override; zero-degree (crossed)
     * and uncrossed angles retain their original values.</p>
     *
     * @param angles the original PPM angle-exposure sequence with decimal exposure times
     * @param overrides map containing "plus" and/or "minus" angle replacements
     * @return new angle sequence with overrides applied, preserving original exposures
     */
    @Override
    public List<AngleExposure> applyAngleOverrides(List<AngleExposure> angles, Map<String, Double> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return angles;
        }

        List<AngleExposure> adjusted = new ArrayList<>();
        for (AngleExposure ae : angles) {
            if (ae.ticks() < 0 && overrides.containsKey("minus")) {
                adjusted.add(new AngleExposure(overrides.get("minus"), ae.exposureMs()));
            } else if (ae.ticks() > 0 && overrides.containsKey("plus")) {
                adjusted.add(new AngleExposure(overrides.get("plus"), ae.exposureMs()));
            } else {
                adjusted.add(ae);
            }
        }
        return adjusted;
    }

    // ========================================================================
    // New ModalityHandler methods (Phase 2 - PPM extraction)
    // ========================================================================

    /**
     * Loads PPM profile-specific exposure defaults for the given hardware configuration.
     */
    @Override
    public void prepareForAcquisition(String modality, String objective, String detector) {
        try {
            PPMPreferences.loadExposuresForProfile(objective, detector);
        } catch (Exception e) {
            logger.warn("Failed to load PPM exposure defaults for {}/{}: {}", objective, detector, e.getMessage());
        }
    }

    /**
     * Returns rotation angles with overrides, showing the PPM angle selection dialog
     * so the user can confirm or adjust the overridden values.
     *
     * <p>This override gets defaults (without dialog), applies overrides,
     * then shows the PPMAngleSelectionController dialog with the overridden
     * values pre-filled for user confirmation.</p>
     */
    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAnglesWithOverrides(
            String modality, String objective, String detector, Map<String, Double> overrides, String wbMode) {

        if (overrides == null || overrides.isEmpty()) {
            // No overrides -- use normal flow (which shows the dialog)
            return getRotationAngles(modality, objective, detector, wbMode);
        }

        // Get defaults without dialog, apply overrides, then show dialog with overridden values
        RotationManager rotationManager = new RotationManager(modality, objective, detector);
        return rotationManager.getDefaultAnglesWithExposure(modality).thenCompose(defaultAngles -> {
            List<AngleExposure> overriddenAngles = applyAngleOverrides(defaultAngles, overrides);

            // Extract overridden plus/minus/uncrossed angles for dialog initialization
            double plusAngle = 7.0, minusAngle = -7.0, uncrossedAngle = 90.0;
            for (AngleExposure ae : overriddenAngles) {
                if (ae.ticks() > 0 && ae.ticks() < 45) plusAngle = ae.ticks();
                else if (ae.ticks() < 0) minusAngle = ae.ticks();
                else if (ae.ticks() >= 45) uncrossedAngle = ae.ticks();
            }

            return PPMAngleSelectionController.showDialog(
                            plusAngle, minusAngle, uncrossedAngle, modality, objective, detector, wbMode)
                    .thenApply(dialogResult -> {
                        if (dialogResult == null) {
                            throw new RuntimeException("ANGLE_SELECTION_CANCELLED");
                        }
                        List<AngleExposure> finalAngles = new ArrayList<>();
                        for (PPMAngleSelectionController.AngleExposure ae : dialogResult.angleExposures) {
                            finalAngles.add(new AngleExposure(ae.angle, ae.exposureMs));
                        }
                        return finalAngles;
                    });
        });
    }

    /**
     * Returns post-processing directory suffixes for birefringence and sum images.
     *
     * <p>The Python acquisition side creates subdirectories ending with ".biref"
     * and ".sum" containing computed birefringence and sum images. The stitching
     * system scans for these directories and processes them as additional outputs.</p>
     */
    @Override
    public List<String> getPostProcessingDirectorySuffixes() {
        return List.of(".biref", ".sum");
    }

    /**
     * Returns 4 as the default angle count for PPM (minus, zero, plus, uncrossed).
     */
    @Override
    public int getDefaultAngleCount() {
        return 4;
    }

    @Override
    public String getDefaultWbMode() {
        return "per_angle";
    }

    @Override
    public void configureCommandBuilder(AcquisitionCommandBuilder builder) {
        // JAI 3-CCD uses prism color splitting, not Bayer mosaic.
        // Debayering must be disabled to prevent corrupting the image.
        builder.enableDebayer(false);

        // Dark-region noise suppression: pixels with combined intensity (I+ + I-)
        // below this threshold are zeroed during birefringence computation.
        int minIntensity = PPMPreferences.getBirefringenceMinIntensity();
        if (minIntensity > 0) {
            builder.birefMinIntensity(minIntensity);
        }
    }

    /**
     * Validates background settings against the selected acquisition angles.
     *
     * <p>Compares background images' angle-exposure pairs against the user's
     * acquisition angles. Reports missing backgrounds, exposure mismatches,
     * and WB mode mismatches.</p>
     */
    @Override
    public BackgroundValidationResult validateBackgroundSettings(
            BackgroundSettingsReader.BackgroundSettings backgroundSettings, List<AngleExposure> angles, String wbMode) {

        // Build maps from both sides
        Map<Double, Double> userAngleMap = new LinkedHashMap<>();
        for (AngleExposure ae : angles) {
            userAngleMap.put(ae.ticks(), ae.exposureMs());
        }

        Map<Double, Double> bgAngleMap = new LinkedHashMap<>();
        for (AngleExposure bgAe : backgroundSettings.angleExposures) {
            bgAngleMap.put(bgAe.ticks(), bgAe.exposureMs());
        }

        // Find selected angles without background
        Set<Double> anglesWithoutBackground = new HashSet<>();
        for (Double userAngle : userAngleMap.keySet()) {
            if (!bgAngleMap.containsKey(userAngle)) {
                anglesWithoutBackground.add(userAngle);
            }
        }

        // Find angles with exposure mismatches
        Set<Double> anglesWithExposureMismatches = new HashSet<>();
        double tolerance = 0.1;
        for (Double angle : userAngleMap.keySet()) {
            if (bgAngleMap.containsKey(angle)) {
                double userExposure = userAngleMap.get(angle);
                double bgExposure = bgAngleMap.get(angle);
                if (Math.abs(userExposure - bgExposure) > tolerance) {
                    anglesWithExposureMismatches.add(angle);
                }
            }
        }

        // Check WB mode mismatch
        boolean wbModeMismatch = false;
        String bgWbMode = backgroundSettings.wbMode;
        if (wbMode != null && bgWbMode != null && !wbMode.equals(bgWbMode)) {
            wbModeMismatch = true;
        }

        // Generate user-facing message
        String userMessage = generateValidationMessage(
                anglesWithoutBackground,
                anglesWithExposureMismatches,
                userAngleMap,
                bgAngleMap,
                tolerance,
                wbModeMismatch,
                bgWbMode,
                wbMode);

        return new BackgroundValidationResult(
                anglesWithoutBackground, anglesWithExposureMismatches, wbModeMismatch, bgWbMode, wbMode, userMessage);
    }

    /**
     * Returns the default exposure time for a PPM rotation angle from
     * persistent user preferences.
     *
     * <p>Maps angle ranges to the four PPM preference slots: zero (crossed),
     * positive, negative, and uncrossed.</p>
     */
    @Override
    public double getDefaultExposureForAngle(double angle) {
        if (Math.abs(angle - 0.0) < 0.001) {
            return PPMPreferences.getZeroExposureMs();
        } else if (angle > 0 && angle < 20) {
            return PPMPreferences.getPlusExposureMs();
        } else if (angle < 0 && angle > -20) {
            return PPMPreferences.getMinusExposureMs();
        } else if (angle >= 40 && angle <= 100) {
            return PPMPreferences.getUncrossedExposureMs();
        }
        return -1;
    }

    /**
     * Returns PPM-specific menu items for calibration and optimization workflows.
     */
    @Override
    public List<ModalityMenuItem> getMenuContributions() {
        return List.of(
                new ModalityMenuItem(
                        "polarizerCalibration",
                        "Polarizer Calibration (PPM)...",
                        "Calibrate the polarizer rotation stage for polarized light microscopy (PPM). "
                                + "Determines the correct rotation angles for optimal birefringence imaging.",
                        () -> qupath.ext.qpsc.modality.ppm.workflow.PolarizerCalibrationWorkflow.run()),
                new ModalityMenuItem(
                        "ppmSensitivityTest",
                        "PPM Rotation Sensitivity Test...",
                        "Test PPM rotation stage sensitivity by acquiring images at precise angles. "
                                + "Analyzes the impact of angular deviations on image quality and birefringence calculations. "
                                + "Provides comprehensive analysis reports for validation and optimization.",
                        () -> qupath.ext.qpsc.modality.ppm.workflow.PPMSensitivityTestWorkflow.run()),
                new ModalityMenuItem(
                        "birefringenceOptimization",
                        "PPM Birefringence Optimization...",
                        "Find the optimal polarizer angle for maximum birefringence signal contrast. "
                                + "Systematically tests angles by acquiring paired images (+theta, -theta) and "
                                + "computing their difference. Results include optimal angles, signal metrics, and "
                                + "visualization plots. Supports multiple exposure modes (interpolate, calibrate, fixed).",
                        () -> qupath.ext.qpsc.modality.ppm.workflow.BirefringenceOptimizationWorkflow.run()),
                new ModalityMenuItem(
                        "sunburstCalibration",
                        "PPM Reference Slide...",
                        "Create a hue-to-angle calibration from a PPM reference slide with sunburst pattern. "
                                + "Acquires an image of radial spokes and creates a linear regression mapping "
                                + "hue values to orientation angles for use in PPM analysis.",
                        () -> qupath.ext.qpsc.modality.ppm.workflow.SunburstCalibrationWorkflow.run()));
        // Analysis menu items (hue range, polarity plot, perpendicularity,
        // batch analysis, back-propagate) are provided by qupath-extension-ppm
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    /**
     * Generates a human-readable validation message for background issues.
     */
    private static String generateValidationMessage(
            Set<Double> anglesWithoutBackground,
            Set<Double> anglesWithExposureMismatches,
            Map<Double, Double> userAngleMap,
            Map<Double, Double> bgAngleMap,
            double tolerance,
            boolean wbModeMismatch,
            String bgWbMode,
            String currentWbMode) {

        StringBuilder info = new StringBuilder();

        if (wbModeMismatch) {
            info.append("  White balance mode mismatch:\n");
            info.append(String.format("    Background collected with: %s\n", bgWbMode));
            info.append(String.format("    Current acquisition mode:  %s\n", currentWbMode));
            info.append("    -> Color cast may occur if WB modes differ between background and acquisition\n");
        }

        if (!anglesWithoutBackground.isEmpty()) {
            info.append("  Selected angles without background images: ");
            anglesWithoutBackground.forEach(angle -> info.append(String.format("%.1f ", angle)));
            info.append("deg\n    -> Background correction will be DISABLED for these angles\n");
        }

        if (!anglesWithExposureMismatches.isEmpty()) {
            info.append("  Exposure time mismatches (background correction will be DISABLED):\n");
            for (Double angle : anglesWithExposureMismatches) {
                double userExposure = userAngleMap.get(angle);
                double bgExposure = bgAngleMap.get(angle);
                double diff = Math.abs(userExposure - bgExposure);
                info.append(String.format(
                        "    %.1f deg: selected %.1f ms vs background %.1f ms (diff: %.1f ms)\n",
                        angle, userExposure, bgExposure, diff));
            }
        }

        if (info.isEmpty()) {
            info.append("  Background images exist for different angles than selected");
        }

        return info.toString();
    }
}
