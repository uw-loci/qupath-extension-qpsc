package qupath.ext.qpsc.modality.ppm.ui;

import java.util.List;
import java.util.Optional;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import org.controlsfx.control.PropertySheet;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityMenuItem;
import qupath.ext.qpsc.modality.ModalityUi;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.modality.ppm.workflow.BirefringenceOptimizationWorkflow;
import qupath.ext.qpsc.modality.ppm.workflow.PPMSensitivityTestWorkflow;
import qupath.ext.qpsc.modality.ppm.workflow.PolarizerCalibrationWorkflow;
import qupath.ext.qpsc.modality.ppm.workflow.SunburstCalibrationWorkflow;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;

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

    private static final String PPM_CATEGORY = "PPM (Polarized Light Microscopy)";

    /** The PPM section of QuPath's preference pane: documentation link, calibration and capture. */
    @Override
    public void addPreferenceItems(ObservableList<PropertySheet.Item> items) {
        // --- PPM category ---
        // README link (read-only, for reference)
        StringProperty ppmReadmeProperty = new SimpleStringProperty(DocumentationHelper.PPM_README_URL);
        items.add(new PropertyItemBuilder<>(ppmReadmeProperty, String.class)
                .name("PPM Extension Documentation")
                .category(PPM_CATEGORY)
                .description("URL to the PPM extension documentation on GitHub.\n"
                        + "Copy this URL to your browser, or open it from the\n"
                        + "PPM menu's help buttons.\n\n"
                        + "Contains workflow guides, calibration instructions,\n"
                        + "and troubleshooting information.")
                .build());
        items.add(new PropertyItemBuilder<>(PPMPreferences.activeCalibrationPathProperty(), String.class)
                .propertyType(PropertyItemBuilder.PropertyType.FILE)
                .name("Active Calibration File (.npz)")
                .category(PPM_CATEGORY)
                .description("Path to the active PPM sunburst calibration file (.npz).\n"
                        + "This is set automatically after a successful sunburst calibration,\n"
                        + "but can also be selected manually here if the setting was lost.\n\n"
                        + "All PPM analysis tools (Polarity Plot, Hue Range, Batch Analysis,\n"
                        + "Surface Perpendicularity) require this calibration to convert\n"
                        + "hue values to orientation angles.\n\n"
                        + "The hue-to-angle mapping is OBJECTIVE-INDEPENDENT: a calibration\n"
                        + "captured at one objective (e.g. 40x) is valid for acquisitions at\n"
                        + "any other (e.g. 20x). A higher-magnification objective gives a\n"
                        + "better-quality calibration, but the mapping itself is the same. If\n"
                        + "the filename or stamped metadata shows a different magnification\n"
                        + "than your acquisition, that only records where the calibration was\n"
                        + "captured -- it is not a mismatch.")
                .build());
        items.add(new PropertyItemBuilder<>(QPPreferenceDialog.lastCalibrationFolderProperty(), String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("Calibration Output Folder")
                .category(PPM_CATEGORY)
                .description("Default folder for sunburst calibration output files.\n"
                        + "Calibration images, .npz files, and plots are saved here.\n"
                        + "Defaults to ppm_reference_slide/ next to the microscope config.")
                .build());

        items.add(new PropertyItemBuilder<>(PPMPreferences.birefringenceMinIntensityProperty(), String.class)
                .name("Birefringence Min Intensity")
                .category(PPM_CATEGORY)
                .description("Dark-pixel noise suppression threshold for birefringence computation.\n"
                        + "Pixels with combined intensity (I+ + I-) below this value\n"
                        + "are zeroed during birefringence computation on the server.\n"
                        + "Default: 10")
                .build());

        items.add(new PropertyItemBuilder<>(PPMPreferences.highBitDepthProperty(), Boolean.class)
                .name("High Bit Depth PPM Capture")
                .category(PPM_CATEGORY)
                .description("Acquire PPM angle frames at the camera's higher-bit PixelFormat so\n"
                        + "the birefringence image is computed from genuine high-precision inputs\n"
                        + "instead of 8-bit. Reduces quantization noise in the dark crossed-polarizer\n"
                        + "regions; the birefringence file stays 16-bit. Requires the camera's\n"
                        + "high_bit_depth block to be configured in the detector YAML -- a no-op on\n"
                        + "the server otherwise. OFF keeps acquisition identical to the 8-bit path.\n"
                        + "Default: OFF")
                .build());
        // Analysis preferences (birefringence threshold, histogram bins, saturation,
        // value, dilation, TACS) are registered by the PPM Analysis extension.
    }
}
