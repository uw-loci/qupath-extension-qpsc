package qupath.ext.qpsc.ui.stagemap;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MinorFunctions;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.StageImageTransform;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Floating window displaying a visual map of the microscope stage.
 * <p>
 * Features:
 * <ul>
 *   <li>Non-modal window that stays above QuPath main window but allows dialogs on top</li>
 *   <li>Real-time crosshair showing current objective position</li>
 *   <li>Camera FOV rectangle display</li>
 *   <li>Switchable insert configurations (single slide, multi-slide)</li>
 *   <li>Double-click navigation with safety confirmation</li>
 * </ul>
 * <p>
 * Uses a singleton pattern to ensure only one window instance exists.
 * The window is owned by the QuPath main window, ensuring it stays visible above
 * the main window while allowing modal dialogs to appear on top when needed.
 */
public class StageMapWindow {

    private static final Logger logger = LoggerFactory.getLogger(StageMapWindow.class);

    // ========== Singleton ==========
    private static StageMapWindow instance;

    // ========== Window Components ==========
    private Stage stage;
    private StageMapCanvas canvas;
    private ComboBox<StageInsert> insertComboBox;
    /**
     * Source-microscope dropdown. Lists distinct source scanners that have a saved alignment
     * preset for the current target microscope. The selected source is what drives macro overlay
     * positioning and Apply-Flips orientation; we look up the most-recent matching preset under
     * the hood and store it in {@link #activePreset}.
     */
    private ComboBox<String> sourceComboBox;
    /** Active preset resolved from {sourceComboBox value, current target microscope}. */
    private AffineTransformManager.TransformPreset activePreset;
    /** Suppresses side effects (metadata write, persistent pref) when setting source programmatically. */
    private boolean suppressSourceSelectionWrite = false;

    private boolean suppressFlipCheckboxListener = false;
    /** Apply Flips checkbox -- promoted to a field so init/source-change paths can sync its state. */
    private CheckBox applyFlipsCheckbox;

    private Label positionLabel;
    private Label targetLabel;
    private Label statusLabel;
    private Label movementWarningLabel;

    // ========== State ==========
    private ScheduledExecutorService positionPoller;
    private volatile boolean isPolling = false;
    private volatile boolean dialogShowing = false; // Pause updates while dialogs are shown
    private volatile int consecutiveErrors = 0; // Track polling failures
    private static final int MAX_CONSECUTIVE_ERRORS = 10; // Pause polling after this many errors
    private static boolean movementWarningShownThisSession = false;
    // Once-per-session acknowledgement for clicking past the imaging well into
    // the surrounding dish body (inserts that declare an outer outline, e.g. a
    // 35mm petri dish around a 14/20mm well). Moving here is legitimate -- it is
    // needed to calibrate the well edge -- so we caution once, then allow it.
    private static boolean outsideApertureWarningShownThisSession = false;

    // ========== Macro Overlay State ==========
    private CheckBox macroOverlayCheckbox;
    private CheckBox showAcquisitionsCheckbox;
    /** Per-image visibility dropdown (one CheckMenuItem per loaded acquisition). */
    private MenuButton acquisitionVisibilityMenu;

    private BufferedImage currentMacroImage = null;
    private AffineTransform currentMacroTransform = null;
    private String currentMacroSampleName = null;
    private String currentMacroScannerName = null;
    // Anchor metadata from the active preset (NaN for legacy presets, in which
    // case StageMapCanvas falls back to its 4-corner / config-offset paths).
    private double currentMacroAnchorMacroX = Double.NaN;
    private double currentMacroAnchorMacroY = Double.NaN;
    private double currentMacroAnchorStageX = Double.NaN;
    private double currentMacroAnchorStageY = Double.NaN;
    private double currentMacroPresetPixelSizeUm = Double.NaN;
    private ChangeListener<ImageData<?>> imageChangeListener = null;

    // ========== Configuration ==========
    // Poll interval for position updates - lower = more responsive but more network traffic
    // 200ms provides smooth tracking without overwhelming the socket connection
    private static final long POLL_INTERVAL_MS = 200;
    private static final double WINDOW_WIDTH = 680;
    private static final double WINDOW_HEIGHT = 400;
    private static final double CANVAS_WIDTH = 600;
    private static final double CANVAS_HEIGHT = 260;

    private StageMapWindow() {
        buildUI();
        loadInsertConfigurations();
        loadTransformPresets();
    }

    /**
     * Shows the Stage Map window, creating it if necessary.
     * If already visible, brings it to front.
     */
    public static void show() {
        Platform.runLater(() -> {
            if (instance == null) {
                instance = new StageMapWindow();
            }

            if (!instance.stage.isShowing()) {
                instance.stage.show();
                instance.applyInitialFlipState();
                instance.startPositionPolling();
            } else {
                instance.stage.toFront();
            }
        });
    }

    /**
     * Multi-slide orientation check: show the Stage Map (opening/focusing it) and render the
     * given macros over their slots at the chosen rotations, driven by the Multi-Slide dialog.
     * This is a passive VIEWER call -- the controls live in the MS dialog. While a preview is
     * active, Apply Flips is forced OFF and greyed so the displayed macro is in its raw
     * orientation (what the software will store), and restored on {@link #clearSlotMacroPreviews}.
     */
    public static void previewSlotMacros(
            StageInsert carrier, java.util.List<StageMapCanvas.SlotMacroPreview> previews) {
        Platform.runLater(() -> {
            if (instance == null) {
                instance = new StageMapWindow();
            }
            if (!instance.stage.isShowing()) {
                instance.stage.show();
                instance.applyInitialFlipState();
                instance.startPositionPolling();
            } else {
                instance.stage.toFront();
            }
            instance.applySlotPreviews(carrier, previews);
        });
    }

    /** Clears the multi-slide macro preview and restores the Apply Flips control. */
    public static void clearSlotMacroPreviews() {
        Platform.runLater(() -> {
            if (instance != null) {
                instance.clearSlotPreviews();
            }
        });
    }

    /**
     * Ends the orientation check but KEEPS the slot previews visible, so they remain as a
     * layout reference through the per-slide alignments that follow. Re-enables the Apply
     * Flips control (leaving it off, matching the raw-orientation previews).
     */
    public static void finishOrientationCheck() {
        Platform.runLater(() -> {
            if (instance != null) {
                instance.finishOrientationCheckInternal();
            }
        });
    }

    private void finishOrientationCheckInternal() {
        if (applyFlipsCheckbox != null) {
            applyFlipsCheckbox.setDisable(false);
        }
        // Leave savedApplyFlipsState cleared and the checkbox OFF -- the previews are drawn in
        // raw orientation and are not re-projected by Apply Flips, so keeping it off avoids a
        // preview/map desync during the alignment step. Previews stay visible.
        savedApplyFlipsState = null;
    }

    /** Prior Apply Flips state saved while a slot preview forces it off; null when inactive. */
    private Boolean savedApplyFlipsState;

    private void applySlotPreviews(StageInsert carrier, java.util.List<StageMapCanvas.SlotMacroPreview> previews) {
        // Sync the map to the carrier so slot indices/rects in the previews match what is
        // drawn. Prefer selecting the matching dropdown item (fires the canvas + overlay
        // update); fall back to setting the insert directly.
        if (carrier != null) {
            StageInsert match = null;
            if (insertComboBox != null) {
                for (StageInsert i : insertComboBox.getItems()) {
                    if (i != null && i.getId().equals(carrier.getId())) {
                        match = i;
                        break;
                    }
                }
            }
            if (match != null) {
                insertComboBox.getSelectionModel().select(match);
            } else if (canvas != null) {
                canvas.setInsert(carrier);
            }
        }
        // Save + force Apply Flips off (greyed) once, on entering the orientation check.
        if (savedApplyFlipsState == null && applyFlipsCheckbox != null) {
            savedApplyFlipsState = applyFlipsCheckbox.isSelected();
            applyFlipsCheckbox.setSelected(false);
            applyFlipsCheckbox.setDisable(true);
        }
        if (canvas != null) {
            canvas.setSlotMacroPreviews(previews);
        }
    }

    private void clearSlotPreviews() {
        if (canvas != null) {
            canvas.clearSlotMacroPreviews();
        }
        // Restore Apply Flips to its pre-check state.
        if (savedApplyFlipsState != null && applyFlipsCheckbox != null) {
            applyFlipsCheckbox.setDisable(false);
            applyFlipsCheckbox.setSelected(savedApplyFlipsState);
        }
        savedApplyFlipsState = null;
    }

    /**
     * Hides the Stage Map window.
     */
    public static void hide() {
        Platform.runLater(() -> {
            if (instance != null) {
                instance.dispose();
            }
        });
    }

    /**
     * Checks if the Stage Map window is currently visible.
     */
    public static boolean isVisible() {
        return instance != null && instance.stage != null && instance.stage.isShowing();
    }

    /**
     * Shows a translucent rectangle on the Stage Map marking the bounding-box region
     * a workflow would acquire. No-op if the Stage Map window is not open.
     */
    public static void setBoundingBoxPreview(double minStageX, double minStageY, double maxStageX, double maxStageY) {
        Platform.runLater(() -> {
            if (isVisible() && instance.canvas != null) {
                instance.canvas.setBoundingBoxPreview(minStageX, minStageY, maxStageX, maxStageY);
            }
        });
    }

    /** Hides the bounding-box preview rectangle, if any. */
    public static void clearBoundingBoxPreview() {
        Platform.runLater(() -> {
            if (instance != null && instance.canvas != null) {
                instance.canvas.clearBoundingBoxPreview();
            }
        });
    }

    /**
     * Shows a translucent bright-orange dashed rectangle on the Stage Map marking
     * the area a SIFT auto-align is searching, pinned to the live crosshair so it
     * tracks stage motion during the alignment step. Sized by the given
     * half-extents (microns) and seeded at {@code seedCenterX/Y}. No-op if the
     * Stage Map window is not open.
     */
    public static void setSearchRangeFollow(
            double seedCenterX, double seedCenterY, double halfWidthUm, double halfHeightUm) {
        Platform.runLater(() -> {
            if (isVisible() && instance.canvas != null) {
                instance.canvas.setSearchRangeFollow(seedCenterX, seedCenterY, halfWidthUm, halfHeightUm);
            }
        });
    }

    /** Hides the SIFT search-range preview rectangle, if any. */
    public static void clearSearchRangePreview() {
        Platform.runLater(() -> {
            if (instance != null && instance.canvas != null) {
                instance.canvas.clearSearchRangePreview();
            }
        });
    }

    /**
     * Disposes of the window and resets the singleton for a clean re-open.
     * This prevents stale state issues when reopening after the microscope
     * disconnects or Live Mode is toggled.
     */
    private void dispose() {
        logger.debug("Disposing Stage Map window");

        // Unregister image change listener first
        unregisterImageChangeListener();

        // Stop polling
        stopPositionPolling();

        // Disable canvas rendering to prevent further texture operations
        if (canvas != null) {
            canvas.setRenderingEnabled(false);
        }

        // Hide and close the stage
        if (stage != null) {
            stage.hide();
            stage.close();
        }

        // Reset singleton so next show() creates a fresh instance
        instance = null;

        logger.info("Stage Map window disposed - will create fresh instance on next open");
    }

    private void buildUI() {
        stage = new Stage();
        stage.setTitle("Stage Map");
        stage.initModality(Modality.NONE);

        // Set owner to QuPath main window - this keeps Stage Map above the main window
        // but allows modal dialogs to appear on top (unlike setAlwaysOnTop which blocks everything)
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            stage.initOwner(gui.getStage());
            logger.debug("Stage Map window owner set to QuPath main window");
        } else {
            // Fallback to alwaysOnTop if QuPath stage not available (shouldn't normally happen)
            stage.setAlwaysOnTop(true);
            logger.warn("Could not get QuPath main stage, using alwaysOnTop as fallback");
        }

        stage.setResizable(true);
        stage.setMinWidth(300);
        stage.setMinHeight(280);

        // Main layout
        VBox root = new VBox(8);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #2b2b2b;");

        // Top controls: Insert selector
        HBox topBar = buildTopBar();

