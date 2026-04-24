package qupath.ext.qpsc.preferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.CameraOrientation;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.StagePolarity;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

/**
 * QPPreferenceDialog
 *
 * <p>Registers and exposes the subset of extension preferences you
 * want in QuPath's Preferences Pane:
 *   - Optical flip, stage axis inversion, script paths, directories, compression, overlap, etc.
 *   - Provides typed getters so other code can simply call getStageInvertedXProperty(), etc.
 *
 * <p><b>Terminology (see CLAUDE.md "COORDINATE SYSTEM TERMINOLOGY"):</b>
 * <ul>
 *   <li><b>flipMacroX/Y</b> - Optical flip: the microscope's light path mirrors the camera
 *       image relative to the physical slide.  Used to create flipped duplicate images in QuPath.</li>
 *   <li><b>stageInvertedX/Y</b> - Stage axis inversion: positive stage commands move opposite
 *       to the visual convention.  Controls tile traversal order and affine transform sign.</li>
 * </ul>
 */
public class QPPreferenceDialog {

    private static final Logger logger = LoggerFactory.getLogger(QPPreferenceDialog.class);
    private static final String CATEGORY =
            ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings").getString("name");

    /** False if qupath-extension-tiles-to-pyramid is not installed. */
    private static boolean stitchingAvailable = true;

    // --- Preference definitions ---

    private static final BooleanProperty flipMacroXProperty =
            PathPrefs.createPersistentPreference("isFlippedXProperty", false);
    private static final BooleanProperty flipMacroYProperty =
            PathPrefs.createPersistentPreference("isFlippedYProperty", false);
    // Stage axis inversion: whether positive stage commands move opposite to the visual convention.
    // CRITICAL: The persisted key strings ("isInvertedXProperty" etc.) must NOT change --
    // they are stored in user config files and changing them would reset user preferences.
    private static final BooleanProperty stageInvertedXProperty =
            PathPrefs.createPersistentPreference("isInvertedXProperty", false);
    private static final BooleanProperty stageInvertedYProperty =
            PathPrefs.createPersistentPreference("isInvertedYProperty", true);
    // Camera orientation: 8-element dihedral group describing how the displayed
    // image is oriented relative to the sample frame. Distinct from stage polarity
    // (which is hardware wiring) and distinct from macro-image flip (which is a
    // property of loaded overview images). Stored as a string to persist the enum
    // name across sessions; parsed into a {@link qupath.ext.qpsc.utilities.CameraOrientation}
    // in {@link #getCameraOrientationProperty()}.
    private static final StringProperty cameraOrientationProperty =
            PathPrefs.createPersistentPreference("cameraOrientationProperty", "NORMAL");
    private static final StringProperty microscopeServerHostProperty =
            PathPrefs.createPersistentPreference("microscope.server.host", "127.0.0.1");

    private static final IntegerProperty microscopeServerPortProperty =
            PathPrefs.createPersistentPreference("microscope.server.port", 5000);

    private static final BooleanProperty autoConnectToServerProperty =
            PathPrefs.createPersistentPreference("microscope.autoConnectToServer", true);
    private static final StringProperty microscopeConfigFileProperty = PathPrefs.createPersistentPreference(
            "microscopeConfigFileProperty", "");

    private static final StringProperty projectsFolderProperty =
            PathPrefs.createPersistentPreference("projectsFolderProperty", "");
    private static final StringProperty tissueDetectionScriptProperty =
            PathPrefs.createPersistentPreference("tissueDetectionScriptProperty", "");

    private static final BooleanProperty saveRawTilesProperty =
            PathPrefs.createPersistentPreference("saveRawTilesProperty", false);

    private static final StringProperty tileHandlingMethodProperty =
            PathPrefs.createPersistentPreference("tileHandlingProperty", "None");
    private static final DoubleProperty tileOverlapPercentProperty =
            PathPrefs.createPersistentPreference("tileOverlapPercentProperty", 10.0);
    // LZW default: J2K (DEFAULT) produces corrupt codestreams at lower pyramid
    // levels with certain tile grid dimensions (Bio-Formats OMEPyramidWriter bug).
    // LZW is lossless, universally readable, and slightly larger files.
    private static final ObjectProperty<OMEPyramidWriter.CompressionType> compressionTypeProperty =
            PathPrefs.createPersistentPreference(
                    "compressionType", OMEPyramidWriter.CompressionType.LZW, OMEPyramidWriter.CompressionType.class);

    // Lazy-init to avoid NoClassDefFoundError if tiles-to-pyramid extension is missing.
    // The StitchingConfig.OutputFormat class comes from qupath-extension-tiles-to-pyramid,
    // and referencing it in a static initializer would prevent the entire preference class
    // from loading if that extension JAR is absent.
    private static ObjectProperty<StitchingConfig.OutputFormat> outputFormatProperty;

    private static ObjectProperty<StitchingConfig.OutputFormat> getOutputFormatPropertyInternal() {
        if (outputFormatProperty == null) {
            outputFormatProperty = PathPrefs.createPersistentPreference(
                    "stitchingOutputFormat", StitchingConfig.OutputFormat.OME_TIFF, StitchingConfig.OutputFormat.class);
        }
        return outputFormatProperty;
    }

