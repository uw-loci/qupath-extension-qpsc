package qupath.ext.qpsc.ui.acquisition;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.acquisition.BoundingBox;
import qupath.ext.qpsc.acquisition.TilingUtilities;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.config.ObjectiveUtils;
import qupath.ext.qpsc.modality.WbMode;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.OutputFormat;
import qupath.ext.qpsc.service.mda.MdaExportAction;
import qupath.ext.qpsc.service.mda.MdaExportContext;
import qupath.ext.qpsc.service.mda.TileStagePos;
import qupath.ext.qpsc.service.microscope.MicroscopeController;
import qupath.ext.qpsc.ui.DialogOwner;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.lib.gui.QuPathGUI;

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
            // Optional dedicated focus channel (channel + focus exposure/intensity).
            // Null unless the operator opted in; see BoundingBoxUI.FocusChannelOverride.
            qupath.ext.qpsc.modality.ModalityHandler.BoundingBoxUI.FocusChannelOverride dedicatedFocusChannel,
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
            // True when the operator has already focused the sample. Skips ONLY the
            // pre-acquisition autofocus search; per-tile AF and drift correction
            // still run. Not the same as the disable-all-autofocus preference.
            boolean sampleAlreadyInFocus,
            // Channel ids the user marked "Split" -- each is stitched into its own
            // file instead of being merged. Empty = merge all (the default).
            Set<String> splitChannelIds,
            // Channel ids ticked "Align": what tile registration measures seams on
            // (one = that channel, several = a normalized merge). Empty = default
            // (the focus channel, else a merge of all acquired channels).
            List<String> alignmentChannelIds,
            // How the stitched output is grouped (single combined file vs one file
            // per channel). Defaults to OME_SINGLE.
            OutputFormat stitchingOrganization) {}

    /**
     * Shows the unified acquisition dialog.
     *
     * @return CompletableFuture containing the result, or canceled if user cancels
     */
    /** The dialog currently on screen, or null. Only touched on the FX thread. */
    private static Dialog<UnifiedAcquisitionResult> activeDialog;

    public static CompletableFuture<UnifiedAcquisitionResult> showDialog() {
        CompletableFuture<UnifiedAcquisitionResult> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                // Modality had been the only thing preventing a second copy being opened off
                // the menu while one is up -- showAndWait runs a nested event loop, so the menu
                // stays live. Canceling the new future matches what Cancel already does.
                if (activeDialog != null
                        && activeDialog.getDialogPane().getScene() != null
                        && activeDialog.getDialogPane().getScene().getWindow() != null
                        && activeDialog.getDialogPane().getScene().getWindow().isShowing()) {
                    logger.info("Bounded acquisition dialog already open - bringing to front");
                    javafx.stage.Window win =
                            activeDialog.getDialogPane().getScene().getWindow();
                    win.requestFocus();
                    if (win instanceof javafx.stage.Stage openStage) {
                        openStage.toFront();
                    }
                    future.cancel(true);
                    return;
                }
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
    private static class UnifiedDialogBuilder extends AcquisitionDialogBuilder<UnifiedAcquisitionResult> {
        private final ResourceBundle res;
        private VBox projectRowsBox;

        // UI Components - Region Section
        private TextField startXField;
        private TextField startYField;
        private TextField widthField;
        private TextField heightField;
        private TextField endXField;
        private TextField endYField;
        private Label calculatedBoundsLabel;
        /**
         * The tile count beside the size inputs, one per mode pane (a node has one parent). Both
         * show what the preview's Tile Grid line shows, so the count is visible while typing.
         */
        private final Label startSizeTileCountLabel = tileCountLabel();

        private final Label twoCornersTileCountLabel = tileCountLabel();
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
        private Label previewCompletionLabel;
        private Label previewErrorLabel;

        // UI Components - Advanced Section Content
        private VBox advancedContent;
        private CheckBox afBenchmarkCheck;
        private CheckBox alreadyInFocusCheck;

        // UI Components - Time-lapse Section

        // TitledPanes for section management
        private TitledPane regionPane;

        UnifiedDialogBuilder() {
            this.res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");

            readProjectState();
        }

        /**
         * Reads which project QuPath has open now. Called at construction, whenever the open
         * project changes while the dialog is up, and again when the result is built, so Start
         * Acquisition never acts on a project state the dialog showed earlier but that no longer
         * holds. Returns true when the state changed.
         */
        private boolean readProjectState() {
            QuPathGUI gui = QuPathGUI.getInstance();
            var project = gui != null ? gui.getProject() : null;
            File folder = null;
            if (project != null && project.getPath() != null) {
                folder = project.getPath().toFile().getParentFile();
            }
            boolean open = folder != null;
            boolean changed = open != hasOpenProject || !java.util.Objects.equals(folder, existingProjectFolder);
            hasOpenProject = open;
            existingProjectFolder = folder;
            existingProjectName = folder != null ? folder.getName() : null;
            if (changed && open) {
                logger.info("Open project: {} in {}", existingProjectName, existingProjectFolder.getParent());
            } else if (changed) {
                logger.info("No project open; a new project will be created in the projects folder");
            }
            return changed;
        }

        /** The folder the acquisition writes its project into: the open project's, else the typed one. */
        private File effectiveProjectsFolder() {
            if (hasOpenProject && existingProjectFolder != null) {
                return existingProjectFolder.getParentFile();
            }
            return new File(folderField.getText().trim());
        }

        @Override
        protected String initialSampleName() {
            return PersistentPreferences.getLastSampleName();
        }

        Optional<UnifiedAcquisitionResult> buildAndShow() {
            dialog = new Dialog<>();
            // Non-modal so the operator can use the Live Viewer while this is open: the
            // "Use Current Position as Center" and "Get Stage Position" buttons refer to the
            // center of the Live Viewer field of view, which modality locked out.
            // showAndWait() still holds the workflow until they answer.
            //
            // Being non-modal, a project can be opened or closed while the dialog is up: the
            // PROJECT & SAMPLE rows follow QuPath's open project (listener below), and the
            // result re-reads it, so Start Acquisition writes where the dialog says it will.
            dialog.initModality(Modality.NONE);
            dialog.setTitle("Bounded Acquisition");
            dialog.setHeaderText("Configure and start a new bounded acquisition.\n"
                    + "All settings are visible below - expand sections as needed.");
            dialog.setGraphic(DocumentationHelper.createHelpButton("boundedAcquisition"));
            dialog.setResizable(true);

            // Add buttons
            ButtonType okType = new ButtonType("Start Acquisition", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            dialog.getDialogPane().getButtonTypes().addAll(okType, cancelType);
            UIFunctions.neverDefaultButton(dialog.getDialogPane().lookupButton(okType));

            startButton = (Button) dialog.getDialogPane().lookupButton(okType);
            startButton.setDisable(true); // Start disabled until validation passes
            guardStartAgainstSlowTimeLapse();

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
                    autofocusPane,
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

            // Registered here rather than via setOnHidden: this class already owns a
            // try/finally around the show, and setOnHidden is a single property, so a second
            // one added elsewhere would silently replace the first and leak the reference.
            activeDialog = dialog;
            // Follow QuPath's open project while the dialog is up.
            QuPathGUI projectGui = QuPathGUI.getInstance();
            javafx.beans.value.ChangeListener<Object> projectListener = (obs, was, now) -> {
                if (readProjectState()) {
                    rebuildProjectRows();
                    validateAll();
                }
            };
            if (projectGui != null) {
                projectGui.projectProperty().addListener(projectListener);
            }
            try {
                DialogOwner.own(dialog);
                return dialog.showAndWait();
            } finally {
                if (projectGui != null) {
                    projectGui.projectProperty().removeListener(projectListener);
                }
                activeDialog = null;
                // Always clear the stage-map preview once the dialog is dismissed --
                // acquisition progress (if started) is shown by the acquisition overlay instead.
                StageMapWindow.clearBoundingBoxPreview();
            }
        }

        @Override
        protected void addProjectRows(GridPane grid, int row) {
            // The projects-folder field is built once so what the operator typed survives a
            // project being opened and closed; the rows around it follow the project state.
            createProjectsFolderField();
            projectRowsBox = new VBox(8);
            grid.add(projectRowsBox, 0, row, 2, 1);
            rebuildProjectRows();
        }

        /** The project rows of PROJECT & SAMPLE, for the project state last read. */
        private void rebuildProjectRows() {
            projectRowsBox.getChildren().clear();
            GridPane rows = new GridPane();
            rows.setHgap(10);
            rows.setVgap(8);
            int row = 0;
            if (!hasOpenProject) {
                rows.add(new Label("Projects Folder:"), 0, row);
                rows.add(createProjectsFolderBox(), 1, row);
                row++;

                Label infoLabel = new Label("A new project will be created for this sample.");
                infoLabel.setStyle("-fx-font-style: italic; " + subtleTextStyle());
                rows.add(infoLabel, 0, row, 2, 1);
            } else {
                Label projectLabel = new Label(existingProjectName);
                projectLabel.setStyle("-fx-font-weight: bold;");
                rows.add(new Label("Project:"), 0, row);
                rows.add(projectLabel, 1, row);
                row++;

                Label locationLabel = new Label(existingProjectFolder.getParent());
                locationLabel.setStyle(subtleTextStyle());
                rows.add(new Label("Location:"), 0, row);
                rows.add(locationLabel, 1, row);
                row++;

                Label infoLabel = new Label("Sample will be added to the existing project.");
                infoLabel.setStyle("-fx-font-style: italic; -fx-font-size: 11px;");
                rows.add(infoLabel, 0, row, 2, 1);
            }
            projectRowsBox.getChildren().add(rows);
        }

        @Override
        protected void addHardwareRows(GridPane grid, int row) {
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
                            savedCenterX = String.format(Locale.ROOT, "%.2f", coords[0]);
                            savedCenterY = String.format(Locale.ROOT, "%.2f", coords[1]);
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
            // Red halo while the stage is somewhere other than the center typed here.
            StagePositionHalo.attach(
                    getStartPosBtn,
                    startXField,
                    startYField,
                    "Set the center of the acquisition region to the current\n"
                            + "stage position (center of the Live Viewer field of view).");
            getStartPosBtn.setOnAction(e -> {
                try {
                    double[] coords = MicroscopeController.getInstance().getStagePositionXY();
                    if (coords != null && coords.length >= 2) {
                        startXField.setText(String.format(Locale.ROOT, "%.2f", coords[0]));
                        startYField.setText(String.format(Locale.ROOT, "%.2f", coords[1]));
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
            startSizePane.add(startSizeTileCountLabel, 4, row);

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
                        corner1XField.setText(String.format(Locale.ROOT, "%.2f", coords[0]));
                        corner1YField.setText(String.format(Locale.ROOT, "%.2f", coords[1]));
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
                        endXField.setText(String.format(Locale.ROOT, "%.2f", coords[0]));
                        endYField.setText(String.format(Locale.ROOT, "%.2f", coords[1]));
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
            row++;
            // Its own line under the corner fields: a sixth column squeezed the count and the
            // two Get Stage Position buttons at the dialog's default width.
            twoCornersPane.add(twoCornersTileCountLabel, 1, row, 3, 1);

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

            // Load saved bounding box -- restore ONLY the Two-Corners corner-2
            // fields. Do NOT overwrite startX/startY here: those are the
            // Start+Size "Center X/Y" fields and were just loaded from the
            // dedicated center preference (which is kept up to date in both
            // modes via the field listeners). The saved bounds string holds the
            // computed min/max corners (x1,y1,x2,y2); loading x1,y1 into the
            // center fields replaced the remembered center with the lower-left
            // corner, which is why the center was not being remembered.
            String savedBounds = PersistentPreferences.getBoundingBoxString();
            if (savedBounds != null && !savedBounds.trim().isEmpty()) {
                String[] parts = savedBounds.split(",");
                if (parts.length == 4) {
                    try {
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

        @Override
        protected Node[] previewContentNodes() {
            return new Node[] {
                previewRegionLabel,
                previewFOVLabel,
                previewTileGridLabel,
                previewAnglesLabel,
                previewTotalImagesLabel,
                new Separator(),
                previewTimeLabel,
                previewCompletionLabel,
                previewStorageLabel,
                previewErrorLabel
            };
        }

        private void createAdvancedSection() {
            advancedContent = new VBox(10);
            advancedContent.setPadding(new Insets(10));

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

            // The operator has already focused -- usually the case for a quick
            // bounding box over a field they are looking at right now. Skips the
            // pre-acquisition search only; per-tile AF still corrects drift.
            alreadyInFocusCheck = new CheckBox("Sample is already in focus (skip initial focus search)");
            alreadyInFocusCheck.setSelected(false);
            alreadyInFocusCheck.setTooltip(new Tooltip("Start from the current Z instead of searching for focus first."
                    + " The pre-acquisition search drives to the first diagonal autofocus"
                    + " position -- not where you focused -- and sweeps there, which can"
                    + " take over a minute and can land on the wrong plane. Per-tile"
                    + " autofocus and drift correction are UNAFFECTED; this is not the"
                    + " same as disabling autofocus. Tick it when you have just focused"
                    + " the field yourself."));

            GridPane afGrid = createAutofocusGrid();
            afGrid.add(alreadyInFocusCheck, 0, 1, 2, 1);
            afGrid.add(afBenchmarkCheck, 0, 2, 2, 1);

            // === MODALITY-SPECIFIC SECTION ===
            modalityContent = new VBox(5);
            Label placeholder = new Label("Modality-specific options will appear here when a modality is selected.");
            placeholder.setStyle("-fx-font-style: italic; -fx-opacity: 0.65;");
            modalityContent.getChildren().add(placeholder);

            createAutofocusPane(afGrid);

            advancedContent.getChildren().add(modalityContent);

            createAdvancedPane(advancedContent);
        }

        /**
         * Builds an {@link MdaExportContext} snapshot from the dialog's current state
         * for the bounding-box workflow. Mirrors the per-annotation variant in
         * {@code ExistingImageAcquisitionController} but synthesizes a single
         * region named {@code "bounds"} from the start+size or two-corners inputs.
         * Bounding-box coordinates are already in stage micrometers, so no
         * alignment transform is applied to tile centroids.
         */
        @Override
        protected MdaExportContext prepareMdaExport() {
            readProjectState();
            String sampleName = mdaSampleName();
            if (sampleName.isEmpty()) {
                return MdaExportContext.notReady("Enter a sample name before exporting MDA.");
            }
            String folderText = folderField != null ? folderField.getText().trim() : "";
            if (!hasOpenProject && folderText.isEmpty()) {
                return MdaExportContext.notReady("Enter a projects folder before exporting MDA.");
            }
            File projectsFolder = effectiveProjectsFolder();

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

            SampleSetupResult sample = new SampleSetupResult(sampleName, projectsFolder, modality, objective, detector);

            String configFileLocation = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configFileLocation);

            String modalityFolder = ObjectiveUtils.createEnhancedFolderName(modality, objective);
            java.nio.file.Path regionDir = projectsFolder
                    .toPath()
                    .resolve(sampleName)
                    .resolve(modalityFolder)
                    .resolve("bounds");

            AcquisitionCommandBuilder cmdBuilder =
                    buildMdaCommand(sample, configFileLocation, mgr, modalityFolder, "bounds");
            if (cmdBuilder == null) {
                return MdaExportContext.notReady(MDA_NO_CHANNELS_MESSAGE);
            }

            double[] fov = mgr.getModalityFOV(modality, objective, detector);
            if (fov == null || fov.length < 2) {
                return MdaExportContext.notReady("Could not resolve camera FOV for the selected hardware combination.");
            }
            double overlap = QPPreferenceDialog.getTileOverlapPercentProperty();
            List<TileStagePos> tiles = synthesizeBoundsCentroids(x1, y1, x2, y2, fov[0], fov[1], overlap);
            if (tiles.isEmpty()) {
                return MdaExportContext.notReady("Bounding box produced zero tiles -- check width/height and overlap.");
            }

            List<MdaExportAction.RegionPlan> regionPlans =
                    List.of(new MdaExportAction.RegionPlan("bounds", regionDir, tiles));

            return MdaExportContext.ready(sample, cmdBuilder, regionPlans, mgr, modalityChannelLibrary(mgr, modality));
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
         * Generates tile centroids in stage micrometers for a bounding box, on
         * the grid the acquisition itself uses
         * ({@link TilingUtilities#boundingBoxGrid}). No alignment transform is
         * applied -- inputs are already in stage coordinates.
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
            TilingUtilities.TileGrid grid = TilingUtilities.boundingBoxGrid(
                    new BoundingBox(x1, y1, x2, y2), frameWidthUm, frameHeightUm, overlapPercent);
            List<TileStagePos> out = new ArrayList<>(grid.cols() * grid.rows());
            int idx = 0;
            for (int r = 0; r < grid.rows(); r++) {
                for (int c = 0; c < grid.cols(); c++) {
                    double cx = grid.startX() + c * strideX + frameWidthUm / 2.0;
                    double cy = grid.startY() + r * strideY + frameHeightUm / 2.0;
                    // Z is unknown until autofocus runs; null keeps it out of the MM position list.
                    out.add(new TileStagePos(String.valueOf(idx++), cx, cy, null));
                }
            }
            return out;
        }

        @Override
        protected void updatePreviewPanel() {
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

                // The count depends only on the region's size, not where it sits
                double overlapPercent = QPPreferenceDialog.getTileOverlapPercentProperty();
                TilingUtilities.TileGrid grid = TilingUtilities.boundingBoxGrid(
                        new BoundingBox(0, 0, width, height), frameWidth, frameHeight, overlapPercent);
                int tilesX = grid.cols();
                int tilesY = grid.rows();
                int totalTiles = tilesX * tilesY;
                setTileCount(String.format("%d tiles (%d x %d)", totalTiles, tilesX, tilesY));

                // Per-tile image count (angles for PPM, channels for fluorescence/BF-IF)
                int imagesPerTile = getImagesPerTileForModality(modality);

                // Z-stack multiplier
                int zPlanes = zPlaneCount();

                int totalImages = totalTiles * imagesPerTile * zPlanes;

                // Time-lapse multiplier: repeat the full acquisition over N timepoints.
                int timepoints = 1;
                double intervalSec = 0.0;
                if (PersistentPreferences.isTimeLapseEnabled()) {
                    timepoints = Math.max(1, PersistentPreferences.getTimeLapseTimepoints());
                    intervalSec = Math.max(0.0, PersistentPreferences.getTimeLapseIntervalSec());
                }

                double perTimepointAcqSec = estimatedAcquisitionSec(totalImages);
                lastPerTimepointSec = perTimepointAcqSec;
                totalImages *= timepoints;
                // If the interval exceeds per-timepoint acquisition the run is interval-bound:
                // (N-1) intervals to start the last timepoint, plus that timepoint's acquisition.
                double estimatedSec = timepoints > 1
                        ? Math.max(
                                estimatedAcquisitionSec(totalImages),
                                (timepoints - 1) * intervalSec + perTimepointAcqSec)
                        : perTimepointAcqSec;
                String timeEstimate = formatTimeEstimate(estimatedSec);

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
                String anglesText = String.format(
                        "%s: %d (%s modality)",
                        isMultiAngle(modality) ? "Angles" : "Channels", imagesPerTile, modality);
                if (zPlanes > 1) {
                    anglesText += String.format(", Z-planes: %d", zPlanes);
                }
                if (timepoints > 1) {
                    anglesText += String.format(", Timepoints: %d", timepoints);
                }
                previewAnglesLabel.setText(anglesText);
                previewTotalImagesLabel.setText(String.format("Total Images: %,d", totalImages));
                previewTimeLabel.setText("Est. Time: " + timeEstimate);
                previewCompletionLabel.setText("Est. Completion: " + formatCompletion(estimatedSec));
                previewStorageLabel.setText("Est. Storage: " + storageEstimate);

                previewErrorLabel.setVisible(false);

            } catch (NumberFormatException e) {
                showPreviewPlaceholder("Invalid number format in coordinates");
            } catch (Exception e) {
                logger.debug("Preview update error: {}", e.getMessage());
                showPreviewPlaceholder("Could not calculate preview");
            }
        }

        private static Label tileCountLabel() {
            Label label = new Label("-- tiles");
            label.setStyle("-fx-font-weight: bold;");
            label.setTooltip(new Tooltip("Tiles this region will acquire at the selected objective and detector, "
                    + "including the half-field margin on each side and the tile overlap."));
            return label;
        }

        private void setTileCount(String text) {
            startSizeTileCountLabel.setText(text);
            twoCornersTileCountLabel.setText(text);
        }

        private void showPreviewPlaceholder(String message) {
            setTileCount("-- tiles");
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

        private String formatCompletion(double secondsFromNow) {
            java.time.LocalDateTime done = java.time.LocalDateTime.now().plusSeconds((long) secondsFromNow);
            return done.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, HH:mm"));
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

        @Override
        protected void validateWorkflowFields() {
            validateRegion();
        }

        private UnifiedAcquisitionResult createResult() {
            try {
                readProjectState();
                String sampleName = sampleNameField.getText().trim();
                File projectsFolder = effectiveProjectsFolder();
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

                saveSelections("Unified acquisition", sampleName, modality, objective, detector);
                PersistentPreferences.setBoundingBoxString(
                        String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", x1, y1, x2, y2));

                String afStrategyProtocol = saveAfStrategyChoice();

                // AF method benchmark checkbox (Advanced panel). Diagnostic
                // mode -- not persisted, defaults off every time the dialog opens.
                boolean afBenchmark = afBenchmarkCheck != null && afBenchmarkCheck.isSelected();
                if (afBenchmark) {
                    logger.info("AF method benchmark enabled -- every tile will time sweep + streaming AF");
                }

                boolean sampleAlreadyInFocus = alreadyInFocusCheck != null && alreadyInFocusCheck.isSelected();
                if (sampleAlreadyInFocus) {
                    logger.info("Sample declared already in focus -- pre-acquisition AF search will be skipped");
                }

                // Get angle / channel overrides if available
                ModalityPanelSelections panel = readModalityPanelSelections();

                OutputFormat stitchingOrganization = selectedStitchingOrganization();

                // A monochrome detector has no white balance: send "off", which is also where
                // Background Collection files its backgrounds. On a JAI camera the combo's value
                // is sent even when the row is hidden (brightfield), because Background Collection
                // offers every WB mode on a JAI and files the background under that mode's folder.
                // Existing Image sends "off" there; which one is right is an open TODO item.
                String wbMode = configManager.isJAICamera(detector)
                        ? WbMode.fromDisplayName(wbModeComboBox != null ? wbModeComboBox.getValue() : "Per-angle (PPM)")
                                .getProtocolName()
                        : "off";
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

                String innerAxis = resolveInnerAxis(modality);

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
                        panel.angleOverrides(),
                        panel.channelIntensityOverrides(),
                        panel.focusChannelId(),
                        panel.dedicatedFocusChannel(),
                        afStrategyProtocol,
                        enableWhiteBalance,
                        perAngleWhiteBalance,
                        wbMode,
                        innerAxis,
                        afBenchmark,
                        sampleAlreadyInFocus,
                        panel.splitChannelIds(),
                        panel.alignmentChannelIds(),
                        stitchingOrganization);

            } catch (Exception e) {
                logger.error("Error creating result", e);
                return null;
            }
        }
    }
}
