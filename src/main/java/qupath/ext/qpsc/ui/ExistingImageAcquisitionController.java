package qupath.ext.qpsc.ui;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.AlignmentHelper;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ChannelExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.modality.WbMode;
import qupath.ext.qpsc.modality.ppm.ui.PPMBoundingBoxUI;
import qupath.ext.qpsc.modality.widefield.ui.WidefieldChannelBoundingBoxUI;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.AngleResolutionService;
import qupath.ext.qpsc.service.ChannelResolutionService;
import qupath.ext.qpsc.service.mda.MdaExportAction;
import qupath.ext.qpsc.service.mda.MdaExportContext;
import qupath.ext.qpsc.service.mda.TileStagePos;
import qupath.ext.qpsc.utilities.AcquisitionConfigurationBuilder;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.BackgroundValidityChecker;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.ObjectiveUtils;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.SampleNameValidator;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.QuPathStyleManager;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

/**
 * Consolidated acquisition controller for the Existing Image workflow.
 *
 * <p>This dialog combines all configuration options into a single scrollable interface:
 * <ul>
 *   <li>Variant banner (new project vs existing)</li>
 *   <li>Project &amp; Sample configuration</li>
 *   <li>Hardware configuration (modality, objective, detector)</li>
 *   <li>Alignment configuration (path selection, transform)</li>
 *   <li>Refinement options (none, single-tile, full manual)</li>
 *   <li>Advanced options (green box params, modality-specific)</li>
 *   <li>Acquisition preview (tile count, time, storage estimates)</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>TitledPane sections for organized configuration</li>
 *   <li>Real-time preview updates with debouncing</li>
 *   <li>Confidence-based recommendations</li>
 *   <li>Dynamic visibility based on selections</li>
 *   <li>Persistent preferences for all fields</li>
 * </ul>
 */
public class ExistingImageAcquisitionController {
    private static final Logger logger = LoggerFactory.getLogger(ExistingImageAcquisitionController.class);

    /** Debounce delay for preview updates in milliseconds */
    private static final long PREVIEW_DEBOUNCE_MS = 300;

    /** Confidence thresholds */
    private static final double HIGH_CONFIDENCE = 0.8;

    private static final double MEDIUM_CONFIDENCE = 0.5;

    /**
     * Refinement choice options.
     */
    public enum RefinementChoice {
        NONE("Proceed without refinement"),
        SINGLE_TILE("Single-tile refinement"),
        FULL_MANUAL("Full manual alignment");

        private final String displayName;

        RefinementChoice(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Result record containing all user selections from the consolidated dialog.
     */
    public record ExistingImageAcquisitionConfig(
            // Sample
            String sampleName,
            File projectsFolder,
            boolean isExistingProject,

            // Hardware
            String modality,
            String objective,
            String detector,

            // Alignment
            boolean useExistingAlignment,
            AffineTransformManager.TransformPreset selectedTransform,
            double alignmentConfidence,

            // Refinement
            RefinementChoice refinementChoice,

            // Advanced (green box params handled by ExistingAlignmentPath)
            Map<String, Double> angleOverrides,

            // Per-channel intensity overrides for channel-based widefield modalities
            Map<String, Double> channelIntensityOverrides,

            // Focus channel id for channel-based widefield modalities; null = library order
            String focusChannelId,

            // Autofocus strategy override (dense_texture / sparse_signal / dark_field /
            // manual_only); null = use autofocus_<scope>.yml per-modality binding
            String afStrategy,

            // White balance settings (JAI camera only)
            boolean enableWhiteBalance,
            boolean perAngleWhiteBalance,
            String wbMode,

            // Per-tile snap-loop inner axis. Null = omit the flag and let the
            // server fall back to its per-modality default. Values: "z",
            // "channel", "angle".
            String innerAxis) {}

    /**
     * Shows the consolidated acquisition dialog.
     *
     * @param defaultSampleName Default sample name (typically from current image)
     * @param annotations List of annotations to acquire (used for tile calculation)
     * @return CompletableFuture containing the result, or cancelled if user cancels
     */
    public static CompletableFuture<ExistingImageAcquisitionConfig> showDialog(
            String defaultSampleName, List<PathObject> annotations) {

        CompletableFuture<ExistingImageAcquisitionConfig> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                ConsolidatedDialogBuilder builder = new ConsolidatedDialogBuilder(defaultSampleName, annotations);
                Optional<ExistingImageAcquisitionConfig> result = builder.buildAndShow();

                if (result.isPresent()) {
                    future.complete(result.get());
                } else {
                    // Use completeExceptionally instead of cancel() so that the
                    // CancellationException propagates directly to exceptionally() handlers
                    // without being wrapped in a CompletionException
                    future.completeExceptionally(
                            new java.util.concurrent.CancellationException("Dialog cancelled by user"));
                }
            } catch (Exception e) {
                logger.error("Error showing consolidated acquisition dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Internal builder class that constructs and manages the consolidated dialog.
     */
    private static class ConsolidatedDialogBuilder {
        private final MicroscopeConfigManager configManager;
        private final boolean hasOpenProject;
        private final String existingProjectName;
        private final File existingProjectFolder;
        private final String defaultSampleName;
        private final List<PathObject> annotations;
        private final int annotationCount;

        // Transform data
        private List<AffineTransformManager.TransformPreset> availableTransforms;
        private AffineTransformManager transformManager;
        private boolean hasSlideAlignment; // True if _alignment.json exists for current image
        private boolean hasMacroImage; // True if a macro image is reachable from this entry

        // UI Components - Banner
        private HBox variantBanner;

        // UI Components - Project Section
        private TextField sampleNameField;
        private Label sampleNameErrorLabel;
        private TextField folderField;

        // UI Components - Hardware Section
        private ComboBox<String> modalityBox;
        private ComboBox<String> objectiveBox;
        private ComboBox<String> detectorBox;

        // UI Components - Alignment Section
        private RadioButton useExistingRadio;
        private RadioButton manualAlignRadio;
        private ComboBox<AffineTransformManager.TransformPreset> transformCombo;
        private Label confidenceLabel;
        private VBox transformSelectionBox;

        // UI Components - Refinement Section
        private ToggleGroup refinementGroup;
        private RadioButton noRefineRadio;
        private RadioButton singleTileRadio;
        private RadioButton fullManualRadio;
        private Label refinementRecommendationLabel;
        private VBox refinementBox;

        // UI Components - Advanced Section
        private ModalityHandler.BoundingBoxUI modalityUI;
        private TitledPane modalityPane;
        private VBox modalityContent;
        private ComboBox<String> afStrategyCombo;

        // Z-stack section: loop-order toggle (channel-inner / angle-inner alternative).
        private CheckBox zStackEnableCheck;
        private RadioButton loopOrderInnerZRadio;
        private RadioButton loopOrderInnerAltRadio;
        private Label loopOrderLabel;
        private VBox loopOrderRow;

        // UI Components - White Balance (hidden for non-JAI or non-WB modalities)
        private Label wbLabel;
        private ComboBox<String> wbModeComboBox;

        // UI Components - Preview Section
        private Label previewAnnotationsLabel;
        private Label previewTilesLabel;
        private Label previewTimeLabel;
        private Label previewStorageLabel;

        // UI Components - Validation
        private VBox errorSummaryPanel;
        private VBox errorListBox;
        private final Map<String, String> validationErrors = new LinkedHashMap<>();

        // TitledPanes
        private TitledPane projectPane;
        private TitledPane hardwarePane;
        private TitledPane alignmentPane;
        private TitledPane refinementPane;
        private TitledPane advancedPane;

        // Debounce timer
        private final PauseTransition previewDebounce = new PauseTransition(Duration.millis(PREVIEW_DEBOUNCE_MS));

        // Dialog and button references
        private Dialog<ExistingImageAcquisitionConfig> dialog;
        private Button startButton;

        ConsolidatedDialogBuilder(String defaultSampleName, List<PathObject> annotations) {
            this.defaultSampleName = defaultSampleName;
            this.annotations = annotations != null ? annotations : new ArrayList<>();
            this.annotationCount = this.annotations.size();
            this.configManager =
                    MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

            // Initialize transform manager
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            this.transformManager = new AffineTransformManager(new File(configPath).getParent());

            // Check if a project is already open
            QuPathGUI gui = QuPathGUI.getInstance();
            this.hasOpenProject = (gui != null && gui.getProject() != null);

            if (hasOpenProject) {
                File projectFile = gui.getProject().getPath().toFile();
                this.existingProjectFolder = projectFile.getParentFile();
                this.existingProjectName = existingProjectFolder.getName();
                logger.info("Found open project: {} in {}", existingProjectName, existingProjectFolder.getParent());
            } else {
                this.existingProjectFolder = null;
                this.existingProjectName = null;
            }

            // Load available transforms
            String microscopeName = configManager.getMicroscopeName();
            this.availableTransforms = transformManager.getTransformsForMicroscope(microscopeName);
            logger.info("Found {} transforms for microscope '{}'", availableTransforms.size(), microscopeName);

            // Check for slide-specific alignment (auto-registered from BoundingBox acquisition)
            this.hasSlideAlignment = detectSlideSpecificAlignment();
            logger.info("Slide-specific alignment for current image: {}", hasSlideAlignment);

            // Detect whether a macro image is reachable. The scanner-preset
            // "Use existing alignment" path runs green-box detection on a macro
            // to localise the WSI on the slide; without a macro that path
            // cannot proceed. QPSC-acquired images (BoundingBox stitches, etc.)
            // typically have no macro, so without this check the dialog would
            // happily route to ExistingAlignmentPath and fail at runtime with
            // "Macro Image Required". Slide-specific alignments and manual
            // alignment do NOT need a macro and remain available.
            this.hasMacroImage = (gui != null)
                    && qupath.ext.qpsc.utilities.MacroImageUtility.retrieveMacroImageWithFallback(
                                    gui, defaultSampleName)
                            != null;
            logger.info("Macro image reachable for current entry: {}", hasMacroImage);
        }

        private boolean detectSlideSpecificAlignment() {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getImageData() == null || gui.getProject() == null) return false;
            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) return false;
            @SuppressWarnings("unchecked")
            Project<java.awt.image.BufferedImage> project = (Project<java.awt.image.BufferedImage>) gui.getProject();
            java.awt.geom.AffineTransform t = AffineTransformManager.loadSlideAlignment(project, imageName);
            if (t != null) return true;
            // Fallback: an open entry stamped with BoundingBox stage metadata
            // (QPSC-acquired stitch) yields a slide-specific transform via
            // ImageMetadataManager.buildBoundingBoxPixelToStageTransform. Mirror
            // the workflow's AlignmentHelper.checkForSlideAlignment fallback so
            // the dialog routes these images through the "image has alignment"
            // path instead of forcing manual alignment.
            var openEntry = project.getEntry(gui.getImageData());
            return openEntry != null
                    && qupath.ext.qpsc.utilities.ImageMetadataManager.getBoundingBoxStageBounds(openEntry) != null;
        }

        /** Returns true when QuPath is using a dark color scheme. */
        private boolean isDark() {
            return QuPathStyleManager.getStyleColorScheme() == ColorScheme.DARK;
        }

        /** Secondary/description text style that works in both light and dark themes. */
        private String subtleTextStyle() {
            return "-fx-font-size: 10px; -fx-opacity: 0.65;";
        }

        /** Info-banner background style for a given semantic color. */
        private String bannerStyle(String semanticColor) {
            // semanticColor: "green", "blue", "yellow"
            boolean dark = isDark();
            switch (semanticColor) {
                case "green":
                    return dark
                            ? "-fx-background-color: #1B3A1B; -fx-border-color: #2E7D32; -fx-border-width: 0 0 1 0;"
                            : "-fx-background-color: #E8F5E9; -fx-border-color: #A5D6A7; -fx-border-width: 0 0 1 0;";
                case "blue":
                    return dark
                            ? "-fx-background-color: #1A2A3A; -fx-border-color: #42A5F5; -fx-border-width: 0 0 1 0;"
                            : "-fx-background-color: #E3F2FD; -fx-border-color: #90CAF9; -fx-border-width: 0 0 1 0;";
                case "yellow":
                    return dark
                            ? "-fx-background-color: #3A3000; -fx-background-radius: 4; -fx-font-size: 11px;"
                            : "-fx-background-color: #FFF9E5; -fx-background-radius: 4; -fx-font-size: 11px;";
                default:
                    return "";
            }
        }

        /** Accent text color that reads well in both themes. */
        private String accentColor(String semanticColor) {
            boolean dark = isDark();
            switch (semanticColor) {
                case "green":
                    return dark ? "#66BB6A" : "#2E7D32";
                case "blue":
                    return dark ? "#64B5F6" : "#1565C0";
                case "amber":
                    return dark ? "#FFD54F" : "#F57F17";
                case "red":
                    return dark ? "#EF5350" : "#C62828";
                default:
                    return dark ? "#B0BEC5" : "#424242";
            }
        }

        Optional<ExistingImageAcquisitionConfig> buildAndShow() {
            dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Acquire from Existing Image");
            dialog.setHeaderText("Configure acquisition from the current image");
            dialog.setGraphic(DocumentationHelper.createHelpButton("existingImage"));
            dialog.setResizable(true);

            // Add buttons. "Save MDA..." is a non-closing footer action so
            // the user can export the MicroManager MDA file set without
            // committing to a live acquisition. The per-modality panels still
            // expose the same action, but the modality TitledPane is easy to
            // overlook when scrolling -- the footer button makes it
            // unmistakable that MDA export is a first-class exit path.
            ButtonType startType = new ButtonType("Start Acquisition", ButtonBar.ButtonData.OK_DONE);
            ButtonType saveMdaType = new ButtonType("Save MDA...", ButtonBar.ButtonData.LEFT);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(saveMdaType, startType, cancelType);

            startButton = (Button) dialog.getDialogPane().lookupButton(startType);
            startButton.setDisable(true);

            Button saveMdaButton = (Button) dialog.getDialogPane().lookupButton(saveMdaType);
            saveMdaButton.setTooltip(new Tooltip("Write Micro-Manager-compatible MDA file sets\n"
                    + "(MDA_<region>.txt, .pos, NOTES) for each selected annotation\n"
                    + "without starting acquisition. Useful for handing the planned\n"
                    + "acquisition to Micro-Manager directly."));
            saveMdaButton.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
                // Consume the event so the dialog does not close. The user
                // should still be able to either Cancel or Start Acquisition
                // after exporting.
                evt.consume();
                onSaveMdaFromFooter();
            });

            // Build all sections
            createVariantBanner();
            createProjectSection();
            createWbModeCombo();
            createHardwareSection();
            createAlignmentSection();
            createRefinementSection();
            createAdvancedSection();
            TitledPane zStackPane = createZStackSection();
            createPreviewPanel();
            createErrorSummaryPanel();

            // Setup listeners
            setupPreviewUpdateListeners();

            // Assemble main content
            VBox mainContent = new VBox(
                    10,
                    variantBanner,
                    errorSummaryPanel,
                    projectPane,
                    hardwarePane,
                    alignmentPane,
                    refinementPane,
                    advancedPane,
                    zStackPane,
                    createPreviewSection());
            mainContent.setPadding(new Insets(0));

            // Wrap in scroll pane
            ScrollPane scrollPane = new ScrollPane(mainContent);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefViewportHeight(650);
            scrollPane.setPrefViewportWidth(650);

            dialog.getDialogPane().setContent(scrollPane);
            dialog.getDialogPane().setPrefWidth(700);
            dialog.getDialogPane().setPrefHeight(750);

            // Initialize hardware dropdowns
            initializeHardwareSelections();

            // Initial validation
            Platform.runLater(this::validateAll);

            // Result converter
            dialog.setResultConverter(button -> {
                if (button != startType) {
                    return null;
                }
                return createResult();
            });

            return dialog.showAndWait();
        }

