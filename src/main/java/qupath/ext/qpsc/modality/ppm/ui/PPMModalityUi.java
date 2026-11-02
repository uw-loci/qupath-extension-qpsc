package qupath.ext.qpsc.modality.ppm.ui;

import java.util.List;
import java.util.Optional;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityMenuItem;
import qupath.ext.qpsc.modality.ModalityUi;
import qupath.ext.qpsc.modality.ppm.workflow.BirefringenceOptimizationWorkflow;
import qupath.ext.qpsc.modality.ppm.workflow.PPMSensitivityTestWorkflow;
import qupath.ext.qpsc.modality.ppm.workflow.PolarizerCalibrationWorkflow;
import qupath.ext.qpsc.modality.ppm.workflow.SunburstCalibrationWorkflow;

/** PPM's panels and menu items; the acquisition logic is {@code PPMModalityHandler}. */
public class PPMModalityUi implements ModalityUi {

    /**
     * Creates the PPM-specific UI component for angle parameter customization.
     */
    @Override
    public Optional<ModalityHandler.BoundingBoxUI> createBoundingBoxUI(String modalityName) {
        return Optional.of(new PPMBoundingBoxUI());
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
                        () -> PolarizerCalibrationWorkflow.run()),
                new ModalityMenuItem(
                        "ppmSensitivityTest",
                        "PPM Rotation Sensitivity Test...",
                        "Test PPM rotation stage sensitivity by acquiring images at precise angles. "
                                + "Analyzes the impact of angular deviations on image quality and birefringence calculations. "
                                + "Provides comprehensive analysis reports for validation and optimization.",
                        () -> PPMSensitivityTestWorkflow.run()),
                new ModalityMenuItem(
                        "birefringenceOptimization",
                        "PPM Birefringence Optimization...",
                        "Find the optimal polarizer angle for maximum birefringence signal contrast. "
                                + "Systematically tests angles by acquiring paired images (+theta, -theta) and "
                                + "computing their difference. Results include optimal angles, signal metrics, and "
                                + "visualization plots. Supports multiple exposure modes (interpolate, calibrate, fixed).",
                        () -> BirefringenceOptimizationWorkflow.run()),
                new ModalityMenuItem(
                        "sunburstCalibration",
                        "PPM Reference Slide...",
                        "Create a hue-to-angle calibration from a PPM reference slide with sunburst pattern. "
                                + "Acquires an image of radial spokes and creates a linear regression mapping "
                                + "hue values to orientation angles for use in PPM analysis.",
                        () -> SunburstCalibrationWorkflow.run()));
        // Analysis menu items (hue range, polarity plot, perpendicularity,
        // batch analysis, back-propagate) are provided by qupath-extension-ppm
    }
}