    // Filename configuration preferences
    // Note: These control what information appears in the filename
    // ALL information is stored in QuPath metadata regardless of these settings
    private static final BooleanProperty includeObjectiveInFilenameProperty =
            PathPrefs.createPersistentPreference("FilenameIncludeObjective", false);

    private static final BooleanProperty includeModalityInFilenameProperty =
            PathPrefs.createPersistentPreference("FilenameIncludeModality", false);

    private static final BooleanProperty includeAnnotationInFilenameProperty =
            PathPrefs.createPersistentPreference("FilenameIncludeAnnotation", false);

    // Angle defaults to TRUE for multi-angle modalities like PPM
    // where distinguishing between angles is critical
    private static final BooleanProperty includeAngleInFilenameProperty =
            PathPrefs.createPersistentPreference("FilenameIncludeAngle", true);

    // Metadata propagation prefix for copying metadata from parent to child images
    private static final StringProperty metadataPropagationPrefixProperty =
            PathPrefs.createPersistentPreference("MetadataPropagationPrefix", "OCR");

    // Autofocus behavior - DANGER: May result in out-of-focus regions
    private static final BooleanProperty skipManualAutofocusProperty =
            PathPrefs.createPersistentPreference("skipManualAutofocus", false);

    // Disable ALL autofocus (standard + sweep) - DANGER: Focus drift will NOT be corrected
    private static final BooleanProperty disableAllAutofocusProperty =
            PathPrefs.createPersistentPreference("disableAllAutofocus", false);

    // Warn the user when estimated acquisition size exceeds free disk space at the save location
    private static final BooleanProperty warnOnLowDiskSpaceProperty =
            PathPrefs.createPersistentPreference("warnOnLowDiskSpace", true);

    // Suppress the exposure-too-long warning dialog in the Live Viewer Autofocus button.
    // Controlled by the "don't show again" checkbox in the dialog, not the preferences pane.
    private static final BooleanProperty suppressExposureWarningProperty =
            PathPrefs.createPersistentPreference("suppressExposureWarning", false);

    // --- Notification / Alert preferences ---
    private static final String ALERTS_CATEGORY = "QuPath SCope Alerts";
    private static final String PPM_CATEGORY = "PPM (Polarized Light Microscopy)";

    private static final BooleanProperty notificationsEnabledProperty =
            PathPrefs.createPersistentPreference("notifications.enabled", false);
    private static final StringProperty notificationTopicProperty =
            PathPrefs.createPersistentPreference("notifications.ntfy.topic", "");
    private static final StringProperty notificationServerProperty =
            PathPrefs.createPersistentPreference("notifications.ntfy.server", "https://ntfy.sh");
    private static final BooleanProperty notifyOnAcquisitionProperty =
            PathPrefs.createPersistentPreference("notifications.event.acquisition", true);
    private static final BooleanProperty notifyOnStitchingProperty =
            PathPrefs.createPersistentPreference("notifications.event.stitching", true);
    private static final BooleanProperty notifyOnErrorsProperty =
            PathPrefs.createPersistentPreference("notifications.event.errors", true);
    private static final BooleanProperty completionBeepEnabledProperty =
            PathPrefs.createPersistentPreference("notifications.completionBeep", true);

    // Last used calibration folder for sunburst/PPM reference calibration
    // Remembers the folder from the most recent calibration workflow
    private static final StringProperty lastCalibrationFolderProperty =
            PathPrefs.createPersistentPreference("lastCalibrationFolder", "");

    // Sunburst calibration dialog settings - remembered between sessions
    private static final StringProperty sunburstLastModalityProperty =
            PathPrefs.createPersistentPreference("sunburstLastModality", "");
    private static final IntegerProperty sunburstExpectedSpokesProperty =
            PathPrefs.createPersistentPreference("sunburstExpectedRectangles", 16);
    private static final DoubleProperty sunburstSaturationThresholdProperty =
            PathPrefs.createPersistentPreference("sunburstSaturationThreshold", 0.1);
    private static final DoubleProperty sunburstValueThresholdProperty =
            PathPrefs.createPersistentPreference("sunburstValueThreshold", 0.1);
    private static final IntegerProperty sunburstRadiusInnerProperty =
            PathPrefs.createPersistentPreference("sunburstRadiusInner", 30);
    private static final IntegerProperty sunburstRadiusOuterProperty =
            PathPrefs.createPersistentPreference("sunburstRadiusOuter", 150);

    // Unified single-point acquisition dialog (successor to StackTimeLapseWorkflow).
    // Default true as of the menu cutover: the Utilities > Z-Stack / Time-Lapse menu
    // routes to SinglePointAcquisitionController. Set false to fall back to the
    // legacy StackTimeLapseWorkflow if the new path misbehaves in production.
    private static final BooleanProperty singlePointDialogEnabledProperty =
            PathPrefs.createPersistentPreference("qpsc.experimental.singlePointDialog", true);

