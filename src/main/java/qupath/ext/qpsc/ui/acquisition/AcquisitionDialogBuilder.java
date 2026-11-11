package qupath.ext.qpsc.ui.acquisition;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.ColorScheme;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.acquisition.AcquisitionConfigurationBuilder;
import qupath.ext.qpsc.background.BackgroundValidityChecker;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.modality.AcquisitionReadout;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ChannelExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.modality.WbMode;
import qupath.ext.qpsc.modality.ppm.ui.PPMBoundingBoxUI;
import qupath.ext.qpsc.modality.ui.BrightfieldExposureBoundingBoxUI;
import qupath.ext.qpsc.modality.widefield.ui.WidefieldChannelBoundingBoxUI;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.project.SampleNameValidator;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.AngleResolutionService;
import qupath.ext.qpsc.service.ChannelResolutionService;
import qupath.ext.qpsc.service.OutputFormat;
import qupath.ext.qpsc.service.mda.MdaExportContext;
import qupath.ext.qpsc.state.ModalityState;
import qupath.ext.qpsc.state.ObjectiveState;
import qupath.ext.qpsc.ui.ThemeColors;
import qupath.ext.qpsc.ui.background.BackgroundProfileSelector;
import qupath.ext.qpsc.ui.background.FlatfieldMethodSelector;
import qupath.ext.qpsc.ui.focus.AfStrategyChoice;
import qupath.ext.qpsc.ui.focus.EdfSettingsDialog;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.lib.gui.prefs.QuPathStyleManager;

/**
 * The parts shared by the two acquisition dialogs: the bounded acquisition dialog in
 * {@link UnifiedAcquisitionController} and the existing-image dialog in
 * {@link ExistingImageAcquisitionController}.
 *
 * <p>This class holds the project, hardware, white balance, autofocus, Z-stack, preview and
 * validation code. Each subclass builds the rest of its dialog in {@code buildAndShow} and states
 * what differs through the protected methods here. Nothing here calls an overridable method from
 * the constructor, so an override may use its subclass's fields.
 *
 * @param <R> the result the dialog returns
 */
abstract class AcquisitionDialogBuilder<R> {
    private static final Logger logger = LoggerFactory.getLogger(AcquisitionDialogBuilder.class);

    /** Debounce delay for preview updates in milliseconds. */
    private static final long PREVIEW_DEBOUNCE_MS = 300;

    /** Stitched-output organization dropdown labels and persistence key. */
    private static final String ORG_SINGLE = "Single combined file";

    private static final String ORG_PER_CHANNEL = "Separate file per channel";
    private static final String PREF_STITCH_ORGANIZATION = "qpscStitchOrganization";

    /** Shown when {@link #buildMdaCommand} finds no channel selected. */
    protected static final String MDA_NO_CHANNELS_MESSAGE =
            "No fluorescence channels selected. Enable at least one channel before exporting MDA.";

    protected final MicroscopeConfigManager configManager;

    // Project QuPath has open, set by the subclass.
    protected boolean hasOpenProject;
    protected String existingProjectName;
    protected File existingProjectFolder;

    // Project section
    protected TextField sampleNameField;
    private Label sampleNameErrorLabel;
    protected TextField folderField;

    // Hardware section
    protected ComboBox<String> modalityBox;
    protected ComboBox<String> objectiveBox;
    protected ComboBox<String> detectorBox;

    // White balance and background correction. The WB row is hidden for non-JAI detectors and
    // for modalities that do not use white balance.
    private Label wbLabel;
    protected ComboBox<String> wbModeComboBox;
    private BackgroundProfileSelector backgroundProfileSelector;
    private FlatfieldMethodSelector flatfieldSelector;

    // Modality panel. The subclass creates modalityContent in its advanced section.
    protected ModalityHandler.BoundingBoxUI modalityUI;
    protected VBox modalityContent;

    // Autofocus section
    private ComboBox<String> afStrategyCombo;

    // Z-stack section, including the loop-order toggle
    private CheckBox zStackEnableCheck;
    private CheckBox timeLapseEnableCheck;
    private Spinner<Integer> timeLapseTimepointsSpinner;
    private Spinner<Double> timeLapseIntervalSpinner;
    private ComboBox<String> timeLapseProjectionCombo;
    private ComboBox<String> outputOrganizationCombo;
    private RadioButton loopOrderInnerZRadio;
    private RadioButton loopOrderInnerAltRadio;
    private Label loopOrderLabel;
    private VBox loopOrderRow;

    // Preview section
    protected Label previewTimeLabel;
    protected Label previewStorageLabel;

    // Validation
    protected VBox errorSummaryPanel;
    private VBox errorListBox;
    protected final Map<String, String> validationErrors = new LinkedHashMap<>();

    // TitledPanes
    protected TitledPane projectPane;
    protected TitledPane hardwarePane;
    protected TitledPane advancedPane;
    protected TitledPane autofocusPane;

    // Debounce timer for preview updates
    private final PauseTransition previewDebounce = new PauseTransition(Duration.millis(PREVIEW_DEBOUNCE_MS));

    // Dialog and its "Start Acquisition" button
    protected Dialog<R> dialog;
    protected Button startButton;

    /** The operator's choices on the modality panel, with nulls replaced by empty collections. */
    protected record ModalityPanelSelections(
            Map<String, Double> angleOverrides,
            Map<String, Double> channelIntensityOverrides,
            String focusChannelId,
            ModalityHandler.BoundingBoxUI.FocusChannelOverride dedicatedFocusChannel,
            Set<String> splitChannelIds,
            List<String> alignmentChannelIds) {}

