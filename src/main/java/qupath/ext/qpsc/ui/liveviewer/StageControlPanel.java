package qupath.ext.qpsc.ui.liveviewer;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.VirtualJoystick;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Collapsible panel for Live Viewer containing stage movement controls.
 *
 * <p>This panel provides a compact interface for manual stage control, including:
 * <ul>
 *   <li>X/Y/Z/R position fields with move buttons</li>
 *   <li>FOV-based step size selection</li>
 *   <li>Virtual joystick for continuous movement</li>
 *   <li>Arrow buttons and WASD keyboard control</li>
 *   <li>Go to object centroid functionality</li>
 * </ul>
 *
 * <p>The panel is designed to be embedded in the Live Viewer window and defaults
 * to a collapsed state to minimize screen real estate when not needed.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class StageControlPanel extends TitledPane {

    private static final Logger logger = LoggerFactory.getLogger(StageControlPanel.class);

    private final ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
    private final MicroscopeConfigManager mgr;

    // Position fields
    private final TextField xField = new TextField();
    private final TextField yField = new TextField();
    private final TextField zField = new TextField();
    private final TextField rField = new TextField();

    // Status labels
    private final Label xyStatus = new Label();
    private final Label zStatus = new Label();
    private final Label rStatus = new Label();

    // Step size controls
    private final TextField xyStepField;
    private final ComboBox<String> fovStepCombo;
    private final Label fovInfoLabel;
    private final double[] cachedFovUm = {0, 0};

    // Navigation components
    private final VirtualJoystick joystick;
    private final Button upBtn = new Button("\u2191");
    private final Button downBtn = new Button("\u2193");
    private final Button leftBtn = new Button("\u2190");
    private final Button rightBtn = new Button("\u2192");

    // Sample movement mode
    private final CheckBox sampleMovementCheckbox;
    private final AtomicBoolean sampleMovementMode = new AtomicBoolean(false);

    // Thread-safe position tracking for joystick
    private final AtomicReference<double[]> joystickPosition = new AtomicReference<>(new double[]{0, 0});

    // Go to centroid components
    private final Button goToCentroidBtn;
    private final Label centroidStatus;
    private final Label availableLabel;
    private final ListView<String> alignmentListView;

    /**
     * Creates a new StageControlPanel with all stage control components.
     */
    public StageControlPanel() {
        setText("Stage Control");
        setExpanded(false);
        setAnimated(false);

        // Get config manager for bounds checking
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        mgr = MicroscopeConfigManager.getInstance(configPath);

        // Input validation filter for integer values
        UnaryOperator<TextFormatter.Change> integerFilter = change -> {
            String newText = change.getControlNewText();
            if (newText.matches("[0-9,]*")) {
                return change;
            }
            return null;
        };

        // Initialize step size field from preferences
        xyStepField = new TextField(PersistentPreferences.getStageControlStepSize());
        xyStepField.setPrefWidth(70);
        xyStepField.setAlignment(Pos.CENTER);
        xyStepField.setTextFormatter(new TextFormatter<>(integerFilter));

        // FOV step combo
        fovStepCombo = new ComboBox<>(FXCollections.observableArrayList(
                "1 FOV", "0.5 FOV", "0.25 FOV", "0.1 FOV", res.getString("stageMovement.fov.value")));
        String savedFovSelection = PersistentPreferences.getStageControlFovSelection();
        if (savedFovSelection.equals("Value")) {
            fovStepCombo.setValue(res.getString("stageMovement.fov.value"));
        } else {
            fovStepCombo.setValue(savedFovSelection);
        }
        fovStepCombo.setPrefWidth(90);

        fovInfoLabel = new Label(res.getString("stageMovement.fov.unavailable"));
        fovInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // Sample movement checkbox
        sampleMovementCheckbox = new CheckBox("Sample mvmt");
        sampleMovementCheckbox.setSelected(PersistentPreferences.getStageControlSampleMovement());
        sampleMovementCheckbox.setStyle("-fx-font-size: 10px;");
        Tooltip.install(sampleMovementCheckbox, new Tooltip(
                "When checked, controls move the sample rather than the stage.\n" +
                "This inverts the X direction to match visual expectations."));
        sampleMovementMode.set(sampleMovementCheckbox.isSelected());

        // Virtual joystick
        joystick = new VirtualJoystick(40);

        // Go to centroid components
        goToCentroidBtn = new Button("Go to Centroid");
        goToCentroidBtn.setStyle("-fx-font-size: 10px;");
        centroidStatus = new Label();
        centroidStatus.setStyle("-fx-font-size: 10px;");
        availableLabel = new Label("Images with alignments:");
        availableLabel.setStyle("-fx-font-size: 10px;");
        availableLabel.setVisible(false);
        availableLabel.setManaged(false);
        alignmentListView = new ListView<>();
        alignmentListView.setPrefHeight(60);
        alignmentListView.setVisible(false);
        alignmentListView.setManaged(false);

        // Build the UI
        VBox content = buildContent();
        setContent(content);

        // Wire up event handlers
        setupEventHandlers();

        // Initialize position fields from hardware
        initializeFromHardware();

        // Pre-fetch FOV
        queryFov();
        applyFovStep();
    }

    private VBox buildContent() {
        VBox content = new VBox(6);
        content.setPadding(new Insets(8));

        // --- Row 1: X/Y fields and Move XY button ---
        HBox xyRow = new HBox(4);
        xyRow.setAlignment(Pos.CENTER_LEFT);
        xField.setPrefWidth(80);
        yField.setPrefWidth(80);
        Label xLabel = new Label("X:");
        xLabel.setStyle("-fx-font-size: 10px;");
        Label yLabel = new Label("Y:");
        yLabel.setStyle("-fx-font-size: 10px;");
        Button moveXYBtn = new Button("Move XY");
        moveXYBtn.setStyle("-fx-font-size: 10px;");
        xyRow.getChildren().addAll(xLabel, xField, yLabel, yField, moveXYBtn);

        // XY status
        xyStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // --- Row 2: Z field, Move Z, step control ---
        HBox zRow = new HBox(4);
        zRow.setAlignment(Pos.CENTER_LEFT);
        zField.setPrefWidth(80);
        Label zLabel = new Label("Z:");
        zLabel.setStyle("-fx-font-size: 10px;");
        Button moveZBtn = new Button("Move Z");
        moveZBtn.setStyle("-fx-font-size: 10px;");

        // Z step control
        TextField zStepField = new TextField("10");
        zStepField.setPrefWidth(50);
        zStepField.setAlignment(Pos.CENTER);
        zStepField.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.matches("[0-9,]*")) {
                return change;
            }
            return null;
        }));
        Label zStepLabel = new Label("Z step:");
        zStepLabel.setStyle("-fx-font-size: 10px;");
        Label zUmLabel = new Label("um");
        zUmLabel.setStyle("-fx-font-size: 10px;");

        zRow.getChildren().addAll(zLabel, zField, moveZBtn, zStepLabel, zStepField, zUmLabel);

        // Z scroll handler
        javafx.event.EventHandler<ScrollEvent> zScrollHandler = event -> {
            handleZScroll(event, zStepField);
        };
        zField.setOnScroll(zScrollHandler);
        moveZBtn.setOnScroll(zScrollHandler);
        zStepField.setOnScroll(zScrollHandler);

        // Z status
        zStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // --- Row 3: R field and Move R button ---
        HBox rRow = new HBox(4);
        rRow.setAlignment(Pos.CENTER_LEFT);
        rField.setPrefWidth(80);
        Label rLabel = new Label("R:");
        rLabel.setStyle("-fx-font-size: 10px;");
        Button moveRBtn = new Button("Move R");
        moveRBtn.setStyle("-fx-font-size: 10px;");
        rRow.getChildren().addAll(rLabel, rField, moveRBtn);

        // R status
        rStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // --- Step settings row ---
        Label valueUmLabel = new Label("um");
        valueUmLabel.setStyle("-fx-font-size: 10px;");
        HBox valueRow = new HBox(4, xyStepField, valueUmLabel);
        valueRow.setAlignment(Pos.CENTER_LEFT);

        Button refreshFovBtn = new Button("R");
        refreshFovBtn.setStyle("-fx-font-size: 10px; -fx-min-width: 22px; -fx-min-height: 22px; -fx-padding: 1;");
        Tooltip.install(refreshFovBtn, new Tooltip(res.getString("stageMovement.fov.refreshTooltip")));
        refreshFovBtn.setOnAction(e -> {
            queryFov();
            applyFovStep();
        });

        HBox stepRow = new HBox(4, new Label("Step:"), fovStepCombo, refreshFovBtn, valueRow, sampleMovementCheckbox);
        stepRow.setAlignment(Pos.CENTER_LEFT);
        ((Label) stepRow.getChildren().get(0)).setStyle("-fx-font-size: 10px;");

        // --- Navigation grid: arrows around joystick ---
        String arrowBtnStyle = "-fx-font-size: 11px; -fx-min-width: 26px; -fx-min-height: 26px; -fx-padding: 1;";
        upBtn.setStyle(arrowBtnStyle);
        downBtn.setStyle(arrowBtnStyle);
        leftBtn.setStyle(arrowBtnStyle);
        rightBtn.setStyle(arrowBtnStyle);

        GridPane navGrid = new GridPane();
        navGrid.setAlignment(Pos.CENTER);
        navGrid.setHgap(2);
        navGrid.setVgap(2);
        navGrid.add(upBtn, 1, 0);
        GridPane.setHalignment(upBtn, HPos.CENTER);
        navGrid.add(leftBtn, 0, 1);
        GridPane.setValignment(leftBtn, VPos.CENTER);
        navGrid.add(joystick, 1, 1);
        navGrid.add(rightBtn, 2, 1);
        GridPane.setValignment(rightBtn, VPos.CENTER);
        navGrid.add(downBtn, 1, 2);
        GridPane.setHalignment(downBtn, HPos.CENTER);

        Label keyboardHint = new Label("WASD / Arrows / Drag");
        keyboardHint.setStyle("-fx-font-size: 9px; -fx-text-fill: #666666;");

        VBox navSection = new VBox(4, navGrid, keyboardHint);
        navSection.setAlignment(Pos.CENTER);

        // --- Go to centroid section ---
        HBox centroidRow = new HBox(6, goToCentroidBtn, centroidStatus);
        centroidRow.setAlignment(Pos.CENTER_LEFT);

        VBox centroidSection = new VBox(4, centroidRow, availableLabel, alignmentListView);

        // --- Wire up move button handlers ---
        moveXYBtn.setOnAction(e -> handleMoveXY());
        moveZBtn.setOnAction(e -> handleMoveZ());
        moveRBtn.setOnAction(e -> handleMoveR());

        // Combine sections with separators
        Separator sep1 = new Separator();
        Separator sep2 = new Separator();

        content.getChildren().addAll(
                xyRow, xyStatus,
                zRow, zStatus,
                rRow, rStatus,
                sep1,
                stepRow, fovInfoLabel,
                navSection,
                sep2,
                centroidSection
        );

        return content;
    }

    private void setupEventHandlers() {
        // FOV combo selection handler
        fovStepCombo.setOnAction(e -> {
            applyFovStep();
            String selection = fovStepCombo.getValue();
            if (selection != null) {
                if (selection.equals(res.getString("stageMovement.fov.value"))) {
                    PersistentPreferences.setStageControlFovSelection("Value");
                } else {
                    PersistentPreferences.setStageControlFovSelection(selection);
                }
            }
        });

        // Save step size when changed
        xyStepField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                PersistentPreferences.setStageControlStepSize(newVal);
            }
            if (!xyStepField.isDisabled()) {
                try {
                    double step = Double.parseDouble(newVal.replace(",", ""));
                    joystick.setMaxStepUm(step);
                } catch (NumberFormatException ignored) {
                    // Incomplete input
                }
            }
        });

        // Sample movement checkbox listener
        sampleMovementCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            sampleMovementMode.set(newVal);
            PersistentPreferences.setStageControlSampleMovement(newVal);
            logger.debug("Sample movement mode changed: {} -> {}", oldVal, newVal);
        });

        // Arrow button handlers
        upBtn.setOnAction(e -> handleArrowMove(0, 1));
        downBtn.setOnAction(e -> handleArrowMove(0, -1));
        leftBtn.setOnAction(e -> handleArrowMove(-1, 0));
        rightBtn.setOnAction(e -> handleArrowMove(1, 0));

        // Joystick callbacks
        joystick.setStartCallback(() -> {
            try {
                double x = Double.parseDouble(xField.getText().replace(",", ""));
                double y = Double.parseDouble(yField.getText().replace(",", ""));
                joystickPosition.set(new double[]{x, y});
                logger.debug("Joystick position synced to: ({}, {})", x, y);
            } catch (NumberFormatException e) {
                logger.warn("Failed to sync joystick position from text fields");
            }
        });

        joystick.setMovementCallback((deltaX, deltaY) -> {
            handleJoystickMove(deltaX, deltaY);
        });

        // Go to centroid button
        goToCentroidBtn.setOnAction(e -> handleGoToCentroid());

        // Initialize centroid button state
        initializeCentroidButton();
    }

    private void initializeFromHardware() {
        try {
            double[] xy = MicroscopeController.getInstance().getStagePositionXY();
            xField.setText(String.format("%.2f", xy[0]));
            yField.setText(String.format("%.2f", xy[1]));
            joystickPosition.set(new double[]{xy[0], xy[1]});
            logger.debug("Initialized XY fields with current position: X={}, Y={}", xy[0], xy[1]);
        } catch (Exception e) {
            logger.debug("Failed to retrieve current XY stage position: {}", e.getMessage());
        }

        try {
            double z = MicroscopeController.getInstance().getStagePositionZ();
            zField.setText(String.format("%.2f", z));
            logger.debug("Initialized Z field with current position: {}", z);
        } catch (Exception e) {
            logger.debug("Failed to retrieve current Z stage position: {}", e.getMessage());
        }

        try {
            double r = MicroscopeController.getInstance().getStagePositionR();
            rField.setText(String.format("%.2f", r));
            logger.debug("Initialized R field with current position: {}", r);
        } catch (Exception e) {
            logger.debug("Failed to retrieve current R stage position: {}", e.getMessage());
        }
    }

    private void queryFov() {
        try {
            double[] fov = MicroscopeController.getInstance().getCameraFOV();
            cachedFovUm[0] = fov[0];
            cachedFovUm[1] = fov[1];
            fovInfoLabel.setText(String.format(res.getString("stageMovement.fov.format"), fov[0], fov[1]));
            logger.debug("Queried camera FOV: {} x {} um", fov[0], fov[1]);
        } catch (Exception ex) {
            logger.debug("Failed to query camera FOV: {}", ex.getMessage());
            cachedFovUm[0] = 0;
            cachedFovUm[1] = 0;
            fovInfoLabel.setText(res.getString("stageMovement.fov.unavailable"));
        }
    }

    private void applyFovStep() {
        String selection = fovStepCombo.getValue();
        boolean isValueMode = selection == null || selection.equals(res.getString("stageMovement.fov.value"));

        // Show/hide value row based on selection
        xyStepField.getParent().setVisible(isValueMode);
        xyStepField.getParent().setManaged(isValueMode);

        if (isValueMode) {
            xyStepField.setDisable(false);
            return;
        }

        try {
            double multiplier = Double.parseDouble(selection.split(" ")[0]);
            double minDim = Math.min(cachedFovUm[0], cachedFovUm[1]);
            if (minDim <= 0) {
                queryFov();
                minDim = Math.min(cachedFovUm[0], cachedFovUm[1]);
            }
            if (minDim > 0) {
                int stepValue = (int) Math.round(minDim * multiplier);
                xyStepField.setTextFormatter(null);
                xyStepField.setText(String.valueOf(stepValue));
                xyStepField.setTextFormatter(new TextFormatter<>(change -> {
                    String newText = change.getControlNewText();
                    if (newText.matches("[0-9,]*")) {
                        return change;
                    }
                    return null;
                }));
                xyStepField.setDisable(true);
                joystick.setMaxStepUm(stepValue * 0.5);
                logger.debug("FOV step set to {} um ({} x min FOV {})", stepValue, multiplier, minDim);
            }
        } catch (NumberFormatException ex) {
            logger.warn("Failed to parse FOV multiplier from: {}", selection);
        }
    }

    private void handleMoveXY() {
        try {
            double x = Double.parseDouble(xField.getText().replace(",", ""));
            double y = Double.parseDouble(yField.getText().replace(",", ""));

            if (!mgr.isWithinStageBounds(x, y)) {
                logger.warn("XY movement rejected - coordinates out of bounds: X={}, Y={}", x, y);
                UIFunctions.notifyUserOfError(
                        res.getString("stageMovement.error.outOfBoundsXY"),
                        res.getString("stageMovement.title"));
                return;
            }

            logger.info("Executing XY stage movement to position: X={}, Y={}", x, y);
            MicroscopeController.getInstance().moveStageXY(x, y);
            xyStatus.setText(String.format("Moved to (%.0f, %.0f)", x, y));
            joystickPosition.set(new double[]{x, y});
        } catch (NumberFormatException ex) {
            logger.warn("Invalid XY coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
        } catch (Exception ex) {
            logger.error("XY stage movement failed: {}", ex.getMessage(), ex);
            UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("stageMovement.title"));
        }
    }

    private void handleMoveZ() {
        try {
            double z = Double.parseDouble(zField.getText().replace(",", ""));

            if (!mgr.isWithinStageBounds(z)) {
                logger.warn("Z movement rejected - coordinate out of bounds: {}", z);
                UIFunctions.notifyUserOfError(
                        res.getString("stageMovement.error.outOfBoundsZ"),
                        res.getString("stageMovement.title"));
                return;
            }

            logger.info("Executing Z stage movement to position: {}", z);
            MicroscopeController.getInstance().moveStageZ(z);
            zStatus.setText(String.format("Moved Z to %.2f", z));
        } catch (NumberFormatException ex) {
            logger.warn("Invalid Z coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
        } catch (Exception ex) {
            logger.error("Z stage movement failed: {}", ex.getMessage(), ex);
            UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("stageMovement.title"));
        }
    }

    private void handleMoveR() {
        try {
            double r = Double.parseDouble(rField.getText().replace(",", ""));

            logger.info("Executing R stage movement to position: {}", r);
            MicroscopeController.getInstance().moveStageR(r);
            rStatus.setText(String.format("Moved R to %.2f", r));
        } catch (NumberFormatException ex) {
            logger.warn("Invalid R coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
        } catch (Exception ex) {
            logger.error("R stage movement failed: {}", ex.getMessage(), ex);
            UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("stageMovement.title"));
        }
    }

    private void handleZScroll(ScrollEvent event, TextField zStepField) {
        try {
            double step = Double.parseDouble(zStepField.getText().replace(",", ""));
            double currentZ = Double.parseDouble(zField.getText().replace(",", ""));
            double direction = event.getDeltaY() > 0 ? 1 : -1;
            double newZ = currentZ + (step * direction);

            if (!mgr.isWithinStageBounds(newZ)) {
                zStatus.setText("Z move out of bounds");
                event.consume();
                return;
            }

            MicroscopeController.getInstance().moveStageZ(newZ);
            zField.setText(String.format("%.2f", newZ));
            zStatus.setText(String.format("Scrolled Z to %.2f", newZ));
            logger.debug("Scroll Z movement to: {}", newZ);
        } catch (Exception ex) {
            logger.warn("Scroll Z movement failed: {}", ex.getMessage());
            zStatus.setText("Z scroll failed");
        }
        event.consume();
    }

    private void handleArrowMove(int xDir, int yDir) {
        try {
            double step = Double.parseDouble(xyStepField.getText().replace(",", ""));
            double currentX = Double.parseDouble(xField.getText().replace(",", ""));
            double currentY = Double.parseDouble(yField.getText().replace(",", ""));

            // Invert X direction if sample movement mode
            boolean sampleMode = sampleMovementCheckbox.isSelected();
            double xMult = sampleMode ? -1 : 1;

            double newX = currentX + (step * xDir * xMult);
            double newY = currentY + (step * yDir);

            if (!mgr.isWithinStageBounds(newX, newY)) {
                xyStatus.setText("Move out of bounds");
                return;
            }

            MicroscopeController.getInstance().moveStageXY(newX, newY);
            xField.setText(String.format("%.2f", newX));
            yField.setText(String.format("%.2f", newY));
            joystickPosition.set(new double[]{newX, newY});
            xyStatus.setText(String.format("Moved to (%.0f, %.0f)", newX, newY));
        } catch (Exception ex) {
            logger.warn("Arrow movement failed: {}", ex.getMessage());
            xyStatus.setText("Move failed");
        }
    }

    private void handleJoystickMove(double deltaX, double deltaY) {
        try {
            double[] current = joystickPosition.get();
            double currentX = current[0];
            double currentY = current[1];

            boolean sampleMode = sampleMovementMode.get();
            double xDir = sampleMode ? -1 : 1;
            double targetX = currentX + (deltaX * xDir);
            double targetY = currentY + deltaY;

            if (!mgr.isWithinStageBounds(targetX, targetY)) {
                Platform.runLater(() -> xyStatus.setText(res.getString("stageMovement.joystick.boundary")));
                return;
            }

            joystickPosition.set(new double[]{targetX, targetY});

            MicroscopeController.getInstance().moveStageXY(targetX, targetY);
            Platform.runLater(() -> {
                xField.setText(String.format("%.2f", targetX));
                yField.setText(String.format("%.2f", targetY));
                xyStatus.setText(String.format("Joystick -> (%.0f, %.0f)", targetX, targetY));
            });
        } catch (Exception ex) {
            logger.warn("Joystick movement failed: {}", ex.getMessage());
            Platform.runLater(() -> xyStatus.setText("Joystick move failed"));
        }
    }

    private void initializeCentroidButton() {
        QuPathGUI gui = QuPathGUI.getInstance();
        AffineTransform currentTransform = MicroscopeController.getInstance().getCurrentTransform();

        // Try to load slide-specific alignment if no active transform
        if (currentTransform == null && gui != null && gui.getProject() != null && gui.getImageData() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                if (imageName != null && !imageName.isEmpty()) {
                    AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(project, imageName);
                    if (slideTransform != null) {
                        currentTransform = slideTransform;
                        MicroscopeController.getInstance().setCurrentTransform(slideTransform);
                        logger.info("Loaded slide-specific alignment for image: {}", imageName);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to load slide-specific alignment: {}", e.getMessage());
            }
        }

        boolean hasAlignment = currentTransform != null;

        if (!hasAlignment) {
            goToCentroidBtn.setDisable(true);

            String currentImageName = gui != null && gui.getImageData() != null
                    ? QPProjectFunctions.getActualImageFileName(gui.getImageData())
                    : "unknown";

            @SuppressWarnings("unchecked")
            Project<BufferedImage> projectForList = gui != null && gui.getProject() != null
                    ? (Project<BufferedImage>) gui.getProject()
                    : null;
            List<String> availableAlignments = getAvailableAlignments(projectForList);

            if (availableAlignments.isEmpty()) {
                centroidStatus.setText("No alignments available");
            } else {
                centroidStatus.setText("No alignment for: " + currentImageName);
                availableLabel.setVisible(true);
                availableLabel.setManaged(true);
                alignmentListView.setVisible(true);
                alignmentListView.setManaged(true);
                alignmentListView.getItems().addAll(availableAlignments);
            }
        } else {
            centroidStatus.setText("Alignment available");
        }
    }

    private void handleGoToCentroid() {
        QuPathGUI gui = QuPathGUI.getInstance();
        AffineTransform transform = MicroscopeController.getInstance().getCurrentTransform();

        // Try to load slide-specific alignment again
        if (transform == null && gui != null && gui.getProject() != null && gui.getImageData() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                if (imageName != null && !imageName.isEmpty()) {
                    transform = AffineTransformManager.loadSlideAlignment(project, imageName);
                    if (transform != null) {
                        MicroscopeController.getInstance().setCurrentTransform(transform);
                    }
                }
            } catch (Exception ex) {
                logger.warn("Failed to load slide-specific alignment on click: {}", ex.getMessage());
            }
        }

        if (transform == null) {
            centroidStatus.setText("No alignment available");
            UIFunctions.notifyUserOfError(
                    "No alignment is available. Please perform alignment first.",
                    "Go to Centroid");
            return;
        }

        if (gui == null || gui.getImageData() == null) {
            centroidStatus.setText("No image open");
            return;
        }

        PathObject selectedObject = gui.getImageData().getHierarchy()
                .getSelectionModel().getSelectedObject();

        if (selectedObject == null) {
            centroidStatus.setText("No object selected");
            UIFunctions.notifyUserOfError(
                    "Please select an object in QuPath first.",
                    "Go to Centroid");
            return;
        }

        if (selectedObject.getROI() == null) {
            centroidStatus.setText("Selected object has no ROI");
            return;
        }

        try {
            double centroidX = selectedObject.getROI().getCentroidX();
            double centroidY = selectedObject.getROI().getCentroidY();

            double[] qpCoords = {centroidX, centroidY};
            double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                    qpCoords, transform);

            if (!mgr.isWithinStageBounds(stageCoords[0], stageCoords[1])) {
                UIFunctions.notifyUserOfError(
                        "The object centroid position is outside the stage bounds.",
                        "Go to Centroid");
                centroidStatus.setText("Position out of bounds");
                return;
            }

            MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

            centroidStatus.setText(String.format("Moved to (%.0f, %.0f)", stageCoords[0], stageCoords[1]));
            xField.setText(String.format("%.2f", stageCoords[0]));
            yField.setText(String.format("%.2f", stageCoords[1]));
            joystickPosition.set(new double[]{stageCoords[0], stageCoords[1]});
            xyStatus.setText(String.format("Moved to centroid (%.0f, %.0f)", stageCoords[0], stageCoords[1]));
        } catch (Exception ex) {
            logger.error("Failed to move to object centroid: {}", ex.getMessage(), ex);
            centroidStatus.setText("Error: " + ex.getMessage());
        }
    }

    private List<String> getAvailableAlignments(Project<BufferedImage> project) {
        List<String> alignedImages = new ArrayList<>();

        if (project == null || project.getPath() == null) {
            return alignedImages;
        }

        try {
            File projectDir = project.getPath().toFile().getParentFile();
            File alignmentDir = new File(projectDir, "alignmentFiles");

            if (!alignmentDir.exists() || !alignmentDir.isDirectory()) {
                return alignedImages;
            }

            File[] alignmentFiles = alignmentDir.listFiles((dir, name) -> name.endsWith("_alignment.json"));

            if (alignmentFiles != null) {
                for (File file : alignmentFiles) {
                    String fileName = file.getName();
                    String imageName = fileName.substring(0, fileName.length() - "_alignment.json".length());
                    alignedImages.add(imageName);
                }
            }
        } catch (Exception e) {
            logger.warn("Error listing available alignments: {}", e.getMessage());
        }

        return alignedImages;
    }

    /**
     * Handles keyboard events for WASD and arrow key navigation.
     * Call this from the parent window's key event handler.
     *
     * @param event the key event
     * @return true if the event was handled, false otherwise
     */
    public boolean handleKeyEvent(KeyEvent event) {
        if (!isExpanded()) {
            return false;
        }

        KeyCode code = event.getCode();
        switch (code) {
            case W:
            case UP:
                upBtn.fire();
                return true;
            case S:
            case DOWN:
                downBtn.fire();
                return true;
            case A:
            case LEFT:
                leftBtn.fire();
                return true;
            case D:
            case RIGHT:
                rightBtn.fire();
                return true;
            default:
                return false;
        }
    }

    /**
     * Stops the joystick and releases resources. Call on window close.
     */
    public void stop() {
        joystick.stop();
        logger.debug("StageControlPanel stopped");
    }

    /**
     * Refreshes the position fields from hardware.
     */
    public void refreshPositions() {
        initializeFromHardware();
    }
}