    /**
     * Register all preferences in QuPath's PreferencePane. Call once during extension installation.
     */
    public static void installPreferences(QuPathGUI qupath) {
        if (qupath == null) return;
        ObservableList<org.controlsfx.control.PropertySheet.Item> items =
                qupath.getPreferencePane().getPropertySheet().getItems();

        items.add(new PropertyItemBuilder<>(flipMacroXProperty, Boolean.class)
                .name("Flip macro image X")
                .category(CATEGORY)
                .description("Flip macro/overview image horizontally for coordinate alignment. "
                        + "For per-detector optical flip, configure flip_x in resources_LOCI.yml. "
                        + "This setting applies to overview images from slide scanners and serves "
                        + "as a fallback when detector-specific flip is not configured.")
                .build());
        items.add(new PropertyItemBuilder<>(flipMacroYProperty, Boolean.class)
                .name("Flip macro image Y")
                .category(CATEGORY)
                .description("Flip macro/overview image vertically for coordinate alignment. "
                        + "For per-detector optical flip, configure flip_y in resources_LOCI.yml. "
                        + "This setting applies to overview images from slide scanners and serves "
                        + "as a fallback when detector-specific flip is not configured.")
                .build());
        items.add(new PropertyItemBuilder<>(stageInvertedXProperty, Boolean.class)
                .name("Inverted X stage")
                .category(CATEGORY)
                .description("Stage X axis is inverted: positive X commands move left instead of right.\n"
                        + "This controls tile traversal order and coordinate transform sign.\n"
                        + "NOT the same as optical flip (Flip macro image X).")
                .build());
        items.add(new PropertyItemBuilder<>(stageInvertedYProperty, Boolean.class)
                .name("Inverted Y stage")
                .category(CATEGORY)
                .description("Stage Y axis is inverted: positive Y commands move down instead of up.\n"
                        + "This controls tile traversal order and coordinate transform sign.\n"
                        + "NOT the same as optical flip (Flip macro image Y).")
                .build());

        // Camera orientation enum (8-element dihedral group). Combines with the
        // stage inversion booleans above via StageImageTransform to produce a
        // single consistent relationship between user gestures and stage commands.
        List<String> cameraOrientationChoices = new ArrayList<>();
        for (CameraOrientation o : CameraOrientation.values()) {
            cameraOrientationChoices.add(o.name());
        }
        items.add(new PropertyItemBuilder<>(cameraOrientationProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .name("Camera orientation")
                .category(CATEGORY)
                .choices(cameraOrientationChoices)
                .description("Describes the NET OPTICAL RELATIONSHIP between your sample and the displayed image.\n"
                        + "Composes with Inverted X/Y stage to form the full stage <-> image coordinate transform\n"
                        + "used by arrow buttons, joystick, double-click-to-center, and the stitcher.\n"
                        + "\n"
                        + "IMPORTANT: This is NOT a command to flip or rotate your Live Viewer image. The Live\n"
                        + "Viewer keeps displaying exactly the same pixels regardless of this setting. What\n"
                        + "changes is how the software interprets direction signs for stage moves and tile\n"
                        + "placement. This setting is also NOT about whether you physically rotated the camera\n"
                        + "hardware -- your scope may have an image flip inherent to its optics (a dichroic, a\n"
                        + "mirror, an adapter, or sensor wiring) that you never installed.\n"
                        + "\n"
                        + "Axis-aligned values (fully supported by all subsystems including the stitcher):\n"
                        + "  NORMAL          - sample +X -> display right, sample +Y -> display down\n"
                        + "  FLIP_H          - net horizontal flip in optical path (sample +X -> display LEFT)\n"
                        + "  FLIP_V          - net vertical flip (sample +Y -> display UP)\n"
                        + "  ROT_180         - image is upside-down relative to the stage frame\n"
                        + "\n"
                        + "Rotation / transpose values (arrows + joystick + click work, BUT the stitcher logs\n"
                        + "an error and falls back to an axis-aligned approximation -- use only if your camera\n"
                        + "sensor is physically rotated 90 degrees):\n"
                        + "  ROT_90_CW / ROT_90_CCW / TRANSPOSE / ANTI_TRANSPOSE\n"
                        + "\n"
                        + "How to configure:\n"
                        + "  1. Start with NORMAL.\n"
                        + "  2. Test the four gestures in the Live Viewer: arrows, joystick, double-click,\n"
                        + "     and a small 2x2 stitched acquisition.\n"
                        + "  3. If the live gestures work but only stitched X is mirrored, try FLIP_H.\n"
                        + "     If only stitched Y is mirrored, try FLIP_V. If both, try ROT_180.\n"
                        + "  4. Changing this value does NOT change what the Live Viewer shows -- it only\n"
                        + "     affects the sign math. Do not hesitate to pick FLIP_H just because 'nothing\n"
                        + "     looks flipped' in the live image.\n"
                        + "\n"
                        + "Diagnostic: QPSC logs 'Live-view coordinate transform at startup: ...' to the\n"
                        + "QuPath log at extension load time. Check Help -> Show Log to see the current\n"
                        + "effective transform and the resulting stitcher flip flags.")
                .build());

        items.add(new PropertyItemBuilder<>(microscopeConfigFileProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.FILE)
                .name("Microscope Config File")
                .category(CATEGORY)
                .description("Path to YAML config describing your microscope setup.\n\n"
                        + "[!] REQUIRED: This must be set before connecting to the microscope server.\n"
                        + "[!] CRITICAL: Using the wrong config could damage the microscope!")
                .build());

        items.add(new PropertyItemBuilder<>(projectsFolderProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.DIRECTORY)
                .name("Projects Folder")
                .category(CATEGORY)
                .description("Root folder where slide projects and data are stored.")
                .build());

        items.add(new PropertyItemBuilder<>(tissueDetectionScriptProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.FILE)
                .name("Tissue Detection Script")
                .category(CATEGORY)
                .description("Groovy script for tissue detection before imaging.")
                .build());

        items.add(new PropertyItemBuilder<>(tileHandlingMethodProperty, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .name("Tile Handling Method")
                .choices(Arrays.asList("None", "Zip", "Delete"))
                .category(CATEGORY)
                .description("How to handle intermediate tiles: none, zip them, or delete them.")
                .build());
        items.add(new PropertyItemBuilder<>(saveRawTilesProperty, Boolean.class)
                .name("Save Raw Tiles")
                .category(CATEGORY)
                .description("Save unprocessed (pre-background-correction) tile images.\n"
                        + "Useful for troubleshooting background subtraction.\n"
                        + "WARNING: Doubles file I/O time during acquisition.")
                .build());
        items.add(new PropertyItemBuilder<>(singlePointDialogEnabledProperty, Boolean.class)
                .name("Use new Z-Stack / Time-Lapse dialog")
                .category(CATEGORY)
                .description("Route the Utilities > Z-Stack / Time-Lapse menu to the unified "
                        + "Single-Point Acquisition dialog. Honors the selected modality "
                        + "(instead of hardcoded brightfield), emits multi-plane OME-TIFFs, "
                        + "and keeps the Live Viewer button synced with server streaming state. "
                        + "Disable to fall back to the legacy StackTimeLapseWorkflow dialog.")
                .build());
        items.add(new PropertyItemBuilder<>(tileOverlapPercentProperty, Double.class)
                .name("Tile Overlap Percent")
                .category(CATEGORY)
                .description("Overlap percentage between adjacent tiles in acquisition.")
                .build());
        // Output format MUST appear before compression type so users see the format
        // first -- compression choices are filtered based on the selected format.
        ObservableList<OMEPyramidWriter.CompressionType> compressionChoices =
                FXCollections.observableArrayList(OMEPyramidWriter.CompressionType.values());
        try {
            ObjectProperty<StitchingConfig.OutputFormat> formatProp = getOutputFormatPropertyInternal();
            items.add(new PropertyItemBuilder<>(formatProp, StitchingConfig.OutputFormat.class)
                    .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                    .choices(Arrays.asList(StitchingConfig.OutputFormat.values()))
                    .name("Stitching output format")
                    .category(CATEGORY)
                    .description("Output format for stitched images.\n"
                            + "OME-TIFF: Traditional single-file format, widely compatible.\n"
                            + "OME-ZARR: Directory format with parallel writing (2-3x faster). "
                            + "Produces many small files -- harder to copy on Windows.\n"
                            + "OME-TIFF via ZARR: Best of both -- parallel ZARR stitching for speed, "
                            + "then automatic background conversion to single-file OME-TIFF. "
                            + "Images are available immediately via ZARR while TIFF converts unattended.")
                    .build());

            // Populate compression choices for the current format
            compressionChoices.setAll(getCompressionTypesForFormat(formatProp.get()));

            // When format changes, update compression choices and fix invalid selection
            formatProp.addListener((obs, oldFormat, newFormat) -> {
                compressionChoices.setAll(getCompressionTypesForFormat(newFormat));
                if (!compressionChoices.contains(compressionTypeProperty.get())) {
                    compressionTypeProperty.set(OMEPyramidWriter.CompressionType.LZW);
                }
            });
        } catch (NoClassDefFoundError e) {
            logger.error("qupath-extension-tiles-to-pyramid is missing! "
                    + "Install it in your QuPath extensions folder. "
                    + "Stitching output format preference will be unavailable.");
            stitchingAvailable = false;
        }
        items.add(new PropertyItemBuilder<>(compressionTypeProperty, OMEPyramidWriter.CompressionType.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .choices(compressionChoices)
                .name("Compression type")
                .category(CATEGORY)
                .description("Compression for stitched output. LZW recommended for reliability.\n"
                        + "Available options depend on the selected output format.")
                .build());

        items.add(new PropertyItemBuilder<>(microscopeServerHostProperty, String.class)
                .name("Microscope Server Host")
                .category(CATEGORY)
                .description("IP address or hostname of the microscope control server")
                .build());

        items.add(new PropertyItemBuilder<>(microscopeServerPortProperty, Integer.class)
                .name("Microscope Server Port")
                .category(CATEGORY)
                .description("Port number for the microscope control server (default: 5000)")
                .build());

        items.add(new PropertyItemBuilder<>(autoConnectToServerProperty, Boolean.class)
                .name("Auto-connect to Server")
                .category(CATEGORY)
                .description("Automatically connect to microscope server when QuPath starts")
                .build());

        items.add(new PropertyItemBuilder<>(skipManualAutofocusProperty, Boolean.class)
                .name("No Manual Autofocus (Danger)")
                .category(CATEGORY)
                .description("WARNING: Enabling this setting may result in out-of-focus regions!\n\n"
                        + "When enabled, the manual autofocus dialog will never appear. "
                        + "If autofocus fails, the system will automatically retry once and then "
                        + "continue imaging with whatever focus level results.\n\n"
                        + "Only enable this for unattended acquisition where some out-of-focus "
                        + "regions are acceptable.")
                .build());

        items.add(new PropertyItemBuilder<>(disableAllAutofocusProperty, Boolean.class)
                .name("Disable All Autofocus (Danger)")
                .category(CATEGORY)
                .description("WARNING: Focus drift will NOT be corrected!\n\n"
                        + "When enabled, ALL autofocus is skipped during acquisition "
                        + "(both standard and sweep). Only use when you know the sample "
                        + "is flat and already in focus.\n\n"
                        + "This is also accessible via the Acquisition Wizard checkbox.")
                .build());

        items.add(new PropertyItemBuilder<>(warnOnLowDiskSpaceProperty, Boolean.class)
                .name("Warn On Low Disk Space")
                .category(CATEGORY)
                .description("Before an acquisition starts, compare the estimated output size "
                        + "against free space at the save location and warn if there may not "
                        + "be enough room. The estimate is approximate -- actual size varies "
                        + "with modality, compression, and per-tile metadata.\n\n"
                        + "Disable only for unattended workflows where you have manually "
                        + "verified that disk space is sufficient.")
                .build());

        // Image name contents -- what metadata to include in generated filenames.
        // All metadata is always stored in QuPath regardless of these settings.
        items.add(new PropertyItemBuilder<>(includeObjectiveInFilenameProperty, Boolean.class)
                .name("Image Name Includes Objective")
                .category(CATEGORY)
                .description("e.g. SampleName_20x_001.ome.tif")
                .build());

        items.add(new PropertyItemBuilder<>(includeModalityInFilenameProperty, Boolean.class)
                .name("Image Name Includes Modality")
                .category(CATEGORY)
                .description("e.g. SampleName_ppm_001.ome.tif")
                .build());

        items.add(new PropertyItemBuilder<>(includeAnnotationInFilenameProperty, Boolean.class)
                .name("Image Name Includes Annotation")
                .category(CATEGORY)
                .description("e.g. SampleName_Tissue_001.ome.tif")
                .build());

        items.add(new PropertyItemBuilder<>(includeAngleInFilenameProperty, Boolean.class)
                .name("Image Name Includes Angle")
                .category(CATEGORY)
                .description("e.g. SampleName_001_7.0.ome.zarr (critical for PPM)")
                .build());

        items.add(new PropertyItemBuilder<>(metadataPropagationPrefixProperty, String.class)
                .name("Metadata Propagation Prefix")
                .category(CATEGORY)
                .description("Prefix for metadata keys that should be automatically propagated from parent "
                        + "images to acquired child images.\n"
                        + "Any metadata key starting with this prefix will be copied.\n"
                        + "Default: 'OCR' (for OCR-extracted text fields)\n"
                        + "Examples: 'OCR_PatientID', 'OCR_SlideLabel' would be propagated with prefix 'OCR'")
                .build());

        // --- Alerts category ---
        items.add(new PropertyItemBuilder<>(completionBeepEnabledProperty, Boolean.class)
                .name("Play beep on completion")
                .category(ALERTS_CATEGORY)
                .description("Play a system beep when an acquisition or stitching workflow completes.")
                .build());
        items.add(new PropertyItemBuilder<>(notificationsEnabledProperty, Boolean.class)
                .name("Enable ntfy.sh notifications")
                .category(ALERTS_CATEGORY)
                .description("Send push notifications to your phone via ntfy.sh when workflows complete or fail.\n\n"
                        + "Setup: Install the ntfy app on your phone (free, Android/iOS),\n"
                        + "subscribe to a topic name, then enter the same topic below.\n"
                        + "No account or API key required.")
                .build());
        items.add(new PropertyItemBuilder<>(notificationTopicProperty, String.class)
                .name("ntfy.sh topic")
                .category(ALERTS_CATEGORY)
                .description("Topic name for ntfy.sh notifications (e.g., 'my-lab-microscope').\n"
                        + "Must match the topic you subscribed to in the ntfy phone app.\n\n"
                        + "Tip: Use a random/unique name (e.g., 'loci-ppm-a7f3x') since\n"
                        + "ntfy.sh topics are public by default.")
                .build());
        items.add(new PropertyItemBuilder<>(notificationServerProperty, String.class)
                .name("ntfy.sh server")
                .category(ALERTS_CATEGORY)
                .description("ntfy.sh server URL. Default: https://ntfy.sh (free hosted service).\n"
                        + "Change this to your self-hosted ntfy server URL for institutional use.")
                .build());
        items.add(new PropertyItemBuilder<>(notifyOnAcquisitionProperty, Boolean.class)
                .name("Notify on: Acquisition complete")
                .category(ALERTS_CATEGORY)
                .description("Send a notification when an acquisition finishes successfully.")
                .build());
        items.add(new PropertyItemBuilder<>(notifyOnStitchingProperty, Boolean.class)
                .name("Notify on: Stitching complete")
                .category(ALERTS_CATEGORY)
                .description("Send a notification when stitching finishes successfully.")
                .build());
        items.add(new PropertyItemBuilder<>(notifyOnErrorsProperty, Boolean.class)
                .name("Notify on: Errors")
                .category(ALERTS_CATEGORY)
                .description("Send a notification when an acquisition or stitching workflow fails.")
                .build());

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
                        + "hue values to orientation angles.")
                .build());
        items.add(new PropertyItemBuilder<>(lastCalibrationFolderProperty, String.class)
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
        // Analysis preferences (birefringence threshold, histogram bins, saturation,
        // value, dilation, TACS) are registered by the PPM Analysis extension.
    }

    // --- Typed getters for use throughout your code ---
    public static boolean getFlipMacroXProperty() {
        return flipMacroXProperty.get();
    }

    public static boolean getFlipMacroYProperty() {
        return flipMacroYProperty.get();
    }

    /** Returns true if the stage X axis is inverted (stage inversion, not optical flip). */
    public static boolean getStageInvertedXProperty() {
        return stageInvertedXProperty.get();
    }

    /** Returns true if the stage Y axis is inverted (stage inversion, not optical flip). */
    public static boolean getStageInvertedYProperty() {
        return stageInvertedYProperty.get();
    }

    /**
     * Returns the composite {@link StagePolarity} derived from the per-axis
     * boolean preferences. This is the preferred way to access stage polarity
     * in new code -- the two individual boolean prefs are retained for UI
     * editing and backwards compatibility.
     */
    public static StagePolarity getStagePolarityProperty() {
        return StagePolarity.fromBooleans(stageInvertedXProperty.get(), stageInvertedYProperty.get());
    }

    /**
     * Returns the persisted {@link CameraOrientation}. Falls back to
     * {@link CameraOrientation#NORMAL} if the stored value is missing or
     * unrecognised (e.g. after a rollback or enum rename), so no upgrade
     * path can leave the preference in an invalid state.
     */
    public static CameraOrientation getCameraOrientationProperty() {
        String stored = cameraOrientationProperty.get();
        if (stored == null || stored.isEmpty()) {
            return CameraOrientation.NORMAL;
        }
        try {
            return CameraOrientation.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return CameraOrientation.NORMAL;
        }
    }

    /** Setter used by the preferences dialog UI. */
    public static void setCameraOrientationProperty(CameraOrientation orientation) {
        if (orientation != null) {
            cameraOrientationProperty.set(orientation.name());
        }
    }

    public static String getMicroscopeServerHost() {
        return microscopeServerHostProperty.get();
    }

    public static void setMicroscopeServerHost(String host) {
        microscopeServerHostProperty.set(host);
    }

    public static int getMicroscopeServerPort() {
        return microscopeServerPortProperty.get();
    }

    public static void setMicroscopeServerPort(int port) {
        microscopeServerPortProperty.set(port);
    }

    public static boolean getAutoConnectToServer() {
        return autoConnectToServerProperty.get();
    }

    public static void setAutoConnectToServer(boolean autoConnect) {
        autoConnectToServerProperty.set(autoConnect);
    }

    /**
     * Returns true if manual autofocus dialogs should be bypassed.
     * WARNING: This may result in out-of-focus imaging regions.
     */
    public static boolean getSkipManualAutofocus() {
        return skipManualAutofocusProperty.get();
    }

    public static void setSkipManualAutofocus(boolean skip) {
        skipManualAutofocusProperty.set(skip);
    }

    /**
     * Returns true if ALL autofocus (standard + sweep) should be disabled.
     * WARNING: Focus drift will NOT be corrected during acquisition.
     */
    public static boolean getDisableAllAutofocus() {
        return disableAllAutofocusProperty.get();
    }

    public static BooleanProperty disableAllAutofocusProperty() {
        return disableAllAutofocusProperty;
    }

    public static void setDisableAllAutofocus(boolean disable) {
        disableAllAutofocusProperty.set(disable);
    }

    public static boolean getSuppressExposureWarning() {
        return suppressExposureWarningProperty.get();
    }

    public static void setSuppressExposureWarning(boolean suppress) {
        suppressExposureWarningProperty.set(suppress);
    }

    /**
     * Returns true if the user should be warned when estimated acquisition
     * output size exceeds free space at the save location.
     */
    public static boolean getWarnOnLowDiskSpace() {
        return warnOnLowDiskSpaceProperty.get();
    }

    public static void setWarnOnLowDiskSpace(boolean warn) {
        warnOnLowDiskSpaceProperty.set(warn);
    }

    public static String getMicroscopeConfigFileProperty() {
        return microscopeConfigFileProperty.get();
    }

    public static void setMicroscopeConfigFileProperty(String path) {
        microscopeConfigFileProperty.set(path);
    }

    public static void setServerHost(String host) {
        microscopeServerHostProperty.set(host);
    }

    public static void setServerPort(String port) {
        try {
            microscopeServerPortProperty.set(Integer.parseInt(port));
        } catch (NumberFormatException e) {
            // ignore
        }
    }

    public static String getProjectsFolderProperty() {
        return projectsFolderProperty.get();
    }

    public static String getTissueDetectionScriptProperty() {
        return tissueDetectionScriptProperty.get();
    }

    public static String getTileHandlingMethodProperty() {
        return tileHandlingMethodProperty.get();
    }

    public static boolean getSaveRawTilesProperty() {
        return saveRawTilesProperty.get();
    }

    public static Double getTileOverlapPercentProperty() {
        return tileOverlapPercentProperty.get();
    }

    public static OMEPyramidWriter.CompressionType getCompressionTypeProperty() {
        return compressionTypeProperty.get();
    }

    public static StitchingConfig.OutputFormat getOutputFormatProperty() {
        try {
            return getOutputFormatPropertyInternal().get();
        } catch (NoClassDefFoundError e) {
            logger.warn("tiles-to-pyramid not available, defaulting to OME_TIFF");
            return null;
        }
    }

    /**
     * Returns true if qupath-extension-tiles-to-pyramid is installed and stitching is available.
     */
    public static boolean isStitchingAvailable() {
        return stitchingAvailable;
    }
    // TODO should this be here?

    private static ObservableList<String> getScannerChoices() {
        List<String> choices = new ArrayList<>();

        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(microscopeConfigFileProperty.get());
            List<String> availableScanners = mgr.getAvailableScanners();

            if (!availableScanners.isEmpty()) {
                choices.addAll(availableScanners);
            } else {
                logger.warn("No scanners found in configuration, using Generic only");
                choices.add("Generic");
            }
        } catch (Exception e) {
            logger.error("Error loading scanner choices", e);
            choices.add("Generic");
        }

        return FXCollections.observableArrayList(choices);
    }

    private static List<String> getAvailableTransforms() {
        List<String> transforms = new ArrayList<>();
        transforms.add(""); // Empty option for no transform

        try {
            String configPath = getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                AffineTransformManager manager = new AffineTransformManager(new File(configPath).getParent());
                manager.getAllTransforms().forEach(t -> transforms.add(t.getName()));
            }
        } catch (Exception e) {
            logger.debug("Could not load transforms: {}", e.getMessage());
        }

        return transforms;
    }