    protected AcquisitionDialogBuilder() {
        this.configManager = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
    }

    /**
     * Extracts the ID from a display string such as
     * "20x Olympus (LOCI_OBJECTIVE_OLYMPUS_20X_POL_001)".
     *
     * @return the text in the last pair of parentheses, the string itself when it has none, or
     *     null for null
     */
    static String extractIdFromDisplayString(String displayString) {
        if (displayString == null) return null;

        int openParen = displayString.lastIndexOf('(');
        int closeParen = displayString.lastIndexOf(')');

        if (openParen != -1 && closeParen != -1 && closeParen > openParen) {
            return displayString.substring(openParen + 1, closeParen);
        }

        return displayString;
    }

    // ==================== What differs between the dialogs ====================

    /** Text the sample name field starts with. */
    protected abstract String initialSampleName();

    /** Called after each edit of the sample name, once the error summary is up to date. */
    protected void afterSampleNameEdited() {}

    /** Adds the rows about the project to the project section, starting at {@code row}. */
    protected abstract void addProjectRows(GridPane grid, int row);

    /** Adds rows after the flat-field selector in the hardware section, starting at {@code row}. */
    protected void addHardwareRows(GridPane grid, int row) {}

    /** Called when the modality panel has been replaced: true if it has options to show. */
    protected void onModalityOptionsChanged(boolean hasOptions) {}

    /**
     * Largest value the Z range spinner accepts, in um: the stage's Z travel from
     * {@code stage.limits.z_um} in the microscope config, since a stack can span no more.
     */
    protected double zRangeMaxUm() {
        return zRangeMaxUm(configManager);
    }

    /** Z travel from the config, or {@link #Z_RANGE_MAX_UNCONFIGURED_UM} when the limits are missing. */
    public static double zRangeMaxUm(MicroscopeConfigManager mgr) {
        Double travel = mgr != null ? mgr.getStageZTravelUm() : null;
        if (travel == null || travel <= 0) {
            logger.warn(
                    "stage.limits.z_um is missing from the microscope config; Z range limited to {} um",
                    Z_RANGE_MAX_UNCONFIGURED_UM);
            return Z_RANGE_MAX_UNCONFIGURED_UM;
        }
        return travel;
    }

    /** Z range maximum when the config has no Z limits (the config check normally refuses such a config). */
    static final double Z_RANGE_MAX_UNCONFIGURED_UM = 2000.0;

    /** Nodes of the preview section, top to bottom. */
    protected abstract Node[] previewContentNodes();

    /** Recomputes the preview. Runs when the debounce timer ends. */
    protected abstract void updatePreviewPanel();

    /** Checks the fields only this dialog has. Runs after the hardware checks. */
    protected abstract void validateWorkflowFields();

    /**
     * Builds the snapshot of the dialog that an MDA export needs.
     *
     * @return the context, or {@link MdaExportContext#notReady(String)} when a precondition fails
     * @throws IOException caught by {@link #buildMdaExportContext}, which reports it to the operator
     */
    protected abstract MdaExportContext prepareMdaExport() throws IOException;

    // ==================== Theme ====================

    /** Returns true when QuPath is using a dark color scheme. */
    protected boolean isDark() {
        return QuPathStyleManager.getStyleColorScheme() == ColorScheme.DARK;
    }

    /** Secondary or description text style that works in both light and dark themes. */
    protected String subtleTextStyle() {
        return "-fx-font-size: 11px; -fx-opacity: 0.65;";
    }

    /** Accent text color for "green", "blue", "amber" or "red" that reads well in both themes. */
    protected String accentColor(String semanticColor) {
        boolean dark = isDark();
        switch (semanticColor) {
            case "green":
                return dark ? "#66BB6A" : "#2E7D32";
            case "blue":
                return dark ? "#64B5F6" : "#1565C0";
            case "amber":
                return dark ? "#FFD54F" : "#856404";
            case "red":
                return dark ? "#EF5350" : "#C62828";
            default:
                return dark ? "#B0BEC5" : "#424242";
        }
    }

    // ==================== Project section ====================

    /** Builds the project and sample section; {@link #addProjectRows} supplies the project rows. */
    protected void createProjectSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        // Sample name field
        sampleNameField = new TextField(initialSampleName());
        sampleNameField.setPromptText("e.g., MySample01");
        sampleNameField.setPrefWidth(300);

