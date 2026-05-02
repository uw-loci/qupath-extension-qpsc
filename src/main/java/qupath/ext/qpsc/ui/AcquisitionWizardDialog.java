package qupath.ext.qpsc.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.QPScopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.CalibrationChecker.Status;
import qupath.ext.qpsc.ui.CalibrationChecker.StepStatus;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.QuPathStyleManager;

/**
 * Acquisition Wizard - a checklist-style dashboard that guides users through
 * the full setup-to-acquisition pipeline.
 *
 * <p>The wizard shows all prerequisite steps with live status indicators.
 * Users can run calibration tools, check alignment, and launch the chosen
 * acquisition workflow from a single persistent window.
 *
 * <p>The dialog is non-modal and always-on-top so users can interact with
 * QuPath and other dialogs while the wizard remains accessible. A collapse
 * button shrinks it to a small floating pill that can be dragged out of the
 * way, then clicked to expand again.
 */
public class AcquisitionWizardDialog {

    private static final Logger logger = LoggerFactory.getLogger(AcquisitionWizardDialog.class);

    private static final String BLUE = "#2d5aa0";
    private static final String BLUE_HOVER = "#3d6ab0";
    private static final double EXPANDED_WIDTH = 520;

    /** Returns true when QuPath is using a dark color scheme. */
    private static boolean isDark() {
        return QuPathStyleManager.getStyleColorScheme() == ColorScheme.DARK;
    }

    // Theme-adaptive colors queried at build time
    private static String bgMain()      { return isDark() ? "#2b2b2b" : "white"; }
    private static String bgSection()   { return isDark() ? "#333333" : "#f5f5f5"; }
    private static String bgRow()       { return isDark() ? "#363636" : "white"; }
    private static String bgIcon()      { return isDark() ? "#3a4a60" : "#e8eef7"; }
    private static String borderColor() { return isDark() ? "#555"    : "#e0e0e0"; }
    private static String borderOuter() { return isDark() ? "#555"    : "#bbb"; }
    private static String borderSection() { return isDark() ? "#555"  : "#ddd"; }
    private static String textPrimary() { return isDark() ? "#ddd"    : "black"; }
    private static String textSecondary() { return isDark() ? "#aaa"  : "#666"; }
    private static String textMuted()   { return isDark() ? "#888"    : "#999"; }
    private static String textSectionHeader() { return isDark() ? "#aaa" : "#888"; }

    private static Stage wizardStage;

    // Root container that holds both expanded and collapsed views
    private StackPane rootPane;
    private VBox expandedContent;
    private HBox collapsedContent;
    private boolean collapsed;

    // Saved position/size for expand/collapse transitions
    private double expandedX, expandedY;

    // Drag support
    private double dragOffsetX, dragOffsetY;

    // Hardware selection controls
    private ComboBox<String> modalityCombo;
    private ComboBox<String> objectiveCombo;
    private ComboBox<String> detectorCombo;

    // Step rows (for status updates)
    private final List<StepRow> stepRows = new ArrayList<>();

    // Step indices for targeted refresh
    private static final int STEP_CONNECTION = 0;
    private static final int STEP_WHITE_BALANCE = 1;
    private static final int STEP_BACKGROUND = 2;
    private static final int STEP_ALIGNMENT = 3;

    // Collapsed pill status dots (mirror the step row dots)
    private final Circle[] pillDots = new Circle[4];

    // Acquire buttons
    private Button boundedButton;
    private Button existingImageButton;

    // Autofocus disable checkbox
    private CheckBox disableAutofocusCheckBox;

    /**
     * Shows the wizard dialog. If already open, brings it to front and expands.
     */
    public static void show() {
        if (wizardStage != null && wizardStage.isShowing()) {
            wizardStage.toFront();
            wizardStage.requestFocus();
            return;
        }

        new AcquisitionWizardDialog().createAndShow();
    }

    private void createAndShow() {
        wizardStage = new Stage();
        wizardStage.initStyle(StageStyle.TRANSPARENT);
        wizardStage.setAlwaysOnTop(true);
        wizardStage.setTitle("Acquisition Wizard");

        // Build both views
        expandedContent = buildExpandedContent();
        collapsedContent = buildCollapsedContent();
        collapsedContent.setVisible(false);
        collapsedContent.setManaged(false);

        rootPane = new StackPane(expandedContent, collapsedContent);
        rootPane.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(rootPane);
        scene.setFill(Color.TRANSPARENT);
        wizardStage.setScene(scene);

        // Position near top-right of QuPath window
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            Stage parent = gui.getStage();
            wizardStage.setX(parent.getX() + parent.getWidth() - EXPANDED_WIDTH - 20);
            wizardStage.setY(parent.getY() + 60);
        }

        wizardStage.show();

        // Auto-open companion windows (Live Viewer and Stage Map) if not already open.
        // Uses the same code paths as the menu items so Dialog Manager is triggered.
        try {
            qupath.ext.qpsc.controller.QPScopeController.getInstance().startWorkflow("liveViewer");
            logger.info("Auto-opened Live Viewer from Acquisition Wizard");
        } catch (Exception e) {
            logger.debug("Could not auto-open Live Viewer: {}", e.getMessage());
        }
        qupath.ext.qpsc.ui.stagemap.StageMapWindow.show();
        logger.info("Auto-opened Stage Map from Acquisition Wizard");

