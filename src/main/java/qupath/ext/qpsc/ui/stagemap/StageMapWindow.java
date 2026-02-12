package qupath.ext.qpsc.ui.stagemap;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MacroImageUtility;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import javafx.beans.value.ChangeListener;
import java.awt.Desktop;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private Label positionLabel;
    private Label targetLabel;
    private Label statusLabel;

    // ========== State ==========
    private ScheduledExecutorService positionPoller;
    private volatile boolean isPolling = false;
    private volatile boolean dialogShowing = false;  // Pause updates while dialogs are shown
    private volatile int consecutiveErrors = 0;  // Track polling failures
    private static final int MAX_CONSECUTIVE_ERRORS = 10;  // Pause polling after this many errors
    private static boolean movementWarningShownThisSession = false;

    // ========== Macro Overlay State ==========
    private CheckBox macroOverlayCheckbox;
    private BufferedImage currentMacroImage = null;
    private AffineTransform currentMacroTransform = null;
    private String currentMacroSampleName = null;
    private ChangeListener<ImageData<?>> imageChangeListener = null;

    // ========== Configuration ==========
    // Poll interval for position updates - lower = more responsive but more network traffic
    // 200ms provides smooth tracking without overwhelming the socket connection
    private static final long POLL_INTERVAL_MS = 200;
    private static final double WINDOW_WIDTH = 840;
    private static final double WINDOW_HEIGHT = 760;
    private static final double CANVAS_WIDTH = 760;
    private static final double CANVAS_HEIGHT = 560;

    private StageMapWindow() {
        buildUI();
        loadInsertConfigurations();
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
                instance.startPositionPolling();
            } else {
                instance.stage.toFront();
            }
        });
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
        stage.setMinWidth(350);
        stage.setMinHeight(320);

        // Main layout
        VBox root = new VBox(8);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #2b2b2b;");

        // Top controls: Insert selector
        HBox topBar = buildTopBar();

        // Canvas for stage visualization (new WritableImage + Shapes implementation)
        canvas = new StageMapCanvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        canvas.setClickHandler(this::handleCanvasClick);

        // The canvas is now a StackPane that resizes with its container
        // Just place it in a container that grows with the window
        StackPane canvasContainer = new StackPane(canvas);
        canvasContainer.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #555; -fx-border-width: 1;");
        VBox.setVgrow(canvasContainer, Priority.ALWAYS);

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

        root.getChildren().addAll(topBar, canvasContainer, bottomBar);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(getClass().getResource("/qupath/ext/qpsc/ui/stagemap/stagemap.css") != null
                ? getClass().getResource("/qupath/ext/qpsc/ui/stagemap/stagemap.css").toExternalForm()
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

        insertComboBox = new ComboBox<>();
        insertComboBox.setPrefWidth(200);
        insertComboBox.setOnAction(e -> {
            StageInsert selected = insertComboBox.getValue();
            if (selected != null) {
                canvas.setInsert(selected);
                logger.debug("Switched to insert configuration: {}", selected.getId());
            }
        });

        // Button to open config folder for calibration
        Button configButton = new Button("Config");
        configButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
        configButton.setTooltip(new Tooltip(
                "Open the configuration folder to edit calibration values.\n" +
                "Edit the YAML file to set aperture and slide reference points."));
        configButton.setOnAction(e -> openConfigFolder());

        // Tooltip explaining the interface
        Button helpButton = new Button("?");
        helpButton.setStyle("-fx-font-size: 10; -fx-padding: 2 6;");
        helpButton.setTooltip(new Tooltip(
                "Stage Map shows the microscope stage position.\n\n" +
                "- Green crosshair: Current objective position\n" +
                "- Orange rectangle: Camera field of view\n" +
                "- Blue rectangles: Slide positions\n" +
                "- Green zones: Safe movement areas\n" +
                "- Red zones: Off-slide areas\n\n" +
                "Double-click to move the stage to that position.\n" +
                "Select insert type to change slide layout.\n\n" +
                "CALIBRATION: Use Stage Control to find:\n" +
                "- Left/right aperture edges (X coords)\n" +
                "- Top/bottom slide edges (Y coords)\n" +
                "Then edit the config YAML file."));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Macro overlay checkbox
        macroOverlayCheckbox = new CheckBox("Overlay Macro");
        macroOverlayCheckbox.setStyle("-fx-text-fill: #ccc;");
        macroOverlayCheckbox.setTooltip(new Tooltip(
                "Display the cropped macro image from alignment\n" +
                "over the stage map at its calibrated position.\n\n" +
                "This shows the slide-only portion of the macro\n" +
                "(with slide holder and background removed)\n" +
                "that was saved during Microscope Alignment.\n\n" +
                "Only available for images with saved alignments."));
        macroOverlayCheckbox.setDisable(true);  // Initially disabled until macro is available
        macroOverlayCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            logger.info("Overlay Macro checkbox toggled: {} -> {}", oldVal, newVal);
            if (canvas != null) {
                if (newVal && currentMacroImage != null && currentMacroTransform != null) {
                    logger.info("Applying macro overlay (image: {}x{}, sample: '{}')",
                            currentMacroImage.getWidth(), currentMacroImage.getHeight(),
                            currentMacroSampleName);
                    canvas.setMacroOverlay(currentMacroImage, currentMacroTransform);
                } else {
                    if (newVal) {
                        logger.info("Checkbox selected but no macro data available (image={}, transform={})",
                                currentMacroImage != null, currentMacroTransform != null);
                    }
                    canvas.clearMacroOverlay();
                }
            }
        });

        topBar.getChildren().addAll(insertLabel, insertComboBox, spacer, macroOverlayCheckbox, configButton, helpButton);
        return topBar;
    }

    /**
     * Opens the folder containing the microscope configuration file.
     */
    private void openConfigFolder() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isEmpty()) {
                showWarning("No Config File",
                        "No microscope configuration file is set.\n" +
                        "Please set one in QuPath preferences.");
                return;
            }

            File configFile = new File(configPath);
            File configFolder = configFile.getParentFile();

            if (configFolder == null || !configFolder.exists()) {
                showWarning("Folder Not Found",
                        "Configuration folder not found:\n" + configPath);
                return;
            }

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(configFolder);
                logger.info("Opened config folder: {}", configFolder.getAbsolutePath());
            } else {
                // Fallback: show the path in a dialog
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Configuration Folder");
                alert.setHeaderText("Open this folder to edit calibration:");
                alert.setContentText(configFolder.getAbsolutePath());
                alert.initOwner(stage);
                alert.showAndWait();
            }

        } catch (Exception e) {
            logger.error("Failed to open config folder: {}", e.getMessage(), e);
            showError("Error", "Failed to open configuration folder:\n" + e.getMessage());
        }
    }

    private HBox buildBottomBar() {
        HBox bottomBar = new HBox(15);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(5, 0, 0, 0));

        positionLabel = new Label("Position: -- , --");
        positionLabel.setStyle("-fx-text-fill: #aaa; -fx-font-family: monospace;");

        targetLabel = new Label("");
        targetLabel.setStyle("-fx-text-fill: #7ab; -fx-font-family: monospace;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #888;");

        bottomBar.getChildren().addAll(positionLabel, targetLabel, spacer, statusLabel);
        return bottomBar;
    }

    private void loadInsertConfigurations() {
        try {
            // Load configurations from YAML
            MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());

            if (configManager != null) {
                StageInsertRegistry.loadFromConfig(configManager);
            }

            List<StageInsert> inserts = StageInsertRegistry.getAvailableInserts();
            insertComboBox.setItems(FXCollections.observableArrayList(inserts));

            // Select default
            StageInsert defaultInsert = StageInsertRegistry.getDefaultInsert();
            if (defaultInsert != null) {
                insertComboBox.setValue(defaultInsert);
                canvas.setInsert(defaultInsert);
            }

            logger.info("Loaded {} insert configurations", inserts.size());

        } catch (Exception e) {
            logger.error("Error loading insert configurations: {}", e.getMessage(), e);
            statusLabel.setText("Config error");
            statusLabel.setStyle("-fx-text-fill: #f66;");
        }
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
        positionPoller.scheduleAtFixedRate(
                this::pollPosition,
                0,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
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
                handlePollingError();
            }

        } catch (Exception e) {
            handlePollingError();
        }
    }

    private void handlePollingError() {
        consecutiveErrors++;

        // Only update UI once when we hit the error threshold
        if (consecutiveErrors == MAX_CONSECUTIVE_ERRORS) {
            logger.warn("Stage Map polling paused after {} consecutive errors - microscope may be disconnected",
                    MAX_CONSECUTIVE_ERRORS);
            Platform.runLater(() -> {
                if (stage != null && stage.isShowing() && !dialogShowing) {
                    // CRITICAL: Hide the canvas to stop JavaFX's internal rendering loop
                    // from trying to repaint a corrupted texture (causes NPE spam)
                    if (canvas != null) {
                        canvas.setVisible(false);
                    }
                    statusLabel.setText("Disconnected - reopen to reconnect");
                    statusLabel.setStyle("-fx-text-fill: #f66;");
                }
            });
        }
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
            MicroscopeConfigManager config = MicroscopeConfigManager.getInstance(
                    QPPreferenceDialog.getMicroscopeConfigFileProperty());

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
        StageInsert insert = insertComboBox.getValue();
        if (insert == null) {
            logger.warn("No insert selected for stage movement");
            return;
        }

        // Check if position is within the aperture/insert bounds
        // Note: The aperture calibration points define the valid clickable area.
        // The stage.limits values in config are NOT accurate hardware limits.
        if (!insert.isPositionInInsert(stageX, stageY)) {
            logger.warn("Invalid position clicked: ({}, {}) - outside aperture for insert '{}'",
                    String.format("%.1f", stageX), String.format("%.1f", stageY), insert.getId());
            showWarning("Invalid Position",
                    "The selected position is outside the visible aperture.\n" +
                    "Please select a position within the stage insert area.");
            return;
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

    private boolean showFirstMovementWarning(double targetX, double targetY) {
        dialogShowing = true;
        try {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Confirm Stage Movement");
            alert.setHeaderText("First Stage Movement This Session");
            alert.setContentText(String.format(
                    "You are about to move the microscope stage.\n\n" +
                    "Target position: (%.1f, %.1f) um\n\n" +
                    "IMPORTANT: Before moving, ensure:\n" +
                    "- The objective turret has adequate clearance\n" +
                    "- Lower-power objectives won't collide with the sample\n" +
                    "- The slide is properly secured in the insert\n\n" +
                    "This warning will not appear again this session.",
                    targetX, targetY));

            alert.initOwner(stage);

            ButtonType moveButton = new ButtonType("Move Stage", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(moveButton, cancelButton);

            Optional<ButtonType> result = alert.showAndWait();
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
            showError("Movement Failed",
                    "Failed to move stage: " + e.getMessage());
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
                alert.initOwner(stage);
                alert.showAndWait();
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
                alert.initOwner(stage);
                alert.showAndWait();
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

            // Load the preset transform (macro -> stage) from saved_transforms.json.
            // The preset IS the macro-to-stage transform and also tells us which scanner was used.
            AffineTransform overlayTransform = null;
            String scannerName = null;
            String presetName = PersistentPreferences.getSavedTransformName();
            if (presetName != null && !presetName.isEmpty()) {
                try {
                    String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                    AffineTransformManager manager = new AffineTransformManager(
                            new File(configPath).getParent());
                    AffineTransformManager.TransformPreset preset = manager.getTransform(presetName);
                    if (preset != null) {
                        overlayTransform = preset.getTransform();
                        scannerName = preset.getMountingMethod();
                        logger.info("Macro overlay: loaded preset '{}' (scanner: {}, scale: {} um/px)",
                                presetName, scannerName,
                                String.format("%.4f", overlayTransform.getScaleX()));
                    } else {
                        logger.warn("Macro overlay: preset '{}' not found in saved_transforms.json", presetName);
                    }
                } catch (Exception e) {
                    logger.warn("Macro overlay: failed to load preset '{}': {}", presetName, e.getMessage());
                }
            } else {
                logger.info("Macro overlay: no preset transform name saved in preferences");
            }

            // Load macro image. Prefer raw macro from QuPath (process on-the-fly with
            // scanner-specific cropping + flip), fall back to saved _alignment.png.
            BufferedImage macroImage = null;
            if (overlayTransform != null) {
                macroImage = loadAndProcessMacroImage(gui, project, sampleName, scannerName);
            }

            logger.info("Macro overlay: macroImage={}, overlayTransform={}",
                    macroImage != null ? macroImage.getWidth() + "x" + macroImage.getHeight() : "null",
                    overlayTransform != null ? String.format("scale(%.4f, %.4f) translate(%.1f, %.1f)",
                            overlayTransform.getScaleX(), overlayTransform.getScaleY(),
                            overlayTransform.getTranslateX(), overlayTransform.getTranslateY()) : "null");

            if (macroImage != null && overlayTransform != null) {
                currentMacroImage = macroImage;
                currentMacroTransform = overlayTransform;
                currentMacroSampleName = sampleName;
                macroOverlayCheckbox.setDisable(false);
                logger.info("Macro overlay available for sample '{}' - checkbox enabled", sampleName);

                // If checkbox is already selected, update the overlay
                if (macroOverlayCheckbox.isSelected()) {
                    logger.info("Macro overlay checkbox already selected - applying overlay");
                    canvas.setMacroOverlay(currentMacroImage, currentMacroTransform);
                }
            } else {
                macroOverlayCheckbox.setDisable(true);
                macroOverlayCheckbox.setSelected(false);
                if (canvas != null) canvas.clearMacroOverlay();
                if (macroImage == null && overlayTransform == null) {
                    logger.info("Macro overlay: NEITHER macro image nor preset transform available - checkbox disabled");
                } else if (macroImage == null) {
                    logger.info("Macro overlay: preset transform found but NO macro image for '{}' - checkbox disabled", sampleName);
                } else {
                    logger.info("Macro overlay: macro image found but NO preset transform available - checkbox disabled");
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
            QuPathGUI gui,
            Project<BufferedImage> project,
            String sampleName,
            String scannerName) {

        // Try to get the macro image from any available source
        BufferedImage macroImage = MacroImageUtility.retrieveMacroImage(gui);
        String source;

        if (macroImage != null) {
            source = "QuPath associated images";
            logger.info("Macro overlay: loaded raw macro ({}x{}) from {}",
                    macroImage.getWidth(), macroImage.getHeight(), source);
        } else {
            // Flipped duplicate images don't expose the original's associated images.
            // Fall back to saved _alignment.png (which is the raw macro saved as PNG).
            macroImage = AffineTransformManager.loadSavedMacroImage(project, sampleName);
            if (macroImage != null) {
                source = "saved alignment image";
                logger.info("Macro overlay: loaded saved alignment image ({}x{}) for '{}'",
                        macroImage.getWidth(), macroImage.getHeight(), sampleName);
            } else {
                logger.info("Macro overlay: no macro image available (no raw macro, no saved alignment)");
                return null;
            }
        }

        // Apply scanner-specific cropping if scanner name is known
        if (scannerName != null) {
            try {
                MacroImageUtility.CroppedMacroResult cropped =
                        MacroImageUtility.cropToSlideArea(macroImage, scannerName);
                macroImage = cropped.getCroppedImage();
                logger.info("Macro overlay: cropped to {}x{} (offset: {}, {})",
                        macroImage.getWidth(), macroImage.getHeight(),
                        cropped.getCropOffsetX(), cropped.getCropOffsetY());
            } catch (Exception e) {
                logger.warn("Macro overlay: failed to crop macro from {}: {}", source, e.getMessage());
            }
        }

        // Apply flip for Stage Map display.
        // The flip preferences correct the macro orientation for QuPath's standard
        // coordinate system. But the Stage Map has its own axis inversion (detected
        // from aperture calibration). An inverted axis already visually mirrors that
        // dimension, so applying the preference flip on top of it creates a double-flip.
        // XOR the preference with axis inversion to get the correct effective flip.
        boolean prefFlipX = QPPreferenceDialog.getFlipMacroXProperty();
        boolean prefFlipY = QPPreferenceDialog.getFlipMacroYProperty();
        StageInsert insert = insertComboBox.getValue();
        boolean axisInvertedX = insert != null && insert.isXAxisInverted();
        boolean axisInvertedY = insert != null && insert.isYAxisInverted();
        boolean flipX = prefFlipX ^ axisInvertedX;
        boolean flipY = prefFlipY ^ axisInvertedY;
        if (flipX || flipY) {
            macroImage = MacroImageUtility.flipMacroImage(macroImage, flipX, flipY);
        }
        logger.info("Macro overlay: flip prefs=({}, {}), axis inverted=({}, {}), effective flip=({}, {})",
                prefFlipX, prefFlipY, axisInvertedX, axisInvertedY, flipX, flipY);

        return macroImage;
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
                                    logger.info("  -> Found alignment using original image's base_image: '{}'", origBase);
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
