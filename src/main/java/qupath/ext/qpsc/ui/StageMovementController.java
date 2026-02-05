package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.stage.Stage;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
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
 * StageMovementController - Manual microscope stage control interface
 * 
 * <p>This controller provides a dialog interface for manual stage control, allowing users to move 
 * X/Y, Z, and R (polarizer) axes independently while enforcing configured safety limits. It 
 * validates movements against stage boundaries and provides real-time feedback on position updates.
 * 
 * <p>Key features:
 * <ul>
 *   <li>Independent control of X/Y stage positioning with bounds validation</li>
 *   <li>Z-axis focus control with configured limit enforcement</li>
 *   <li>Polarizer rotation (R-axis) control without bounds restrictions</li>
 *   <li>Real-time position feedback and status updates</li>
 *   <li>Safety validation against configured stage boundaries</li>
 *   <li>Error handling and user notification for invalid operations</li>
 * </ul>
 * 
 * <p>Dialog operation flow:
 * <ol>
 *   <li>Initialize dialog with current stage positions from hardware</li>
 *   <li>Load stage boundary configuration from MicroscopeConfigManager</li>
 *   <li>Present independent input fields for X, Y, Z, and R coordinates</li>
 *   <li>Validate input values against configured stage limits before movement</li>
 *   <li>Execute stage movements via MicroscopeController</li>
 *   <li>Update status labels with movement confirmation or error messages</li>
 * </ol>
 * 
 * <p>The dialog remains open for multiple movements, allowing users to perform sequential 
 * positioning operations without reopening the interface.
 * 
 * @author Mike Nelson
 * @since 1.0
 */
public class StageMovementController {

    private static final Logger logger = LoggerFactory.getLogger(StageMovementController.class);

    /** Singleton instance tracking - ensures only one dialog is open at a time */
    private static Dialog<Void> activeDialog = null;