        // Canvas for stage visualization (new WritableImage + Shapes implementation)
        canvas = new StageMapCanvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvas.setClickHandler(this::handleCanvasClick);

        // Set minimum canvas size so the slide is always visible at a usable scale.
        // Below this size, scroll bars appear instead of shrinking further.
        canvas.setMinSize(350, 200);

        // The canvas is now a StackPane that resizes with its container
        // Just place it in a container that grows with the window
        StackPane canvasContainer = new StackPane(canvas);
        canvasContainer.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #555; -fx-border-width: 1;");

        // Wrap in ScrollPane so users can scroll when the window is too small
        ScrollPane scrollPane = new ScrollPane(canvasContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background: #1a1a1a; -fx-background-color: #1a1a1a;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Notify canvas when container size changes
        canvasContainer.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (Math.abs(newVal.doubleValue() - oldVal.doubleValue()) > 1) {
                canvas.onSizeChanged();
            }
        });
        canvasContainer.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (Math.abs(newVal.doubleValue() - oldVal.doubleValue()) > 1) {
                canvas.onSizeChanged();
            }
        });

        // Bottom status bar
        HBox bottomBar = buildBottomBar();

        root.getChildren().addAll(topBar, scrollPane, bottomBar);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets()
                .add(
                        getClass().getResource("/qupath/ext/qpsc/ui/stagemap/stagemap.css") != null
                                ? getClass()
                                        .getResource("/qupath/ext/qpsc/ui/stagemap/stagemap.css")
                                        .toExternalForm()
                                : "");

        stage.setScene(scene);

        // Stop polling when window is closed
        stage.setOnCloseRequest(e -> dispose());

        // Register image change listener for macro overlay availability
        registerImageChangeListener();
    }

    private HBox buildTopBar() {
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label insertLabel = new Label("Insert:");
        insertLabel.setStyle("-fx-text-fill: #ccc;");
        insertLabel.setTooltip(new Tooltip("Stage insert / slide holder type.\n"
                + "Defines the aperture dimensions and slide positions.\n"
                + "Configured in the YAML config file."));

        insertComboBox = new ComboBox<>();
        insertComboBox.setPrefWidth(200);
        insertComboBox.setOnAction(e -> {
            StageInsert selected = insertComboBox.getValue();
            if (selected != null) {
                canvas.setInsert(selected);
                PersistentPreferences.setStageMapInsert(selected.getId());
                logger.debug("Switched to insert configuration: {}", selected.getId());
            }
            updateMovementWarning();
            // Re-evaluate macro overlay availability: multi-slot holders disable it (the
            // single-macro overlay would span all slots), single-slide inserts re-enable it.
            checkMacroOverlayAvailability();
        });

        // Source microscope/scanner selector. Lists distinct source scanners that have an
        // alignment preset for the current target microscope. The dropdown drives macro overlay
        // orientation and the Apply-Flips toggle; the matching preset is resolved internally.
        Label presetLabel = new Label("Source:");
        presetLabel.setStyle("-fx-text-fill: #ccc;");
        presetLabel.setTooltip(new Tooltip("Source scanner that produced the macro image.\n"
                + "Used to look up the matching alignment preset for\n"
                + "this microscope, and to stamp source_microscope\n"
                + "metadata on the open image when missing."));

        sourceComboBox = new ComboBox<>();
        sourceComboBox.setPrefWidth(180);
        sourceComboBox.setTooltip(new Tooltip("Scanner whose macro image to overlay on the Stage Map.\n"
                + "Auto-syncs to the macro-owning ancestor's source on open\n"
                + "(e.g. a PPM sub of an Ocus40-macro parent will resolve to\n"
                + "Ocus40 so the parent's macro is shown).\n\n"
                + "Picking a value uses the (source -> this microscope) preset\n"
                + "for macro placement and the Apply Flips macro component.\n\n"
                + "This dropdown drives macro display only; it does NOT relabel\n"
                + "the open entry's source_microscope when that metadata is\n"
                + "already set by the acquisition workflow."));
        sourceComboBox.setOnAction(e -> {
            if (suppressSourceSelectionWrite) {
                return;
            }
            String selected = sourceComboBox.getValue();
            if (selected == null || selected.isEmpty()) {
                return;
            }
            applySourceSelection(selected, /* writeMetadata */ true);
        });

        // Movement direction warning label - shows when Live View controls move opposite to expected
        movementWarningLabel = new Label();
        movementWarningLabel.setStyle("-fx-text-fill: orange; -fx-font-size: 10px; -fx-font-weight: bold;");
        movementWarningLabel.setVisible(false);
        movementWarningLabel.setManaged(false);

        // Update warning when Sample Movement checkbox is toggled in Live Viewer
        PersistentPreferences.stageControlSampleMovementProperty()
                .addListener((obs, oldVal, newVal) -> Platform.runLater(this::updateMovementWarning));

        // Button to open config folder for calibration
        Button configButton = new Button("Config");
        configButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
        configButton.setTooltip(new Tooltip("Open the configuration folder to edit calibration values.\n"
                + "Edit the YAML file to set aperture and slide reference points."));
        configButton.setOnAction(e -> openConfigFolder());

        // Calibrate button - drive-to-point capture of the selected insert's
        // reference points, written straight back to the YAML.
        Button calibrateButton = new Button("Calibrate...");
        calibrateButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
        calibrateButton.setTooltip(new Tooltip("Calibrate the selected insert's reference points.\n"
                + "Drive the stage to each point (e.g. a petri-dish well edge)\n"
                + "in Live View and capture the position; values are saved\n"
                + "back to the microscope YAML and the map reloads."));
        calibrateButton.setOnAction(e -> calibrateSelectedInsert());

        // Reload button - re-reads YAML config and updates display
        Button reloadButton = new Button("Reload");
        reloadButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
        reloadButton.setTooltip(new Tooltip("Reload the YAML configuration from disk.\n"
                + "Updates insert positions, slide dimensions,\n"
                + "and stage limits without restarting.\n\n"
                + "Use this after editing the config file."));
        reloadButton.setOnAction(e -> reloadConfiguration());

        // Reset View button - undo zoom/pan back to the fit-to-insert view.
        // Same action as right-click on the canvas, surfaced as a button for discoverability.
        Button resetViewButton = new Button("Reset View");
        resetViewButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
        resetViewButton.setTooltip(new Tooltip(
                "Reset zoom and pan back to the fit-to-insert view.\n" + "(Same as right-clicking the map.)"));
        resetViewButton.setOnAction(e -> {
            if (canvas != null) {
                canvas.resetView();
            }
        });

        // Help button - opens online documentation
        Button helpButton = DocumentationHelper.createHelpButton("stageMap");
        if (helpButton != null) {
            helpButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
            helpButton.setTooltip(new Tooltip("Open online documentation for the Stage Map.\n\n"
                    + "Quick reference:\n"
                    + "- Green crosshair: Current objective position\n"
                    + "- Orange rectangle: Camera field of view\n"
                    + "- Blue rectangles: Slide positions\n"
                    + "- Double-click to move the stage\n"
                    + "- Mouse wheel to zoom (on cursor), drag to pan,\n"
                    + "  right-click to reset the view"));
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Macro overlay checkbox
        macroOverlayCheckbox = new CheckBox("Overlay Macro");
        macroOverlayCheckbox.setStyle("-fx-text-fill: #ccc;");
        macroOverlayCheckbox.setTooltip(new Tooltip("Display the cropped macro image from alignment\n"
                + "over the stage map at its calibrated position.\n\n"
                + "This shows the slide-only portion of the macro\n"
                + "(with slide holder and background removed)\n"
                + "that was saved during Microscope Alignment.\n\n"
                + "Only available for images with saved alignments."));
        macroOverlayCheckbox.setDisable(true); // Initially disabled until macro is available
        macroOverlayCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            logger.info("Overlay Macro checkbox toggled: {} -> {}", oldVal, newVal);
            if (canvas != null) {
                if (newVal && currentMacroImage != null && currentMacroTransform != null) {
                    logger.info(
                            "Applying macro overlay (image: {}x{}, sample: '{}')",
                            currentMacroImage.getWidth(),
                            currentMacroImage.getHeight(),
                            currentMacroSampleName);
                    applyMacroOverlayToCanvas();
                } else {
                    if (newVal) {
                        logger.info(
                                "Checkbox selected but no macro data available (image={}, transform={})",
                                currentMacroImage != null,
                                currentMacroTransform != null);
                    }
                    canvas.clearMacroOverlay();
                }
            }
        });

        // Apply Flips checkbox -- flips the map, overlay, and coordinate system
        applyFlipsCheckbox = new CheckBox("Apply Flips");
        applyFlipsCheckbox.setStyle("-fx-text-fill: #C62828; -fx-font-weight: bold; "
                + "-fx-border-color: #C62828; -fx-border-width: 1; -fx-border-radius: 2; "
                + "-fx-padding: 2 4;");
        applyFlipsCheckbox.setTooltip(new Tooltip("Flip the Stage Map to match the Live Viewer orientation.\n\n"
                + "Default state composes:\n"
                + "  - Active scope's Camera Orientation (the map already\n"
                + "    applies Stage Polarity, so only the camera flip is\n"
                + "    needed to reach Live Viewer orientation)\n"
                + "  - The macro overlay's per-scanner flipMacroX/Y from\n"
                + "    the source picked in the Source dropdown\n"
                + "XOR'd per-axis so the whole map -- including the macro\n"
                + "overlay -- ends up oriented like the Live Viewer.\n\n"
                + "Independent of which project image is open. Toggleable\n"
                + "if you want to compare against the un-flipped view."));

        // Initial checked state -- pulled from open entry metadata if any. Preset dropdown
        // is not yet populated at this point in construction, so the dropdown leg of
        // resolveCurrentFlipAxes() is a no-op until applyInitialFlipState runs after show().
        boolean[] initialAxes = resolveCurrentFlipAxes();
        applyFlipsCheckbox.setSelected(initialAxes[0] || initialAxes[1]);

        applyFlipsCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            // Programmatic setSelected from applySourceSelection / applyInitialFlipState
            // already calls canvas.setFlipsApplied directly with the resolved axes;
            // re-running here would just repeat the work and log an extra "toggled" line.
            if (suppressFlipCheckboxListener) return;
            logger.info("Apply Flips toggled: {}", newVal);
            if (canvas != null) {
                // Resolve actual flip axes from the live context so the canvas uses the right
                // mirror direction. Priority: open image entry metadata, then active preset.
                boolean[] axes = resolveCurrentFlipAxes();
                canvas.setFlipsApplied(newVal, axes[0], axes[1]);
            }
        });

        // NOTE: Initial flip state is applied in show() after the stage is visible.
        // Calling setScaleX/Y during construction doesn't take effect because the
        // StackPane isn't in the scene graph yet, and Platform.runLater from the
        // constructor fires before show() is called.

        topBar.getChildren()
                .addAll(
                        insertLabel,
                        insertComboBox,
                        presetLabel,
                        sourceComboBox,
                        spacer,
                        applyFlipsCheckbox,
                        macroOverlayCheckbox,
                        configButton,
                        calibrateButton,
                        reloadButton,
                        resetViewButton,
                        helpButton);
        return topBar;
    }

    /**
     * Updates the movement direction warning label based on the current insert's
     * axis inversion settings and the Sample Movement checkbox state.
     *
     * Movement is "opposite" when Live View stage controls move the image in the
     * reverse direction from what the user expects:
     * - X is opposite when the X axis is inverted AND Sample Movement is enabled
     * - Y is opposite when the Y axis is inverted (regardless of Sample Movement)
     */
    private void updateMovementWarning() {
        StageInsert insert = insertComboBox.getValue();
        if (insert == null) {
            movementWarningLabel.setVisible(false);
            movementWarningLabel.setManaged(false);
            return;
        }

        boolean sampleMovement = PersistentPreferences.getStageControlSampleMovement();
        boolean xOpposite = insert.isXAxisInverted() && sampleMovement;
        boolean yOpposite = insert.isYAxisInverted();

        if (!xOpposite && !yOpposite) {
            movementWarningLabel.setVisible(false);
            movementWarningLabel.setManaged(false);
            return;
        }

        String axes;
        if (xOpposite && yOpposite) {
            axes = "X and Y";
        } else if (xOpposite) {
            axes = "X";
        } else {
            axes = "Y";
        }

        movementWarningLabel.setText("Movement in Live View in [" + axes + "] Opposite Live View Controls");
        movementWarningLabel.setVisible(true);
        movementWarningLabel.setManaged(true);
    }

    /**
     * Opens the folder containing the microscope configuration file.
     */
    private void openConfigFolder() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isEmpty()) {
                showWarning(
                        "No Config File",
                        "No microscope configuration file is set.\n" + "Please set one in QuPath preferences.");
                return;
            }

            File configFile = new File(configPath);
            File configFolder = configFile.getParentFile();

            if (configFolder == null || !configFolder.exists()) {
                showWarning("Folder Not Found", "Configuration folder not found:\n" + configPath);
                return;
            }

            if (!UIFunctions.revealInFileBrowser(configFile)) {
                // Last-resort fallback: show the path in a dialog if no
                // platform reveal mechanism is available.
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Configuration Folder");
                alert.setHeaderText("Open this folder to edit calibration:");
                alert.setContentText(configFolder.getAbsolutePath());
                UIFunctions.showAlertOverParent(alert, stage);
            } else {
                logger.info("Revealed config file: {}", configFile.getAbsolutePath());
            }

        } catch (Exception e) {
            logger.error("Failed to open config folder: {}", e.getMessage(), e);
            showError("Error", "Failed to open configuration folder:\n" + e.getMessage());
        }
    }

    /**
     * Opens the calibration dialog for the currently-selected insert. Lets the
     * user capture the insert's reference points from the live stage and saves
     * them back to the YAML, then reloads so the Stage Map reflects them.
     */
    @SuppressWarnings("unchecked")
    private void calibrateSelectedInsert() {
        try {
            StageInsert selected = insertComboBox.getValue();
            if (selected == null) {
                showWarning("No Insert", "Select an insert to calibrate first.");
                return;
            }
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isEmpty()) {
                showWarning(
                        "No Config File",
                        "No microscope configuration file is set.\n" + "Please set one in QuPath preferences.");
                return;
            }

            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            Map<String, Object> insertsConfig = configManager.getSection("stage", "inserts");
            Object configsObj = (insertsConfig != null) ? insertsConfig.get("configurations") : null;
            Map<String, Object> insertConfig = null;
            if (configsObj instanceof Map<?, ?> configs) {
                Object c = configs.get(selected.getId());
                if (c instanceof Map<?, ?> cm) {
                    insertConfig = (Map<String, Object>) cm;
                }
            }
            if (insertConfig == null) {
                showWarning(
                        "Not Calibratable",
                        "Insert '" + selected.getId() + "' is synthesized from stage limits and has no\n"
                                + "editable reference points in the config.\n\n"
                                + "Only inserts declared under stage.inserts.configurations\n"
                                + "(e.g. the petri-dish carriers) can be calibrated here.");
                return;
            }

            // Non-modal: the window stays open so the user can keep driving the
            // stage to each point. The callback fires on each successful save.
            InsertCalibrationDialog.show(stage, configPath, selected.getId(), selected.getName(), insertConfig, () -> {
                reloadConfiguration();
                statusLabel.setText("Insert calibration saved");
                statusLabel.setStyle("-fx-text-fill: #6b6;");
            });
        } catch (Exception e) {
            logger.error("Insert calibration failed: {}", e.getMessage(), e);
            showError("Error", "Calibration failed:\n" + e.getMessage());
        }
    }

    /**
     * Reloads the YAML configuration from disk, updating insert layout,
     * slide positions, and stage limits without restarting.
     */
    private void reloadConfiguration() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isEmpty()) {
                statusLabel.setText("No config file set");
                statusLabel.setStyle("-fx-text-fill: #f66;");
                return;
            }

            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
            configManager.reload(configPath);

            // Tell the Python server to re-read the YAML too, so its
            // stage limits and other config stay in sync with Java.
            boolean serverReconfigOk = true;
            try {
                MicroscopeController.getInstance().sendReconfig();
            } catch (Exception reconfigEx) {
                serverReconfigOk = false;
                logger.warn("Server config reload failed (non-fatal): {}", reconfigEx.getMessage());
            }

            // Reload insert registry
            StageInsertRegistry.loadFromConfig(configManager);

            // Preserve current selection by ID
            String previousId = (insertComboBox.getValue() != null)
                    ? insertComboBox.getValue().getId()
                    : null;

            List<StageInsert> inserts = StageInsertRegistry.getAvailableInserts();
            insertComboBox.setItems(javafx.collections.FXCollections.observableArrayList(inserts));

            StageInsert restored = (previousId != null) ? StageInsertRegistry.getInsert(previousId) : null;
            if (restored == null) restored = StageInsertRegistry.getDefaultInsert();
            if (restored != null) {
                insertComboBox.setValue(restored);
                canvas.setInsert(restored);
            }

            updateMovementWarning();
            loadTransformPresets();

            if (macroOverlayCheckbox.isSelected() && currentMacroImage != null) {
                applyMacroOverlayToCanvas();
            }

            if (serverReconfigOk) {
                statusLabel.setText("Config reloaded");
                statusLabel.setStyle("-fx-text-fill: #6b6;");
            } else {
                statusLabel.setText("Config reloaded (server reconfig failed)");
                statusLabel.setStyle("-fx-text-fill: #f66;");
            }
            logger.info("Configuration reloaded from: {}", configPath);

        } catch (Exception e) {
            logger.error("Failed to reload configuration: {}", e.getMessage(), e);
            statusLabel.setText("Reload failed");
            statusLabel.setStyle("-fx-text-fill: #f66;");
        }
    }

    /**
     * Scans all project images for alignment transforms and loads thumbnails
     * on a background thread. Updates the canvas on the FX thread when done.
     */
    @SuppressWarnings("unchecked")
    private void loadAndPaintAcquisitions() {
        statusLabel.setText("Loading acquisitions...");
        statusLabel.setStyle("-fx-text-fill: #aaa;");

        Thread loader = new Thread(
                () -> {
                    try {
                        QuPathGUI gui = QuPathGUI.getInstance();
                        if (gui == null || gui.getProject() == null) {
                            Platform.runLater(() -> {
                                statusLabel.setText("No project open");
                                statusLabel.setStyle("-fx-text-fill: #f66;");
                                showAcquisitionsCheckbox.setSelected(false);
                            });
                            return;
                        }

                        Project<java.awt.image.BufferedImage> project =
                                (Project<java.awt.image.BufferedImage>) gui.getProject();

                        List<StageMapCanvas.AcquisitionThumbnail> thumbnails = new ArrayList<>();
                        int count = 0;

                        for (ProjectImageEntry<java.awt.image.BufferedImage> entry : project.getImageList()) {
                            String imageName = entry.getImageName();
                            String strippedName = qupath.lib.common.GeneralTools.stripExtension(imageName);

                            // Three-tier fallback to find a pixel->stage transform for this entry:
                            //   1. Macro-frame per-slide JSON (alignmentFiles/) -- hand-saved
                            //      via ManualAlignmentPath / ExistingAlignmentPath.
                            //   2. Sub-frame per-slide JSON (alignmentFiles/derived/) -- auto-
                            //      registered by StitchingHelper.autoRegisterBoundsTransformIfAvailable
                            //      for bounded acquisitions with stage-bounds in metadata.
                            //   3. STAGE_BOUNDS_* + STITCHER_FLIP_* stamped directly on the
                            //      project entry -- the self-contained record that survives
                            //      alignment-directory restructures. Catches annotation-based
                            //      acquisitions whose JSON wasn't written but whose stitched
                            //      output knows its stage rectangle.
                            // Without all three, Show Acquisitions reports 0 even when every
                            // entry has a perfectly good stage record.
                            AffineTransform alignment =
                                    AffineTransformManager.loadSlideAlignment(project, strippedName);
                            if (alignment == null) {
                                alignment = AffineTransformManager.loadDerivedAlignment(project, strippedName);
                            }

                            try (var server = entry.readImageData().getServer()) {
                                int imgW = server.getWidth();
                                int imgH = server.getHeight();
                                if (alignment == null) {
                                    alignment = ImageMetadataManager.buildBoundingBoxPixelToStageTransform(
                                            entry, imgW, imgH);
                                }
                                if (alignment == null) continue;
                                double downsample = Math.max(1.0, Math.max(imgW, imgH) / 200.0);

                                var request = qupath.lib.regions.RegionRequest.createInstance(
                                        server.getPath(), downsample, 0, 0, imgW, imgH);
                                java.awt.image.BufferedImage thumb = server.readRegion(request);
                                if (thumb == null) continue;

                                thumb = normalizeImage(thumb);

                                // Compute stage bounds from all 4 corners
                                double[] corners = {0, 0, imgW, 0, imgW, imgH, 0, imgH};
                                double[] stageCorners = new double[8];
                                alignment.transform(corners, 0, stageCorners, 0, 4);

                                double minX = Math.min(
                                        Math.min(stageCorners[0], stageCorners[2]),
                                        Math.min(stageCorners[4], stageCorners[6]));
                                double maxX = Math.max(
                                        Math.max(stageCorners[0], stageCorners[2]),
                                        Math.max(stageCorners[4], stageCorners[6]));
                                double minY = Math.min(
                                        Math.min(stageCorners[1], stageCorners[3]),
                                        Math.min(stageCorners[5], stageCorners[7]));
                                double maxY = Math.max(
                                        Math.max(stageCorners[1], stageCorners[3]),
                                        Math.max(stageCorners[5], stageCorners[7]));

                                thumbnails.add(new StageMapCanvas.AcquisitionThumbnail(
                                        imageName, thumb, minX, minY, maxX, maxY));
                                count++;
                            } catch (Exception e) {
                                logger.debug("Failed to load thumbnail for '{}': {}", imageName, e.getMessage());
                            }
                        }

                        final int finalCount = count;
                        final int totalEntries = project.getImageList().size();
                        final List<StageMapCanvas.AcquisitionThumbnail> finalThumbs = thumbnails;
                        logger.info(
                                "Show Acquisitions scan complete: {}/{} project entries have a usable alignment "
                                        + "(macro-frame JSON, sub-frame JSON, or STAGE_BOUNDS_* metadata on the entry)",
                                finalCount,
                                totalEntries);

                        Platform.runLater(() -> {
                            canvas.setAcquisitionThumbnails(finalThumbs);
                            canvas.setAcquisitionOverlayVisible(true);
                            populateAcquisitionVisibilityMenu(finalThumbs);
                            statusLabel.setText(finalCount + " acquisitions loaded");
                            statusLabel.setStyle("-fx-text-fill: #6b6;");
                        });

                    } catch (Exception e) {
                        logger.error("Failed to load acquisition thumbnails: {}", e.getMessage(), e);
                        Platform.runLater(() -> {
                            statusLabel.setText("Load failed");
                            statusLabel.setStyle("-fx-text-fill: #f66;");
                            showAcquisitionsCheckbox.setSelected(false);
                        });
                    }
                },
                "StageMap-AcquisitionLoader");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Per-image brightness normalization: stretches min/max to 0-255 range
     * so thumbnails from different modalities and exposures are all visible.
     */
    private static java.awt.image.BufferedImage normalizeImage(java.awt.image.BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();

        int globalMin = 255;
        int globalMax = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int avg = (r + g + b) / 3;
                globalMin = Math.min(globalMin, avg);
                globalMax = Math.max(globalMax, avg);
            }
        }

        if (globalMax <= globalMin) return img;

        double scale = 255.0 / (globalMax - globalMin);

        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int nr = (int) Math.min(255, Math.max(0, (((rgb >> 16) & 0xFF) - globalMin) * scale));
                int ng = (int) Math.min(255, Math.max(0, (((rgb >> 8) & 0xFF) - globalMin) * scale));
                int nb = (int) Math.min(255, Math.max(0, ((rgb & 0xFF) - globalMin) * scale));
                out.setRGB(x, y, (255 << 24) | (nr << 16) | (ng << 8) | nb);
            }
        }
        return out;
    }

    private HBox buildBottomBar() {
        HBox bottomBar = new HBox(10);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(5, 0, 0, 0));

        positionLabel = new Label("Position: -- , --");
        positionLabel.setStyle("-fx-text-fill: #aaa; -fx-font-family: monospace;");

        targetLabel = new Label("");
        targetLabel.setStyle("-fx-text-fill: #7ab; -fx-font-family: monospace;");

        // Acquisition overlay controls. Explicit text-fill is required: without it,
        // JavaFX's default checkbox label color is near-black, which disappears
        // against the dark stage-map background in dark mode.
        showAcquisitionsCheckbox = new CheckBox("Show Acquisitions");
        showAcquisitionsCheckbox.setStyle("-fx-text-fill: #ccc; -fx-font-size: 10;");
        showAcquisitionsCheckbox.setTooltip(new Tooltip("Show / hide the acquisition overlay.\n"
                + "First check scans the project for per-slide alignments\n"
                + "and paints a translucent thumbnail at each acquired\n"
                + "image's stage position. Unchecking hides the overlay\n"
                + "but keeps the cached thumbnails and the Images list\n"
                + "so re-checking is instant. Use Clear to drop the cache\n"
                + "and force a fresh project rescan next time."));
        showAcquisitionsCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (Boolean.TRUE.equals(newVal)) {
                if (canvas.hasLoadedAcquisitionThumbnails()) {
                    logger.info("Show Acquisitions toggled ON -- re-showing cached overlay (no rescan)");
                    canvas.setAcquisitionOverlayVisible(true);
                } else {
                    logger.info("Show Acquisitions toggled ON -- scanning project for per-slide alignments");
                    loadAndPaintAcquisitions();
                }
            } else {
                logger.info("Show Acquisitions toggled OFF -- hiding overlay (cache retained)");
                canvas.setAcquisitionOverlayVisible(false);
            }
        });

        // Per-image visibility dropdown: one CheckMenuItem per loaded thumbnail.
        // Right-click on the button surface offers Select All / Select None.
        acquisitionVisibilityMenu = new MenuButton("Images");
        acquisitionVisibilityMenu.setStyle("-fx-font-size: 10; -fx-padding: 1 4;");
        acquisitionVisibilityMenu.setTooltip(new Tooltip(
                "Toggle visibility of individual acquired images.\n" + "Right-click for Select All / Select None."));
        acquisitionVisibilityMenu.setDisable(true);
        ContextMenu visibilityCtx = new ContextMenu();
        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setOnAction(e -> setAllVisibilityChecks(true));
        MenuItem selectNoneItem = new MenuItem("Select None");
        selectNoneItem.setOnAction(e -> setAllVisibilityChecks(false));
        visibilityCtx.getItems().addAll(selectAllItem, selectNoneItem);
        acquisitionVisibilityMenu.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                visibilityCtx.show(acquisitionVisibilityMenu, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });

        Button clearAcqButton = new Button("Clear");
        clearAcqButton.setStyle("-fx-font-size: 10; -fx-padding: 1 4;");
        clearAcqButton.setTooltip(new Tooltip("Drop the cached acquisition thumbnails and Images list.\n"
                + "Unlike unchecking Show Acquisitions (which just hides\n"
                + "the overlay and keeps the cache), Clear frees memory\n"
                + "and forces a fresh project rescan the next time you\n"
                + "re-enable Show Acquisitions. Use this after acquiring\n"
                + "new images that should appear in the overlay."));
        clearAcqButton.setOnAction(e -> {
            canvas.clearAcquisitionOverlay();
            showAcquisitionsCheckbox.setSelected(false);
            acquisitionVisibilityMenu.getItems().clear();
            acquisitionVisibilityMenu.setDisable(true);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #888;");

        bottomBar
                .getChildren()
                .addAll(
                        positionLabel,
                        targetLabel,
                        showAcquisitionsCheckbox,
                        acquisitionVisibilityMenu,
                        clearAcqButton,
                        spacer,
                        movementWarningLabel,
                        statusLabel);
        return bottomBar;
    }

    /**
     * Toggle every CheckMenuItem in the acquisition visibility menu. Used by the
     * right-click Select All / Select None affordance on the dropdown.
     */
    private void setAllVisibilityChecks(boolean selected) {
        for (MenuItem mi : acquisitionVisibilityMenu.getItems()) {
            if (mi instanceof CheckMenuItem cmi) cmi.setSelected(selected);
        }
        publishVisibilityToCanvas();
    }

    /**
     * Rebuild the per-image visibility menu from the freshly-loaded thumbnails.
     * Defaults all entries to selected so existing behaviour (everything visible)
     * is preserved on first load.
     */
    private void populateAcquisitionVisibilityMenu(List<StageMapCanvas.AcquisitionThumbnail> thumbnails) {
        acquisitionVisibilityMenu.getItems().clear();
        if (thumbnails == null || thumbnails.isEmpty()) {
            acquisitionVisibilityMenu.setDisable(true);
            return;
        }
        for (StageMapCanvas.AcquisitionThumbnail t : thumbnails) {
            CheckMenuItem item = new CheckMenuItem(t.imageName);
            item.setSelected(true);
            item.selectedProperty().addListener((obs, was, now) -> publishVisibilityToCanvas());
            acquisitionVisibilityMenu.getItems().add(item);
        }
        acquisitionVisibilityMenu.setDisable(false);
        // Push the (all-selected) initial state so the canvas filter is in sync.
        publishVisibilityToCanvas();
    }

    /**
     * Push the current per-image visibility selection from the dropdown to the
     * canvas. Empty selection hides everything; full selection clears the filter
     * (null = "no filter, show all").
     */
    private void publishVisibilityToCanvas() {
        Set<String> visible = new LinkedHashSet<>();
        int total = 0;
        for (MenuItem mi : acquisitionVisibilityMenu.getItems()) {
            if (mi instanceof CheckMenuItem cmi) {
                total++;
                if (cmi.isSelected()) visible.add(cmi.getText());
            }
        }
        canvas.setVisibleAcquisitionImages(visible.size() == total ? null : visible);
    }

    private void loadInsertConfigurations() {
        try {
            // Load configurations from YAML
            MicroscopeConfigManager configManager =
                    MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

            if (configManager != null) {
                StageInsertRegistry.loadFromConfig(configManager);
            }

            List<StageInsert> inserts = StageInsertRegistry.getAvailableInserts();
            insertComboBox.setItems(FXCollections.observableArrayList(inserts));

            // Restore the last-used insert (persisted), falling back to the config default.
            String savedId = PersistentPreferences.getStageMapInsert();
            StageInsert initial =
                    (savedId != null && !savedId.isEmpty()) ? StageInsertRegistry.getInsert(savedId) : null;
            if (initial == null) {
                initial = StageInsertRegistry.getDefaultInsert();
            }
            if (initial != null) {
                insertComboBox.setValue(initial);
                canvas.setInsert(initial);
            }

            logger.info("Loaded {} insert configurations", inserts.size());

            // Set initial movement warning state based on default insert
            updateMovementWarning();

        } catch (Exception e) {
            logger.error("Error loading insert configurations: {}", e.getMessage(), e);
            statusLabel.setText("Config error");
            statusLabel.setStyle("-fx-text-fill: #f66;");
        }
    }

    /**
     * Populates the Source dropdown with the distinct source scanners that have at least one
     * alignment preset for the current target microscope. Tries (in order) the open entry's
     * {@code source_microscope} metadata, then the persistent default, then the first listed
     * source as the initial selection.
     */
    private void loadTransformPresets() {
        if (sourceComboBox == null) return;

        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            logger.info("Source dropdown: loading from configPath='{}'", configPath);
            if (configPath == null || configPath.isEmpty()) {
                logger.info("Source dropdown: no microscope config -- disabled");
                sourceComboBox.setDisable(true);
                sourceComboBox.setTooltip(new Tooltip(
                        "No microscope configuration loaded.\n" + "Set a configuration file in QuPath preferences."));
                return;
            }

            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            String targetMicroscope = (mgr != null) ? mgr.getMicroscopeName() : null;
            logger.info("Source dropdown: target microscope = '{}'", targetMicroscope);

            File configDir = new File(configPath).getParentFile();
            AffineTransformManager transformManager = new AffineTransformManager(configDir.getAbsolutePath());

            List<String> sources = new ArrayList<>();
            if (targetMicroscope != null && !"Unknown".equals(targetMicroscope)) {
                // The active microscope is itself a valid source: source==target means
                // identity (no pair preset, no flip). This is the default case for
                // images acquired on this scope. Prepend it so it's the natural
                // first choice; pair-preset scanners follow for genuine cross-scope.
                sources.add(targetMicroscope);
                transformManager.getDistinctSourceScannersForMicroscope(targetMicroscope).stream()
                        .filter(s -> !s.equals(targetMicroscope))
                        .forEach(sources::add);
            } else {
                transformManager.getAllTransforms().stream()
                        .map(AffineTransformManager.TransformPreset::getSourceScanner)
                        .filter(s -> s != null && !s.isEmpty())
                        .distinct()
                        .sorted()
                        .forEach(sources::add);
            }
            logger.info(
                    "Source dropdown: {} scanner(s) available for target '{}': {}",
                    sources.size(),
                    targetMicroscope,
                    sources);

            if (sources.isEmpty()) {
                sourceComboBox.setDisable(true);
                sourceComboBox.setTooltip(new Tooltip("No source scanners with an alignment for this microscope.\n"
                        + "Create one via Microscope Alignment workflow."));
                return;
            }

            sourceComboBox.setDisable(false);
            suppressSourceSelectionWrite = true;
            try {
                sourceComboBox.setItems(FXCollections.observableArrayList(sources));
            } finally {
                suppressSourceSelectionWrite = false;
            }

            String initial = pickInitialSource(sources);
            if (initial != null) {
                applySourceSelection(initial, /* writeMetadata */ false);
            }
        } catch (Exception e) {
            logger.error("Error loading source scanner list: {}", e.getMessage(), e);
            sourceComboBox.setDisable(true);
        }
    }

    /**
     * Picks the source to show on dropdown init. Priority:
     * <ol>
     *   <li>The persistent default
     *       ({@link PersistentPreferences#getSelectedScanner()}) -- the user's
     *       last explicit pick from a prior session. Wins so the dropdown is
     *       remembered across sessions.</li>
     *   <li>The macro-owning ancestor's {@code source_microscope} (walks
     *       {@code base_image} then {@code original_image_id} via
     *       {@link #resolveMacroSourceForEntry}); falls back to the open
     *       entry's own {@code source_microscope}</li>
     *   <li>The active microscope (same-scope identity is the natural default)</li>
     *   <li>First entry in {@code sources}</li>
     * </ol>
     *
     * <p>Reading the persistent default for display is safe; the
     * Ocus40-stamp-on-OWS3-slide bug was about <i>stamping</i> the
     * persistent default onto an entry, not about reading it. That stamp
     * path is now gated on "entry has no existing source_microscope" in
     * {@link #applySourceSelection}, and {@link #onOpenedImageChanged}
     * no longer auto-modifies the dropdown at all.
     */
    private String pickInitialSource(List<String> sources) {
        String fromPref = PersistentPreferences.getSelectedScanner();
        if (fromPref != null && !fromPref.isEmpty() && sources.contains(fromPref)) {
            logger.info("pickInitialSource: persistent default '{}' wins", fromPref);
            return fromPref;
        }
        try {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui != null && gui.getProject() != null && gui.getImageData() != null) {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                ProjectImageEntry<BufferedImage> entry = project.getEntry(gui.getImageData());
                if (entry != null) {
                    String fromEntry = resolveMacroSourceForEntry(project, entry);
                    if (fromEntry != null && sources.contains(fromEntry)) {
                        logger.info(
                                "pickInitialSource: macro-ancestor walk -> '{}' (no usable persistent default)",
                                fromEntry);
                        return fromEntry;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        String activeScope = (mgr != null) ? mgr.getMicroscopeName() : null;
        if (activeScope != null && sources.contains(activeScope)) {
            logger.info("pickInitialSource: active microscope '{}' wins", activeScope);
            return activeScope;
        }
        return sources.isEmpty() ? null : sources.get(0);
    }

    /**
     * Resolve which scanner's macro should be displayed for the given entry.
     * For derived entries (sub-acquisitions, flipped siblings), walks up to
     * the macro-owning ancestor via {@code base_image} then
     * {@code original_image_id}, returning that ancestor's
     * {@code source_microscope}. If no ancestor chain resolves, returns the
     * entry's own {@code source_microscope}. May return null.
     *
     * <p>Note: the result is independent of the open entry's own
     * {@code source_microscope} when the entry is derived from a different-
     * scanner parent (e.g. PPM sub of an Ocus40 macro). Callers must not
     * write the returned value back as the entry's {@code source_microscope}
     * -- that metadata reflects acquisition origin, not display intent.
     */
    private String resolveMacroSourceForEntry(Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry) {
        if (project == null || entry == null) return null;
        // base_image: stable name reference written by acquisition workflows.
        // The base entry is identified by image name match.
        String baseImage = ImageMetadataManager.getBaseImage(entry);
        if (baseImage != null && !baseImage.isEmpty()) {
            for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
                if (baseImage.equals(e.getImageName()) || baseImage.equals(stripExt(e.getImageName()))) {
                    String src = e.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
                    if (src != null && !src.isEmpty()) {
                        logger.info(
                                "resolveMacroSourceForEntry: entry='{}' -> base_image='{}' -> source_microscope='{}'",
                                entry.getImageName(),
                                baseImage,
                                src);
                        return src;
                    }
                    break;
                }
            }
        }
        // original_image_id: direct parent for flipped siblings or other
        // single-step derivations. Resolved by entry ID, not name.
        String originalId = ImageMetadataManager.getOriginalImageId(entry);
        if (originalId != null && !originalId.isEmpty()) {
            for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
                if (originalId.equals(e.getID())) {
                    String src = e.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
                    if (src != null && !src.isEmpty()) {
                        logger.info(
                                "resolveMacroSourceForEntry: entry='{}' -> original_image_id='{}' -> source_microscope='{}'",
                                entry.getImageName(),
                                originalId,
                                src);
                        return src;
                    }
                    break;
                }
            }
        }
        // Fallback: the entry's own source_microscope (covers the
        // base-entry case where no ancestor chain exists).
        return entry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
    }

    private static String stripExt(String name) {
        return name == null ? null : qupath.lib.common.GeneralTools.stripExtension(name);
    }

    /**
     * Apply a source-microscope selection: update the dropdown value (suppressing recursion),
     * resolve the matching preset, persist the default, optionally stamp metadata on the open
     * entry, and refresh dependent UI.
     *
     * @param source        source scanner name (must be one of the dropdown items)
     * @param writeMetadata when true, stamp {@code source_microscope} on the open entry and
     *                      sync the project; when false, only update UI state (used during
     *                      programmatic init / dropdown population)
     */
    private void applySourceSelection(String source, boolean writeMetadata) {
        suppressSourceSelectionWrite = true;
        try {
            sourceComboBox.setValue(source);
        } finally {
            suppressSourceSelectionWrite = false;
        }

        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                String target = (mgr != null) ? mgr.getMicroscopeName() : null;
                AffineTransformManager mgr2 = new AffineTransformManager(new File(configPath).getParent());
                activePreset = mgr2.getBestPresetForPair(source, target);
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve preset for source='{}': {}", source, e.getMessage());
            activePreset = null;
        }

        if (writeMetadata) {
            PersistentPreferences.setSelectedScanner(source);
            try {
                QuPathGUI gui = QuPathGUI.getInstance();
                if (gui != null && gui.getProject() != null && gui.getImageData() != null) {
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                    ProjectImageEntry<BufferedImage> entry = project.getEntry(gui.getImageData());
                    if (entry != null) {
                        String existing = entry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
                        if (existing == null || existing.isEmpty()) {
                            // Fresh-import path: no acquisition workflow ever set source_microscope,
                            // so picking from the dropdown is the user's way of marking origin.
                            entry.getMetadata().put(ImageMetadataManager.SOURCE_MICROSCOPE, source);
                            project.syncChanges();
                            logger.info(
                                    "Stamped source_microscope='{}' on open entry '{}' (was unset)",
                                    source,
                                    entry.getImageName());
                        } else if (!existing.equals(source)) {
                            // Entry already has an acquisition-set source_microscope. The dropdown
                            // is now a macro-display picker (it may resolve to an ancestor scanner
                            // distinct from the entry's own acquisition origin); we must not
                            // overwrite that origin from here. Re-stamping caused entry-origin
                            // corruption in the PPM-sub-of-Ocus40-parent case.
                            logger.info(
                                    "Source dropdown -> '{}' on entry '{}' (existing source_microscope='{}'); "
                                            + "leaving metadata untouched (Stage Map dropdown drives macro display only)",
                                    source,
                                    entry.getImageName(),
                                    existing);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to stamp source_microscope on open entry: {}", e.getMessage());
            }
        }

        logger.info(
                "Source selection: '{}' -> preset '{}'",
                source,
                activePreset != null ? activePreset.getName() : "(none)");

        syncApplyFlipsFromHardware("source change to '" + source + "'");
        checkMacroOverlayAvailability();
    }

    /**
     * Called when the QuPath viewer image changes. No-op for the Source
     * dropdown: it stays at whatever the user picked (or where
     * {@link #pickInitialSource} placed it on Stage Map open). Auto-syncing
     * the dropdown on every image switch made the user's explicit choice
     * disappear when they switched to a derived entry whose ancestor walk
     * resolved to a different scanner.
     *
     * <p>To view a different macro source after switching images, pick it
     * manually from the dropdown -- that updates the persistent default so
     * subsequent sessions remember the choice.
     *
     * <p>Fresh-import WSI entries (no {@code source_microscope}) used to be
     * auto-stamped with the active scope here. That stamp now happens only
     * via explicit workflows (Microscope Alignment, ExistingImageWorkflow's
     * source-mismatch dialog) so the Stage Map can't silently corrupt
     * acquisition-origin metadata. Microscope-acquired images already have
     * {@code source_microscope} set at acquisition time, so they're
     * unaffected.
     */
    private void onOpenedImageChanged() {
        // Intentionally a no-op for the dropdown. The image-change listener
        // still fires (used for macro overlay refresh + movement-warning
        // updates via separately-wired paths -- see show()).
        logger.debug("onOpenedImageChanged: Source dropdown auto-sync disabled by design");
    }

    /**
     * Apply the initial flip state after the stage is shown.
     * Must be called after stage.show() so the StackPane scale transforms take effect.
     *
     * <p>Also attaches change listeners to the underlying StagePolarity /
     * CameraOrientation prefs so the toggle keeps tracking the hardware
     * settings if the user edits them mid-session (e.g. via Calibrate
     * Directions). Listener attachment is one-shot.
     */
    private void applyInitialFlipState() {
        syncApplyFlipsFromHardware("initial");
        // One-shot listener attachment; weak references are not needed because
        // the StageMapWindow is a singleton tied to QuPath's lifetime.
        QPPreferenceDialog.stageInvertedXObservable()
                .addListener(
                        (obs, was, now) -> Platform.runLater(() -> syncApplyFlipsFromHardware("stage polarity X")));
        QPPreferenceDialog.stageInvertedYObservable()
                .addListener(
                        (obs, was, now) -> Platform.runLater(() -> syncApplyFlipsFromHardware("stage polarity Y")));
        QPPreferenceDialog.cameraOrientationObservable()
                .addListener(
                        (obs, was, now) -> Platform.runLater(() -> syncApplyFlipsFromHardware("camera orientation")));
    }

    /**
     * Recompute the flip axes from hardware (active scope's polarity + camera
     * orientation, XOR'd with the active macro preset) and push the result to
     * the checkbox + canvas. Called on initial show, when the source dropdown
     * changes, and when StagePolarity / CameraOrientation prefs change.
     */
    private void syncApplyFlipsFromHardware(String reason) {
        boolean[] axes = resolveCurrentFlipAxes();
        boolean shouldFlip = axes[0] || axes[1];
        if (applyFlipsCheckbox != null) {
            suppressFlipCheckboxListener = true;
            try {
                applyFlipsCheckbox.setSelected(shouldFlip);
            } finally {
                suppressFlipCheckboxListener = false;
            }
        }
        if (canvas != null) {
            canvas.setFlipsApplied(shouldFlip, axes[0], axes[1]);
            logger.info(
                    "syncApplyFlipsFromHardware({}): shouldFlip={}, flipX={}, flipY={}",
                    reason,
                    shouldFlip,
                    axes[0],
                    axes[1]);
        }
    }

    /**
     * Resolve the currently-effective {flipX, flipY} for the Stage Map view.
     *
     * <p>Composes two fixed hardware-side properties of the active microscope
     * setup; the open project entry has no influence on the result.
     * <ol>
     *   <li><b>Camera flip</b> -- the active scope's
     *       {@link qupath.ext.qpsc.utilities.CameraOrientation} only, via
     *       {@link StageImageTransform#cameraFlipFlags()}. The map geometry is
     *       drawn through {@code stageToScreen}, which already applies the
     *       insert's axis inversion (= {@link qupath.ext.qpsc.utilities.StagePolarity}),
     *       so the unflipped map is already in the sample frame. Only the camera
     *       orientation separates the sample frame from the displayed (Live
     *       Viewer) frame -- so we must NOT fold polarity in again here. Using
     *       {@code stitcherFlipFlags()} (stage+camera) double-counted polarity
     *       and made Apply Flips 180 deg wrong on inverted-stage scopes (OWS3).</li>
     *   <li><b>Macro overlay flip</b> -- if a macro is being shown, its source
     *       scanner's preset records {@code flipMacroX/Y} (the orientation of
     *       that scanner's macro relative to the stage). Read from
     *       {@link #activePreset}, which the user picks in the Source dropdown.
     *       If no preset is selected (active-scope identity), this contributes
     *       {@code (false, false)}.</li>
     * </ol>
     *
     * <p>The two are XOR'd per-axis so that everything on the canvas -- the
     * insert/slides/crosshair/FOV box AND the macro overlay -- ends up in the
     * same orientation as the Live Viewer.
     *
     * @return a 2-element array {@code {flipX, flipY}}; never null
     */
    private boolean[] resolveCurrentFlipAxes() {
        // CAMERA flip only -- NOT stitcherFlipFlags(). The Stage Map geometry is
        // drawn through stageToScreen, which already applies the insert's axis
        // inversion (= stage polarity). stitcherFlipFlags() folds polarity in a
        // second time, which double-counted it and made "Apply Flips" 180 deg
        // wrong on inverted-stage scopes (OWS3: polarity (true,true), camera
        // NORMAL -> stitcher (true,true) but the map already matched the Live
        // Viewer unflipped, so the correct flip is (false,false)). The map is in
        // the sample frame; only the camera orientation separates it from the
        // displayed (Live Viewer) frame. See COORDINATE_TRANSFORMS.md.
        boolean[] stageFlags = StageImageTransform.current().cameraFlipFlags();
        boolean macroFlipX = false;
        boolean macroFlipY = false;
        if (activePreset != null && activePreset.hasFlipState()) {
            macroFlipX = Boolean.TRUE.equals(activePreset.getFlipMacroX());
            macroFlipY = Boolean.TRUE.equals(activePreset.getFlipMacroY());
        }
        boolean[] axes = {stageFlags[0] ^ macroFlipX, stageFlags[1] ^ macroFlipY};
        logger.info(
                "resolveCurrentFlipAxes: camera=({}, {}) XOR macroPreset='{}'=({}, {}) -> ({}, {})",
                stageFlags[0],
                stageFlags[1],
                activePreset != null ? activePreset.getName() : "(none)",
                macroFlipX,
                macroFlipY,
                axes[0],
                axes[1]);
        return axes;
    }

    private void startPositionPolling() {
        if (isPolling) {
            return;
        }

        // Use a daemon thread so it won't prevent JVM shutdown
        positionPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StageMap-PositionPoller");
            t.setDaemon(true);
            return t;
        });
        positionPoller.scheduleAtFixedRate(this::pollPosition, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        isPolling = true;

        logger.debug("Started position polling ({}ms interval)", POLL_INTERVAL_MS);
    }

    private void stopPositionPolling() {
        if (positionPoller != null && !positionPoller.isShutdown()) {
            positionPoller.shutdownNow();
            isPolling = false;
            logger.debug("Stopped position polling");
        }
    }

    private void pollPosition() {
        // Skip updates if window is not visible or dialogs are showing
        if (dialogShowing || stage == null || !stage.isShowing()) {
            return;
        }

        // Skip if too many consecutive errors (microscope likely disconnected)
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            return;
        }

        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            double[] pos = controller.getStagePositionXY();

            if (pos != null && pos.length >= 2) {
                // Reset error counter on success
                consecutiveErrors = 0;

                Platform.runLater(() -> {
                    // Double-check state on FX thread
                    if (dialogShowing || stage == null || !stage.isShowing()) {
                        return;
                    }
                    canvas.updatePosition(pos[0], pos[1]);
                    positionLabel.setText(String.format("Pos: %.1f, %.1f um", pos[0], pos[1]));
                    statusLabel.setText("");
                    statusLabel.setStyle("-fx-text-fill: #888;");

                    // Update target label based on mouse position
                    double[] target = canvas.getTargetPosition();
                    if (target != null) {
                        StageInsert insert = insertComboBox.getValue();
                        boolean isLegal = insert != null && insert.isPositionLegal(target[0], target[1]);
                        targetLabel.setText(String.format("Target: %.1f, %.1f", target[0], target[1]));
                        targetLabel.setStyle(isLegal ? "-fx-text-fill: #7ab;" : "-fx-text-fill: #fa7;");
                    } else {
                        targetLabel.setText("");
                    }
                });

                // Update FOV if available
                if (!dialogShowing && stage != null && stage.isShowing()) {
                    updateFOV();
                }
            } else {
                // null position is also an error
                handlePollingError(null);
            }

        } catch (Exception e) {
            handlePollingError(e);
        }
    }

    private void handlePollingError(Exception e) {
        // Aux reconnect cooldown is a transient back-off imposed by the
        // socket client when the auxiliary channel is contended (e.g. during
        // an active acquisition). It is not a disconnection -- don't let it
        // trip the Disconnected state. Just skip this cycle and retry later.
        if (e != null && isCooldownError(e)) {
            return;
        }
        consecutiveErrors++;

        // Only update UI once when we hit the error threshold
        if (consecutiveErrors == MAX_CONSECUTIVE_ERRORS) {
            // Include the actual cause so failures are diagnosable. The per-call
            // path logs at debug only (to avoid flood), so without this the user
            // has no signal about WHY polling failed -- common case is aux
            // socket down while primary stays up (green status is misleading).
            if (e != null) {
                logger.warn(
                        "Stage Map polling paused after {} consecutive errors - last error: {}: {}",
                        MAX_CONSECUTIVE_ERRORS,
                        e.getClass().getSimpleName(),
                        e.getMessage());
            } else {
                logger.warn(
                        "Stage Map polling paused after {} consecutive errors - microscope returned null position",
                        MAX_CONSECUTIVE_ERRORS);
            }
            Platform.runLater(() -> {
                if (stage != null && stage.isShowing() && !dialogShowing) {
                    // Do NOT hide the canvas. StageMapCanvas now uses
                    // WritableImage+Shape and explicitly does not suffer the
                    // texture-corruption class of bug the prior Canvas-based
                    // implementation did (see its class javadoc and
                    // isTextureCorrupted()), so there is no render loop to
                    // suppress. Hiding the canvas wiped the macro overlay,
                    // slide rect, and insert outline -- exactly what the user
                    // wants to keep seeing when live position polling breaks.
                    statusLabel.setText("Position polling paused - see log");
                    statusLabel.setStyle("-fx-text-fill: #f66;");
                }
            });
        }
    }

    private static boolean isCooldownError(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("cooldown")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Resets the error counter and resumes polling.
     * Call this when reconnecting to the microscope.
     */
    public void resetPollingErrors() {
        consecutiveErrors = 0;
        logger.info("Stage Map polling errors reset");
    }

    private void updateFOV() {
        try {
            MicroscopeConfigManager config =
                    MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());

            if (config == null) return;

            // Get current modality, objective, and detector to calculate FOV
            String modality = config.getString("microscope", "modality");
            String objective = config.getString("microscope", "objective_in_use");
            String detector = config.getString("microscope", "detector_in_use");

            if (modality != null && objective != null && detector != null) {
                double[] fov = config.getModalityFOV(modality, objective, detector);
                if (fov != null && fov.length >= 2) {
                    Platform.runLater(() -> canvas.updateFOV(fov[0], fov[1]));
                }
            }
        } catch (Exception e) {
            // FOV display is optional, don't log errors
        }
    }

    private void handleCanvasClick(double stageX, double stageY) {
        // Block navigation during active acquisition. The local flag can
        // desync from the server if a cancellation path skips the cleanup
        // hook (e.g. a sync exception between setAcquisitionActive(true)
        // and the whenComplete registration). Verify against the server
        // before refusing -- this turns the local flag into an advisory
        // cache and makes the server's STATUS authoritative.
        if (MicroscopeController.getInstance().isAcquisitionActive()) {
            statusLabel.setText("Verifying acquisition state...");
            statusLabel.setStyle("-fx-text-fill: #fa7;");
            new Thread(
                            () -> {
                                boolean stillActive;
                                try {
                                    stillActive =
                                            MicroscopeController.getInstance().isAcquisitionReallyActive();
                                } catch (Exception ex) {
                                    // STATUS check failed -- can't prove the flag is stale,
                                    // so honor the cached value and stay locked.
                                    logger.warn(
                                            "Could not verify acquisition state (treating as active): {}",
                                            ex.getMessage());
                                    stillActive = true;
                                }
                                final boolean blocked = stillActive;
                                Platform.runLater(() -> {
                                    if (blocked) {
                                        statusLabel.setText("Locked during acquisition");
                                        statusLabel.setStyle("-fx-text-fill: #fa7;");
                                    } else {
                                        // Stale flag was cleared inside the controller. Re-fire
                                        // the click so the user's intended move actually happens.
                                        statusLabel.setText("");
                                        statusLabel.setStyle("-fx-text-fill: #888;");
                                        handleCanvasClick(stageX, stageY);
                                    }
                                });
                            },
                            "StageMap-AcqStateVerify")
                    .start();
            return;
        }

        StageInsert insert = insertComboBox.getValue();
        if (insert == null) {
            logger.warn("No insert selected for stage movement");
            return;
        }

        // Check if position is within the aperture/insert bounds.
        // Note: The aperture calibration points define the primary clickable area.
        // The stage.limits values in config are NOT accurate hardware limits.
        //
        // For inserts with an outer outline (a petri dish body around its imaging
        // well), the well is smaller than the dish. Limiting clicks to the well
        // makes edge calibration painful, so positions between the well and the
        // dish outline are ALLOWED with a once-per-session caution; only clicks
        // past the dish body are blocked. Plain slides (no outline) keep the
        // strict aperture gate.
        if (!insert.isPositionInInsert(stageX, stageY)) {
            boolean withinDishBody = insert.hasOutline() && insert.isPositionInOutline(stageX, stageY);
            if (withinDishBody) {
                logger.info(
                        "Click at ({}, {}) is outside the well but within the dish body of insert '{}'",
                        String.format("%.1f", stageX),
                        String.format("%.1f", stageY),
                        insert.getId());
                if (!outsideApertureWarningShownThisSession) {
                    boolean confirmed = showOutsideApertureWarning(stageX, stageY);
                    if (!confirmed) {
                        return;
                    }
                    outsideApertureWarningShownThisSession = true;
                }
            } else {
                logger.warn(
                        "Invalid position clicked: ({}, {}) - outside {} for insert '{}'",
                        String.format("%.1f", stageX),
                        String.format("%.1f", stageY),
                        insert.hasOutline() ? "dish body" : "aperture",
                        insert.getId());
                showWarning(
                        "Invalid Position",
                        insert.hasOutline()
                                ? "The selected position is past the dish body outline.\n"
                                        + "Please select a position within the dish."
                                : "The selected position is outside the visible aperture.\n"
                                        + "Please select a position within the stage insert area.");
                return;
            }
        }

        // First movement warning
        if (!movementWarningShownThisSession) {
            boolean confirmed = showFirstMovementWarning(stageX, stageY);
            if (!confirmed) {
                return;
            }
            movementWarningShownThisSession = true;
        }

        // Execute the move
        executeMove(stageX, stageY);
    }

    /**
     * Caution shown the first time this session that the user clicks past the
     * imaging well into the surrounding dish body. Confirms once, then allows
     * subsequent out-of-well moves silently (needed for well-edge calibration).
     */
    private boolean showOutsideApertureWarning(double targetX, double targetY) {
        dialogShowing = true;
        try {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Outside Imaging Well");
            alert.setHeaderText("Moving Outside the Imaging Well");
            alert.setContentText(String.format(
                    "Target position: (%.1f, %.1f) um\n\n"
                            + "This position is outside the imaging well but still within the\n"
                            + "dish body. Moving here is allowed (e.g. to calibrate the well\n"
                            + "edge), but there may be no coverslip or sample under the\n"
                            + "objective. Watch clearance.\n\n"
                            + "This warning will not appear again this session.",
                    targetX, targetY));

            ButtonType moveButton = new ButtonType("Move Stage", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(moveButton, cancelButton);

            // StageMap is always-on-top; helper parents + raises the alert.
            Optional<ButtonType> result = UIFunctions.showAlertOverParent(alert, stage);
            return result.isPresent() && result.get() == moveButton;
        } finally {
            dialogShowing = false;
        }
    }

    private boolean showFirstMovementWarning(double targetX, double targetY) {
        dialogShowing = true;
        try {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Confirm Stage Movement");
            alert.setHeaderText("First Stage Movement This Session");
            alert.setContentText(String.format(
                    "You are about to move the microscope stage.\n\n" + "Target position: (%.1f, %.1f) um\n\n"
                            + "IMPORTANT: Before moving, ensure:\n"
                            + "- The objective turret has adequate clearance\n"
                            + "- Lower-power objectives won't collide with the sample\n"
                            + "- The slide is properly secured in the insert\n\n"
                            + "This warning will not appear again this session.",
                    targetX, targetY));

            ButtonType moveButton = new ButtonType("Move Stage", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(moveButton, cancelButton);

            // StageMap is always-on-top; helper parents + raises the alert.
            Optional<ButtonType> result = UIFunctions.showAlertOverParent(alert, stage);
            return result.isPresent() && result.get() == moveButton;
        } finally {
            dialogShowing = false;
        }
    }

    private void executeMove(double targetX, double targetY) {
        try {
            logger.info("Moving stage to ({}, {})", targetX, targetY);
            statusLabel.setText("Moving...");
            statusLabel.setStyle("-fx-text-fill: #fa7;");

            MicroscopeController controller = MicroscopeController.getInstance();
            controller.moveStageXY(targetX, targetY);

            // Position will update on next poll cycle

        } catch (Exception e) {
            logger.error("Failed to move stage: {}", e.getMessage(), e);
            showError("Movement Failed", "Failed to move stage: " + e.getMessage());
            statusLabel.setText("Move failed");
            statusLabel.setStyle("-fx-text-fill: #f66;");
        }
    }

    private void showWarning(String title, String message) {
        Platform.runLater(() -> {
            dialogShowing = true;
            try {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle(title);
                alert.setHeaderText(null);
                alert.setContentText(message);
                UIFunctions.showAlertOverParent(alert, stage);
            } finally {
                dialogShowing = false;
            }
        });
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            dialogShowing = true;
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle(title);
                alert.setHeaderText(null);
                alert.setContentText(message);
                UIFunctions.showAlertOverParent(alert, stage);
            } finally {
                dialogShowing = false;
            }
        });
    }

    /**
     * Resets the session warning flag. Primarily for testing.
     */
    public static void resetWarningFlag() {
        movementWarningShownThisSession = false;
        outsideApertureWarningShownThisSession = false;
    }

    // ========== Macro Overlay Methods ==========

    /**
     * Registers a listener for QuPath image changes to update macro overlay availability.
     */
    private void registerImageChangeListener() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null) {
            imageChangeListener = (obs, oldImage, newImage) -> {
                logger.info("Image changed in QuPath viewer - rechecking macro overlay availability");
                onOpenedImageChanged();
                checkMacroOverlayAvailability();
            };
            gui.imageDataProperty().addListener(imageChangeListener);
            logger.info("Registered image change listener for macro overlay updates");

            // Initial check
            logger.info("Running initial macro overlay availability check...");
            checkMacroOverlayAvailability();
        } else {
            logger.warn("QuPath GUI not available - cannot register image change listener for macro overlay");
        }
    }

    /**
     * Unregisters the image change listener to prevent memory leaks.
     */
    private void unregisterImageChangeListener() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && imageChangeListener != null) {
            gui.imageDataProperty().removeListener(imageChangeListener);
            imageChangeListener = null;
            logger.debug("Unregistered image change listener");
        }
    }

    /**
     * Checks if a macro overlay is available for the current QuPath image
     * and updates the checkbox state accordingly.
     */
    private void checkMacroOverlayAvailability() {
        Platform.runLater(() -> {
            logger.info("Checking macro overlay availability...");
            currentMacroImage = null;
            currentMacroTransform = null;
            currentMacroSampleName = null;
            currentMacroScannerName = null;
            currentMacroAnchorMacroX = Double.NaN;
            currentMacroAnchorMacroY = Double.NaN;
            currentMacroAnchorStageX = Double.NaN;
            currentMacroAnchorStageY = Double.NaN;
            currentMacroPresetPixelSizeUm = Double.NaN;

            // Multi-slot slide holders (e.g. quad_v): a single macro belongs to ONE slot,
            // but the single-macro overlay is positioned/scaled as if the slide fills the
            // whole insert -- on a 4-slot holder it renders as one horizontal slide spanning
            // all four positions, which is wrong and misleading. The correct per-slot macro
            // rendering is the separate "macros at slots" Stage Map view; until that exists,
            // disable the single-macro overlay for multi-slot holders rather than draw the
            // spanning artifact. Single-slide inserts are unaffected.
            StageInsert selectedInsert = insertComboBox != null ? insertComboBox.getValue() : null;
            if (selectedInsert != null
                    && selectedInsert.getSlides() != null
                    && selectedInsert.getSlides().size() > 1) {
                logger.info(
                        "Macro overlay disabled for multi-slot holder '{}' ({} slots): "
                                + "single-macro overlay would span all slots",
                        selectedInsert.getName(),
                        selectedInsert.getSlides().size());
                macroOverlayCheckbox.setSelected(false);
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setTooltip(new Tooltip("Macro overlay is disabled for multi-slot slide holders.\n"
                        + "A single macro belongs to one slot, but this overlay would\n"
                        + "stretch it across all slots as one horizontal slide.\n"
                        + "Per-slot macro display is handled separately."));
                if (canvas != null) canvas.clearMacroOverlay();
                return;
            }

            // Always refresh activePreset from disk so a recently-saved alignment is picked
            // up on the next overlay refresh. Without this, the in-memory preset object
            // cached at Stage Map open time would shadow newer anchor metadata that the
            // alignment workflow just wrote to saved_transforms.json.
            try {
                String src = sourceComboBox != null ? sourceComboBox.getValue() : null;
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                if (src != null && !src.isEmpty() && configPath != null && !configPath.isEmpty()) {
                    MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                    String target = mgr != null ? mgr.getMicroscopeName() : null;
                    AffineTransformManager refreshed = new AffineTransformManager(new File(configPath).getParent());
                    AffineTransformManager.TransformPreset fresh = refreshed.getBestPresetForPair(src, target);
                    if (fresh != null) {
                        activePreset = fresh;
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not refresh activePreset before macro overlay check: {}", e.getMessage());
            }

            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui == null) {
                logger.info("Macro overlay: QuPath GUI not available");
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                return;
            }
            if (gui.getProject() == null) {
                logger.info("Macro overlay: no project open");
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                return;
            }
            if (gui.getImageData() == null) {
                logger.info("Macro overlay: no image selected in viewer");
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                return;
            }

            @SuppressWarnings("unchecked")
            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();

            // Get sample name for lookup
            String sampleName = getSampleNameForCurrentImage(gui, project);

            if (sampleName == null || sampleName.isEmpty()) {
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                logger.info("Macro overlay: no sample name resolved for current image - checkbox disabled");
                return;
            }

            logger.info("Macro overlay: resolved sample name '{}', loading data...", sampleName);

            // Re-resolve activePreset from disk: AffineTransformManager is not a
            // singleton, so the saved-transforms file can change underneath us
            // (e.g. after a Microscope Alignment run wrote a new preset with
            // anchor metadata). Reading the cached activePreset would otherwise
            // serve the pre-save copy and force the legacy 4-corner placement
            // path even though the new preset has anchor data.
            try {
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                String selectedSource = sourceComboBox != null ? (String) sourceComboBox.getValue() : null;
                MicroscopeConfigManager mcm = MicroscopeConfigManager.getInstanceIfAvailable();
                String targetMicroscope = (mcm != null) ? mcm.getMicroscopeName() : null;
                if (configPath != null && !configPath.isEmpty() && selectedSource != null && targetMicroscope != null) {
                    AffineTransformManager fresh = new AffineTransformManager(new File(configPath).getParent());
                    AffineTransformManager.TransformPreset refreshed =
                            fresh.getBestPresetForPair(selectedSource, targetMicroscope);
                    if (refreshed != null) {
                        activePreset = refreshed;
                    }
                }
            } catch (Exception e) {
                logger.warn("Macro overlay: failed to refresh activePreset from disk: {}", e.getMessage());
            }

            // Use the preset resolved by the Source dropdown. activePreset is the
            // most-recent (sourceScanner -> currentMicroscope) match.
            AffineTransform overlayTransform = null;
            String scannerName = null;
            if (activePreset != null) {
                overlayTransform = activePreset.getTransform();
                scannerName = activePreset.getMountingMethod();
                logger.info(
                        "Macro overlay: using preset '{}' (scanner: {}, scale: {} um/px)",
                        activePreset.getName(),
                        scannerName,
                        String.format("%.4f", overlayTransform.getScaleX()));
            } else {
                logger.info("Macro overlay: no source selected / no matching preset for this microscope");
            }

            // Load macro image. Prefer raw macro from QuPath (process on-the-fly with
            // scanner-specific cropping + flip), fall back to saved _alignment.png.
            BufferedImage macroImage = null;
            if (overlayTransform != null) {
                macroImage = loadAndProcessMacroImage(gui, project, sampleName, scannerName);
            }

            logger.info(
                    "Macro overlay: macroImage={}, overlayTransform={}",
                    macroImage != null ? macroImage.getWidth() + "x" + macroImage.getHeight() : "null",
                    overlayTransform != null
                            ? String.format(
                                    "scale(%.4f, %.4f) translate(%.1f, %.1f)",
                                    overlayTransform.getScaleX(),
                                    overlayTransform.getScaleY(),
                                    overlayTransform.getTranslateX(),
                                    overlayTransform.getTranslateY())
                            : "null");

            if (macroImage != null && overlayTransform != null) {
                currentMacroImage = macroImage;
                currentMacroTransform = overlayTransform;
                currentMacroSampleName = sampleName;
                currentMacroScannerName = scannerName;
                // Pull the anchor metadata if the preset has it; legacy presets
                // return NaN here, which causes StageMapCanvas to fall back to
                // the 4-corner placement path.
                if (activePreset != null && activePreset.hasOverlayAnchor()) {
                    currentMacroAnchorMacroX = activePreset.getGreenBoxDisplayCenterX();
                    currentMacroAnchorMacroY = activePreset.getGreenBoxDisplayCenterY();
                    currentMacroAnchorStageX = activePreset.getStageAnchorX();
                    currentMacroAnchorStageY = activePreset.getStageAnchorY();
                    currentMacroPresetPixelSizeUm = activePreset.getMacroPixelSizeUm();
                    logger.info(
                            "Macro overlay anchor from preset: macro=({}, {}), stage=({}, {}), pixelSize={} um",
                            String.format("%.2f", currentMacroAnchorMacroX),
                            String.format("%.2f", currentMacroAnchorMacroY),
                            String.format("%.2f", currentMacroAnchorStageX),
                            String.format("%.2f", currentMacroAnchorStageY),
                            String.format("%.4f", currentMacroPresetPixelSizeUm));
                } else {
                    currentMacroAnchorMacroX = Double.NaN;
                    currentMacroAnchorMacroY = Double.NaN;
                    currentMacroAnchorStageX = Double.NaN;
                    currentMacroAnchorStageY = Double.NaN;
                    currentMacroPresetPixelSizeUm = Double.NaN;
                    logger.info(
                            "Macro overlay anchor: preset has no anchor metadata (legacy preset) -- 4-corner placement will be used");
                }
                macroOverlayCheckbox.setDisable(false);
                logger.info("Macro overlay available for sample '{}' - checkbox enabled", sampleName);

                // Auto-select the overlay when a macro image is detected.
                // This fires the selectedProperty listener which calls applyMacroOverlayToCanvas().
                if (!macroOverlayCheckbox.isSelected()) {
                    logger.info("Macro overlay auto-enabled (macro image detected for '{}')", sampleName);
                    macroOverlayCheckbox.setSelected(true);
                } else {
                    // Already selected (e.g., user toggled manually) -- just refresh
                    logger.info("Macro overlay checkbox already selected - refreshing overlay");
                    applyMacroOverlayToCanvas();
                }
            } else {
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                if (macroImage == null && overlayTransform == null) {
                    logger.info(
                            "Macro overlay: NEITHER macro image nor preset transform available - checkbox disabled");
                } else if (macroImage == null) {
                    logger.info(
                            "Macro overlay: preset transform found but NO macro image for '{}' - checkbox disabled",
                            sampleName);
                } else {
                    logger.info(
                            "Macro overlay: macro image found but NO preset transform available - checkbox disabled");
                }
            }
        });
    }

    /**
     * Loads and processes the macro image for overlay display.
     *
     * <p>Prefers the raw macro from QuPath's associated images. Falls back to the saved
     * {@code _alignment.png} if no raw macro is available (e.g., flipped duplicate images
     * don't expose the original image's associated images).
     *
     * <p>Regardless of source, scanner-specific cropping and flip preferences are applied.
     * An already-cropped image (dimensions matching the expected crop result) will not be
     * double-cropped.
     *
     * @param gui         The QuPath GUI instance
     * @param project     The current project
     * @param sampleName  The sample name for fallback lookup
     * @param scannerName The scanner that produced the macro (from the preset), may be null
     * @return The processed macro image, or null if not available
     */
    private BufferedImage loadAndProcessMacroImage(
            QuPathGUI gui, Project<BufferedImage> project, String sampleName, String scannerName) {

        // Try to get the macro image from any available source.
        // For flipped entries (TransformedServer), associated images are not exposed,
        // so we trace back to the original entry and read its macro directly.
        // This ensures both base and flipped images use the exact same raw macro.
        BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
        String source;

        if (macroImage != null) {
            source = "QuPath associated images";
            logger.info(
                    "Macro overlay: loaded raw macro ({}x{}) from {}",
                    macroImage.getWidth(),
                    macroImage.getHeight(),
                    source);
        } else {
            // Current image has no associated images (typical for flipped duplicates).
            // Look up the original entry via metadata and load its macro.
            macroImage = loadMacroFromOriginalEntry(gui, project);
            if (macroImage != null) {
                source = "original entry associated images";
                logger.info(
                        "Macro overlay: loaded raw macro ({}x{}) from {}",
                        macroImage.getWidth(),
                        macroImage.getHeight(),
                        source);
            } else {
                logger.info("Macro overlay: no macro image available");
                return null;
            }
        }

        // Apply scanner-specific cropping if scanner name is known.
        if (scannerName != null) {
            try {
                MacroImageUtility.CroppedMacroResult cropped =
                        MacroImageUtility.cropToSlideArea(macroImage, scannerName);
                macroImage = cropped.getCroppedImage();
                logger.info(
                        "Macro overlay: cropped to {}x{} (offset: {}, {})",
                        macroImage.getWidth(),
                        macroImage.getHeight(),
                        cropped.getCropOffsetX(),
                        cropped.getCropOffsetY());
            } catch (Exception e) {
                logger.warn("Macro overlay: failed to crop macro from {}: {}", source, e.getMessage());
            }
        }

        // Apply optical flip for Stage Map display.
        // The macro is always raw (from associated images, either current or original entry).
        //
        // Optical flip (preset.flipMacroX/Y) and stage axis inversion are INDEPENDENT --
        // see qupath-extension-qpsc/CLAUDE.md "Coordinate system terminology". Optical
        // flip changes which way the macro pixels render; stage axis inversion changes
        // which way stage commands move. Conflating them via XOR (the previous
        // implementation) was a coincidence-correct hack on PPM (where both axes are
        // inverted AND both optical flips are set, so XOR cancels) that broke as soon
        // as the same preset was used in a project where the entry's metadata didn't
        // happen to line up with the inversion booleans (today on PPM, where opening
        // the unflipped MetroHealth_142.svs base produced an unflipped macro overlay
        // even though Ocus40_PPM correctly says flipMacroX=Y=true).
        boolean[] resolved = resolveCurrentFlipAxes();
        boolean flipX = resolved[0];
        boolean flipY = resolved[1];
        logger.info("Macro overlay: optical flip from preset/entry = ({}, {})", flipX, flipY);

        if (flipX || flipY) {
            macroImage = MacroImageUtility.flipMacroImage(macroImage, flipX, flipY);
        }

        return macroImage;
    }

    /**
     * Loads the raw macro image by tracing the current entry's metadata chain
     * back to the base (source) image that has associated images.
     *
     * <p>This handles flipped duplicates, sub-acquisitions, and any other derived
     * image entries that trace back to an original slide scan. The lookup uses:
     * <ol>
     *   <li>{@code base_image} metadata - the root source image name (covers all derived images)</li>
     *   <li>{@code original_image_id} metadata - direct parent for flipped entries</li>
     * </ol>
     *
     * @param gui     The QuPath GUI instance
     * @param project The current project
     * @return The raw macro image from the source entry, or null if not found
     */
    private BufferedImage loadMacroFromOriginalEntry(QuPathGUI gui, Project<BufferedImage> project) {
        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null || project == null) {
            return null;
        }

        ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(imageData);
        if (currentEntry == null) {
            return null;
        }

        // Strategy 1: Use base_image metadata (covers flipped entries AND sub-acquisitions)
        String baseImageName = ImageMetadataManager.getBaseImage(currentEntry);
        if (baseImageName != null && !baseImageName.isEmpty()) {
            logger.info("Macro overlay: tracing base_image='{}' for current entry", baseImageName);
            BufferedImage macro = findMacroByImageName(project, baseImageName);
            if (macro != null) {
                return macro;
            }
        }

        // Strategy 2: Use original_image_id (direct parent for flipped entries)
        String originalId = ImageMetadataManager.getOriginalImageId(currentEntry);
        if (originalId != null) {
            logger.info("Macro overlay: tracing original_image_id='{}' for current entry", originalId);
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                if (originalId.equals(entry.getID())) {
                    BufferedImage macro = readMacroFromEntry(entry);
                    if (macro != null) {
                        return macro;
                    }
                    // The original might itself be derived; check its base_image
                    String origBase = ImageMetadataManager.getBaseImage(entry);
                    if (origBase != null && !origBase.isEmpty()) {
                        macro = findMacroByImageName(project, origBase);
                        if (macro != null) {
                            return macro;
                        }
                    }
                    break;
                }
            }
        }

        logger.debug("Macro overlay: no source entry with macro found for current image");
        return null;
    }

    /**
     * Finds an entry by image name (with or without extension) and reads its macro.
     */
    private BufferedImage findMacroByImageName(Project<BufferedImage> project, String targetName) {
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String entryName = entry.getImageName();
            String strippedName = qupath.lib.common.GeneralTools.stripExtension(entryName);
            if (targetName.equals(entryName) || targetName.equals(strippedName)) {
                BufferedImage macro = readMacroFromEntry(entry);
                if (macro != null) {
                    logger.info(
                            "Macro overlay: loaded raw macro ({}x{}) from entry '{}'",
                            macro.getWidth(),
                            macro.getHeight(),
                            entryName);
                    return macro;
                }
            }
        }
        return null;
    }

    /**
     * Reads the macro associated image from a project entry.
     */
    private BufferedImage readMacroFromEntry(ProjectImageEntry<BufferedImage> entry) {
        try {
            ImageData<BufferedImage> data = entry.readImageData();
            var server = data.getServer();
            var associatedList = server.getAssociatedImageList();
            if (associatedList == null) {
                return null;
            }
            for (String name : associatedList) {
                if (name.toLowerCase().contains("macro")) {
                    Object image = server.getAssociatedImage(name);
                    if (image instanceof BufferedImage macro) {
                        return macro;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn(
                    "Macro overlay: failed to read macro from entry '{}': {}", entry.getImageName(), e.getMessage());
        }
        return null;
    }

    /**
     * Applies the macro overlay to the canvas, loading scanner-specific positioning
     * parameters from the scanner config file (pixel_size_um, stagemap_overlay offsets).
     */
    private void applyMacroOverlayToCanvas() {
        if (canvas == null || currentMacroImage == null || currentMacroTransform == null) {
            return;
        }

        StageInsert insert = insertComboBox.getValue();
        boolean axInvX = insert != null && insert.isXAxisInverted();
        boolean axInvY = insert != null && insert.isYAxisInverted();

        // Load scanner-specific overlay positioning from config
        double pixelSizeUm = 0;
        double xOffsetUm = 0;
        double yOffsetUm = 0;

        if (currentMacroScannerName != null) {
            try {
                String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                File configDir = new File(configPath).getParentFile();
                File scannerConfigFile = new File(configDir, "config_" + currentMacroScannerName + ".yml");

                if (scannerConfigFile.exists()) {
                    Map<String, Object> scannerConfig =
                            MinorFunctions.loadYamlFile(scannerConfigFile.getAbsolutePath());

                    Double ps = MinorFunctions.getYamlDouble(scannerConfig, "macro", "pixel_size_um");
                    if (ps != null && ps > 0) {
                        pixelSizeUm = ps;
                    }

                    Double xOff =
                            MinorFunctions.getYamlDouble(scannerConfig, "macro", "stagemap_overlay", "x_offset_um");
                    if (xOff != null) {
                        xOffsetUm = xOff;
                    }

                    Double yOff =
                            MinorFunctions.getYamlDouble(scannerConfig, "macro", "stagemap_overlay", "y_offset_um");
                    if (yOff != null) {
                        yOffsetUm = yOff;
                    }

                    logger.info(
                            "Macro overlay config for '{}': pixelSize={} um, offset=({}, {}) um",
                            currentMacroScannerName,
                            pixelSizeUm,
                            xOffsetUm,
                            yOffsetUm);
                } else {
                    logger.warn(
                            "Scanner config not found for '{}' - using fit-to-slide fallback", currentMacroScannerName);
                }
            } catch (Exception e) {
                logger.warn(
                        "Failed to load scanner overlay config for '{}': {}", currentMacroScannerName, e.getMessage());
            }
        }

        // Prefer the preset's recorded macroPixelSize when available; this is
        // the value used at alignment time and removes any chance of config
        // drift between alignment and overlay render.
        double effectivePixelSizeUm = !Double.isNaN(currentMacroPresetPixelSizeUm) && currentMacroPresetPixelSizeUm > 0
                ? currentMacroPresetPixelSizeUm
                : pixelSizeUm;

        canvas.setMacroOverlay(
                currentMacroImage,
                currentMacroTransform,
                axInvX,
                axInvY,
                effectivePixelSizeUm,
                xOffsetUm,
                yOffsetUm,
                currentMacroAnchorMacroX,
                currentMacroAnchorMacroY,
                currentMacroAnchorStageX,
                currentMacroAnchorStageY);
    }

    /**
     * Gets the sample name to use for macro lookup.
     * Tries multiple sources: image file name, sample_name metadata, base_image metadata,
     * and follows original_image_id for flipped images.
     *
     * @param gui The QuPath GUI instance
     * @param project The current project
     * @return The sample name, or null if not found
     */
    private String getSampleNameForCurrentImage(QuPathGUI gui, Project<BufferedImage> project) {
        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            logger.info("  Sample name lookup: imageData is null");
            return null;
        }

        // First try: actual image file name (how alignments are typically saved)
        String imageName = QPProjectFunctions.getActualImageFileName(imageData);
        logger.info("  Sample name lookup: image file name = '{}'", imageName);

        if (imageName != null && !imageName.isEmpty()) {
            // Check if alignment exists for this name (with extension - legacy format)
            logger.info("  Trying alignment lookup with full name: '{}'", imageName);
            if (AffineTransformManager.loadSlideAlignment(project, imageName) != null) {
                logger.info("  -> Found alignment using image name: '{}'", imageName);
                return imageName;
            }
            // Also try without extension (new format for base_image compatibility)
            String strippedName = qupath.lib.common.GeneralTools.stripExtension(imageName);
            if (!strippedName.equals(imageName)) {
                logger.info("  Trying alignment lookup with stripped name: '{}'", strippedName);
                if (AffineTransformManager.loadSlideAlignment(project, strippedName) != null) {
                    logger.info("  -> Found alignment using stripped image name: '{}'", strippedName);
                    return strippedName;
                }
            }
        }

        // Second try: get from project entry metadata
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        if (entry != null) {
            logger.info("  Project entry found (ID: {}), checking metadata...", entry.getID());

            // Try sample_name metadata
            String sampleName = ImageMetadataManager.getSampleName(entry);
            logger.info("  Metadata sample_name = '{}'", sampleName);
            if (sampleName != null && !sampleName.isEmpty()) {
                logger.info("  Trying alignment lookup with sample_name: '{}'", sampleName);
                if (AffineTransformManager.loadSlideAlignment(project, sampleName) != null) {
                    logger.info("  -> Found alignment using sample_name metadata: '{}'", sampleName);
                    return sampleName;
                }
            }

            // Try base_image metadata
            String baseImage = ImageMetadataManager.getBaseImage(entry);
            logger.info("  Metadata base_image = '{}'", baseImage);
            if (baseImage != null && !baseImage.isEmpty()) {
                logger.info("  Trying alignment lookup with base_image: '{}'", baseImage);
                if (AffineTransformManager.loadSlideAlignment(project, baseImage) != null) {
                    logger.info("  -> Found alignment using base_image metadata: '{}'", baseImage);
                    return baseImage;
                }
            }

            // For flipped images, try original's base_image
            boolean isFlipped = ImageMetadataManager.isFlipped(entry);
            logger.info("  Image is flipped: {}", isFlipped);
            if (isFlipped) {
                String originalId = ImageMetadataManager.getOriginalImageId(entry);
                logger.info("  Original image ID for flipped image: '{}'", originalId);
                if (originalId != null) {
                    for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
                        if (originalId.equals(e.getID())) {
                            String origBase = ImageMetadataManager.getBaseImage(e);
                            logger.info("  Original image's base_image = '{}'", origBase);
                            if (origBase != null && !origBase.isEmpty()) {
                                logger.info("  Trying alignment lookup with original's base_image: '{}'", origBase);
                                if (AffineTransformManager.loadSlideAlignment(project, origBase) != null) {
                                    logger.info(
                                            "  -> Found alignment using original image's base_image: '{}'", origBase);
                                    return origBase;
                                }
                            }
                            break;
                        }
                    }
                }
            }
        } else {
            logger.info("  No project entry found for current imageData");
        }

        // Return image name even if no alignment found (for logging purposes)
        logger.info("  No alignment found via any strategy. Returning image name '{}' as fallback", imageName);
        return imageName;
    }
}