        // Auto-collapse when the wizard loses focus (user clicks on QuPath
        // or another window), and auto-refresh + expand when it regains focus.
        wizardStage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                expand();
                refreshAllStatuses();
            } else {
                collapse();
            }
        });

        // Initial status refresh
        refreshAllStatuses();
    }

    // ======================================================================
    // Collapse / Expand
    // ======================================================================

    private void collapse() {
        if (collapsed) return;
        collapsed = true;

        // Save expanded position
        expandedX = wizardStage.getX();
        expandedY = wizardStage.getY();

        expandedContent.setVisible(false);
        expandedContent.setManaged(false);
        collapsedContent.setVisible(true);
        collapsedContent.setManaged(true);

        wizardStage.sizeToScene();
    }

    private void expand() {
        if (!collapsed) return;
        collapsed = false;

        collapsedContent.setVisible(false);
        collapsedContent.setManaged(false);
        expandedContent.setVisible(true);
        expandedContent.setManaged(true);

        wizardStage.sizeToScene();

        // Restore position
        wizardStage.setX(expandedX);
        wizardStage.setY(expandedY);
    }

    // ======================================================================
    // Expanded content (full wizard)
    // ======================================================================

    private VBox buildExpandedContent() {
        VBox root = new VBox(0);
        root.setPrefWidth(EXPANDED_WIDTH);
        root.setStyle("-fx-background-color: " + bgMain() + "; -fx-background-radius: 8; "
                + "-fx-border-color: " + borderOuter() + "; -fx-border-radius: 8; -fx-border-width: 1; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 8, 0, 0, 2);");

        // -- Draggable header with window controls --
        root.getChildren().add(createHeader());

        // -- Hardware selection --
        root.getChildren().add(createHardwareSection());

        // -- Separator --
        root.getChildren().add(createSectionSeparator("Checklist"));

        // -- Step rows --
        VBox stepsBox = new VBox(2);
        stepsBox.setPadding(new Insets(4, 16, 8, 16));

        stepsBox.getChildren()
                .add(createStepRow(
                        STEP_CONNECTION,
                        createConnectionIcon(),
                        "Server Connection",
                        "Connect to microscope control server",
                        "Connect",
                        this::onConnect));

        stepsBox.getChildren()
                .add(createStepRow(
                        STEP_WHITE_BALANCE,
                        createCalibrationIcon(),
                        "White Balance",
                        "Calibrate per-channel camera exposures",
                        "Calibrate...",
                        this::onWhiteBalance));

        stepsBox.getChildren()
                .add(createStepRow(
                        STEP_BACKGROUND,
                        createGridIcon(),
                        "Background Correction",
                        "Acquire flat-field correction images",
                        "Collect...",
                        this::onBackgroundCollection));

        stepsBox.getChildren()
                .add(createStepRow(
                        STEP_ALIGNMENT,
                        createAlignmentIcon(),
                        "Microscope Alignment",
                        "Align macro image to stage (for Existing Image workflow)",
                        "Align...",
                        this::onAlignment));

        // Reference-only tooltip on the alignment age indicator. Days-since
        // is the only signal we report; physical perturbations (bumping the
        // stage, swapping inserts, anything that changes pixel<->stage geometry)
        // invalidate alignment without the software being able to detect it.
        Tooltip alignmentAgeTooltip = new Tooltip(
                "Reference only -- this is the time since the last full microscope\n"
                        + "alignment. The software cannot detect physical changes that\n"
                        + "would break the alignment (bumping the stage or table,\n"
                        + "swapping inserts, changing optics, etc.). When in doubt,\n"
                        + "re-run Microscope Alignment.");
        alignmentAgeTooltip.setShowDelay(javafx.util.Duration.millis(250));
        alignmentAgeTooltip.setShowDuration(javafx.util.Duration.seconds(20));
        StepRow alignmentRow = stepRows.get(STEP_ALIGNMENT);
        Tooltip.install(alignmentRow.statusLabel, alignmentAgeTooltip);
        Tooltip.install(alignmentRow.statusDot, alignmentAgeTooltip);

        root.getChildren().add(stepsBox);

        // -- Autofocus override --
        root.getChildren().add(createAutofocusOverrideSection());

        // -- Separator --
        root.getChildren().add(createSectionSeparator("Start Acquisition"));

        // -- Acquire section --
        root.getChildren().add(createAcquireSection());

        // -- Bottom bar --
        root.getChildren().add(createBottomBar());

        return root;
    }

    // ======================================================================
    // Collapsed content (floating pill)
    // ======================================================================

    private HBox buildCollapsedContent() {
        HBox pill = new HBox(6);
        pill.setAlignment(Pos.CENTER);
        pill.setPadding(new Insets(6, 12, 6, 12));
        pill.setStyle("-fx-background-color: " + BLUE + "; -fx-background-radius: 18; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 6, 0, 0, 2);");
        pill.setCursor(Cursor.HAND);

        // Make pill draggable
        pill.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - wizardStage.getX();
            dragOffsetY = e.getScreenY() - wizardStage.getY();
        });
        pill.setOnMouseDragged(e -> {
            wizardStage.setX(e.getScreenX() - dragOffsetX);
            wizardStage.setY(e.getScreenY() - dragOffsetY);
        });

        // Microscope-style icon for the pill
        SVGPath icon = new SVGPath();
        icon.setContent("M7,1 L9,1 L9,4 L11,4 L11,6 L5,6 L5,4 L7,4 Z "
                + "M6,6 L10,6 L10,12 L6,12 Z M4,12 L12,12 L12,14 L4,14 Z");
        icon.setFill(Color.WHITE);
        icon.setScaleX(1.0);
        icon.setScaleY(1.0);

        Label titleLabel = new Label("Wizard");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: white;");

        // Mini status dots
        HBox dotsBox = new HBox(3);
        dotsBox.setAlignment(Pos.CENTER);
        for (int i = 0; i < 4; i++) {
            pillDots[i] = new Circle(4, Color.GRAY);
            dotsBox.getChildren().add(pillDots[i]);
        }

        // Expand button (chevron up)
        Label expandLabel = new Label("^");
        expandLabel.setStyle(
                "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: white; " + "-fx-padding: 0 0 0 4;");

        pill.getChildren().addAll(icon, titleLabel, dotsBox, expandLabel);

        // Click to expand
        pill.setOnMouseClicked(e -> {
            if (!e.isStillSincePress()) return; // ignore drag-end clicks
            expand();
        });

        // Hover effect
        pill.setOnMouseEntered(
                e -> pill.setStyle("-fx-background-color: " + BLUE_HOVER + "; -fx-background-radius: 18; "
                        + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 6, 0, 0, 2);"));
        pill.setOnMouseExited(e -> pill.setStyle("-fx-background-color: " + BLUE + "; -fx-background-radius: 18; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 6, 0, 0, 2);"));

        return pill;
    }

    // ======================================================================
    // Header (draggable title bar with window controls)
    // ======================================================================

    private VBox createHeader() {
        VBox header = new VBox(4);
        header.setPadding(new Insets(10, 12, 8, 16));
        header.setStyle("-fx-background-color: " + BLUE + "; " + "-fx-background-radius: 8 8 0 0;");

        // Top row: title + window control buttons
        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Acquisition Wizard");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        // Help button (opens documentation)
        Button helpBtn = DocumentationHelper.createHelpButton("acquisitionWizard");
        if (helpBtn != null) {
            helpBtn.setStyle("-fx-background-color: rgba(255,255,255,0.2); -fx-background-radius: 50%; "
                    + "-fx-text-fill: white; -fx-font-weight: bold; "
                    + "-fx-min-width: 22; -fx-min-height: 22; "
                    + "-fx-max-width: 22; -fx-max-height: 22; "
                    + "-fx-padding: 0; -fx-font-size: 11;");
        }

        // Minimize (collapse) button
        Button collapseBtn = createWindowButton("_", "Collapse to floating icon");
        collapseBtn.setOnAction(e -> collapse());

        // Close button
        Button closeBtn = createWindowButton("X", "Close wizard");
        closeBtn.setOnAction(e -> wizardStage.close());

        titleRow.getChildren().add(title);
        if (helpBtn != null) titleRow.getChildren().add(helpBtn);
        titleRow.getChildren().addAll(collapseBtn, closeBtn);

        Label subtitle = new Label("Follow the steps below to prepare and start an acquisition.");
        subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: #ccd9ee;");
        subtitle.setWrapText(true);

        header.getChildren().addAll(titleRow, subtitle);

        // Make header draggable
        header.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - wizardStage.getX();
            dragOffsetY = e.getScreenY() - wizardStage.getY();
        });
        header.setOnMouseDragged(e -> {
            wizardStage.setX(e.getScreenX() - dragOffsetX);
            wizardStage.setY(e.getScreenY() - dragOffsetY);
        });
        header.setCursor(Cursor.MOVE);

        return header;
    }

    private Button createWindowButton(String text, String tooltip) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; "
                + "-fx-font-size: 13px; -fx-font-weight: bold; "
                + "-fx-min-width: 28; -fx-min-height: 22; -fx-max-height: 22; "
                + "-fx-padding: 0 4 0 4; -fx-background-radius: 4;");
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: rgba(255,255,255,0.2); -fx-text-fill: white; "
                + "-fx-font-size: 13px; -fx-font-weight: bold; "
                + "-fx-min-width: 28; -fx-min-height: 22; -fx-max-height: 22; "
                + "-fx-padding: 0 4 0 4; -fx-background-radius: 4;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; "
                + "-fx-font-size: 13px; -fx-font-weight: bold; "
                + "-fx-min-width: 28; -fx-min-height: 22; -fx-max-height: 22; "
                + "-fx-padding: 0 4 0 4; -fx-background-radius: 4;"));
        btn.setTooltip(new Tooltip(tooltip));
        return btn;
    }

    // ======================================================================
    // Hardware selection
    // ======================================================================

    private VBox createHardwareSection() {
        VBox section = new VBox(6);
        section.setPadding(new Insets(12, 16, 8, 16));
        section.setStyle("-fx-background-color: " + bgSection() + "; -fx-border-color: " + borderSection()
                + "; -fx-border-width: 0 0 1 0;");

        Label label = new Label("Hardware Configuration");
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + textPrimary() + ";");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);

        // Modality
        Label modalityLabel = new Label("Modality:");
        modalityLabel.setStyle("-fx-text-fill: " + textPrimary() + ";");
        grid.add(modalityLabel, 0, 0);
        modalityCombo = new ComboBox<>();
        modalityCombo.setMaxWidth(Double.MAX_VALUE);
        modalityCombo.setOnAction(e -> onModalityChanged());
        grid.add(modalityCombo, 1, 0);

        // Objective
        Label objectiveLabel = new Label("Objective:");
        objectiveLabel.setStyle("-fx-text-fill: " + textPrimary() + ";");
        grid.add(objectiveLabel, 0, 1);
        objectiveCombo = new ComboBox<>();
        objectiveCombo.setMaxWidth(Double.MAX_VALUE);
        objectiveCombo.setOnAction(e -> onObjectiveChanged());
        grid.add(objectiveCombo, 1, 1);

        // Detector
        Label detectorLabel = new Label("Detector:");
        detectorLabel.setStyle("-fx-text-fill: " + textPrimary() + ";");
        grid.add(detectorLabel, 0, 2);
        detectorCombo = new ComboBox<>();
        detectorCombo.setMaxWidth(Double.MAX_VALUE);
        detectorCombo.setOnAction(e -> onDetectorChanged());
        grid.add(detectorCombo, 1, 2);

        // Set column constraints
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(80);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);

        section.getChildren().addAll(label, grid);

        // Populate modalities
        populateModalities();

        return section;
    }

    private void populateModalities() {
        Set<String> modalities = CalibrationChecker.getAvailableModalities();
        modalityCombo.setItems(FXCollections.observableArrayList(modalities));
        if (!modalities.isEmpty()) {
            // Try to restore last-used modality
            String lastModality = PersistentPreferences.getLastModality();
            if (!lastModality.isEmpty() && modalities.contains(lastModality)) {
                modalityCombo.setValue(lastModality);
            } else {
                modalityCombo.getSelectionModel().selectFirst();
            }
            onModalityChanged();
        }
    }

    private void onModalityChanged() {
        String modality = modalityCombo.getValue();
        if (modality == null) return;

        // Save current selection
        PersistentPreferences.setLastModality(modality);

        Set<String> objectives = CalibrationChecker.getAvailableObjectives();
        objectiveCombo.setItems(FXCollections.observableArrayList(objectives));
        if (!objectives.isEmpty()) {
            boolean restored = false;

            // Priority 1: match objective_in_use from microscope config
            String configObjective = getConfigObjectiveInUse();
            if (configObjective != null && objectives.contains(configObjective)) {
                objectiveCombo.setValue(configObjective);
                restored = true;
            }

            // Priority 2: restore last-used objective from preferences
            if (!restored) {
                String lastObjective = PersistentPreferences.getLastObjective();
                if (!lastObjective.isEmpty() && objectives.contains(lastObjective)) {
                    objectiveCombo.setValue(lastObjective);
                    restored = true;
                }
            }

            // Fallback: select first
            if (!restored) {
                objectiveCombo.getSelectionModel().selectFirst();
            }
            onObjectiveChanged();
        } else {
            objectiveCombo.getItems().clear();
            detectorCombo.getItems().clear();
            refreshCalibrationStatuses();
        }
    }

    private void onObjectiveChanged() {
        String modality = modalityCombo.getValue();
        String objective = objectiveCombo.getValue();
        if (modality == null || objective == null) return;

        // Save current selection
        PersistentPreferences.setLastObjective(objective);

        Set<String> detectors = CalibrationChecker.getAvailableDetectors();
        detectorCombo.setItems(FXCollections.observableArrayList(detectors));
        if (!detectors.isEmpty()) {
            // Try to restore last-used detector
            String lastDetector = PersistentPreferences.getLastDetector();
            if (!lastDetector.isEmpty() && detectors.contains(lastDetector)) {
                detectorCombo.setValue(lastDetector);
            } else {
                detectorCombo.getSelectionModel().selectFirst();
            }
        }
        refreshCalibrationStatuses();
    }

    private void onDetectorChanged() {
        String detector = detectorCombo.getValue();
        if (detector != null) {
            PersistentPreferences.setLastDetector(detector);
        }
        refreshCalibrationStatuses();
    }

    private String getSelectedModality() {
        return modalityCombo.getValue();
    }

    private String getSelectedObjective() {
        return objectiveCombo.getValue();
    }

    private String getSelectedDetector() {
        return detectorCombo.getValue();
    }

    /**
     * Read the current objective from microscope config (microscope.objective_in_use).
     * Returns null if not configured or unavailable.
     */
    private String getConfigObjectiveInUse() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            String objective = mgr.getString("microscope", "objective_in_use");
            return (objective != null && !objective.isEmpty()) ? objective : null;
        } catch (Exception e) {
            logger.debug("Could not read objective_in_use from config", e);
            return null;
        }
    }

    // ======================================================================
    // Step rows
    // ======================================================================

    private static class StepRow {
        final int index;
        final Circle statusDot;
        final Label statusLabel;
        final Button actionButton;

        StepRow(int index, Circle statusDot, Label statusLabel, Button actionButton) {
            this.index = index;
            this.statusDot = statusDot;
            this.statusLabel = statusLabel;
            this.actionButton = actionButton;
        }
    }

    private HBox createStepRow(
            int index, Region icon, String title, String description, String buttonText, Runnable action) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setStyle("-fx-background-color: " + bgRow() + "; -fx-background-radius: 6; "
                + "-fx-border-color: " + borderColor() + "; -fx-border-radius: 6;");

        // Icon circle
        StackPane iconPane = new StackPane(icon);
        iconPane.setPrefSize(36, 36);
        iconPane.setMinSize(36, 36);
        iconPane.setMaxSize(36, 36);
        iconPane.setStyle("-fx-background-color: " + bgIcon() + "; -fx-background-radius: 18;");
        iconPane.setAlignment(Pos.CENTER);

        // Text
        VBox textBox = new VBox(1);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: " + textPrimary() + ";");

        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + textSecondary() + ";");
        descLabel.setWrapText(true);

        textBox.getChildren().addAll(titleLabel, descLabel);

        // Status dot + label
        Circle statusDot = new Circle(5, Color.GRAY);

        Label statusLabel = new Label("Checking...");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: " + textMuted() + ";");
        statusLabel.setMaxWidth(120);
        statusLabel.setWrapText(true);

        VBox statusBox = new VBox(2);
        statusBox.setAlignment(Pos.CENTER);
        statusBox.setPrefWidth(130);
        statusBox.setMinWidth(130);
        statusBox.getChildren().addAll(statusDot, statusLabel);

        // Action button
        Button btn = new Button(buttonText);
        btn.setMinWidth(90);
        btn.setOnAction(e -> action.run());

        row.getChildren().addAll(iconPane, textBox, statusBox, btn);

        StepRow stepRow = new StepRow(index, statusDot, statusLabel, btn);
        stepRows.add(stepRow);

        return row;
    }

    private void updateStepStatus(int stepIndex, StepStatus status) {
        if (stepIndex < 0 || stepIndex >= stepRows.size()) return;

        StepRow row = stepRows.get(stepIndex);

        Platform.runLater(() -> {
            Color dotColor;
            String labelStyle;

            switch (status.status()) {
                case READY -> {
                    dotColor = Color.web("#4CAF50");
                    labelStyle = "-fx-font-size: 10px; -fx-text-fill: #4CAF50;";
                    row.actionButton.setDisable(false);
                }
                case WARNING -> {
                    dotColor = Color.web("#FF9800");
                    labelStyle = "-fx-font-size: 10px; -fx-text-fill: #e68a00;";
                    row.actionButton.setDisable(false);
                }
                case NOT_READY -> {
                    dotColor = Color.web("#f44336");
                    labelStyle = "-fx-font-size: 10px; -fx-text-fill: #d32f2f;";
                    row.actionButton.setDisable(false);
                }
                case NOT_APPLICABLE -> {
                    dotColor = Color.web("#9E9E9E");
                    labelStyle = "-fx-font-size: 10px; -fx-text-fill: " + textMuted() + ";";
                    row.actionButton.setDisable(true);
                }
                default -> {
                    dotColor = Color.GRAY;
                    labelStyle = "-fx-font-size: 10px; -fx-text-fill: " + textMuted() + ";";
                }
            }

            row.statusDot.setFill(dotColor);
            row.statusLabel.setStyle(labelStyle);
            row.statusLabel.setText(status.message());

            // Mirror to collapsed pill dot
            if (stepIndex < pillDots.length && pillDots[stepIndex] != null) {
                pillDots[stepIndex].setFill(dotColor);
            }

            updateAcquireButtons();
        });
    }

    // ======================================================================
    // Autofocus override
    // ======================================================================

    private HBox createAutofocusOverrideSection() {
        HBox section = new HBox(8);
        section.setPadding(new Insets(4, 16, 4, 16));
        section.setAlignment(Pos.CENTER_LEFT);

        disableAutofocusCheckBox = new CheckBox("Disable Autofocus");
        disableAutofocusCheckBox.setTooltip(
                new Tooltip("Skip all autofocus during acquisition. Only use when you know\n"
                        + "the sample is flat and already in focus. Focus drift will NOT\n"
                        + "be corrected."));

        // Bind to the persistent preference (bidirectional)
        disableAutofocusCheckBox.setSelected(QPPreferenceDialog.getDisableAllAutofocus());
        disableAutofocusCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            QPPreferenceDialog.setDisableAllAutofocus(newVal);
            updateAutofocusCheckBoxStyle();
        });
        updateAutofocusCheckBoxStyle();

        Button validateAfButton = new Button("Validate AF");
        validateAfButton.setStyle("-fx-font-size: 11px; -fx-border-color: #4A90D9; -fx-border-width: 1.5; "
                + "-fx-border-radius: 3; -fx-background-radius: 3;");
        validateAfButton.setTooltip(new Tooltip("Test autofocus on current tissue position.\n\n"
                + "HOW TO USE:\n"
                + "  1. Navigate to tissue using the Live Viewer\n"
                + "  2. Manually focus on the tissue (scroll Z until sharp)\n"
                + "  3. Click this button\n\n"
                + "THE TEST:\n"
                + "  Phase 1: Sweep drift check from your focused position\n"
                + "  Phase 2: Defocus 80%, then full autofocus recovery\n"
                + "  Results show pass/fail for each phase.\n\n"
                + "TO CHANGE SETTINGS:\n"
                + "  Extensions > QP Scope > Autofocus Configuration Editor\n"
                + "  Adjust search range, step count, or score metric there."));
        Label afWarningLabel = new Label();
        afWarningLabel.setStyle("-fx-text-fill: #D32F2F; -fx-font-weight: bold; -fx-font-size: 11px;");
        afWarningLabel.setVisible(false);
        afWarningLabel.setManaged(false);

        // Blink animation for the warning
        javafx.animation.Timeline blinkTimeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.millis(700), e2 -> afWarningLabel.setVisible(!afWarningLabel.isVisible())),
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.millis(1400),
                        e2 -> afWarningLabel.setVisible(!afWarningLabel.isVisible())));
        blinkTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);

        validateAfButton.setOnAction(e -> {
            afWarningLabel.setText("Do not interact with the microscope stage position");
            afWarningLabel.setVisible(true);
            afWarningLabel.setManaged(true);
            blinkTimeline.play();
            runAutofocusValidation(validateAfButton, afWarningLabel, blinkTimeline);
        });

        javafx.scene.layout.HBox afButtonRow = new javafx.scene.layout.HBox(10, validateAfButton, afWarningLabel);
        afButtonRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        section.getChildren().addAll(disableAutofocusCheckBox, afButtonRow);
        return section;
    }

    private void runAutofocusValidation(Button button, Label warningLabel, javafx.animation.Timeline blinkTimeline) {
        try {
            if (!MicroscopeController.getInstance().isConnected()) {
                qupath.fx.dialogs.Dialogs.showErrorMessage(
                        "Connection Required", "Connect to the microscope server first.");
                return;
            }

            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null) {
                qupath.fx.dialogs.Dialogs.showErrorMessage(
                        "Configuration Error", "No microscope configuration file set.");
                return;
            }

            // Get current objective from wizard selection
            String objective = getSelectedObjective();
            if (objective == null || objective.isEmpty()) {
                qupath.fx.dialogs.Dialogs.showErrorMessage(
                        "Selection Required", "Select an objective in the wizard first.");
                return;
            }

            String testOutputPath =
                    new java.io.File(configPath).getParent() + java.io.File.separator + "autofocus_tests";

            button.setDisable(true);
            button.setText("Testing...");

            java.util.concurrent.CompletableFuture.runAsync(() -> {
                        // Stop live viewing -- AF validation moves Z and snaps images
                        MicroscopeController mc = MicroscopeController.getInstance();
                        MicroscopeController.LiveViewState liveState = mc.stopAllLiveViewing();
                        try {
                            var socketClient = mc.getSocketClient();
                            var result = socketClient.testAutofocusValidation(configPath, testOutputPath, objective);

                            javafx.application.Platform.runLater(() -> {
                                blinkTimeline.stop();
                                warningLabel.setVisible(false);
                                warningLabel.setManaged(false);
                                button.setDisable(false);
                                button.setText("Validate AF");
                                qupath.ext.qpsc.controller.AutofocusEditorWorkflow.showValidationResultStatic(result);
                            });
                        } catch (Exception ex) {
                            logger.error("Autofocus validation failed", ex);
                            javafx.application.Platform.runLater(() -> {
                                blinkTimeline.stop();
                                warningLabel.setVisible(false);
                                warningLabel.setManaged(false);
                                button.setDisable(false);
                                button.setText("Validate AF");
                                qupath.fx.dialogs.Dialogs.showErrorMessage(
                                        "Autofocus Validation Failed",
                                        "Error: " + ex.getMessage()
                                                + "\n\nMake sure you are focused on tissue before testing.");
                            });
                        } finally {
                            mc.restoreLiveViewState(liveState);
                        }
                    })
                    .exceptionally(ex -> {
                        javafx.application.Platform.runLater(() -> {
                            blinkTimeline.stop();
                            warningLabel.setVisible(false);
                            warningLabel.setManaged(false);
                            button.setDisable(false);
                            button.setText("Validate AF");
                        });
                        return null;
                    });
        } catch (Exception ex) {
            qupath.fx.dialogs.Dialogs.showErrorMessage("Error", "Failed to start validation: " + ex.getMessage());
        }
    }

    private void updateAutofocusCheckBoxStyle() {
        if (disableAutofocusCheckBox.isSelected()) {
            disableAutofocusCheckBox.setStyle("-fx-text-fill: #E65100; -fx-font-weight: bold; -fx-font-size: 12px;");
        } else {
            disableAutofocusCheckBox.setStyle("-fx-font-size: 12px; -fx-text-fill: " + textPrimary() + ";");
        }
    }

    // ======================================================================
    // Acquire section
    // ======================================================================

    private HBox createAcquireSection() {
        HBox section = new HBox(12);
        section.setPadding(new Insets(12, 16, 12, 16));
        section.setAlignment(Pos.CENTER);

        boundedButton = createAcquireButton(
                "Bounded\nAcquisition",
                "Define a rectangular region\nusing stage coordinates",
                this::onBoundedAcquisition);

        existingImageButton = createAcquireButton(
                "Existing Image\nAcquisition",
                "Acquire annotated regions\nfrom an open image",
                this::onExistingImageAcquisition);

        section.getChildren().addAll(boundedButton, existingImageButton);
        return section;
    }

    private Button createAcquireButton(String title, String description, Runnable action) {
        VBox content = new VBox(4);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(4));

        Polygon triangle = new Polygon(0, 0, 0, 16, 14, 8);
        triangle.setFill(Color.WHITE);

        Label titleLabel = new Label(title);
        titleLabel.setStyle(
                "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: white; " + "-fx-text-alignment: center;");
        titleLabel.setAlignment(Pos.CENTER);
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #ccd9ee; -fx-text-alignment: center;");
        descLabel.setAlignment(Pos.CENTER);
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);

        content.getChildren().addAll(triangle, titleLabel, descLabel);
        content.setMaxWidth(Double.MAX_VALUE);

        Button btn = new Button();
        btn.setGraphic(content);
        btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        btn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btn, Priority.ALWAYS);
        btn.setPrefHeight(110);
        btn.setStyle(
                "-fx-background-color: " + BLUE + "; -fx-background-radius: 8; " + "-fx-cursor: hand; -fx-padding: 4;");

        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: " + BLUE_HOVER + "; -fx-background-radius: 8; "
                + "-fx-cursor: hand; -fx-padding: 4;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: " + BLUE + "; -fx-background-radius: 8; "
                + "-fx-cursor: hand; -fx-padding: 4;"));

        btn.setOnAction(e -> action.run());

        return btn;
    }

    private void updateAcquireButtons() {
        boolean connected = false;
        for (StepRow row : stepRows) {
            if (row.index == STEP_CONNECTION) {
                connected = Color.web("#4CAF50").equals(row.statusDot.getFill());
                break;
            }
        }

        // Bounded Acquisition: only needs server connection
        boundedButton.setDisable(!connected);

        // Existing Image: needs server connection AND an open image
        QuPathGUI gui = QuPathGUI.getInstance();
        boolean hasImage = gui != null && gui.getImageData() != null;
        existingImageButton.setDisable(!connected || !hasImage);

        // Update the existing image button tooltip to explain why it's disabled
        if (!hasImage && connected) {
            existingImageButton.setTooltip(new Tooltip("Open an image in QuPath first"));
        } else {
            existingImageButton.setTooltip(null);
        }
    }

    // ======================================================================
    // Bottom bar
    // ======================================================================

    private HBox createBottomBar() {
        HBox bar = new HBox(10);
        bar.setPadding(new Insets(8, 16, 10, 16));
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setStyle("-fx-background-color: " + bgSection() + "; -fx-border-color: " + borderSection()
                + "; -fx-border-width: 1 0 0 0; -fx-background-radius: 0 0 8 8;");

        Button refreshBtn = new Button("Refresh All");
        refreshBtn.setOnAction(e -> refreshAllStatuses());

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> wizardStage.close());

        bar.getChildren().addAll(refreshBtn, closeBtn);
        return bar;
    }

    // ======================================================================
    // Section separator
    // ======================================================================

    private HBox createSectionSeparator(String text) {
        HBox sep = new HBox(8);
        sep.setAlignment(Pos.CENTER_LEFT);
        sep.setPadding(new Insets(8, 16, 4, 16));

        Label label = new Label(text);
        label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + textSectionHeader() + ";");

        Separator line = new Separator();
        HBox.setHgrow(line, Priority.ALWAYS);

        sep.getChildren().addAll(label, line);
        return sep;
    }

    // ======================================================================
    // Step icons (simple shapes)
    // ======================================================================

    private Region createConnectionIcon() {
        SVGPath svg = new SVGPath();
        svg.setContent("M8,2 L8,8 L6,8 L6,14 L10,14 L10,8 L12,8 L12,2 Z M4,8 L4,2 L6,2 L6,8 Z");
        svg.setFill(Color.web(BLUE));
        svg.setScaleX(1.2);
        svg.setScaleY(1.2);
        StackPane pane = new StackPane(svg);
        pane.setPrefSize(20, 20);
        return pane;
    }

    private Region createCalibrationIcon() {
        SVGPath svg = new SVGPath();
        svg.setContent("M8,0 L8,4 M8,12 L8,16 M0,8 L4,8 M12,8 L16,8 " + "M8,5 A3,3 0 1,1 8,11 A3,3 0 1,1 8,5 Z");
        svg.setFill(Color.TRANSPARENT);
        svg.setStroke(Color.web(BLUE));
        svg.setStrokeWidth(1.5);
        svg.setScaleX(1.1);
        svg.setScaleY(1.1);
        StackPane pane = new StackPane(svg);
        pane.setPrefSize(20, 20);
        return pane;
    }

    private Region createGridIcon() {
        VBox grid = new VBox(2);
        grid.setAlignment(Pos.CENTER);
        for (int r = 0; r < 2; r++) {
            HBox row = new HBox(2);
            row.setAlignment(Pos.CENTER);
            for (int c = 0; c < 2; c++) {
                Rectangle rect = new Rectangle(7, 7);
                rect.setFill(Color.web(BLUE));
                rect.setArcWidth(2);
                rect.setArcHeight(2);
                row.getChildren().add(rect);
            }
            grid.getChildren().add(row);
        }
        return grid;
    }

    private Region createAlignmentIcon() {
        StackPane pane = new StackPane();
        pane.setPrefSize(20, 20);

        Rectangle r1 = new Rectangle(10, 10);
        r1.setFill(Color.TRANSPARENT);
        r1.setStroke(Color.web(BLUE));
        r1.setStrokeWidth(1.5);
        r1.setTranslateX(-2);
        r1.setTranslateY(-2);

        Rectangle r2 = new Rectangle(10, 10);
        r2.setFill(Color.TRANSPARENT);
        r2.setStroke(Color.web(BLUE));
        r2.setStrokeWidth(1.5);
        r2.getStrokeDashArray().addAll(3.0, 2.0);
        r2.setTranslateX(2);
        r2.setTranslateY(2);

        pane.getChildren().addAll(r1, r2);
        return pane;
    }

    // ======================================================================
    // Status refresh
    // ======================================================================

    private void refreshAllStatuses() {
        CompletableFuture.runAsync(() -> {
            updateStepStatus(STEP_CONNECTION, CalibrationChecker.checkServerConnection());
        });
        refreshCalibrationStatuses();
    }

    private void refreshCalibrationStatuses() {
        String modality = getSelectedModality();
        String objective = getSelectedObjective();
        String detector = getSelectedDetector();

        CompletableFuture.runAsync(() -> {
            updateStepStatus(STEP_WHITE_BALANCE, CalibrationChecker.checkWhiteBalance(modality, objective, detector));
            updateStepStatus(
                    STEP_BACKGROUND, CalibrationChecker.checkBackgroundCorrection(modality, objective, detector));
            updateStepStatus(STEP_ALIGNMENT, CalibrationChecker.checkAlignment());
        });
    }

    // ======================================================================
    // Action handlers
    // ======================================================================

    private void onConnect() {
        try {
            if (MicroscopeController.getInstance().isConnected()) {
                refreshAllStatuses();
                return;
            }
            MicroscopeController.getInstance().connect();
            refreshAllStatuses();
        } catch (IOException e) {
            logger.error("Failed to connect to microscope server", e);
            updateStepStatus(STEP_CONNECTION, new StepStatus(Status.NOT_READY, "Connection failed: " + e.getMessage()));
        }
    }

    /**
     * Re-assert the Wizard's current combo-box selections as the authoritative
     * global preference state immediately before launching a sub-workflow.
     *
     * Sub-dialogs (WhiteBalanceDialog, BackgroundCollectionController, etc.)
     * read `PersistentPreferences.getLastObjective()` / `getLastModality()` /
     * `getLastDetector()` at init time to pre-select their own combo boxes.
     * Between the moment the user opens the Wizard and the moment they click
     * a sub-workflow button, something else (Live Viewer "Refresh from MM",
     * another dialog's submit, a stale shadow preference) may have written a
     * different value into those globals.
     *
     * The Wizard's own combo values are the user's *current stated intent*.
     * Reasserting them here guarantees that whatever the Wizard is showing is
     * what the downstream dialog sees. This is the single load-bearing line
     * that keeps the Wizard -> WB -> Background -> Acquire chain consistent.
     *
     * Root cause for this defence: the 2026-04-15 incident where WB
     * calibration saved exposures under a 20X_POL slot while the user was
     * physically on 10X and the Wizard correctly knew 10X -- a stale shadow
     * preference in WhiteBalanceDialog had silently overridden the Wizard's
     * selection.
     */
    private void syncWizardSelectionToPreferences() {
        String modality = getSelectedModality();
        String objective = getSelectedObjective();
        String detector = getSelectedDetector();
        if (modality != null && !modality.isEmpty()) {
            PersistentPreferences.setLastModality(modality);
        }
        if (objective != null && !objective.isEmpty()) {
            PersistentPreferences.setLastObjective(objective);
        }
        if (detector != null && !detector.isEmpty()) {
            PersistentPreferences.setLastDetector(detector);
        }
        logger.info(
                "Wizard asserting selection to preferences before sub-workflow launch: "
                        + "modality='{}', objective='{}', detector='{}'",
                modality,
                objective,
                detector);
    }

    private void onWhiteBalance() {
        syncWizardSelectionToPreferences();
        try {
            QPScopeController.getInstance().startWorkflow("whiteBalance");
        } catch (IOException e) {
            logger.error("Failed to launch white balance workflow", e);
        }
    }

    private void onBackgroundCollection() {
        syncWizardSelectionToPreferences();
        try {
            QPScopeController.getInstance().startWorkflow("backgroundCollection");
        } catch (IOException e) {
            logger.error("Failed to launch background collection workflow", e);
        }
    }

    private void onAlignment() {
        syncWizardSelectionToPreferences();
        try {
            QPScopeController.getInstance().startWorkflow("microscopeAlignment");
        } catch (IOException e) {
            logger.error("Failed to launch microscope alignment workflow", e);
        }
    }

    private void onBoundedAcquisition() {
        if (!confirmCalibrationStatus()) return;
        syncWizardSelectionToPreferences();
        try {
            QPScopeController.getInstance().startWorkflow("boundedAcquisition");
        } catch (IOException e) {
            logger.error("Failed to launch bounded acquisition", e);
        }
    }

    private void onExistingImageAcquisition() {
        if (!confirmCalibrationStatus()) return;
        syncWizardSelectionToPreferences();
        try {
            QPScopeController.getInstance().startWorkflow("existingImage");
        } catch (IOException e) {
            logger.error("Failed to launch existing image acquisition", e);
        }
    }

    /**
     * Checks current calibration status and warns the user before acquisition
     * if any steps are not READY. Returns true to proceed, false to cancel.
     */
    private boolean confirmCalibrationStatus() {
        String modality = modalityCombo.getValue();
        String objective = objectiveCombo.getValue();
        String detector = detectorCombo.getValue();

        List<String> warnings = new ArrayList<>();

        // Check server connection
        StepStatus connStatus = CalibrationChecker.checkServerConnection();
        if (connStatus.status() == Status.NOT_READY) {
            warnings.add("Server: " + connStatus.message());
        }

        // Check white balance
        StepStatus wbStatus = CalibrationChecker.checkWhiteBalance(modality, objective, detector);
        if (wbStatus.status() == Status.WARNING || wbStatus.status() == Status.NOT_READY) {
            warnings.add("White Balance: " + wbStatus.message());
        }

        // Check background correction
        StepStatus bgStatus = CalibrationChecker.checkBackgroundCorrection(modality, objective, detector);
        if (bgStatus.status() == Status.WARNING || bgStatus.status() == Status.NOT_READY) {
            warnings.add("Backgrounds: " + bgStatus.message());
        }

        // Check alignment
        StepStatus alignStatus = CalibrationChecker.checkAlignment();
        if (alignStatus.status() == Status.NOT_READY) {
            warnings.add("Alignment: " + alignStatus.message());
        }

        if (warnings.isEmpty()) {
            return true;
        }

        // Server not connected is a hard block
        if (connStatus.status() == Status.NOT_READY) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Cannot Start Acquisition");
            alert.setHeaderText("Microscope server is not connected");
            alert.setContentText(connStatus.message());
            // Wizard is always-on-top; helper parents + raises the alert.
            UIFunctions.showAlertOverParent(alert, wizardStage);
            return false;
        }

        // Other issues get a warning with proceed/cancel
        StringBuilder msg = new StringBuilder();
        msg.append("The following calibration steps have warnings:\n\n");
        for (String w : warnings) {
            msg.append("  - ").append(w).append("\n");
        }
        msg.append("\nAcquiring with stale or missing calibration may produce ");
        msg.append("incorrect flat-field correction or color balance.\n\n");
        msg.append("Proceed anyway?");

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Calibration Warnings");
        alert.setHeaderText("Some calibration steps are not ready");
        alert.setContentText(msg.toString());
        alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.CANCEL);

        // Wizard is always-on-top; helper parents + raises the alert.
        var result = UIFunctions.showAlertOverParent(alert, wizardStage);
        if (result.isPresent() && result.get() == ButtonType.YES) {
            logger.warn("User proceeding with calibration warnings: {}", warnings);
            return true;
        }
        logger.info("User cancelled acquisition due to calibration warnings");
        return false;
    }
}
