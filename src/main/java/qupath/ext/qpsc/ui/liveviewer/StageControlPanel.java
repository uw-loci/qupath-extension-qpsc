package qupath.ext.qpsc.ui.liveviewer;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputDialog;
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
    // Single-step arrows
    private final Button upBtn = new Button("\u2191");
    private final Button downBtn = new Button("\u2193");
    private final Button leftBtn = new Button("\u2190");
    private final Button rightBtn = new Button("\u2192");
    // Double-step arrows (move 2x distance)
    private final Button upBtn2x = new Button("\u21C8");      // Double up arrow
    private final Button downBtn2x = new Button("\u21CA");    // Double down arrow
    private final Button leftBtn2x = new Button("\u21C7");    // Double left arrow
    private final Button rightBtn2x = new Button("\u21C9");   // Double right arrow

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

        public String getName() { return name; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }

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
            return String.format("{\"name\":\"%s\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f}",
                    escapedName, x, y, z);
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
            while (end < json.length() && (Character.isDigit(json.charAt(end))
                    || json.charAt(end) == '.' || json.charAt(end) == '-')) {
                end++;
            }
            return Double.parseDouble(json.substring(start, end));
        }
    }

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
        sampleMovementCheckbox = new CheckBox("Sample Movement");
        sampleMovementCheckbox.setSelected(PersistentPreferences.getStageControlSampleMovement());
        sampleMovementCheckbox.setStyle("-fx-font-size: 10px;");
        Tooltip sampleMvmtTooltip = new Tooltip(
                "When unchecked, controls match MicroManager stage behavior.\n" +
                "When checked, inverts X axis so sample appears to move with controls.");
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
        // Single-step buttons: half dimensions (up/down half height, left/right half width)
        String singleUpDownStyle = "-fx-font-size: 10px; -fx-min-width: 28px; -fx-min-height: 14px; -fx-max-height: 14px; -fx-padding: 0;";
        String singleLeftRightStyle = "-fx-font-size: 10px; -fx-min-width: 14px; -fx-max-width: 14px; -fx-min-height: 28px; -fx-padding: 0;";
        upBtn.setStyle(singleUpDownStyle);
        downBtn.setStyle(singleUpDownStyle);
        leftBtn.setStyle(singleLeftRightStyle);
        rightBtn.setStyle(singleLeftRightStyle);

        // Double-step buttons: same half dimensions as single buttons
        String doubleUpDownStyle = "-fx-font-size: 10px; -fx-min-width: 28px; -fx-min-height: 14px; -fx-max-height: 14px; -fx-padding: 0;";
        String doubleLeftRightStyle = "-fx-font-size: 10px; -fx-min-width: 14px; -fx-max-width: 14px; -fx-min-height: 28px; -fx-padding: 0;";
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
        Tooltip joystickTooltip = new Tooltip("Drag to move stage continuously.\nSpeed scales with deflection from center.");
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

        // ============ TAB 3: SAVED POINTS ============
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

        savedPointsContent.getChildren().addAll(
                addPointBtn,
                savedPointsListView,
                goButtons,
                manageButtons,
                savedPointsStatus
        );
        savedPointsTab.setContent(savedPointsContent);

        // Wire up Saved Points event handlers
        addPointBtn.setOnAction(e -> handleAddSavedPoint());
        goToXYBtn.setOnAction(e -> handleGoToSavedPoint(false));
        goToXYZBtn.setOnAction(e -> handleGoToSavedPoint(true));
        removeBtn.setOnAction(e -> handleRemoveSavedPoint());
        clearAllBtn.setOnAction(e -> handleClearAllSavedPoints());

        // Load saved points from preferences
        loadSavedPointsFromPrefs();

        // Add tabs to TabPane and default to Navigate tab
        tabPane.getTabs().addAll(positionTab, navigateTab, savedPointsTab);
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

            // Default (unchecked): Match MicroManager stage controls exactly.
            //   - Right decreases X, Left increases X
            //   - Up decreases Y, Down increases Y
            // Sample Movement (checked): Invert X axis only so sample appears to move with controls.
            //   - Right increases X, Left decreases X
            //   - Up decreases Y, Down increases Y (unchanged)
            boolean sampleMode = sampleMovementCheckbox.isSelected();
            double xMult = sampleMode ? 1 : -1;  // Sample: right increases X; Default: right decreases X
            double yMult = -1;  // Always: up decreases Y (matches MicroManager)

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

            // Joystick uses screen coordinates: deltaY negative = up, deltaX positive = right.
            // Default (unchecked): Match MicroManager stage controls exactly.
            //   - Right (deltaX > 0) -> X decreases
            //   - Up (deltaY < 0) -> Y decreases
            // Sample Movement (checked): Invert X axis only.
            //   - Right (deltaX > 0) -> X increases
            //   - Up (deltaY < 0) -> Y decreases (unchanged)
            boolean sampleMode = sampleMovementMode.get();
            double xMult = sampleMode ? 1 : -1;  // Sample: right increases X; Default: right decreases X
            double yMult = 1;  // Always: up decreases Y (matches MicroManager)
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

    // ============ SAVED POINTS TAB HANDLERS ============

    private void handleAddSavedPoint() {
        TextInputDialog dialog = new TextInputDialog("Point " + (savedPointsListView.getItems().size() + 1));
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
        logger.info("Moving to saved point '{}' ({}) at X={}, Y={}{}", selected.getName(), moveType,
                targetX, targetY, includeZ ? ", Z=" + targetZ : "");

        Thread moveThread = new Thread(() -> {
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
                    joystickPosition.set(new double[]{targetX, targetY});
                    savedPointsStatus.setText(String.format("Moved to %s (%.0f, %.0f%s)",
                            selected.getName(), targetX, targetY,
                            targetZ != null ? String.format(", %.1f", targetZ) : ""));
                    xyStatus.setText(String.format("Moved to saved point (%.0f, %.0f)", targetX, targetY));
                });
            } catch (Exception ex) {
                logger.error("Failed to move to saved point: {}", ex.getMessage());
                Platform.runLater(() -> savedPointsStatus.setText("Move failed: " + ex.getMessage()));
            }
        }, "StageControl-GoToSavedPoint");
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
            logger.debug("Loaded {} saved points from preferences", savedPointsListView.getItems().size());
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
        logger.debug("Saved {} points to preferences", savedPointsListView.getItems().size());
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