        sampleNameErrorLabel = new Label();
        sampleNameErrorLabel.setStyle("-fx-text-fill: " + ThemeColors.WARNING + "; -fx-font-size: 10px;");
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
            afterSampleNameEdited();
        });

        int row = 0;
        grid.add(new Label("Sample Name:"), 0, row);
        grid.add(sampleNameField, 1, row);
        row++;
        grid.add(new Label(""), 0, row);
        grid.add(sampleNameErrorLabel, 1, row);
        row++;

        addProjectRows(grid, row);

        projectPane = new TitledPane("PROJECT & SAMPLE", grid);
        projectPane.setExpanded(true);
        projectPane.setStyle("-fx-font-weight: bold;");
    }

    /** Creates {@link #folderField} from the saved projects folder, validated on every edit. */
    protected void createProjectsFolderField() {
        folderField = new TextField(QPPreferenceDialog.getProjectsFolderProperty());
        folderField.setPrefWidth(250);
        folderField.textProperty().addListener((obs, oldVal, newVal) -> {
            validateProjectsFolder();
            updateErrorSummary();
        });
    }

    /** {@link #folderField} with a Browse button beside it. */
    protected HBox createProjectsFolderBox() {
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
        return folderBox;
    }

    // ==================== White balance ====================

    /** Builds the WB mode combo. Runs before the hardware section, which places it in its grid. */
    protected void createWbModeCombo() {
        wbModeComboBox = new ComboBox<>();
        wbModeComboBox.getItems().addAll("Off", "Camera AWB", "Simple (90deg)", "Per-angle (PPM)");
        String savedWBMode = PersistentPreferences.getLastWhiteBalanceMode();
        if (savedWBMode != null && wbModeComboBox.getItems().contains(savedWBMode)) {
            wbModeComboBox.setValue(savedWBMode);
        } else {
            // Use modality handler's default WB mode
            wbModeComboBox.setValue(getDefaultWbModeFromModality());
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
            refreshModalityReadout(); // wbMode targets the background subfolder PPM exposures resolve from
        });
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
                var handler = ModalityRegistry.getHandler(modality);
                String wireMode = handler.getDefaultWbMode();
                // Map wire mode to display name
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

    /**
     * Updates the visibility and default of the white balance row based on the current detector
     * and modality. WB is shown only when the detector is a JAI camera AND the modality supports
     * white balance (its {@code getDefaultWbMode()} is not "off"). Monochrome detectors use the
     * flat-field selector instead.
     */
    protected void updateWhiteBalanceVisibility() {
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

        if (flatfieldSelector != null) {
            flatfieldSelector.refresh(modality, detector);
            refreshBackgroundStatusVisibility();
        }

        if (backgroundProfileSelector != null) {
            String objectiveDisplay = objectiveBox.getValue();
            backgroundProfileSelector.refreshStatus(
                    modality, objectiveDisplay != null ? extractIdFromDisplayString(objectiveDisplay) : null, detector);
        }

        logger.debug(
                "White balance visibility updated: JAI={}, modalityWB={}, visible={}", isJAI, modalityUsesWb, showWb);
    }

    /** "No background profile" means nothing to a run that does not use backgrounds. */
    private void refreshBackgroundStatusVisibility() {
        if (backgroundProfileSelector != null && flatfieldSelector != null) {
            backgroundProfileSelector.setStatusVisible(flatfieldSelector.usesBackground());
        }
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
            String baseFolder = configManager.getBackgroundProfileFolder();
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

    /** The internal white-balance protocol name for the current selection ({@code "off"} when hidden). */
    protected String currentWbModeProtocol() {
        if (wbModeComboBox == null || !wbModeComboBox.isVisible()) {
            return "off";
        }
        String display = wbModeComboBox.getValue();
        return WbMode.fromDisplayName(display != null ? display : "Off").getProtocolName();
    }

    // ==================== Hardware section ====================

    /** Builds the modality, objective and detector pickers and wires their cascading updates. */
    protected void createHardwareSection() {
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
        objectiveBox.valueProperty().addListener((obs, old, newVal) -> {
            triggerPreviewUpdate();
            refreshModalityReadout(); // objective drives the resolved profile / exposure / intensity
        });
        detectorBox.valueProperty().addListener((obs, old, newVal) -> {
            triggerPreviewUpdate();
            updateWhiteBalanceVisibility(); // Update WB section visibility when detector changes
            refreshModalityReadout();
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
        row++;

        // Background profile: which set of backgrounds this acquisition is corrected with.
        backgroundProfileSelector = new BackgroundProfileSelector(configManager::getBackgroundBaseFolder, () -> {
            updateWhiteBalanceVisibility();
            refreshModalityReadout();
        });
        grid.add(backgroundProfileSelector, 0, row, 2, 1);
        row++;

        // Flat-field correction for monochrome (non-JAI) detectors. JAI cameras bundle
        // background correction into the WB mode selector.
        flatfieldSelector = new FlatfieldMethodSelector(this::refreshBackgroundStatusVisibility);
        grid.add(flatfieldSelector, 0, row, 2, 1);
        row++;

        addHardwareRows(grid, row);

        hardwarePane = new TitledPane("HARDWARE CONFIGURATION", grid);
        hardwarePane.setExpanded(true);
        hardwarePane.setStyle("-fx-font-weight: bold;");
    }

    /** Seeds modality from the central state, keeps the two in sync, and fills the cascade. */
    protected void initializeHardwareSelections() {
        // Initial modality from the central state so this dialog agrees
        // with the wizard / Camera tab / other open surfaces.
        var modalityState = ModalityState.getInstance();
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
        // Drop the listener when the dialog's scene goes away.
        modalityBox.sceneProperty().addListener((s, oldScene, newScene) -> {
            if (newScene == null) {
                modalityState.modalityProperty().removeListener(stateListener);
            }
        });
    }

    private String hardwareDisplayItem(String friendlyName, String id) {
        boolean named = friendlyName != null && !friendlyName.isEmpty();
        return named ? friendlyName + " (" + id + ")" : id;
    }

    private void updateObjectivesForModality(String modality) {
        Set<String> objectiveIds = configManager.getAvailableObjectives();
        Map<String, String> objectiveNames = configManager.getObjectiveFriendlyNames(objectiveIds);

        List<String> objectiveDisplayItems = objectiveIds.stream()
                .map(id -> hardwareDisplayItem(objectiveNames.get(id), id))
                .sorted()
                .collect(Collectors.toList());

        objectiveBox.getItems().clear();
        objectiveBox.getItems().addAll(objectiveDisplayItems);

        // Try to restore last used objective via the shared state
        String lastObjective = ObjectiveState.getInstance().getObjective();
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
                .map(id -> hardwareDisplayItem(detectorNames.get(id), id))
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

    // ==================== Modality panel ====================

    /** Current dialog selection for the modality panel's "what will be used" readout. */
    private AcquisitionReadout.Context currentReadoutContext() {
        String modality = modalityBox != null ? modalityBox.getValue() : null;
        String objective = extractIdFromDisplayString(objectiveBox != null ? objectiveBox.getValue() : null);
        String detector = extractIdFromDisplayString(detectorBox != null ? detectorBox.getValue() : null);
        return new AcquisitionReadout.Context(modality, objective, detector, currentWbModeProtocol());
    }

    /** Refresh the current modality panel's resolved-values readout, if it has one. */
    private void refreshModalityReadout() {
        if (modalityUI instanceof BrightfieldExposureBoundingBoxUI bfUi) {
            bfUi.refreshReadout();
        } else if (modalityUI instanceof PPMBoundingBoxUI ppmUi) {
            ppmUi.refreshReadout();
        }
    }

    /** Swaps in the modality's panel and wires its MDA export button and readout. */
    private void updateModalityUI(String modality) {
        ModalityHandler handler = ModalityRegistry.getHandler(modality);
        Optional<ModalityHandler.BoundingBoxUI> uiOpt =
                ModalityRegistry.getUi(handler).createBoundingBoxUI(modality);

        modalityContent.getChildren().clear();

        if (uiOpt.isPresent()) {
            modalityUI = uiOpt.get();
            modalityContent.getChildren().add(modalityUI.getNode());
            javafx.beans.value.ObservableValue<?> selection = modalityUI.selectionObservable();
            if (selection != null) {
                selection.addListener((obs, was, now) -> triggerPreviewUpdate());
            }
            onModalityOptionsChanged(true);
            logger.debug("Added modality UI for: {}", modality);
            // Wire the "Save as MicroManager MDA..." button on the panel
            // so it can build an export context from this dialog's live state.
            java.util.function.Supplier<MdaExportContext> supplier = this::buildMdaExportContext;
            if (modalityUI instanceof WidefieldChannelBoundingBoxUI wfUi) {
                wfUi.installMdaExportContext(supplier);
            } else if (modalityUI instanceof PPMBoundingBoxUI ppmUi) {
                ppmUi.installMdaExportContext(supplier);
                // Live "what will be used" readout: resolved per-angle exposures.
                ppmUi.installReadoutContext(this::currentReadoutContext);
            } else if (modalityUI instanceof BrightfieldExposureBoundingBoxUI bfUi) {
                // Live "what will be used" readout: resolved profile / exposure / illumination.
                bfUi.installReadoutContext(this::currentReadoutContext);
            }
        } else {
            modalityUI = null;
            Label noOptions = new Label("No additional options for " + modality + " modality.");
            noOptions.setStyle("-fx-text-fill: " + ThemeColors.MUTED + "; -fx-font-style: italic;");
            modalityContent.getChildren().add(noOptions);
            onModalityOptionsChanged(false);
        }

        // Update white balance visibility based on current detector and modality
        updateWhiteBalanceVisibility();

        // Refresh the loop-order toggle: labels swap between widefield and PPM, the disable
        // binding picks up the new modalityUI's hasMultipleChannelsSelected property (or the row
        // hides for non-participating modalities), and the saved pick for this family is restored.
        updateLoopOrderToggle(modality);
    }

    /** What the operator chose on the modality panel, for the dialog's result. */
    protected ModalityPanelSelections readModalityPanelSelections() {
        if (modalityUI == null) {
            return new ModalityPanelSelections(null, Map.of(), null, null, Set.of(), List.of());
        }
        Map<String, Double> angleOverrides = modalityUI.getAngleOverrides();
        if (angleOverrides != null) {
            logger.info("User specified angle overrides: {}", angleOverrides);
        }
        Map<String, Double> channelIntensityOverrides = modalityUI.getChannelIntensityOverrides();
        if (channelIntensityOverrides == null) {
            channelIntensityOverrides = Map.of();
        }
        if (!channelIntensityOverrides.isEmpty()) {
            logger.info("User specified channel intensity overrides: {}", channelIntensityOverrides);
        }
        String focusChannelId = modalityUI.getFocusChannelId();
        if (focusChannelId != null) {
            logger.info("User specified focus channel: {}", focusChannelId);
        }
        ModalityHandler.BoundingBoxUI.FocusChannelOverride dedicatedFocusChannel =
                modalityUI.getDedicatedFocusChannel();
        if (dedicatedFocusChannel != null) {
            logger.info("User specified a dedicated focus channel: {}", dedicatedFocusChannel);
        }
        Set<String> splitChannelIds = modalityUI.getSplitChannelIds();
        if (splitChannelIds == null) {
            splitChannelIds = Set.of();
        }
        if (!splitChannelIds.isEmpty()) {
            logger.info("User chose to split channels into separate files: {}", splitChannelIds);
        }
        List<String> alignmentChannelIds = modalityUI.getAlignmentChannelIds();
        if (alignmentChannelIds == null) {
            alignmentChannelIds = List.of();
        }
        if (!alignmentChannelIds.isEmpty()) {
            logger.info("User chose tile-alignment channel(s): {}", alignmentChannelIds);
        }
        return new ModalityPanelSelections(
                angleOverrides,
                channelIntensityOverrides,
                focusChannelId,
                dedicatedFocusChannel,
                splitChannelIds,
                alignmentChannelIds);
    }

    // ==================== Autofocus and advanced sections ====================

    /** Creates the autofocus strategy combo and a grid holding it in row 0. */
    protected GridPane createAutofocusGrid() {
        // Overrides the per-modality strategy declared in autofocus_<scope>.yml for this one
        // acquisition. "Default" uses the YAML binding; the other values emit the --af-strategy
        // CLI flag, which the Python v2 loader respects.
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
        return afGrid;
    }

    /** Wraps the autofocus controls in their own section, since autofocus applies to every modality. */
    protected void createAutofocusPane(GridPane afGrid) {
        autofocusPane = new TitledPane("AUTOFOCUS", afGrid);
        autofocusPane.setExpanded(false);
        autofocusPane.setStyle("-fx-font-weight: bold;");
    }

    /** Wraps the modality options in the collapsed advanced section. */
    protected void createAdvancedPane(VBox content) {
        advancedPane = new TitledPane("MODALITY-SPECIFIC OPTIONS", content);
        advancedPane.setExpanded(false);
        advancedPane.setStyle("-fx-font-weight: bold;");
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
     * Saves the autofocus strategy override and returns its protocol name. Null means "use the
     * YAML per-modality binding" and produces no --af-strategy CLI flag.
     */
    protected String saveAfStrategyChoice() {
        String afStrategyProtocol = AfStrategyChoice.displayToProtocol(
                afStrategyCombo != null ? afStrategyCombo.getValue() : AfStrategyChoice.DEFAULT_DISPLAY);
        PersistentPreferences.setLastAfStrategy(afStrategyProtocol);
        if (afStrategyProtocol != null) {
            logger.info("User selected AF strategy override: {}", afStrategyProtocol);
        }
        return afStrategyProtocol;
    }

    // ==================== Time-lapse section ====================

    /**
     * Builds the time-lapse controls: timepoints, interval and timepoint projection. Shared by
     * Bounded and Existing Image acquisition, which read the same time-lapse preferences.
     */
    protected TitledPane createTimeLapseSection() {
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

        timeLapseProjectionCombo = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "None", "Mean", "Max Intensity", "Min Intensity", "Sum", "Std Deviation"));
        timeLapseProjectionCombo.setValue(PersistentPreferences.getTimeLapseProjection());
        timeLapseProjectionCombo.setTooltip(new Tooltip("How to combine the timepoints, as the Z projection "
                + "combines Z planes. None keeps every timepoint, and the stitched image is a time series. "
                + "Mean averages each tile over the timepoints (for example to reduce noise), and the "
                + "stitched image is one timepoint; with Save raw tiles the timepoints are kept in the "
                + "raw folder."));
        timeLapseProjectionCombo
                .disableProperty()
                .bind(timeLapseEnableCheck.selectedProperty().not());
        timeLapseProjectionCombo.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                PersistentPreferences.setTimeLapseProjection(n);
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
        grid.add(new Label("Timepoint projection:"), 0, 2);
        grid.add(timeLapseProjectionCombo, 1, 2);

        VBox content = new VBox(8, timeLapseEnableCheck, grid);
        content.setPadding(new Insets(5));

        TitledPane pane = new TitledPane("TIME-LAPSE OPTIONS", content);
        pane.setExpanded(false);
        pane.setAnimated(false);
        pane.setStyle("-fx-font-weight: bold;");
        return pane;
    }

    // ==================== Z-stack section ====================

    /** Builds the Z-stack controls, the stitched-output choice and the loop-order toggle. */
    protected TitledPane createZStackSection() {
        zStackEnableCheck = new CheckBox("Enable Z-stack acquisition");
        zStackEnableCheck.setTooltip(
                new Tooltip("Acquire multiple Z-planes at each tile position and compute a projection. "
                        + "Essential for thick samples and SHG/multiphoton imaging."));
        zStackEnableCheck.setSelected(PersistentPreferences.isZStackEnabled());
        CheckBox enableCheck = zStackEnableCheck;

        double zMaxUm = zRangeMaxUm();
        Spinner<Double> rangeSpinner = new Spinner<>(1.0, zMaxUm, PersistentPreferences.getZStackRange(), 5.0);
        rangeSpinner.setEditable(true);
        rangeSpinner.setPrefWidth(100);
        rangeSpinner.setTooltip(new Tooltip("Total Z range in micrometers centered on autofocus. "
                + "Example: 20um = +/-10um around focus. "
                + String.format(
                        "At most %.0f um, the stage's Z travel (stage.limits.z_um in the microscope config).",
                        zMaxUm)));

        Spinner<Double> stepSpinner = new Spinner<>(0.1, 50.0, PersistentPreferences.getZStackStep(), 0.5);
        stepSpinner.setEditable(true);
        stepSpinner.setPrefWidth(100);
        stepSpinner.setTooltip(new Tooltip(
                "Distance between Z-planes in micrometers. " + "Smaller steps give finer Z sampling but more planes."));

        ComboBox<String> projectionCombo = new ComboBox<>();
        projectionCombo
                .getItems()
                .addAll(
                        "Max Intensity",
                        "Min Intensity",
                        "Sum",
                        "Mean",
                        "Std Deviation",
                        "Extended Depth of Field",
                        "None");
        projectionCombo.setValue(PersistentPreferences.getZStackProjection());
        projectionCombo.setTooltip(new Tooltip("How to combine Z-planes for stitching. "
                + "Max intensity is standard for fluorescence and SHG. "
                + "Extended Depth of Field takes each pixel from the plane where it is "
                + "sharpest -- use it for brightfield, and wherever one field spans more "
                + "than a depth of field in Z (a tilted sample, or thick/uneven tissue), "
                + "which no amount of autofocus can fix because autofocus picks a single "
                + "Z for the whole field. "
                + "Choose \"None\" to preserve the full Z-stack (and time series, if any) "
                + "as a single multi-dimensional stitched image instead of projecting to 2D."));

        // EDF is the only projection with settings, so the button appears
        // beside the dropdown and enables only for it.
        Button edfSettingsButton = new Button("EDF settings...");
        edfSettingsButton.setTooltip(
                new Tooltip("Sharpness metric, averaging window and focal-surface smoothing for the "
                        + "Extended Depth of Field projection. Only available when that projection "
                        + "is selected; the others have nothing to tune."));
        edfSettingsButton.setOnAction(e -> EdfSettingsDialog.show(
                edfSettingsButton.getScene() == null
                        ? null
                        : edfSettingsButton.getScene().getWindow()));
        Runnable syncEdfButton = () -> edfSettingsButton.setDisable(
                !"Extended Depth of Field".equals(projectionCombo.getValue()) || projectionCombo.isDisabled());
        projectionCombo.valueProperty().addListener((obs, o, n) -> syncEdfButton.run());
        projectionCombo.disabledProperty().addListener((obs, o, n) -> syncEdfButton.run());
        syncEdfButton.run();

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
        projectionCombo.disableProperty().bind(enableCheck.selectedProperty().not());

        // Save to preferences on change, and refresh the preview, whose image count and time include the Z planes.
        enableCheck.selectedProperty().addListener((obs, o, n) -> {
            PersistentPreferences.setZStackEnabled(n);
            triggerPreviewUpdate();
        });
        rangeSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) PersistentPreferences.setZStackRange(n);
            triggerPreviewUpdate();
        });
        stepSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) PersistentPreferences.setZStackStep(n);
            triggerPreviewUpdate();
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
        grid.add(edfSettingsButton, 2, 2);
        grid.add(infoLabel, 0, 3, 2, 1);
        grid.add(new Label("Stitched output:"), 0, 4);
        grid.add(outputOrganizationCombo, 1, 4);

        // Loop-order toggle: lets the user flip the per-tile snap nest
        // between the "Z-inner" default (one channel sweeps all Z, then
        // switch channels -- fewer filter changes, fast for fixed slides)
        // and "channel-inner" (every channel at each Z -- slower but tightly
        // Z-registered for live samples). Labels swap for PPM (angle-inner
        // default vs Z-inner alternative).
        //
        // The toggle is only meaningful when Z-stack is on AND the
        // modality has 2+ outer-axis units (channels or angles) -- the
        // disable binding wired in updateLoopOrderToggle() reflects that.
        // The row is hidden for non-participating modalities (BF, LSM).
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
        // Persist whichever radio is selected, keyed by the current modality family.
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

    /**
     * Returns the {@link PersistentPreferences#LOOP_ORDER_FAMILY_WIDEFIELD}
     * / {@code LOOP_ORDER_FAMILY_PPM} key for the given modality string,
     * or {@code null} if the modality doesn't participate in the toggle.
     */
    private static String loopOrderFamily(String modality) {
        if (modality == null) {
            return null;
        }
        String norm = modality.toLowerCase(Locale.ROOT);
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

    /**
     * Resolves the per-tile inner axis from the loop-order toggle. Null means omit the flag and
     * let the server pick its per-modality default, which is also the answer when the toggle is
     * hidden or disabled (modality does not participate, Z-stack is off, or fewer than 2 channels).
     */
    protected String resolveInnerAxis(String modality) {
        String innerAxis = null;
        String family = loopOrderFamily(modality);
        if (family != null
                && loopOrderInnerAltRadio != null
                && !loopOrderInnerAltRadio.isDisabled()
                && loopOrderInnerAltRadio.isSelected()) {
            if (PersistentPreferences.LOOP_ORDER_FAMILY_WIDEFIELD.equals(family)) {
                innerAxis = "channel";
            } else if (PersistentPreferences.LOOP_ORDER_FAMILY_PPM.equals(family)) {
                innerAxis = "z";
            }
        }
        return innerAxis;
    }

    /** How the stitched output is grouped. Defaults to {@link OutputFormat#OME_SINGLE}. */
    protected OutputFormat selectedStitchingOrganization() {
        if (outputOrganizationCombo == null) {
            return OutputFormat.OME_SINGLE;
        }
        return ORG_PER_CHANNEL.equals(outputOrganizationCombo.getValue())
                ? OutputFormat.OME_PER_CHANNEL
                : OutputFormat.OME_SINGLE;
    }

    // ==================== Preview section ====================

    /** Wraps {@link #previewContentNodes} in a styled, non-collapsible titled pane. */
    protected TitledPane createPreviewSection() {
        VBox previewContent = new VBox(5, previewContentNodes());
        previewContent.setPadding(new Insets(10));
        previewContent.setStyle("-fx-background-color: " + (isDark() ? "#333" : "#f8f9fa") + "; -fx-border-color: "
                + (isDark() ? "#555" : "#dee2e6") + "; -fx-border-width: 1px;");

        TitledPane previewPane = new TitledPane("ACQUISITION PREVIEW", previewContent);
        previewPane.setExpanded(true);
        previewPane.setCollapsible(false);
        previewPane.setStyle("-fx-font-weight: bold;");

        return previewPane;
    }

    /** Routes the preview debounce timer to {@link #updatePreviewPanel}. */
    protected void setupPreviewUpdateListeners() {
        previewDebounce.setOnFinished(event -> updatePreviewPanel());
    }

    /** Restarts the debounce timer that schedules a preview refresh. */
    protected void triggerPreviewUpdate() {
        previewDebounce.playFromStart();
    }

    /** Planes per tile from the saved Z-stack settings; 1 when Z-stack is off. */
    protected int zPlaneCount() {
        int zPlanes = 1;
        if (PersistentPreferences.isZStackEnabled()) {
            double zRange = PersistentPreferences.getZStackRange();
            double zStep = PersistentPreferences.getZStackStep();
            if (zStep > 0) {
                zPlanes = (int) Math.ceil(zRange / zStep) + 1;
            }
        }
        return zPlanes;
    }

    /** Whether the modality takes one image per rotation angle, rather than per channel. */
    protected boolean isMultiAngle(String modality) {
        ModalityHandler handler = ModalityRegistry.getHandler(modality);
        return handler != null && handler.isMultiAngleModality();
    }

    /**
     * Images captured per tile position: the angles (PPM) or channels (fluorescence, BF-IF)
     * the run will take.
     *
     * @param modality The modality name
     * @return Images per tile (minimum 1)
     */
    protected int getImagesPerTileForModality(String modality) {
        if (modality == null) return 1;
        ModalityHandler handler = ModalityRegistry.getHandler(modality);
        boolean multiAngle = handler != null && handler.isMultiAngleModality();
        // The panel's selection: the ticked angles, or the ticked channels when "override"
        // is on (null means the defaults for every channel).
        Map<String, Double> selection = modalityUI != null ? modalityUI.getAngleOverrides() : null;
        int configured = 1;
        if (multiAngle) {
            configured = handler.getDefaultAngleCount();
        } else {
            try {
                List<Channel> channels = configManager.getModalityChannels(modality);
                if (channels != null && !channels.isEmpty()) {
                    configured = channels.size();
                }
            } catch (Exception e) {
                logger.debug("Could not read channels for modality {}: {}", modality, e.getMessage());
            }
        }
        return imagesPerTile(selection, configured);
    }

    /**
     * The panel's selection size when it has one, else the configured count; never below 1.
     * An empty selection (nothing ticked) is refused at Start, so it counts as the configured set.
     */
    static int imagesPerTile(Map<String, Double> selection, int configured) {
        if (selection != null && !selection.isEmpty()) {
            return selection.size();
        }
        return Math.max(1, configured);
    }

    /**
     * Seconds to acquire {@code images} files. Uses the per-file mean saved from previous
     * acquisitions, which already includes autofocus overhead at its actual frequency, else
     * a conservative 2 seconds per image.
     */
    protected double estimatedAcquisitionSec(int images) {
        if (PersistentPreferences.hasTimingData()) {
            return PersistentPreferences.estimateAcquisitionTime(images) / 1000.0;
        }
        return images * 2.0;
    }

    /** Formats a time estimate and says what it is based on. */
    protected String formatTimeEstimate(double seconds) {
        String basis = PersistentPreferences.hasTimingData() ? " (based on previous acquisitions)" : " (estimate)";
        return formatTime(seconds) + basis;
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

    /** Formats a size in megabytes as MB or GB. */
    protected String formatStorage(double megabytes) {
        if (megabytes < 1024) {
            return String.format("~%.0f MB", megabytes);
        } else {
            return String.format("~%.1f GB", megabytes / 1024.0);
        }
    }

    // ==================== Validation ====================

    /** Creates the hidden panel that lists validation errors. */
    protected void createErrorSummaryPanel() {
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

    /**
     * A new project needs a folder to go in. Left empty, the project was created relative
     * to QuPath's working directory and the run stopped there with nothing on screen.
     */
    private void validateProjectsFolder() {
        boolean needsFolder = !hasOpenProject;
        String text = folderField != null ? folderField.getText().trim() : "";
        if (needsFolder && text.isEmpty()) {
            validationErrors.put("projectsFolder", "Choose a projects folder for the new project");
        } else if (needsFolder && !new File(text).isAbsolute()) {
            validationErrors.put("projectsFolder", "The projects folder must be a full path");
        } else {
            validationErrors.remove("projectsFolder");
        }
    }

    /** Checks every field, recording an error per invalid one, and updates the error summary. */
    protected void validateAll() {
        // Validate sample name
        String sampleName = sampleNameField.getText().trim();
        String sampleError = SampleNameValidator.getValidationError(sampleName);
        if (sampleError != null) {
            validationErrors.put("sampleName", sampleError);
        } else {
            validationErrors.remove("sampleName");
        }

        validateProjectsFolder();

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

        validateWorkflowFields();

        updateErrorSummary();
    }

    /** Shows the error list and disables Start while any validation error exists. */
    protected void updateErrorSummary() {
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

    // ==================== Result and MDA export ====================

    /**
     * Saves the sample and hardware choices. Modality and objective go through their central
     * state singletons so cross-dialog sync stays canonical.
     *
     * <p>Logs any divergence between the dialog combo and the global objective state that
     * accumulated while the dialog was open (a Live Viewer Camera tab pick, another dialog's
     * submit). The combo wins, as the user's explicit choice.
     *
     * @param dialogLabel names this dialog in the drift warning
     */
    protected void saveSelections(
            String dialogLabel, String sampleName, String modality, String objective, String detector) {
        String globalObjective = ObjectiveState.getInstance().getObjective();
        if (objective != null
                && !objective.isEmpty()
                && globalObjective != null
                && !globalObjective.isEmpty()
                && !globalObjective.equals(objective)) {
            logger.warn(
                    "{} objective drift: dialog combo='{}' but ObjectiveState='{}'. Using dialog value.",
                    dialogLabel,
                    objective,
                    globalObjective);
        }

        PersistentPreferences.setLastSampleName(sampleName);
        ModalityState.getInstance().setModality(modality);
        ObjectiveState.getInstance().setObjective(objective);
        PersistentPreferences.setLastDetector(detector);
    }

    /**
     * Builds the snapshot an MDA export needs. Called by the "Save as MicroManager MDA..." button
     * on the modality panel via a {@code Supplier}.
     *
     * @return the context, or {@link MdaExportContext#notReady(String)} when a precondition fails
     *     or an error occurs, so the panel can surface a friendly alert
     */
    protected final MdaExportContext buildMdaExportContext() {
        try {
            return prepareMdaExport();
        } catch (Exception ex) {
            logger.error("Could not build MDA export context: {}", ex.getMessage(), ex);
            return MdaExportContext.notReady("Could not prepare MDA export: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    /**
     * The sample name an MDA export uses. With a project open, the OK path derives the effective
     * sample name from the project's folder name; this does the same, so MDA files land inside
     * the open project and not in a sibling folder.
     */
    protected String mdaSampleName() {
        if (hasOpenProject && existingProjectName != null) {
            return existingProjectName;
        }
        return sampleNameField != null ? sampleNameField.getText().trim() : "";
    }

    /**
     * Builds the acquisition command for an MDA export, from the same sources the OK path uses,
     * so the exported files match what Start Acquisition would acquire.
     *
     * @param regionName region the command is built for
     * @return the command, or null when a channel-based modality has no channel selected; report
     *     that with {@link #MDA_NO_CHANNELS_MESSAGE}
     */
    protected AcquisitionCommandBuilder buildMdaCommand(
            SampleSetupResult sample,
            String configFileLocation,
            MicroscopeConfigManager mgr,
            String modalityFolder,
            String regionName) {
        String sampleName = sample.sampleName();
        String objective = sample.objective();
        String detector = sample.detector();

        // Per-region overrides (channel + angle + focus) read from the panel.
        Map<String, Double> angleOverrides = modalityUI != null ? modalityUI.getAngleOverrides() : null;
        String focusChannelId = modalityUI != null ? modalityUI.getFocusChannelId() : null;
        ModalityHandler.BoundingBoxUI.FocusChannelOverride dedicatedFocusChannel =
                modalityUI != null ? modalityUI.getDedicatedFocusChannel() : null;
        Map<String, Double> channelIntensityOverrides =
                modalityUI != null ? modalityUI.getChannelIntensityOverrides() : Map.of();
        if (channelIntensityOverrides == null) {
            channelIntensityOverrides = Map.of();
        }

        // Resolve angles + channels (mirrors AcquisitionManager.performSingleAnnotationAcquisition).
        String wbMode = currentWbModeProtocol();
        List<AngleExposure> angleExposures = AngleResolutionService.resolve(
                        modalityFolder, objective, detector, angleOverrides, wbMode)
                .join();
        List<ChannelExposure> channelExposures =
                ChannelResolutionService.resolve(modalityFolder, objective, detector, angleOverrides, focusChannelId);

        if (ChannelResolutionService.isEmptySelectionForChannelBasedModality(
                modalityFolder, objective, detector, angleOverrides)) {
            return null;
        }

        double pixelSize = mgr.getPixelSize(objective, detector);
        AcquisitionConfigurationBuilder.AcquisitionConfiguration config =
                AcquisitionConfigurationBuilder.buildConfiguration(
                        sample,
                        configFileLocation,
                        modalityFolder,
                        regionName,
                        angleExposures,
                        channelExposures,
                        sample.projectsFolder().getAbsolutePath(),
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
        if (dedicatedFocusChannel != null) {
            cmdBuilder.dedicatedFocusChannel(
                    dedicatedFocusChannel.channelId(),
                    dedicatedFocusChannel.exposureMs(),
                    dedicatedFocusChannel.intensity());
        }
        // Let the modality handler tweak the builder (e.g. disable debayer for LSM).
        ModalityRegistry.getHandler(sample.modality()).configureCommandBuilder(cmdBuilder);
        return cmdBuilder;
    }

    /** The modality's channels by id, empty when it has none. */
    protected Map<String, Channel> modalityChannelLibrary(MicroscopeConfigManager mgr, String modality) {
        Map<String, Channel> channelLibrary = new LinkedHashMap<>();
        try {
            for (Channel ch : mgr.getModalityChannels(modality)) {
                channelLibrary.put(ch.id(), ch);
            }
        } catch (Exception e) {
            logger.debug("No channel library for modality '{}': {}", modality, e.getMessage());
        }
        return channelLibrary;
    }
}