    // Filename configuration getters
    public static boolean getFilenameIncludeObjective() {
        return includeObjectiveInFilenameProperty.get();
    }

    public static void setFilenameIncludeObjective(boolean include) {
        includeObjectiveInFilenameProperty.set(include);
    }

    public static boolean getFilenameIncludeModality() {
        return includeModalityInFilenameProperty.get();
    }

    public static void setFilenameIncludeModality(boolean include) {
        includeModalityInFilenameProperty.set(include);
    }

    public static boolean getFilenameIncludeAnnotation() {
        return includeAnnotationInFilenameProperty.get();
    }

    public static void setFilenameIncludeAnnotation(boolean include) {
        includeAnnotationInFilenameProperty.set(include);
    }

    public static boolean getFilenameIncludeAngle() {
        return includeAngleInFilenameProperty.get();
    }

    public static void setFilenameIncludeAngle(boolean include) {
        includeAngleInFilenameProperty.set(include);
    }

    /**
     * Gets the prefix used to identify metadata keys that should be propagated
     * from parent images to child images during acquisition workflows.
     *
     * @return The metadata propagation prefix (default: "OCR")
     */
    public static String getMetadataPropagationPrefix() {
        return metadataPropagationPrefixProperty.get();
    }

    /**
     * Sets the prefix used to identify metadata keys that should be propagated.
     *
     * @param prefix The prefix to use (e.g., "OCR", "LIMS_", "custom_")
     */
    public static void setMetadataPropagationPrefix(String prefix) {
        metadataPropagationPrefixProperty.set(prefix);
    }

