package qupath.ext.qpsc.ui;

import java.io.File;
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
import qupath.ext.qpsc.controller.MicroscopeController;
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
import qupath.ext.qpsc.service.OutputFormat;
import qupath.ext.qpsc.service.mda.MdaExportAction;
import qupath.ext.qpsc.service.mda.MdaExportContext;
import qupath.ext.qpsc.service.mda.TileStagePos;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.AcquisitionConfigurationBuilder;
import qupath.ext.qpsc.utilities.BackgroundValidityChecker;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.ObjectiveUtils;
import qupath.ext.qpsc.utilities.SampleNameValidator;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.QuPathStyleManager;

/**
 * Unified acquisition controller that consolidates sample setup, hardware selection,
 * and bounding box configuration into a single dialog with collapsible sections.
 * <p>
 * This replaces the multi-dialog workflow with a single-screen experience where users
 * can see all configuration at once while still having the option to collapse sections
 * they don't need to modify.
 * <p>
 * Key features:
 * <ul>
 *   <li>TitledPane sections for Project, Hardware, Region, and Modality-Specific options</li>
 *   <li>Real-time acquisition preview showing tile count, time estimate, storage estimate</li>
 *   <li>Debounced preview updates (300ms delay) for responsive feedback</li>
 *   <li>Persistent preferences for all fields and section expansion states</li>
 *   <li>Validation with clickable error summary</li>
 * </ul>
 */
public class UnifiedAcquisitionController {
    private static final Logger logger = LoggerFactory.getLogger(UnifiedAcquisitionController.class);

    /** Debounce delay for preview updates in milliseconds */
    private static final long PREVIEW_DEBOUNCE_MS = 300;

    /** Stitched-output organization dropdown labels and persistence key. */
    private static final String ORG_SINGLE = "Single combined file";

    private static final String ORG_PER_CHANNEL = "Separate file per channel";
    private static final String PREF_STITCH_ORGANIZATION = "qpscStitchOrganization";

    /** Map an organization dropdown label to its {@link OutputFormat}. */
    private static OutputFormat organizationFromDisplay(String display) {
        return ORG_PER_CHANNEL.equals(display) ? OutputFormat.OME_PER_CHANNEL : OutputFormat.OME_SINGLE;
    }

    /**
     * Result record containing all user selections from the unified dialog.
     * <p>
     * Note: Exposure times are not included here because PPM modality uses per-angle
     * exposures from configuration via PPMPreferences, not a single exposure value.
     */
    public record UnifiedAcquisitionResult(
            String sampleName,
            File projectsFolder,
            String modality,
            String objective,
            String detector,
            double x1,
            double y1,
            double x2,
            double y2,
            Map<String, Double> angleOverrides,
            Map<String, Double> channelIntensityOverrides,
            String focusChannelId,
            String afStrategy,
            boolean enableWhiteBalance,
            boolean perAngleWhiteBalance,
            String wbMode,
            // Per-tile snap-loop inner axis. Null = omit and let the server fall
            // back to its per-modality default. Values: "z", "channel", "angle".
            String innerAxis,
            // True when the AF method benchmark checkbox is ticked: every tile
            // runs sweep + streaming AF (timed, neither applied) and the server
            // writes af_benchmark.csv. Diagnostic mode -- acquired images drift
            // out of focus since no AF result is applied.
            boolean afBenchmark,
            // Channel ids the user marked "Split" -- each is stitched into its own
            // file instead of being merged. Empty = merge all (the default).
            Set<String> splitChannelIds,
            // How the stitched output is grouped (single combined file vs one file
            // per channel). Defaults to OME_SINGLE.
            OutputFormat stitchingOrganization) {}

