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
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;
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
import qupath.ext.qpsc.utilities.StagePositionManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
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
    private final TextField zStepField;  // Shared between Position and Navigate tabs
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

    // Position synchronization listener
    private PropertyChangeListener positionListener;

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
        Tooltip sampleMvmtTooltip = new Tooltip(
                "When checked, controls move the sample rather than the stage.\n" +
                "The sample appears to move in the direction you push.");
        sampleMvmtTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(sampleMovementCheckbox, sampleMvmtTooltip);
        sampleMovementMode.set(sampleMovementCheckbox.isSelected());

        // Initialize shared Z step field
        zStepField = new TextField("10");
        zStepField.setPrefWidth(45);
        zStepField.setAlignment(Pos.CENTER);
        zStepField.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.matches("[0-9,]*")) {
                return change;
            }
            return null;
        }));
        Tooltip zStepTooltip = new Tooltip(
                "Z step size in micrometers.\n" +
                "Use mouse scroll wheel over this field, Z field,\n" +
                "or Move Z button to adjust focus.");
        zStepTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(zStepField, zStepTooltip);

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

        // Register for centralized position updates from StagePositionManager
        positionListener = this::onPositionChanged;
        StagePositionManager.getInstance().addPropertyChangeListener(positionListener);
        logger.debug("Registered with StagePositionManager for position updates");

        // Pre-fetch FOV
        queryFov();
        applyFovStep();
    }

    private VBox buildContent() {
        VBox content = new VBox(4);
        content.setPadding(new Insets(4));

        // Create TabPane with two tabs
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ============ TAB 1: POSITION (Move to specific coordinates) ============
        Tab positionTab = new Tab("Position");
        VBox positionContent = new VBox(6);
        positionContent.setPadding(new Insets(8));

        // X/Y fields and Move XY button
        Label xLabel = new Label("X:");
        xLabel.setStyle("-fx-font-size: 10px;");
        xField.setPrefWidth(70);
        Label yLabel = new Label("Y:");
        yLabel.setStyle("-fx-font-size: 10px;");
        yField.setPrefWidth(70);
        Button moveXYBtn = new Button("Move XY");
        moveXYBtn.setStyle("-fx-font-size: 10px;");
        HBox xyRow = new HBox(4, xLabel, xField, yLabel, yField, moveXYBtn);
        xyRow.setAlignment(Pos.CENTER_LEFT);
        xyStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // Z field, Move Z button, Z step control (uses shared zStepField)
        Label zLabel = new Label("Z:");
        zLabel.setStyle("-fx-font-size: 10px;");
        zField.setPrefWidth(70);
        Tooltip zFieldTooltip = new Tooltip(
                "Current Z position in micrometers.\n" +
                "Use mouse scroll wheel to adjust focus.");
        zFieldTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(zField, zFieldTooltip);

        Button moveZBtn = new Button("Move Z");
        moveZBtn.setStyle("-fx-font-size: 10px;");

        Label zStepLabel = new Label("step:");
        zStepLabel.setStyle("-fx-font-size: 10px;");
        Label zUmLabel = new Label("um");
        zUmLabel.setStyle("-fx-font-size: 10px;");

        // Help button for Z scroll behavior
        Button zHelpBtn = new Button("?");
        zHelpBtn.setStyle("-fx-font-size: 9px; -fx-min-width: 18px; -fx-min-height: 18px; -fx-padding: 0;");
        Tooltip zHelpTooltip = new Tooltip(
                "Z Focus Control via Mouse Scroll Wheel\n" +
                "=========================================\n\n" +
                "Hover your mouse over any of these controls and scroll:\n" +
                "  - Z position field\n" +
                "  - Step size field\n" +
                "  - Move Z button\n\n" +
                "Scroll UP = Move Z up (toward sample)\n" +
                "Scroll DOWN = Move Z down (away from sample)\n\n" +
                "The step size determines how much Z moves per scroll tick.");
        zHelpTooltip.setShowDelay(Duration.ZERO);
        zHelpTooltip.setShowDuration(Duration.INDEFINITE);
        zHelpTooltip.setHideDelay(Duration.millis(200));
        Tooltip.install(zHelpBtn, zHelpTooltip);

        HBox zRow = new HBox(4, zLabel, zField, moveZBtn, zStepLabel, zStepField, zUmLabel, zHelpBtn);
        zRow.setAlignment(Pos.CENTER_LEFT);
        zStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // Z scroll handler
        javafx.event.EventHandler<ScrollEvent> zScrollHandler = event -> handleZScroll(event, zStepField);
        zField.setOnScroll(zScrollHandler);
        moveZBtn.setOnScroll(zScrollHandler);
        zStepField.setOnScroll(zScrollHandler);

        // R field and Move R button
        Label rLabel = new Label("R:");
        rLabel.setStyle("-fx-font-size: 10px;");
        rField.setPrefWidth(70);
        Button moveRBtn = new Button("Move R");
        moveRBtn.setStyle("-fx-font-size: 10px;");
        HBox rRow = new HBox(4, rLabel, rField, moveRBtn);
        rRow.setAlignment(Pos.CENTER_LEFT);
        rStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // Wire up move button handlers
        moveXYBtn.setOnAction(e -> handleMoveXY());
        moveZBtn.setOnAction(e -> handleMoveZ());
        moveRBtn.setOnAction(e -> handleMoveR());

        // Go to centroid section
        HBox centroidRow = new HBox(6, goToCentroidBtn, centroidStatus);
        centroidRow.setAlignment(Pos.CENTER_LEFT);
        VBox centroidSection = new VBox(4, new Separator(), centroidRow, availableLabel, alignmentListView);

        positionContent.getChildren().addAll(
                xyRow, xyStatus,
                zRow, zStatus,
                rRow, rStatus,
                centroidSection
        );
        positionTab.setContent(positionContent);

        // ============ TAB 2: NAVIGATE (Arrows, joystick, step controls) ============
        Tab navigateTab = new Tab("Navigate");
        VBox navigateContent = new VBox(8);
        navigateContent.setPadding(new Insets(8));

        // Step size settings with tooltips
        Label stepLabel = new Label("Step:");
        stepLabel.setStyle("-fx-font-size: 10px;");
        Label valueUmLabel = new Label("um");
        valueUmLabel.setStyle("-fx-font-size: 10px;");

        // Add tooltip to xyStepField
        Tooltip xyStepTooltip = new Tooltip(
                "Step size in micrometers for arrow keys and joystick movement.\n" +
                "Arrow buttons move exactly this distance.\n" +
                "Joystick uses this as the max speed at full deflection.");
        xyStepTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(xyStepField, xyStepTooltip);

        HBox valueRow = new HBox(4, xyStepField, valueUmLabel);
        valueRow.setAlignment(Pos.CENTER_LEFT);

        Button refreshFovBtn = new Button("R");
        refreshFovBtn.setStyle("-fx-font-size: 10px; -fx-min-width: 22px; -fx-min-height: 22px; -fx-padding: 1;");
        Tooltip.install(refreshFovBtn, new Tooltip(res.getString("stageMovement.fov.refreshTooltip")));
        refreshFovBtn.setOnAction(e -> {
            queryFov();
            applyFovStep();
        });

        // Help button for step controls
        Button stepHelpBtn = new Button("?");
        stepHelpBtn.setStyle("-fx-font-size: 9px; -fx-min-width: 18px; -fx-min-height: 18px; -fx-padding: 0;");
        Tooltip stepHelpTooltip = new Tooltip(
                "XY Step Size Controls\n" +
                "======================\n\n" +
                "FOV Presets:\n" +
                "  - 1 FOV: Move one full field of view\n" +
                "  - 0.5 FOV: Half field (for overlap)\n" +
                "  - 0.25 FOV: Quarter field (fine positioning)\n" +
                "  - 0.1 FOV: Fine movement\n" +
                "  - Value: Enter custom step size\n\n" +
                "Controls:\n" +
                "  - Arrow buttons: Move exactly one step\n" +
                "  - WASD/Arrow keys: Move exactly one step\n" +
                "  - Joystick: Continuous movement, speed scales with deflection");
        stepHelpTooltip.setShowDelay(Duration.ZERO);
        stepHelpTooltip.setShowDuration(Duration.INDEFINITE);
        stepHelpTooltip.setHideDelay(Duration.millis(200));
        Tooltip.install(stepHelpBtn, stepHelpTooltip);

        // Add tooltip to FOV combo
        Tooltip fovComboTooltip = new Tooltip(
                "Select step size based on camera field of view.\n" +
                "Choose a fraction of FOV for consistent tile spacing,\n" +
                "or 'Value' to enter a custom step size.");
        fovComboTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(fovStepCombo, fovComboTooltip);

        HBox stepRow1 = new HBox(4, stepLabel, fovStepCombo, refreshFovBtn, stepHelpBtn);
        stepRow1.setAlignment(Pos.CENTER_LEFT);
        HBox stepRow2 = new HBox(4, valueRow, sampleMovementCheckbox);
        stepRow2.setAlignment(Pos.CENTER_LEFT);

        // Navigation grid: arrows around joystick
        String arrowBtnStyle = "-fx-font-size: 12px; -fx-min-width: 28px; -fx-min-height: 28px; -fx-padding: 2;";
        upBtn.setStyle(arrowBtnStyle);
        downBtn.setStyle(arrowBtnStyle);
        leftBtn.setStyle(arrowBtnStyle);
        rightBtn.setStyle(arrowBtnStyle);

        // Add tooltips to arrow buttons
        Tooltip arrowTooltip = new Tooltip("Click or use WASD/Arrow keys to move by the step amount.");
        arrowTooltip.setShowDelay(Duration.millis(500));
        Tooltip.install(upBtn, arrowTooltip);
        Tooltip.install(downBtn, arrowTooltip);
        Tooltip.install(leftBtn, arrowTooltip);
        Tooltip.install(rightBtn, arrowTooltip);

        // Add tooltip to joystick
        Tooltip joystickTooltip = new Tooltip("Drag to move stage continuously.\nSpeed scales with deflection from center.");
        joystickTooltip.setShowDelay(Duration.millis(500));
        Tooltip.install(joystick, joystickTooltip);

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

        Label keyboardHint = new Label("WASD / Arrows / Drag joystick");
        keyboardHint.setStyle("-fx-font-size: 9px; -fx-text-fill: #666666;");

        VBox navSection = new VBox(4, navGrid, keyboardHint);
        navSection.setAlignment(Pos.CENTER);

        // Z scroll section for Navigate tab (shares zField and zStepField with Position tab)
        Label navZLabel = new Label("Z (scroll):");
        navZLabel.setStyle("-fx-font-size: 10px;");

        // Create a display-only field that mirrors zField
        TextField navZField = new TextField();
        navZField.setPrefWidth(70);
        navZField.textProperty().bindBidirectional(zField.textProperty());
        Tooltip navZFieldTooltip = new Tooltip(
                "Current Z position. Scroll mouse wheel here to adjust focus.\n" +
                "Step size is controlled by the step field to the right.");
        navZFieldTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(navZField, navZFieldTooltip);

        Label navZStepLabel = new Label("step:");
        navZStepLabel.setStyle("-fx-font-size: 10px;");

        // Create a display-only field that mirrors zStepField
        TextField navZStepFieldMirror = new TextField();
        navZStepFieldMirror.setPrefWidth(45);
        navZStepFieldMirror.setAlignment(Pos.CENTER);
        navZStepFieldMirror.textProperty().bindBidirectional(zStepField.textProperty());
        Tooltip navZStepTooltip = new Tooltip(
                "Z step size in micrometers.\n" +
                "Scroll mouse wheel over Z controls to adjust focus.");
        navZStepTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(navZStepFieldMirror, navZStepTooltip);

        Label navZUmLabel = new Label("um");
        navZUmLabel.setStyle("-fx-font-size: 10px;");

        // Help button for Z in Navigate tab
        Button navZHelpBtn = new Button("?");
        navZHelpBtn.setStyle("-fx-font-size: 9px; -fx-min-width: 18px; -fx-min-height: 18px; -fx-padding: 0;");
        Tooltip navZHelpTooltip = new Tooltip(
                "Z Focus Control via Mouse Scroll Wheel\n" +
                "=========================================\n\n" +
                "Hover your mouse over any of these controls and scroll:\n" +
                "  - Z position field\n" +
                "  - Step size field\n\n" +
                "Scroll UP = Move Z up (toward sample)\n" +
                "Scroll DOWN = Move Z down (away from sample)\n\n" +
                "The step size determines how much Z moves per scroll tick.");
        navZHelpTooltip.setShowDelay(Duration.ZERO);
        navZHelpTooltip.setShowDuration(Duration.INDEFINITE);
        navZHelpTooltip.setHideDelay(Duration.millis(200));
        Tooltip.install(navZHelpBtn, navZHelpTooltip);

        HBox navZRow = new HBox(4, navZLabel, navZField, navZStepLabel, navZStepFieldMirror, navZUmLabel, navZHelpBtn);
        navZRow.setAlignment(Pos.CENTER_LEFT);

        // Z scroll handler for navigate tab
        javafx.event.EventHandler<ScrollEvent> navZScrollHandler = event -> handleZScroll(event, zStepField);
        navZField.setOnScroll(navZScrollHandler);
        navZStepFieldMirror.setOnScroll(navZScrollHandler);

        // Z status for navigate tab (bound to main zStatus)
        Label navZStatus = new Label();
        navZStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
        navZStatus.textProperty().bind(zStatus.textProperty());

        VBox navZSection = new VBox(2, navZRow);
        navZSection.setAlignment(Pos.CENTER_LEFT);

        // XY status shown in navigate tab too
        Label navXyStatus = new Label();
        navXyStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
        // Bind to xyStatus text
        navXyStatus.textProperty().bind(xyStatus.textProperty());

        navigateContent.getChildren().addAll(
                stepRow1, stepRow2, fovInfoLabel,
                new Separator(),
                navSection,
                navXyStatus,
                new Separator(),
                navZSection,
                navZStatus
        );
        navigateTab.setContent(navigateContent);

        // Add tabs to TabPane and default to Navigate tab
        tabPane.getTabs().addAll(positionTab, navigateTab);
        tabPane.getSelectionModel().select(navigateTab);

        content.getChildren().add(tabPane);
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
        // Run socket operations on background thread to avoid blocking FX thread
        Thread initThread = new Thread(() -> {
            try {
                double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                Platform.runLater(() -> {
                    xField.setText(String.format("%.2f", xy[0]));
                    yField.setText(String.format("%.2f", xy[1]));
                    joystickPosition.set(new double[]{xy[0], xy[1]});
                });
                logger.debug("Initialized XY fields with current position: X={}, Y={}", xy[0], xy[1]);
            } catch (Exception e) {
                logger.debug("Failed to retrieve current XY stage position: {}", e.getMessage());
            }

            try {
                double z = MicroscopeController.getInstance().getStagePositionZ();
                Platform.runLater(() -> zField.setText(String.format("%.2f", z)));
                logger.debug("Initialized Z field with current position: {}", z);
            } catch (Exception e) {
                logger.debug("Failed to retrieve current Z stage position: {}", e.getMessage());
            }

            try {
                double r = MicroscopeController.getInstance().getStagePositionR();
                Platform.runLater(() -> rField.setText(String.format("%.2f", r)));
                logger.debug("Initialized R field with current position: {}", r);
            } catch (Exception e) {
                logger.debug("Failed to retrieve current R stage position: {}", e.getMessage());
            }
        }, "StageControl-Init");
        initThread.setDaemon(true);
        initThread.start();
    }

    /**
     * Handles position change events from the StagePositionManager.
     * Updates the corresponding text field and joystick tracking when positions change.
     *
     * @param evt The property change event containing the new position
     */
    private void onPositionChanged(PropertyChangeEvent evt) {
        // Ensure we're on the FX Application Thread for UI updates
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> onPositionChanged(evt));
            return;
        }

        Object newValue = evt.getNewValue();
        if (!(newValue instanceof Double)) {
            return;
        }
        double newVal = (Double) newValue;

        switch (evt.getPropertyName()) {
            case StagePositionManager.PROP_POS_X -> {
                xField.setText(String.format("%.2f", newVal));
                // Update joystick tracking
                double[] current = joystickPosition.get();
                joystickPosition.set(new double[]{newVal, current[1]});
            }
            case StagePositionManager.PROP_POS_Y -> {
                yField.setText(String.format("%.2f", newVal));
                // Update joystick tracking
                double[] current = joystickPosition.get();
                joystickPosition.set(new double[]{current[0], newVal});
            }
            case StagePositionManager.PROP_POS_Z -> zField.setText(String.format("%.2f", newVal));
            case StagePositionManager.PROP_POS_R -> rField.setText(String.format("%.2f", newVal));
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
            xyStatus.setText("Moving...");
            joystickPosition.set(new double[]{x, y});

            // Run socket operation on background thread to avoid blocking FX thread
            Thread moveThread = new Thread(() -> {
                try {
                    MicroscopeController.getInstance().moveStageXY(x, y);
                    Platform.runLater(() -> xyStatus.setText(String.format("Moved to (%.0f, %.0f)", x, y)));
                } catch (Exception ex) {
                    logger.error("XY stage movement failed: {}", ex.getMessage(), ex);
                    Platform.runLater(() -> {
                        xyStatus.setText("Move failed");
                        UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("stageMovement.title"));
                    });
                }
            }, "StageControl-MoveXY");
            moveThread.setDaemon(true);
            moveThread.start();
        } catch (NumberFormatException ex) {
            logger.warn("Invalid XY coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
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
            zStatus.setText("Moving...");

            // Run socket operation on background thread to avoid blocking FX thread
            Thread moveThread = new Thread(() -> {
                try {
                    MicroscopeController.getInstance().moveStageZ(z);
                    Platform.runLater(() -> zStatus.setText(String.format("Moved Z to %.2f", z)));
                } catch (Exception ex) {
                    logger.error("Z stage movement failed: {}", ex.getMessage(), ex);
                    Platform.runLater(() -> {
                        zStatus.setText("Move failed");
                        UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("stageMovement.title"));
                    });
                }
            }, "StageControl-MoveZ");
            moveThread.setDaemon(true);
            moveThread.start();
        } catch (NumberFormatException ex) {
            logger.warn("Invalid Z coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
        }
    }

    private void handleMoveR() {
        try {
            double r = Double.parseDouble(rField.getText().replace(",", ""));

            logger.info("Executing R stage movement to position: {}", r);
            rStatus.setText("Moving...");

            // Run socket operation on background thread to avoid blocking FX thread
            Thread moveThread = new Thread(() -> {
                try {
                    MicroscopeController.getInstance().moveStageR(r);
                    Platform.runLater(() -> rStatus.setText(String.format("Moved R to %.2f", r)));
                } catch (Exception ex) {
                    logger.error("R stage movement failed: {}", ex.getMessage(), ex);
                    Platform.runLater(() -> {
                        rStatus.setText("Move failed");
                        UIFunctions.notifyUserOfError(ex.getMessage(), res.getString("stageMovement.title"));
                    });
                }
            }, "StageControl-MoveR");
            moveThread.setDaemon(true);
            moveThread.start();
        } catch (NumberFormatException ex) {
            logger.warn("Invalid R coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
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

            zField.setText(String.format("%.2f", newZ));
            zStatus.setText("Moving...");
            logger.debug("Scroll Z movement to: {}", newZ);

            // Run socket operation on background thread to avoid blocking FX thread
            Thread moveThread = new Thread(() -> {
                try {
                    MicroscopeController.getInstance().moveStageZ(newZ);
                    Platform.runLater(() -> zStatus.setText(String.format("Scrolled Z to %.2f", newZ)));
                } catch (Exception ex) {
                    logger.warn("Z scroll movement failed: {}", ex.getMessage());
                    Platform.runLater(() -> zStatus.setText("Z scroll failed"));
                }
            }, "StageControl-ZScroll");
            moveThread.setDaemon(true);
            moveThread.start();
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

            // Y is inverted by default to match Micro-Manager's stage convention.
            // In sample movement mode, both X and Y are flipped so the sample
            // appears to move in the direction of the arrow/joystick.
            boolean sampleMode = sampleMovementCheckbox.isSelected();
            double xMult = sampleMode ? -1 : 1;
            double yMult = sampleMode ? 1 : -1;

            double newX = currentX + (step * xDir * xMult);
            double newY = currentY + (step * yDir * yMult);

            if (!mgr.isWithinStageBounds(newX, newY)) {
                xyStatus.setText("Move out of bounds");
                return;
            }

            // Update UI immediately with expected position
            xField.setText(String.format("%.2f", newX));
            yField.setText(String.format("%.2f", newY));
            joystickPosition.set(new double[]{newX, newY});
            xyStatus.setText("Moving...");

            // Run socket operation on background thread to avoid blocking FX thread
            // (which could cause "Not Responding" if socket is held by another operation)
            Thread moveThread = new Thread(() -> {
                try {
                    MicroscopeController.getInstance().moveStageXY(newX, newY);
                    Platform.runLater(() -> xyStatus.setText(String.format("Moved to (%.0f, %.0f)", newX, newY)));
                } catch (Exception ex) {
                    logger.warn("Arrow movement failed: {}", ex.getMessage());
                    Platform.runLater(() -> xyStatus.setText("Move failed"));
                }
            }, "StageControl-ArrowMove");
            moveThread.setDaemon(true);
            moveThread.start();
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

            // The joystick's deltaY follows screen coordinates (negative = up),
            // which is opposite from the arrow keys' yDir convention (positive = up).
            // So the Y multiplier is inverted compared to handleArrowMove.
            // Default: joystick up (deltaY<0) should decrease Y (match MM convention)
            // Sample mode: joystick up should increase Y (sample moves with joystick)
            boolean sampleMode = sampleMovementMode.get();
            double xMult = sampleMode ? -1 : 1;
            double yMult = sampleMode ? -1 : 1;
            double targetX = currentX + (deltaX * xMult);
            double targetY = currentY + (deltaY * yMult);

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

            // Update UI immediately with expected position
            double targetX = stageCoords[0];
            double targetY = stageCoords[1];
            xField.setText(String.format("%.2f", targetX));
            yField.setText(String.format("%.2f", targetY));
            joystickPosition.set(new double[]{targetX, targetY});
            centroidStatus.setText("Moving...");
            xyStatus.setText("Moving to centroid...");

            // Run socket operation on background thread to avoid blocking FX thread
            Thread moveThread = new Thread(() -> {
                try {
                    MicroscopeController.getInstance().moveStageXY(targetX, targetY);
                    Platform.runLater(() -> {
                        centroidStatus.setText(String.format("Moved to (%.0f, %.0f)", targetX, targetY));
                        xyStatus.setText(String.format("Moved to centroid (%.0f, %.0f)", targetX, targetY));
                    });
                } catch (Exception ex) {
                    logger.error("Failed to move to object centroid: {}", ex.getMessage(), ex);
                    Platform.runLater(() -> {
                        centroidStatus.setText("Move failed");
                        xyStatus.setText("Centroid move failed");
                    });
                }
            }, "StageControl-GoToCentroid");
            moveThread.setDaemon(true);
            moveThread.start();
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

        // Unregister from StagePositionManager to stop position polling
        if (positionListener != null) {
            StagePositionManager.getInstance().removePropertyChangeListener(positionListener);
            positionListener = null;
            logger.debug("Unregistered from StagePositionManager");
        }

        logger.debug("StageControlPanel stopped");
    }

    /**
     * Refreshes the position fields from hardware.
     */
    public void refreshPositions() {
        initializeFromHardware();
    }
}