    /**
     * Gets the last used calibration folder path.
     * Used by sunburst/PPM reference calibration workflow.
     *
     * @return Last used calibration folder path, or empty string if not set
     */
    public static String getLastCalibrationFolder() {
        return lastCalibrationFolderProperty.get();
    }

    /**
     * Sets the last used calibration folder path.
     * Called when user selects a folder in the calibration dialog.
     *
     * @param folderPath The folder path to remember
     */
    public static void setLastCalibrationFolder(String folderPath) {
        lastCalibrationFolderProperty.set(folderPath);
    }

    /**
     * Gets the default calibration folder based on configuration directory.
     * Falls back to last used folder, then to configurations directory.
     *
     * @return Default calibration folder path
     */
    public static String getDefaultCalibrationFolder() {
        // First check if we have a remembered folder
        String lastFolder = getLastCalibrationFolder();
        if (lastFolder != null && !lastFolder.isEmpty()) {
            java.io.File folder = new java.io.File(lastFolder);
            if (folder.exists() && folder.isDirectory()) {
                return lastFolder;
            }
        }

        // Fall back to configurations directory / ppm_reference_slide
        String configPath = getMicroscopeConfigFileProperty();
        if (configPath != null && !configPath.isEmpty()) {
            java.io.File configFile = new java.io.File(configPath);
            java.io.File configDir = configFile.getParentFile();
            if (configDir != null && configDir.exists()) {
                java.io.File calibDir = new java.io.File(configDir, "ppm_reference_slide");
                return calibDir.getAbsolutePath();
            }
        }

        // Last resort - user home
        return System.getProperty("user.home");
    }

