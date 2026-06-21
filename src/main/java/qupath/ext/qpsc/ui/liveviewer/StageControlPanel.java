package qupath.ext.qpsc.ui.liveviewer;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
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
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
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

    /** Callback invoked when hardware detection changes (objective/detector). */
    private Runnable onHardwareChanged;
    /** Callback invoked when the Camera-tab modality dropdown changes. */
    private Runnable onModalityChanged;
    /** Callback invoked when user toggles the FoV overlay button. */
    private Runnable onFovOverlayToggle;

    /** Sets a callback invoked whenever the detected objective/detector changes. */
    public void setOnHardwareChanged(Runnable callback) {
        this.onHardwareChanged = callback;
    }

    /** Sets a callback invoked whenever the Camera-tab modality dropdown changes. */
    public void setOnModalityChanged(Runnable callback) {
        this.onModalityChanged = callback;
    }

    /** Sets a callback invoked when the "Show Objective FoVs" button is toggled. */
    public void setOnFovOverlayToggle(Runnable callback) {
        this.onFovOverlayToggle = callback;
    }

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

    // Z bar widget + Live View gating
    private ZBarPanel zBarPanel;
    private Tab navigateTab;
    private Label movementDisabledBanner;
    private ReadOnlyBooleanProperty liveActiveProperty;
    private final SimpleBooleanProperty internalLiveActive = new SimpleBooleanProperty(true);
    // Drives the Go-to-Centroid disable state independently of the Live View
    // gate. initializeCentroidButton sets this based on image / alignment
    // availability; the button's disableProperty is bound to the OR of this
    // and the movement gate, so direct setDisable() is forbidden.
    private final SimpleBooleanProperty centroidUnavailable = new SimpleBooleanProperty(false);
    // True when the QuPath selection is not a legal target for Go-to-Centroid
    // (no selection, or selection has no ROI). Updated by a hierarchy
    // selection listener.
    private final SimpleBooleanProperty selectionNotLegal = new SimpleBooleanProperty(true);
    private qupath.lib.objects.hierarchy.PathObjectHierarchy attachedHierarchy;
    private qupath.lib.objects.hierarchy.events.PathObjectSelectionListener centroidSelectionListener;
    // Last XY position at which the focus trace was sampled. The trace is
    // cleared once the user moves more than FOCUS_TRACE_XY_RESET_UM from this
    // reference, so the trace stays valid for one site at a time.
    private double lastFocusXyX = Double.NaN;
    private double lastFocusXyY = Double.NaN;
    private static final double FOCUS_TRACE_XY_RESET_UM = 1.0;

    // Z scroll streaming state -- see handleZScroll / zScrollWorkerLoop.
    /** Standard JavaFX deltaY units per mouse wheel notch on Windows. */
    private static final double SCROLL_UNITS_PER_NOTCH = 40.0;
    /** Minimum interval between non-blocking move dispatches; floors the command rate. */
    private static final long MIN_DISPATCH_INTERVAL_MS = 50;
    /** Worker poll cadence: re-target dispatch and live Z readback. */
    private static final long POLL_INTERVAL_MS = 50;
    /** A scroll gesture ends once no scroll event has arrived for this long. */
    private static final long GESTURE_IDLE_MS = 250;

    /**
     * Live accumulated Z target for the current scroll gesture, in microns.
     * NaN when idle. Written on the FX thread by {@link #handleZScroll}, read
     * by the scroll worker, which re-targets the stage toward it via a
     * non-blocking move so the stage tracks the wheel continuously.
     */
    private volatile double zGestureTarget = Double.NaN;
    /** Wall-clock time of the most recent scroll event. */
    private volatile long lastScrollEventMs = 0;
    /** True while a scroll gesture is in progress. Suppresses poller overwrites of the Z field. */
    private volatile boolean zScrollInFlight = false;
    /** True iff the single Z scroll worker thread is currently alive. Guarded by {@link #zWorkerMutex}. */
    private boolean zWorkerRunning = false;
    /** Mutex for {@link #zWorkerRunning} and the worker's end-of-gesture check. */
    private final Object zWorkerMutex = new Object();

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

        // Input validation filter for signed-decimal stage positions
        // (X, Y, Z, R). Allows empty string and the partial states a
        // user types on the way to a complete number: '-', '.', '-.',
        // '-1', '-1.', '-1.2'. Rejects any character that isn't a
        // digit, minus, or dot, and rejects any invalid placement
        // (extra minus, extra dot) by feeding the candidate text
        // through a regex that only matches well-formed partials.
        // Fixes a bug where typing a letter into the Z field
        // corrupted the field and blocked subsequent stage moves
        // until the user manually cleared and re-entered a value.
        UnaryOperator<TextFormatter.Change> signedDecimalFilter = change -> {
            String newText = change.getControlNewText();
            if (newText.isEmpty()) {
                return change;
            }
            // Allow signed decimal in progress -- minus only at start,
            // at most one dot, digits anywhere else.
            if (newText.matches("-?\\d*\\.?\\d*")) {
                return change;
            }
            return null;
        };
        xField.setTextFormatter(new TextFormatter<>(signedDecimalFilter));
        yField.setTextFormatter(new TextFormatter<>(signedDecimalFilter));
        zField.setTextFormatter(new TextFormatter<>(signedDecimalFilter));
        rField.setTextFormatter(new TextFormatter<>(signedDecimalFilter));

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
        // Allow positive decimals (e.g. ".25", "0.5") because handleZScroll
        // parses this with Double.parseDouble - integer-only filtering would
        // block fine-focus step sizes that the rest of the code supports.
        // Match any *prefix* of a legal positive decimal so the user can
        // type a value one character at a time, including a leading dot.
        zStepField.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.isEmpty() || newText.matches("\\d*\\.?\\d*")) {
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

        // Z scroll handler (streams the wheel to the stage like a focus knob)
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
        navigateTab = new Tab("Navigate");
        // When Live View is off, the tab text turns orange so the user can see
        // the gate from any tab. The in-tab banner repeats the message in full.
        // (Previously this used an orange dot graphic, but that pushed the tab
        // label off-center; styling the text keeps it centered.)
        VBox navigateContent = new VBox(8);
        navigateContent.setPadding(new Insets(8));

        // Banner shown when Live View is off: movement is gated.
        movementDisabledBanner = new Label("Movement disabled - Live View is Off");
        movementDisabledBanner.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #b35900;"
                + " -fx-background-color: #fff3e0; -fx-padding: 4 8 4 8;"
                + " -fx-border-color: #ff9933; -fx-border-width: 1;");
        movementDisabledBanner.setMaxWidth(Double.MAX_VALUE);
        movementDisabledBanner.setVisible(false);
        movementDisabledBanner.setManaged(false);

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

        Button refreshFovBtn = new Button("Refresh FoV");
        refreshFovBtn.setStyle("-fx-font-size: 10px;");
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
        // fillWidth defaults to true on VBox, which lets navGrid stretch to the
        // VBox's full width. When the Z Focus widget below widened the
        // navigateContent VBox, the stretched navGrid drifted right because its
        // own Pos.CENTER content alignment had no extra space to play with.
        // Disable fillWidth so navGrid takes its preferred size and navSection's
        // Pos.CENTER actually centers it. Same applies to keyboardHint.
        navSection.setFillWidth(false);

        // XY status shown below navigation grid
        Label navXyStatus = new Label();
        navXyStatus.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
        navXyStatus.textProperty().bind(xyStatus.textProperty());

        // --- Move-to-position controls (formerly the Position tab) ---
        Label moveToLabel = new Label("Move to Position");
        moveToLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        HBox moveXyRow = new HBox(4, xLabel, xField, yLabel, yField, moveXYBtn);
        moveXyRow.setAlignment(Pos.CENTER_LEFT);

        // Step controls live with the ZBarPanel's right-side button column now;
        // the move-to-position Z row stays compact with just the typed-target trio.
        HBox moveZRow = new HBox(4, zLabel, zField, moveZBtn);
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

        // Re-runnable calibration for stage polarity + camera orientation. Opens the same
        // dialog the Setup Wizard uses, so the arrow buttons / joystick / click-to-center
        // can be re-tuned without restarting QuPath or hand-editing preferences.
        Button calibrateDirectionsBtn = new Button("Calibrate Directions...");
        Tooltip calibrateTooltip =
                new Tooltip("Jog the stage and confirm which way the image pans to back-solve the correct\n"
                        + "stage polarity and camera orientation preferences.");
        calibrateTooltip.setShowDelay(Duration.millis(300));
        Tooltip.install(calibrateDirectionsBtn, calibrateTooltip);
        calibrateDirectionsBtn.setOnAction(e -> qupath.ext.qpsc.ui.StageDirectionCalibrationDialog.show(
                calibrateDirectionsBtn.getScene() == null
                        ? null
                        : calibrateDirectionsBtn.getScene().getWindow()));
        HBox calibrateRow = new HBox(6, calibrateDirectionsBtn);
        calibrateRow.setAlignment(Pos.CENTER_LEFT);

        // Z bar widget (fine + coarse vertical bars, focus-metric trace, Mark Z)
        String scannerKey = PersistentPreferences.getSelectedScanner();
        BooleanBinding movementDisabled =
                Bindings.createBooleanBinding(() -> !internalLiveActive.get(), internalLiveActive);
        zBarPanel = new ZBarPanel(
                scannerKey,
                () -> mgr.getStageLimit("z", "low"),
                () -> mgr.getStageLimit("z", "high"),
                this::streamZTo,
                this::markZ,
                movementDisabled,
                event -> handleZScroll(event, zStepField));

        // Relocate the Z step controls from the move-to-position row into the
        // ZBarPanel's right-side column (under Mark Z / Max Z Focus). The step
        // size is shared: scrolling on the bars uses this same field. Stacked
        // vertically -- the column is too narrow for label + field + help on
        // one line without truncation.
        Label zStepHere = new Label("Step (um):");
        zStepHere.setStyle("-fx-font-size: 10px;");
        HBox stepFieldRow = new HBox(4, zStepField, zHelpBtn);
        stepFieldRow.setAlignment(Pos.CENTER_LEFT);
        VBox stepBlock = new VBox(2, zStepHere, stepFieldRow);
        stepBlock.setAlignment(Pos.CENTER_LEFT);
        zBarPanel.getControlsColumn().getChildren().add(stepBlock);

        Label zBarLabel = new Label("Z Focus");
        zBarLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");

        // Layout order:
        //  1. Banner (visible only when Live View is off)
        //  2. Step controls (FoV combo + Refresh + help)
        //  3. Sample Movement row
        //  4. Go to Centroid section -- moved up so users see it first; greyed
        //     out when no legal annotation is selected.
        //  5. FoV info text -- pushed below centroidSection so the FoV combo
        //     dropdown (which expands downward) does not obstruct it.
        //  6. Navigation grid (joystick / arrows / WASD hint)
        //  7. Z Focus widget
        //  8. Move to Position (typed X / Y / Z / R targets)
        //  9. Calibrate Directions button -- bottom of the tab. Re-run is
        //     occasional (after rewiring or remounting), not part of the
        //     primary navigation loop, so it sinks to the end out of the way.
        navigateContent
                .getChildren()
                .addAll(
                        movementDisabledBanner,
                        stepRow1,
                        stepRow2,
                        centroidSection,
                        fovInfoLabel,
                        new Separator(),
                        navSection,
                        navXyStatus,
                        new Separator(),
                        zBarLabel,
                        zBarPanel,
                        new Separator(),
                        moveToLabel,
                        moveXyRow,
                        xyStatus,
                        moveZRow,
                        zStatus,
                        moveRRow,
                        rStatus,
                        new Separator(),
                        calibrateRow);
        navigateTab.setContent(tabScroll(navigateContent));

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
        savedPointsTab.setContent(tabScroll(savedPointsContent));

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

        // Let the TabPane fill the available height so its content area is
        // bounded and the per-tab ScrollPanes engage -- keeping the tab HEADERS
        // fixed at the top while only the content scrolls.
        VBox.setVgrow(tabPane, javafx.scene.layout.Priority.ALWAYS);
        content.getChildren().add(tabPane);
        return content;
    }

    /**
     * Wraps a tab's content in a ScrollPane so the TabPane headers stay fixed
     * while only the content scrolls (matches the Camera tab's pattern). Without
     * this, the outer panel scroll moved the tab headers off-screen when a tab's
     * content was taller than the window.
     */
    private static ScrollPane tabScroll(javafx.scene.Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background-color: transparent;");
        return sp;
    }

    // ------------------------------------------------------------------
    // Camera tab (modality-dependent)
    // ------------------------------------------------------------------

    private Label cameraStatusLabel;
    private VBox cameraModContent; // Swapped when modality changes
    private volatile String currentCameraModality; // Current modality for preset refresh

    /**
     * Returns the modality currently selected in the Camera tab, or
     * {@code null} if none has been chosen yet. Thread-safe -- the
     * field is volatile and this method is a simple read.
     *
     * <p>Used by other Live Viewer controls (e.g. Streaming Autofocus) that
     * need to know the active modality to make modality-aware
     * decisions. For Streaming Autofocus specifically, the server uses the
     * modality to pick a saturation-refusal threshold: brightfield
     * is tolerant (~30%), PPM is moderate (~5%), fluorescence and
     * laser-scanning are strict (1-2%).
     */
    public String getCurrentCameraModality() {
        return currentCameraModality;
    }

    private String currentCameraObjectiveId;
    private String currentCameraDetectorId;

    /** Returns the currently detected objective ID, or "Unknown". */
    public String getCurrentObjectiveId() {
        return currentCameraObjectiveId;
    }

    /** Returns the currently detected detector ID, or "Unknown". */
    public String getCurrentDetectorId() {
        return currentCameraDetectorId;
    }

    // ---- Per-channel preview state (Camera tab) -----------------------
    // Populated as channels are built in buildCameraTab so callers
    // (e.g. LiveViewerWindow's streaming-AF preflight) can query the
    // currently-selected channel's spinner value without a socket
    // round-trip to MMCore. The 150 ms PauseTransition debounce keeps
    // socket load bounded under spinner-drag.

    private ToggleGroup cameraChannelGroup;
    private final Map<String, RadioButton> cameraChannelRadios = new HashMap<>();
    private final Map<String, Spinner<Double>> cameraChannelExpSpinners = new HashMap<>();
    private final Map<String, Spinner<Double>> cameraChannelIntSpinners = new HashMap<>();
    private final Map<String, qupath.ext.qpsc.modality.Channel> cameraChannelDefs = new HashMap<>();
    private final Map<String, PauseTransition> cameraChannelExpDebounces = new HashMap<>();
    private final Map<String, Double> cameraChannelExpPending = new HashMap<>();
    /** Active acquisition-profile selection in the Camera tab Profile dropdown. */
    private volatile String cameraActiveProfile;

    /**
     * Returns the per-channel exposure (ms) the user has dialed in for the
     * currently-selected channel radio. Returns NaN if the "None" radio is
     * selected, no channel radios exist, or the camera tab has not been
     * built yet. Callers should fall back to MMCore's exposure
     * ({@code MicroscopeSocketClient.getExposures()}) when this returns NaN.
     */
    public double getCurrentChannelExposureMs() {
        if (cameraChannelGroup == null) return Double.NaN;
        Toggle selected = cameraChannelGroup.getSelectedToggle();
        if (!(selected instanceof RadioButton rb)) return Double.NaN;
        String chId = null;
        for (Map.Entry<String, RadioButton> e : cameraChannelRadios.entrySet()) {
            if (e.getValue() == rb) {
                chId = e.getKey();
                break;
            }
        }
        if (chId == null) return Double.NaN; // "None" radio is not in the map
        Spinner<Double> sp = cameraChannelExpSpinners.get(chId);
        return sp == null ? Double.NaN : sp.getValue();
    }

    /**
     * If a per-channel exposure debounce is queued for the currently-selected
     * channel, fire it synchronously now. Used by callers that need MMCore to
     * match the spinner value before issuing a hardware operation (e.g.
     * streaming-AF preflight). No-op if the camera tab has not been built,
     * the "None" radio is selected, or no edit is pending.
     */
    public void flushPendingExposureSync() {
        if (cameraChannelGroup == null) return;
        Toggle selected = cameraChannelGroup.getSelectedToggle();
        if (!(selected instanceof RadioButton rb)) return;
        String chId = null;
        for (Map.Entry<String, RadioButton> e : cameraChannelRadios.entrySet()) {
            if (e.getValue() == rb) {
                chId = e.getKey();
                break;
            }
        }
        if (chId == null) return;
        PauseTransition deb = cameraChannelExpDebounces.get(chId);
        Double pending = cameraChannelExpPending.get(chId);
        if (deb == null || pending == null || Double.isNaN(pending)) return;
        // Stop the timer so it doesn't double-fire, then run the action
        // body inline. Mirrors what the debounce handler does.
        deb.stop();
        cameraChannelExpPending.remove(chId);
        try {
            MicroscopeController.getInstance().getSocketClient().setExposures(new float[] {pending.floatValue()});
            logger.info("flushPendingExposureSync: channel {} exposure -> {} ms", chId, pending);
        } catch (Exception ex) {
            logger.warn("flushPendingExposureSync: setExposures failed: {}", ex.toString());
        }
    }

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
        Label detLabel = new Label("Det:");
        detLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        Label detValue = new Label(shortenId(currentCameraDetectorId));
        detValue.setStyle("-fx-font-size: 10px;");
        detValue.setTooltip(new Tooltip(currentCameraDetectorId));
        Label objLabel = new Label("Obj:");
        objLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        Label objValue = new Label(shortenId(currentCameraObjectiveId));
        objValue.setStyle("-fx-font-size: 10px;");
        objValue.setTooltip(new Tooltip(currentCameraObjectiveId));
        hwGrid.add(detLabel, 0, 0);
        hwGrid.add(detValue, 1, 0);
        hwGrid.add(objLabel, 0, 1);
        hwGrid.add(objValue, 1, 1);

        // "Refresh from Micro-Manager" button: after the user changes
        // objective turret position in MM directly (not via QPSC), the
        // Camera tab and every downstream dialog that reads ObjectiveState
        // is still holding the stale objective from when the Live Viewer
        // first opened. This button re-runs detectCurrentHardware() (which
        // queries MM's live pixel size and reverse-matches it against the
        // YAML's objective table), broadcasts the new objective via
        // ObjectiveState so any open dialog re-syncs and the next-open
        // dialog sees it, and rebuilds the modality-specific content so
        // per-objective exposures/gains reload.
        Button refreshHardwareBtn = new Button("Refresh from MM");
        refreshHardwareBtn.setStyle("-fx-font-size: 10px;");
        refreshHardwareBtn.setTooltip(
                new Tooltip("Re-read the current objective and detector from Micro-Manager's live pixel size.\n"
                        + "Use this after manually rotating the objective turret so QPSC\n"
                        + "picks up the change. Also saves the new objective as the default\n"
                        + "for the next acquisition dialog."));
        refreshHardwareBtn.setOnAction(e -> {
            String previousObjective = currentCameraObjectiveId;
            String previousDetector = currentCameraDetectorId;
            detectCurrentHardware();
            objValue.setText(shortenId(currentCameraObjectiveId));
            objValue.setTooltip(new Tooltip(currentCameraObjectiveId));
            detValue.setText(shortenId(currentCameraDetectorId));
            detValue.setTooltip(new Tooltip(currentCameraDetectorId));
            if (currentCameraObjectiveId != null && !"Unknown".equals(currentCameraObjectiveId)) {
                // Route through ObjectiveState so any open dialog (Wizard,
                // Existing-Image, Background Collection, etc.) re-syncs.
                qupath.ext.qpsc.state.ObjectiveState.getInstance().setObjective(currentCameraObjectiveId);
            }
            // Per-objective exposures / gains / WB presets change with the
            // objective; rebuild the modality panel so the UI reflects the
            // newly-detected hardware.
            if (currentCameraModality != null) {
                rebuildCameraModContent(currentCameraModality);
            }
            if (cameraStatusLabel != null) {
                if (currentCameraObjectiveId != null && !currentCameraObjectiveId.equals(previousObjective)) {
                    cameraStatusLabel.setText("Hardware refreshed: objective "
                            + shortenId(previousObjective)
                            + " -> "
                            + shortenId(currentCameraObjectiveId));
                } else if (currentCameraDetectorId != null && !currentCameraDetectorId.equals(previousDetector)) {
                    cameraStatusLabel.setText("Hardware refreshed: detector "
                            + shortenId(previousDetector)
                            + " -> "
                            + shortenId(currentCameraDetectorId));
                } else {
                    cameraStatusLabel.setText("Hardware already up to date: "
                            + shortenId(currentCameraObjectiveId)
                            + " on "
                            + shortenId(currentCameraDetectorId));
                }
            }
            logger.info(
                    "Camera tab: refreshed hardware from MM -- objective='{}' detector='{}'",
                    currentCameraObjectiveId,
                    currentCameraDetectorId);
            if (onHardwareChanged != null) onHardwareChanged.run();
        });
        hwGrid.add(refreshHardwareBtn, 2, 0, 1, 2);
        GridPane.setMargin(refreshHardwareBtn, new Insets(0, 0, 0, 6));

        // Modality dropdown -- backed by ModalityState so changes here
        // propagate to other open dialogs (Wizard, Background Collection,
        // etc.) and drive the hardware via ModalityActuator.
        ComboBox<String> modalityCombo = new ComboBox<>();
        try {
            var modalities = mgr.getAvailableModalities();
            if (modalities != null) modalityCombo.getItems().addAll(modalities);
        } catch (Exception e) {
            logger.debug("Could not load modalities: {}", e.getMessage());
        }
        if (modalityCombo.getItems().isEmpty()) modalityCombo.getItems().add("ppm");
        // Bootstrap from the central state when its value is one of the
        // declared modalities; otherwise fall back to the first item.
        String initialFromState =
                qupath.ext.qpsc.state.ModalityState.getInstance().getModality();
        if (initialFromState != null && modalityCombo.getItems().contains(initialFromState)) {
            modalityCombo.setValue(initialFromState);
        } else {
            modalityCombo.setValue(modalityCombo.getItems().get(0));
        }
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

        // "Show Objective FoVs" toggle -- draws a temporary overlay on the
        // live image showing relative FoV sizes for all configured objectives.
        ToggleButton fovOverlayBtn = new ToggleButton("Show Objective FoVs");
        fovOverlayBtn.setMaxWidth(Double.MAX_VALUE);
        fovOverlayBtn.setStyle("-fx-font-size: 10px;");
        fovOverlayBtn.setTooltip(new Tooltip("Overlay color-coded rectangles on the live image showing each\n"
                + "objective's FoV relative to the current one. Useful for\n"
                + "framing decisions when switching magnifications."));
        fovOverlayBtn.setOnAction(e -> {
            if (onFovOverlayToggle != null) onFovOverlayToggle.run();
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
                        fullControlBtn,
                        fovOverlayBtn);

        // Populate initial content + wire dropdown through ModalityState.
        // Selection here -> ModalityState.setModality (which fans out to
        // every other linked combo + ModalityActuator drives APPLYPR).
        // External changes from other surfaces (Wizard etc.) arrive via the
        // property listener below, which keeps this combo in sync + rebuilds
        // the per-modality content panel.
        currentCameraModality = modalityCombo.getValue();
        rebuildCameraModContent(currentCameraModality);
        var modalityState = qupath.ext.qpsc.state.ModalityState.getInstance();
        // Ensure the actuator is constructed -- it subscribes to ModalityState
        // on first getInstance() so APPLYPR fires for changes from any UI.
        qupath.ext.qpsc.service.ModalityActuator.ensureRegistered();
        // Status messages from the actuator land on the Camera tab status label.
        Runnable actuatorUnsub = qupath.ext.qpsc.service.ModalityActuator.getInstance()
                .addStatusListener(status -> {
                    cameraStatusLabel.setText(status.text());
                    String color =
                            switch (status.phase()) {
                                case SWITCHING -> "#666";
                                case SUCCEEDED -> "green";
                                case FAILED -> "red";
                            };
                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: " + color + ";");
                });
        // External modality changes (Wizard, Background Collection, etc.) keep
        // this combo + its content panel in sync.
        javafx.beans.value.ChangeListener<String> stateListener = (obs, oldV, newV) -> {
            if (newV != null && !newV.equals(modalityCombo.getValue())) {
                modalityCombo.setValue(newV);
            }
            currentCameraModality = newV;
            if (newV != null) rebuildCameraModContent(newV);
            if (onModalityChanged != null) onModalityChanged.run();
        };
        modalityState.modalityProperty().addListener(stateListener);
        modalityCombo.setOnAction(e -> {
            cameraStatusLabel.setText("");
            currentCameraModality = modalityCombo.getValue();
            // Set on the central state; the stateListener above handles
            // rebuilding the per-modality content panel + the actuator
            // handles APPLYPR. setModality is idempotent so this does not
            // double-fire when the combo update came from the listener.
            modalityState.setModality(currentCameraModality);
        });
        // Lifecycle: StageControlPanel lives for the Live Viewer's life. When
        // the Live Viewer scene is unset, drop our listeners so the panel can
        // be GC'd cleanly.
        cameraContent.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                modalityState.modalityProperty().removeListener(stateListener);
                actuatorUnsub.run();
            }
        });

        // Wrap in a ScrollPane so the Fluorescence modality (per-channel
        // grid + preset bar + tip + Save-to-Profile + Save/Load preset rows)
        // doesn't push cameraStatusLabel / fullControlBtn / fovOverlayBtn
        // off the bottom edge. The outer stageScrollPane in LiveViewerWindow
        // can't help because TabPane internally constrains its tab content's
        // height and clips overflow.
        ScrollPane cameraScroll = new ScrollPane(cameraContent);
        cameraScroll.setFitToWidth(true);
        cameraScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        cameraScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        cameraScroll.setStyle("-fx-background-color: transparent;");
        tab.setContent(cameraScroll);
        return tab;
    }

    /** Detect current objective/detector by matching MicroManager pixel size against config. */
    private void detectCurrentHardware() {
        currentCameraObjectiveId = "Unknown";
        currentCameraDetectorId = "Unknown";
        try {
            var objectives = mgr.getAvailableObjectives();
            var detectors = mgr.getHardwareDetectors();

            boolean detected = false;
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc != null && mc.isConnected()) {
                try {
                    double mmPx = mc.getSocketClient().getMicroscopePixelSize();
                    var match =
                            mgr.findHardwareByPixelSize(mmPx, MicroscopeConfigManager.DEFAULT_PIXEL_SIZE_TOLERANCE_UM);
                    if (match.isPresent()) {
                        currentCameraObjectiveId = match.get().objectiveId();
                        currentCameraDetectorId = match.get().detectorId();
                        detected = true;
                        logger.info(
                                "Camera tab: detected {}/{} from MM pixel size {}",
                                currentCameraObjectiveId,
                                currentCameraDetectorId,
                                mmPx);
                    }
                } catch (Exception e) {
                    logger.debug("Camera tab: pixel size detection failed: {}", e.getMessage());
                }
            }
            if (!detected) {
                // Fall back to the last known objective if it's still in the config.
                // Do NOT guess by picking the first objective -- that silently selects
                // the wrong hardware when the server isn't connected yet.
                String lastObj =
                        qupath.ext.qpsc.state.ObjectiveState.getInstance().getObjective();
                if (lastObj != null && !lastObj.isEmpty() && objectives != null && objectives.contains(lastObj))
                    currentCameraObjectiveId = lastObj;
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
        } else if (norm.startsWith("fl")
                || norm.startsWith("fluorescence")
                || norm.startsWith("widefield")
                || norm.startsWith("epi")) {
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

        // Save current illumination (live GETILLM value) into the active
        // acquisition profile in the YAML. Without this, BG and tiled
        // acquisition reload the YAML's profile.illumination_intensity and
        // ignore whatever the user just tuned in the Camera tab.
        cameraModContent.getChildren().addAll(new Separator(), buildBrightfieldSaveToProfileButton(modality));

        // Save/Load preset buttons
        cameraModContent.getChildren().addAll(new Separator(), buildPresetButtons(modality));

        if (!anyPresets) addNoPresetsLabel();
    }

    /**
     * Widefield fluorescence (and BF+IF combined) modality: one row per
     * channel from the YAML channel library, each row exposing a radio to
     * mark the live-preview channel, an exposure spinner, and an intensity
     * spinner bound to the channel's {@code intensityProperty}. Falls back
     * to the single-exposure layout when the modality has no channel
     * library declared.
     *
     * <p>Live preview path: the radio drives cube/shutter/light source via
     * APPLYCH; the exposure spinner drives the camera via SETEXP; the
     * intensity spinner writes the channel's {@code intensity_property}
     * directly via SETPROP so the live preview brightens/dims in real
     * time. The intensity value is also persisted to
     * {@code PersistentPreferences.setLastChannelIntensity} so the next
     * BoundedAcquisition command's {@code --channel-intensities} flag
     * inherits the user's tuning.
     */
    private void buildFluorescenceCameraContent(String modality) {
        // Profile selector (for channel switching: bf_20x -> fl_20x, etc.)
        Node profileSelector = buildProfileSelector(modality);
        if (profileSelector != null) {
            cameraModContent.getChildren().addAll(profileSelector, new Separator());
        }

        // Try the channel library first. If the modality has declared
        // channels, render one row per channel. Otherwise fall back to
        // the old single-exposure layout.
        java.util.List<qupath.ext.qpsc.modality.Channel> channels = java.util.List.of();
        try {
            channels = mgr.getModalityChannels(modality);
        } catch (Exception e) {
            logger.debug("Could not load channel library for '{}': {}", modality, e.getMessage());
        }

        if (!channels.isEmpty()) {
            Label header = new Label("Per-channel controls");
            header.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
            cameraModContent.getChildren().add(header);

            // Resolve the profile we'll send APPLYCH against. The radio
            // button drives hardware via the profile's channel library; we
            // pick the first profile that matches the active modality. If
            // none exists, channel switching is disabled (status label
            // explains why).
            final String resolvedProfile = findFirstMatchingProfile(modality);

            // Reset per-channel preview state for the freshly-built tab.
            // The Camera tab is rebuilt on modality switch; we drop any
            // stale entries so getCurrentChannelExposureMs() / flush
            // don't reference a previous modality's channels.
            cameraChannelRadios.clear();
            cameraChannelExpSpinners.clear();
            cameraChannelIntSpinners.clear();
            cameraChannelDefs.clear();
            cameraChannelExpDebounces.clear();
            cameraChannelExpPending.clear();
            cameraChannelGroup = new ToggleGroup();
            ToggleGroup previewGroup = cameraChannelGroup;
            GridPane grid = new GridPane();
            grid.setHgap(6);
            grid.setVgap(4);
            // Column headers
            Label prevCol = new Label("Preview");
            Label idCol = new Label("Channel");
            Label expCol = new Label("Exp (ms)");
            Label intCol = new Label("Intensity");
            for (Label l : new Label[] {prevCol, idCol, expCol, intCol}) {
                l.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
                // GridPane otherwise sizes column 0 to the narrow RadioButton
                // below, truncating the "Preview" header to "...". Force each
                // header to its preferred width so the text always renders.
                l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            }
            grid.add(prevCol, 0, 0);
            grid.add(idCol, 1, 0);
            grid.add(expCol, 2, 0);
            grid.add(intCol, 3, 0);

            // "None" row: explicit deactivate-all option so the user can
            // close the shutter / turn off illumination from the dialog.
            // Without this the ToggleGroup forces exactly one channel
            // selected at all times.
            RadioButton noneRadio = new RadioButton();
            noneRadio.setToggleGroup(previewGroup);
            noneRadio.setSelected(true); // Start in deactivated state.
            Label noneLabel = new Label("(None -- deactivate all)");
            noneLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #888888;");
            noneRadio.setOnAction(e -> {
                if (resolvedProfile == null) {
                    cameraStatusLabel.setText("No matching profile for modality " + modality);
                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                    return;
                }
                applyChannelInBackground(resolvedProfile, "");
            });
            grid.add(noneRadio, 0, 1);
            grid.add(noneLabel, 1, 1);

            int row = 2;
            for (qupath.ext.qpsc.modality.Channel ch : channels) {
                RadioButton previewRadio = new RadioButton();
                previewRadio.setToggleGroup(previewGroup);
                previewRadio.setTooltip(new Tooltip("Mark " + ch.displayName() + " as the active preview channel. "
                        + "Switches cube/shutter/light source via APPLYCH; "
                        + "the per-channel Exp/Intensity spinners then drive the hardware live."));

                // Show the channel id only -- displayName carries the wavelength
                // ("DAPI (385 nm)") which doubles the column width and pushes
                // the spinners into a single-character editor in cramped layouts.
                Label channelLabel = new Label(ch.id());
                channelLabel.setStyle("-fx-font-size: 10px;");
                if (ch.displayName() != null && !ch.displayName().equals(ch.id())) {
                    channelLabel.setTooltip(new Tooltip(ch.displayName()));
                }

                // Default vertical arrows (stacked on the right) eat ~12 px;
                // split-arrows-horizontal eats ~50 px and leaves the editor
                // showing one digit. Use the default skin and a wider editor.
                //
                // Initialize from persisted last-edit so spinners stay
                // consistent with what was tuned in a previous session
                // (and also across modality rebuilds within one session).
                double initialExp = resolveChannelExposureMs(modality, ch);
                Spinner<Double> expSpinner = new Spinner<>(0.1, 10000.0, initialExp, 1.0);
                expSpinner.setPrefWidth(95);
                expSpinner.setEditable(true);

                Spinner<Double> intSpinner = new Spinner<>(0.0, 100000.0, resolveChannelIntensity(ch), 1.0);
                intSpinner.setPrefWidth(95);
                intSpinner.setEditable(true);
                // Disable intensity spinner if the channel has no intensityProperty.
                // There's nothing for the spinner to write to in that case.
                if (ch.intensityProperty() == null) {
                    intSpinner.setDisable(true);
                    intSpinner.setTooltip(new Tooltip("No intensity_property declared for this channel in the YAML."));
                }

                // Debounce spinner edits: dragging the arrows fires the
                // listener on every interpolated value (50-100/sec), and each
                // fire previously spawned a daemon thread + socket round-trip.
                // PauseTransition coalesces -- only the last value seen in a
                // 150 ms quiet window actually goes to the wire. The pending
                // value is also stored in cameraChannelExpPending so
                // flushPendingExposureSync() can fire SETEXP synchronously
                // when streaming-AF needs MMCore to match the spinner.
                final String chIdLocal = ch.id();
                final PauseTransition expDebounce = new PauseTransition(Duration.millis(150));
                expDebounce.setOnFinished(e -> {
                    Double pending = cameraChannelExpPending.remove(chIdLocal);
                    if (pending == null || Double.isNaN(pending)) return;
                    double v = pending;
                    qupath.ext.qpsc.preferences.PersistentPreferences.setLastChannelExposureMs(modality, chIdLocal, v);
                    try {
                        MicroscopeController.getInstance().getSocketClient().setExposures(new float[] {(float) v});
                        cameraStatusLabel.setText("Channel " + chIdLocal + " exposure -> " + v + " ms");
                        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                    } catch (Exception ex) {
                        cameraStatusLabel.setText("Exposure update failed: " + ex.getMessage());
                        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                    }
                });
                expSpinner.valueProperty().addListener((obs, oldV, newV) -> {
                    if (newV == null) return;
                    cameraChannelExpPending.put(chIdLocal, newV);
                    expDebounce.playFromStart();
                });
                cameraChannelExpSpinners.put(chIdLocal, expSpinner);
                cameraChannelIntSpinners.put(chIdLocal, intSpinner);
                cameraChannelDefs.put(chIdLocal, ch);
                cameraChannelExpDebounces.put(chIdLocal, expDebounce);
                cameraChannelRadios.put(chIdLocal, previewRadio);

                final double[] pendingInt = {Double.NaN};
                final PauseTransition intDebounce = new PauseTransition(Duration.millis(150));
                intDebounce.setOnFinished(e -> {
                    double v = pendingInt[0];
                    if (Double.isNaN(v) || ch.intensityProperty() == null) return;
                    qupath.ext.qpsc.preferences.PersistentPreferences.setLastChannelIntensity(modality, ch.id(), v);

                    // Drive the hardware live via SETPROP. We write directly
                    // to the channel's intensity_property so the user sees
                    // the live preview brighten/dim as they spin. Done on a
                    // background thread because socket I/O on the FX thread
                    // freezes the slider UI under load.
                    //
                    // Note: NO withLiveModeHandling here. Stopping/restarting
                    // continuous acquisition on every spinner edit caused a
                    // visible brightness blip on Enter (the camera resync
                    // briefly lost the LED state). DLED Intensity-XXXnm
                    // accepts writes during live mode without issue.
                    final qupath.ext.qpsc.modality.PropertyRef pr = ch.intensityProperty();
                    Thread t = new Thread(
                            () -> {
                                try {
                                    MicroscopeController mc = MicroscopeController.getInstance();
                                    if (mc == null || !mc.isConnected()) return;
                                    String valueStr = formatIntensityValue(v);
                                    mc.getSocketClient().setProperty(pr.device(), pr.property(), valueStr);
                                    Platform.runLater(() -> {
                                        cameraStatusLabel.setText("Channel " + ch.id() + " intensity -> " + v);
                                        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                                    });
                                } catch (Exception ex) {
                                    logger.warn(
                                            "SETPROP({}.{} <- {}) failed: {}",
                                            pr.device(),
                                            pr.property(),
                                            v,
                                            ex.getMessage());
                                    Platform.runLater(() -> {
                                        cameraStatusLabel.setText("Intensity update failed: " + ex.getMessage());
                                        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                                    });
                                }
                            },
                            "LiveViewer-SetIntensity");
                    t.setDaemon(true);
                    t.start();
                });
                intSpinner.valueProperty().addListener((obs, oldV, newV) -> {
                    if (newV == null) return;
                    pendingInt[0] = newV;
                    intDebounce.playFromStart();
                });

                previewRadio.setOnAction(e -> {
                    qupath.ext.qpsc.preferences.PersistentPreferences.setLastFocusChannelId(ch.id());
                    if (resolvedProfile == null) {
                        cameraStatusLabel.setText("No matching profile for modality " + modality);
                        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        return;
                    }
                    // Drive the hardware to this channel: cube/shutter switch +
                    // per-channel illumination + exposure all happen on the
                    // server via apply_channel_hardware_state. Then push the
                    // spinner values back to hardware so the user's tunings
                    // win over the YAML defaults APPLYCH just wrote --
                    // otherwise FITC -> TRITC -> FITC re-applies FITC's YAML
                    // defaults each visit and the user's edits are lost.
                    Double expOverride = expSpinner.getValue();
                    Double intOverride = (ch.intensityProperty() != null) ? intSpinner.getValue() : null;
                    applyChannelInBackground(
                            resolvedProfile, ch.id(), expOverride, ch.intensityProperty(), intOverride);
                });

                grid.add(previewRadio, 0, row);
                grid.add(channelLabel, 1, row);
                grid.add(expSpinner, 2, row);
                grid.add(intSpinner, 3, row);
                row++;
            }

            cameraModContent.getChildren().add(grid);

            // Named channel-preset bar -- shares the WidefieldChannelPresetStore
            // with the bounded-acquisition dialog's Fluorescence panel, so a
            // preset saved here is immediately available in the acquisition
            // dialog and vice versa.
            cameraModContent.getChildren().add(buildChannelPresetBar(resolvedProfile));

            Label liveNote = new Label("Tip: select a channel radio first to switch the cube/light path; "
                    + "then the Exp/Intensity spinners drive the hardware live via SETEXP/SETPROP.");
            liveNote.setWrapText(true);
            liveNote.setStyle("-fx-font-size: 9px; -fx-text-fill: gray; -fx-font-style: italic;");
            cameraModContent.getChildren().addAll(new Separator(), liveNote);

            // Persist the spinner-tuned values to YAML so they survive
            // restarts and feed BG / acquisition. Hover the button for the
            // exact list of fields written for this modality.
            cameraModContent.getChildren().addAll(new Separator(), buildFluorescenceSaveToProfileButton(modality));
        } else {
            // Fallback for profiles without a channel library.
            cameraModContent.getChildren().add(buildExposureControl());

            Node illumControl = buildIlluminationControl();
            if (illumControl != null) {
                cameraModContent.getChildren().addAll(new Separator(), illumControl);
            }
        }

        // Save/Load preset buttons (shared across both paths)
        cameraModContent.getChildren().addAll(new Separator(), buildPresetButtons(modality));
    }

    /**
     * Resolves the intensity spinner's initial value for a channel: first
     * try the saved PersistentPreferences entry, then the default value
     * from the channel's intensityProperty in the YAML, then 0.
     */
    private double resolveChannelIntensity(qupath.ext.qpsc.modality.Channel channel) {
        Double saved = qupath.ext.qpsc.preferences.PersistentPreferences.getLastChannelIntensity(
                currentCameraModality, channel.id());
        if (saved != null) return saved;
        if (channel.intensityProperty() != null) {
            for (qupath.ext.qpsc.modality.PropertyWrite pw : channel.properties()) {
                if (pw.device().equals(channel.intensityProperty().device())
                        && pw.property().equals(channel.intensityProperty().property())) {
                    try {
                        return Double.parseDouble(pw.value());
                    } catch (NumberFormatException ex) {
                        return 0.0;
                    }
                }
            }
        }
        return 0.0;
    }

    /**
     * Resolves the exposure spinner's initial value for a channel: first the
     * persisted last-edit (per modality + channel), then the channel's
     * YAML-declared {@code defaultExposureMs()}. Mirrors
     * {@link #resolveChannelIntensity}, so an FITC tune-up survives a
     * modality rebuild instead of snapping back to 80 ms every time.
     */
    private double resolveChannelExposureMs(String modality, qupath.ext.qpsc.modality.Channel channel) {
        Double saved =
                qupath.ext.qpsc.preferences.PersistentPreferences.getLastChannelExposureMs(modality, channel.id());
        if (saved != null && saved > 0) return saved;
        return channel.defaultExposureMs();
    }

    /**
     * Build a "Save to profile" button for the brightfield Camera tab.
     * Writes the live illumination level (read via GETILLM) to
     * {@code acquisition_profiles.<active_profile>.illumination_intensity}
     * in the microscope YAML, calls reload on both the Java config manager
     * and the Python server (RECONFG), then rebuilds the tab so dropdowns
     * reflect the new state.
     */
    private Node buildBrightfieldSaveToProfileButton(String modality) {
        Button saveBtn = new Button("Save Intensity to Profile");
        saveBtn.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        saveBtn.setTooltip(new Tooltip("Persists the LIVE lamp intensity (whatever GETILLM reports right now) "
                + "to acquisition_profiles." + (cameraActiveProfile == null ? "<profile>" : cameraActiveProfile)
                + ".illumination_intensity in your microscope YAML.\n\n"
                + "Why it matters: Background Collection and tiled Acquisition both pull "
                + "this value from the YAML profile (apply_profile_illumination + "
                + "apply_mode_setup) -- they do NOT inherit whatever the Camera tab last "
                + "tuned in live preview. Save here so a 700 Camera-tab tune-up actually "
                + "drives BG and acquisition at 700.\n\n"
                + "After save: the Java MicroscopeConfigManager reloads from disk and "
                + "the server runs RECONFG so both sides see the new value immediately.\n\n"
                + "Note: brightfield exposure is NOT saved -- BF exposure is determined "
                + "per-acquisition by the adaptive-exposure loop targeting the detector's "
                + "background_target_intensity, not by a profile-level field."));
        saveBtn.setOnAction(e -> doSaveBrightfieldProfile(modality));
        HBox row = new HBox(4, saveBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void doSaveBrightfieldProfile(String modality) {
        final String profileKey = cameraActiveProfile;
        if (profileKey == null) {
            cameraStatusLabel.setText("No profile selected -- cannot save");
            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
            return;
        }
        final String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            cameraStatusLabel.setText("No config path configured");
            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
            return;
        }
        cameraStatusLabel.setText("Saving illumination to " + profileKey + "...");
        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Thread t = new Thread(
                () -> {
                    try {
                        MicroscopeController mc = MicroscopeController.getInstance();
                        if (mc == null || !mc.isConnected()) throw new java.io.IOException("Not connected");
                        var illum = mc.getSocketClient().getIllumination();
                        if (!illum.available()) {
                            throw new java.io.IOException("Server reports no illumination configured");
                        }
                        double power = illum.power();
                        var result = qupath.ext.qpsc.utilities.ConfigYamlEditor.setProfileScalar(
                                java.nio.file.Paths.get(configPath), profileKey, "illumination_intensity", power);
                        // Reload on BOTH sides so the next BG / acquisition actually sees the new value.
                        mgr.reload(configPath);
                        try {
                            mc.getSocketClient().sendReconfig();
                        } catch (Exception reconfigEx) {
                            logger.warn("RECONFG after save failed: {}", reconfigEx.getMessage());
                        }
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Saved: " + result.message);
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                            rebuildCameraModContent(modality);
                        });
                    } catch (Exception ex) {
                        logger.error("Save brightfield profile failed: {}", ex.getMessage(), ex);
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Save failed: " + ex.getMessage());
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        });
                    }
                },
                "Save-Profile-BF");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Build a "Save all channels to YAML" button for the fluorescence
     * Camera tab. Walks every channel row in the tab and writes its
     * Exp/Intensity values to {@code modalities.<modality>.channels[*]}
     * in the microscope YAML (exposure_ms + the device_properties value
     * matching the channel's intensity_property), then reloads both sides.
     */
    private Node buildFluorescenceSaveToProfileButton(String modality) {
        Button saveBtn = new Button("Save Channels to YAML");
        saveBtn.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        StringBuilder lines = new StringBuilder();
        for (var ch : cameraChannelDefs.values()) {
            lines.append("  ").append(ch.id());
            if (ch.intensityProperty() != null) {
                lines.append(" -> exposure_ms + ")
                        .append(ch.intensityProperty().device())
                        .append(".")
                        .append(ch.intensityProperty().property());
            } else {
                lines.append(" -> exposure_ms only");
            }
            lines.append("\n");
        }
        saveBtn.setTooltip(new Tooltip("Persists the CURRENT spinner values for every channel below to "
                + "modalities." + modality + ".channels[*] in your microscope YAML.\n\n"
                + "Each channel writes:\n"
                + "  exposure_ms (from the Exp spinner)\n"
                + "  device_properties[intensity_property].value (from the Intensity spinner)\n\n"
                + "Channels saved this session:\n" + lines.toString()
                + "\nAcquisition reads these via apply_channel_hardware_state, so the "
                + "next BG/tile run will use exactly what you tuned in live preview "
                + "(no more spinner-vs-hardware drift).\n\n"
                + "After save: the Java MicroscopeConfigManager reloads from disk and "
                + "the server runs RECONFG so both sides see the new values immediately.\n\n"
                + "Note: profile-level channel_overrides (per-objective tuning) are NOT "
                + "touched -- this writes the modality-level base only."));
        saveBtn.setOnAction(e -> doSaveFluorescenceChannels(modality));
        HBox row = new HBox(4, saveBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void doSaveFluorescenceChannels(String modality) {
        final String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            cameraStatusLabel.setText("No config path configured");
            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
            return;
        }
        if (cameraChannelDefs.isEmpty()) {
            cameraStatusLabel.setText("No channels to save");
            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
            return;
        }
        // Snapshot spinner state on the FX thread so the worker reads
        // immutable values; otherwise the user dragging arrows during the
        // save races the writer.
        java.util.Map<String, Double> expSnap = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> intSnap = new java.util.LinkedHashMap<>();
        java.util.Map<String, qupath.ext.qpsc.modality.Channel> defSnap = new java.util.LinkedHashMap<>();
        for (var entry : cameraChannelDefs.entrySet()) {
            String id = entry.getKey();
            defSnap.put(id, entry.getValue());
            Spinner<Double> exp = cameraChannelExpSpinners.get(id);
            Spinner<Double> in = cameraChannelIntSpinners.get(id);
            if (exp != null && exp.getValue() != null) expSnap.put(id, exp.getValue());
            if (in != null && in.getValue() != null) intSnap.put(id, in.getValue());
        }
        cameraStatusLabel.setText("Saving " + defSnap.size() + " channels...");
        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Thread t = new Thread(
                () -> {
                    try {
                        java.nio.file.Path path = java.nio.file.Paths.get(configPath);
                        int writes = 0;
                        StringBuilder summary = new StringBuilder();
                        for (var entry : defSnap.entrySet()) {
                            String id = entry.getKey();
                            qupath.ext.qpsc.modality.Channel ch = entry.getValue();
                            Double exp = expSnap.get(id);
                            if (exp != null) {
                                var r = qupath.ext.qpsc.utilities.ConfigYamlEditor.setChannelExposureMs(
                                        path, modality, id, exp);
                                if (r.changed) writes++;
                                summary.append(id)
                                        .append(" exp ")
                                        .append(r.message)
                                        .append("; ");
                            }
                            Double intensity = intSnap.get(id);
                            if (intensity != null && ch.intensityProperty() != null) {
                                String valueStr = (intensity == Math.floor(intensity) && !Double.isInfinite(intensity))
                                        ? Long.toString((long) (double) intensity)
                                        : Double.toString(intensity);
                                var r = qupath.ext.qpsc.utilities.ConfigYamlEditor.setChannelDevicePropertyValue(
                                        path,
                                        modality,
                                        id,
                                        ch.intensityProperty().device(),
                                        ch.intensityProperty().property(),
                                        valueStr);
                                if (r.changed) writes++;
                                summary.append(id)
                                        .append(" int ")
                                        .append(r.message)
                                        .append("; ");
                            }
                        }
                        // Reload both sides
                        mgr.reload(configPath);
                        try {
                            MicroscopeController mc = MicroscopeController.getInstance();
                            if (mc != null && mc.isConnected()) {
                                mc.getSocketClient().sendReconfig();
                            }
                        } catch (Exception reconfigEx) {
                            logger.warn("RECONFG after save failed: {}", reconfigEx.getMessage());
                        }
                        final int finalWrites = writes;
                        final String finalSummary = summary.toString();
                        logger.info("Save channels to YAML: {} write(s). Detail: {}", finalWrites, finalSummary);
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Saved " + finalWrites + " field(s) to YAML + reloaded server");
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                            rebuildCameraModContent(modality);
                        });
                    } catch (Exception ex) {
                        logger.error("Save fluorescence channels failed: {}", ex.getMessage(), ex);
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Save failed: " + ex.getMessage());
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        });
                    }
                },
                "Save-Profile-FL");
        t.setDaemon(true);
        t.start();
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
        // Use the detected detector (matched from MM pixel size) when available.
        // When detection fails, defer to getActiveDetector() (PersistentPreferences
        // last-detector or the unique hardware detector) rather than iterator-first.
        // The 2026-04-27 silent-first-detector incident showed that hash-iteration
        // order is not safe to use as a stand-in for user intent -- and presets
        // are keyed by detector, so saving under the wrong key strands them.
        if (currentCameraDetectorId != null && !"Unknown".equals(currentCameraDetectorId)) {
            return currentCameraDetectorId;
        }
        String active = mgr.getActiveDetector();
        return active != null ? active : "Unknown";
    }

    // --- Shared Camera Tab builders ---

    /** Build an exposure control row (label + text field + Set button). */
    private Node buildExposureControl() {
        Label expLabel = new Label("Exposure (ms):");
        expLabel.setStyle("-fx-font-size: 10px;");
        TextField expField = new TextField();
        expField.setPrefWidth(80);
        expField.setPromptText("e.g., 33");
        // Prefer the persisted last-edit so a Camera-tab tune-up survives
        // a session restart. Apply it back to hardware so live preview
        // matches the prefilled value rather than whatever exposure the
        // active profile's apply_mode_setup just left in place.
        // Falls back to the live GETEXP value if no preference is saved.
        double saved = qupath.ext.qpsc.preferences.PersistentPreferences.getLastUnifiedExposureMs();
        boolean usedSaved = false;
        if (saved > 0) {
            expField.setText(String.format("%.1f", saved));
            usedSaved = true;
            final float toApply = (float) saved;
            Thread t = new Thread(
                    () -> {
                        try {
                            MicroscopeController mc = MicroscopeController.getInstance();
                            if (mc != null && mc.isConnected()) {
                                mc.getSocketClient().setExposures(new float[] {toApply});
                            }
                        } catch (Exception ex) {
                            logger.debug("Restoring last exposure {} ms failed: {}", toApply, ex.getMessage());
                        }
                    },
                    "Restore-Exposure");
            t.setDaemon(true);
            t.start();
        }
        if (!usedSaved) {
            try {
                var expResult =
                        MicroscopeController.getInstance().getSocketClient().getExposures();
                expField.setText(String.format("%.1f", expResult.unified()));
            } catch (Exception ex) {
                expField.setText("");
            }
        }
        Button applyBtn = new Button("Set");
        applyBtn.setStyle("-fx-font-size: 10px;");
        applyBtn.setOnAction(e -> {
            try {
                float exp = Float.parseFloat(expField.getText().trim());
                MicroscopeController.getInstance().getSocketClient().setExposures(new float[] {exp});
                // Persist so the Bounded Acquisition workflow inherits this
                // value when building the command for non-rotation modalities.
                qupath.ext.qpsc.preferences.PersistentPreferences.setLastUnifiedExposureMs(exp);
                cameraStatusLabel.setText("Exposure: " + exp + " ms");
                cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                logger.info("Set exposure to {} ms", exp);
                try {
                    var verify =
                            MicroscopeController.getInstance().getSocketClient().getExposures();
                    logger.info(
                            "Set exposure verify: GETEXP unified={} red={} green={} blue={} (perChannel={})",
                            verify.unified(),
                            verify.red(),
                            verify.green(),
                            verify.blue(),
                            verify.isPerChannel());
                } catch (Exception verifyEx) {
                    logger.warn("Set exposure verify GETEXP failed: {}", verifyEx.toString());
                }
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
            // Prefer the persisted last-edit per modality (e.g. BF) so the
            // Camera tab survives the APPLYPR-on-modality-switch that
            // otherwise resets the lamp to the YAML's
            // illumination_intensity. Push the persisted value back to
            // hardware on prefill so the live preview matches what the
            // slider shows. Falls back to the live GETILLM value if the
            // pref is not set.
            Double savedIntensity = (currentCameraModality != null)
                    ? qupath.ext.qpsc.preferences.PersistentPreferences.getLastModalityIllumination(
                            currentCameraModality)
                    : null;
            float current;
            if (savedIntensity != null && savedIntensity > 0) {
                float clamped = (float) Math.max(min, Math.min(max, savedIntensity));
                current = clamped;
                final float toApply = clamped;
                Thread t = new Thread(
                        () -> {
                            try {
                                MicroscopeController mc2 = MicroscopeController.getInstance();
                                if (mc2 != null && mc2.isConnected()) {
                                    mc2.getSocketClient().setIllumination(toApply);
                                }
                            } catch (Exception ex) {
                                logger.debug(
                                        "Restoring last {} illumination {} failed: {}",
                                        currentCameraModality,
                                        toApply,
                                        ex.getMessage());
                            }
                        },
                        "Restore-Illum");
                t.setDaemon(true);
                t.start();
            } else {
                current = illumResult.power();
            }

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
        // Persist non-zero values per modality so the next session/rebuild
        // restores the user's last tune-up instead of snapping back to the
        // YAML profile's illumination_intensity. Power=0 is the OFF toggle
        // and intentionally NOT persisted -- otherwise toggling off
        // permanently demotes the saved level to zero.
        if (currentCameraModality != null && power > 0) {
            qupath.ext.qpsc.preferences.PersistentPreferences.setLastModalityIllumination(currentCameraModality, power);
        }
        Thread t = new Thread(
                () -> {
                    try {
                        MicroscopeController mc = MicroscopeController.getInstance();
                        if (mc != null && mc.isConnected()) {
                            mc.getSocketClient().setIllumination(power);
                            logger.debug("Set illumination power to {}", power);
                        }
                    } catch (Exception ex) {
                        logger.warn("Failed to set illumination: {}", ex.getMessage());
                    }
                },
                "Illum-Set");
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

            // Filter profiles to ONLY those declaring this exact modality.
            // Mixing modalities here lets the user end up on Brightfield_10x
            // while the modality combo still says Fluorescence -- the radio
            // buttons then drive the wrong hardware path. The modality combo
            // (above) is the single source of truth for cross-modality
            // switching; this Profile: dropdown is just for picking the
            // objective within the current modality.
            java.util.List<String> matchingProfiles = new java.util.ArrayList<>();
            String modalityLower = modality.toLowerCase();
            for (var entry : profileMap.entrySet()) {
                String profileName = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof java.util.Map<?, ?> profileCfg) {
                    Object profModality = profileCfg.get("modality");
                    if (profModality != null
                            && profModality.toString().toLowerCase().equals(modalityLower)) {
                        matchingProfiles.add(profileName);
                    }
                }
            }

            if (matchingProfiles.isEmpty()) return null;

            Label label = new Label("Profile:");
            label.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
            ComboBox<String> profileCombo = new ComboBox<>();
            profileCombo.getItems().addAll(matchingProfiles);
            // Preserve the existing dropdown selection across rebuilds. Without
            // this, every rebuildCameraModContent (modality switch, Apply
            // Profile, Save/Load Preset) snaps the dropdown back to the first
            // profile in the list -- so a user on Brightfield_20x who clicks
            // Apply ends up displayed as Brightfield_10x even though they
            // applied 20x. The retained selection also lets Apply Profile
            // rebuild without losing context.
            String defaultSelection = matchingProfiles.get(0);
            if (cameraActiveProfile != null && matchingProfiles.contains(cameraActiveProfile)) {
                defaultSelection = cameraActiveProfile;
            }
            profileCombo.setValue(defaultSelection);
            cameraActiveProfile = defaultSelection;
            profileCombo.valueProperty().addListener((obs, oldV, newV) -> {
                if (newV != null) cameraActiveProfile = newV;
            });
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

                Thread t = new Thread(
                        () -> {
                            try {
                                MicroscopeController mc = MicroscopeController.getInstance();
                                if (mc == null || !mc.isConnected()) throw new Exception("Not connected");
                                mc.withLiveModeHandling(
                                        () -> mc.getSocketClient().applyProfile(selectedProfile));
                                // APPLYPR resets exposure + illumination to the YAML
                                // profile's apply_mode_setup defaults, but the UI
                                // fields still show whatever the user (or saved
                                // prefs) had previously displayed. Rebuilding the
                                // panel re-pulls the saved prefs and pushes them
                                // back to hardware via buildExposureControl /
                                // buildIlluminationControl's restore threads, so
                                // the displayed values match what's actually on
                                // the camera. Without this, the user sees stale
                                // numbers (e.g. "2 ms / 700") that don't reflect
                                // the dim image hardware is actually producing.
                                Platform.runLater(() -> {
                                    cameraStatusLabel.setText("Profile applied: " + selectedProfile);
                                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                                    rebuildCameraModContent(modality);
                                });
                            } catch (Exception ex) {
                                logger.error("Failed to apply profile '{}': {}", selectedProfile, ex.getMessage());
                                Platform.runLater(() -> {
                                    cameraStatusLabel.setText("Failed: " + ex.getMessage());
                                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                                });
                            }
                        },
                        "Profile-Apply");
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

    /**
     * Send APPLYPR for a profile matching the given modality so the
     * server's hardware state (filter cube, illumination, light path
     * presets) actually follows the dropdown. Runs in a background
     * thread under withLiveModeHandling. No-op if no matching profile
     * is configured.
     */
    /**
     * Send APPLYCH for a single channel from the given profile's library
     * on a daemon thread. Empty channel id deactivates all illumination
     * for the profile's modality (used by the "None" radio). Wrapped in
     * withLiveModeHandling so the streaming live view is properly stopped
     * around the cube/shutter switch and resumed afterwards.
     */
    private void applyChannelInBackground(String profileName, String channelId) {
        applyChannelInBackground(profileName, channelId, null, null, null);
    }

    /**
     * Apply a channel and then push the user's tuned exposure / intensity to
     * hardware so the displayed spinner values match what the camera actually
     * uses.
     *
     * <p>APPLYCH writes the channel's YAML defaults (exposure_ms +
     * Intensity-XXXnm). Without this restore step, switching FITC -> TRITC
     * -> FITC re-applies FITC's YAML defaults each time and ignores any
     * spinner edits the user made between switches. Symptom: TRITC click
     * goes super bright (YAML says 30) until the user nudges the spinner,
     * which then drops the signal to whatever was displayed.
     *
     * <p>Both overrides are optional. Pass {@code null} to skip a write
     * (e.g. the "None -- deactivate all" radio doesn't push anything).
     *
     * @param profileName       APPLYCH profile name
     * @param channelId         APPLYCH channel id (or null/empty for None)
     * @param exposureOverride  exposure (ms) to write after APPLYCH, or null
     * @param intensityProperty channel's intensity_property (device, property)
     *                          for the SETPROP override, or null
     * @param intensityOverride intensity value to write to that property, or null
     */
    private void applyChannelInBackground(
            String profileName,
            String channelId,
            Double exposureOverride,
            qupath.ext.qpsc.modality.PropertyRef intensityProperty,
            Double intensityOverride) {
        if (profileName == null) return;
        String label = (channelId == null || channelId.isEmpty()) ? "None" : channelId;
        cameraStatusLabel.setText("Applying " + label + "...");
        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
        Thread t = new Thread(
                () -> {
                    try {
                        MicroscopeController mc = MicroscopeController.getInstance();
                        if (mc == null || !mc.isConnected()) return;
                        mc.withLiveModeHandling(() -> {
                            mc.getSocketClient().applyChannel(profileName, channelId);
                            // Restore user tunings inside the same live-mode
                            // pause so the camera doesn't briefly run with
                            // YAML-default exposure before our override lands.
                            if (exposureOverride != null) {
                                mc.getSocketClient().setExposures(new float[] {exposureOverride.floatValue()});
                            }
                            if (intensityProperty != null && intensityOverride != null) {
                                String valueStr = formatIntensityValue(intensityOverride);
                                mc.getSocketClient()
                                        .setProperty(
                                                intensityProperty.device(), intensityProperty.property(), valueStr);
                            }
                        });
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Applied: " + label);
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                        });
                    } catch (Exception ex) {
                        logger.error("APPLYCH({}, {}) failed: {}", profileName, label, ex.getMessage());
                        Platform.runLater(() -> {
                            cameraStatusLabel.setText("Channel apply failed: " + ex.getMessage());
                            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                        });
                    }
                },
                "Channel-Apply");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Format an intensity spinner value as a Micro-Manager property string.
     * Integer-valued doubles drop the trailing ".0" so DLED.Intensity-475nm
     * receives "30" rather than "30.0" -- some MM drivers reject the latter
     * with "Invalid property value".
     */
    private static String formatIntensityValue(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Integer.toString((int) v);
        }
        return Double.toString(v);
    }

    /**
     * Builds the named channel-preset bar (ComboBox + Save / Delete) for the
     * Camera tab's Fluorescence content. Shares
     * {@link qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore}
     * with the bounded-acquisition dialog, so presets round-trip between the
     * two UIs.
     *
     * <p>Save semantics: captures the current per-channel exposure / intensity
     * spinner values for every channel in the active library, and marks
     * {@code selected=true} only on the channel whose preview radio is
     * currently active (or none if "(None)" is selected).
     *
     * <p>Load semantics: writes each preset entry's exposure / intensity into
     * the corresponding spinners (the spinner listeners then push to hardware
     * via SETEXP / SETPROP for the active channel only). If exactly one
     * channel in the preset is {@code selected=true} and we have a resolved
     * APPLYCH profile, fire that channel's radio so APPLYCH switches the
     * cube/light path. Otherwise the radio state is left alone.
     */
    private javafx.scene.layout.VBox buildChannelPresetBar(String resolvedProfile) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setPromptText("(no preset)");
        combo.setPrefWidth(160);
        combo.setStyle("-fx-font-size: 10px;");
        combo.setTooltip(
                new Tooltip("Select a saved channel preset to populate the per-channel exposure / intensity spinners.\n"
                        + "Presets are shared with the bounded-acquisition Fluorescence panel."));

        boolean[] suppressListener = {false};
        Runnable refresh = () -> {
            suppressListener[0] = true;
            try {
                combo.getItems().setAll(qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.loadNames());
                String last = qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.getLastPresetName();
                if (!last.isEmpty() && combo.getItems().contains(last)) {
                    combo.setValue(last);
                } else {
                    combo.setValue(null);
                }
            } finally {
                suppressListener[0] = false;
            }
        };
        refresh.run();

        combo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressListener[0] || newVal == null || newVal.isEmpty()) return;
            applyChannelPreset(newVal, resolvedProfile);
        });

        Button saveBtn = new Button("Save...");
        saveBtn.setStyle("-fx-font-size: 10px;");
        saveBtn.setTooltip(new Tooltip("Save the per-channel exposure / intensity spinner state as a named preset.\n"
                + "The active preview channel (or none) becomes the preset's 'selected' channel."));
        saveBtn.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Save Channel Preset");
            dialog.setHeaderText("Save current channel spinner state");
            dialog.setContentText("Preset name:");
            dialog.showAndWait().ifPresent(rawName -> {
                String name = rawName == null ? "" : rawName.trim();
                String error = qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.validateName(name);
                if (error != null) {
                    cameraStatusLabel.setText(error);
                    cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
                    return;
                }
                boolean overwrite = qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.loadNames()
                        .contains(name);
                qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.savePreset(
                        name, getActiveChannelId(), snapshotChannelState());
                refresh.run();
                cameraStatusLabel.setText((overwrite ? "Updated preset: " : "Saved preset: ") + name);
                cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
            });
        });

        Button deleteBtn = new Button("Delete");
        deleteBtn.setStyle("-fx-font-size: 10px;");
        deleteBtn.setTooltip(new Tooltip("Delete the currently-selected preset."));
        deleteBtn.disableProperty().bind(combo.valueProperty().isNull());
        deleteBtn.setOnAction(e -> {
            String name = combo.getValue();
            if (name == null || name.isEmpty()) return;
            qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.deletePreset(name);
            refresh.run();
            cameraStatusLabel.setText("Deleted preset: " + name);
            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
        });

        Label label = new Label("Preset:");
        label.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        // Two-row layout: combo on the Presets line, Save/Delete on the next
        // line. The Camera tab is narrow enough that a single-row layout
        // truncates the label to "Pr..." and the buttons to one letter each.
        javafx.scene.layout.HBox topRow = new javafx.scene.layout.HBox(6, label, combo);
        topRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.HBox bottomRow = new javafx.scene.layout.HBox(6, saveBtn, deleteBtn);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.VBox bar = new javafx.scene.layout.VBox(4, topRow, bottomRow);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** Returns the channel id whose preview radio is currently active, or null if "(None)" is. */
    private String getActiveChannelId() {
        for (var entry : cameraChannelRadios.entrySet()) {
            if (entry.getValue().isSelected()) return entry.getKey();
        }
        return null;
    }

    /** Snapshot the current per-channel spinner state for preset save. */
    private java.util.Map<String, qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.ChannelState>
            snapshotChannelState() {
        java.util.Map<String, qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.ChannelState> out =
                new java.util.LinkedHashMap<>();
        String activeId = getActiveChannelId();
        for (var entry : cameraChannelExpSpinners.entrySet()) {
            String id = entry.getKey();
            Double exp = entry.getValue().getValue();
            Spinner<Double> intSpinner = cameraChannelIntSpinners.get(id);
            Double intensity = intSpinner != null ? intSpinner.getValue() : null;
            boolean selected = id.equals(activeId);
            out.put(
                    id,
                    new qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.ChannelState(
                            selected, exp, intensity));
        }
        return out;
    }

    /**
     * Apply a preset by name: write exposures / intensities into the
     * per-channel spinners (the spinner listeners debounce and push to
     * hardware), then -- if the preset has exactly one channel marked
     * selected and we have a profile -- fire that channel's radio to drive
     * APPLYCH.
     */
    private void applyChannelPreset(String name, String resolvedProfile) {
        var preset = qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.loadPreset(name);
        if (preset == null) {
            cameraStatusLabel.setText("Preset '" + name + "' has no stored data.");
            cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
            return;
        }
        String singleSelectedId = null;
        int selectedCount = 0;
        for (var entry : preset.states().entrySet()) {
            String id = entry.getKey();
            var state = entry.getValue();
            if (state.selected()) {
                selectedCount++;
                singleSelectedId = id;
            }
            Spinner<Double> exp = cameraChannelExpSpinners.get(id);
            if (exp != null && state.exposureMs() != null) {
                try {
                    exp.getValueFactory().setValue(state.exposureMs());
                } catch (Exception ex) {
                    logger.warn("Set exposure spinner failed for '{}': {}", id, ex.getMessage());
                }
            }
            Spinner<Double> intensity = cameraChannelIntSpinners.get(id);
            if (intensity != null && state.intensity() != null && !intensity.isDisable()) {
                try {
                    intensity.getValueFactory().setValue(state.intensity());
                } catch (Exception ex) {
                    logger.warn("Set intensity spinner failed for '{}': {}", id, ex.getMessage());
                }
            }
        }
        qupath.ext.qpsc.modality.widefield.WidefieldChannelPresetStore.setLastPresetName(name);

        // If the preset captured exactly one active channel and we know the
        // APPLYCH profile, fire that radio so the cube/light path follows
        // the preset. Multi-select presets leave the live radio alone --
        // the Camera tab can only preview one channel at a time.
        if (selectedCount == 1 && singleSelectedId != null && resolvedProfile != null) {
            RadioButton radio = cameraChannelRadios.get(singleSelectedId);
            if (radio != null) {
                radio.setSelected(true);
                radio.fire();
            }
        }
        cameraStatusLabel.setText("Applied preset: " + name);
        cameraStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
    }

    /**
     * Resolve the first acquisition profile whose modality matches the
     * given modality string. Used by Save/Load Preset to round-trip
     * through APPLYPR so the server's _illumination is primed for the
     * preset's modality before camera settings + illumination power
     * are written. Returns null if no matching profile exists.
     */
    @SuppressWarnings("unchecked")
    private String findFirstMatchingProfile(String modality) {
        try {
            var profiles = mgr.getConfigItem("acquisition_profiles");
            if (!(profiles instanceof java.util.Map<?, ?> profileMap) || profileMap.isEmpty()) return null;
            String modalityLower = modality.toLowerCase();
            for (var entry : profileMap.entrySet()) {
                if (!(entry.getValue() instanceof java.util.Map<?, ?> profileCfg)) continue;
                Object profModality = profileCfg.get("modality");
                if (profModality == null) continue;
                String profModStr = profModality.toString().toLowerCase();
                int prefix = Math.min(2, Math.min(modalityLower.length(), profModStr.length()));
                if (modalityLower.regionMatches(0, profModStr, 0, prefix)) {
                    return String.valueOf(entry.getKey());
                }
            }
        } catch (Exception e) {
            logger.debug("findFirstMatchingProfile({}) failed: {}", modality, e.getMessage());
        }
        return null;
    }

    /** Save current camera state (exposure + gain + illumination) as a preset. */
    private void saveCurrentPreset(String modality) {
        Thread t = new Thread(
                () -> {
                    try {
                        MicroscopeController mc = MicroscopeController.getInstance();
                        if (mc == null || !mc.isConnected()) throw new Exception("Not connected");

                        var expResult = mc.getSocketClient().getExposures();
                        var gainResult = mc.getSocketClient().getGains();
                        var illumResult = mc.getSocketClient().getIllumination();

                        // Build string: profile|exp|gain|illum
                        // The leading profile name primes the server's
                        // _illumination via APPLYPR on Load -- without it,
                        // SETILLM hits whatever device the previous
                        // workflow last selected (often the wrong one,
                        // causing ERR_ILLM when the power value is out of
                        // range for that device).
                        StringBuilder sb = new StringBuilder();
                        String profileName = findFirstMatchingProfile(modality);
                        sb.append(profileName != null ? profileName : "");
                        sb.append("|");
                        sb.append(String.format("%.2f", expResult.unified()));
                        if (expResult.isPerChannel()) {
                            sb.append(String.format(
                                    ",%.2f,%.2f,%.2f", expResult.red(), expResult.green(), expResult.blue()));
                        }
                        sb.append("|");
                        sb.append(String.format(
                                "%.2f,%.2f,%.2f",
                                gainResult.unifiedGain(), gainResult.analogRed(), gainResult.analogBlue()));
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
                },
                "Preset-Save");
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

        Thread t = new Thread(
                () -> {
                    try {
                        MicroscopeController mc = MicroscopeController.getInstance();
                        if (mc == null || !mc.isConnected()) throw new Exception("Not connected");

                        // New format:    profile|exp[,r,g,b]|gain,aR,aB|illum
                        // Legacy format: exp[,r,g,b]|gain,aR,aB|illum
                        // Detect by checking whether the first segment
                        // parses as a float (legacy) or a profile name (new).
                        String[] parts = presetStr.split("\\|");
                        if (parts.length < 2) throw new Exception("Invalid preset format");

                        String savedProfile = null;
                        int expIdx;
                        boolean isLegacy;
                        try {
                            Float.parseFloat(parts[0].split(",")[0]);
                            isLegacy = true;
                        } catch (NumberFormatException nfe) {
                            isLegacy = false;
                        }
                        if (isLegacy) {
                            expIdx = 0;
                        } else {
                            savedProfile = parts[0].isBlank() ? null : parts[0];
                            expIdx = 1;
                        }
                        if (parts.length < expIdx + 2) throw new Exception("Invalid preset format");

                        // Apply the modality's profile FIRST so the server's
                        // _illumination is primed for the right device.
                        // Without this, SETILLM lands on whatever was active
                        // last (often a different modality's source) and
                        // returns ERR_ILLM when the power is out of range.
                        if (savedProfile == null) {
                            savedProfile = findFirstMatchingProfile(modality);
                        }
                        final String profileToApply = savedProfile;
                        if (profileToApply != null) {
                            try {
                                mc.withLiveModeHandling(
                                        () -> mc.getSocketClient().applyProfile(profileToApply));
                            } catch (Exception apEx) {
                                logger.warn(
                                        "applyProfile({}) before preset load failed: {}",
                                        profileToApply,
                                        apEx.getMessage());
                            }
                        }

                        // Parse exposures
                        String[] expParts = parts[expIdx].split(",");
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
                        String[] gainParts = parts[expIdx + 1].split(",");
                        float[] gains = new float[] {
                            Float.parseFloat(gainParts[0]),
                            gainParts.length >= 2 ? Float.parseFloat(gainParts[1]) : 1.0f,
                            gainParts.length >= 3 ? Float.parseFloat(gainParts[2]) : 1.0f
                        };

                        // Apply atomically via SETCAM
                        mc.withLiveModeHandling(
                                () -> mc.getSocketClient().setCameraSettings(individual, exposures, gains));

                        // Apply illumination if present
                        if (parts.length > expIdx + 2) {
                            float illumPower = Float.parseFloat(parts[expIdx + 2]);
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
                },
                "Preset-Load");
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
                        controller.withLiveModeHandling(
                                () -> controller.getSocketClient().setCameraSettings(individual, exposures, gains));
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

        // Initialize centroid button state and re-evaluate when image changes.
        // Image change also re-attaches the QuPath selection listener so the
        // "legal selection" gate tracks the new image's hierarchy.
        initializeCentroidButton();
        attachCentroidSelectionListener();
        QuPathGUI guiRef = QuPathGUI.getInstance();
        if (guiRef != null && guiRef.getViewer() != null) {
            guiRef.getViewer()
                    .imageDataProperty()
                    .addListener((obs, oldData, newData) -> Platform.runLater(() -> {
                        initializeCentroidButton();
                        attachCentroidSelectionListener();
                    }));
        }

        // Re-evaluate alignment availability whenever a workflow (e.g.
        // MicroscopeAlignmentWorkflow) installs a new pixel->stage transform.
        // Without this, running alignment leaves the Go to Centroid button stuck
        // on its previous "No alignments available" state until the image is
        // re-opened, because initializeCentroidButton only fires on imageData
        // change.
        MicroscopeController.getInstance()
                .currentTransformProperty()
                .addListener((obs, oldT, newT) -> Platform.runLater(this::initializeCentroidButton));

        // ---- Live View gating: bind movement-control disable state to !liveActive ----
        BooleanBinding moveDisabled = internalLiveActive.not();
        upBtn.disableProperty().bind(moveDisabled);
        downBtn.disableProperty().bind(moveDisabled);
        leftBtn.disableProperty().bind(moveDisabled);
        rightBtn.disableProperty().bind(moveDisabled);
        upBtn2x.disableProperty().bind(moveDisabled);
        downBtn2x.disableProperty().bind(moveDisabled);
        leftBtn2x.disableProperty().bind(moveDisabled);
        rightBtn2x.disableProperty().bind(moveDisabled);
        joystick.disableProperty().bind(moveDisabled);
        // Go-to-Centroid stays disabled when movement is gated OR no alignment
        // is available OR no legal annotation is selected. The three pieces
        // compose so each gate updates independently.
        goToCentroidBtn
                .disableProperty()
                .bind(moveDisabled.or(centroidUnavailable).or(selectionNotLegal));
        movementDisabledBanner.visibleProperty().bind(moveDisabled);
        movementDisabledBanner.managedProperty().bind(moveDisabled);

        // Navigate tab title turns orange when Live View is off so the gated
        // state is visible from any tab. Tab.setStyle is the cheapest way to
        // alter the rendered header without a graphic that would push the text
        // off-center.
        Runnable applyTabStyle = () -> {
            if (internalLiveActive.get()) {
                navigateTab.setStyle("");
            } else {
                navigateTab.setStyle("-fx-text-base-color: #b35900; -fx-font-weight: bold;");
            }
        };
        applyTabStyle.run();
        internalLiveActive.addListener((obs, oldV, newV) -> applyTabStyle.run());
    }

    /**
     * Attach (or re-attach on image change) a selection listener that drives
     * {@link #selectionNotLegal}. A "legal" selection is a PathObject with a
     * non-null ROI -- we need the ROI for {@code getCentroidX/Y}.
     */
    private void attachCentroidSelectionListener() {
        QuPathGUI gui = QuPathGUI.getInstance();
        qupath.lib.objects.hierarchy.PathObjectHierarchy h = (gui == null || gui.getImageData() == null)
                ? null
                : gui.getImageData().getHierarchy();
        if (h == attachedHierarchy) {
            evaluateLegalSelection();
            return;
        }
        if (attachedHierarchy != null && centroidSelectionListener != null) {
            attachedHierarchy.getSelectionModel().removePathObjectSelectionListener(centroidSelectionListener);
        }
        attachedHierarchy = h;
        if (h == null) {
            selectionNotLegal.set(true);
            return;
        }
        if (centroidSelectionListener == null) {
            centroidSelectionListener = (selected, prev, all) -> Platform.runLater(this::evaluateLegalSelection);
        }
        h.getSelectionModel().addPathObjectSelectionListener(centroidSelectionListener);
        evaluateLegalSelection();
    }

    private void evaluateLegalSelection() {
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui == null || gui.getImageData() == null) {
            selectionNotLegal.set(true);
            return;
        }
        PathObject sel = gui.getImageData().getHierarchy().getSelectionModel().getSelectedObject();
        selectionNotLegal.set(sel == null || sel.getROI() == null);
    }

    /**
     * Mirror an external live-active property (e.g. {@link LiveViewerWindow})
     * into this panel so its movement-control bindings and the focus-trace
     * reset logic respond reactively.
     */
    public void setLiveActiveProperty(ReadOnlyBooleanProperty external) {
        this.liveActiveProperty = external;
        if (external == null) {
            internalLiveActive.set(true);
            return;
        }
        internalLiveActive.set(external.get());
        external.addListener((obs, oldV, newV) -> internalLiveActive.set(newV != null && newV));
    }

    /**
     * Feed a target Z into the existing scroll-worker stream so the Z bars and
     * any other UI surface can drive the stage with the same continuous-ramp
     * semantics as the mouse wheel. Idempotent end-of-gesture handling is
     * provided by {@link #zScrollWorkerLoop}.
     */
    void streamZTo(double target) {
        if (isAcquisitionBlocked()) return;
        if (!internalLiveActive.get()) return;
        if (!mgr.isWithinStageBounds(target)) {
            Platform.runLater(() -> zStatus.setText("Z move out of bounds"));
            return;
        }
        zGestureTarget = target;
        lastScrollEventMs = System.currentTimeMillis();
        zScrollInFlight = true;
        Platform.runLater(() -> {
            zField.setText(String.format("%.2f", target));
            zStatus.setText("Moving...");
        });
        boolean startWorker;
        synchronized (zWorkerMutex) {
            startWorker = !zWorkerRunning;
            if (startWorker) zWorkerRunning = true;
        }
        if (startWorker) {
            Thread worker = new Thread(this::zScrollWorkerLoop, "StageControl-ZScroll");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * Push the current polled Z onto the AF history ring buffer as a manual
     * tic. Wired into the Mark Z button on the {@link ZBarPanel}; intentionally
     * stays enabled when Live View is off because it does not move the stage.
     */
    void markZ() {
        Thread t = new Thread(
                () -> {
                    try {
                        double z = MicroscopeController.getInstance().getStageZFast();
                        AfHistoryService.add(z);
                    } catch (Exception ex) {
                        logger.warn("Mark Z failed: {}", ex.getMessage());
                    }
                },
                "StageControl-MarkZ");
        t.setDaemon(true);
        t.start();
    }

    /** Accessor used by LiveViewerWindow to push focus samples into the bar trace. */
    public ZBarPanel getZBarPanel() {
        return zBarPanel;
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
                checkFocusTraceXyReset(newVal, current[1]);
            }
            case StagePositionManager.PROP_POS_Y -> {
                yField.setText(String.format("%.2f", newVal));
                // Update joystick tracking
                double[] current = joystickPosition.get();
                joystickPosition.set(new double[] {current[0], newVal});
                checkFocusTraceXyReset(current[0], newVal);
            }
            case StagePositionManager.PROP_POS_Z -> {
                // Suppress poller updates while a scroll gesture is in-flight
                // to prevent the stale hardware position from rolling back
                // the user's accumulated scroll target.
                if (!zScrollInFlight) {
                    zField.setText(String.format("%.2f", newVal));
                }
                if (zBarPanel != null) {
                    zBarPanel.setCurrentZ(newVal);
                }
            }
            case StagePositionManager.PROP_POS_R -> rField.setText(String.format("%.2f", newVal));
        }
    }

    /**
     * Clear the Z-bar focus trace if the stage has moved more than
     * {@link #FOCUS_TRACE_XY_RESET_UM} from the last reference point. The trace
     * is location-specific; a meaningful XY move invalidates the prior curve.
     */
    private void checkFocusTraceXyReset(double x, double y) {
        if (zBarPanel == null) return;
        if (Double.isNaN(lastFocusXyX) || Double.isNaN(lastFocusXyY)) {
            lastFocusXyX = x;
            lastFocusXyY = y;
            return;
        }
        double dx = x - lastFocusXyX;
        double dy = y - lastFocusXyY;
        if (Math.sqrt(dx * dx + dy * dy) > FOCUS_TRACE_XY_RESET_UM) {
            zBarPanel.getFocusTrace().clear();
            lastFocusXyX = x;
            lastFocusXyY = y;
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

    /**
     * Returns true when Live View is off -- the policy is that the user must be
     * able to see the stage before any movement is dispatched. The Navigate
     * tab's banner and orange tab-header dot signal this state visibly; this
     * gate is defense-in-depth for entry points that don't route through a
     * disabled button (typed-target Enter, scroll wheel, keyboard shortcuts).
     */
    private boolean isMovementGatedByLiveView() {
        if (internalLiveActive.get()) return false;
        Platform.runLater(() -> zStatus.setText("Live View is Off"));
        return true;
    }

    private void handleMoveXY() {
        if (isAcquisitionBlocked()) return;
        if (isMovementGatedByLiveView()) return;
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
        if (isMovementGatedByLiveView()) return;
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
        if (isMovementGatedByLiveView()) return;
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

    /**
     * Streams the mouse wheel into a live Z target so the stage tracks the
     * wheel like a physical focus knob. Each {@code ScrollEvent} accumulates
     * into {@link #zGestureTarget}; a single persistent worker
     * ({@link #zScrollWorkerLoop}) continuously re-targets the stage toward it
     * with non-blocking moves, so the stage ramps smoothly through every
     * intermediate focus plane instead of teleporting once the gesture ends.
     *
     * <p>Linear mapping: each mouse-wheel notch (~40 deltaY units) adds exactly
     * one configured Z step. Trackpads and high-resolution wheels produce
     * fractional notches for finer control; a fast swipe simply delivers more
     * notches and so covers more distance.
     *
     * <p>A gesture begins when no scroll event has arrived for
     * {@link #GESTURE_IDLE_MS}; the target is then re-seeded from the displayed
     * Z so accumulation starts from the true position.
     */
    private void handleZScroll(ScrollEvent event, TextField zStepField) {
        if (isAcquisitionBlocked()) {
            event.consume();
            return;
        }
        if (isMovementGatedByLiveView()) {
            event.consume();
            return;
        }
        try {
            double step = Double.parseDouble(zStepField.getText().replace(",", ""));
            double notches = event.getDeltaY() / SCROLL_UNITS_PER_NOTCH;
            double movement = step * notches;

            long now = System.currentTimeMillis();
            boolean newGesture = Double.isNaN(zGestureTarget) || (now - lastScrollEventMs) > GESTURE_IDLE_MS;
            double base = newGesture ? Double.parseDouble(zField.getText().replace(",", "")) : zGestureTarget;
            double candidate = base + movement;

            if (!mgr.isWithinStageBounds(candidate)) {
                zStatus.setText("Z move out of bounds");
                event.consume();
                return;
            }

            zGestureTarget = candidate;
            lastScrollEventMs = now;

            // Optimistic UI update; the worker takes over the field with live
            // readings once it starts. zScrollInFlight suppresses the 500ms
            // position poller so it cannot roll the value back mid-gesture.
            zField.setText(String.format("%.2f", candidate));
            zStatus.setText("Moving...");
            zScrollInFlight = true;

            boolean startWorker;
            synchronized (zWorkerMutex) {
                startWorker = !zWorkerRunning;
                if (startWorker) {
                    zWorkerRunning = true;
                }
            }
            if (startWorker) {
                Thread worker = new Thread(this::zScrollWorkerLoop, "StageControl-ZScroll");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Exception ex) {
            logger.warn("Scroll Z movement failed: {}", ex.getMessage());
            zStatus.setText("Z scroll failed");
        }
        event.consume();
    }

    /**
     * Body of the single persistent Z scroll worker. While a gesture is active
     * it re-dispatches a non-blocking move toward {@link #zGestureTarget}
     * (rate-limited to {@link #MIN_DISPATCH_INTERVAL_MS}) and polls the true Z
     * position into the field so the user sees focus track the wheel. Because
     * each non-blocking move simply re-targets the in-flight ramp, the stage
     * passes continuously through intermediate planes and reverses promptly.
     *
     * <p>Exactly one worker runs per gesture ({@link #zWorkerRunning} guard):
     * this, the {@link #MIN_DISPATCH_INTERVAL_MS} floor, and the absence of any
     * server-side {@code wait_z} mean a fast swipe cannot flood the server --
     * the failure mode behind the 2026-04-15 Z-scroll storm.
     *
     * <p>The end-of-gesture check is re-evaluated under {@link #zWorkerMutex}
     * so a scroll event racing with worker shutdown cannot be lost: either the
     * worker sees the fresh event and stays alive, or it has already cleared
     * {@code zWorkerRunning} and {@link #handleZScroll} starts a fresh worker.
     */
    private void zScrollWorkerLoop() {
        double lastDispatched = Double.NaN;
        long lastDispatchMs = 0;
        try {
            while (true) {
                double target = zGestureTarget;
                long now = System.currentTimeMillis();

                if (!Double.isNaN(target)
                        && target != lastDispatched
                        && (now - lastDispatchMs) >= MIN_DISPATCH_INTERVAL_MS) {
                    try {
                        MicroscopeController.getInstance().moveStageZNoWait(target);
                        lastDispatched = target;
                        lastDispatchMs = now;
                    } catch (Exception ex) {
                        logger.warn("Z scroll movement failed: {}", ex.getMessage());
                        Platform.runLater(() -> zStatus.setText("Z scroll failed"));
                    }
                }

                // Live position readback -- the user watches focus track here.
                try {
                    double actual = MicroscopeController.getInstance().getStageZFast();
                    Platform.runLater(() -> zField.setText(String.format("%.2f", actual)));
                } catch (Exception ex) {
                    logger.debug("Z scroll position poll failed: {}", ex.getMessage());
                }

                boolean exit = false;
                synchronized (zWorkerMutex) {
                    boolean idle = (System.currentTimeMillis() - lastScrollEventMs) > GESTURE_IDLE_MS;
                    double t = zGestureTarget;
                    boolean settled = Double.isNaN(t) || t == lastDispatched;
                    if (idle && settled) {
                        zWorkerRunning = false;
                        exit = true;
                    }
                }
                if (exit) {
                    break;
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    synchronized (zWorkerMutex) {
                        zWorkerRunning = false;
                    }
                    break;
                }
            }
        } finally {
            final double finalZ = lastDispatched;
            zGestureTarget = Double.NaN;
            Platform.runLater(() -> {
                zScrollInFlight = false;
                if (!Double.isNaN(finalZ)) {
                    zStatus.setText(String.format("Scrolled Z to %.2f", finalZ));
                }
            });
        }
    }

    private void handleArrowMove(int xDir, int yDir) {
        if (isAcquisitionBlocked()) return;
        if (isMovementGatedByLiveView()) return;
        try {
            double step = Double.parseDouble(xyStepField.getText().replace(",", ""));
            double currentX = Double.parseDouble(xField.getText().replace(",", ""));
            double currentY = Double.parseDouble(yField.getText().replace(",", ""));

            // Compute the MM command delta via StageImageTransform. The transform
            // expects a "screen pan" delta in screen-frame coordinates (positive
            // Y = down). The button wiring passes yDir=+1 for up, so we negate
            // it here to reach the screen convention.
            //
            // The "Sample Movement" checkbox inverts X on top of the transform
            // to reproduce the historical MicroManager default ("non-sample"
            // mode inverts X relative to pan intent).
            boolean sampleMode = sampleMovementCheckbox.isSelected();
            double screenDx = step * xDir;
            double screenDy = step * (-yDir);
            double[] mmDelta =
                    qupath.ext.qpsc.utilities.StageImageTransform.current().screenPanDeltaToMmDelta(screenDx, screenDy);
            if (!sampleMode) {
                mmDelta[0] = -mmDelta[0];
            }
            double newX = currentX + mmDelta[0];
            double newY = currentY + mmDelta[1];

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
        if (isMovementGatedByLiveView()) return;
        try {
            double[] current = joystickPosition.get();
            double currentX = current[0];
            double currentY = current[1];

            // The joystick already produces a delta in the screen-frame
            // convention (deltaY < 0 when knob is pulled upward, because
            // knobOffsetY uses mouse coordinates). Feed it directly into
            // StageImageTransform. Sample mode inverts X on top.
            boolean sampleMode = sampleMovementMode.get();
            double[] mmDelta =
                    qupath.ext.qpsc.utilities.StageImageTransform.current().screenPanDeltaToMmDelta(deltaX, deltaY);
            if (!sampleMode) {
                mmDelta[0] = -mmDelta[0];
            }
            double targetX = currentX + mmDelta[0];
            double targetY = currentY + mmDelta[1];

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
        // Reset state so this method is safe to re-call on image change.
        // disableProperty is bound to (moveDisabled OR centroidUnavailable);
        // drive availability through the property instead of calling setDisable.
        centroidUnavailable.set(false);
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
            // BoundingBox sub-image path: either the per-entry stage-bounds
            // metadata (2026-05-15+) or the sub-image alignment JSON in
            // alignmentFiles/derived/ (older entries) is enough for
            // handleGoToCentroid to navigate, even though loadSlideAlignment
            // above doesn't reach those. Check both so the button stays
            // enabled for bounded acquisitions.
            boolean hasBoundedSubImageAlignment = false;
            if (gui != null && gui.getProject() != null && gui.getImageData() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                    ProjectImageEntry<BufferedImage> entry =
                            QPProjectFunctions.findImageInProject(project, gui.getImageData());
                    if (entry != null
                            && qupath.ext.qpsc.utilities.ImageMetadataManager.getBoundingBoxStageBounds(entry)
                                    != null) {
                        hasBoundedSubImageAlignment = true;
                    }
                    if (!hasBoundedSubImageAlignment) {
                        String subName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                        if (subName != null && !subName.isEmpty()) {
                            AffineTransform derived = AffineTransformManager.loadDerivedAlignment(project, subName);
                            if (derived != null) hasBoundedSubImageAlignment = true;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not check bounded sub-image alignment: {}", e.getMessage());
                }
            }

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

            if (hasBoundedSubImageAlignment) {
                centroidStatus.setText("BoundingBox alignment available");
            } else if (hasXYOffset) {
                centroidStatus.setText("Sub-image: offset-based navigation");
            } else {
                centroidUnavailable.set(true);

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
        if (isMovementGatedByLiveView()) return;
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

        // First: per-entry bounding-box metadata path (most reliable for
        // BoundingBox acquisitions). The stage bounds + stitcher flips are
        // stamped on the entry by StitchingHelper.autoRegisterBoundsTransformIfAvailable
        // and travel WITH the entry -- no JSON lookup, no cross-scope guard,
        // no directory restructure to trip over. Rebuilt 2026-05-15 after the
        // alignment-lookup restructure (commit 18a800d) broke the JSON path
        // for bounded acquisitions (cross-scope guard rejecting legacy files /
        // derived directory vs flat directory confusion).
        if (gui.getProject() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
                if (openEntry != null) {
                    double[] bounds =
                            qupath.ext.qpsc.utilities.ImageMetadataManager.getBoundingBoxStageBounds(openEntry);
                    if (bounds != null) {
                        boolean[] flips = qupath.ext.qpsc.utilities.ImageMetadataManager.getStitcherFlips(openEntry);
                        int widthPx = gui.getImageData().getServer().getWidth();
                        int heightPx = gui.getImageData().getServer().getHeight();
                        double imgX1 = bounds[0];
                        double imgY1 = bounds[1];
                        double imgX2 = bounds[2];
                        double imgY2 = bounds[3];
                        boolean flipX = flips[0];
                        boolean flipY = flips[1];
                        double originX = flipX ? imgX2 : imgX1;
                        double originY = flipY ? imgY2 : imgY1;
                        double scaleX = (flipX ? -1 : 1) * (imgX2 - imgX1) / widthPx;
                        double scaleY = (flipY ? -1 : 1) * (imgY2 - imgY1) / heightPx;
                        double stageX = originX + scaleX * centroidX;
                        double stageY = originY + scaleY * centroidY;
                        logger.info(
                                "BoundingBox centroid via entry metadata: pixel ({}, {}) -> stage ({}, {}) "
                                        + "[bounds=({},{})->({},{}) flip=({},{})]",
                                String.format("%.1f", centroidX),
                                String.format("%.1f", centroidY),
                                String.format("%.1f", stageX),
                                String.format("%.1f", stageY),
                                String.format("%.1f", imgX1),
                                String.format("%.1f", imgY1),
                                String.format("%.1f", imgX2),
                                String.format("%.1f", imgY2),
                                flipX,
                                flipY);
                        moveToStagePosition(stageX, stageY);
                        return;
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not use bounding-box entry-metadata path: {}", e.getMessage());
            }
        }

        // Fallback: sub-image alignment JSON. Used for entries that pre-date
        // the 2026-05-15 per-entry-metadata stamp, or for sub-images other
        // than BoundingBox (e.g. annotation-based sub-acquisitions whose
        // alignment is auto-registered from a parent macro). Sub-frame
        // transforms live in alignmentFiles/derived/ as of Layer 3 of the
        // 2026-05-11 lookup restructure; loadDerivedAlignment also falls
        // back to the flat directory for backward compatibility.
        if (gui.getProject() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                String subImageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                if (subImageName != null && !subImageName.isEmpty()) {
                    AffineTransform subAlignment = AffineTransformManager.loadDerivedAlignment(project, subImageName);
                    if (subAlignment != null) {
                        // ====================================================
                        // BEGIN one-shot metadata backfill (introduced 2026-05-15).
                        //
                        // WHY THIS EXISTS:
                        //   Bounded acquisitions made BEFORE 2026-05-15 didn't
                        //   stamp STAGE_BOUNDS_*_UM / STITCHER_FLIP_* on the
                        //   project entry -- that stamp only ships from the
                        //   accompanying StitchingHelper change forward. Those
                        //   older entries DO have a working sub-image alignment
                        //   JSON (otherwise this branch wouldn't have fired),
                        //   so we can reconstruct the per-entry metadata from
                        //   the transform geometry. Doing it here means the
                        //   first centroid click on an old entry heals it, and
                        //   subsequent clicks take the primary metadata path
                        //   above without touching the JSON-lookup chain.
                        //
                        // HOW IT WORKS:
                        //   autoRegisterBoundsTransformIfAvailable builds the
                        //   transform as:
                        //     scaleX = (flipX ? -1 : 1) * (imgX2 - imgX1) / w
                        //     scaleY = (flipY ? -1 : 1) * (imgY2 - imgY1) / h
                        //     originX = flipX ? imgX2 : imgX1
                        //     originY = flipY ? imgY2 : imgY1
                        //   So pixel(0) -> origin and pixel(w,h) -> opposite
                        //   corner. We recover [imgX1, imgY1, imgX2, imgY2]
                        //   as min/max of those two stage points, and
                        //   flipX/flipY from the sign of scaleX/scaleY. The
                        //   recovered numbers are byte-identical to what the
                        //   save site wrote, so the future metadata-path math
                        //   matches the JSON-path math exactly.
                        //
                        // HOW TO REMOVE LATER:
                        //   Delete this block (between BEGIN and END markers).
                        //   No other code reads from or depends on it.
                        //   Safe to strip once enough time has passed that
                        //   pre-2026-05-15 bounded entries are no longer in
                        //   active use, or once the older entries have been
                        //   touched at least once and self-healed.
                        // ====================================================
                        try {
                            ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
                            if (openEntry != null
                                    && qupath.ext.qpsc.utilities.ImageMetadataManager.getBoundingBoxStageBounds(
                                                    openEntry)
                                            == null
                                    && Math.abs(subAlignment.getShearX()) < 1e-9
                                    && Math.abs(subAlignment.getShearY()) < 1e-9) {
                                int wPx = gui.getImageData().getServer().getWidth();
                                int hPx = gui.getImageData().getServer().getHeight();
                                double scaleX = subAlignment.getScaleX();
                                double scaleY = subAlignment.getScaleY();
                                double originX = subAlignment.getTranslateX();
                                double originY = subAlignment.getTranslateY();
                                double cornerX = originX + scaleX * wPx;
                                double cornerY = originY + scaleY * hPx;
                                double imgX1 = Math.min(originX, cornerX);
                                double imgY1 = Math.min(originY, cornerY);
                                double imgX2 = Math.max(originX, cornerX);
                                double imgY2 = Math.max(originY, cornerY);
                                boolean flipXRecovered = scaleX < 0;
                                boolean flipYRecovered = scaleY < 0;
                                qupath.ext.qpsc.utilities.ImageMetadataManager.setBoundingBoxStageBounds(
                                        openEntry, imgX1, imgY1, imgX2, imgY2, flipXRecovered, flipYRecovered);
                                project.syncChanges();
                                logger.info(
                                        "Backfilled bounding-box metadata on legacy entry '{}': "
                                                + "bounds=({},{}) -> ({},{}) flip=({},{}) "
                                                + "[recovered from alignment JSON; see 2026-05-15 regression report]",
                                        openEntry.getImageName(),
                                        String.format("%.1f", imgX1),
                                        String.format("%.1f", imgY1),
                                        String.format("%.1f", imgX2),
                                        String.format("%.1f", imgY2),
                                        flipXRecovered,
                                        flipYRecovered);
                            }
                        } catch (Exception backfillEx) {
                            logger.debug(
                                    "Could not backfill bounding-box metadata from alignment JSON: {}",
                                    backfillEx.getMessage());
                        }
                        // ====================================================
                        // END one-shot metadata backfill.
                        // ====================================================

                        double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                                new double[] {centroidX, centroidY}, subAlignment);
                        logger.info(
                                "Sub-image centroid via alignment: pixel ({}, {}) " + "-> stage ({}, {}) [image={}]",
                                String.format("%.1f", centroidX),
                                String.format("%.1f", centroidY),
                                String.format("%.1f", stageCoords[0]),
                                String.format("%.1f", stageCoords[1]),
                                subImageName);
                        moveToStagePosition(stageCoords[0], stageCoords[1]);
                        return;
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not use sub-image alignment path: {}", e.getMessage());
            }
        }

        // Fall through to alignment transform path. Load the per-slide
        // alignment with its alignment-time flip frame so we can bake
        // any required flip into the transform here -- mirroring
        // AlignmentHelper.checkForSlideAlignment so all callers consume
        // unflipped-base pixel coords directly.
        AffineTransform transform = null;
        if (gui.getProject() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                if (imageName != null && !imageName.isEmpty()) {
                    AffineTransformManager.SlideAlignmentResult withFrame =
                            AffineTransformManager.loadSlideAlignmentWithFrame(project, imageName);
                    AffineTransform raw = withFrame != null ? withFrame.getTransform() : null;
                    Boolean alignFlipX = withFrame != null ? withFrame.getFlipMacroX() : null;
                    Boolean alignFlipY = withFrame != null ? withFrame.getFlipMacroY() : null;

                    if (raw == null) {
                        // Fall back to the legacy raw loader for older JSONs that
                        // pre-date the with-frame format; for those the saved
                        // transform is in unflipped-base frame already (Manual /
                        // ExistingAlignment paths produced that).
                        raw = AffineTransformManager.loadSlideAlignment(project, imageName);
                    }

                    // Determine whether the open entry is the unflipped base or
                    // the flipped sibling. If unflipped base AND the alignment
                    // was captured in flipped frame, bake the flip so we can
                    // apply the centroid in unflipped-base pixel coords.
                    ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
                    boolean entryIsFlippedSibling = openEntry != null
                            && qupath.ext.qpsc.utilities.ImageFlipHelper.isFlippedSiblingName(openEntry.getImageName());

                    if (raw != null
                            && alignFlipX != null
                            && alignFlipY != null
                            && (alignFlipX || alignFlipY)
                            && !entryIsFlippedSibling) {
                        int w = gui.getImageData().getServer().getWidth();
                        int h = gui.getImageData().getServer().getHeight();
                        AffineTransform flip = qupath.ext.qpsc.controller.ForwardPropagationWorkflow.createFlip(
                                alignFlipX, alignFlipY, w, h);
                        AffineTransform composed = new AffineTransform(raw);
                        composed.concatenate(flip);
                        transform = composed;
                        logger.debug(
                                "Move-to-Centroid: baked alignment flip ({}, {}) into transform for unflipped base",
                                alignFlipX,
                                alignFlipY);
                    } else {
                        transform = raw;
                    }

                    if (transform != null) {
                        MicroscopeController.getInstance().setCurrentTransform(transform);
                    }
                }
            } catch (Exception ex) {
                logger.warn("Failed to load slide-specific alignment on click: {}", ex.getMessage());
            }
        }

        // Last-resort: use the in-session current transform if no per-slide JSON.
        if (transform == null) {
            transform = MicroscopeController.getInstance().getCurrentTransform();
        }

        if (transform == null) {
            centroidStatus.setText("No alignment available");
            UIFunctions.notifyUserOfError(
                    "No alignment is available. Please perform alignment first.", "Go to Centroid");
            return;
        }

        try {
            double[] qpCoords = {centroidX, centroidY};
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