    /**
     * Displays the stage movement control dialog for manual microscope positioning.
     * 
     * <p>This method creates and shows a non-modal dialog that allows users to control the microscope
     * stage position across X/Y, Z, and R (polarizer) axes. The dialog initializes with current
     * hardware positions and provides real-time movement controls with safety validation.
     * 
     * <p>Dialog components:
     * <ul>
     *   <li>Text fields for X, Y, Z, and R coordinate input</li>
     *   <li>Move buttons for each axis with bounds checking</li>
     *   <li>Status labels showing movement confirmations and errors</li>
     *   <li>Configuration-based boundary validation for X/Y and Z axes</li>
     * </ul>
     * 
     * <p>The dialog executes on the JavaFX Application Thread and remains open for multiple
     * movement operations. Stage boundaries are enforced through MicroscopeConfigManager
     * validation before any movement commands are sent to hardware.
     * 
     * @throws RuntimeException if resource bundle loading fails or dialog creation encounters errors
     */
    public static void showTestStageMovementDialog() {
        logger.info("Initiating stage movement dialog display");
        Platform.runLater(() -> {
            // Check if dialog is already showing - bring to front instead of creating duplicate
            if (activeDialog != null && activeDialog.isShowing()) {
                logger.info("Stage movement dialog already open - bringing to front");
                Stage stage = (Stage) activeDialog.getDialogPane().getScene().getWindow();
                stage.toFront();
                stage.requestFocus();
                return;
            }

            logger.debug("Creating stage movement dialog on JavaFX Application Thread");
            ResourceBundle res = ResourceBundle.getBundle("qupath.ext.qpsc.ui.strings");
            Dialog<Void> dlg = new Dialog<>();
            activeDialog = dlg;  // Track the active dialog
            dlg.setTitle(res.getString("stageMovement.title"));
            dlg.setHeaderText(res.getString("stageMovement.header"));
            logger.debug("Dialog created with title: {}", res.getString("stageMovement.title"));

            // Create warning label for multiple viewers
            Label warningLabel = new Label(res.getString("stageMovement.warning.multipleViewers"));
            warningLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #cc3300; -fx-padding: 5 0 10 0;");

            // --- Fields and status labels ---
            logger.debug("Creating UI input fields and status labels");
            TextField xField = new TextField();
            TextField yField = new TextField();
            Label xyStatus = new Label();

            TextField zField = new TextField();
            Label zStatus = new Label();

            TextField rField = new TextField();
            Label rStatus = new Label();

            // --- Initialize from hardware ---
            logger.debug("Initializing dialog fields with current stage positions from hardware");
            try {
                double[] xy = MicroscopeController.getInstance().getStagePositionXY();
                xField.setText(String.format("%.2f", xy[0]));
                yField.setText(String.format("%.2f", xy[1]));
                logger.debug("Initialized XY fields with current position: X={}, Y={}", xy[0], xy[1]);
            } catch (Exception e) {
                logger.warn("Failed to retrieve current XY stage position: {}", e.getMessage());
                MicroscopeErrorHandler.handleException(e, "get current XY stage position");
            }

            try {
                double z = MicroscopeController.getInstance().getStagePositionZ();
                zField.setText(String.format("%.2f", z));
                logger.debug("Initialized Z field with current position: {}", z);
            } catch (Exception e) {
                logger.warn("Failed to retrieve current Z stage position: {}", e.getMessage());
                // Don't show error again if XY already failed (same root cause)
            }

            try {
                double r = MicroscopeController.getInstance().getStagePositionR();
                rField.setText(String.format("%.2f", r));
                logger.debug("Initialized R field with current position: {}", r);
            } catch (Exception e) {
                logger.warn("Failed to retrieve current R stage position: {}", e.getMessage());
                // Don't show error again if XY already failed (same root cause)
            }

            // Get config manager for bounds checking
            logger.debug("Loading microscope configuration for stage bounds validation");
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
            logger.debug("Configuration manager initialized with path: {}", configPath);

            // --- Buttons ---
            logger.debug("Creating movement control buttons");
            Button moveXYBtn = new Button(res.getString("stageMovement.button.moveXY"));
            Button moveZBtn  = new Button(res.getString("stageMovement.button.moveZ"));
            Button moveRBtn  = new Button(res.getString("stageMovement.button.moveR"));

            // --- Actions (dialog stays open) ---
            logger.debug("Setting up button action handlers for stage movements");
            moveXYBtn.setOnAction(e -> {
                logger.debug("XY movement button clicked");
                try {
                    double x = Double.parseDouble(xField.getText());
                    double y = Double.parseDouble(yField.getText());
                    logger.debug("Parsed XY coordinates for movement: X={}, Y={}", x, y);
                    
                    // *** BOUNDS CHECK using ConfigManager directly ***
                    if (!mgr.isWithinStageBounds(x, y)) {
                        logger.warn("XY movement rejected - coordinates out of bounds: X={}, Y={}", x, y);
                        UIFunctions.notifyUserOfError(
                                res.getString("stageMovement.error.outOfBoundsXY"),
                                res.getString("stageMovement.title"));
                        UIFunctions.showAlertDialog(res.getString("stageMovement.error.outOfBoundsXY"));
                        return;
                    }
                    
                    logger.info("Executing XY stage movement to position: X={}, Y={}", x, y);
                    MicroscopeController.getInstance().moveStageXY(x, y);
                    xyStatus.setText(
                            String.format(res.getString("stageMovement.status.xyMoved"), x, y));
                    logger.info("XY stage movement completed successfully: X={}, Y={}", x, y);
                } catch (NumberFormatException ex) {
                    logger.warn("Invalid XY coordinate format - X: '{}', Y: '{}'", xField.getText(), yField.getText());
                    UIFunctions.notifyUserOfError(
                            "Invalid coordinate format: " + ex.getMessage(), res.getString("stageMovement.title"));
                } catch (Exception ex) {
                    logger.error("XY stage movement failed: {}", ex.getMessage(), ex);
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            moveZBtn.setOnAction(e -> {
                logger.debug("Z movement button clicked");
                try {
                    double z = Double.parseDouble(zField.getText());
                    logger.debug("Parsed Z coordinate for movement: {}", z);
                    
                    // *** BOUNDS CHECK using ConfigManager directly ***
                    if (!mgr.isWithinStageBounds(z)) {
                        logger.warn("Z movement rejected - coordinate out of bounds: {}", z);
                        UIFunctions.notifyUserOfError(
                                res.getString("stageMovement.error.outOfBoundsZ"),
                                res.getString("stageMovement.title"));
                        UIFunctions.showAlertDialog(res.getString("stageMovement.error.outOfBoundsZ"));
                        return;
                    }
                    
                    logger.info("Executing Z stage movement to position: {}", z);
                    MicroscopeController.getInstance().moveStageZ(z);
                    zStatus.setText(
                            String.format(res.getString("stageMovement.status.zMoved"), z));
                    logger.info("Z stage movement completed successfully: {}", z);
                } catch (NumberFormatException ex) {
                    logger.warn("Invalid Z coordinate format: '{}'", zField.getText());
                    UIFunctions.notifyUserOfError(
                            "Invalid coordinate format: " + ex.getMessage(), res.getString("stageMovement.title"));
                } catch (Exception ex) {
                    logger.error("Z stage movement failed: {}", ex.getMessage(), ex);
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            moveRBtn.setOnAction(e -> {
                logger.debug("R movement button clicked");
                try {
                    double r = Double.parseDouble(rField.getText());
                    logger.debug("Parsed R coordinate for movement: {}", r);

                    // no bounds for R (polarizer rotation is unrestricted)
                    logger.info("Executing R stage movement to position: {}", r);
                    MicroscopeController.getInstance().moveStageR(r);
                    rStatus.setText(
                            String.format(res.getString("stageMovement.status.rMoved"), r));
                    logger.info("R stage movement completed successfully: {}", r);
                } catch (NumberFormatException ex) {
                    logger.warn("Invalid R coordinate format: '{}'", rField.getText());
                    UIFunctions.notifyUserOfError(
                            "Invalid coordinate format: " + ex.getMessage(), res.getString("stageMovement.title"));
                } catch (Exception ex) {
                    logger.error("R stage movement failed: {}", ex.getMessage(), ex);
                    UIFunctions.notifyUserOfError(
                            ex.getMessage(), res.getString("stageMovement.title"));
                }
            });

            // --- Step size controls (shared by arrows and joystick) ---
            logger.debug("Creating step size controls");

            // Input validation: only allow digits and commas (no decimals)
            UnaryOperator<TextFormatter.Change> integerFilter = change -> {
                String newText = change.getControlNewText();
                // Allow digits and commas only (commas are removed when parsing)
                if (newText.matches("[0-9,]*")) {
                    return change;
                }
                return null;
            };

            // Value field (only shown when "Value" is selected in dropdown)
            TextField xyStepField = new TextField("100");
            xyStepField.setPrefWidth(70);
            xyStepField.setAlignment(Pos.CENTER);
            xyStepField.setTextFormatter(new TextFormatter<>(integerFilter));
            Label valueUmLabel = new Label("um");
            valueUmLabel.setStyle("-fx-font-size: 10px;");
            HBox valueRow = new HBox(5, xyStepField, valueUmLabel);
            valueRow.setAlignment(Pos.CENTER_LEFT);

            // FOV-based step size dropdown
            double[] cachedFovUm = {0, 0};
            ComboBox<String> fovStepCombo = new ComboBox<>(FXCollections.observableArrayList(
                    "1 FOV", "0.5 FOV", "0.25 FOV", "0.1 FOV", res.getString("stageMovement.fov.value")));
            fovStepCombo.setValue(res.getString("stageMovement.fov.value"));
            fovStepCombo.setPrefWidth(100);

            Label fovInfoLabel = new Label(res.getString("stageMovement.fov.unavailable"));
            fovInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");

            Button refreshFovBtn = new Button("R");
            refreshFovBtn.setStyle("-fx-font-size: 10px; -fx-min-width: 24px; -fx-min-height: 24px; -fx-padding: 2;");
            Tooltip.install(refreshFovBtn, new Tooltip(res.getString("stageMovement.fov.refreshTooltip")));

            // Virtual joystick for continuous movement (larger for arrows around it)
            VirtualJoystick joystick = new VirtualJoystick(50);

            // Helper to query FOV from hardware and cache it
            Runnable queryFov = () -> {
                try {
                    double[] fov = MicroscopeController.getInstance().getCameraFOV();
                    cachedFovUm[0] = fov[0];
                    cachedFovUm[1] = fov[1];
                    fovInfoLabel.setText(String.format(res.getString("stageMovement.fov.format"), fov[0], fov[1]));
                    logger.debug("Queried camera FOV: {} x {} um", fov[0], fov[1]);
                } catch (Exception ex) {
                    logger.warn("Failed to query camera FOV: {}", ex.getMessage());
                    cachedFovUm[0] = 0;
                    cachedFovUm[1] = 0;
                    fovInfoLabel.setText(res.getString("stageMovement.fov.unavailable"));
                    // Fall back to Value mode
                    fovStepCombo.setValue(res.getString("stageMovement.fov.value"));
                    xyStepField.setDisable(false);
                }
            };

            // Helper to apply FOV-based step size
            Runnable applyFovStep = () -> {
                String selection = fovStepCombo.getValue();
                boolean isValueMode = selection == null || selection.equals(res.getString("stageMovement.fov.value"));

                // Show/hide value row based on selection
                valueRow.setVisible(isValueMode);
                valueRow.setManaged(isValueMode);

                if (isValueMode) {
                    xyStepField.setDisable(false);
                    return;
                }
                // Parse multiplier from selection (e.g., "0.5 FOV" -> 0.5)
                try {
                    double multiplier = Double.parseDouble(selection.split(" ")[0]);
                    double minDim = Math.min(cachedFovUm[0], cachedFovUm[1]);
                    if (minDim <= 0) {
                        // FOV not yet queried, fetch it
                        queryFov.run();
                        minDim = Math.min(cachedFovUm[0], cachedFovUm[1]);
                    }
                    if (minDim > 0) {
                        int stepValue = (int) Math.round(minDim * multiplier);
                        xyStepField.setTextFormatter(null); // temporarily remove to set text
                        xyStepField.setText(String.valueOf(stepValue));
                        xyStepField.setTextFormatter(new TextFormatter<>(integerFilter));
                        xyStepField.setDisable(true);
                        // Update joystick max step: half the selected step per tick at full deflection
                        joystick.setMaxStepUm(stepValue * 0.5);
                        logger.debug("FOV step set to {} um ({} x min FOV {})", stepValue, multiplier, minDim);
                    }
                } catch (NumberFormatException ex) {
                    logger.warn("Failed to parse FOV multiplier from: {}", selection);
                }
            };

            fovStepCombo.setOnAction(e -> applyFovStep.run());
            refreshFovBtn.setOnAction(e -> {
                queryFov.run();
                applyFovStep.run();
            });

            // Update joystick max step when manual value changes
            xyStepField.textProperty().addListener((obs, oldVal, newVal) -> {
                if (!xyStepField.isDisabled()) {
                    try {
                        double step = Double.parseDouble(newVal.replace(",", ""));
                        joystick.setMaxStepUm(step);
                    } catch (NumberFormatException ignored) {
                        // Incomplete input, ignore
                    }
                }
            });

            Button upBtn = new Button("\u2191");  // Up arrow
            Button downBtn = new Button("\u2193");  // Down arrow
            Button leftBtn = new Button("\u2190");  // Left arrow
            Button rightBtn = new Button("\u2192");  // Right arrow

            // Style arrow buttons (smaller to fit around joystick)
            String arrowBtnStyle = "-fx-font-size: 12px; -fx-min-width: 28px; -fx-min-height: 28px; -fx-padding: 2;";
            upBtn.setStyle(arrowBtnStyle);
            downBtn.setStyle(arrowBtnStyle);
            leftBtn.setStyle(arrowBtnStyle);
            rightBtn.setStyle(arrowBtnStyle);

            // Sample movement checkbox - inverts Y direction when checked
            CheckBox sampleMovementCheckbox = new CheckBox("Sample movement");
            Tooltip.install(sampleMovementCheckbox, new Tooltip(
                    "When checked, controls move the sample rather than the stage.\n" +
                    "This inverts the Y direction to match visual expectations."));

            // Thread-safe flag for sample movement mode (used by joystick callback on background thread)
            // Initialize from checkbox's current state and keep in sync via listener
            final AtomicBoolean sampleMovementMode = new AtomicBoolean(sampleMovementCheckbox.isSelected());
            sampleMovementCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                sampleMovementMode.set(newVal);
                logger.info("Sample movement mode changed: {} -> {}", oldVal, newVal);
            });
            logger.debug("Sample movement checkbox initialized, selected={}", sampleMovementCheckbox.isSelected());

            upBtn.setOnAction(e -> {
                try {
                    double step = Double.parseDouble(xyStepField.getText().replace(",", ""));
                    double currentY = Double.parseDouble(yField.getText().replace(",", ""));
                    // Invert Y direction if sample movement is checked
                    boolean sampleMode = sampleMovementCheckbox.isSelected();
                    double yDirection = sampleMode ? -1 : 1;
                    logger.debug("UP button: sampleMovement={}, yDirection={}", sampleMode, yDirection);
                    double newY = currentY + (step * yDirection);
                    double currentX = Double.parseDouble(xField.getText().replace(",", ""));

                    if (!mgr.isWithinStageBounds(currentX, newY)) {
                        xyStatus.setText("Y+ move out of bounds");
                        return;
                    }

                    MicroscopeController.getInstance().moveStageXY(currentX, newY);
                    yField.setText(String.format("%.2f", newY));
                    xyStatus.setText(String.format("Moved Y+ to %.2f", newY));
                    logger.debug("Arrow Y+ movement to: {}", newY);
                } catch (Exception ex) {
                    logger.warn("Arrow Y+ movement failed: {}", ex.getMessage());
                    xyStatus.setText("Move failed: " + ex.getMessage());
                }
            });

            downBtn.setOnAction(e -> {
                try {
                    double step = Double.parseDouble(xyStepField.getText().replace(",", ""));
                    double currentY = Double.parseDouble(yField.getText().replace(",", ""));
                    // Invert Y direction if sample movement is checked
                    boolean sampleMode = sampleMovementCheckbox.isSelected();
                    double yDirection = sampleMode ? -1 : 1;
                    logger.debug("DOWN button: sampleMovement={}, yDirection={}", sampleMode, yDirection);
                    double newY = currentY - (step * yDirection);
                    double currentX = Double.parseDouble(xField.getText().replace(",", ""));

                    if (!mgr.isWithinStageBounds(currentX, newY)) {
                        xyStatus.setText("Y- move out of bounds");
                        return;
                    }

                    MicroscopeController.getInstance().moveStageXY(currentX, newY);
                    yField.setText(String.format("%.2f", newY));
                    xyStatus.setText(String.format("Moved Y- to %.2f", newY));
                    logger.debug("Arrow Y- movement to: {}", newY);
                } catch (Exception ex) {
                    logger.warn("Arrow Y- movement failed: {}", ex.getMessage());
                    xyStatus.setText("Move failed: " + ex.getMessage());
                }
            });

            leftBtn.setOnAction(e -> {
                try {
                    double step = Double.parseDouble(xyStepField.getText().replace(",", ""));
                    double currentX = Double.parseDouble(xField.getText().replace(",", ""));
                    // Invert X direction if sample movement is checked
                    boolean sampleMode = sampleMovementCheckbox.isSelected();
                    double xDirection = sampleMode ? -1 : 1;
                    double newX = currentX - (step * xDirection);
                    double currentY = Double.parseDouble(yField.getText().replace(",", ""));
                    logger.debug("LEFT button: sampleMovement={}, xDirection={}", sampleMode, xDirection);

                    if (!mgr.isWithinStageBounds(newX, currentY)) {
                        xyStatus.setText("X- move out of bounds");
                        return;
                    }

                    MicroscopeController.getInstance().moveStageXY(newX, currentY);
                    xField.setText(String.format("%.2f", newX));
                    xyStatus.setText(String.format("Moved X- to %.2f", newX));
                    logger.debug("Arrow X- movement to: {}", newX);
                } catch (Exception ex) {
                    logger.warn("Arrow X- movement failed: {}", ex.getMessage());
                    xyStatus.setText("Move failed: " + ex.getMessage());
                }
            });

            rightBtn.setOnAction(e -> {
                try {
                    double step = Double.parseDouble(xyStepField.getText().replace(",", ""));
                    double currentX = Double.parseDouble(xField.getText().replace(",", ""));
                    // Invert X direction if sample movement is checked
                    boolean sampleMode = sampleMovementCheckbox.isSelected();
                    double xDirection = sampleMode ? -1 : 1;
                    double newX = currentX + (step * xDirection);
                    double currentY = Double.parseDouble(yField.getText().replace(",", ""));
                    logger.debug("RIGHT button: sampleMovement={}, xDirection={}", sampleMode, xDirection);

                    if (!mgr.isWithinStageBounds(newX, currentY)) {
                        xyStatus.setText("X+ move out of bounds");
                        return;
                    }

                    MicroscopeController.getInstance().moveStageXY(newX, currentY);
                    xField.setText(String.format("%.2f", newX));
                    xyStatus.setText(String.format("Moved X+ to %.2f", newX));
                    logger.debug("Arrow X+ movement to: {}", newX);
                } catch (Exception ex) {
                    logger.warn("Arrow X+ movement failed: {}", ex.getMessage());
                    xyStatus.setText("Move failed: " + ex.getMessage());
                }
            });

            // --- Step Settings Section (applies to both arrows and joystick) ---
            Label stepSettingsLabel = new Label("XY Step Settings");
            stepSettingsLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");

            Label stepLabel = new Label(res.getString("stageMovement.label.step"));
            HBox fovControlsRow = new HBox(5, stepLabel, fovStepCombo, refreshFovBtn, fovInfoLabel);
            fovControlsRow.setAlignment(Pos.CENTER_LEFT);

            VBox stepSettingsSection = new VBox(4);
            stepSettingsSection.setAlignment(Pos.CENTER_LEFT);
            stepSettingsSection.getChildren().addAll(stepSettingsLabel, fovControlsRow, valueRow, sampleMovementCheckbox);
            stepSettingsSection.setStyle("-fx-padding: 5; -fx-border-color: #cccccc; -fx-border-radius: 3; -fx-background-color: #f8f8f8; -fx-background-radius: 3;");

            // --- Navigation Control: arrows around joystick ---
            Label navLabel = new Label("XY Navigation");
            navLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");

            Label keyboardHint = new Label("WASD / Arrow keys / Drag joystick");
            keyboardHint.setStyle("-fx-font-size: 9px; -fx-text-fill: #666666;");

            // Arrange arrows around the joystick using GridPane
            GridPane navGrid = new GridPane();
            navGrid.setAlignment(Pos.CENTER);
            navGrid.setHgap(2);
            navGrid.setVgap(2);
            // Row 0: up button centered
            navGrid.add(upBtn, 1, 0);
            GridPane.setHalignment(upBtn, HPos.CENTER);
            // Row 1: left, joystick, right
            navGrid.add(leftBtn, 0, 1);
            GridPane.setValignment(leftBtn, VPos.CENTER);
            navGrid.add(joystick, 1, 1);
            navGrid.add(rightBtn, 2, 1);
            GridPane.setValignment(rightBtn, VPos.CENTER);
            // Row 2: down button centered
            navGrid.add(downBtn, 1, 2);
            GridPane.setHalignment(downBtn, HPos.CENTER);

            // --- Wire joystick movement callback ---
            // Track position atomically to avoid race conditions between background executor and JavaFX thread
            double initialX, initialY;
            try {
                initialX = Double.parseDouble(xField.getText().replace(",", ""));
                initialY = Double.parseDouble(yField.getText().replace(",", ""));
            } catch (NumberFormatException e) {
                initialX = 0;
                initialY = 0;
            }
            final AtomicReference<double[]> joystickPosition = new AtomicReference<>(
                    new double[]{initialX, initialY});

            // Sync position from text fields when user starts dragging
            // This ensures the joystick picks up from wherever the stage actually is
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
                try {
                    // Read current position atomically (thread-safe)
                    double[] current = joystickPosition.get();
                    double currentX = current[0];
                    double currentY = current[1];

                    // Invert X for sample movement mode (read from atomic flag, not JavaFX control)
                    boolean sampleMode = sampleMovementMode.get();
                    double xDir = sampleMode ? -1 : 1;
                    logger.debug("Joystick: sampleMovement={}, xDir={}", sampleMode, xDir);
                    double targetX = currentX + (deltaX * xDir);
                    double targetY = currentY + deltaY;

                    if (!mgr.isWithinStageBounds(targetX, targetY)) {
                        Platform.runLater(() -> xyStatus.setText(res.getString("stageMovement.joystick.boundary")));
                        return;
                    }

                    // Update atomic position BEFORE sending command (latest-wins semantics)
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
            });

            // Navigation section with arrows around joystick
            VBox navigationSection = new VBox(6);
            navigationSection.setAlignment(Pos.CENTER);
            navigationSection.getChildren().addAll(navLabel, navGrid, keyboardHint);
            navigationSection.setStyle("-fx-padding: 5; -fx-border-color: #cccccc; -fx-border-radius: 3; -fx-background-color: #f8f8f8; -fx-background-radius: 3;");

            // Combined XY controls section
            VBox xyControlsSection = new VBox(10);
            xyControlsSection.setAlignment(Pos.CENTER_LEFT);
            xyControlsSection.getChildren().addAll(stepSettingsSection, navigationSection);

            // --- Z Scroll Control ---
            logger.debug("Adding Z scroll wheel control");
            TextField zStepField = new TextField("10");
            zStepField.setPrefWidth(50);
            zStepField.setAlignment(Pos.CENTER);
            Tooltip.install(zStepField, new Tooltip("Z step size (um, integers only) - use mouse wheel over Z controls"));

            // Input validation: only allow digits and commas (no decimals)
            zStepField.setTextFormatter(new TextFormatter<>(integerFilter));

            Label zScrollLabel = new Label("Z step:");
            zScrollLabel.setStyle("-fx-font-size: 10px;");

            // Help button with tooltip explaining Z scroll
            Button zHelpBtn = new Button("?");
            zHelpBtn.setStyle("-fx-font-size: 10px; -fx-min-width: 20px; -fx-min-height: 20px; -fx-padding: 0;");
            Tooltip zHelpTooltip = new Tooltip(
                    "Z SCROLL CONTROL\n\n" +
                    "Use mouse scroll wheel to move Z axis:\n" +
                    "- Hover over Z field, Move Z button, or Z step field\n" +
                    "- Scroll UP to INCREASE Z (move up)\n" +
                    "- Scroll DOWN to DECREASE Z (move down)\n\n" +
                    "The Z step field sets how far each scroll moves."
            );
            zHelpTooltip.setShowDelay(javafx.util.Duration.ZERO);
            zHelpTooltip.setShowDuration(javafx.util.Duration.INDEFINITE);
            Tooltip.install(zHelpBtn, zHelpTooltip);
            zHelpBtn.setOnAction(e -> {
                // Show tooltip when clicked as well
                Tooltip.install(zHelpBtn, zHelpTooltip);
            });

            HBox zScrollBox = new HBox(5, zScrollLabel, zStepField, new Label("um"), zHelpBtn);
            zScrollBox.setAlignment(Pos.CENTER_LEFT);

            // Add scroll handler to Z field and button
            javafx.event.EventHandler<ScrollEvent> zScrollHandler = event -> {
                try {
                    double step = Double.parseDouble(zStepField.getText().replace(",", ""));
                    double currentZ = Double.parseDouble(zField.getText().replace(",", ""));

                    // Scroll up = positive delta = move Z up (increase)
                    // Scroll down = negative delta = move Z down (decrease)
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
                    zStatus.setText("Z scroll failed: " + ex.getMessage());
                }
                event.consume();
            };

            zField.setOnScroll(zScrollHandler);
            moveZBtn.setOnScroll(zScrollHandler);
            zStepField.setOnScroll(zScrollHandler);

            // --- Layout ---
            logger.debug("Creating and configuring dialog layout grid");
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(20));

            // XY
            logger.debug("Adding XY control components to dialog grid");
            grid.add(new Label(res.getString("stageMovement.label.x")), 0, 0);
            grid.add(xField, 1, 0);
            grid.add(new Label(res.getString("stageMovement.label.y")), 0, 1);
            grid.add(yField, 1, 1);
            grid.add(moveXYBtn, 2, 0, 1, 2);
            grid.add(xyStatus, 1, 2, 2, 1);

            // Z with scroll control
            logger.debug("Adding Z control components to dialog grid");
            grid.add(new Label(res.getString("stageMovement.label.z")), 0, 3);
            grid.add(zField, 1, 3);
            grid.add(moveZBtn, 2, 3);
            grid.add(zScrollBox, 3, 3);  // Z step control with scroll wheel hint
            grid.add(zStatus, 1, 4, 3, 1);

            // R
            logger.debug("Adding R control components to dialog grid");
            grid.add(new Label(res.getString("stageMovement.label.r")), 0, 5);
            grid.add(rField, 1, 5);
            grid.add(moveRBtn, 2, 5);
            grid.add(rStatus, 1, 6, 3, 1);

            // --- Go to object centroid button and arrow controls ---
            logger.debug("Adding 'Go to object centroid' button and XY arrow controls");
            Separator separator = new Separator();
            separator.setPadding(new Insets(10, 0, 10, 0));
            grid.add(separator, 0, 7, 4, 1);

            Button goToCentroidBtn = new Button("Go to Object Centroid");
            Label centroidStatus = new Label();

            // Check conditions for enabling the button
            QuPathGUI gui = QuPathGUI.getInstance();

            // Try to get alignment - first from MicroscopeController, then from slide-specific storage
            AffineTransform currentTransform = MicroscopeController.getInstance().getCurrentTransform();

            // If no active transform, try to load slide-specific alignment for current image
            if (currentTransform == null && gui != null && gui.getProject() != null && gui.getImageData() != null) {
                logger.debug("No active transform, checking for slide-specific alignment");
                try {
                    @SuppressWarnings("unchecked")
                    Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();

                    // Use actual image file name - this is how alignments are saved
                    String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                    if (imageName != null && !imageName.isEmpty()) {
                        logger.debug("Looking for slide-specific alignment for image: {}", imageName);
                        AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(project, imageName);

                        if (slideTransform != null) {
                            currentTransform = slideTransform;
                            // Also set it in MicroscopeController for use in the action handler
                            MicroscopeController.getInstance().setCurrentTransform(slideTransform);
                            logger.info("Loaded slide-specific alignment for image: {}", imageName);
                        } else {
                            logger.debug("No slide-specific alignment found for image: {}", imageName);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load slide-specific alignment: {}", e.getMessage());
                }
            }

            // Initial state - check if we have an alignment
            boolean hasAlignment = currentTransform != null;

            // Create a container for available alignments list (shown when no alignment for current image)
            ListView<String> alignmentListView = new ListView<>();
            alignmentListView.setPrefHeight(100);
            alignmentListView.setPrefWidth(280);
            alignmentListView.setVisible(false);
            alignmentListView.setManaged(false);

            Label availableLabel = new Label("Images with alignments:");
            availableLabel.setVisible(false);
            availableLabel.setManaged(false);

            if (!hasAlignment) {
                goToCentroidBtn.setDisable(true);

                // Get list of available alignments
                String currentImageName = gui != null && gui.getImageData() != null
                        ? QPProjectFunctions.getActualImageFileName(gui.getImageData())
                        : "unknown";

                @SuppressWarnings("unchecked")
                Project<BufferedImage> projectForList = gui != null && gui.getProject() != null
                        ? (Project<BufferedImage>) gui.getProject()
                        : null;
                List<String> availableAlignments = getAvailableAlignments(projectForList);

                if (availableAlignments.isEmpty()) {
                    centroidStatus.setText("No alignments available in project");
                } else {
                    centroidStatus.setText("No alignment for: " + currentImageName);

                    // Show the list of available alignments
                    availableLabel.setVisible(true);
                    availableLabel.setManaged(true);
                    alignmentListView.setVisible(true);
                    alignmentListView.setManaged(true);
                    alignmentListView.getItems().addAll(availableAlignments);
                }

                logger.debug("Go to centroid button disabled - no alignment for current image. {} alignments available in project.",
                        availableAlignments.size());
            } else {
                logger.debug("Go to centroid button enabled - alignment available");
            }

            goToCentroidBtn.setOnAction(e -> {
                logger.debug("Go to object centroid button clicked");
                try {
                    // Re-check transform in case it was set after dialog opened
                    AffineTransform transform = MicroscopeController.getInstance().getCurrentTransform();

                    // If still no transform, try slide-specific alignment again
                    if (transform == null && gui != null && gui.getProject() != null && gui.getImageData() != null) {
                        try {
                            @SuppressWarnings("unchecked")
                            Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();
                            String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                            if (imageName != null && !imageName.isEmpty()) {
                                transform = AffineTransformManager.loadSlideAlignment(project, imageName);
                                if (transform != null) {
                                    MicroscopeController.getInstance().setCurrentTransform(transform);
                                    logger.info("Loaded slide-specific alignment on button click for: {}", imageName);
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

                    // Get selected object
                    if (gui == null || gui.getImageData() == null) {
                        centroidStatus.setText("No image open");
                        UIFunctions.notifyUserOfError(
                                "No image is currently open.",
                                "Go to Centroid");
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
                        UIFunctions.notifyUserOfError(
                                "The selected object has no region of interest.",
                                "Go to Centroid");
                        return;
                    }

                    // Get centroid coordinates from QuPath
                    double centroidX = selectedObject.getROI().getCentroidX();
                    double centroidY = selectedObject.getROI().getCentroidY();
                    logger.info("Object centroid in QuPath coordinates: ({}, {})", centroidX, centroidY);

                    // Transform to stage coordinates
                    double[] qpCoords = {centroidX, centroidY};
                    double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                            qpCoords, transform);
                    logger.info("Transformed to stage coordinates: ({}, {})", stageCoords[0], stageCoords[1]);

                    // Validate stage bounds
                    if (!mgr.isWithinStageBounds(stageCoords[0], stageCoords[1])) {
                        logger.warn("Centroid position out of stage bounds: ({}, {})", stageCoords[0], stageCoords[1]);
                        UIFunctions.notifyUserOfError(
                                "The object centroid position is outside the stage bounds.",
                                "Go to Centroid");
                        centroidStatus.setText("Position out of bounds");
                        return;
                    }

                    // Move stage to centroid position (XY only, no Z change)
                    logger.info("Moving stage to object centroid: ({}, {})", stageCoords[0], stageCoords[1]);
                    MicroscopeController.getInstance().moveStageXY(stageCoords[0], stageCoords[1]);

                    // Update status and XY fields to reflect new position
                    centroidStatus.setText(String.format("Moved to (%.2f, %.2f)", stageCoords[0], stageCoords[1]));
                    xField.setText(String.format("%.2f", stageCoords[0]));
                    yField.setText(String.format("%.2f", stageCoords[1]));
                    xyStatus.setText(String.format("Moved to object centroid (%.2f, %.2f)", stageCoords[0], stageCoords[1]));
                    logger.info("Successfully moved to object centroid");

                } catch (Exception ex) {
                    logger.error("Failed to move to object centroid: {}", ex.getMessage(), ex);
                    centroidStatus.setText("Error: " + ex.getMessage());
                    UIFunctions.notifyUserOfError(
                            "Failed to move to centroid: " + ex.getMessage(),
                            "Go to Centroid");
                }
            });

            grid.add(goToCentroidBtn, 0, 8, 2, 1);
            grid.add(xyControlsSection, 0, 9, 4, 1);
            grid.add(centroidStatus, 2, 8, 2, 1);
            grid.add(availableLabel, 0, 10, 4, 1);
            grid.add(alignmentListView, 0, 11, 4, 1);

            // Try to pre-fetch FOV on dialog open (non-blocking)
            try {
                double[] fov = MicroscopeController.getInstance().getCameraFOV();
                cachedFovUm[0] = fov[0];
                cachedFovUm[1] = fov[1];
                fovInfoLabel.setText(String.format(res.getString("stageMovement.fov.format"), fov[0], fov[1]));
            } catch (Exception fovEx) {
                logger.debug("FOV pre-fetch failed (microscope may not be connected): {}", fovEx.getMessage());
            }

            // Wrap grid in VBox with warning at top
            VBox contentBox = new VBox(5);
            contentBox.getChildren().addAll(warningLabel, grid);

            logger.debug("Finalizing dialog configuration and displaying");
            dlg.getDialogPane().setContent(contentBox);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dlg.initModality(Modality.NONE);
            if (gui != null && gui.getStage() != null) {
                dlg.initOwner(gui.getStage());
            }

            // Add WASD and arrow key support for XY movement
            // Using event filter to capture keys before text fields consume them
            dlg.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                // Only respond if focus is not on a text field (to allow typing in fields)
                if (event.getTarget() instanceof TextField) {
                    return;
                }

                KeyCode code = event.getCode();
                switch (code) {
                    case W:
                    case UP:
                        event.consume();
                        upBtn.fire();
                        logger.debug("Keyboard: {} triggered up movement", code);
                        break;
                    case S:
                    case DOWN:
                        event.consume();
                        downBtn.fire();
                        logger.debug("Keyboard: {} triggered down movement", code);
                        break;
                    case A:
                    case LEFT:
                        event.consume();
                        leftBtn.fire();
                        logger.debug("Keyboard: {} triggered left movement", code);
                        break;
                    case D:
                    case RIGHT:
                        event.consume();
                        rightBtn.fire();
                        logger.debug("Keyboard: {} triggered right movement", code);
                        break;
                    default:
                        break;
                }
            });
            logger.debug("WASD and arrow key controls enabled for XY movement");

            // Add listener to refresh alignment status when image changes
            if (gui != null) {
                javafx.beans.value.ChangeListener<qupath.lib.images.ImageData<?>> imageChangeListener =
                    (obs, oldImage, newImage) -> {
                        logger.debug("Image changed in viewer, refreshing alignment status");
                        refreshAlignmentStatus(gui, goToCentroidBtn, centroidStatus,
                                availableLabel, alignmentListView, mgr);
                    };
                gui.imageDataProperty().addListener(imageChangeListener);

                // Remove listener and stop joystick when dialog closes to prevent leaks
                dlg.setOnHidden(event -> {
                    gui.imageDataProperty().removeListener(imageChangeListener);
                    joystick.stop();
                    activeDialog = null;  // Clear singleton reference
                    logger.debug("Removed image change listener and stopped joystick on dialog close");
                });
            } else {
                // Still stop joystick on close even without GUI
                dlg.setOnHidden(event -> {
                    joystick.stop();
                    activeDialog = null;  // Clear singleton reference
                    logger.debug("Stopped joystick on dialog close");
                });
            }

            // Make dialog always on top but not blocking
            dlg.setOnShown(event -> {
                Stage stage = (Stage) dlg.getDialogPane().getScene().getWindow();
                stage.setAlwaysOnTop(true);
                logger.debug("Stage movement dialog set to always on top");
            });

            dlg.show();
            logger.info("Stage movement dialog displayed successfully");
        });
    }

    /**
     * Gets a list of image names that have saved alignments in the project.
     *
     * @param project The QuPath project
     * @return List of image names with alignments, or empty list if none found
     */
    private static List<String> getAvailableAlignments(Project<BufferedImage> project) {
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
                    // Extract image name from filename (remove _alignment.json suffix)
                    String fileName = file.getName();
                    String imageName = fileName.substring(0, fileName.length() - "_alignment.json".length());
                    alignedImages.add(imageName);
                }
            }

            logger.debug("Found {} images with alignments in project", alignedImages.size());
        } catch (Exception e) {
            logger.warn("Error listing available alignments: {}", e.getMessage());
        }

        return alignedImages;
    }

    /**
     * Refreshes the alignment status for the Go to Centroid button when the image changes.
     * Updates button enabled state and available alignments list.
     *
     * @param gui The QuPath GUI instance
     * @param goToCentroidBtn The Go to Centroid button
     * @param centroidStatus The status label
     * @param availableLabel The label for available alignments
     * @param alignmentListView The list view for available alignments
     * @param mgr The config manager (for validation, passed but not used in this method)
     */
    private static void refreshAlignmentStatus(QuPathGUI gui, Button goToCentroidBtn,
            Label centroidStatus, Label availableLabel, ListView<String> alignmentListView,
            MicroscopeConfigManager mgr) {

        // Reset state
        AffineTransform currentTransform = null;
        goToCentroidBtn.setDisable(true);
        alignmentListView.getItems().clear();

        if (gui == null || gui.getProject() == null) {
            centroidStatus.setText("No project open");
            availableLabel.setVisible(false);
            availableLabel.setManaged(false);
            alignmentListView.setVisible(false);
            alignmentListView.setManaged(false);
            return;
        }

        // Try to load slide-specific alignment for current image
        if (gui.getImageData() != null) {
            try {
                @SuppressWarnings("unchecked")
                Project<BufferedImage> project = (Project<BufferedImage>) gui.getProject();

                String imageName = QPProjectFunctions.getActualImageFileName(gui.getImageData());
                if (imageName != null && !imageName.isEmpty()) {
                    logger.debug("Checking alignment for image: {}", imageName);
                    AffineTransform slideTransform = AffineTransformManager.loadSlideAlignment(project, imageName);

                    if (slideTransform != null) {
                        currentTransform = slideTransform;
                        MicroscopeController.getInstance().setCurrentTransform(slideTransform);
                        logger.info("Loaded alignment for image: {}", imageName);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to load alignment: {}", e.getMessage());
            }
        }

        // Update UI based on alignment availability
        if (currentTransform != null) {
            goToCentroidBtn.setDisable(false);
            centroidStatus.setText("Alignment available");
            availableLabel.setVisible(false);
            availableLabel.setManaged(false);
            alignmentListView.setVisible(false);
            alignmentListView.setManaged(false);
            logger.debug("Go to centroid button enabled - alignment found");
        } else {
            String currentImageName = gui.getImageData() != null
                    ? QPProjectFunctions.getActualImageFileName(gui.getImageData())
                    : "no image";

            @SuppressWarnings("unchecked")
            Project<BufferedImage> projectForList = (Project<BufferedImage>) gui.getProject();
            List<String> availableAlignments = getAvailableAlignments(projectForList);

            if (availableAlignments.isEmpty()) {
                centroidStatus.setText("No alignments available in project");
                availableLabel.setVisible(false);
                availableLabel.setManaged(false);
                alignmentListView.setVisible(false);
                alignmentListView.setManaged(false);
            } else {
                centroidStatus.setText("No alignment for: " + currentImageName);
                availableLabel.setVisible(true);
                availableLabel.setManaged(true);
                alignmentListView.setVisible(true);
                alignmentListView.setManaged(true);
                alignmentListView.getItems().addAll(availableAlignments);
            }
            logger.debug("Go to centroid button disabled - no alignment for current image");
        }
    }
}