    // ===== Sunburst Calibration Dialog Preferences =====

    /**
     * Gets the last selected modality for sunburst calibration.
     */
    public static String getSunburstLastModality() {
        return sunburstLastModalityProperty.get();
    }

    /**
     * Sets the last selected modality for sunburst calibration.
     */
    public static void setSunburstLastModality(String modality) {
        sunburstLastModalityProperty.set(modality);
    }

    /**
     * Gets the expected spokes count for sunburst calibration.
     */
    public static int getSunburstExpectedSpokes() {
        return sunburstExpectedSpokesProperty.get();
    }

    /**
     * Sets the expected spokes count for sunburst calibration.
     */
    public static void setSunburstExpectedSpokes(int count) {
        sunburstExpectedSpokesProperty.set(count);
    }

    /**
     * Gets the saturation threshold for sunburst calibration.
     */
    public static double getSunburstSaturationThreshold() {
        return sunburstSaturationThresholdProperty.get();
    }

    /**
     * Sets the saturation threshold for sunburst calibration.
     */
    public static void setSunburstSaturationThreshold(double threshold) {
        sunburstSaturationThresholdProperty.set(threshold);
    }

    /**
     * Gets the value threshold for sunburst calibration.
     */
    public static double getSunburstValueThreshold() {
        return sunburstValueThresholdProperty.get();
    }

