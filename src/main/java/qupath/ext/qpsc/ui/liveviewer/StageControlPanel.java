package qupath.ext.qpsc.ui.liveviewer;

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
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.VirtualJoystick;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.StagePositionManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

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
public class StageControlPanel extends VBox {

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
    private final TextField zStepField; // Shared between Position and Navigate tabs
    private final ComboBox<String> fovStepCombo;
    private final Label fovInfoLabel;
    private final double[] cachedFovUm = {0, 0};

    // Navigation components
    private final VirtualJoystick joystick;
    // Single-step arrows
    private final Button upBtn = new Button("\u2191");
    private final Button downBtn = new Button("\u2193");
    private final Button leftBtn = new Button("\u2190");
    private final Button rightBtn = new Button("\u2192");
    // Double-step arrows (move 2x distance)
    private final Button upBtn2x = new Button("\u21C8"); // Double up arrow
    private final Button downBtn2x = new Button("\u21CA"); // Double down arrow
    private final Button leftBtn2x = new Button("\u21C7"); // Double left arrow
    private final Button rightBtn2x = new Button("\u21C9"); // Double right arrow

    // Sample movement mode
    private final CheckBox sampleMovementCheckbox;
    private final AtomicBoolean sampleMovementMode = new AtomicBoolean(false);

    // Thread-safe position tracking for joystick
    private final AtomicReference<double[]> joystickPosition = new AtomicReference<>(new double[] {0, 0});

    // Go to centroid components
    private final Button goToCentroidBtn;
    private final Label centroidStatus;
    private final Label availableLabel;
    private final ListView<String> alignmentListView;

    // Position synchronization listener
    private PropertyChangeListener positionListener;

    // Saved Points tab components
    private ListView<SavedPoint> savedPointsListView;
    private Label savedPointsStatus;

    /**
     * Represents a saved stage position with name and XYZ coordinates.
     */
    public static class SavedPoint {
        private String name;
        private double x;
        private double y;
        private double z;

        public SavedPoint(String name, double x, double y, double z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public String getName() {
            return name;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }

        @Override
        public String toString() {
            return String.format("%s: X=%.0f, Y=%.0f, Z=%.1f", name, x, y, z);
        }

        /**
         * Serializes this point to a JSON object string.
         */
        public String toJson() {
            // Escape quotes in name for JSON safety
            String escapedName = name.replace("\\", "\\\\").replace("\"", "\\\"");
            return String.format("{\"name\":\"%s\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}", escapedName, x, y, z);
        }

        /**
         * Parses a SavedPoint from a JSON object string.
         * @param json JSON object string like {"name":"Point 1","x":1234.5,"y":2345.6,"z":100.0}
         * @return Parsed SavedPoint or null if parsing fails
         */
        public static SavedPoint fromJson(String json) {
            try {
                // Simple JSON parsing without external library
                String name = extractJsonString(json, "name");
                double x = extractJsonDouble(json, "x");
                double y = extractJsonDouble(json, "y");
                double z = extractJsonDouble(json, "z");
                if (name != null) {
                    return new SavedPoint(name, x, y, z);
                }
            } catch (Exception e) {
                // Parsing failed
            }
            return null;
        }

        private static String extractJsonString(String json, String key) {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start < 0) return null;
            start += pattern.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return null;
            return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
        }

