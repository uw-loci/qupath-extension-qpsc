package qupath.ext.qpsc.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Configuration dialog for the WB Comparison Test workflow.
 *
 * <p>Collects:
 * <ul>
 *   <li>Blank position (X, Y) for calibration and backgrounds</li>
 *   <li>Tissue center position (X, Y) for acquisition</li>
 *   <li>Grid size (columns x rows)</li>
 *   <li>Which WB modes to compare (camera_awb, simple, per_angle)</li>
 *   <li>Sample name and output folder</li>
 *   <li>Advanced settings (overlap, target intensity, autofocus params)</li>
 * </ul>
 *
 * <p>All position and output values are persisted via QuPath preferences.
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class WBComparisonDialog {

    private static final Logger logger = LoggerFactory.getLogger(WBComparisonDialog.class);

    // Persistent preferences for positions and settings
    private static final javafx.beans.property.DoubleProperty blankXProp =
            PathPrefs.createPersistentPreference("wbComparison.blankX", Double.NaN);
    private static final javafx.beans.property.DoubleProperty blankYProp =
            PathPrefs.createPersistentPreference("wbComparison.blankY", Double.NaN);
    private static final javafx.beans.property.DoubleProperty blankZProp =
            PathPrefs.createPersistentPreference("wbComparison.blankZ", Double.NaN);
    private static final javafx.beans.property.DoubleProperty tissueXProp =
            PathPrefs.createPersistentPreference("wbComparison.tissueX", Double.NaN);
    private static final javafx.beans.property.DoubleProperty tissueYProp =
            PathPrefs.createPersistentPreference("wbComparison.tissueY", Double.NaN);
    private static final javafx.beans.property.DoubleProperty tissueZProp =
            PathPrefs.createPersistentPreference("wbComparison.tissueZ", Double.NaN);
    private static final javafx.beans.property.IntegerProperty gridColsProp =
            PathPrefs.createPersistentPreference("wbComparison.gridCols", 3);
    private static final javafx.beans.property.IntegerProperty gridRowsProp =
            PathPrefs.createPersistentPreference("wbComparison.gridRows", 3);
    private static final javafx.beans.property.StringProperty outputFolderProp =
            PathPrefs.createPersistentPreference("wbComparison.outputFolder", "");
    private static final javafx.beans.property.DoubleProperty targetIntensityProp =
            PathPrefs.createPersistentPreference("wbComparison.targetIntensity", 180.0);
    private static final javafx.beans.property.IntegerProperty afTilesProp =
            PathPrefs.createPersistentPreference("wbComparison.afTiles", 9);
    private static final javafx.beans.property.IntegerProperty afStepsProp =
            PathPrefs.createPersistentPreference("wbComparison.afSteps", 10);
    private static final javafx.beans.property.DoubleProperty afRangeProp =
            PathPrefs.createPersistentPreference("wbComparison.afRange", 50.0);

    /**
     * Result record containing all dialog parameters.
     */
    public record WBComparisonParams(
            double blankX, double blankY, double blankZ,
            double tissueX, double tissueY, double tissueZ,
            int gridCols, int gridRows,
            boolean doCameraAWB, boolean doSimple, boolean doPerAngle,
            String sampleName, String outputFolder,
            double overlapPercent, double targetIntensity,
            int afTiles, int afSteps, double afRange
    ) {
        /** Returns the list of selected WB mode names. */
        public List<String> selectedModes() {
            List<String> modes = new ArrayList<>();
            if (doCameraAWB) modes.add("camera_awb");
            if (doSimple) modes.add("simple");
            if (doPerAngle) modes.add("per_angle");
            return modes;
        }
    }

    /**
     * Shows the WB Comparison configuration dialog.
     *
     * @return CompletableFuture with dialog parameters, or null if cancelled
     */
    public static CompletableFuture<WBComparisonParams> showDialog() {
        CompletableFuture<WBComparisonParams> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                Dialog<WBComparisonParams> dialog = buildDialog();
                dialog.showAndWait().ifPresentOrElse(
                        future::complete,
                        () -> future.complete(null)
                );
            } catch (Exception e) {
                logger.error("Failed to show WB Comparison dialog", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private static Dialog<WBComparisonParams> buildDialog() {
        Dialog<WBComparisonParams> dialog = new Dialog<>();
        dialog.setTitle("WB Comparison Test");
        dialog.setHeaderText("Compare white balance modes under identical conditions");
        dialog.setResizable(true);

        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            dialog.initOwner(gui.getStage());
        }

        // === Position fields ===
        TextField blankXField = new TextField();
        TextField blankYField = new TextField();
        TextField blankZField = new TextField();
        TextField tissueXField = new TextField();
        TextField tissueYField = new TextField();
        TextField tissueZField = new TextField();

        blankXField.setPrefColumnCount(10);
        blankYField.setPrefColumnCount(10);
        blankZField.setPrefColumnCount(10);
        tissueXField.setPrefColumnCount(10);
        tissueYField.setPrefColumnCount(10);
        tissueZField.setPrefColumnCount(10);

        // Populate from preferences if valid
        if (!Double.isNaN(blankXProp.get())) blankXField.setText(String.format("%.2f", blankXProp.get()));
        if (!Double.isNaN(blankYProp.get())) blankYField.setText(String.format("%.2f", blankYProp.get()));
        if (!Double.isNaN(blankZProp.get())) blankZField.setText(String.format("%.2f", blankZProp.get()));
        if (!Double.isNaN(tissueXProp.get())) tissueXField.setText(String.format("%.2f", tissueXProp.get()));
        if (!Double.isNaN(tissueYProp.get())) tissueYField.setText(String.format("%.2f", tissueYProp.get()));
        if (!Double.isNaN(tissueZProp.get())) tissueZField.setText(String.format("%.2f", tissueZProp.get()));

        Button useCurrentBlank = new Button("Use Current Position");
        useCurrentBlank.setOnAction(e -> fillCurrentPosition(blankXField, blankYField, blankZField));

        Button useCurrentTissue = new Button("Use Current Position");
        useCurrentTissue.setOnAction(e -> fillCurrentPosition(tissueXField, tissueYField, tissueZField));

        GridPane posGrid = new GridPane();
        posGrid.setHgap(8);
        posGrid.setVgap(6);

        posGrid.add(new Label("Blank Position:"), 0, 0, 3, 1);
        posGrid.add(new Label("X:"), 0, 1);
        posGrid.add(blankXField, 1, 1);
        posGrid.add(new Label("Y:"), 0, 2);
        posGrid.add(blankYField, 1, 2);
        posGrid.add(new Label("Z:"), 0, 3);
        posGrid.add(blankZField, 1, 3);
        posGrid.add(useCurrentBlank, 2, 1, 1, 3);

        posGrid.add(new Separator(), 0, 4, 3, 1);

        posGrid.add(new Label("Tissue Center:"), 0, 5, 3, 1);
        posGrid.add(new Label("X:"), 0, 6);
        posGrid.add(tissueXField, 1, 6);
        posGrid.add(new Label("Y:"), 0, 7);
        posGrid.add(tissueYField, 1, 7);
        posGrid.add(new Label("Z:"), 0, 8);
        posGrid.add(tissueZField, 1, 8);
        posGrid.add(useCurrentTissue, 2, 6, 1, 3);

        // Grid size
        Spinner<Integer> colsSpinner = new Spinner<>(1, 20, gridColsProp.get());
        Spinner<Integer> rowsSpinner = new Spinner<>(1, 20, gridRowsProp.get());
        colsSpinner.setEditable(true);
        rowsSpinner.setEditable(true);
        colsSpinner.setPrefWidth(80);
        rowsSpinner.setPrefWidth(80);

        HBox gridSizeBox = new HBox(6,
                new Label("Grid size:"),
                colsSpinner, new Label("x"), rowsSpinner, new Label("tiles"));
        gridSizeBox.setAlignment(Pos.CENTER_LEFT);

        TitledPane positionsPane = new TitledPane("Positions", new VBox(8, posGrid, gridSizeBox));
        positionsPane.setCollapsible(false);

        // === WB Mode checkboxes ===
        CheckBox cbCameraAWB = new CheckBox("Camera AWB");
        CheckBox cbSimple = new CheckBox("Simple");
        CheckBox cbPerAngle = new CheckBox("Per-Angle (PPM)");
        cbCameraAWB.setSelected(true);
        cbSimple.setSelected(true);
        cbPerAngle.setSelected(true);

        TitledPane modesPane = new TitledPane("White Balance Modes",
                new VBox(4, cbCameraAWB, cbSimple, cbPerAngle));
        modesPane.setCollapsible(false);

        // === Output settings ===
        String defaultName = "wb_comparison_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        TextField sampleNameField = new TextField(defaultName);

        TextField outputFolderField = new TextField(outputFolderProp.get());
        outputFolderField.setPrefColumnCount(25);

        // Default to QuPath projects folder if empty
        if (outputFolderField.getText().isEmpty()) {
            String defaultFolder = QPPreferenceDialog.getProjectsFolderProperty();
            if (defaultFolder != null && !defaultFolder.isEmpty()) {
                outputFolderField.setText(defaultFolder);
            }
        }

        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Output Folder");
            String current = outputFolderField.getText();
            if (current != null && !current.isEmpty()) {
                File dir = new File(current);
                if (dir.isDirectory()) chooser.setInitialDirectory(dir);
            }
            Stage ownerStage = gui != null ? gui.getStage() : null;
            File selected = chooser.showDialog(ownerStage);
            if (selected != null) {
                outputFolderField.setText(selected.getAbsolutePath());
            }
        });

        GridPane outputGrid = new GridPane();
        outputGrid.setHgap(8);
        outputGrid.setVgap(6);
        outputGrid.add(new Label("Sample name:"), 0, 0);
        outputGrid.add(sampleNameField, 1, 0);
        outputGrid.add(new Label("Output folder:"), 0, 1);
        outputGrid.add(outputFolderField, 1, 1);
        outputGrid.add(browseButton, 2, 1);

        TitledPane outputPane = new TitledPane("Output", outputGrid);
        outputPane.setCollapsible(false);

        // === Advanced settings (collapsed) ===
        double defaultOverlap = QPPreferenceDialog.getTileOverlapPercentProperty();

        TextField overlapField = new TextField(String.format("%.1f", defaultOverlap));
        overlapField.setPrefColumnCount(6);

        TextField targetIntensityField = new TextField(String.format("%.0f", targetIntensityProp.get()));
        targetIntensityField.setPrefColumnCount(6);

        Spinner<Integer> afTilesSpinner = new Spinner<>(1, 25, afTilesProp.get());
        Spinner<Integer> afStepsSpinner = new Spinner<>(1, 50, afStepsProp.get());
        afTilesSpinner.setPrefWidth(80);
        afStepsSpinner.setPrefWidth(80);
        afTilesSpinner.setEditable(true);
        afStepsSpinner.setEditable(true);

        TextField afRangeField = new TextField(String.format("%.0f", afRangeProp.get()));
        afRangeField.setPrefColumnCount(6);

        GridPane advGrid = new GridPane();
        advGrid.setHgap(8);
        advGrid.setVgap(6);
        advGrid.add(new Label("Overlap %:"), 0, 0);
        advGrid.add(overlapField, 1, 0);
        advGrid.add(new Label("Target intensity:"), 0, 1);
        advGrid.add(targetIntensityField, 1, 1);
        advGrid.add(new Label("AF tiles:"), 0, 2);
        advGrid.add(afTilesSpinner, 1, 2);
        advGrid.add(new Label("AF steps:"), 0, 3);
        advGrid.add(afStepsSpinner, 1, 3);
        advGrid.add(new Label("AF range (um):"), 0, 4);
        advGrid.add(afRangeField, 1, 4);

        TitledPane advancedPane = new TitledPane("Advanced", advGrid);
        advancedPane.setExpanded(false);

        // === Layout ===
        VBox content = new VBox(10, positionsPane, modesPane, outputPane, advancedPane);
        content.setPadding(new Insets(10));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(500);

        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().setPrefWidth(550);

        // Buttons
        ButtonType runButton = new ButtonType("Run Comparison", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(runButton, ButtonType.CANCEL);

        // Result converter
        dialog.setResultConverter(buttonType -> {
            if (buttonType != runButton) return null;

            try {
                double blankX = parseDouble(blankXField.getText(), "Blank X");
                double blankY = parseDouble(blankYField.getText(), "Blank Y");
                double blankZ = parseDouble(blankZField.getText(), "Blank Z");
                double tissueX = parseDouble(tissueXField.getText(), "Tissue X");
                double tissueY = parseDouble(tissueYField.getText(), "Tissue Y");
                double tissueZ = parseDouble(tissueZField.getText(), "Tissue Z");
                int cols = colsSpinner.getValue();
                int rows = rowsSpinner.getValue();
                double overlap = parseDouble(overlapField.getText(), "Overlap");
                double targetIntensity = parseDouble(targetIntensityField.getText(), "Target intensity");
                double afRange = parseDouble(afRangeField.getText(), "AF range");

                boolean anyMode = cbCameraAWB.isSelected() || cbSimple.isSelected() || cbPerAngle.isSelected();
                if (!anyMode) {
                    qupath.fx.dialogs.Dialogs.showErrorMessage("WB Comparison",
                            "Please select at least one white balance mode.");
                    return null;
                }

                String sampleName = sampleNameField.getText().trim();
                if (sampleName.isEmpty()) {
                    qupath.fx.dialogs.Dialogs.showErrorMessage("WB Comparison",
                            "Please enter a sample name.");
                    return null;
                }

                String outputFolder = outputFolderField.getText().trim();
                if (outputFolder.isEmpty()) {
                    qupath.fx.dialogs.Dialogs.showErrorMessage("WB Comparison",
                            "Please select an output folder.");
                    return null;
                }

                // Persist values
                blankXProp.set(blankX);
                blankYProp.set(blankY);
                blankZProp.set(blankZ);
                tissueXProp.set(tissueX);
                tissueYProp.set(tissueY);
                tissueZProp.set(tissueZ);
                gridColsProp.set(cols);
                gridRowsProp.set(rows);
                outputFolderProp.set(outputFolder);
                targetIntensityProp.set(targetIntensity);
                afTilesProp.set(afTilesSpinner.getValue());
                afStepsProp.set(afStepsSpinner.getValue());
                afRangeProp.set(afRange);

                return new WBComparisonParams(
                        blankX, blankY, blankZ, tissueX, tissueY, tissueZ,
                        cols, rows,
                        cbCameraAWB.isSelected(), cbSimple.isSelected(), cbPerAngle.isSelected(),
                        sampleName, outputFolder,
                        overlap, targetIntensity,
                        afTilesSpinner.getValue(), afStepsSpinner.getValue(), afRange
                );

            } catch (NumberFormatException ex) {
                qupath.fx.dialogs.Dialogs.showErrorMessage("WB Comparison",
                        "Invalid numeric value: " + ex.getMessage());
                return null;
            }
        });

        return dialog;
    }

    /**
     * Fills X/Y/Z text fields with the current stage position.
     */
    private static void fillCurrentPosition(TextField xField, TextField yField, TextField zField) {
        try {
            MicroscopeController mc = MicroscopeController.getInstance();
            if (!mc.isConnected()) {
                mc.connect();
            }
            double[] pos = mc.getStagePositionXY();
            double z = mc.getStagePositionZ();
            xField.setText(String.format("%.2f", pos[0]));
            yField.setText(String.format("%.2f", pos[1]));
            zField.setText(String.format("%.2f", z));
            logger.info("Filled current stage position: ({}, {}, {})", pos[0], pos[1], z);
        } catch (Exception e) {
            logger.error("Failed to get current stage position", e);
            qupath.fx.dialogs.Dialogs.showErrorMessage("Stage Position",
                    "Could not read current stage position: " + e.getMessage());
        }
    }

    private static double parseDouble(String text, String fieldName) {
        if (text == null || text.trim().isEmpty()) {
            throw new NumberFormatException(fieldName + " is empty");
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new NumberFormatException(fieldName + ": '" + text.trim() + "' is not a valid number");
        }
    }
}