    /**
     * Sets the value threshold for sunburst calibration.
     */
    public static void setSunburstValueThreshold(double threshold) {
        sunburstValueThresholdProperty.set(threshold);
    }

    /**
     * Gets the inner radius for sunburst radial sampling.
     */
    public static int getSunburstRadiusInner() {
        return sunburstRadiusInnerProperty.get();
    }

    /**
     * Sets the inner radius for sunburst radial sampling.
     */
    public static void setSunburstRadiusInner(int radius) {
        sunburstRadiusInnerProperty.set(radius);
    }

    /**
     * Gets the outer radius for sunburst radial sampling.
     */
    public static int getSunburstRadiusOuter() {
        return sunburstRadiusOuterProperty.get();
    }

    /**
     * Sets the outer radius for sunburst radial sampling.
     */
    public static void setSunburstRadiusOuter(int radius) {
        sunburstRadiusOuterProperty.set(radius);
    }

    // ===== Notification / Alert Preferences =====

    /** Returns true if ntfy.sh push notifications are enabled. */
    public static boolean getNotificationsEnabled() {
        return notificationsEnabledProperty.get();
    }

    /** Returns the ntfy.sh topic name. */
    public static String getNotificationTopic() {
        return notificationTopicProperty.get();
    }

    /** Returns the ntfy.sh server URL (default: https://ntfy.sh). */
    public static String getNotificationServer() {
        return notificationServerProperty.get();
    }