        private void createVariantBanner() {
            variantBanner = new HBox(10);
            variantBanner.setPadding(new Insets(12, 15, 12, 15));
            variantBanner.setAlignment(Pos.CENTER_LEFT);

            Label iconLabel = new Label();
            Label textLabel = new Label();
            textLabel.setWrapText(true);

            if (hasOpenProject) {
                variantBanner.setStyle(bannerStyle("green"));
                iconLabel.setText("[+]");
                iconLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + accentColor("green") + ";");
                textLabel.setText("Adding to existing project: " + existingProjectName);
                textLabel.setStyle("-fx-text-fill: " + accentColor("green") + ";");
            } else {
                variantBanner.setStyle(bannerStyle("blue"));
                iconLabel.setText("[*]");
                iconLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + accentColor("blue") + ";");
                textLabel.setText("Creating new project");
                textLabel.setStyle("-fx-text-fill: " + accentColor("blue") + ";");
            }

            variantBanner.getChildren().addAll(iconLabel, textLabel);
        }

        private void createProjectSection() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            // Sample name field
            sampleNameField = new TextField(defaultSampleName);
            sampleNameField.setPromptText("e.g., MySample01");
            sampleNameField.setPrefWidth(300);

            sampleNameErrorLabel = new Label();
            sampleNameErrorLabel.setStyle("-fx-text-fill: orange; -fx-font-size: 10px;");
            sampleNameErrorLabel.setVisible(false);

            sampleNameField.textProperty().addListener((obs, oldVal, newVal) -> {
                String error = SampleNameValidator.getValidationError(newVal);
                if (error != null) {
                    sampleNameErrorLabel.setText(error);
                    sampleNameErrorLabel.setVisible(true);
                    validationErrors.put("sampleName", error);
                } else {
                    sampleNameErrorLabel.setVisible(false);
                    validationErrors.remove("sampleName");
                }
                updateErrorSummary();
                triggerPreviewUpdate();
            });

            int row = 0;
            grid.add(new Label("Sample Name:"), 0, row);
            grid.add(sampleNameField, 1, row);
            row++;
            grid.add(new Label(""), 0, row);
            grid.add(sampleNameErrorLabel, 1, row);
            row++;

            if (!hasOpenProject) {
                // Projects folder
                folderField = new TextField(QPPreferenceDialog.getProjectsFolderProperty());
                folderField.setPrefWidth(250);

                Button browseBtn = new Button("Browse...");
                browseBtn.setOnAction(e -> {
                    Window win = dialog.getDialogPane().getScene().getWindow();
                    DirectoryChooser chooser = new DirectoryChooser();
                    chooser.setTitle("Select Projects Folder");
                    File currentFolder = new File(folderField.getText());
                    if (currentFolder.exists() && currentFolder.isDirectory()) {
                        chooser.setInitialDirectory(currentFolder);
                    }
                    File chosen = chooser.showDialog(win);
                    if (chosen != null) {
                        folderField.setText(chosen.getAbsolutePath());
                    }
                });

                HBox folderBox = new HBox(5, folderField, browseBtn);
                HBox.setHgrow(folderField, Priority.ALWAYS);

                grid.add(new Label("Projects Folder:"), 0, row);
                grid.add(folderBox, 1, row);
            } else {
                folderField = new TextField(existingProjectFolder.getParent());
                folderField.setVisible(false);

                Label projectLabel = new Label(existingProjectName);
                projectLabel.setStyle("-fx-font-weight: bold;");
                grid.add(new Label("Project:"), 0, row);
                grid.add(projectLabel, 1, row);
            }

            projectPane = new TitledPane("PROJECT & SAMPLE", grid);
            projectPane.setExpanded(true);
            projectPane.setStyle("-fx-font-weight: bold;");
        }

        private void createWbModeCombo() {
            wbModeComboBox = new ComboBox<>();
            wbModeComboBox.getItems().addAll("Off", "Camera AWB", "Simple (90deg)", "Per-angle (PPM)");
            // Default from modality handler; fall back to saved preference
            String defaultDisplay = getDefaultWbModeFromModality();
            String savedWBMode = PersistentPreferences.getLastWhiteBalanceMode();
            if (savedWBMode != null && wbModeComboBox.getItems().contains(savedWBMode)) {
                wbModeComboBox.setValue(savedWBMode);
            } else {
                wbModeComboBox.setValue(defaultDisplay);
            }
            WbMode.applyColorCellFactory(wbModeComboBox);
            wbModeComboBox.setTooltip(new Tooltip("White balance mode:\n"
                    + "  Off - No white balance correction\n"
                    + "  Camera AWB - Set in MicroManager before acquisition\n"
                    + "  Simple (90deg) - Use 90deg R:G:B ratios, scaled per angle\n"
                    + "  Per-angle (PPM) - Independent calibration per angle"));

            wbModeComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    PersistentPreferences.setLastWhiteBalanceMode(newVal);
                }
            });
        }

        /**
         * Maps the current modality handler's wire mode to a display name.
         */
        private String getDefaultWbModeFromModality() {
            try {
                String modality = modalityBox != null ? modalityBox.getValue() : null;
                if (modality != null) {
                    var handler = ModalityRegistry.getHandler(modality);
                    String wireMode = handler.getDefaultWbMode();
                    for (WbMode wm : WbMode.values()) {
                        if (wm.getProtocolName().equals(wireMode)) {
                            return wm.getDisplayName();
                        }
                    }
                }
            } catch (Exception e) {
                // Fallback silently
            }
            return "Off";
        }

        private void createHardwareSection() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            Set<String> modalities = configManager.getSection("modalities").keySet();

            modalityBox = new ComboBox<>(FXCollections.observableArrayList(modalities));
            modalityBox.setPrefWidth(200);

            objectiveBox = new ComboBox<>();
            objectiveBox.setPrefWidth(300);

            detectorBox = new ComboBox<>();
            detectorBox.setPrefWidth(300);

            // Cascading selection
            modalityBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    updateObjectivesForModality(newVal);
                    updateModalityUI(newVal);
                    updateAfStrategyDefaultForModality(newVal);
                    updateWhiteBalanceVisibility();
                }
            });

            objectiveBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && modalityBox.getValue() != null) {
                    updateDetectorsForObjective(modalityBox.getValue(), newVal);
                }
            });

            // Preview updates on hardware change
            modalityBox.valueProperty().addListener((obs, old, newVal) -> triggerPreviewUpdate());
            objectiveBox.valueProperty().addListener((obs, old, newVal) -> triggerPreviewUpdate());
            detectorBox.valueProperty().addListener((obs, old, newVal) -> {
                triggerPreviewUpdate();
                updateWhiteBalanceVisibility(); // Update WB section visibility when detector changes
            });

            int row = 0;
            grid.add(new Label("Modality:"), 0, row);
            Hyperlink quickStartLink = new Hyperlink("Quick Start Guide");
            quickStartLink.setStyle("-fx-font-size: 10px;");
            quickStartLink.setOnAction(e -> DocumentationHelper.openDocumentation("quickstartBF"));
            HBox modalityRow = new HBox(8, modalityBox, quickStartLink);
            modalityRow.setAlignment(Pos.CENTER_LEFT);
            grid.add(modalityRow, 1, row);
            row++;

            grid.add(new Label("Objective:"), 0, row);
            grid.add(objectiveBox, 1, row);
            row++;

            grid.add(new Label("Detector:"), 0, row);
            grid.add(detectorBox, 1, row);
            row++;

            wbLabel = new Label("WB Mode:");
            wbLabel.setTooltip(new Tooltip("White balance mode for acquisition"));
            grid.add(wbLabel, 0, row);
            grid.add(wbModeComboBox, 1, row);

            hardwarePane = new TitledPane("HARDWARE CONFIGURATION", grid);
            hardwarePane.setExpanded(true);
            hardwarePane.setStyle("-fx-font-weight: bold;");
        }

        private void createAlignmentSection() {
            VBox content = new VBox(12);
            content.setPadding(new Insets(10));

            // Alignment method toggle
            ToggleGroup alignmentGroup = new ToggleGroup();
            useExistingRadio = new RadioButton("Use existing alignment");
            manualAlignRadio = new RadioButton("Perform manual alignment");
            useExistingRadio.setToggleGroup(alignmentGroup);
            manualAlignRadio.setToggleGroup(alignmentGroup);

            boolean hasScannerTransforms = !availableTransforms.isEmpty();
            // Scanner-preset path runs green-box detection on a macro; without a
            // macro it cannot fire. A slide-specific alignment is independent of
            // the macro and remains usable. "Use existing alignment" is only
            // truly available if at least one of those routes is open.
            boolean scannerPresetUsable = hasScannerTransforms && hasMacroImage;
            boolean hasTransforms = scannerPresetUsable || hasSlideAlignment;
            if (hasTransforms) {
                useExistingRadio.setSelected(true);
            } else {
                manualAlignRadio.setSelected(true);
                useExistingRadio.setDisable(true);
            }

            // Quick comparison
            HBox comparisonRow = new HBox(20);
            comparisonRow.setAlignment(Pos.CENTER_LEFT);

            Label existingInfo = new Label("[T] 2-5 min | [A] +/- 20 um");
            existingInfo.setStyle(subtleTextStyle());

            Label manualInfo = new Label("[T] 10-20 min | [A] +/- 5 um");
            manualInfo.setStyle(subtleTextStyle());

            VBox existingOption = new VBox(3, useExistingRadio, existingInfo);
            VBox manualOption = new VBox(3, manualAlignRadio, manualInfo);

            comparisonRow.getChildren().addAll(existingOption, manualOption);

            // Transform selection (for existing alignment)
            transformSelectionBox = new VBox(8);
            transformSelectionBox.setPadding(new Insets(0, 0, 0, 20));

            transformCombo = new ComboBox<>();
            transformCombo.setPrefWidth(350);
            transformCombo.getItems().addAll(availableTransforms);

            transformCombo.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(AffineTransformManager.TransformPreset item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(item.getName() + " (" + item.getMountingMethod() + ")");
                    }
                }
            });

            transformCombo.setButtonCell(new ListCell<>() {
                @Override
                protected void updateItem(AffineTransformManager.TransformPreset item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(item.getName() + " (" + item.getMountingMethod() + ")");
                    }
                }
            });

            // Restore last selection
            String lastSelected = PersistentPreferences.getLastSelectedTransform();
            if (!lastSelected.isEmpty()) {
                availableTransforms.stream()
                        .filter(t -> t.getName().equals(lastSelected))
                        .findFirst()
                        .ifPresent(transformCombo::setValue);
            } else if (!availableTransforms.isEmpty()) {
                transformCombo.getSelectionModel().selectFirst();
            }

            // Confidence label
            confidenceLabel = new Label();
            confidenceLabel.setStyle("-fx-font-size: 11px;");
            updateConfidenceLabel();

            // Note: transformCombo and useExistingRadio listeners are registered in
            // setupPreviewUpdateListeners() to avoid init-order NPEs -- they reference
            // fields created in createRefinementSection() (refinementBox, noRefineRadio, etc.)

            Label transformLabel = new Label("Select saved transform:");
            transformSelectionBox.getChildren().addAll(transformLabel, transformCombo, confidenceLabel);

            // Hide scanner preset combo when only slide alignment is available
            // (no scanner preset to select -- the slide-specific alignment is used directly).
            // Also hide when there is no reachable macro: the scanner-preset
            // route relies on green-box detection in the macro, so the combo is
            // not actionable without one.
            boolean showScannerCombo = scannerPresetUsable && useExistingRadio.isSelected();
            transformSelectionBox.setVisible(showScannerCombo);
            transformSelectionBox.setManaged(showScannerCombo);

            // Info label for slide-specific (auto-registered) alignment
            Label slideAlignmentInfo = new Label(
                    "This image has stage coordinates from QPSC acquisition. " + "No scanner alignment needed.");
            slideAlignmentInfo.setWrapText(true);
            slideAlignmentInfo.setStyle(
                    "-fx-font-size: 11px; -fx-text-fill: " + accentColor("green") + "; -fx-padding: 4 0 0 20;");
            slideAlignmentInfo.setVisible(hasSlideAlignment && useExistingRadio.isSelected());
            slideAlignmentInfo.setManaged(hasSlideAlignment && useExistingRadio.isSelected());

            // Recommendation
            Label recommendationLabel = new Label();
            recommendationLabel.setWrapText(true);
            recommendationLabel.setPadding(new Insets(8));
            recommendationLabel.setStyle(bannerStyle("yellow"));

            if (hasSlideAlignment) {
                recommendationLabel.setText("[i] Image has auto-registered alignment from QPSC acquisition. "
                        + "Proceed without refinement is the default. If the slide has been removed and "
                        + "re-seated since acquisition, choose Single-tile refinement below to correct for "
                        + "the new position; the corrected alignment is saved for the next acquisition.");
            } else if (scannerPresetUsable) {
                recommendationLabel.setText(
                        "[i] Recommendation: Use Existing Alignment (found " + availableTransforms.size()
                                + " saved transform" + (availableTransforms.size() > 1 ? "s" : "")
                                + ")");
            } else if (hasScannerTransforms) {
                // Scanner presets exist but no macro -- the green-box detection
                // path cannot run on this image, so the user must pick manual.
                recommendationLabel.setText("[i] Manual alignment required: "
                        + availableTransforms.size() + " saved scanner transform"
                        + (availableTransforms.size() > 1 ? "s" : "")
                        + " available, but this image has no macro for green-box detection.");
            } else {
                recommendationLabel.setText("[i] Manual alignment required (no saved transforms found)");
            }

            content.getChildren().addAll(comparisonRow, transformSelectionBox, slideAlignmentInfo, recommendationLabel);

            alignmentPane = new TitledPane("ALIGNMENT CONFIGURATION", content);
            alignmentPane.setExpanded(true);
            alignmentPane.setStyle("-fx-font-weight: bold;");
        }

        private void createRefinementSection() {
            refinementBox = new VBox(10);
            refinementBox.setPadding(new Insets(10));

            // Add informational banner if using refined alignment
            if (isUsingRefinedAlignment()) {
                String date = getRefinedAlignmentDate();
                long ageInDays = getAlignmentAgeInDays();

                HBox banner = createRefinedAlignmentBanner(date, ageInDays);
                refinementBox.getChildren().add(banner);

                // Add spacing after banner
                Region spacer = new Region();
                spacer.setPrefHeight(5);
                refinementBox.getChildren().add(spacer);
            }

            refinementGroup = new ToggleGroup();

            noRefineRadio = new RadioButton("Proceed without refinement");
            noRefineRadio.setToggleGroup(refinementGroup);

            singleTileRadio = new RadioButton("Single-tile refinement (+2-3 min)");
            singleTileRadio.setToggleGroup(refinementGroup);

            fullManualRadio = new RadioButton("Full manual alignment (+10-15 min)");
            fullManualRadio.setToggleGroup(refinementGroup);

            // Add descriptions
            Label noRefineDesc = new Label(
                    isUsingRefinedAlignment()
                            ? "    Fastest - use alignment as-is. Accuracy: +/- 5 um (previously refined)"
                            : "    Fastest - use alignment as-is. Accuracy: +/- 20 um");
            noRefineDesc.setStyle(subtleTextStyle());

            Label singleTileDesc = new Label("    Verify position with one tile. Accuracy: +/- 5 um");
            singleTileDesc.setStyle(subtleTextStyle());

            Label fullManualDesc = new Label("    Complete re-alignment. Accuracy: +/- 2 um");
            fullManualDesc.setStyle(subtleTextStyle());

            // Recommendation label
            refinementRecommendationLabel = new Label();
            refinementRecommendationLabel.setWrapText(true);
            refinementRecommendationLabel.setPadding(new Insets(8));
            refinementRecommendationLabel.setStyle(bannerStyle("yellow"));

            // Set default based on confidence
            updateRefinementRecommendation();

            refinementBox
                    .getChildren()
                    .addAll(
                            noRefineRadio,
                            noRefineDesc,
                            singleTileRadio,
                            singleTileDesc,
                            fullManualRadio,
                            fullManualDesc,
                            refinementRecommendationLabel);

            refinementPane = new TitledPane("REFINEMENT OPTIONS", refinementBox);
            refinementPane.setExpanded(true);
            refinementPane.setStyle("-fx-font-weight: bold;");

            // Initially visible only if using existing alignment
            refinementBox.setVisible(useExistingRadio.isSelected());
            refinementBox.setManaged(useExistingRadio.isSelected());
        }

        private void createAdvancedSection() {
            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            // Note: Green box detection parameters are handled by ExistingAlignmentPath
            // which shows its own dialog during the alignment process

            // WB combo already created in createWbModeCombo()

            // Autofocus strategy override dropdown (Mike's decision #2: give
            // users a GUI entry point to pick the strategy without editing YAML).
            afStrategyCombo = new ComboBox<>();
            afStrategyCombo.getItems().addAll(AfStrategyChoice.displayOrder());
            afStrategyCombo.setValue(AfStrategyChoice.protocolToDisplay(PersistentPreferences.getLastAfStrategy()));
            afStrategyCombo.setTooltip(new Tooltip(AfStrategyChoice.TOOLTIP));
            GridPane afGrid = new GridPane();
            afGrid.setHgap(10);
            afGrid.setVgap(5);
            afGrid.setPadding(new Insets(5));
            afGrid.add(new Label("Autofocus:"), 0, 0);
            afGrid.add(afStrategyCombo, 1, 0);

            // Modality-specific options (will be populated by updateModalityUI)
            modalityContent = new VBox(5);
            Label modalityPlaceholder = new Label("Select a modality to see specific options.");
            modalityPlaceholder.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
            modalityContent.getChildren().add(modalityPlaceholder);

            modalityPane = new TitledPane("Modality Options", modalityContent);
            modalityPane.setExpanded(false);

            content.getChildren().addAll(afGrid, new Separator(), modalityPane);

            advancedPane = new TitledPane("MODALITY-SPECIFIC OPTIONS", content);
            advancedPane.setExpanded(false);
            advancedPane.setStyle("-fx-font-weight: bold;");
        }

        private TitledPane createZStackSection() {
            zStackEnableCheck = new CheckBox("Enable Z-stack acquisition");
            zStackEnableCheck.setTooltip(
                    new Tooltip("Acquire multiple Z-planes at each tile position and compute a projection. "
                            + "Essential for thick samples and SHG/multiphoton imaging."));
            zStackEnableCheck.setSelected(PersistentPreferences.isZStackEnabled());
            CheckBox enableCheck = zStackEnableCheck;

            Spinner<Double> rangeSpinner = new Spinner<>(1.0, 2000.0, PersistentPreferences.getZStackRange(), 5.0);
            rangeSpinner.setEditable(true);
            rangeSpinner.setPrefWidth(100);
            rangeSpinner.setTooltip(new Tooltip(
                    "Total Z range in micrometers centered on autofocus. " + "Example: 20um = +/-10um around focus."));

            Spinner<Double> stepSpinner = new Spinner<>(0.1, 50.0, PersistentPreferences.getZStackStep(), 0.5);
            stepSpinner.setEditable(true);
            stepSpinner.setPrefWidth(100);
            stepSpinner.setTooltip(new Tooltip("Distance between Z-planes in micrometers. "
                    + "Smaller steps give finer Z sampling but more planes."));

            ComboBox<String> projectionCombo = new ComboBox<>();
            projectionCombo.getItems().addAll("Max Intensity", "Min Intensity", "Sum", "Mean", "Std Deviation");
            projectionCombo.setValue(PersistentPreferences.getZStackProjection());
            projectionCombo.setTooltip(new Tooltip("How to combine Z-planes into a single 2D tile for stitching. "
                    + "Max intensity is standard for fluorescence and SHG."));

            Label infoLabel = new Label();
            infoLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11;");

            Runnable updateInfo = () -> {
                double range = rangeSpinner.getValue();
                double step = stepSpinner.getValue();
                int planes = (int) Math.ceil(range / step) + 1;
                infoLabel.setText(String.format("%d planes over +/-%.1f um", planes, range / 2));
            };
            rangeSpinner.valueProperty().addListener((obs, o, n) -> updateInfo.run());
            stepSpinner.valueProperty().addListener((obs, o, n) -> updateInfo.run());
            updateInfo.run();

            rangeSpinner.disableProperty().bind(enableCheck.selectedProperty().not());
            stepSpinner.disableProperty().bind(enableCheck.selectedProperty().not());
            projectionCombo
                    .disableProperty()
                    .bind(enableCheck.selectedProperty().not());

            enableCheck.selectedProperty().addListener((obs, o, n) -> PersistentPreferences.setZStackEnabled(n));
            rangeSpinner.valueProperty().addListener((obs, o, n) -> {
                if (n != null) PersistentPreferences.setZStackRange(n);
            });
            stepSpinner.valueProperty().addListener((obs, o, n) -> {
                if (n != null) PersistentPreferences.setZStackStep(n);
            });
            projectionCombo.valueProperty().addListener((obs, o, n) -> {
                if (n != null) PersistentPreferences.setZStackProjection(n);
            });

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));
            grid.add(new Label("Z range (um):"), 0, 0);
            grid.add(rangeSpinner, 1, 0);
            grid.add(new Label("Z step (um):"), 0, 1);
            grid.add(stepSpinner, 1, 1);
            grid.add(new Label("Projection:"), 0, 2);
            grid.add(projectionCombo, 1, 2);
            grid.add(infoLabel, 0, 3, 2, 1);

            // Loop-order toggle: mirrors the one in UnifiedAcquisitionController.
            // Disabled when Z-stack is off OR (widefield) fewer than 2 channels
            // are selected. Hidden for non-participating modalities (BF, LSM).
            ToggleGroup loopOrderGroup = new ToggleGroup();
            loopOrderInnerZRadio = new RadioButton();
            loopOrderInnerAltRadio = new RadioButton();
            loopOrderInnerZRadio.setToggleGroup(loopOrderGroup);
            loopOrderInnerAltRadio.setToggleGroup(loopOrderGroup);
            loopOrderInnerZRadio.setSelected(true);
            loopOrderInnerZRadio.setTooltip(new Tooltip("Loop order across Z and channels (widefield) or angles (PPM). "
                    + "Default sweeps Z inner -- fewer filter / rotation changes, faster."));
            loopOrderInnerAltRadio.setTooltip(
                    new Tooltip("Alternative loop order. Widefield: 'Channels per Z' re-images every "
                            + "channel at each Z -- slower but tightly Z-registered for live samples. "
                            + "PPM: 'Z per angle' sweeps Z per angle -- fewer rotation moves on z-stacks."));
            loopOrderInnerAltRadio.selectedProperty().addListener((obs, oldV, newV) -> {
                String family = loopOrderFamily(modalityBox != null ? modalityBox.getValue() : null);
                if (family == null) return;
                String chosen;
                if (PersistentPreferences.LOOP_ORDER_FAMILY_WIDEFIELD.equals(family)) {
                    chosen = newV ? "channel" : "z";
                } else {
                    chosen = newV ? "z" : "angle";
                }
                PersistentPreferences.setAcqLoopOrder(family, chosen);
            });
            loopOrderLabel = new Label("Loop order:");
            loopOrderLabel.setStyle("-fx-font-weight: bold;");
            HBox loopOrderRadios = new HBox(15, loopOrderInnerZRadio, loopOrderInnerAltRadio);
            loopOrderRadios.setAlignment(Pos.CENTER_LEFT);
            loopOrderRow = new VBox(4, loopOrderLabel, loopOrderRadios);
            loopOrderRow.setPadding(new Insets(8, 0, 0, 0));
            updateLoopOrderToggle(modalityBox != null ? modalityBox.getValue() : null);

            VBox zContent = new VBox(8, enableCheck, grid, loopOrderRow);
            zContent.setPadding(new Insets(5));

            TitledPane pane = new TitledPane("Z-STACK OPTIONS", zContent);
            pane.setExpanded(false);
            pane.setAnimated(false);
            pane.setStyle("-fx-font-weight: bold;");
            return pane;
        }

        private static String loopOrderFamily(String modality) {
            if (modality == null) {
                return null;
            }
            String norm = modality.toLowerCase();
            if (norm.startsWith("ppm")) {
                return PersistentPreferences.LOOP_ORDER_FAMILY_PPM;
            }
            if (norm.startsWith("fl")
                    || norm.startsWith("widefield")
                    || norm.startsWith("epi")
                    || norm.startsWith("fluorescence")) {
                return PersistentPreferences.LOOP_ORDER_FAMILY_WIDEFIELD;
            }
            return null;
        }

        private void updateLoopOrderToggle(String modality) {
            if (loopOrderInnerZRadio == null || loopOrderInnerAltRadio == null) {
                return;
            }
            String family = loopOrderFamily(modality);
            loopOrderInnerZRadio.disableProperty().unbind();
            loopOrderInnerAltRadio.disableProperty().unbind();
            loopOrderLabel.setDisable(false);

            if (PersistentPreferences.LOOP_ORDER_FAMILY_PPM.equals(family)) {
                loopOrderInnerZRadio.setText("Angle per Z (current default)");
                loopOrderInnerAltRadio.setText("Z per angle (fast for thicker slides)");
                loopOrderInnerZRadio
                        .disableProperty()
                        .bind(zStackEnableCheck.selectedProperty().not());
                loopOrderInnerAltRadio
                        .disableProperty()
                        .bind(zStackEnableCheck.selectedProperty().not());
                String saved = PersistentPreferences.getAcqLoopOrder(family);
                boolean useAlt = "z".equalsIgnoreCase(saved);
                loopOrderInnerAltRadio.setSelected(useAlt);
                loopOrderInnerZRadio.setSelected(!useAlt);
                loopOrderRow.setVisible(true);
                loopOrderRow.setManaged(true);
            } else if (PersistentPreferences.LOOP_ORDER_FAMILY_WIDEFIELD.equals(family)) {
                loopOrderInnerZRadio.setText("Z per channel (fast, fixed slides)");
                loopOrderInnerAltRadio.setText("Channels per Z (drift-tolerant)");
                ObservableBooleanValue multipleChannels = modalityUI instanceof WidefieldChannelBoundingBoxUI wfUi
                        ? wfUi.hasMultipleChannelsSelectedProperty()
                        : null;
                if (multipleChannels != null) {
                    loopOrderInnerZRadio
                            .disableProperty()
                            .bind(zStackEnableCheck.selectedProperty().not().or(Bindings.not(multipleChannels)));
                    loopOrderInnerAltRadio
                            .disableProperty()
                            .bind(zStackEnableCheck.selectedProperty().not().or(Bindings.not(multipleChannels)));
                } else {
                    loopOrderInnerZRadio
                            .disableProperty()
                            .bind(zStackEnableCheck.selectedProperty().not());
                    loopOrderInnerAltRadio
                            .disableProperty()
                            .bind(zStackEnableCheck.selectedProperty().not());
                }
                String saved = PersistentPreferences.getAcqLoopOrder(family);
                boolean useAlt = "channel".equalsIgnoreCase(saved);
                loopOrderInnerAltRadio.setSelected(useAlt);
                loopOrderInnerZRadio.setSelected(!useAlt);
                loopOrderRow.setVisible(true);
                loopOrderRow.setManaged(true);
            } else {
                loopOrderRow.setVisible(false);
                loopOrderRow.setManaged(false);
            }
        }

        private void createPreviewPanel() {
            if (annotationCount > 0) {
                previewAnnotationsLabel = new Label("Annotations: " + annotationCount + " regions selected");
            } else {
                previewAnnotationsLabel = new Label("Annotations: None (will be created after alignment)");
            }
            previewTilesLabel = new Label("Tiles: --");
            previewTimeLabel = new Label("Estimated Time: --");
            previewStorageLabel = new Label("Estimated Storage: --");
        }

        private TitledPane createPreviewSection() {
            VBox previewContent =
                    new VBox(5, previewAnnotationsLabel, previewTilesLabel, previewTimeLabel, previewStorageLabel);
            previewContent.setPadding(new Insets(10));
            boolean dark = QuPathStyleManager.getStyleColorScheme() == ColorScheme.DARK;
            previewContent.setStyle("-fx-background-color: " + (dark ? "#333" : "#f8f9fa") + "; -fx-border-color: "
                    + (dark ? "#555" : "#dee2e6") + "; -fx-border-width: 1px;");

            TitledPane previewPane = new TitledPane("ACQUISITION PREVIEW", previewContent);
            previewPane.setExpanded(true);
            previewPane.setCollapsible(false);
            previewPane.setStyle("-fx-font-weight: bold;");

            return previewPane;
        }

        private void createErrorSummaryPanel() {
            errorSummaryPanel = new VBox(5);
            boolean darkErr = QuPathStyleManager.getStyleColorScheme() == ColorScheme.DARK;
            errorSummaryPanel.setStyle("-fx-background-color: " + (darkErr ? "#4a3800" : "#fff3cd")
                    + "; -fx-border-color: " + (darkErr ? "#806000" : "#ffc107")
                    + "; -fx-border-width: 1px; -fx-padding: 10px;");
            errorSummaryPanel.setVisible(false);
            errorSummaryPanel.setManaged(false);

            Label errorTitle = new Label("Please fix the following errors:");
            errorTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (darkErr ? "#ffc107" : "#856404") + ";");

            errorListBox = new VBox(3);
            errorSummaryPanel.getChildren().addAll(errorTitle, errorListBox);
        }

        private void initializeHardwareSelections() {
            var modalityState = qupath.ext.qpsc.state.ModalityState.getInstance();
            String fromState = modalityState.getModality();
            Set<String> modalities = configManager.getSection("modalities").keySet();

            if (fromState != null && !fromState.isEmpty() && modalities.contains(fromState)) {
                modalityBox.setValue(fromState);
            } else if (!modalities.isEmpty()) {
                modalityBox.setValue(modalities.iterator().next());
            }
            modalityBox.valueProperty().addListener((obs, oldV, newV) -> {
                if (newV != null) modalityState.setModality(newV);
            });
            javafx.beans.value.ChangeListener<String> stateListener = (obs, oldV, newV) -> {
                if (newV != null
                        && !newV.equals(modalityBox.getValue())
                        && modalityBox.getItems().contains(newV)) {
                    modalityBox.setValue(newV);
                }
            };
            modalityState.modalityProperty().addListener(stateListener);
            modalityBox.sceneProperty().addListener((s, oldScene, newScene) -> {
                if (newScene == null) {
                    modalityState.modalityProperty().removeListener(stateListener);
                }
            });
        }

        private void updateObjectivesForModality(String modality) {
            Set<String> objectiveIds = configManager.getAvailableObjectives();
            Map<String, String> objectiveNames = configManager.getObjectiveFriendlyNames(objectiveIds);

            List<String> objectiveDisplayItems = objectiveIds.stream()
                    .map(id -> objectiveNames.get(id) + " (" + id + ")")
                    .sorted()
                    .collect(Collectors.toList());

            objectiveBox.getItems().clear();
            objectiveBox.getItems().addAll(objectiveDisplayItems);

            String lastObjective =
                    qupath.ext.qpsc.state.ObjectiveState.getInstance().getObjective();
            if (lastObjective == null) lastObjective = "";
            boolean restored = false;
            if (!lastObjective.isEmpty()) {
                for (String displayItem : objectiveDisplayItems) {
                    if (extractIdFromDisplayString(displayItem).equals(lastObjective)) {
                        objectiveBox.setValue(displayItem);
                        restored = true;
                        break;
                    }
                }
            }

            if (!restored && !objectiveDisplayItems.isEmpty()) {
                objectiveBox.setValue(objectiveDisplayItems.get(0));
            }
        }

        private void updateDetectorsForObjective(String modality, String objectiveDisplay) {
            String objectiveId = extractIdFromDisplayString(objectiveDisplay);
            Set<String> detectorIds = configManager.getAvailableDetectors();
            Map<String, String> detectorNames = configManager.getDetectorFriendlyNames(detectorIds);

            List<String> detectorDisplayItems = detectorIds.stream()
                    .map(id -> detectorNames.get(id) + " (" + id + ")")
                    .sorted()
                    .collect(Collectors.toList());

            detectorBox.getItems().clear();
            detectorBox.getItems().addAll(detectorDisplayItems);

            String lastDetector = PersistentPreferences.getLastDetector();
            boolean restored = false;
            if (!lastDetector.isEmpty()) {
                for (String displayItem : detectorDisplayItems) {
                    if (extractIdFromDisplayString(displayItem).equals(lastDetector)) {
                        detectorBox.setValue(displayItem);
                        restored = true;
                        break;
                    }
                }
            }

            if (!restored && !detectorDisplayItems.isEmpty()) {
                detectorBox.setValue(detectorDisplayItems.get(0));
            }
        }

        /**
         * Mirrors UnifiedAcquisitionController.updateAfStrategyDefaultForModality:
         * rewrites the "Default (from config)" item label to show the resolved
         * strategy binding for the current modality. See that method's docstring.
         */
        private void updateAfStrategyDefaultForModality(String modality) {
            if (afStrategyCombo == null) return;
            String boundStrategy = configManager.getAutofocusStrategyForModality(modality);
            String defaultLabel = boundStrategy != null && !boundStrategy.isBlank()
                    ? AfStrategyChoice.DEFAULT_DISPLAY + " -> " + boundStrategy
                    : AfStrategyChoice.DEFAULT_DISPLAY;
            int defaultIdx = -1;
            for (int i = 0; i < afStrategyCombo.getItems().size(); i++) {
                String item = afStrategyCombo.getItems().get(i);
                if (item != null && item.startsWith(AfStrategyChoice.DEFAULT_DISPLAY)) {
                    defaultIdx = i;
                    break;
                }
            }
            if (defaultIdx < 0) return;
            String currentValue = afStrategyCombo.getValue();
            boolean wasOnDefault = currentValue != null && currentValue.startsWith(AfStrategyChoice.DEFAULT_DISPLAY);
            afStrategyCombo.getItems().set(defaultIdx, defaultLabel);
            if (wasOnDefault) {
                afStrategyCombo.setValue(defaultLabel);
            }
        }

        /**
         * Builds an {@link MdaExportContext} snapshot from the dialog's current state.
         * Called by the "Save as MicroManager MDA..." button on the modality panel
         * via a {@code Supplier}. Returns {@link MdaExportContext#notReady(String)}
         * when preconditions aren't met (no annotations, no project, etc.) so the
         * panel can surface a friendly INFORMATION alert.
         *
         * <p>This mirrors the OK-button path's command-building (via
         * {@link AcquisitionConfigurationBuilder}) so the exported MDA files
         * match what would be acquired if the user pressed Start Acquisition.
         * Tiles are generated on-demand into the per-region project subdirectory
         * (same as the auto-save path) so the exported {@code .pos} file uses
         * stage micrometers from the same transform pipeline.
         */
        private MdaExportContext buildMdaExportContext() {
            try {
                if (annotations == null || annotations.isEmpty()) {
                    return MdaExportContext.notReady(
                            "Select at least one annotation before exporting MicroManager MDA files.");
                }
                // When a project is already open, the OK path derives the
                // effective sample name from the open project's folder name
                // (the QPProjectFunctions layout that AcquisitionManager
                // uses); mirror that here so MDA files land inside the open
                // project, not in a sibling folder named after whatever stale
                // value sat in PersistentPreferences.getLastSampleName().
                String sampleName;
                if (hasOpenProject && existingProjectName != null) {
                    sampleName = existingProjectName;
                } else {
                    sampleName =
                            sampleNameField != null ? sampleNameField.getText().trim() : "";
                }
                if (sampleName.isEmpty()) {
                    return MdaExportContext.notReady("Enter a sample name before exporting MDA.");
                }
                String folderText = folderField != null ? folderField.getText().trim() : "";
                if (folderText.isEmpty()) {
                    return MdaExportContext.notReady("Enter a projects folder before exporting MDA.");
                }
                File projectsFolder = new File(folderText);
                String modality = modalityBox != null ? modalityBox.getValue() : null;
                String objective = extractIdFromDisplayString(objectiveBox != null ? objectiveBox.getValue() : null);
                String detector = extractIdFromDisplayString(detectorBox != null ? detectorBox.getValue() : null);
                if (modality == null || objective == null || detector == null) {
                    return MdaExportContext.notReady("Select modality, objective, and detector before exporting MDA.");
                }

                SampleSetupResult sample =
                        new SampleSetupResult(sampleName, projectsFolder, modality, objective, detector);

                String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configFileLocation);

                String enhancedModality = ObjectiveUtils.createEnhancedFolderName(modality, objective);
                java.nio.file.Path sampleRoot = projectsFolder.toPath().resolve(sampleName);
                java.nio.file.Path modalityRoot = sampleRoot.resolve(enhancedModality);

                // Per-region overrides (channel + angle + focus) read from the panel
                // -- same source the OK path uses.
                Map<String, Double> angleOverrides = modalityUI != null ? modalityUI.getAngleOverrides() : null;
                String focusChannelId = modalityUI != null ? modalityUI.getFocusChannelId() : null;
                Map<String, Double> channelIntensityOverrides =
                        modalityUI != null ? modalityUI.getChannelIntensityOverrides() : Map.of();
                if (channelIntensityOverrides == null) {
                    channelIntensityOverrides = Map.of();
                }

                // Resolve angles + channels (mirrors AcquisitionManager.performSingleAnnotationAcquisition).
                String wbMode;
                if (wbModeComboBox == null || !wbModeComboBox.isVisible()) {
                    wbMode = "off";
                } else {
                    String wbModeDisplay = wbModeComboBox.getValue();
                    wbMode = WbMode.fromDisplayName(wbModeDisplay != null ? wbModeDisplay : "Off")
                            .getProtocolName();
                }
                List<AngleExposure> angleExposures = AngleResolutionService.resolve(
                                enhancedModality, objective, detector, angleOverrides, wbMode)
                        .join();
                List<ChannelExposure> channelExposures = ChannelResolutionService.resolve(
                        enhancedModality, objective, detector, angleOverrides, focusChannelId);

                if (ChannelResolutionService.isEmptySelectionForChannelBasedModality(
                        enhancedModality, objective, detector, angleOverrides)) {
                    return MdaExportContext.notReady(
                            "No fluorescence channels selected. Enable at least one channel before exporting MDA.");
                }

                double pixelSize = mgr.getPixelSize(objective, detector);
                AcquisitionConfigurationBuilder.AcquisitionConfiguration config =
                        AcquisitionConfigurationBuilder.buildConfiguration(
                                sample,
                                configFileLocation,
                                enhancedModality,
                                annotations.get(0).getName() == null
                                        ? "region"
                                        : annotations.get(0).getName(),
                                angleExposures,
                                channelExposures,
                                projectsFolder.getAbsolutePath(),
                                sampleName,
                                pixelSize,
                                wbMode);
                AcquisitionCommandBuilder cmdBuilder = config.commandBuilder();
                if (wbMode != null) {
                    cmdBuilder.wbMode(wbMode);
                }
                if (channelIntensityOverrides != null && !channelIntensityOverrides.isEmpty()) {
                    cmdBuilder.channelIntensityOverrides(channelIntensityOverrides);
                }
                cmdBuilder.focusChannel(focusChannelId);
                // Let the modality handler tweak the builder (e.g. disable debayer for LSM).
                ModalityRegistry.getHandler(modality).configureCommandBuilder(cmdBuilder);

                // Build the per-region tile lists. For pre-acquisition export we
                // synthesize centroids directly from the annotation bounds + camera
                // FOV without writing files (avoids polluting the project before
                // the user commits to actually acquiring).
                double[] fovMicrons = mgr.getCameraFOV(modality, objective, detector);
                double fovWumicrons = fovMicrons[0];
                double fovHumicrons = fovMicrons[1];
                double overlap = QPPreferenceDialog.getTileOverlapPercentProperty();
                double imagePixelSize = pixelSize;
                try {
                    var gui = qupath.lib.gui.QuPathGUI.getInstance();
                    if (gui != null && gui.getImageData() != null) {
                        imagePixelSize = gui.getImageData()
                                .getServer()
                                .getPixelCalibration()
                                .getAveragedPixelSizeMicrons();
                    }
                } catch (Exception ignored) {
                    // Fall back to detector pixel size.
                }
                // Resolve the alignment transform so synthesized centroids land in stage micrometers.
                AffineTransformManager.TransformPreset preset =
                        (useExistingRadio != null && useExistingRadio.isSelected() && transformCombo != null)
                                ? transformCombo.getValue()
                                : null;
                AffineTransform transform = preset != null ? preset.getTransform() : null;

                List<MdaExportAction.RegionPlan> regionPlans = new ArrayList<>(annotations.size());
                for (PathObject ann : annotations) {
                    if (ann == null || ann.getROI() == null) {
                        continue;
                    }
                    String regionName = ann.getName() != null ? ann.getName() : "region";
                    java.nio.file.Path regionDir = modalityRoot.resolve(regionName);
                    List<TileStagePos> tiles = synthesizeTileCentroids(
                            ann, fovWumicrons, fovHumicrons, overlap, imagePixelSize, transform);
                    regionPlans.add(new MdaExportAction.RegionPlan(regionName, regionDir, tiles));
                }
                if (regionPlans.isEmpty()) {
                    return MdaExportContext.notReady("No usable annotations to export.");
                }

                Map<String, Channel> channelLibrary = new LinkedHashMap<>();
                try {
                    for (Channel ch : mgr.getModalityChannels(modality)) {
                        channelLibrary.put(ch.id(), ch);
                    }
                } catch (Exception e) {
                    logger.debug("No channel library for modality '{}': {}", modality, e.getMessage());
                }

                return MdaExportContext.ready(sample, cmdBuilder, regionPlans, mgr, channelLibrary);
            } catch (Exception ex) {
                logger.error("Could not build MDA export context: {}", ex.getMessage(), ex);
                return MdaExportContext.notReady("Could not prepare MDA export: "
                        + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }

        /**
         * Footer "Save MDA..." handler. Builds the export context from the
         * current dialog state and writes MDA files; mirrors the per-modality
         * panel button so users who never expand the modality TitledPane (or
         * who scroll past it) still have a discoverable export path. Errors
         * and "not ready" states surface via the same alerts that
         * {@link MdaExportAction#exportAndConfirm} uses, re-parented over
         * this dialog.
         */
        private void onSaveMdaFromFooter() {
            Window parentWindow = dialog.getDialogPane().getScene() != null
                    ? dialog.getDialogPane().getScene().getWindow()
                    : null;
            MdaExportContext ctx;
            try {
                ctx = buildMdaExportContext();
            } catch (RuntimeException ex) {
                logger.error("MDA export context build failed: {}", ex.getMessage(), ex);
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("MicroManager MDA Export");
                err.setHeaderText("Failed to build export context");
                err.setContentText(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                UIFunctions.showAlertOverParent(err, parentWindow);
                return;
            }
            if (ctx == null || ctx.hasError()) {
                Alert info = new Alert(Alert.AlertType.INFORMATION);
                info.setTitle("MicroManager MDA Export");
                info.setHeaderText("Not ready to export");
                info.setContentText(
                        ctx != null
                                        && ctx.errorMessage() != null
                                        && !ctx.errorMessage().isBlank()
                                ? ctx.errorMessage()
                                : "Configure modality, objective, detector and annotations before exporting MDA.");
                UIFunctions.showAlertOverParent(info, parentWindow);
                return;
            }
            CompletableFuture.runAsync(() -> MdaExportAction.exportAndConfirm(
                            parentWindow,
                            ctx.sample(),
                            ctx.cmdBuilder(),
                            ctx.regions(),
                            ctx.configManager(),
                            ctx.channelLibrary()))
                    .exceptionally(t -> {
                        logger.error("MDA export task failed unexpectedly: {}", t.getMessage(), t);
                        return null;
                    });
        }

        /**
         * Tiles {@code annotation} into a grid of frame-sized cells with overlap and
         * maps the cell centroids through {@code transform} to obtain stage
         * micrometers. Returns an empty list if the annotation has no ROI.
         *
         * <p>This intentionally avoids writing {@code TileConfiguration.txt} files
         * (the heavyweight {@link qupath.ext.qpsc.utilities.TilingUtilities} path
         * pollutes the project before the user has committed to an acquisition).
         */
        private static List<TileStagePos> synthesizeTileCentroids(
                PathObject annotation,
                double fovWumicrons,
                double fovHumicrons,
                double overlapPercent,
                double imagePixelSizeUm,
                AffineTransform transform) {
            if (annotation == null || annotation.getROI() == null) {
                return List.of();
            }
            var roi = annotation.getROI();
            double fovWpx = fovWumicrons / imagePixelSizeUm;
            double fovHpx = fovHumicrons / imagePixelSizeUm;
            double strideX = fovWpx * (1.0 - overlapPercent / 100.0);
            double strideY = fovHpx * (1.0 - overlapPercent / 100.0);
            if (strideX <= 0 || strideY <= 0) {
                return List.of();
            }
            // Buffer one full FOV on each side (matches TilingUtilities addBuffer=true).
            double minX = roi.getBoundsX() - fovWpx / 2.0;
            double minY = roi.getBoundsY() - fovHpx / 2.0;
            double maxX = roi.getBoundsX() + roi.getBoundsWidth() + fovWpx / 2.0;
            double maxY = roi.getBoundsY() + roi.getBoundsHeight() + fovHpx / 2.0;
            int cols = (int) Math.ceil((maxX - minX) / strideX);
            int rows = (int) Math.ceil((maxY - minY) / strideY);
            if (cols < 1) cols = 1;
            if (rows < 1) rows = 1;
            List<TileStagePos> out = new ArrayList<>(cols * rows);
            int idx = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    double cxPx = minX + c * strideX + fovWpx / 2.0;
                    double cyPx = minY + r * strideY + fovHpx / 2.0;
                    double[] src = new double[] {cxPx, cyPx};
                    double[] dst = new double[2];
                    if (transform != null) {
                        transform.transform(src, 0, dst, 0, 1);
                    } else {
                        // No alignment yet -- fall back to QuPath pixel * pixel-size
                        // so the .pos file still carries plausible-magnitude values
                        // (calibrated stage coords just aren't available).
                        dst[0] = cxPx * imagePixelSizeUm;
                        dst[1] = cyPx * imagePixelSizeUm;
                    }
                    out.add(new TileStagePos(String.valueOf(idx++), dst[0], dst[1], 0.0));
                }
            }
            return out;
        }

        private void updateModalityUI(String modality) {
            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            Optional<ModalityHandler.BoundingBoxUI> uiOpt = handler.createBoundingBoxUI();

            // Clear existing content
            modalityContent.getChildren().clear();

            if (uiOpt.isPresent()) {
                modalityUI = uiOpt.get();
                // Add the modality-specific UI to the content pane
                modalityContent.getChildren().add(modalityUI.getNode());
                modalityPane.setExpanded(true); // Auto-expand when there's content
                logger.debug("Added modality UI for: {}", modality);
                // Wire the "Save as MicroManager MDA..." button on the panel
                // so it can build an export context from this dialog's live state.
                java.util.function.Supplier<MdaExportContext> supplier = this::buildMdaExportContext;
                if (modalityUI instanceof WidefieldChannelBoundingBoxUI wfUi) {
                    wfUi.installMdaExportContext(supplier);
                } else if (modalityUI instanceof PPMBoundingBoxUI ppmUi) {
                    ppmUi.installMdaExportContext(supplier);
                }
            } else {
                modalityUI = null;
                // Show placeholder when no modality-specific UI
                Label placeholder = new Label("No specific options for " + modality);
                placeholder.setStyle("-fx-text-fill: gray; -fx-font-style: italic;");
                modalityContent.getChildren().add(placeholder);
                modalityPane.setExpanded(false);
            }

            // Update white balance visibility based on current detector and modality
            updateWhiteBalanceVisibility();

            // Refresh the loop-order toggle in the Z-stack section (labels +
            // disable binding depend on the new modality's family and channel
            // count). Re-applies the saved per-family pick from preferences.
            updateLoopOrderToggle(modality);
        }

        /**
         * Updates the visibility and default of the white balance row based on
         * the current detector and modality.  WB is shown only when the detector
         * is a JAI camera AND the modality supports white balance (i.e. its
         * {@code getDefaultWbMode()} is not "off").
         */
        private void updateWhiteBalanceVisibility() {
            if (wbModeComboBox == null || wbLabel == null) {
                return; // Not yet initialized
            }

            String detectorDisplay = detectorBox.getValue();
            String detector = detectorDisplay != null ? extractIdFromDisplayString(detectorDisplay) : null;
            boolean isJAI = configManager.isJAICamera(detector);

            String modality = modalityBox.getValue();
            var handler = modality != null ? ModalityRegistry.getHandler(modality) : null;
            String defaultWb = handler != null ? handler.getDefaultWbMode() : "off";
            boolean modalityUsesWb = !"off".equals(defaultWb);

            boolean showWb = isJAI && modalityUsesWb;
            wbLabel.setVisible(showWb);
            wbLabel.setManaged(showWb);
            wbModeComboBox.setVisible(showWb);
            wbModeComboBox.setManaged(showWb);

            // When modality changes, reset the WB default from the handler
            if (showWb) {
                String displayDefault = getDefaultWbModeFromModality();
                wbModeComboBox.setValue(displayDefault);
                boolean isMultiAngle = handler != null && handler.isMultiAngleModality();
                filterWbModesByBackgroundValidity(modality, detector, isMultiAngle);
            }

            logger.debug(
                    "White balance visibility updated: JAI={}, modalityWB={}, visible={}",
                    isJAI,
                    modalityUsesWb,
                    showWb);
        }

        /**
         * Filter WB mode dropdown to only show modes with valid backgrounds.
         * When background correction is disabled in config, all modes are shown.
         */
        private void filterWbModesByBackgroundValidity(String modality, String detector, boolean isPPM) {
            String objectiveDisplay = objectiveBox.getValue();
            String objective = objectiveDisplay != null ? extractIdFromDisplayString(objectiveDisplay) : null;
            if (objective == null) {
                return;
            }

            boolean bgEnabled = configManager.isBackgroundCorrectionEnabled(modality);

            String currentSelection = wbModeComboBox.getValue();
            wbModeComboBox.getItems().clear();

            if (bgEnabled) {
                String baseFolder = configManager.getBackgroundCorrectionFolder(modality);
                if (baseFolder != null) {
                    List<WbMode> validModes = BackgroundValidityChecker.getValidModes(
                            baseFolder, modality, objective, detector, configManager);
                    for (WbMode mode : validModes) {
                        wbModeComboBox.getItems().add(mode.getDisplayName());
                    }
                    logger.debug("Filtered WB modes to {} valid options (BG correction enabled)", validModes.size());
                } else {
                    for (WbMode mode : WbMode.values()) {
                        if (isPPM || !isPpmOnlyMode(mode)) {
                            wbModeComboBox.getItems().add(mode.getDisplayName());
                        }
                    }
                }
            } else {
                for (WbMode mode : WbMode.values()) {
                    if (isPPM || !isPpmOnlyMode(mode)) {
                        wbModeComboBox.getItems().add(mode.getDisplayName());
                    }
                }
            }

            if (wbModeComboBox.getItems().contains(currentSelection)) {
                wbModeComboBox.setValue(currentSelection);
            } else if (!wbModeComboBox.getItems().isEmpty()) {
                wbModeComboBox.setValue(wbModeComboBox.getItems().get(0));
            }
        }

        private static boolean isPpmOnlyMode(WbMode mode) {
            return mode == WbMode.SIMPLE || mode == WbMode.PER_ANGLE;
        }

        private void updateConfidenceLabel() {
            AffineTransformManager.TransformPreset preset = transformCombo.getValue();
            if (preset != null) {
                double confidence = AlignmentHelper.calculateConfidence(preset);
                String level =
                        confidence >= HIGH_CONFIDENCE ? "HIGH" : (confidence >= MEDIUM_CONFIDENCE ? "MEDIUM" : "LOW");
                String color = confidence >= HIGH_CONFIDENCE
                        ? accentColor("green")
                        : (confidence >= MEDIUM_CONFIDENCE ? accentColor("amber") : accentColor("red"));
                confidenceLabel.setText(String.format("Confidence: %s (%.0f%%)", level, confidence * 100));
                confidenceLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
            } else {
                confidenceLabel.setText("");
            }
        }

        private void updateRefinementRecommendation() {
            // Bounding-box images have auto-registered alignment from known
            // stage coordinates -- highest possible confidence, no refinement needed.
            if (hasSlideAlignment) {
                noRefineRadio.setSelected(true);
                refinementRecommendationLabel.setText("[i] Recommendation: Proceed without refinement "
                        + "(auto-registered from acquisition coordinates). Pick Single-tile refinement "
                        + "if the slide was removed and re-seated since the last acquisition -- the "
                        + "corrected alignment is saved for next time.");
                return;
            }

            AffineTransformManager.TransformPreset preset = transformCombo.getValue();
            double confidence = preset != null ? AlignmentHelper.calculateConfidence(preset) : 0.5;

            String recommendation;
            if (confidence >= HIGH_CONFIDENCE) {
                recommendation = "[i] Recommendation: Proceed without refinement (high confidence)";
                noRefineRadio.setSelected(true);
            } else if (confidence >= MEDIUM_CONFIDENCE) {
                recommendation = "[i] Recommendation: Single-tile refinement (medium confidence)";
                singleTileRadio.setSelected(true);
            } else {
                recommendation = "[i] Recommendation: Full manual alignment (low confidence)";
                fullManualRadio.setSelected(true);
            }

            refinementRecommendationLabel.setText(recommendation);
        }

        private void setupPreviewUpdateListeners() {
            previewDebounce.setOnFinished(event -> updatePreviewPanel());

            // These listeners are registered here (not in createAlignmentSection) because
            // they reference fields from createRefinementSection (refinementBox, radio buttons, etc.)
            transformCombo.valueProperty().addListener((obs, old, preset) -> {
                updateConfidenceLabel();
                updateRefinementRecommendation();
                if (preset != null) {
                    PersistentPreferences.setLastSelectedTransform(preset.getName());
                }
            });

            useExistingRadio.selectedProperty().addListener((obs, old, selected) -> {
                // Show scanner combo only when selected AND scanner presets exist
                // AND a macro is reachable (the combo path requires green-box
                // detection on a macro image).
                boolean showScanner = selected && !availableTransforms.isEmpty() && hasMacroImage;
                transformSelectionBox.setVisible(showScanner);
                transformSelectionBox.setManaged(showScanner);
                refinementBox.setVisible(selected);
                refinementBox.setManaged(selected);
                PersistentPreferences.setUseExistingAlignment(selected);
                triggerPreviewUpdate();
            });
        }

        private void triggerPreviewUpdate() {
            previewDebounce.playFromStart();
        }

        /**
         * Check if the current alignment is a slide-specific refined alignment.
         * @return true if using a previously refined alignment
         */
        private boolean isUsingRefinedAlignment() {
            AffineTransformManager.TransformPreset preset = transformCombo.getValue();
            if (preset == null || preset.getTransform() == null) {
                return false;
            }

            // Check if this is a slide-specific alignment
            // Slide-specific alignments are saved when single-tile refinement is performed
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getImageData() == null) {
                return false;
            }

            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) {
                return false;
            }

            // Check if alignment file exists for this specific image
            Project<BufferedImage> project = gui.getProject();
            if (project == null) {
                return false;
            }

            AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(project, imageName);

            return slideTransform != null;
        }

        /**
         * Get the date when the slide-specific alignment was created.
         * @return formatted date string, or null if not available
         */
        private String getRefinedAlignmentDate() {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getImageData() == null) {
                return null;
            }

            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) {
                return null;
            }

            Project<BufferedImage> project = gui.getProject();
            if (project == null) {
                return null;
            }

            String dateStr = AffineTransformManager.getSlideAlignmentDate(project, imageName);
            if (dateStr == null) {
                return null;
            }

            // Parse and format date for display
            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                SimpleDateFormat outputFormat = new SimpleDateFormat("MMM d, yyyy");
                Date date = inputFormat.parse(dateStr);
                return outputFormat.format(date);
            } catch (Exception e) {
                return dateStr; // Return as-is if parsing fails
            }
        }

        /**
         * Calculate the age of the refined alignment in days.
         * @return number of days since alignment was created, or -1 if unable to determine
         */
        private long getAlignmentAgeInDays() {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null || gui.getImageData() == null) {
                return -1;
            }

            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
            if (imageName == null) {
                return -1;
            }

            Project<BufferedImage> project = gui.getProject();
            if (project == null) {
                return -1;
            }

            String dateStr = AffineTransformManager.getSlideAlignmentDate(project, imageName);
            if (dateStr == null) {
                return -1;
            }

            try {
                SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                Date alignmentDate = inputFormat.parse(dateStr);
                Date now = new Date();
                long diffMillis = now.getTime() - alignmentDate.getTime();
                return diffMillis / (1000 * 60 * 60 * 24); // Convert to days
            } catch (Exception e) {
                return -1;
            }
        }

        /**
         * Create an informational banner indicating that a previously refined alignment is being used.
         * @param date Date the alignment was refined
         * @param ageInDays Age of the alignment in days
         * @return HBox containing the banner
         */
        private HBox createRefinedAlignmentBanner(String date, long ageInDays) {
            HBox banner = new HBox(10);
            banner.setPadding(new Insets(12, 15, 12, 15));

            // Determine if alignment is outdated (> 1 day)
            boolean isOutdated = ageInDays > 1;

            // Style based on age
            String semantic = isOutdated ? "yellow" : "blue";
            String accentSemantic = isOutdated ? "amber" : "blue";
            banner.setStyle(bannerStyle(semantic));

            // Icon
            Label iconLabel = new Label(isOutdated ? "[!]" : "[*]");
            iconLabel.setStyle(String.format(
                    "-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: %s;", accentColor(accentSemantic)));

            // Message
            VBox messageBox = new VBox(2);

            // Title
            String titleText =
                    isOutdated ? "Previously Refined Alignment May Be Outdated" : "Using Previously Refined Alignment";
            Label titleLabel = new Label(titleText);
            titleLabel.setStyle(String.format(
                    "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: %s;", accentColor(accentSemantic)));

            // Date with age indicator
            String ageText;
            if (ageInDays == 0) {
                ageText = "today";
            } else if (ageInDays == 1) {
                ageText = "yesterday";
            } else if (ageInDays > 1) {
                ageText = String.format("%d days ago", ageInDays);
            } else {
                ageText = ""; // Unknown age
            }

            String detailText = date != null
                    ? String.format("Last refined: %s%s", date, ageText.isEmpty() ? "" : " (" + ageText + ")")
                    : "Last refined: Unknown";
            Label detailLabel = new Label(detailText);
            detailLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.75;");

            // Recommendation
            String recommendationText = isOutdated
                    ? "The system may have shifted - consider re-aligning"
                    : "Recommendation: Proceed without refinement";
            Label recommendationLabel = new Label(recommendationText);
            recommendationLabel.setStyle(String.format(
                    "-fx-font-size: 11px; -fx-font-style: italic; -fx-text-fill: %s;", accentColor(accentSemantic)));

            messageBox.getChildren().addAll(titleLabel, detailLabel, recommendationLabel);

            banner.getChildren().addAll(iconLabel, messageBox);
            return banner;
        }

        private void updatePreviewPanel() {
            try {
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                if (modality == null || objective == null || detector == null) {
                    previewTilesLabel.setText("Tiles: --");
                    previewTimeLabel.setText("Estimated Time: --");
                    previewStorageLabel.setText("Estimated Storage: --");
                    return;
                }

                // Get FOV in microns
                double[] fov = configManager.getModalityFOV(modality, objective, detector);
                if (fov == null) {
                    previewTilesLabel.setText("Tiles: -- (FOV not available)");
                    return;
                }
                double fovWidthMicrons = fov[0];
                double fovHeightMicrons = fov[1];

                // Get image pixel size to convert annotation bounds from pixels to microns
                double imagePixelSize = 1.0; // Default if no image
                QuPathGUI gui = QuPathGUI.getInstance();
                if (gui != null && gui.getImageData() != null) {
                    imagePixelSize =
                            gui.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                }

                // Calculate actual tile count from annotation bounds
                double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                int totalTiles = calculateTileCount(fovWidthMicrons, fovHeightMicrons, overlapPercent, imagePixelSize);

                // Get per-tile image count (angles for PPM, channels for fluorescence/BF-IF)
                int imagesPerTile = getImagesPerTileForModality(modality);

                // Z-stack multiplier
                int zPlanes = 1;
                if (PersistentPreferences.isZStackEnabled()) {
                    double zRange = PersistentPreferences.getZStackRange();
                    double zStep = PersistentPreferences.getZStackStep();
                    if (zStep > 0) {
                        zPlanes = (int) Math.ceil(zRange / zStep) + 1;
                    }
                }

                int totalImages = totalTiles * imagesPerTile * zPlanes;

                // Update labels with actual counts
                String zSuffix = zPlanes > 1 ? String.format(" x %d Z-planes", zPlanes) : "";
                if (imagesPerTile > 1) {
                    ModalityHandler handler = ModalityRegistry.getHandler(modality);
                    boolean isPpm = handler != null && handler.isMultiAngleModality();
                    String unit = isPpm ? "angles" : "channels";
                    previewTilesLabel.setText(String.format(
                            "Images: %,d tiles x %d %s%s = %,d images",
                            totalTiles, imagesPerTile, unit, zSuffix, totalImages));
                } else {
                    previewTilesLabel.setText(
                            String.format("Tiles: %,d%s = %,d images", totalTiles, zSuffix, totalImages));
                }

                // Time estimate using persistent per-file mean from previous acquisitions.
                // The stored mean already bakes in autofocus overhead at its actual
                // occurrence frequency, so the estimate is a simple product.
                String timeEstimate;
                if (PersistentPreferences.hasTimingData()) {
                    long estimatedMs = PersistentPreferences.estimateAcquisitionTime(totalImages);
                    double estimatedSeconds = estimatedMs / 1000.0;
                    timeEstimate = formatTime(estimatedSeconds) + " (based on previous acquisitions)";
                } else {
                    // Fallback: ~2 seconds per image (conservative estimate)
                    double estimatedSeconds = totalImages * 2.0;
                    timeEstimate = formatTime(estimatedSeconds) + " (estimate)";
                }
                previewTimeLabel.setText("Estimated Time: " + timeEstimate);

                // Storage estimate: ~4 MB per image
                double estimatedMB = totalImages * 4.0;
                String storageEstimate = formatStorage(estimatedMB);
                previewStorageLabel.setText("Estimated Storage: " + storageEstimate);

            } catch (Exception e) {
                logger.debug("Preview update error: {}", e.getMessage());
            }
        }

        /**
         * Calculates the total tile count for all annotations.
         *
         * @param fovWidthMicrons FOV width in microns
         * @param fovHeightMicrons FOV height in microns
         * @param overlapPercent Overlap percentage
         * @param imagePixelSize Image pixel size in microns
         * @return Total number of tiles
         */
        private int calculateTileCount(
                double fovWidthMicrons, double fovHeightMicrons, double overlapPercent, double imagePixelSize) {
            if (annotations.isEmpty()) {
                return 0;
            }

            // Effective FOV size considering overlap
            double effectiveFovWidth = fovWidthMicrons * (1 - overlapPercent / 100.0);
            double effectiveFovHeight = fovHeightMicrons * (1 - overlapPercent / 100.0);

            int totalTiles = 0;
            for (PathObject ann : annotations) {
                if (ann.getROI() != null) {
                    // Get annotation bounds in pixels, convert to microns
                    double annWidthMicrons = ann.getROI().getBoundsWidth() * imagePixelSize;
                    double annHeightMicrons = ann.getROI().getBoundsHeight() * imagePixelSize;

                    // Add buffer (half FOV on each side) like TilingUtilities does
                    annWidthMicrons += fovWidthMicrons;
                    annHeightMicrons += fovHeightMicrons;

                    // Calculate tiles for this annotation
                    int tilesX = (int) Math.ceil(annWidthMicrons / effectiveFovWidth);
                    int tilesY = (int) Math.ceil(annHeightMicrons / effectiveFovHeight);
                    totalTiles += tilesX * tilesY;
                }
            }

            return totalTiles;
        }

        /**
         * Gets the number of images captured per tile position for a modality.
         * For PPM this is the number of rotation angles; for fluorescence/BF-IF
         * this is the number of channels.
         *
         * @param modality The modality name
         * @return Images per tile (minimum 1)
         */
        private int getImagesPerTileForModality(String modality) {
            if (modality == null) return 1;

            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            if (handler != null) {
                // PPM: use angle overrides if available, else default angle count
                if (handler.isMultiAngleModality()) {
                    if (modalityUI != null) {
                        Map<String, Double> overrides = modalityUI.getAngleOverrides();
                        if (overrides != null && !overrides.isEmpty()) {
                            return overrides.size();
                        }
                    }
                    return handler.getDefaultAngleCount();
                }
            }

            // Non-PPM: count channels from the modality config
            try {
                List<Channel> channels = configManager.getModalityChannels(modality);
                if (channels != null && !channels.isEmpty()) {
                    return channels.size();
                }
            } catch (Exception e) {
                logger.debug("Could not read channels for modality {}: {}", modality, e.getMessage());
            }
            return 1;
        }

        private String formatTime(double seconds) {
            if (seconds < 60) {
                return String.format("~%.0f seconds", seconds);
            } else if (seconds < 3600) {
                return String.format("~%.0f minutes", seconds / 60.0);
            } else {
                return String.format("~%.1f hours", seconds / 3600.0);
            }
        }

        private String formatStorage(double megabytes) {
            if (megabytes < 1024) {
                return String.format("~%.0f MB", megabytes);
            } else {
                return String.format("~%.1f GB", megabytes / 1024.0);
            }
        }

        private void validateAll() {
            String sampleName = sampleNameField.getText().trim();
            String sampleError = SampleNameValidator.getValidationError(sampleName);
            if (sampleError != null) {
                validationErrors.put("sampleName", sampleError);
            } else {
                validationErrors.remove("sampleName");
            }

            if (modalityBox.getValue() == null) {
                validationErrors.put("modality", "Please select a modality");
            } else {
                validationErrors.remove("modality");
            }

            if (objectiveBox.getValue() == null) {
                validationErrors.put("objective", "Please select an objective");
            } else {
                validationErrors.remove("objective");
            }

            if (detectorBox.getValue() == null) {
                validationErrors.put("detector", "Please select a detector");
            } else {
                validationErrors.remove("detector");
            }

            if (useExistingRadio.isSelected() && transformCombo.getValue() == null && !hasSlideAlignment) {
                validationErrors.put("transform", "Please select a transform");
            } else {
                validationErrors.remove("transform");
            }

            updateErrorSummary();
        }

        private void updateErrorSummary() {
            if (validationErrors.isEmpty()) {
                errorSummaryPanel.setVisible(false);
                errorSummaryPanel.setManaged(false);
                startButton.setDisable(false);
            } else {
                errorListBox.getChildren().clear();
                validationErrors.forEach((fieldId, errorMsg) -> {
                    Label errorLabel = new Label("- " + errorMsg);
                    errorLabel.setStyle("-fx-text-fill: " + accentColor("amber") + ";");
                    errorListBox.getChildren().add(errorLabel);
                });

                errorSummaryPanel.setVisible(true);
                errorSummaryPanel.setManaged(true);
                startButton.setDisable(true);
            }
        }

        private ExistingImageAcquisitionConfig createResult() {
            try {
                String sampleName = sampleNameField.getText().trim();
                File projectsFolder = new File(folderField.getText().trim());
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                boolean useExisting = useExistingRadio.isSelected();
                AffineTransformManager.TransformPreset transform = useExisting ? transformCombo.getValue() : null;
                double confidence = transform != null ? AlignmentHelper.calculateConfidence(transform) : 0.0;

                RefinementChoice refinement = RefinementChoice.NONE;
                if (singleTileRadio.isSelected()) {
                    refinement = RefinementChoice.SINGLE_TILE;
                } else if (fullManualRadio.isSelected()) {
                    refinement = RefinementChoice.FULL_MANUAL;
                }

                // Angle / channel-intensity overrides + focus channel from modality UI
                // (green box params handled by ExistingAlignmentPath)
                Map<String, Double> angleOverrides = null;
                Map<String, Double> channelIntensityOverrides = Map.of();
                String focusChannelId = null;
                if (modalityUI != null) {
                    angleOverrides = modalityUI.getAngleOverrides();
                    Map<String, Double> intensityMap = modalityUI.getChannelIntensityOverrides();
                    channelIntensityOverrides = intensityMap == null ? Map.of() : intensityMap;
                    focusChannelId = modalityUI.getFocusChannelId();
                }

                // Autofocus strategy override: display -> protocol name.
                // Null means "use YAML per-modality binding".
                String afStrategyProtocol = AfStrategyChoice.displayToProtocol(
                        afStrategyCombo != null ? afStrategyCombo.getValue() : AfStrategyChoice.DEFAULT_DISPLAY);
                PersistentPreferences.setLastAfStrategy(afStrategyProtocol);
                if (afStrategyProtocol != null) {
                    logger.info("User selected AF strategy override: {}", afStrategyProtocol);
                }

                // Get white balance settings from ComboBox.
                // Force "off" when the WB row is hidden (non-JAI cameras or
                // modalities that don't use WB, e.g. Fluorescence).
                String wbMode;
                if (wbModeComboBox == null || !wbModeComboBox.isVisible()) {
                    wbMode = "off";
                } else {
                    String wbModeDisplay = wbModeComboBox.getValue();
                    wbMode = WbMode.fromDisplayName(wbModeDisplay != null ? wbModeDisplay : "Off")
                            .getProtocolName();
                }
                boolean enableWhiteBalance = !"off".equals(wbMode);
                boolean perAngleWhiteBalance = "per_angle".equals(wbMode);

                // Drift guard: surface any divergence between the dialog combo and
                // the global objective state that may have accumulated since this
                // dialog opened (e.g. Live Viewer Camera tab pick, another dialog's
                // submit). The combo wins (user's explicit choice), but the warning
                // flags a drift we want to catch early.
                String globalObjective =
                        qupath.ext.qpsc.state.ObjectiveState.getInstance().getObjective();
                if (objective != null
                        && !objective.isEmpty()
                        && globalObjective != null
                        && !globalObjective.isEmpty()
                        && !globalObjective.equals(objective)) {
                    logger.warn(
                            "Existing image acquisition objective drift: dialog combo='{}' but "
                                    + "ObjectiveState='{}'. Using dialog value.",
                            objective,
                            globalObjective);
                }

                // Save preferences. Modality + objective route through their
                // central state singletons so cross-dialog sync stays canonical.
                PersistentPreferences.setLastSampleName(sampleName);
                qupath.ext.qpsc.state.ModalityState.getInstance().setModality(modality);
                qupath.ext.qpsc.state.ObjectiveState.getInstance().setObjective(objective);
                PersistentPreferences.setLastDetector(detector);

                logger.info(
                        "Created acquisition config: sample={}, modality={}, useExisting={}, refinement={}, "
                                + "wbMode={}, enableWB={}, perAngleWB={}",
                        sampleName,
                        modality,
                        useExisting,
                        refinement,
                        wbMode,
                        enableWhiteBalance,
                        perAngleWhiteBalance);

                // Resolve --inner-axis from the loop-order toggle. Null means
                // "use server's per-modality default" so byte-identical wire
                // format is preserved when the toggle stays on default.
                String innerAxis = null;
                String loopFamily = loopOrderFamily(modality);
                if (loopFamily != null
                        && loopOrderInnerAltRadio != null
                        && !loopOrderInnerAltRadio.isDisabled()
                        && loopOrderInnerAltRadio.isSelected()) {
                    if (PersistentPreferences.LOOP_ORDER_FAMILY_WIDEFIELD.equals(loopFamily)) {
                        innerAxis = "channel";
                    } else if (PersistentPreferences.LOOP_ORDER_FAMILY_PPM.equals(loopFamily)) {
                        innerAxis = "z";
                    }
                }

                return new ExistingImageAcquisitionConfig(
                        sampleName,
                        projectsFolder,
                        hasOpenProject,
                        modality,
                        objective,
                        detector,
                        useExisting,
                        transform,
                        confidence,
                        refinement,
                        angleOverrides,
                        channelIntensityOverrides,
                        focusChannelId,
                        afStrategyProtocol,
                        enableWhiteBalance,
                        perAngleWhiteBalance,
                        wbMode,
                        innerAxis);

            } catch (Exception e) {
                logger.error("Error creating result", e);
                return null;
            }
        }

        private static String extractIdFromDisplayString(String displayString) {
            if (displayString == null) return null;

            int openParen = displayString.lastIndexOf('(');
            int closeParen = displayString.lastIndexOf(')');

            if (openParen != -1 && closeParen != -1 && closeParen > openParen) {
                return displayString.substring(openParen + 1, closeParen);
            }

            return displayString;
        }
    }
}