    /**
     * Shows the unified acquisition dialog.
     *
     * @return CompletableFuture containing the result, or cancelled if user cancels
     */
    public static CompletableFuture<UnifiedAcquisitionResult> showDialog() {
        CompletableFuture<UnifiedAcquisitionResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                UnifiedDialogBuilder builder = new UnifiedDialogBuilder();
                Optional<UnifiedAcquisitionResult> result = builder.buildAndShow();

                if (result.isPresent()) {
                    future.complete(result.get());
                } else {
                    future.cancel(true);
                }
            } catch (Exception e) {
                logger.error("Error showing unified acquisition dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Internal builder class that constructs and manages the unified dialog.
     */
    private static class UnifiedDialogBuilder {
        private final ResourceBundle res;
        private final MicroscopeConfigManager configManager;
        private final boolean hasOpenProject;
        private final String existingProjectName;
        private final File existingProjectFolder;

        // UI Components - Project Section
        private TextField sampleNameField;
        private Label sampleNameErrorLabel;
        private TextField folderField;

        // UI Components - Hardware Section
        private ComboBox<String> modalityBox;
        private ComboBox<String> objectiveBox;
        private ComboBox<String> detectorBox;

        // UI Components - Region Section
        private TextField startXField;
        private TextField startYField;
        private TextField widthField;
        private TextField heightField;
        private TextField endXField;
        private TextField endYField;
        private Label calculatedBoundsLabel;
        private RadioButton startSizeMode;
        private RadioButton twoCornersMode;
        private GridPane startSizePane;
        private GridPane twoCornersPane;

        // UI Components - Preview Section
        private Label previewRegionLabel;
        private Label previewFOVLabel;
        private Label previewTileGridLabel;
        private Label previewAnglesLabel;
        private Label previewTotalImagesLabel;
        private Label previewTimeLabel;
        private Label previewCompletionLabel;
        private Label previewStorageLabel;
        private Label previewErrorLabel;

        // UI Components - Modality Section
        private ModalityHandler.BoundingBoxUI modalityUI;

        // UI Components - White Balance Section (JAI camera only)
        private VBox whiteBalanceSection;
        private ComboBox<String> wbModeComboBox;
        private Label wbModeLabel;
        private CheckBox monoBgCorrectionCheck;

        // UI Components - Advanced Section Content
        private VBox advancedContent;
        private VBox modalityContentBox;
        private ComboBox<String> afStrategyCombo;
        private CheckBox afBenchmarkCheck;

        // UI Components - Z-stack Section
        private CheckBox zStackEnableCheck;
        private ComboBox<String> outputOrganizationCombo;
        private RadioButton loopOrderInnerZRadio;
        private RadioButton loopOrderInnerAltRadio;
        private Label loopOrderLabel;
        private VBox loopOrderRow;

        // UI Components - Time-lapse Section
        private CheckBox timeLapseEnableCheck;
        private Spinner<Integer> timeLapseTimepointsSpinner;
        private Spinner<Double> timeLapseIntervalSpinner;

        // UI Components - Validation
        private VBox errorSummaryPanel;
        private VBox errorListBox;
        private final Map<String, String> validationErrors = new LinkedHashMap<>();

        // TitledPanes for section management
        private TitledPane projectPane;
        private TitledPane hardwarePane;
        private TitledPane regionPane;
        private TitledPane advancedPane;

        // Debounce timer for preview updates
        private final PauseTransition previewDebounce = new PauseTransition(Duration.millis(PREVIEW_DEBOUNCE_MS));

        // Dialog and OK button references
        private Dialog<UnifiedAcquisitionResult> dialog;
        private Button okButton;

        UnifiedDialogBuilder() {
            this.res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
            this.configManager =
                    MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

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
        }

        private boolean isDark() {
            return QuPathStyleManager.getStyleColorScheme() == ColorScheme.DARK;
        }

        private String subtleTextStyle() {
            return "-fx-font-size: 11px; -fx-opacity: 0.65;";
        }

        private String accentColor(String semantic) {
            boolean dark = isDark();
            switch (semantic) {
                case "green":
                    return dark ? "#66BB6A" : "#28a745";
                case "red":
                    return dark ? "#EF5350" : "#C62828";
                case "amber":
                    return dark ? "#FFD54F" : "#856404";
                default:
                    return dark ? "#B0BEC5" : "#666";
            }
        }

        Optional<UnifiedAcquisitionResult> buildAndShow() {
            dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.setTitle("Bounded Acquisition");
            dialog.setHeaderText("Configure and start a new bounded acquisition.\n"
                    + "All settings are visible below - expand sections as needed.");
            dialog.setGraphic(DocumentationHelper.createHelpButton("boundedAcquisition"));
            dialog.setResizable(true);

            // Add buttons
            ButtonType okType = new ButtonType("Start Acquisition", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);

            okButton = (Button) dialog.getDialogPane().lookupButton(okType);
            okButton.setDisable(true); // Start disabled until validation passes

            // Build error summary panel FIRST - other sections may trigger validation during init
            createErrorSummaryPanel();

            // Build all sections
            createProjectSection();
            createWbModeCombo(); // Must be before hardware section (WB combo placed in hardware grid)
            createHardwareSection();
            createRegionSection();
            createPreviewPanel();
            createAdvancedSection();
            TitledPane zStackPane = createZStackSection();
            TitledPane timeLapsePane = createTimeLapseSection();

            // Setup debounced preview updates
            setupPreviewUpdateListeners();

            // Assemble the main content
            VBox mainContent = new VBox(
                    10,
                    errorSummaryPanel,
                    projectPane,
                    hardwarePane,
                    regionPane,
                    advancedPane,
                    zStackPane,
                    timeLapsePane,
                    createPreviewSection());
            mainContent.setPadding(new Insets(15));

            // Wrap in scroll pane for smaller screens
            ScrollPane scrollPane = new ScrollPane(mainContent);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefViewportHeight(600);
            scrollPane.setPrefViewportWidth(700);

            dialog.getDialogPane().setContent(scrollPane);
            dialog.getDialogPane().setPrefWidth(750);
            dialog.getDialogPane().setPrefHeight(700);

            // Initialize hardware dropdowns
            initializeHardwareSelections();

            // Initial validation
            Platform.runLater(this::validateAll);

            // Result converter
            dialog.setResultConverter(button -> {
                if (button != okType) {
                    return null;
                }
                return createResult();
            });

            try {
                return dialog.showAndWait();
            } finally {
                // Always clear the stage-map preview once the dialog is dismissed --
                // acquisition progress (if started) is shown by the acquisition overlay instead.
                StageMapWindow.clearBoundingBoxPreview();
            }
        }

        private void createProjectSection() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            // Sample name field
            sampleNameField = new TextField();
            sampleNameField.setPromptText("e.g., MySample01");
            sampleNameField.setPrefWidth(300);

            String lastSampleName = PersistentPreferences.getLastSampleName();
            if (!lastSampleName.isEmpty()) {
                sampleNameField.setText(lastSampleName);
            }

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
                folderField = new TextField();
                folderField.setPrefWidth(250);
                folderField.setText(QPPreferenceDialog.getProjectsFolderProperty());

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
                row++;

                Label infoLabel = new Label("A new project will be created for this sample.");
                infoLabel.setStyle("-fx-font-style: italic; " + subtleTextStyle());
                grid.add(infoLabel, 0, row, 2, 1);
            } else {
                folderField = new TextField(existingProjectFolder.getParent());
                folderField.setVisible(false);

                Label projectLabel = new Label(existingProjectName);
                projectLabel.setStyle("-fx-font-weight: bold;");
                grid.add(new Label("Project:"), 0, row);
                grid.add(projectLabel, 1, row);
                row++;

                Label locationLabel = new Label(existingProjectFolder.getParent());
                locationLabel.setStyle(subtleTextStyle());
                grid.add(new Label("Location:"), 0, row);
                grid.add(locationLabel, 1, row);
                row++;

                Label infoLabel = new Label("Sample will be added to the existing project.");
                infoLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11px;");
                grid.add(infoLabel, 0, row, 2, 1);
            }

            projectPane = new TitledPane("PROJECT & SAMPLE", grid);
            projectPane.setExpanded(true);
            projectPane.setStyle("-fx-font-weight: bold;");
        }

        /**
         * Get the default WB mode from the current modality handler.
         * Maps handler's wire mode string (e.g. "off", "per_angle") to
         * the display name used in the ComboBox.
         */
        private String getDefaultWbModeFromModality() {
            try {
                String modality = modalityBox != null ? modalityBox.getValue() : null;
                if (modality != null) {
                    var handler = qupath.ext.qpsc.modality.ModalityRegistry.getHandler(modality);
                    String wireMode = handler.getDefaultWbMode();
                    // Map wire mode to display name
                    for (qupath.ext.qpsc.modality.WbMode wm : qupath.ext.qpsc.modality.WbMode.values()) {
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

        private void createWbModeCombo() {
            wbModeComboBox = new ComboBox<>();
            wbModeComboBox.getItems().addAll("Off", "Camera AWB", "Simple (90deg)", "Per-angle (PPM)");
            String savedWBMode = PersistentPreferences.getLastWhiteBalanceMode();
            if (savedWBMode != null && wbModeComboBox.getItems().contains(savedWBMode)) {
                wbModeComboBox.setValue(savedWBMode);
            } else {
                // Use modality handler's default WB mode
                String defaultWbMode = getDefaultWbModeFromModality();
                wbModeComboBox.setValue(defaultWbMode);
            }
            WbMode.applyColorCellFactory(wbModeComboBox);
            wbModeComboBox.setTooltip(new Tooltip("White balance mode:\n"
                    + "  Off - No white balance correction\n"
                    + "  Camera AWB - Set in MicroManager before acquisition\n"
                    + "  Simple (90deg) - Use 90deg R:G:B ratios, scaled per angle\n"
                    + "  Per-angle (PPM) - Independent calibration per angle (default)"));

            wbModeComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    PersistentPreferences.setLastWhiteBalanceMode(newVal);
                }
            });
        }

        private void createHardwareSection() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            // Get available modalities
            Set<String> modalities = configManager.getSection("modalities").keySet();

            modalityBox = new ComboBox<>(FXCollections.observableArrayList(modalities));
            modalityBox.setPrefWidth(200);

            objectiveBox = new ComboBox<>();
            objectiveBox.setPrefWidth(300);

            detectorBox = new ComboBox<>();
            detectorBox.setPrefWidth(300);

            // Set up cascading selection listeners
            modalityBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    updateObjectivesForModality(newVal);
                    updateModalityUI(newVal);
                    updateAfStrategyDefaultForModality(newVal);
                }
            });

            objectiveBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && modalityBox.getValue() != null) {
                    updateDetectorsForObjective(modalityBox.getValue(), newVal);
                }
            });

            // Trigger preview update on any hardware change
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

            // WB Mode - moved here from Advanced Options for visibility
            wbModeLabel = new Label("WB Mode:");
            wbModeLabel.setTooltip(new Tooltip("White balance mode for acquisition"));
            grid.add(wbModeLabel, 0, row);
            grid.add(wbModeComboBox, 1, row);
            row++;

            // Background correction toggle for monochrome (non-JAI) detectors.
            // JAI cameras bundle background correction into the WB mode
            // selector; monochrome detectors need an independent toggle so
            // that brightfield / fluorescence acquisitions can apply flat-field
            // correction when a matching background image exists on disk.
            monoBgCorrectionCheck = new CheckBox("Use background correction");
            monoBgCorrectionCheck.setSelected(PersistentPreferences.getUseBackgroundCorrectionMono());
            monoBgCorrectionCheck.setTooltip(
                    new Tooltip("Apply flat-field background correction to monochrome acquisitions.\n"
                            + "Requires a matching background image collected at the same\n"
                            + "objective/detector (and ideally the same exposure)."));
            monoBgCorrectionCheck
                    .selectedProperty()
                    .addListener((obs, o, n) -> PersistentPreferences.setUseBackgroundCorrectionMono(n));
            grid.add(monoBgCorrectionCheck, 0, row, 2, 1);
            row++;

            // Pixel size mismatch warning
            Label pixelSizeWarning = new Label();
            pixelSizeWarning.setWrapText(true);
            pixelSizeWarning.setStyle(
                    "-fx-text-fill: " + accentColor("red") + "; -fx-font-weight: bold; -fx-font-size: 11px;");
            pixelSizeWarning.setVisible(false);
            pixelSizeWarning.setManaged(false);
            grid.add(pixelSizeWarning, 0, row, 2, 1);

            // Check pixel size when objective changes
            objectiveBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                checkPixelSizeMismatch(newVal, pixelSizeWarning);
            });

            hardwarePane = new TitledPane("HARDWARE CONFIGURATION", grid);
            hardwarePane.setExpanded(true);
            hardwarePane.setStyle("-fx-font-weight: bold;");
        }

        private void checkPixelSizeMismatch(String objectiveDisplay, Label warningLabel) {
            if (objectiveDisplay == null || objectiveDisplay.isEmpty()) {
                warningLabel.setVisible(false);
                warningLabel.setManaged(false);
                return;
            }

            // The combo boxes hold "friendlyName (id)" display strings;
            // getPixelSize expects raw IDs.
            final String objective = extractIdFromDisplayString(objectiveDisplay);
            final String detectorDisplay = detectorBox.getValue();
            final String detector = detectorDisplay != null ? extractIdFromDisplayString(detectorDisplay) : "";

            // Run in background to avoid blocking UI with socket call
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    if (!MicroscopeController.getInstance().isConnected()) return;

                    double mmPixelSize =
                            MicroscopeController.getInstance().getSocketClient().getMicroscopePixelSize();
                    if (mmPixelSize <= 0) return;

                    // Get QPSC config pixel size for the selected objective
                    double configPixelSize = configManager.getPixelSize(objective, detector);
                    if (configPixelSize <= 0) return;

                    // Round both to the fewer decimal places
                    int mmDecimals = countDecimals(mmPixelSize);
                    int cfgDecimals = countDecimals(configPixelSize);
                    int minDecimals = Math.min(mmDecimals, cfgDecimals);
                    minDecimals = Math.max(minDecimals, 2); // at least 2

                    double scale = Math.pow(10, minDecimals);
                    double mmRounded = Math.round(mmPixelSize * scale) / scale;
                    double cfgRounded = Math.round(configPixelSize * scale) / scale;

                    double diff = Math.abs(mmRounded - cfgRounded);
                    double tolerance = cfgRounded * 0.1; // 10% tolerance

                    javafx.application.Platform.runLater(() -> {
                        if (diff > tolerance) {
                            warningLabel.setText(String.format(
                                    "WARNING: Pixel size mismatch! Micro-Manager reports %.4f um/px "
                                            + "but QPSC config expects %.4f um/px for this objective. "
                                            + "Check that the correct objective is selected in Micro-Manager.",
                                    mmPixelSize, configPixelSize));
                            warningLabel.setVisible(true);
                            warningLabel.setManaged(true);
                        } else {
                            warningLabel.setVisible(false);
                            warningLabel.setManaged(false);
                        }
                    });
                } catch (Exception e) {
                    // Non-critical -- just skip the check
                    logger.debug("Could not check pixel size: {}", e.getMessage());
                }
            });
        }

        private static int countDecimals(double value) {
            String text = String.valueOf(value);
            int dotIndex = text.indexOf('.');
            if (dotIndex < 0) return 0;
            // Strip trailing zeros
            String decimals = text.substring(dotIndex + 1).replaceAll("0+$", "");
            return decimals.length();
        }

        private void createRegionSection() {
            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            // Mode selection toggle
            ToggleGroup modeGroup = new ToggleGroup();
            startSizeMode = new RadioButton("Center Point + Size");
            twoCornersMode = new RadioButton("Two Corners");
            startSizeMode.setToggleGroup(modeGroup);
            twoCornersMode.setToggleGroup(modeGroup);
            startSizeMode.setSelected(true);

            HBox modeBox = new HBox(20, startSizeMode, twoCornersMode);
            modeBox.setAlignment(Pos.CENTER_LEFT);

            // Description label for center+size mode
            Label centerModeDescription = new Label("The center position defines the midpoint of the acquisition area. "
                    + "Tiles extend equally in all directions from this point.");
            centerModeDescription.setStyle(subtleTextStyle());
            centerModeDescription.setWrapText(true);

            // === Center + Size Mode Pane ===
            startSizePane = new GridPane();
            startSizePane.setHgap(10);
            startSizePane.setVgap(8);

            // Initialize center coordinates: prefer saved value, then current stage position
            String savedCenterX = PersistentPreferences.getBoundingBoxCenterX();
            String savedCenterY = PersistentPreferences.getBoundingBoxCenterY();
            if ((savedCenterX == null || savedCenterX.isEmpty()) && (savedCenterY == null || savedCenterY.isEmpty())) {
                // No saved center -- try current stage position
                try {
                    if (MicroscopeController.getInstance().isConnected()) {
                        double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                        if (coords != null && coords.length >= 2) {
                            savedCenterX = String.format("%.2f", coords[0]);
                            savedCenterY = String.format("%.2f", coords[1]);
                            logger.info(
                                    "Initialized center from current stage position: {}, {}",
                                    savedCenterX,
                                    savedCenterY);
                        }
                    }
                } catch (Exception ex) {
                    logger.debug("Could not read stage position for center default: {}", ex.getMessage());
                }
            }
            startXField = new TextField(savedCenterX != null ? savedCenterX : "");
            startYField = new TextField(savedCenterY != null ? savedCenterY : "");
            startXField.setPromptText("Center X");
            startYField.setPromptText("Center Y");
            startXField.setPrefWidth(120);
            startYField.setPrefWidth(120);
            startXField.setTooltip(new Tooltip("Center X coordinate of the acquisition region in micrometers.\n"
                    + "The tiled area extends equally in both directions from this point."));
            startYField.setTooltip(new Tooltip("Center Y coordinate of the acquisition region in micrometers.\n"
                    + "The tiled area extends equally in both directions from this point."));

            widthField = new TextField(PersistentPreferences.getBoundingBoxWidth());
            heightField = new TextField(PersistentPreferences.getBoundingBoxHeight());
            widthField.setPromptText("Width");
            heightField.setPromptText("Height");
            widthField.setPrefWidth(120);
            heightField.setPrefWidth(120);
            widthField.setTooltip(new Tooltip("Total width of the acquisition region in micrometers."));
            heightField.setTooltip(new Tooltip("Total height of the acquisition region in micrometers."));

            Button getStartPosBtn = new Button("Use Current Position as Center");
            getStartPosBtn.setTooltip(new Tooltip("Set the center of the acquisition region to the current\n"
                    + "stage position (center of the Live Viewer field of view)."));
            getStartPosBtn.setOnAction(e -> {
                try {
                    double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                    if (coords != null && coords.length >= 2) {
                        startXField.setText(String.format("%.2f", coords[0]));
                        startYField.setText(String.format("%.2f", coords[1]));
                        logger.info("Updated center position from stage: X={}, Y={}", coords[0], coords[1]);
                        if (widthField.getText().trim().isEmpty()) widthField.setText("2000");
                        if (heightField.getText().trim().isEmpty()) heightField.setText("2000");
                    }
                } catch (Exception ex) {
                    logger.error("Failed to get stage position", ex);
                    UIFunctions.showAlertDialog("Failed to get stage position: " + ex.getMessage());
                }
            });

            int row = 0;
            startSizePane.add(new Label("Center X (um):"), 0, row);
            startSizePane.add(startXField, 1, row);
            startSizePane.add(new Label("Center Y (um):"), 2, row);
            startSizePane.add(startYField, 3, row);
            startSizePane.add(getStartPosBtn, 4, row);
            row++;
            startSizePane.add(new Label("Width (um):"), 0, row);
            startSizePane.add(widthField, 1, row);
            startSizePane.add(new Label("Height (um):"), 2, row);
            startSizePane.add(heightField, 3, row);

            // === Two Corners Mode Pane ===
            twoCornersPane = new GridPane();
            twoCornersPane.setHgap(10);
            twoCornersPane.setVgap(8);
            twoCornersPane.setVisible(false);
            twoCornersPane.setManaged(false);

            endXField = new TextField();
            endYField = new TextField();
            endXField.setPromptText("X");
            endYField.setPromptText("Y");
            endXField.setPrefWidth(120);
            endYField.setPrefWidth(120);

            // Reuse startX/Y for corner 1 in two-corners mode
            TextField corner1XField = new TextField();
            TextField corner1YField = new TextField();
            corner1XField.setPromptText("X");
            corner1YField.setPromptText("Y");
            corner1XField.setPrefWidth(120);
            corner1YField.setPrefWidth(120);

            Button getCorner1Btn = new Button("Get Stage Position");
            getCorner1Btn.setOnAction(e -> {
                try {
                    double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                    if (coords != null && coords.length >= 2) {
                        corner1XField.setText(String.format("%.2f", coords[0]));
                        corner1YField.setText(String.format("%.2f", coords[1]));
                        logger.info("Updated corner 1 from stage: X={}, Y={}", coords[0], coords[1]);
                    }
                } catch (Exception ex) {
                    logger.error("Failed to get stage position", ex);
                    UIFunctions.showAlertDialog("Failed to get stage position: " + ex.getMessage());
                }
            });

            Button getCorner2Btn = new Button("Get Stage Position");
            getCorner2Btn.setOnAction(e -> {
                try {
                    double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                    if (coords != null && coords.length >= 2) {
                        endXField.setText(String.format("%.2f", coords[0]));
                        endYField.setText(String.format("%.2f", coords[1]));
                        logger.info("Updated corner 2 from stage: X={}, Y={}", coords[0], coords[1]);
                    }
                } catch (Exception ex) {
                    logger.error("Failed to get stage position", ex);
                    UIFunctions.showAlertDialog("Failed to get stage position: " + ex.getMessage());
                }
            });

            row = 0;
            twoCornersPane.add(new Label("Corner 1 X (um):"), 0, row);
            twoCornersPane.add(corner1XField, 1, row);
            twoCornersPane.add(new Label("Y (um):"), 2, row);
            twoCornersPane.add(corner1YField, 3, row);
            twoCornersPane.add(getCorner1Btn, 4, row);
            row++;
            twoCornersPane.add(new Label("Corner 2 X (um):"), 0, row);
            twoCornersPane.add(endXField, 1, row);
            twoCornersPane.add(new Label("Y (um):"), 2, row);
            twoCornersPane.add(endYField, 3, row);
            twoCornersPane.add(getCorner2Btn, 4, row);

            // Sync corner1 fields with startX/Y fields for consistency
            corner1XField.textProperty().bindBidirectional(startXField.textProperty());
            corner1YField.textProperty().bindBidirectional(startYField.textProperty());

            // Mode switching logic
            modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
                boolean isStartSize = newVal == startSizeMode;
                startSizePane.setVisible(isStartSize);
                startSizePane.setManaged(isStartSize);
                twoCornersPane.setVisible(!isStartSize);
                twoCornersPane.setManaged(!isStartSize);
                validateRegion();
                triggerPreviewUpdate();
            });

            // Calculated bounds label
            calculatedBoundsLabel = new Label("Enter coordinates to see calculated bounds");
            calculatedBoundsLabel.setStyle(subtleTextStyle());

            // Validation listeners for Start+Size mode
            startXField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
                if (!newVal.isEmpty()) {
                    PersistentPreferences.setBoundingBoxCenterX(newVal);
                }
            });
            startYField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
                if (!newVal.isEmpty()) {
                    PersistentPreferences.setBoundingBoxCenterY(newVal);
                }
            });
            widthField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
                if (!newVal.isEmpty()) {
                    PersistentPreferences.setBoundingBoxWidth(newVal);
                }
            });
            heightField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
                if (!newVal.isEmpty()) {
                    PersistentPreferences.setBoundingBoxHeight(newVal);
                }
            });

            // Validation listeners for Two Corners mode
            endXField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
            });
            endYField.textProperty().addListener((obs, old, newVal) -> {
                validateRegion();
                triggerPreviewUpdate();
            });

            // Load saved bounding box
            String savedBounds = PersistentPreferences.getBoundingBoxString();
            if (savedBounds != null && !savedBounds.trim().isEmpty()) {
                String[] parts = savedBounds.split(",");
                if (parts.length == 4) {
                    try {
                        startXField.setText(parts[0].trim());
                        startYField.setText(parts[1].trim());
                        endXField.setText(parts[2].trim());
                        endYField.setText(parts[3].trim());
                    } catch (Exception e) {
                        // Ignore parsing errors
                    }
                }
            }

            // Assemble content
            content.getChildren()
                    .addAll(modeBox, centerModeDescription, startSizePane, twoCornersPane, calculatedBoundsLabel);

            // Show/hide description label based on mode
            twoCornersMode.selectedProperty().addListener((obs, old, selected) -> {
                centerModeDescription.setVisible(!selected);
                centerModeDescription.setManaged(!selected);
            });

            regionPane = new TitledPane("ACQUISITION REGION", content);
            regionPane.setExpanded(true);
            regionPane.setStyle("-fx-font-weight: bold;");
        }

        private void createPreviewPanel() {
            previewRegionLabel = new Label("Region: --");
            previewFOVLabel = new Label("Field of View: --");
            previewTileGridLabel = new Label("Tile Grid: --");
            previewAnglesLabel = new Label("Angles: --");
            previewTotalImagesLabel = new Label("Total Images: --");
            previewTimeLabel = new Label("Est. Time: --");
            previewTimeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
            previewCompletionLabel = new Label("Est. Completion: --");
            previewStorageLabel = new Label("Est. Storage: --");
            previewErrorLabel = new Label("Enter valid coordinates to see preview");
            previewErrorLabel.setStyle("-fx-font-style: italic; -fx-opacity: 0.65;");
        }

        private TitledPane createPreviewSection() {
            VBox previewContent = new VBox(
                    5,
                    previewRegionLabel,
                    previewFOVLabel,
                    previewTileGridLabel,
                    previewAnglesLabel,
                    previewTotalImagesLabel,
                    new Separator(),
                    previewTimeLabel,
                    previewCompletionLabel,
                    previewStorageLabel,
                    previewErrorLabel);
            previewContent.setPadding(new Insets(10));
            previewContent.setStyle("-fx-background-color: " + (isDark() ? "#333" : "#f8f9fa") + "; -fx-border-color: "
                    + (isDark() ? "#555" : "#dee2e6") + "; -fx-border-width: 1px;");

            TitledPane previewPane = new TitledPane("ACQUISITION PREVIEW", previewContent);
            previewPane.setExpanded(true);
            previewPane.setCollapsible(false);
            previewPane.setStyle("-fx-font-weight: bold;");

            return previewPane;
        }

        private void createAdvancedSection() {
            advancedContent = new VBox(10);
            advancedContent.setPadding(new Insets(10));

            // WB combo already created in createWbModeCombo()

            // === AUTOFOCUS STRATEGY OVERRIDE ===
            // Lets the user override the per-modality strategy declared in
            // autofocus_<scope>.yml for this one acquisition. "Default" uses
            // the YAML binding (the C2 per-modality preselect will set the
            // initial value to match the YAML binding). The other values emit
            // the --af-strategy CLI flag which the Python v2 loader respects.
            afStrategyCombo = new ComboBox<>();
            afStrategyCombo.getItems().addAll(AfStrategyChoice.displayOrder());
            afStrategyCombo.setValue(AfStrategyChoice.protocolToDisplay(PersistentPreferences.getLastAfStrategy()));
            afStrategyCombo.setTooltip(new Tooltip(AfStrategyChoice.TOOLTIP));
            // AF method benchmark: diagnostic mode. When ticked, every tile
            // runs BOTH sweep and streaming autofocus (timed, neither result
            // applied) and the server writes af_benchmark.csv. Acquired images
            // drift out of focus since no AF is applied -- the CSV is the
            // deliverable. Off by default.
            afBenchmarkCheck = new CheckBox("Benchmark AF methods (sweep vs streaming, per tile)");
            afBenchmarkCheck.setSelected(false);
            afBenchmarkCheck.setTooltip(
                    new Tooltip("Diagnostic mode. Each tile runs both sweep and streaming autofocus,"
                            + " times each, and applies NEITHER result -- the stage stays at"
                            + " the pre-AF Z. Per-tile timings are written to af_benchmark.csv"
                            + " in the acquisition output folder. Acquired images will drift"
                            + " out of focus; use a small grid (e.g. 3x3) and treat the images"
                            + " as throwaway."));

            GridPane afGrid = new GridPane();
            afGrid.setHgap(10);
            afGrid.setVgap(5);
            afGrid.setPadding(new Insets(5));
            afGrid.add(new Label("Autofocus:"), 0, 0);
            afGrid.add(afStrategyCombo, 1, 0);
            afGrid.add(afBenchmarkCheck, 0, 1, 2, 1);

            // === MODALITY-SPECIFIC SECTION ===
            modalityContentBox = new VBox(5);
            Label placeholder = new Label("Modality-specific options will appear here when a modality is selected.");
            placeholder.setStyle("-fx-font-style: italic; -fx-opacity: 0.65;");
            modalityContentBox.getChildren().add(placeholder);

            advancedContent.getChildren().addAll(afGrid, new Separator(), modalityContentBox);

            advancedPane = new TitledPane("MODALITY-SPECIFIC OPTIONS", advancedContent);
            advancedPane.setExpanded(false); // Collapsed by default
            advancedPane.setStyle("-fx-font-weight: bold;");
        }

        private TitledPane createZStackSection() {
            zStackEnableCheck = new CheckBox("Enable Z-stack acquisition");
            zStackEnableCheck.setTooltip(
                    new Tooltip("Acquire multiple Z-planes at each tile position and compute a projection. "
                            + "Essential for thick samples and SHG/multiphoton imaging."));
            zStackEnableCheck.setSelected(PersistentPreferences.isZStackEnabled());
            CheckBox enableCheck = zStackEnableCheck;

            Spinner<Double> rangeSpinner = new Spinner<>(1.0, 200.0, PersistentPreferences.getZStackRange(), 5.0);
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
            projectionCombo.getItems().addAll("Max Intensity", "Min Intensity", "Sum", "Mean", "Std Deviation", "None");
            projectionCombo.setValue(PersistentPreferences.getZStackProjection());
            projectionCombo.setTooltip(new Tooltip("How to combine Z-planes for stitching. "
                    + "Max intensity is standard for fluorescence and SHG. "
                    + "Choose \"None\" to preserve the full Z-stack (and time series, if any) "
                    + "as a single multi-dimensional stitched image instead of projecting to 2D."));

            Label infoLabel = new Label();
            infoLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11;");

            // Update info label dynamically
            Runnable updateInfo = () -> {
                double range = rangeSpinner.getValue();
                double step = stepSpinner.getValue();
                int planes = (int) Math.ceil(range / step) + 1;
                infoLabel.setText(String.format("%d planes over +/-%.1f um", planes, range / 2));
            };
            rangeSpinner.valueProperty().addListener((obs, o, n) -> updateInfo.run());
            stepSpinner.valueProperty().addListener((obs, o, n) -> updateInfo.run());
            updateInfo.run();

            // Disable controls when unchecked
            rangeSpinner.disableProperty().bind(enableCheck.selectedProperty().not());
            stepSpinner.disableProperty().bind(enableCheck.selectedProperty().not());
            projectionCombo
                    .disableProperty()
                    .bind(enableCheck.selectedProperty().not());

            // Save to preferences on change
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

            // Stitched-output organization. Independent of Z-stack, so not
            // disabled with the rest of the section. "Single combined file"
            // merges all channels (and Z/T) into one image; "Separate file per
            // channel" writes each channel on its own. The per-channel "Split"
            // checkboxes give finer control between the two.
            outputOrganizationCombo = new ComboBox<>();
            outputOrganizationCombo.getItems().addAll(ORG_SINGLE, ORG_PER_CHANNEL);
            outputOrganizationCombo.setValue(
                    PersistentPreferences.getStringPreference(PREF_STITCH_ORGANIZATION, ORG_SINGLE));
            outputOrganizationCombo.setTooltip(new Tooltip("How to group the stitched output. "
                    + "\"Single combined file\" merges all channels (and any preserved Z/T) into one image; "
                    + "\"Separate file per channel\" writes each channel as its own file. The per-channel "
                    + "\"Split\" checkboxes give finer control."));
            outputOrganizationCombo.valueProperty().addListener((obs, o, n) -> {
                if (n != null) PersistentPreferences.setStringPreference(PREF_STITCH_ORGANIZATION, n);
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
            grid.add(new Label("Stitched output:"), 0, 4);
            grid.add(outputOrganizationCombo, 1, 4);

            // Loop-order toggle: lets the user flip the per-tile snap nest
            // between the current "Z-inner" default (one channel sweeps all
            // Z, then switch channels -- fewer filter changes, fast for
            // fixed slides) and "channel-inner" (every channel at each Z --
            // slower but tightly Z-registered for live samples). Labels swap
            // for PPM (angle-inner default vs Z-inner alternative).
            //
            // The toggle is only meaningful when Z-stack is on AND the
            // modality has 2+ outer-axis units (channels or angles) -- the
            // disable binding wired in updateLoopOrderToggle() reflects that.
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
            // Persist whichever radio is selected, keyed by the current
            // modality family. updateLoopOrderToggle() suppresses these
            // listeners while restoring saved state by calling setSelected
            // before re-binding, which triggers the listeners only for true
            // user-driven toggles.
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

            // Initial labels for the dialog's starting modality (refined by
            // updateLoopOrderToggle when the modality combo changes).
            updateLoopOrderToggle(modalityBox != null ? modalityBox.getValue() : null);

            VBox content = new VBox(8, enableCheck, grid, loopOrderRow);
            content.setPadding(new Insets(5));

            TitledPane pane = new TitledPane("Z-STACK OPTIONS", content);
            pane.setExpanded(false);
            pane.setAnimated(false);
            pane.setStyle("-fx-font-weight: bold;");
            return pane;
        }

        private TitledPane createTimeLapseSection() {
            timeLapseEnableCheck = new CheckBox("Enable time-lapse acquisition");
            timeLapseEnableCheck.setTooltip(
                    new Tooltip("Repeat the full acquisition over multiple timepoints at a fixed interval. "
                            + "The interval is the time between the start of consecutive timepoints."));
            timeLapseEnableCheck.setSelected(PersistentPreferences.isTimeLapseEnabled());

            timeLapseTimepointsSpinner = new Spinner<>(1, 10000, PersistentPreferences.getTimeLapseTimepoints(), 1);
            timeLapseTimepointsSpinner.setEditable(true);
            timeLapseTimepointsSpinner.setPrefWidth(100);
            timeLapseTimepointsSpinner.setTooltip(new Tooltip("Number of times the full acquisition is repeated."));

            timeLapseIntervalSpinner =
                    new Spinner<>(0.0, Double.MAX_VALUE, PersistentPreferences.getTimeLapseIntervalSec(), 10.0);
            timeLapseIntervalSpinner.setEditable(true);
            timeLapseIntervalSpinner.setPrefWidth(100);
            timeLapseIntervalSpinner.setTooltip(
                    new Tooltip("Seconds between the start of consecutive timepoints. If a timepoint takes "
                            + "longer than this, remaining timepoints start late and a warning is shown."));

            timeLapseTimepointsSpinner
                    .disableProperty()
                    .bind(timeLapseEnableCheck.selectedProperty().not());
            timeLapseIntervalSpinner
                    .disableProperty()
                    .bind(timeLapseEnableCheck.selectedProperty().not());

            timeLapseEnableCheck.selectedProperty().addListener((obs, o, n) -> {
                PersistentPreferences.setTimeLapseEnabled(n);
                updatePreviewPanel();
            });
            timeLapseTimepointsSpinner.valueProperty().addListener((obs, o, n) -> {
                if (n != null) {
                    PersistentPreferences.setTimeLapseTimepoints(n);
                    updatePreviewPanel();
                }
            });
            timeLapseIntervalSpinner.valueProperty().addListener((obs, o, n) -> {
                if (n != null) {
                    PersistentPreferences.setTimeLapseIntervalSec(n);
                    updatePreviewPanel();
                }
            });

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));
            grid.add(new Label("Timepoints:"), 0, 0);
            grid.add(timeLapseTimepointsSpinner, 1, 0);
            grid.add(new Label("Interval (s):"), 0, 1);
            grid.add(timeLapseIntervalSpinner, 1, 1);

            VBox content = new VBox(8, timeLapseEnableCheck, grid);
            content.setPadding(new Insets(5));

            TitledPane pane = new TitledPane("TIME-LAPSE OPTIONS", content);
            pane.setExpanded(false);
            pane.setAnimated(false);
            pane.setStyle("-fx-font-weight: bold;");
            return pane;
        }

        /**
         * Returns the {@link PersistentPreferences#LOOP_ORDER_FAMILY_WIDEFIELD}
         * / {@code LOOP_ORDER_FAMILY_PPM} key for the given modality string,
         * or {@code null} if the modality doesn't participate in the toggle.
         */
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

        /**
         * Update the loop-order radio labels, disable binding, and selection
         * for a new modality. Called once during section build and again from
         * {@link #updateModalityUI} whenever the modality combo flips.
         */
        private void updateLoopOrderToggle(String modality) {
            if (loopOrderInnerZRadio == null || loopOrderInnerAltRadio == null) {
                return; // Section not built yet.
            }
            String family = loopOrderFamily(modality);

            // Default radio binding: disabled when Z-stack is off; further
            // refined per family below. Bind off any previous binding first.
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
                // Modality doesn't participate in the toggle (BF, LSM, etc.)
                // -- hide the row so the dialog doesn't show a meaningless
                // control. Single-axis modalities have no inner-axis choice.
                loopOrderRow.setVisible(false);
                loopOrderRow.setManaged(false);
            }
        }

        private void createErrorSummaryPanel() {
            errorSummaryPanel = new VBox(5);
            errorSummaryPanel.setStyle("-fx-background-color: " + (isDark() ? "#4a3800" : "#fff3cd") + "; "
                    + "-fx-border-color: " + (isDark() ? "#806000" : "#ffc107") + "; "
                    + "-fx-border-width: 1px; "
                    + "-fx-padding: 10px;");
            errorSummaryPanel.setVisible(false);
            errorSummaryPanel.setManaged(false);

            Label errorTitle = new Label("Please fix the following errors:");
            errorTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: " + accentColor("amber") + ";");

            errorListBox = new VBox(3);
            errorSummaryPanel.getChildren().addAll(errorTitle, errorListBox);
        }

        private void initializeHardwareSelections() {
            // Initial modality from the central state so this dialog agrees
            // with the wizard / Camera tab / other open surfaces.
            var modalityState = qupath.ext.qpsc.state.ModalityState.getInstance();
            String fromState = modalityState.getModality();
            Set<String> modalities = configManager.getSection("modalities").keySet();

            if (fromState != null && !fromState.isEmpty() && modalities.contains(fromState)) {
                modalityBox.setValue(fromState);
            } else if (!modalities.isEmpty()) {
                modalityBox.setValue(modalities.iterator().next());
            }
            // Push every change to the central state.
            modalityBox.valueProperty().addListener((obs, oldV, newV) -> {
                if (newV != null) modalityState.setModality(newV);
            });
            // Mirror external changes (Camera tab, Wizard, etc.) back into the combo.
            javafx.beans.value.ChangeListener<String> stateListener = (obs, oldV, newV) -> {
                if (newV != null
                        && !newV.equals(modalityBox.getValue())
                        && modalityBox.getItems().contains(newV)) {
                    modalityBox.setValue(newV);
                }
            };
            modalityState.modalityProperty().addListener(stateListener);
            // Lifecycle: drop the listener when the dialog stage hides. The
            // stage is the modalityBox's containing window; resolve lazily so
            // we don't depend on construction order.
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
                    .map(id -> {
                        String name = objectiveNames.get(id);
                        return (name != null && !name.isEmpty()) ? (name + " (" + id + ")") : id;
                    })
                    .sorted()
                    .collect(Collectors.toList());

            objectiveBox.getItems().clear();
            objectiveBox.getItems().addAll(objectiveDisplayItems);

            // Try to restore last used objective via the shared state
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
                    .map(id -> {
                        String name = detectorNames.get(id);
                        return (name != null && !name.isEmpty()) ? (name + " (" + id + ")") : id;
                    })
                    .sorted()
                    .collect(Collectors.toList());

            detectorBox.getItems().clear();
            detectorBox.getItems().addAll(detectorDisplayItems);

            // Try to restore last used detector
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

        private void updateModalityUI(String modality) {
            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            Optional<ModalityHandler.BoundingBoxUI> uiOpt = handler.createBoundingBoxUI();

            // Clear and update only the modality content box, preserving white balance section
            modalityContentBox.getChildren().clear();

            if (uiOpt.isPresent()) {
                modalityUI = uiOpt.get();
                modalityContentBox.getChildren().add(modalityUI.getNode());
                // Wire the "Save as MicroManager MDA..." button on the modality
                // panel so LSM (and any bounding-box workflow) can export an
                // MDA file before running the QPSC acquisition.
                java.util.function.Supplier<MdaExportContext> supplier = this::buildMdaExportContext;
                if (modalityUI instanceof WidefieldChannelBoundingBoxUI wfUi) {
                    wfUi.installMdaExportContext(supplier);
                } else if (modalityUI instanceof PPMBoundingBoxUI ppmUi) {
                    ppmUi.installMdaExportContext(supplier);
                }
            } else {
                modalityUI = null;
                Label noOptions = new Label("No additional options for " + modality + " modality.");
                noOptions.setStyle("-fx-font-style: italic; -fx-opacity: 0.65;");
                modalityContentBox.getChildren().add(noOptions);
            }

            // Update white balance visibility based on current detector and modality
            updateWhiteBalanceVisibility();

            // Refresh the loop-order toggle: labels swap between widefield
            // and PPM, the disable binding picks up the new modalityUI's
            // hasMultipleChannelsSelected property (or hides for non-
            // participating modalities). The persisted pick for this family
            // is restored.
            updateLoopOrderToggle(modality);
        }

        /**
         * Builds an {@link MdaExportContext} snapshot from the dialog's current state
         * for the bounding-box workflow. Mirrors the per-annotation variant in
         * {@code ExistingImageAcquisitionController} but synthesizes a single
         * region named {@code "bounds"} from the start+size or two-corners inputs.
         * Bounding-box coordinates are already in stage micrometers, so no
         * alignment transform is applied to tile centroids.
         */
        private MdaExportContext buildMdaExportContext() {
            try {
                // When a project is already open, the OK path uses the open
                // project's folder name as the effective sample name (see
                // BoundedAcquisitionWorkflow's actualSampleName derivation);
                // mirror that here so MDA files land inside the open project,
                // not in a sibling folder named after whatever stale value
                // PersistentPreferences.getLastSampleName() returned.
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

                double[] bounds = parseBoundsForExport();
                if (bounds == null) {
                    return MdaExportContext.notReady(
                            "Fill in the bounding-box coordinates (positive width/height) before exporting MDA.");
                }
                double x1 = bounds[0], y1 = bounds[1], x2 = bounds[2], y2 = bounds[3];

                SampleSetupResult sample =
                        new SampleSetupResult(sampleName, projectsFolder, modality, objective, detector);

                String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configFileLocation);

                String enhancedModality = ObjectiveUtils.createEnhancedFolderName(modality, objective);
                java.nio.file.Path regionDir = projectsFolder
                        .toPath()
                        .resolve(sampleName)
                        .resolve(enhancedModality)
                        .resolve("bounds");

                Map<String, Double> angleOverrides = modalityUI != null ? modalityUI.getAngleOverrides() : null;
                String focusChannelId = modalityUI != null ? modalityUI.getFocusChannelId() : null;
                Map<String, Double> channelIntensityOverrides =
                        modalityUI != null ? modalityUI.getChannelIntensityOverrides() : Map.of();
                if (channelIntensityOverrides == null) {
                    channelIntensityOverrides = Map.of();
                }

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
                                "bounds",
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
                if (!channelIntensityOverrides.isEmpty()) {
                    cmdBuilder.channelIntensityOverrides(channelIntensityOverrides);
                }
                cmdBuilder.focusChannel(focusChannelId);
                ModalityRegistry.getHandler(modality).configureCommandBuilder(cmdBuilder);

                double[] fov = mgr.getModalityFOV(modality, objective, detector);
                if (fov == null || fov.length < 2) {
                    return MdaExportContext.notReady(
                            "Could not resolve camera FOV for the selected hardware combination.");
                }
                double overlap = QPPreferenceDialog.getTileOverlapPercentProperty();
                List<TileStagePos> tiles = synthesizeBoundsCentroids(x1, y1, x2, y2, fov[0], fov[1], overlap);
                if (tiles.isEmpty()) {
                    return MdaExportContext.notReady(
                            "Bounding box produced zero tiles -- check width/height and overlap.");
                }

                List<MdaExportAction.RegionPlan> regionPlans =
                        List.of(new MdaExportAction.RegionPlan("bounds", regionDir, tiles));

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
         * Parses the bounding-box inputs (handles both start+size and two-corners
         * modes) and returns {@code [x1, y1, x2, y2]} in stage micrometers, or
         * {@code null} if any field is empty / unparseable / yields non-positive
         * extent. Mirrors {@code updatePreviewPanel}'s parse so MDA export uses
         * the same geometry the preview shows.
         */
        private double[] parseBoundsForExport() {
            try {
                String startXStr = startXField != null ? startXField.getText().trim() : "";
                String startYStr = startYField != null ? startYField.getText().trim() : "";
                if (startXStr.isEmpty() || startYStr.isEmpty()) {
                    return null;
                }
                double startX = Double.parseDouble(startXStr);
                double startY = Double.parseDouble(startYStr);

                double x1, y1, x2, y2;
                if (startSizeMode != null && startSizeMode.isSelected()) {
                    String widthStr = widthField != null ? widthField.getText().trim() : "";
                    String heightStr =
                            heightField != null ? heightField.getText().trim() : "";
                    if (widthStr.isEmpty() || heightStr.isEmpty()) {
                        return null;
                    }
                    double width = Double.parseDouble(widthStr);
                    double height = Double.parseDouble(heightStr);
                    if (width <= 0 || height <= 0) return null;
                    x1 = startX - width / 2.0;
                    y1 = startY - height / 2.0;
                    x2 = startX + width / 2.0;
                    y2 = startY + height / 2.0;
                } else {
                    String endXStr = endXField != null ? endXField.getText().trim() : "";
                    String endYStr = endYField != null ? endYField.getText().trim() : "";
                    if (endXStr.isEmpty() || endYStr.isEmpty()) return null;
                    double endX = Double.parseDouble(endXStr);
                    double endY = Double.parseDouble(endYStr);
                    x1 = Math.min(startX, endX);
                    y1 = Math.min(startY, endY);
                    x2 = Math.max(startX, endX);
                    y2 = Math.max(startY, endY);
                    if ((x2 - x1) <= 0 || (y2 - y1) <= 0) return null;
                }
                return new double[] {x1, y1, x2, y2};
            } catch (NumberFormatException nfe) {
                return null;
            }
        }

        /**
         * Generates tile centroids in stage micrometers for a bounding box,
         * mirroring {@code TilingUtilities.processBoundingBoxTilingRequest}'s
         * half-frame buffer on each side. No alignment transform is applied --
         * inputs are already in stage coordinates.
         */
        private static List<TileStagePos> synthesizeBoundsCentroids(
                double x1,
                double y1,
                double x2,
                double y2,
                double frameWidthUm,
                double frameHeightUm,
                double overlapPercent) {
            double strideX = frameWidthUm * (1.0 - overlapPercent / 100.0);
            double strideY = frameHeightUm * (1.0 - overlapPercent / 100.0);
            if (strideX <= 0 || strideY <= 0) {
                return List.of();
            }
            double minX = x1 - frameWidthUm / 2.0;
            double minY = y1 - frameHeightUm / 2.0;
            double maxX = x2 + frameWidthUm / 2.0;
            double maxY = y2 + frameHeightUm / 2.0;
            int cols = (int) Math.ceil((maxX - minX) / strideX);
            int rows = (int) Math.ceil((maxY - minY) / strideY);
            if (cols < 1) cols = 1;
            if (rows < 1) rows = 1;
            List<TileStagePos> out = new ArrayList<>(cols * rows);
            int idx = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    double cx = minX + c * strideX + frameWidthUm / 2.0;
                    double cy = minY + r * strideY + frameHeightUm / 2.0;
                    out.add(new TileStagePos(String.valueOf(idx++), cx, cy, 0.0));
                }
            }
            return out;
        }

        /**
         * Updates the "Default (from config)" item's label to show which
         * strategy the v2 YAML binding picks for the current modality. The
         * item still maps to {@code null} so the CLI emits no --af-strategy
         * flag; the label is a hint to the user, nothing more. Called when
         * the modality dropdown changes.
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
         * Updates the visibility of the white balance section based on current detector and modality.
         * White balance section is only shown for JAI cameras.
         * Per-angle checkbox is only shown for PPM modality.
         */
        private void updateWhiteBalanceVisibility() {
            String detectorDisplay = detectorBox.getValue();
            String detector = detectorDisplay != null ? extractIdFromDisplayString(detectorDisplay) : null;
            boolean isJAI = configManager.isJAICamera(detector);

            String modality = modalityBox.getValue();
            var handler = modality != null ? ModalityRegistry.getHandler(modality) : null;
            String defaultWb = handler != null ? handler.getDefaultWbMode() : "off";
            boolean modalityUsesWb = !"off".equals(defaultWb);

            // JAI cameras use the WB mode selector (which also controls bg
            // correction mode). Monochrome detectors use the standalone
            // "Use background correction" checkbox instead.
            // Hide WB entirely for modalities that don't use it (e.g. Fluorescence).
            boolean showWb = isJAI && modalityUsesWb;
            if (wbModeComboBox != null) {
                wbModeComboBox.setVisible(showWb);
                wbModeComboBox.setManaged(showWb);
            }
            if (wbModeLabel != null) {
                wbModeLabel.setVisible(showWb);
                wbModeLabel.setManaged(showWb);
            }
            if (monoBgCorrectionCheck != null) {
                monoBgCorrectionCheck.setVisible(!isJAI);
                monoBgCorrectionCheck.setManaged(!isJAI);
            }

            // Reset WB default from handler when modality changes
            if (showWb && wbModeComboBox != null) {
                String displayDefault = getDefaultWbModeFromModality();
                wbModeComboBox.setValue(displayDefault);
            }

            boolean isMultiAngle = handler != null && handler.isMultiAngleModality();

            // Filter WB modes based on background validity (JAI only)
            if (showWb && wbModeComboBox != null && modality != null && detector != null) {
                filterWbModesByBackgroundValidity(modality, detector, isMultiAngle);
            }

            logger.debug("WB/BG visibility updated: JAI={}, modalityWB={}, visible={}", isJAI, modalityUsesWb, showWb);
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
                // Only show modes with valid backgrounds
                String baseFolder = configManager.getBackgroundCorrectionFolder(modality);
                if (baseFolder != null) {
                    List<WbMode> validModes = BackgroundValidityChecker.getValidModes(
                            baseFolder, modality, objective, detector, configManager);
                    for (WbMode mode : validModes) {
                        if (isPPM || !isPpmOnlyMode(mode)) {
                            wbModeComboBox.getItems().add(mode.getDisplayName());
                        }
                    }
                    logger.debug("Filtered WB modes to {} valid options (BG correction enabled)", validModes.size());
                } else {
                    // No background folder configured -- show all modes
                    for (WbMode mode : WbMode.values()) {
                        if (isPPM || !isPpmOnlyMode(mode)) {
                            wbModeComboBox.getItems().add(mode.getDisplayName());
                        }
                    }
                }
            } else {
                // Background correction disabled -- show all modes
                for (WbMode mode : WbMode.values()) {
                    if (isPPM || !isPpmOnlyMode(mode)) {
                        wbModeComboBox.getItems().add(mode.getDisplayName());
                    }
                }
            }

            // Restore previous selection or default
            if (wbModeComboBox.getItems().contains(currentSelection)) {
                wbModeComboBox.setValue(currentSelection);
            } else if (!wbModeComboBox.getItems().isEmpty()) {
                wbModeComboBox.setValue(wbModeComboBox.getItems().get(0));
            }
        }

        private static boolean isPpmOnlyMode(WbMode mode) {
            return mode == WbMode.SIMPLE || mode == WbMode.PER_ANGLE;
        }

        private void setupPreviewUpdateListeners() {
            previewDebounce.setOnFinished(event -> updatePreviewPanel());
        }

        private void triggerPreviewUpdate() {
            previewDebounce.playFromStart();
        }

        private void updatePreviewPanel() {
            try {
                boolean isStartSizeMode = startSizeMode.isSelected();
                double startX, startY, width, height;

                String startXStr = startXField.getText().trim();
                String startYStr = startYField.getText().trim();

                if (startXStr.isEmpty() || startYStr.isEmpty()) {
                    showPreviewPlaceholder("Enter all coordinates to see preview");
                    return;
                }

                startX = Double.parseDouble(startXStr);
                startY = Double.parseDouble(startYStr);

                if (isStartSizeMode) {
                    // Start + Size mode
                    String widthStr = widthField.getText().trim();
                    String heightStr = heightField.getText().trim();

                    if (widthStr.isEmpty() || heightStr.isEmpty()) {
                        showPreviewPlaceholder("Enter all coordinates to see preview");
                        return;
                    }

                    width = Double.parseDouble(widthStr);
                    height = Double.parseDouble(heightStr);
                } else {
                    // Two Corners mode
                    String endXStr = endXField.getText().trim();
                    String endYStr = endYField.getText().trim();

                    if (endXStr.isEmpty() || endYStr.isEmpty()) {
                        showPreviewPlaceholder("Enter all coordinates to see preview");
                        return;
                    }

                    double endX = Double.parseDouble(endXStr);
                    double endY = Double.parseDouble(endYStr);

                    // Calculate width and height from corners (use absolute values)
                    width = Math.abs(endX - startX);
                    height = Math.abs(endY - startY);
                }

                if (width <= 0 || height <= 0) {
                    showPreviewPlaceholder("Region must have positive width and height");
                    return;
                }

                // Get hardware selections
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                if (modality == null || objective == null || detector == null) {
                    showPreviewPlaceholder("Select hardware configuration to see preview");
                    return;
                }

                // Calculate FOV
                double[] fov = configManager.getModalityFOV(modality, objective, detector);
                if (fov == null) {
                    showPreviewPlaceholder("Could not calculate FOV for selected hardware");
                    return;
                }

                double frameWidth = fov[0];
                double frameHeight = fov[1];

                // Calculate tile grid
                // Match the actual tiling logic in TilingUtilities.processBoundingBoxTilingRequest():
                // The tiling expands bounds by half a frame on each side to ensure full coverage,
                // so total tiling area = annotation + one full frame in each dimension.
                double paddedWidth = width + frameWidth;
                double paddedHeight = height + frameHeight;

                double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                double stepX = frameWidth * (1.0 - overlapPercent / 100.0);
                double stepY = frameHeight * (1.0 - overlapPercent / 100.0);

                int tilesX = (int) Math.ceil(paddedWidth / stepX);
                int tilesY = (int) Math.ceil(paddedHeight / stepY);
                int totalTiles = tilesX * tilesY;

                // Get angle count from modality handler for preview estimation
                int angleCount = qupath.ext.qpsc.modality.ModalityRegistry.getHandler(modality)
                        .getDefaultAngleCount();

                // Z-stack multiplier
                int zPlanes = 1;
                if (PersistentPreferences.isZStackEnabled()) {
                    double zRange = PersistentPreferences.getZStackRange();
                    double zStep = PersistentPreferences.getZStackStep();
                    if (zStep > 0) {
                        zPlanes = (int) Math.ceil(zRange / zStep) + 1;
                    }
                }

                int totalImages = totalTiles * angleCount * zPlanes;

                // Time-lapse multiplier: repeat the full acquisition over N timepoints.
                int timepoints = 1;
                double intervalSec = 0.0;
                if (PersistentPreferences.isTimeLapseEnabled()) {
                    timepoints = Math.max(1, PersistentPreferences.getTimeLapseTimepoints());
                    intervalSec = Math.max(0.0, PersistentPreferences.getTimeLapseIntervalSec());
                }

                // Estimate time: ~2s per image for capture+move, plus ~10s per AF tile
                double perTimepointAcqSeconds = totalImages * 2.0;
                totalImages *= timepoints;
                // If the interval exceeds per-timepoint acquisition the run is interval-bound:
                // (N-1) intervals to start the last timepoint, plus that timepoint's acquisition.
                double estimatedSeconds = timepoints > 1
                        ? Math.max(totalImages * 2.0, (timepoints - 1) * intervalSec + perTimepointAcqSeconds)
                        : perTimepointAcqSeconds;
                String timeEstimate = formatTime(estimatedSeconds);

                // Estimate storage (rough: 4MB per image for 16-bit 2048x2048)
                double estimatedMB = totalImages * 4.0;
                String storageEstimate = formatStorage(estimatedMB);

                // Update calculated bounds
                double x1, y1, x2, y2;
                if (isStartSizeMode) {
                    // Center point + size: startX/Y is the center of the region
                    x1 = startX - width / 2.0;
                    y1 = startY - height / 2.0;
                    x2 = startX + width / 2.0;
                    y2 = startY + height / 2.0;
                } else {
                    // Two corners mode - ensure proper min/max ordering
                    double endX = Double.parseDouble(endXField.getText().trim());
                    double endY = Double.parseDouble(endYField.getText().trim());
                    x1 = Math.min(startX, endX);
                    y1 = Math.min(startY, endY);
                    x2 = Math.max(startX, endX);
                    y2 = Math.max(startY, endY);
                }
                calculatedBoundsLabel.setText(String.format(
                        "Bounds: (%.1f, %.1f) to (%.1f, %.1f)  [center: %.1f, %.1f]",
                        x1, y1, x2, y2, (x1 + x2) / 2.0, (y1 + y2) / 2.0));
                calculatedBoundsLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + accentColor("green") + ";");

                StageMapWindow.setBoundingBoxPreview(x1, y1, x2, y2);

                // Update preview labels
                previewRegionLabel.setText(String.format("Region: %.2f x %.2f mm", width / 1000.0, height / 1000.0));
                previewFOVLabel.setText(
                        String.format("Field of View: %.0f x %.0f um (%s)", frameWidth, frameHeight, objective));
                previewTileGridLabel.setText(String.format(
                        "Tile Grid: %d x %d = %d tiles (%.0f%% overlap)", tilesX, tilesY, totalTiles, overlapPercent));
                String anglesText = String.format("Angles: %d (%s modality)", angleCount, modality);
                if (zPlanes > 1) {
                    anglesText += String.format(", Z-planes: %d", zPlanes);
                }
                if (timepoints > 1) {
                    anglesText += String.format(", Timepoints: %d", timepoints);
                }
                previewAnglesLabel.setText(anglesText);
                previewTotalImagesLabel.setText(String.format("Total Images: %,d", totalImages));
                previewTimeLabel.setText("Est. Time: " + timeEstimate);
                previewCompletionLabel.setText("Est. Completion: " + formatCompletion(estimatedSeconds));
                previewStorageLabel.setText("Est. Storage: " + storageEstimate);

                previewErrorLabel.setVisible(false);

            } catch (NumberFormatException e) {
                showPreviewPlaceholder("Invalid number format in coordinates");
            } catch (Exception e) {
                logger.debug("Preview update error: {}", e.getMessage());
                showPreviewPlaceholder("Could not calculate preview");
            }
        }

        private void showPreviewPlaceholder(String message) {
            previewRegionLabel.setText("Region: --");
            previewFOVLabel.setText("Field of View: --");
            previewTileGridLabel.setText("Tile Grid: --");
            previewAnglesLabel.setText("Angles: --");
            previewTotalImagesLabel.setText("Total Images: --");
            previewTimeLabel.setText("Est. Time: --");
            previewCompletionLabel.setText("Est. Completion: --");
            previewStorageLabel.setText("Est. Storage: --");
            previewErrorLabel.setText(message);
            previewErrorLabel.setVisible(true);
            StageMapWindow.clearBoundingBoxPreview();
        }

        private String formatTime(double seconds) {
            if (seconds < 60) {
                return String.format("%.0f seconds", seconds);
            } else if (seconds < 3600) {
                return String.format("%.1f minutes", seconds / 60.0);
            } else {
                return String.format("%.1f hours", seconds / 3600.0);
            }
        }

        private String formatCompletion(double secondsFromNow) {
            java.time.LocalDateTime done = java.time.LocalDateTime.now().plusSeconds((long) secondsFromNow);
            return done.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, HH:mm"));
        }

        private String formatStorage(double megabytes) {
            if (megabytes < 1024) {
                return String.format("%.0f MB", megabytes);
            } else {
                return String.format("%.1f GB", megabytes / 1024.0);
            }
        }

        private void validateRegion() {
            try {
                StringBuilder errors = new StringBuilder();
                boolean isStartSizeMode = startSizeMode.isSelected();

                String startXStr = startXField.getText().trim();
                String startYStr = startYField.getText().trim();

                if (startXStr.isEmpty()) errors.append("Start/Corner 1 X is required. ");
                if (startYStr.isEmpty()) errors.append("Start/Corner 1 Y is required. ");

                if (isStartSizeMode) {
                    // Start + Size mode validation
                    String widthStr = widthField.getText().trim();
                    String heightStr = heightField.getText().trim();

                    if (widthStr.isEmpty()) errors.append("Width is required. ");
                    if (heightStr.isEmpty()) errors.append("Height is required. ");

                    if (errors.length() == 0) {
                        double width = Double.parseDouble(widthStr);
                        double height = Double.parseDouble(heightStr);
                        if (width <= 0) errors.append("Width must be positive. ");
                        if (height <= 0) errors.append("Height must be positive. ");
                        Double.parseDouble(startXStr);
                        Double.parseDouble(startYStr);
                    }
                } else {
                    // Two Corners mode validation
                    String endXStr = endXField.getText().trim();
                    String endYStr = endYField.getText().trim();

                    if (endXStr.isEmpty()) errors.append("Corner 2 X is required. ");
                    if (endYStr.isEmpty()) errors.append("Corner 2 Y is required. ");

                    if (errors.length() == 0) {
                        double x1 = Double.parseDouble(startXStr);
                        double y1 = Double.parseDouble(startYStr);
                        double x2 = Double.parseDouble(endXStr);
                        double y2 = Double.parseDouble(endYStr);
                        if (x1 == x2 && y1 == y2) {
                            errors.append("The two corners must be different points. ");
                        }
                    }
                }

                if (errors.length() > 0) {
                    validationErrors.put("region", errors.toString().trim());
                } else {
                    validationErrors.remove("region");
                }

            } catch (NumberFormatException e) {
                validationErrors.put("region", "Invalid number format in coordinates");
            }

            updateErrorSummary();
        }

        private void validateAll() {
            // Validate sample name
            String sampleName = sampleNameField.getText().trim();
            String sampleError = SampleNameValidator.getValidationError(sampleName);
            if (sampleError != null) {
                validationErrors.put("sampleName", sampleError);
            } else {
                validationErrors.remove("sampleName");
            }

            // Validate hardware
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

            // Validate region
            validateRegion();

            updateErrorSummary();
        }

        private void updateErrorSummary() {
            if (validationErrors.isEmpty()) {
                errorSummaryPanel.setVisible(false);
                errorSummaryPanel.setManaged(false);
                okButton.setDisable(false);
            } else {
                errorListBox.getChildren().clear();
                validationErrors.forEach((fieldId, errorMsg) -> {
                    Label errorLabel = new Label("- " + errorMsg);
                    errorLabel.setStyle("-fx-text-fill: " + accentColor("amber") + ";");
                    errorListBox.getChildren().add(errorLabel);
                });

                errorSummaryPanel.setVisible(true);
                errorSummaryPanel.setManaged(true);
                okButton.setDisable(true);
            }
        }

        private UnifiedAcquisitionResult createResult() {
            try {
                String sampleName = sampleNameField.getText().trim();
                File projectsFolder = new File(folderField.getText().trim());
                String modality = modalityBox.getValue();
                String objective = extractIdFromDisplayString(objectiveBox.getValue());
                String detector = extractIdFromDisplayString(detectorBox.getValue());

                double x1, y1, x2, y2;
                boolean isStartSizeMode = startSizeMode.isSelected();

                double startX = Double.parseDouble(startXField.getText().trim());
                double startY = Double.parseDouble(startYField.getText().trim());

                if (isStartSizeMode) {
                    double width = Double.parseDouble(widthField.getText().trim());
                    double height = Double.parseDouble(heightField.getText().trim());
                    // Center point + size: startX/Y is the center of the region
                    x1 = startX - width / 2.0;
                    y1 = startY - height / 2.0;
                    x2 = startX + width / 2.0;
                    y2 = startY + height / 2.0;
                } else {
                    double endX = Double.parseDouble(endXField.getText().trim());
                    double endY = Double.parseDouble(endYField.getText().trim());
                    // Ensure proper min/max ordering
                    x1 = Math.min(startX, endX);
                    y1 = Math.min(startY, endY);
                    x2 = Math.max(startX, endX);
                    y2 = Math.max(startY, endY);
                }

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
                            "Unified acquisition objective drift: dialog combo='{}' but "
                                    + "ObjectiveState='{}'. Using dialog value.",
                            objective,
                            globalObjective);
                }

                // Save preferences. Modality + objective go through their
                // central state singletons so cross-dialog sync stays canonical.
                PersistentPreferences.setLastSampleName(sampleName);
                qupath.ext.qpsc.state.ModalityState.getInstance().setModality(modality);
                qupath.ext.qpsc.state.ObjectiveState.getInstance().setObjective(objective);
                PersistentPreferences.setLastDetector(detector);
                PersistentPreferences.setBoundingBoxString(String.format("%.2f,%.2f,%.2f,%.2f", x1, y1, x2, y2));

                // Autofocus strategy override: display -> protocol name.
                // Default (null) means "use YAML per-modality binding" and
                // produces no --af-strategy CLI flag.
                String afStrategyProtocol = AfStrategyChoice.displayToProtocol(
                        afStrategyCombo != null ? afStrategyCombo.getValue() : AfStrategyChoice.DEFAULT_DISPLAY);
                PersistentPreferences.setLastAfStrategy(afStrategyProtocol);
                if (afStrategyProtocol != null) {
                    logger.info("User selected AF strategy override: {}", afStrategyProtocol);
                }

                // AF method benchmark checkbox (Advanced panel). Diagnostic
                // mode -- not persisted, defaults off every time the dialog opens.
                boolean afBenchmark = afBenchmarkCheck != null && afBenchmarkCheck.isSelected();
                if (afBenchmark) {
                    logger.info("AF method benchmark enabled -- every tile will time sweep + streaming AF");
                }

                // Get angle / channel overrides if available
                Map<String, Double> angleOverrides = null;
                Map<String, Double> channelIntensityOverrides = Map.of();
                String focusChannelId = null;
                Set<String> splitChannelIds = Set.of();
                if (modalityUI != null) {
                    angleOverrides = modalityUI.getAngleOverrides();
                    if (angleOverrides != null) {
                        logger.info("User specified angle overrides: {}", angleOverrides);
                    }
                    channelIntensityOverrides = modalityUI.getChannelIntensityOverrides();
                    if (channelIntensityOverrides == null) {
                        channelIntensityOverrides = Map.of();
                    }
                    if (!channelIntensityOverrides.isEmpty()) {
                        logger.info("User specified channel intensity overrides: {}", channelIntensityOverrides);
                    }
                    focusChannelId = modalityUI.getFocusChannelId();
                    if (focusChannelId != null) {
                        logger.info("User specified focus channel: {}", focusChannelId);
                    }
                    splitChannelIds = modalityUI.getSplitChannelIds();
                    if (splitChannelIds == null) {
                        splitChannelIds = Set.of();
                    }
                    if (!splitChannelIds.isEmpty()) {
                        logger.info("User chose to split channels into separate files: {}", splitChannelIds);
                    }
                }

                OutputFormat stitchingOrganization = outputOrganizationCombo != null
                        ? organizationFromDisplay(outputOrganizationCombo.getValue())
                        : OutputFormat.OME_SINGLE;

                // Get white balance settings from ComboBox
                String wbModeDisplay = wbModeComboBox != null ? wbModeComboBox.getValue() : "Per-angle (PPM)";
                String wbMode = WbMode.fromDisplayName(wbModeDisplay).getProtocolName();
                boolean enableWhiteBalance = !"off".equals(wbMode);
                boolean perAngleWhiteBalance = "per_angle".equals(wbMode);

                logger.info(
                        "Created unified acquisition result: sample={}, modality={}, "
                                + "objective={}, detector={}, bounds=({},{}) to ({},{}), "
                                + "wbMode={}, enableWB={}, perAngleWB={}",
                        sampleName,
                        modality,
                        objective,
                        detector,
                        x1,
                        y1,
                        x2,
                        y2,
                        wbMode,
                        enableWhiteBalance,
                        perAngleWhiteBalance);

                // Resolve the inner-axis flag from the loop-order toggle.
                // Null = omit the flag and let the server pick its per-modality
                // default. The toggle is hidden / disabled for modalities that
                // don't participate (and when Z-stack is off or <2 channels);
                // in that case we still return null so the wire format stays
                // byte-identical to pre-toggle builds.
                String innerAxis = null;
                String loopOrderFamily = loopOrderFamily(modality);
                if (loopOrderFamily != null
                        && loopOrderInnerAltRadio != null
                        && !loopOrderInnerAltRadio.isDisabled()
                        && loopOrderInnerAltRadio.isSelected()) {
                    if (PersistentPreferences.LOOP_ORDER_FAMILY_WIDEFIELD.equals(loopOrderFamily)) {
                        innerAxis = "channel";
                    } else if (PersistentPreferences.LOOP_ORDER_FAMILY_PPM.equals(loopOrderFamily)) {
                        innerAxis = "z";
                    }
                }

                return new UnifiedAcquisitionResult(
                        sampleName,
                        projectsFolder,
                        modality,
                        objective,
                        detector,
                        x1,
                        y1,
                        x2,
                        y2,
                        angleOverrides,
                        channelIntensityOverrides,
                        focusChannelId,
                        afStrategyProtocol,
                        enableWhiteBalance,
                        perAngleWhiteBalance,
                        wbMode,
                        innerAxis,
                        afBenchmark,
                        splitChannelIds,
                        stitchingOrganization);

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