    /** Returns true if acquisition-complete notifications are enabled. */
    public static boolean getNotifyOnAcquisition() {
        return notifyOnAcquisitionProperty.get();
    }

    /** Returns true if stitching-complete notifications are enabled. */
    public static boolean getNotifyOnStitching() {
        return notifyOnStitchingProperty.get();
    }

    /** Returns true if error notifications are enabled. */
    public static boolean getNotifyOnErrors() {
        return notifyOnErrorsProperty.get();
    }

    /** Returns true if a system beep should play on workflow completion. */
    public static boolean getCompletionBeepEnabled() {
        return completionBeepEnabledProperty.get();
    }

    public static void setCompletionBeepEnabled(boolean enabled) {
        completionBeepEnabledProperty.set(enabled);
    }

    public static void setNotificationsEnabled(boolean enabled) {
        notificationsEnabledProperty.set(enabled);
    }

    public static void setNotificationTopic(String topic) {
        notificationTopicProperty.set(topic);
    }

    public static void setNotificationServer(String server) {
        notificationServerProperty.set(server);
    }

    public static void setNotifyOnAcquisition(boolean enabled) {
        notifyOnAcquisitionProperty.set(enabled);
    }

    public static void setNotifyOnStitching(boolean enabled) {
        notifyOnStitchingProperty.set(enabled);
    }

    public static void setNotifyOnErrors(boolean enabled) {
        notifyOnErrorsProperty.set(enabled);
    }

    /**
     * Returns the compression types that are valid for the given stitching output format.
     *
     * <p>OME-TIFF supports all compression types. OME-ZARR only supports a subset:
     * JPEG-2000 variants (J2K, J2K_LOSSY) have no native ZARR codec, and JPEG would
     * silently fall back to zstd which is misleading. Only types that produce the
     * expected compression algorithm are offered.
     */
    private static List<OMEPyramidWriter.CompressionType> getCompressionTypesForFormat(
            StitchingConfig.OutputFormat format) {
        if (format != null && format.stitchAsZarr()) {
            return List.of(
                    OMEPyramidWriter.CompressionType.LZW,
                    OMEPyramidWriter.CompressionType.ZLIB,
                    OMEPyramidWriter.CompressionType.UNCOMPRESSED,
                    OMEPyramidWriter.CompressionType.DEFAULT);
        }
        return Arrays.asList(OMEPyramidWriter.CompressionType.values());
    }

    /** Observable flag controlling visibility of the experimental single-point acquisition dialog. */
    public static BooleanProperty getSinglePointDialogEnabledProperty() {
        return singlePointDialogEnabledProperty;
    }

    public static boolean isSinglePointDialogEnabled() {
        return singlePointDialogEnabledProperty.get();
    }
}