        private static double extractJsonDouble(String json, String key) {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start < 0) return 0;
            start += pattern.length();
            int end = start;
            while (end < json.length()
                    && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) {
                end++;
            }
            return Double.parseDouble(json.substring(start, end));
        }
    }

    /**
     * Creates a new StageControlPanel with all stage control components.
     */
    public StageControlPanel() {
        // No title bar -- visibility is controlled by the toolbar toggle in LiveViewerWindow

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
        sampleMovementCheckbox = new CheckBox("Sample Movement");
        sampleMovementCheckbox.setSelected(PersistentPreferences.getStageControlSampleMovement());
        sampleMovementCheckbox.setStyle("-fx-font-size: 10px;");
        Tooltip sampleMvmtTooltip = new Tooltip("When unchecked, controls match MicroManager stage behavior.\n"
                + "When checked, inverts X axis so sample appears to move with controls.");
        sampleMvmtTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(sampleMovementCheckbox, sampleMvmtTooltip);
        sampleMovementMode.set(sampleMovementCheckbox.isSelected());

        // Initialize shared Z step field from preferences
        zStepField = new TextField(PersistentPreferences.getStageControlZStepSize());
        zStepField.setPrefWidth(45);
        zStepField.setAlignment(Pos.CENTER);
        zStepField.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.matches("[0-9,]*")) {
                return change;
            }
            return null;
        }));
        Tooltip zStepTooltip = new Tooltip("Z step size in micrometers.\n"
                + "Use mouse scroll wheel over this field, Z field,\n" + "or Move Z button to adjust focus.");
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
        getChildren().add(content);
        VBox.setVgrow(content, javafx.scene.layout.Priority.ALWAYS);

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

        // Create UI controls for move-to-position (placed in Navigate tab below Z scroll)
        Label xLabel = new Label("X:");
        xLabel.setStyle("-fx-font-size: 10px;");
        xField.setPrefWidth(70);
        Label yLabel = new Label("Y:");
        yLabel.setStyle("-fx-font-size: 10px;");
        yField.setPrefWidth(70);
        Button moveXYBtn = new Button("Move XY");
        moveXYBtn.setStyle("-fx-font-size: 10px;");
        xyStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        Label zLabel = new Label("Z:");
        zLabel.setStyle("-fx-font-size: 10px;");
        zField.setPrefWidth(70);
        Tooltip zFieldTooltip =
                new Tooltip("Current Z position in micrometers.\n" + "Use mouse scroll wheel to adjust focus.");
        zFieldTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(zField, zFieldTooltip);

        Button moveZBtn = new Button("Move Z");
        moveZBtn.setStyle("-fx-font-size: 10px;");

        Label zStepLabel = new Label("step:");
        zStepLabel.setStyle("-fx-font-size: 10px;");
        Label zUmLabel = new Label("um");
        zUmLabel.setStyle("-fx-font-size: 10px;");

        Button zHelpBtn = new Button("?");
        zHelpBtn.setStyle("-fx-font-size: 9px; -fx-min-width: 18px; -fx-min-height: 18px; -fx-padding: 0;");
        Tooltip zHelpTooltip =
                new Tooltip("Z Focus Control via Mouse Scroll Wheel\n" + "=========================================\n\n"
                        + "Hover your mouse over any of these controls and scroll:\n"
                        + "  - Z position field\n"
                        + "  - Step size field\n"
                        + "  - Move Z button\n\n"
                        + "Scroll UP = Move Z up (toward sample)\n"
                        + "Scroll DOWN = Move Z down (away from sample)\n\n"
                        + "The step size determines how much Z moves per scroll tick.");
        zHelpTooltip.setShowDelay(Duration.ZERO);
        zHelpTooltip.setShowDuration(Duration.INDEFINITE);
        zHelpTooltip.setHideDelay(Duration.millis(200));
        Tooltip.install(zHelpBtn, zHelpTooltip);

        zStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // Z scroll handler
        javafx.event.EventHandler<ScrollEvent> zScrollHandler = event -> handleZScroll(event, zStepField);
        zField.setOnScroll(zScrollHandler);
        moveZBtn.setOnScroll(zScrollHandler);
        zStepField.setOnScroll(zScrollHandler);

        Label rLabel = new Label("R:");
        rLabel.setStyle("-fx-font-size: 10px;");
        rField.setPrefWidth(70);
        Button moveRBtn = new Button("Move R");
        moveRBtn.setStyle("-fx-font-size: 10px;");
        rStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        // Wire up move button handlers
        moveXYBtn.setOnAction(e -> handleMoveXY());
        moveZBtn.setOnAction(e -> handleMoveZ());
        moveRBtn.setOnAction(e -> handleMoveR());

        // Enter key in text fields triggers the corresponding move
        xField.setOnAction(e -> handleMoveXY());
        yField.setOnAction(e -> handleMoveXY());
        zField.setOnAction(e -> handleMoveZ());
        rField.setOnAction(e -> handleMoveR());

        // ============ TAB 1: NAVIGATE (Arrows, joystick, step controls, position, centroid) ============
        Tab navigateTab = new Tab("Navigate");
        VBox navigateContent = new VBox(8);
        navigateContent.setPadding(new Insets(8));

        // Step size settings with tooltips
        Label stepLabel = new Label("Step:");
        stepLabel.setStyle("-fx-font-size: 10px;");
        Label valueUmLabel = new Label("um");
        valueUmLabel.setStyle("-fx-font-size: 10px;");

        // Add tooltip to xyStepField
        Tooltip xyStepTooltip = new Tooltip("Step size in micrometers for arrow keys and joystick movement.\n"
                + "Arrow buttons move exactly this distance.\n"
                + "Joystick uses this as the max speed at full deflection.");
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
        Tooltip stepHelpTooltip = new Tooltip("XY Step Size Controls\n" + "======================\n\n"
                + "FOV Presets:\n"
                + "  - 1 FOV: Move one full field of view\n"
                + "  - 0.5 FOV: Half field (for overlap)\n"
                + "  - 0.25 FOV: Quarter field (fine positioning)\n"
                + "  - 0.1 FOV: Fine movement\n"
                + "  - Value: Enter custom step size\n\n"
                + "Controls:\n"
                + "  - Arrow buttons: Move exactly one step\n"
                + "  - WASD/Arrow keys: Move exactly one step\n"
                + "  - Joystick: Continuous movement, speed scales with deflection");
        stepHelpTooltip.setShowDelay(Duration.ZERO);
        stepHelpTooltip.setShowDuration(Duration.INDEFINITE);
        stepHelpTooltip.setHideDelay(Duration.millis(200));
        Tooltip.install(stepHelpBtn, stepHelpTooltip);

        // Add tooltip to FOV combo
        Tooltip fovComboTooltip = new Tooltip("Select step size based on camera field of view.\n"
                + "Choose a fraction of FOV for consistent tile spacing,\n"
                + "or 'Value' to enter a custom step size.");
        fovComboTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(fovStepCombo, fovComboTooltip);

        HBox stepRow1 = new HBox(4, stepLabel, fovStepCombo, refreshFovBtn, stepHelpBtn);
        stepRow1.setAlignment(Pos.CENTER_LEFT);
        HBox stepRow2 = new HBox(4, valueRow, sampleMovementCheckbox);
        stepRow2.setAlignment(Pos.CENTER_LEFT);

        // Navigation grid: arrows around joystick
        // Single-step buttons: half dimensions (up/down half height, left/right half width)
        String singleUpDownStyle =
                "-fx-font-size: 10px; -fx-min-width: 28px; -fx-min-height: 14px; -fx-max-height: 14px; -fx-padding: 0;";
        String singleLeftRightStyle =
                "-fx-font-size: 10px; -fx-min-width: 14px; -fx-max-width: 14px; -fx-min-height: 28px; -fx-padding: 0;";
        upBtn.setStyle(singleUpDownStyle);
        downBtn.setStyle(singleUpDownStyle);
        leftBtn.setStyle(singleLeftRightStyle);
        rightBtn.setStyle(singleLeftRightStyle);

        // Double-step buttons: same half dimensions as single buttons
        String doubleUpDownStyle =
                "-fx-font-size: 10px; -fx-min-width: 28px; -fx-min-height: 14px; -fx-max-height: 14px; -fx-padding: 0;";
        String doubleLeftRightStyle =
                "-fx-font-size: 10px; -fx-min-width: 14px; -fx-max-width: 14px; -fx-min-height: 28px; -fx-padding: 0;";
        upBtn2x.setStyle(doubleUpDownStyle);
        downBtn2x.setStyle(doubleUpDownStyle);
        leftBtn2x.setStyle(doubleLeftRightStyle);
        rightBtn2x.setStyle(doubleLeftRightStyle);

        // Add tooltips to arrow buttons
        Tooltip arrowTooltip = new Tooltip("Click or use WASD/Arrow keys to move by the step amount.");
        arrowTooltip.setShowDelay(Duration.millis(500));
        Tooltip.install(upBtn, arrowTooltip);
        Tooltip.install(downBtn, arrowTooltip);
        Tooltip.install(leftBtn, arrowTooltip);
        Tooltip.install(rightBtn, arrowTooltip);

        Tooltip doubleArrowTooltip = new Tooltip("Move 2x the step distance.");
        doubleArrowTooltip.setShowDelay(Duration.millis(500));
        Tooltip.install(upBtn2x, doubleArrowTooltip);
        Tooltip.install(downBtn2x, doubleArrowTooltip);
        Tooltip.install(leftBtn2x, doubleArrowTooltip);
        Tooltip.install(rightBtn2x, doubleArrowTooltip);

        // Add tooltip to joystick
        Tooltip joystickTooltip =
                new Tooltip("Drag to move stage continuously.\nSpeed scales with deflection from center.");
        joystickTooltip.setShowDelay(Duration.millis(500));
        Tooltip.install(joystick, joystickTooltip);

        // Layout:
        //            [upBtn2x]     (row 0, col 2)
        //             [upBtn]      (row 1, col 2)
        // [leftBtn2x][leftBtn][joystick][rightBtn][rightBtn2x]  (row 2, cols 0-4)
        //            [downBtn]     (row 3, col 2)
        //           [downBtn2x]    (row 4, col 2)
        GridPane navGrid = new GridPane();
        navGrid.setAlignment(Pos.CENTER);
        navGrid.setHgap(2);
        navGrid.setVgap(2);

        // Row 0: 2x up button
        navGrid.add(upBtn2x, 2, 0);
        GridPane.setHalignment(upBtn2x, HPos.CENTER);

        // Row 1: 1x up button
        navGrid.add(upBtn, 2, 1);
        GridPane.setHalignment(upBtn, HPos.CENTER);

        // Row 2: left buttons, joystick, right buttons
        navGrid.add(leftBtn2x, 0, 2);
        GridPane.setValignment(leftBtn2x, VPos.CENTER);
        navGrid.add(leftBtn, 1, 2);
        GridPane.setValignment(leftBtn, VPos.CENTER);
        navGrid.add(joystick, 2, 2);
        navGrid.add(rightBtn, 3, 2);
        GridPane.setValignment(rightBtn, VPos.CENTER);
        navGrid.add(rightBtn2x, 4, 2);
        GridPane.setValignment(rightBtn2x, VPos.CENTER);

        // Row 3: 1x down button
        navGrid.add(downBtn, 2, 3);
        GridPane.setHalignment(downBtn, HPos.CENTER);

        // Row 4: 2x down button
        navGrid.add(downBtn2x, 2, 4);
        GridPane.setHalignment(downBtn2x, HPos.CENTER);

        Label keyboardHint = new Label("WASD / Arrows / Drag joystick");
        keyboardHint.setStyle("-fx-font-size: 9px; -fx-text-fill: #666666;");

        VBox navSection = new VBox(4, navGrid, keyboardHint);
        navSection.setAlignment(Pos.CENTER);

        // XY status shown below navigation grid
        Label navXyStatus = new Label();
        navXyStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
        navXyStatus.textProperty().bind(xyStatus.textProperty());

        // --- Move-to-position controls (formerly the Position tab) ---
        Label moveToLabel = new Label("Move to Position");
        moveToLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        HBox moveXyRow = new HBox(4, xLabel, xField, yLabel, yField, moveXYBtn);
        moveXyRow.setAlignment(Pos.CENTER_LEFT);

        HBox moveZRow = new HBox(4, zLabel, zField, moveZBtn, zStepLabel, zStepField, zUmLabel, zHelpBtn);
        moveZRow.setAlignment(Pos.CENTER_LEFT);

        HBox moveRRow = new HBox(4, rLabel, rField, moveRBtn);
        moveRRow.setAlignment(Pos.CENTER_LEFT);

        // Hide rotation controls when no rotation stage is configured
        boolean hasRotation = false;
        try {
            MicroscopeConfigManager config = MicroscopeConfigManager.getInstanceIfAvailable();
            hasRotation = config != null && config.hasRotationStage();
        } catch (Exception ignored) {
            // Config not loaded yet -- default to hidden
        }
        moveRRow.setVisible(hasRotation);
        moveRRow.setManaged(hasRotation);
        rStatus.setVisible(hasRotation);
        rStatus.setManaged(hasRotation);

        HBox centroidRow = new HBox(6, goToCentroidBtn, centroidStatus);
        centroidRow.setAlignment(Pos.CENTER_LEFT);
        VBox centroidSection = new VBox(4, centroidRow, availableLabel, alignmentListView);

        navigateContent
                .getChildren()
                .addAll(
                        stepRow1,
                        stepRow2,
                        fovInfoLabel,
                        new Separator(),
                        navSection,
                        navXyStatus,
                        new Separator(),
                        moveToLabel,
                        moveXyRow,
                        xyStatus,
                        moveZRow,
                        zStatus,
                        moveRRow,
                        rStatus,
                        new Separator(),
                        centroidSection);
        navigateTab.setContent(navigateContent);

        // ============ TAB 2: SAVED POINTS (was Tab 3) ============
        Tab savedPointsTab = new Tab("Saved Points");
        VBox savedPointsContent = new VBox(8);
        savedPointsContent.setPadding(new Insets(8));

        // Add point button
        Button addPointBtn = new Button("Add Current Point...");
        addPointBtn.setStyle("-fx-font-size: 10px;");
        Tooltip addPointTooltip = new Tooltip("Save the current stage position with a custom name.");
        addPointTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(addPointBtn, addPointTooltip);

        // Points list
        savedPointsListView = new ListView<>();
        savedPointsListView.setPrefHeight(100);

        // Action buttons
        Button goToXYBtn = new Button("Go to XY");
        goToXYBtn.setStyle("-fx-font-size: 10px;");
        Tooltip goXYTooltip = new Tooltip("Move to selected point's XY position, keeping current Z.");
        goXYTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(goToXYBtn, goXYTooltip);

        Button goToXYZBtn = new Button("Go to XYZ");
        goToXYZBtn.setStyle("-fx-font-size: 10px;");
        Tooltip goXYZTooltip = new Tooltip("Move to selected point's full XYZ position.");
        goXYZTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(goToXYZBtn, goXYZTooltip);

        Button removeBtn = new Button("Remove");
        removeBtn.setStyle("-fx-font-size: 10px;");
        Tooltip removeTooltip = new Tooltip("Remove the selected point from the list.");
        removeTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(removeBtn, removeTooltip);

        Button clearAllBtn = new Button("Clear All");
        clearAllBtn.setStyle("-fx-font-size: 10px;");
        Tooltip clearTooltip = new Tooltip("Remove all saved points.");
        clearTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(clearAllBtn, clearTooltip);

        HBox goButtons = new HBox(4, goToXYBtn, goToXYZBtn);
        goButtons.setAlignment(Pos.CENTER_LEFT);
        HBox manageButtons = new HBox(4, removeBtn, clearAllBtn);
        manageButtons.setAlignment(Pos.CENTER_LEFT);

        // Status label
        savedPointsStatus = new Label();
        savedPointsStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

        savedPointsContent
                .getChildren()
                .addAll(addPointBtn, savedPointsListView, goButtons, manageButtons, savedPointsStatus);
        savedPointsTab.setContent(savedPointsContent);

        // Wire up Saved Points event handlers
        addPointBtn.setOnAction(e -> handleAddSavedPoint());
        goToXYBtn.setOnAction(e -> handleGoToSavedPoint(false));
        goToXYZBtn.setOnAction(e -> handleGoToSavedPoint(true));
        removeBtn.setOnAction(e -> handleRemoveSavedPoint());
        clearAllBtn.setOnAction(e -> handleClearAllSavedPoints());

        // Load saved points from preferences
        loadSavedPointsFromPrefs();

        // Camera tab with WB angle presets
        Tab cameraTab = buildCameraTab();

        // Add tabs to TabPane and default to Navigate tab
        tabPane.getTabs().addAll(navigateTab, savedPointsTab, cameraTab);
        tabPane.getSelectionModel().select(navigateTab);

        content.getChildren().add(tabPane);
        return content;
    }

    // ------------------------------------------------------------------
    // Camera tab (modality-dependent)
    // ------------------------------------------------------------------

    private Label cameraStatusLabel;
    private VBox cameraModContent; // Swapped when modality changes
    private volatile String currentCameraModality; // Current modality for preset refresh
    private String currentCameraObjectiveId;
    private String currentCameraDetectorId;

    /**
     * Builds the Camera tab with modality dropdown and modality-specific content.
     */
    private Tab buildCameraTab() {
        Tab tab = new Tab("Camera");
        VBox cameraContent = new VBox(6);
        cameraContent.setPadding(new Insets(6));

        // Detect current objective/detector by matching MicroManager pixel size
        detectCurrentHardware();

        // Hardware info (read-only)
        GridPane hwGrid = new GridPane();
        hwGrid.setHgap(4);
        hwGrid.setVgap(2);
        Label detLabel = new Label("Detector:");
        detLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        Label detValue = new Label(shortenId(currentCameraDetectorId));
        detValue.setStyle("-fx-font-size: 10px;");
        detValue.setTooltip(new Tooltip(currentCameraDetectorId));
        Label objLabel = new Label("Objective:");
        objLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        Label objValue = new Label(shortenId(currentCameraObjectiveId));
        objValue.setStyle("-fx-font-size: 10px;");
        objValue.setTooltip(new Tooltip(currentCameraObjectiveId));
        hwGrid.add(detLabel, 0, 0);
        hwGrid.add(detValue, 1, 0);
        hwGrid.add(objLabel, 0, 1);
        hwGrid.add(objValue, 1, 1);

        // Modality dropdown
        ComboBox<String> modalityCombo = new ComboBox<>();
        try {
            var modalities = mgr.getAvailableModalities();
            if (modalities != null) modalityCombo.getItems().addAll(modalities);
        } catch (Exception e) {
            logger.debug("Could not load modalities: {}", e.getMessage());
        }
        if (modalityCombo.getItems().isEmpty()) modalityCombo.getItems().add("ppm");
        modalityCombo.setValue(modalityCombo.getItems().get(0));
        modalityCombo.setMaxWidth(Double.MAX_VALUE);
        modalityCombo.setStyle("-fx-font-size: 10px;");

        HBox modRow = new HBox(4, new Label("Modality:"), modalityCombo);
        modRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        ((Label) modRow.getChildren().get(0)).setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        HBox.setHgrow(modalityCombo, javafx.scene.layout.Priority.ALWAYS);

        // Modality-specific content area (rebuilt on modality change)
        cameraModContent = new VBox(6);

        // Status label (shared across modalities)
        cameraStatusLabel = new Label();
        cameraStatusLabel.setStyle("-fx-font-size: 10px;");
        cameraStatusLabel.setWrapText(true);

        // Full Camera Control button (shared)
        Button fullControlBtn = new Button("Full Camera Control...");
        fullControlBtn.setMaxWidth(Double.MAX_VALUE);
        fullControlBtn.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        fullControlBtn.setOnAction(e -> {
            try {
                qupath.ext.qpsc.controller.QPScopeController.getInstance().startWorkflow("cameraControl");
            } catch (Exception ex) {
                logger.warn("Could not open Camera Control: {}", ex.getMessage());
            }
        });

        cameraContent
                .getChildren()
                .addAll(
                        hwGrid,
                        new Separator(),
                        modRow,
                        new Separator(),
                        cameraModContent,
                        new Separator(),
                        cameraStatusLabel,
                        fullControlBtn);

        // Populate initial content and wire dropdown
        currentCameraModality = modalityCombo.getValue();
        rebuildCameraModContent(currentCameraModality);
        modalityCombo.setOnAction(e -> {
            cameraStatusLabel.setText("");
            currentCameraModality = modalityCombo.getValue();
            rebuildCameraModContent(currentCameraModality);
        });

        tab.setContent(cameraContent);
        return tab;
    }

    /** Detect current objective/detector by matching MicroManager pixel size against config. */
    private void detectCurrentHardware() {
        currentCameraObjectiveId = "Unknown";
        currentCameraDetectorId = "Unknown";
        try {
            var modalities = mgr.getAvailableModalities();
            String mod = (modalities != null && !modalities.isEmpty())
                    ? modalities.iterator().next()
                    : "ppm";
            var objectives = mgr.getAvailableObjectives();
            var detectors = mgr.getHardwareDetectors();

            boolean detected = false;
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc != null && mc.isConnected() && objectives != null && detectors != null) {
                try {
                    double mmPx = mc.getSocketClient().getMicroscopePixelSize();
                    if (mmPx > 0) {
                        for (String obj : objectives) {
                            for (String det : detectors) {
                                Double cfgPx = mgr.getHardwarePixelSize(obj, det);
                                if (cfgPx != null && Math.abs(cfgPx - mmPx) < 0.001) {
                                    currentCameraObjectiveId = obj;
                                    currentCameraDetectorId = det;
                                    detected = true;
                                    logger.info("Camera tab: detected {}/{} from MM pixel size {}", obj, det, mmPx);
                                    break;
                                }
                            }
                            if (detected) break;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Camera tab: pixel size detection failed: {}", e.getMessage());
                }
            }
            if (!detected) {
                String lastObj = PersistentPreferences.getLastObjective();
                if (lastObj != null && !lastObj.isEmpty() && objectives != null && objectives.contains(lastObj))
                    currentCameraObjectiveId = lastObj;
                if (objectives != null && !objectives.isEmpty() && "Unknown".equals(currentCameraObjectiveId))
                    currentCameraObjectiveId = objectives.iterator().next();
                if (detectors != null && !detectors.isEmpty())
                    currentCameraDetectorId = detectors.iterator().next();
            }
        } catch (Exception e) {
            logger.debug("Could not read hardware IDs: {}", e.getMessage());
        }
    }

    /** Rebuild the modality-specific camera content area. */
    /**
     * Refreshes camera preset buttons from the current config/YAML values.
     * Call after white balance calibration updates the imageprocessing YAML.
     */
    public void refreshCameraPresets() {
        if (currentCameraModality != null) {
            logger.info("Refreshing camera presets after calibration");
            rebuildCameraModContent(currentCameraModality);
        }
    }

    private void rebuildCameraModContent(String modality) {
        cameraModContent.getChildren().clear();
        if (modality == null || modality.isEmpty()) return;

        String norm = modality.toLowerCase();
        if (norm.startsWith("ppm")) {
            buildPpmCameraContent(modality);
        } else if (norm.startsWith("brightfield") || norm.startsWith("bf")) {
            buildBrightfieldCameraContent(modality);
        } else if (norm.startsWith("fl") || norm.startsWith("fluorescence")
                || norm.startsWith("widefield") || norm.startsWith("epi")) {
            buildFluorescenceCameraContent(modality);
        } else {
            buildGenericCameraContent(modality);
        }
    }

    /** PPM modality: per-angle WB presets with rotation. */
    @SuppressWarnings("unchecked")
    private void buildPpmCameraContent(String modality) {
        Label header = new Label("Apply WB Preset (PPM)");
        header.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        cameraModContent.getChildren().add(header);

        boolean anyPresets = false;
        try {
            String det = resolveDetector(modality);
            java.util.Map<String, Double> ppmAngles = loadPpmAnglesLocal(mgr);
            var exposures = mgr.getModalityExposures(modality, currentCameraObjectiveId, det);
            var gainsObj = mgr.getModalityGains(modality, currentCameraObjectiveId, det);
            java.util.Map<String, Object> gains =
                    (gainsObj instanceof java.util.Map<?, ?>) ? (java.util.Map<String, Object>) gainsObj : null;

            if (exposures != null && !exposures.isEmpty()) {
                for (var entry : ppmAngles.entrySet()) {
                    String angleName = entry.getKey();
                    double angleDeg = entry.getValue();
                    Object angleExp = exposures.get(angleName);
                    if (angleExp == null) continue;
                    float[] expArray = parseExposures(angleExp);
                    if (expArray == null) continue;
                    float[] gainArray = parseGains(gains, angleName);

                    String label = capitalize(angleName) + " (" + (int) angleDeg + " deg)";
                    String detail = formatExpDetail(expArray) + formatGainDetail(gainArray);

                    Button btn = createPresetButton(label, detail);
                    final float[] fExp = expArray;
                    final float[] fGain = gainArray;
                    btn.setOnAction(e -> applyWbPreset(angleName, angleDeg, fExp, fGain));

                    cameraModContent.getChildren().addAll(btn, createDetailLabel(detail));
                    anyPresets = true;
                }
            }

            // Simple WB preset: uncrossed uses per-channel exposures (R, G, B)
            // for accurate white balance. Other angles use the per-angle presets
            // above (from PPM WB) or the acquisition workflow switches to unified
            // exposure mode with the analog gains from uncrossed.
            Object simpleWb = mgr.getProfileSetting(modality, currentCameraObjectiveId, det, "simple_wb");
            if (simpleWb instanceof java.util.Map<?, ?> simpleMap) {
                Object baseExp = simpleMap.get("base_exposures_ms");
                Object baseGains = simpleMap.get("base_gains");
                if (baseExp instanceof java.util.Map<?, ?> baseExpMap) {
                    // Per-channel exposures for uncrossed white balance
                    float[] sExp = {
                        toFloat(baseExpMap.get("r")), toFloat(baseExpMap.get("g")), toFloat(baseExpMap.get("b"))
                    };
                    float[] sGain = parseGainsFromMap(
                            baseGains instanceof java.util.Map<?, ?>
                                    ? (java.util.Map<String, Object>) baseGains
                                    : null);
                    double uncrossedDeg = ppmAngles.getOrDefault("uncrossed", 90.0);

                    Button btn = createPresetButton("Uncrossed (Simple WB)", formatExpDetail(sExp));
                    btn.setStyle(btn.getStyle() + " -fx-text-fill: -fx-accent;");
                    btn.setOnAction(e -> applyWbPreset("uncrossed", uncrossedDeg, sExp, sGain));
                    cameraModContent.getChildren().addAll(btn, createDetailLabel(formatExpDetail(sExp)));
                    anyPresets = true;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not load PPM presets: {}", e.getMessage());
        }

        // Illumination control (for brightfield component of PPM systems)
        Node illumControl = buildIlluminationControl();
        if (illumControl != null) {
            cameraModContent.getChildren().addAll(new Separator(), illumControl);
        }

        // Save/Load preset buttons
        cameraModContent.getChildren().addAll(new Separator(), buildPresetButtons(modality));

        if (!anyPresets) addNoPresetsLabel();
    }

    /** Brightfield modality: exposure + illumination + WB preset + presets. */
    @SuppressWarnings("unchecked")
    private void buildBrightfieldCameraContent(String modality) {
        // Profile selector (if acquisition profiles exist)
        Node profileSelector = buildProfileSelector(modality);
        if (profileSelector != null) {
            cameraModContent.getChildren().addAll(profileSelector, new Separator());
        }

        // Exposure control
        cameraModContent.getChildren().add(buildExposureControl());

        // Illumination control
        Node illumControl = buildIlluminationControl();
        if (illumControl != null) {
            cameraModContent.getChildren().addAll(new Separator(), illumControl);
        }

        // WB preset (if calibrated)
        boolean anyPresets = false;
        try {
            String det = resolveDetector(modality);
            var exposures = mgr.getModalityExposures(modality, currentCameraObjectiveId, det);
            var gainsObj = mgr.getModalityGains(modality, currentCameraObjectiveId, det);

            Object singleExp = (exposures != null) ? exposures.get("single") : null;
            if (singleExp != null) {
                float[] expArray = parseExposures(singleExp);
                if (expArray != null) {
                    float[] gainArray = {1.0f};
                    if (gainsObj instanceof java.util.Map<?, ?> gainsMap) {
                        gainArray = parseGainsFromMap((java.util.Map<String, Object>) gainsMap);
                    } else if (gainsObj instanceof Number n) {
                        gainArray = new float[] {n.floatValue()};
                    }
                    String detail = formatExpDetail(expArray) + formatGainDetail(gainArray);
                    Button btn = createPresetButton("Apply WB Preset", detail);
                    final float[] fExp = expArray;
                    final float[] fGain = gainArray;
                    btn.setOnAction(e -> applyPresetNoRotation("Brightfield WB", fExp, fGain));
                    cameraModContent.getChildren().addAll(new Separator(), btn, createDetailLabel(detail));
                    anyPresets = true;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not load brightfield presets: {}", e.getMessage());
        }

        // Save/Load preset buttons
        cameraModContent.getChildren().addAll(new Separator(), buildPresetButtons(modality));

        if (!anyPresets) addNoPresetsLabel();
    }

    /** Widefield fluorescence modality: exposure + illumination + profile selector + presets. */
    private void buildFluorescenceCameraContent(String modality) {
        // Profile selector (for channel switching: bf_20x -> fl_20x, etc.)
        Node profileSelector = buildProfileSelector(modality);
        if (profileSelector != null) {
            cameraModContent.getChildren().addAll(profileSelector, new Separator());
        }

        // Exposure control
        cameraModContent.getChildren().add(buildExposureControl());

        // Illumination control (epi-LED)
        Node illumControl = buildIlluminationControl();
        if (illumControl != null) {
            cameraModContent.getChildren().addAll(new Separator(), illumControl);
        }

        // Save/Load preset buttons
        cameraModContent.getChildren().addAll(new Separator(), buildPresetButtons(modality));
    }

    /** Generic fallback for unknown/future modalities -- basic exposure + illumination + presets. */
    private void buildGenericCameraContent(String modality) {
        // Profile selector (if any)
        Node profileSelector = buildProfileSelector(modality);
        if (profileSelector != null) {
            cameraModContent.getChildren().addAll(profileSelector, new Separator());
        }

        // Exposure control
        cameraModContent.getChildren().add(buildExposureControl());

        // Illumination control
        Node illumControl = buildIlluminationControl();
        if (illumControl != null) {
            cameraModContent.getChildren().addAll(new Separator(), illumControl);
        }

        // Save/Load preset buttons
        cameraModContent.getChildren().addAll(new Separator(), buildPresetButtons(modality));
    }

    /** Resolve the detector ID for a given modality. */
    private String resolveDetector(String modality) {
        var dets = mgr.getAvailableDetectors();
        return (dets != null && !dets.isEmpty()) ? dets.iterator().next() : currentCameraDetectorId;
    }

    // --- Shared Camera Tab builders ---

    /** Build an exposure control row (label + text field + Set button). */
    private Node buildExposureControl() {
        Label expLabel = new Label("Exposure (ms):");
        expLabel.setStyle("-fx-font-size: 10px;");
        TextField expField = new TextField();
        expField.setPrefWidth(80);
        expField.setPromptText("e.g., 33");
        try {
            var expResult = MicroscopeController.getInstance().getSocketClient().getExposures();
            expField.setText(String.format("%.1f", expResult.unified()));
        } catch (Exception ex) {
            expField.setText("");
        }
        Button applyBtn = new Button("Set");
        applyBtn.setStyle("-fx-font-size: 10px;");
        applyBtn.setOnAction(e -> {
            try {
                float exp = Float.parseFloat(expField.getText().trim());
                MicroscopeController.getInstance().getSocketClient().setExposures(new float[] {exp});
                cameraStatusLabel.setText("Exposure: " + exp + " ms");
                cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                logger.info("Set exposure to {} ms", exp);
            } catch (Exception ex) {
                cameraStatusLabel.setText("Failed: " + ex.getMessage());
                cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                logger.warn("Failed to set exposure: {}", ex.getMessage());
            }
        });
        // Also allow Enter key to apply
        expField.setOnAction(e -> applyBtn.fire());

        HBox row = new HBox(4, expLabel, expField, applyBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Build illumination control (slider + text field + on/off toggle).
     * Returns null if no illumination is available on the server.
     */
    private Node buildIlluminationControl() {
        try {
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc == null || !mc.isConnected()) return null;
            var illumResult = mc.getSocketClient().getIllumination();
            if (!illumResult.available()) return null;

            float min = illumResult.minPower();
            float max = illumResult.maxPower();
            float current = illumResult.power();

            // Try to get the illumination label from config
            String illumLabel = "Lamp";
            try {
                var modalities = mgr.getConfigItem("modalities");
                if (modalities instanceof java.util.Map<?, ?> modMap) {
                    // Find first modality with illumination.label
                    for (Object modCfg : modMap.values()) {
                        if (modCfg instanceof java.util.Map<?, ?> cfg) {
                            Object illum = cfg.get("illumination");
                            if (illum instanceof java.util.Map<?, ?> illumMap) {
                                Object label = illumMap.get("label");
                                if (label instanceof String s && !s.isEmpty()) {
                                    illumLabel = s;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Keep default "Lamp"
            }

            Label label = new Label(illumLabel + ":");
            label.setStyle("-fx-font-size: 10px;");

            Slider slider = new Slider(min, max, current);
            slider.setPrefWidth(120);
            slider.setShowTickLabels(false);
            slider.setShowTickMarks(false);

            TextField valueField = new TextField(String.format("%.0f", current));
            valueField.setPrefWidth(60);
            valueField.setStyle("-fx-font-size: 10px;");

            ToggleButton onOffBtn = new ToggleButton(illumResult.isOn() ? "ON" : "OFF");
            onOffBtn.setSelected(illumResult.isOn());
            onOffBtn.setStyle("-fx-font-size: 9px; -fx-padding: 2 6;");
            onOffBtn.selectedProperty().addListener((obs, oldVal, newVal) -> {
                onOffBtn.setText(newVal ? "ON" : "OFF");
                float power = newVal ? (float) slider.getValue() : 0f;
                if (!newVal || power <= 0) power = newVal ? (float) Math.max(1, max / 2) : 0f;
                sendIlluminationPower(power);
                if (newVal && power > 0) {
                    slider.setValue(power);
                    valueField.setText(String.format("%.0f", power));
                }
            });

            // Slider change -> send power (debounced by only sending on release)
            slider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
                if (!isChanging) {
                    float power = (float) slider.getValue();
                    valueField.setText(String.format("%.0f", power));
                    sendIlluminationPower(power);
                }
            });
            // Also update text field while dragging
            slider.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (slider.isValueChanging()) {
                    valueField.setText(String.format("%.0f", newVal.floatValue()));
                }
            });

            // Text field -> send power on Enter
            valueField.setOnAction(e -> {
                try {
                    float power = Float.parseFloat(valueField.getText().trim());
                    slider.setValue(power);
                    sendIlluminationPower(power);
                } catch (NumberFormatException ex) {
                    // ignore
                }
            });

            VBox illumBox = new VBox(2);
            HBox topRow = new HBox(4, label, slider, onOffBtn);
            topRow.setAlignment(Pos.CENTER_LEFT);
            HBox bottomRow = new HBox(4);
            Label valLabel = new Label("Intensity:");
            valLabel.setStyle("-fx-font-size: 9px;");
            Button setBtn = new Button("Set");
            setBtn.setStyle("-fx-font-size: 9px;");
            setBtn.setOnAction(e -> {
                try {
                    float power = Float.parseFloat(valueField.getText().trim());
                    slider.setValue(power);
                    sendIlluminationPower(power);
                } catch (NumberFormatException ex) {
                    // ignore
                }
            });
            bottomRow.getChildren().addAll(valLabel, valueField, setBtn);
            bottomRow.setAlignment(Pos.CENTER_LEFT);
            illumBox.getChildren().addAll(topRow, bottomRow);
            return illumBox;
        } catch (Exception e) {
            logger.debug("Could not build illumination control: {}", e.getMessage());
            return null;
        }
    }

    /** Send illumination power to the server in a background thread. */
    private void sendIlluminationPower(float power) {
        Thread t = new Thread(() -> {
            try {
                MicroscopeController mc = MicroscopeController.getInstance();
                if (mc != null && mc.isConnected()) {
                    mc.getSocketClient().setIllumination(power);
                    logger.debug("Set illumination power to {}", power);
                }
            } catch (Exception ex) {
                logger.warn("Failed to set illumination: {}", ex.getMessage());
            }
        }, "Illum-Set");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Build profile selector dropdown for acquisition profile switching.
     * Returns null if no acquisition profiles match the modality.
     */
    @SuppressWarnings("unchecked")
    private Node buildProfileSelector(String modality) {
        try {
            var profiles = mgr.getConfigItem("acquisition_profiles");
            if (!(profiles instanceof java.util.Map<?, ?> profileMap) || profileMap.isEmpty()) return null;

            // Filter profiles matching this modality
            java.util.List<String> matchingProfiles = new java.util.ArrayList<>();
            String modalityLower = modality.toLowerCase();
            for (var entry : profileMap.entrySet()) {
                String profileName = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof java.util.Map<?, ?> profileCfg) {
                    Object profModality = profileCfg.get("modality");
                    if (profModality != null) {
                        String profModStr = profModality.toString().toLowerCase();
                        // Match if profile modality starts with same prefix as current modality
                        if (profModStr.startsWith(modalityLower.substring(0, Math.min(2, modalityLower.length())))
                                || modalityLower.startsWith(profModStr.substring(0, Math.min(2, profModStr.length())))) {
                            matchingProfiles.add(profileName);
                        }
                    }
                }
            }

            // Also add ALL profiles so user can switch modalities from here
            for (var entry : profileMap.entrySet()) {
                String profileName = String.valueOf(entry.getKey());
                if (!matchingProfiles.contains(profileName)) {
                    matchingProfiles.add(profileName);
                }
            }

            if (matchingProfiles.isEmpty()) return null;

            Label label = new Label("Profile:");
            label.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
            ComboBox<String> profileCombo = new ComboBox<>();
            profileCombo.getItems().addAll(matchingProfiles);
            profileCombo.setValue(matchingProfiles.get(0));
            profileCombo.setMaxWidth(Double.MAX_VALUE);
            profileCombo.setStyle("-fx-font-size: 10px;");
            HBox.setHgrow(profileCombo, javafx.scene.layout.Priority.ALWAYS);

            Button applyBtn = new Button("Apply");
            applyBtn.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
            applyBtn.setOnAction(e -> {
                String selectedProfile = profileCombo.getValue();
                if (selectedProfile == null) return;
                cameraStatusLabel.setText("Applying profile: " + selectedProfile + "...");
                cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

                Thread t = new Thread(() -> {
                    try {
                        MicroscopeController mc = MicroscopeController.getInstance();
                        if (mc == null || !mc.isConnected()) throw new Exception("Not connected");
                        mc.withLiveModeHandling(() -> mc.getSocketClient().applyProfile(selectedProfile));
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Profile applied: " + selectedProfile);
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                        });
                    } catch (Exception ex) {
                        logger.error("Failed to apply profile '{}': {}", selectedProfile, ex.getMessage());
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Failed: " + ex.getMessage());
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        });
                    }
                }, "Profile-Apply");
                t.setDaemon(true);
                t.start();
            });

            HBox row = new HBox(4, label, profileCombo, applyBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        } catch (Exception e) {
            logger.debug("Could not build profile selector: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build Save/Load preset buttons.
     * Presets are stored in PersistentPreferences keyed by modality + objective + detector.
     */
    private Node buildPresetButtons(String modality) {
        Button saveBtn = new Button("Save Preset");
        saveBtn.setStyle("-fx-font-size: 10px;");
        saveBtn.setOnAction(e -> saveCurrentPreset(modality));

        Button loadBtn = new Button("Load Preset");
        loadBtn.setStyle("-fx-font-size: 10px;");
        loadBtn.setOnAction(e -> loadAndApplyPreset(modality));

        // Check if a preset exists
        String presetKey = getPresetKey(modality);
        String existing = PersistentPreferences.getStringPreference(presetKey, null);
        if (existing == null) {
            loadBtn.setDisable(true);
            loadBtn.setTooltip(new Tooltip("No saved preset for this configuration"));
        } else {
            loadBtn.setTooltip(new Tooltip("Load saved preset"));
        }

        HBox row = new HBox(8, saveBtn, loadBtn);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    private String getPresetKey(String modality) {
        return "camera.preset."
                + modality.toLowerCase().replaceAll("[^a-z0-9]", "")
                + "." + currentCameraObjectiveId
                + "." + currentCameraDetectorId;
    }

    /** Save current camera state (exposure + gain + illumination) as a preset. */
    private void saveCurrentPreset(String modality) {
        Thread t = new Thread(() -> {
            try {
                MicroscopeController mc = MicroscopeController.getInstance();
                if (mc == null || !mc.isConnected()) throw new Exception("Not connected");

                var expResult = mc.getSocketClient().getExposures();
                var gainResult = mc.getSocketClient().getGains();
                var illumResult = mc.getSocketClient().getIllumination();

                // Build simple JSON-like string: exp|gain|illum
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%.2f", expResult.unified()));
                if (expResult.isPerChannel()) {
                    sb.append(String.format(",%.2f,%.2f,%.2f", expResult.red(), expResult.green(), expResult.blue()));
                }
                sb.append("|");
                sb.append(String.format("%.2f,%.2f,%.2f", gainResult.unifiedGain(), gainResult.analogRed(), gainResult.analogBlue()));
                sb.append("|");
                if (illumResult.available()) {
                    sb.append(String.format("%.1f", illumResult.power()));
                } else {
                    sb.append("0");
                }

                String presetKey = getPresetKey(modality);
                PersistentPreferences.setStringPreference(presetKey, sb.toString());

                Platform.runLater(() -> {
                    cameraStatusLabel.setText("Preset saved");
                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                    // Re-enable load button
                    rebuildCameraModContent(modality);
                });
                logger.info("Saved camera preset: {} -> {}", presetKey, sb);
            } catch (Exception ex) {
                logger.error("Failed to save preset: {}", ex.getMessage());
                Platform.runLater(() -> {
                    cameraStatusLabel.setText("Save failed: " + ex.getMessage());
                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                });
            }
        }, "Preset-Save");
        t.setDaemon(true);
        t.start();
    }

    /** Load and apply a saved camera preset. */
    private void loadAndApplyPreset(String modality) {
        String presetKey = getPresetKey(modality);
        String presetStr = PersistentPreferences.getStringPreference(presetKey, null);
        if (presetStr == null || presetStr.isEmpty()) {
            cameraStatusLabel.setText("No saved preset");
            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");
            return;
        }

        cameraStatusLabel.setText("Applying preset...");
        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Thread t = new Thread(() -> {
            try {
                MicroscopeController mc = MicroscopeController.getInstance();
                if (mc == null || !mc.isConnected()) throw new Exception("Not connected");

                // Parse: exp[,r,g,b]|gain,aR,aB|illum
                String[] parts = presetStr.split("\\|");
                if (parts.length < 2) throw new Exception("Invalid preset format");

                // Parse exposures
                String[] expParts = parts[0].split(",");
                float[] exposures;
                boolean individual;
                if (expParts.length >= 4) {
                    exposures = new float[] {
                            Float.parseFloat(expParts[1]),
                            Float.parseFloat(expParts[2]),
                            Float.parseFloat(expParts[3])
                    };
                    individual = true;
                } else {
                    exposures = new float[] {Float.parseFloat(expParts[0])};
                    individual = false;
                }

                // Parse gains
                String[] gainParts = parts[1].split(",");
                float[] gains = new float[] {
                        Float.parseFloat(gainParts[0]),
                        gainParts.length >= 2 ? Float.parseFloat(gainParts[1]) : 1.0f,
                        gainParts.length >= 3 ? Float.parseFloat(gainParts[2]) : 1.0f
                };

                // Apply atomically via SETCAM
                mc.withLiveModeHandling(() ->
                        mc.getSocketClient().setCameraSettings(individual, exposures, gains));

                // Apply illumination if present
                if (parts.length >= 3) {
                    float illumPower = Float.parseFloat(parts[2]);
                    if (illumPower > 0) {
                        mc.getSocketClient().setIllumination(illumPower);
                    }
                }

                Platform.runLater(() -> {
                    cameraStatusLabel.setText("Preset applied");
                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                    rebuildCameraModContent(modality);
                });
                logger.info("Applied camera preset: {}", presetStr);
            } catch (Exception ex) {
                logger.error("Failed to apply preset: {}", ex.getMessage());
                Platform.runLater(() -> {
                    cameraStatusLabel.setText("Load failed: " + ex.getMessage());
                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                });
            }
        }, "Preset-Load");
        t.setDaemon(true);
        t.start();
    }

    // --- Shared UI helpers for camera presets ---

    private Button createPresetButton(String label, String tooltipDetail) {
        Button btn = new Button(label);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle("-fx-font-size: 10px;");
        btn.setTooltip(new Tooltip("Apply: " + tooltipDetail));
        return btn;
    }

    private Label createDetailLabel(String detail) {
        Label lbl = new Label("  " + detail);
        lbl.setStyle("-fx-font-size: 9px; -fx-text-fill: #666;");
        return lbl;
    }

    private void addNoPresetsLabel() {
        Label lbl = new Label("No WB presets available.\nRun White Balance calibration first.");
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");
        lbl.setWrapText(true);
        cameraModContent.getChildren().add(lbl);
    }

    private static String formatExpDetail(float[] exp) {
        if (exp.length == 3) return String.format("R=%.1f G=%.1f B=%.1f ms", exp[0], exp[1], exp[2]);
        return String.format("%.1f ms", exp[0]);
    }

    private static String formatGainDetail(float[] gain) {
        if (gain.length == 3) return String.format(", gain=%.1f aR=%.2f aB=%.2f", gain[0], gain[1], gain[2]);
        return String.format(", gain=%.1f", gain[0]);
    }

    /**
     * Apply a WB preset WITHOUT rotation (for brightfield and other non-PPM modalities).
     * Uses atomic SETCAM command for single round-trip.
     */
    private void applyPresetNoRotation(String name, float[] exposures, float[] gains) {
        cameraStatusLabel.setText("Applying " + name + "...");
        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Thread thread = new Thread(
                () -> {
                    try {
                        MicroscopeController controller = MicroscopeController.getInstance();
                        if (controller == null) {
                            Platform.runLater(() -> {
                                cameraStatusLabel.setText("Not connected");
                                cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                            });
                            return;
                        }
                        // Use atomic SETCAM instead of sequential mode/exp/gain calls
                        boolean individual = exposures.length == 3;
                        controller.withLiveModeHandling(() ->
                                controller.getSocketClient().setCameraSettings(individual, exposures, gains));
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Applied: " + name);
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                        });
                    } catch (Exception ex) {
                        logger.error("Failed to apply preset: {}", ex.getMessage());
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Failed: " + ex.getMessage());
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        });
                    }
                },
                "BF-Preset-Apply");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Apply a WB preset: set camera mode, exposures, gains, and rotate stage (PPM).
     */
    private void applyWbPreset(String angleName, double angleDeg, float[] exposures, float[] gains) {
        cameraStatusLabel.setText("Applying " + angleName + "...");
        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Thread thread = new Thread(
                () -> {
                    try {
                        MicroscopeController controller = MicroscopeController.getInstance();
                        if (controller == null) {
                            Platform.runLater(() -> {
                                cameraStatusLabel.setText("Not connected");
                                cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                            });
                            return;
                        }
                        controller.withLiveModeHandling(
                                () -> controller.applyCameraSettingsForAngle(angleName, exposures, gains, angleDeg));
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText(
                                    "Applied: " + capitalize(angleName) + " (" + (int) angleDeg + " deg)");
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                        });
                    } catch (Exception ex) {
                        logger.error("Failed to apply WB preset: {}", ex.getMessage());
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Failed: " + ex.getMessage());
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        });
                    }
                },
                "WB-Preset-Apply");
        thread.setDaemon(true);
        thread.start();
    }

    /** Load PPM angles from config, falling back to defaults. */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Double> loadPpmAnglesLocal(MicroscopeConfigManager mgr) {
        try {
            var rotationAngles = mgr.getRotationAngles("ppm");
            if (rotationAngles != null && !rotationAngles.isEmpty()) {
                java.util.Map<String, Double> angles = new java.util.LinkedHashMap<>();
                for (java.util.Map<String, Object> angle : rotationAngles) {
                    String name = (String) angle.get("name");
                    Number tick = (Number) angle.get("tick");
                    if (name != null && tick != null) {
                        angles.put(name, tick.doubleValue());
                    }
                }
                if (!angles.isEmpty()) return angles;
            }
        } catch (Exception e) {
            logger.debug("Failed to load angles: {}", e.getMessage());
        }
        java.util.Map<String, Double> defaults = new java.util.LinkedHashMap<>();
        defaults.put("uncrossed", 90.0);
        defaults.put("crossed", 0.0);
        defaults.put("positive", 7.0);
        defaults.put("negative", -7.0);
        return defaults;
    }

    /** Parse per-channel exposures from YAML map like {r: X, g: Y, b: Z} or {all: X, r: Y, ...}. */
    @SuppressWarnings("unchecked")
    private static float[] parseExposures(Object angleExp) {
        if (angleExp instanceof java.util.Map<?, ?> map) {
            float r = toFloat(map.get("r"));
            float g = toFloat(map.get("g"));
            float b = toFloat(map.get("b"));
            if (r > 0 && g > 0 && b > 0) return new float[] {r, g, b};
        } else if (angleExp instanceof Number num) {
            // Single exposure value (unified)
            float v = num.floatValue();
            return new float[] {v};
        }
        return null;
    }

    /** Parse gains for a specific angle from the gains map. */
    @SuppressWarnings("unchecked")
    private static float[] parseGains(java.util.Map<String, Object> gains, String angleName) {
        if (gains == null) return new float[] {1.0f};
        Object angleGains = gains.get(angleName);
        if (angleGains instanceof java.util.Map<?, ?> map) {
            return parseGainsFromMap((java.util.Map<String, Object>) map);
        }
        return new float[] {1.0f};
    }

    /** Parse gains from a map with unified_gain, analog_red, analog_blue keys. */
    private static float[] parseGainsFromMap(java.util.Map<String, Object> map) {
        if (map == null) return new float[] {1.0f};
        // New format: unified_gain + analog_red + analog_blue
        float unified = toFloat(map.getOrDefault("unified_gain", 1.0));
        Object aRed = map.get("analog_red");
        Object aBlue = map.get("analog_blue");
        if (aRed != null && aBlue != null) {
            return new float[] {unified, toFloat(aRed), toFloat(aBlue)};
        }
        // Old format: r/g/b gain values (no unified_gain key)
        Object oldR = map.get("r");
        Object oldB = map.get("b");
        if (oldR != null && oldB != null) {
            // Old format used per-channel gains directly; map to unified=1 + analog R/B
            return new float[] {1.0f, toFloat(oldR), toFloat(oldB)};
        }
        return new float[] {unified};
    }

    /** Shorten a LOCI ID for display: "LOCI_DETECTOR_JAI_001" -> "JAI_001". */
    private static String shortenId(String id) {
        if (id == null) return "?";
        // Remove common LOCI prefixes
        String s = id.replace("LOCI_DETECTOR_", "").replace("LOCI_OBJECTIVE_", "");
        return s.length() > 20 ? s.substring(0, 20) + "..." : s;
    }

    private static float toFloat(Object o) {
        if (o instanceof Number n) return n.floatValue();
        if (o instanceof String s) {
            try {
                return Float.parseFloat(s);
            } catch (NumberFormatException e) {
                return 0f;
            }
        }
        return 0f;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
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

        // Save Z step size when changed
        zStepField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                PersistentPreferences.setStageControlZStepSize(newVal);
            }
        });

        // Sample movement checkbox listener
        sampleMovementCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            sampleMovementMode.set(newVal);
            PersistentPreferences.setStageControlSampleMovement(newVal);
            logger.debug("Sample movement mode changed: {} -> {}", oldVal, newVal);
        });

        // Arrow button handlers (single-step)
        upBtn.setOnAction(e -> handleArrowMove(0, 1));
        downBtn.setOnAction(e -> handleArrowMove(0, -1));
        leftBtn.setOnAction(e -> handleArrowMove(-1, 0));
        rightBtn.setOnAction(e -> handleArrowMove(1, 0));

        // Double-step arrow button handlers (move 2x step size)
        upBtn2x.setOnAction(e -> handleArrowMove(0, 2));
        downBtn2x.setOnAction(e -> handleArrowMove(0, -2));
        leftBtn2x.setOnAction(e -> handleArrowMove(-2, 0));
        rightBtn2x.setOnAction(e -> handleArrowMove(2, 0));

        // Joystick callbacks
        joystick.setStartCallback(() -> {
            try {
                double x = Double.parseDouble(xField.getText().replace(",", ""));
                double y = Double.parseDouble(yField.getText().replace(",", ""));
                joystickPosition.set(new double[] {x, y});
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

        // Initialize centroid button state and re-evaluate when image changes
        initializeCentroidButton();
        QuPathGUI guiRef = QuPathGUI.getInstance();
        if (guiRef != null && guiRef.getViewer() != null) {
            guiRef.getViewer()
                    .imageDataProperty()
                    .addListener((obs, oldData, newData) -> Platform.runLater(this::initializeCentroidButton));
        }
    }

    private void initializeFromHardware() {
        // Run socket operations on background thread to avoid blocking FX thread
        Thread initThread = new Thread(
                () -> {
                    try {
                        double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                        Platform.runLater(() -> {
                            xField.setText(String.format("%.2f", xy[0]));
                            yField.setText(String.format("%.2f", xy[1]));
                            joystickPosition.set(new double[] {xy[0], xy[1]});
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

                    if (MicroscopeController.getInstance().hasRotationStage()) {
                        try {
                            double r = MicroscopeController.getInstance().getStagePositionR();
                            Platform.runLater(() -> rField.setText(String.format("%.2f", r)));
                            logger.debug("Initialized R field with current position: {}", r);
                        } catch (Exception e) {
                            logger.debug("Failed to retrieve current R stage position: {}", e.getMessage());
                        }
                    }
                },
                "StageControl-Init");
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
                joystickPosition.set(new double[] {newVal, current[1]});
            }
            case StagePositionManager.PROP_POS_Y -> {
                yField.setText(String.format("%.2f", newVal));
                // Update joystick tracking
                double[] current = joystickPosition.get();
                joystickPosition.set(new double[] {current[0], newVal});
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

    /**
     * Checks if stage movement is blocked by an active acquisition.
     * Updates the XY status label if blocked.
     *
     * @return true if movement is blocked
     */
    private boolean isAcquisitionBlocked() {
        if (MicroscopeController.getInstance().isAcquisitionActive()) {
            xyStatus.setText("Locked during acquisition");
            return true;
        }
        return false;
    }

    private void handleMoveXY() {
        if (isAcquisitionBlocked()) return;
        try {
            double x = Double.parseDouble(xField.getText().replace(",", ""));
            double y = Double.parseDouble(yField.getText().replace(",", ""));

            if (!mgr.isWithinStageBounds(x, y)) {
                logger.warn("XY movement rejected - coordinates out of bounds: X={}, Y={}", x, y);
                UIFunctions.notifyUserOfError(
                        res.getString("stageMovement.error.outOfBoundsXY"), res.getString("stageMovement.title"));
                return;
            }

            logger.info("Executing XY stage movement to position: X={}, Y={}", x, y);
            xyStatus.setText("Moving...");
            joystickPosition.set(new double[] {x, y});

            // Run socket operation on background thread to avoid blocking FX thread
            Thread moveThread = new Thread(
                    () -> {
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
                    },
                    "StageControl-MoveXY");
            moveThread.setDaemon(true);
            moveThread.start();
        } catch (NumberFormatException ex) {
            logger.warn("Invalid XY coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
        }
    }

    private void handleMoveZ() {
        if (isAcquisitionBlocked()) return;
        try {
            double z = Double.parseDouble(zField.getText().replace(",", ""));

            if (!mgr.isWithinStageBounds(z)) {
                logger.warn("Z movement rejected - coordinate out of bounds: {}", z);
                UIFunctions.notifyUserOfError(
                        res.getString("stageMovement.error.outOfBoundsZ"), res.getString("stageMovement.title"));
                return;
            }

            logger.info("Executing Z stage movement to position: {}", z);
            zStatus.setText("Moving...");

            // Run socket operation on background thread to avoid blocking FX thread
            Thread moveThread = new Thread(
                    () -> {
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
                    },
                    "StageControl-MoveZ");
            moveThread.setDaemon(true);
            moveThread.start();
        } catch (NumberFormatException ex) {
            logger.warn("Invalid Z coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
        }
    }

    private void handleMoveR() {
        if (isAcquisitionBlocked()) return;
        try {
            double r = Double.parseDouble(rField.getText().replace(",", ""));

            logger.info("Executing R stage movement to position: {}", r);
            rStatus.setText("Moving...");

            // Run socket operation on background thread to avoid blocking FX thread
            Thread moveThread = new Thread(
                    () -> {
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
                    },
                    "StageControl-MoveR");
            moveThread.setDaemon(true);
            moveThread.start();
        } catch (NumberFormatException ex) {
            logger.warn("Invalid R coordinate format");
            UIFunctions.notifyUserOfError("Invalid coordinate format", res.getString("stageMovement.title"));
        }
    }

    private void handleZScroll(ScrollEvent event, TextField zStepField) {
        if (isAcquisitionBlocked()) {
            event.consume();
            return;
        }
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
            Thread moveThread = new Thread(
                    () -> {
                        try {
                            MicroscopeController.getInstance().moveStageZ(newZ);
                            Platform.runLater(() -> zStatus.setText(String.format("Scrolled Z to %.2f", newZ)));
                        } catch (Exception ex) {
                            logger.warn("Z scroll movement failed: {}", ex.getMessage());
                            Platform.runLater(() -> zStatus.setText("Z scroll failed"));
                        }
                    },
                    "StageControl-ZScroll");
            moveThread.setDaemon(true);
            moveThread.start();
        } catch (Exception ex) {
            logger.warn("Scroll Z movement failed: {}", ex.getMessage());
            zStatus.setText("Z scroll failed");
        }
        event.consume();
    }

    private void handleArrowMove(int xDir, int yDir) {
        if (isAcquisitionBlocked()) return;
        try {
            double step = Double.parseDouble(xyStepField.getText().replace(",", ""));
            double currentX = Double.parseDouble(xField.getText().replace(",", ""));
            double currentY = Double.parseDouble(yField.getText().replace(",", ""));

            // Movement direction is determined by the stage inversion preferences.
            // stageInvertedX/Y indicate whether positive stage commands move opposite
            // to the expected direction. The "Sample Movement" checkbox adds an extra
            // inversion on X so the sample appears to move with the control direction.
            boolean sampleMode = sampleMovementCheckbox.isSelected();
            boolean invertX = qupath.ext.qpsc.preferences.QPPreferenceDialog.getStageInvertedXProperty();
            boolean invertY = qupath.ext.qpsc.preferences.QPPreferenceDialog.getStageInvertedYProperty();
            double xMult = (invertX ? -1 : 1) * (sampleMode ? -1 : 1);
            double yMult = invertY ? -1 : 1;

            double newX = currentX + (step * xDir * xMult);
            double newY = currentY + (step * yDir * yMult);

            if (!mgr.isWithinStageBounds(newX, newY)) {
                xyStatus.setText("Move out of bounds");
                return;
            }

            // Update UI immediately with expected position
            xField.setText(String.format("%.2f", newX));
            yField.setText(String.format("%.2f", newY));
            joystickPosition.set(new double[] {newX, newY});
            xyStatus.setText("Moving...");

            // Run socket operation on background thread to avoid blocking FX thread
            // (which could cause "Not Responding" if socket is held by another operation)
            Thread moveThread = new Thread(
                    () -> {
                        try {
                            long t0 = System.nanoTime();
                            MicroscopeController.getInstance().moveStageXY(newX, newY);
                            long ms = (System.nanoTime() - t0) / 1_000_000;
                            logger.debug("Arrow move to ({}, {}) took {}ms", newX, newY, ms);
                            Platform.runLater(
                                    () -> xyStatus.setText(String.format("Moved to (%.0f, %.0f)", newX, newY)));
                        } catch (Exception ex) {
                            logger.warn("Arrow movement failed: {}", ex.getMessage());
                            Platform.runLater(() -> xyStatus.setText("Move failed"));
                        }
                    },
                    "StageControl-ArrowMove");
            moveThread.setDaemon(true);
            moveThread.start();
        } catch (Exception ex) {
            logger.warn("Arrow movement failed: {}", ex.getMessage());
            xyStatus.setText("Move failed");
        }
    }

    private void handleJoystickMove(double deltaX, double deltaY) {
        if (isAcquisitionBlocked()) return;
        try {
            double[] current = joystickPosition.get();
            double currentX = current[0];
            double currentY = current[1];

            // Movement direction from stage inversion preferences + sample mode toggle.
            boolean sampleMode = sampleMovementMode.get();
            boolean invertX = qupath.ext.qpsc.preferences.QPPreferenceDialog.getStageInvertedXProperty();
            boolean invertY = qupath.ext.qpsc.preferences.QPPreferenceDialog.getStageInvertedYProperty();
            double xMult = (invertX ? -1 : 1) * (sampleMode ? -1 : 1);
            double yMult = invertY ? -1 : 1;
            double targetX = currentX + (deltaX * xMult);
            double targetY = currentY + (deltaY * yMult);

            if (!mgr.isWithinStageBounds(targetX, targetY)) {
                Platform.runLater(() -> xyStatus.setText(res.getString("stageMovement.joystick.boundary")));
                return;
            }

            joystickPosition.set(new double[] {targetX, targetY});

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
        // Reset state so this method is safe to re-call on image change
        goToCentroidBtn.setDisable(false);
        centroidStatus.setText("");
        availableLabel.setVisible(false);
        availableLabel.setManaged(false);
        alignmentListView.setVisible(false);
        alignmentListView.setManaged(false);
        alignmentListView.getItems().clear();

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
            // Check if this is a sub-image with XY offset metadata
            boolean hasXYOffset = false;
            if (gui != null && gui.getProject() != null && gui.getImageData() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                    ProjectImageEntry<BufferedImage> entry =
                            QPProjectFunctions.findImageInProject(project, gui.getImageData());
                    if (entry != null) {
                        double[] offset = ImageMetadataManager.getXYOffset(entry);
                        hasXYOffset = offset[0] != 0 || offset[1] != 0;
                    }
                } catch (Exception e) {
                    logger.debug("Could not check XY offset: {}", e.getMessage());
                }
            }

            if (hasXYOffset) {
                centroidStatus.setText("Sub-image: offset-based navigation");
            } else {
                goToCentroidBtn.setDisable(true);

                String currentImageName = gui != null && gui.getImageData() != null
                        ? QPProjectFunctions.getActualImageFileName(gui.getImageData())
                        : "unknown";

                @SuppressWarnings("unchecked")
                Project<BufferedImage> projectForList =
                        gui != null && gui.getProject() != null ? (Project<BufferedImage>) gui.getProject() : null;
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
            }
        } else {
            centroidStatus.setText("Alignment available");
        }
    }

    private void handleGoToCentroid() {
        if (isAcquisitionBlocked()) return;
        QuPathGUI gui = QuPathGUI.getInstance();

        if (gui == null || gui.getImageData() == null) {
            centroidStatus.setText("No image open");
            return;
        }

        PathObject selectedObject =
                gui.getImageData().getHierarchy().getSelectionModel().getSelectedObject();

        if (selectedObject == null) {
            centroidStatus.setText("No object selected");
            UIFunctions.notifyUserOfError("Please select an object in QuPath first.", "Go to Centroid");
            return;
        }

        if (selectedObject.getROI() == null) {
            centroidStatus.setText("Selected object has no ROI");
            return;
        }

        double centroidX = selectedObject.getROI().getCentroidX();
        double centroidY = selectedObject.getROI().getCentroidY();

        // Try sub-image XY offset path first
        if (gui.getProject() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                // Use robust entry lookup (project.getEntry() alone can return null)
                ProjectImageEntry<BufferedImage> entry =
                        QPProjectFunctions.findImageInProject(project, gui.getImageData());
                if (entry != null) {
                    double[] offset = ImageMetadataManager.getXYOffset(entry);
                    if (offset[0] != 0 || offset[1] != 0) {
                        double pixelSize = gui.getImageData()
                                .getServer()
                                .getPixelCalibration()
                                .getAveragedPixelSizeMicrons();

                        // Derive the pixel-to-stage direction from the parent alignment
                        // transform's scale signs. The alignment bakes in the combined
                        // effect of optical flip and stage inversion; reading preferences
                        // directly is unreliable (persisted stageInverted may differ from
                        // auto-detected values).
                        double signX = 1.0;
                        double signY = 1.0;
                        String baseName = entry.getMetadata().get(ImageMetadataManager.BASE_IMAGE);
                        if (baseName != null && !baseName.isEmpty()) {
                            AffineTransform parentTransform =
                                    AffineTransformManager.loadSlideAlignment(project, baseName);
                            if (parentTransform != null) {
                                signX = parentTransform.getScaleX() < 0 ? -1.0 : 1.0;
                                signY = parentTransform.getScaleY() < 0 ? -1.0 : 1.0;
                            } else {
                                logger.warn(
                                        "Could not load parent alignment for '{}' " + "- defaulting sign to +1",
                                        baseName);
                            }
                        } else {
                            logger.warn(
                                    "No base_image metadata on entry '{}' " + "- defaulting sign to +1",
                                    entry.getImageName());
                        }

                        int imgW = gui.getImageData().getServer().getWidth();
                        int imgH = gui.getImageData().getServer().getHeight();

                        // The XY offset stores transform(annotation corner), but the
                        // stitched image extends half a frame beyond the annotation in
                        // each direction (TilingUtilities: startX = minX - frameWidth/2).
                        // After stitcher normalization, the image center (not the corner)
                        // aligns with the annotation corner. So centroid coordinates must
                        // be measured relative to the image center, not pixel (0,0).
                        double halfW = imgW / 2.0;
                        double halfH = imgH / 2.0;
                        double targetX = offset[0] + (centroidX - halfW) * pixelSize * signX;
                        double targetY = offset[1] + (centroidY - halfH) * pixelSize * signY;
                        logger.info(
                                "Sub-image centroid: pixel ({}, {}) - center ({}, {}) "
                                        + "* {}um * sign({}, {}) [base={}] "
                                        + "+ offset ({}, {}) -> stage ({}, {})",
                                String.format("%.1f", centroidX),
                                String.format("%.1f", centroidY),
                                String.format("%.1f", halfW),
                                String.format("%.1f", halfH),
                                String.format("%.4f", pixelSize),
                                String.format("%.0f", signX),
                                String.format("%.0f", signY),
                                baseName,
                                String.format("%.1f", offset[0]),
                                String.format("%.1f", offset[1]),
                                String.format("%.1f", targetX),
                                String.format("%.1f", targetY));
                        moveToStagePosition(targetX, targetY);
                        return;
                    } else {
                        logger.info("Sub-image entry found but XY offset is (0,0): {}", entry.getImageName());
                    }
                } else {
                    logger.warn("Could not find project entry for current image - "
                            + "sub-image offset navigation unavailable");
                }
            } catch (Exception e) {
                logger.warn("Could not use XY offset path: {}", e.getMessage());
            }
        }

        // Fall through to alignment transform path
        AffineTransform transform = MicroscopeController.getInstance().getCurrentTransform();

        // Try to load slide-specific alignment
        if (transform == null && gui.getProject() != null) {
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
                    "No alignment is available. Please perform alignment first.", "Go to Centroid");
            return;
        }

        try {
            double adjustedX = centroidX;
            double adjustedY = centroidY;

            // The alignment was calibrated on the flipped image. If we're viewing the
            // original (unflipped) image, convert the centroid to the flipped coordinate
            // space before applying the transform.
            if (gui.getProject() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> proj = (Project<BufferedImage>) gui.getProject();
                    ProjectImageEntry<BufferedImage> currentEntry = proj.getEntry(gui.getImageData());
                    if (currentEntry != null) {
                        boolean imageFlipX = ImageMetadataManager.isFlippedX(currentEntry);
                        boolean imageFlipY = ImageMetadataManager.isFlippedY(currentEntry);
                        boolean prefFlipX = QPPreferenceDialog.getFlipMacroXProperty();
                        boolean prefFlipY = QPPreferenceDialog.getFlipMacroYProperty();

                        int imgWidth = gui.getImageData().getServer().getWidth();
                        int imgHeight = gui.getImageData().getServer().getHeight();

                        // If the preference says flip but the image isn't flipped,
                        // we're on the original -- flip the centroid to match the
                        // coordinate space the alignment was calibrated in.
                        if (prefFlipX && !imageFlipX) {
                            adjustedX = imgWidth - centroidX;
                            logger.debug(
                                    "Flipping centroid X: {} -> {} (image width={})", centroidX, adjustedX, imgWidth);
                        }
                        if (prefFlipY && !imageFlipY) {
                            adjustedY = imgHeight - centroidY;
                            logger.debug(
                                    "Flipping centroid Y: {} -> {} (image height={})", centroidY, adjustedY, imgHeight);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not check image flip state: {}", e.getMessage());
                }
            }

            double[] qpCoords = {adjustedX, adjustedY};
            double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(qpCoords, transform);
            moveToStagePosition(stageCoords[0], stageCoords[1]);
        } catch (Exception ex) {
            logger.error("Failed to move to object centroid: {}", ex.getMessage(), ex);
            centroidStatus.setText("Error: " + ex.getMessage());
        }
    }

    private void moveToStagePosition(double targetX, double targetY) {
        if (!mgr.isWithinStageBounds(targetX, targetY)) {
            UIFunctions.notifyUserOfError(
                    "The object centroid position is outside the stage bounds.", "Go to Centroid");
            centroidStatus.setText("Position out of bounds");
            return;
        }

        xField.setText(String.format("%.2f", targetX));
        yField.setText(String.format("%.2f", targetY));
        joystickPosition.set(new double[] {targetX, targetY});
        centroidStatus.setText("Moving...");
        xyStatus.setText("Moving to centroid...");

        Thread moveThread = new Thread(
                () -> {
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
                },
                "StageControl-GoToCentroid");
        moveThread.setDaemon(true);
        moveThread.start();
    }

    // ============ SAVED POINTS TAB HANDLERS ============

    private void handleAddSavedPoint() {
        TextInputDialog dialog =
                new TextInputDialog("Point " + (savedPointsListView.getItems().size() + 1));
        dialog.setTitle("Add Saved Point");
        dialog.setHeaderText("Save current position");
        dialog.setContentText("Point name:");

        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) {
                savedPointsStatus.setText("Name cannot be empty");
                return;
            }

            try {
                double x = Double.parseDouble(xField.getText().replace(",", ""));
                double y = Double.parseDouble(yField.getText().replace(",", ""));
                double z = Double.parseDouble(zField.getText().replace(",", ""));

                SavedPoint point = new SavedPoint(name.trim(), x, y, z);
                savedPointsListView.getItems().add(point);
                saveSavedPointsToPrefs();
                savedPointsStatus.setText("Added: " + name.trim());
                logger.info("Saved stage point '{}' at X={}, Y={}, Z={}", name.trim(), x, y, z);
            } catch (NumberFormatException ex) {
                savedPointsStatus.setText("Error: Invalid position values");
                logger.warn("Failed to add saved point - invalid position values in fields");
            }
        });
    }

    private void handleGoToSavedPoint(boolean includeZ) {
        if (isAcquisitionBlocked()) return;
        SavedPoint selected = savedPointsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            savedPointsStatus.setText("No point selected");
            return;
        }

        double targetX = selected.getX();
        double targetY = selected.getY();
        Double targetZ = includeZ ? selected.getZ() : null;

        // Validate bounds
        if (!mgr.isWithinStageBounds(targetX, targetY)) {
            savedPointsStatus.setText("XY position out of bounds");
            return;
        }
        if (targetZ != null && !mgr.isWithinStageBounds(targetZ)) {
            savedPointsStatus.setText("Z position out of bounds");
            return;
        }

        savedPointsStatus.setText("Moving...");
        String moveType = includeZ ? "XYZ" : "XY";
        logger.info(
                "Moving to saved point '{}' ({}) at X={}, Y={}{}",
                selected.getName(),
                moveType,
                targetX,
                targetY,
                includeZ ? ", Z=" + targetZ : "");

        Thread moveThread = new Thread(
                () -> {
                    try {
                        MicroscopeController controller = MicroscopeController.getInstance();
                        controller.moveStageXY(targetX, targetY);
                        if (targetZ != null) {
                            controller.moveStageZ(targetZ);
                        }

                        Platform.runLater(() -> {
                            xField.setText(String.format("%.2f", targetX));
                            yField.setText(String.format("%.2f", targetY));
                            if (targetZ != null) {
                                zField.setText(String.format("%.2f", targetZ));
                            }
                            joystickPosition.set(new double[] {targetX, targetY});
                            savedPointsStatus.setText(String.format(
                                    "Moved to %s (%.0f, %.0f%s)",
                                    selected.getName(),
                                    targetX,
                                    targetY,
                                    targetZ != null ? String.format(", %.1f", targetZ) : ""));
                            xyStatus.setText(String.format("Moved to saved point (%.0f, %.0f)", targetX, targetY));
                        });
                    } catch (Exception ex) {
                        logger.error("Failed to move to saved point: {}", ex.getMessage());
                        Platform.runLater(() -> savedPointsStatus.setText("Move failed: " + ex.getMessage()));
                    }
                },
                "StageControl-GoToSavedPoint");
        moveThread.setDaemon(true);
        moveThread.start();
    }

    private void handleRemoveSavedPoint() {
        int selectedIndex = savedPointsListView.getSelectionModel().getSelectedIndex();
        if (selectedIndex >= 0) {
            SavedPoint removed = savedPointsListView.getItems().remove(selectedIndex);
            saveSavedPointsToPrefs();
            savedPointsStatus.setText("Removed: " + removed.getName());
            logger.info("Removed saved point '{}'", removed.getName());
        } else {
            savedPointsStatus.setText("No point selected");
        }
    }

    private void handleClearAllSavedPoints() {
        if (savedPointsListView.getItems().isEmpty()) {
            savedPointsStatus.setText("No points to clear");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear All Points");
        confirm.setHeaderText("Remove all saved points?");
        confirm.setContentText("This cannot be undone.");

        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                int count = savedPointsListView.getItems().size();
                savedPointsListView.getItems().clear();
                saveSavedPointsToPrefs();
                savedPointsStatus.setText("Cleared " + count + " point(s)");
                logger.info("Cleared all {} saved points", count);
            }
        });
    }

    private void loadSavedPointsFromPrefs() {
        String json = PersistentPreferences.getSavedStagePoints();
        if (json == null || json.equals("[]") || json.trim().isEmpty()) {
            return;
        }

        try {
            // Parse JSON array manually
            // Format: [{"name":"...", ...}, {"name":"...", ...}]
            json = json.trim();
            if (!json.startsWith("[") || !json.endsWith("]")) {
                return;
            }

            // Remove outer brackets
            String inner = json.substring(1, json.length() - 1).trim();
            if (inner.isEmpty()) {
                return;
            }

            // Split by objects - find each {...} block
            int depth = 0;
            int start = 0;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String objJson = inner.substring(start, i + 1);
                        SavedPoint point = SavedPoint.fromJson(objJson);
                        if (point != null) {
                            savedPointsListView.getItems().add(point);
                        }
                    }
                }
            }
            logger.debug(
                    "Loaded {} saved points from preferences",
                    savedPointsListView.getItems().size());
        } catch (Exception e) {
            logger.warn("Failed to load saved points from preferences: {}", e.getMessage());
        }
    }

    private void saveSavedPointsToPrefs() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < savedPointsListView.getItems().size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(savedPointsListView.getItems().get(i).toJson());
        }
        sb.append("]");
        PersistentPreferences.setSavedStagePoints(sb.toString());
        logger.debug(
                "Saved {} points to preferences", savedPointsListView.getItems().size());
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
        if (!isVisible() || getParent() == null) {
            return false;
        }
        if (isAcquisitionBlocked()) {
            return true; // consume the event so it doesn't propagate
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
