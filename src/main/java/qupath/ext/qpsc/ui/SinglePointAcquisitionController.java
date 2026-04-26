package qupath.ext.qpsc.ui;

import java.io.File;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.OutputFormat;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Single-point acquisition dialog for Z-stack and time-lapse.
 *
 * <p>Self-contained dialog with inline sample name, modality selection, and
 * output configuration. Does not require the full SampleSetupController flow
 * (which locks the sample name when a project is open).
 *
 * <p>Stops live camera streaming before acquisition and restarts it after
 * completion, matching the behavior of the main acquisition workflows.
 */
public class SinglePointAcquisitionController {

    private static final Logger logger = LoggerFactory.getLogger(SinglePointAcquisitionController.class);

    private SinglePointAcquisitionController() {}

    /**
     * Launch the dialog. Connects to microscope if needed, then shows the
     * all-in-one dialog directly (no separate sample setup step).
     */
    public static CompletableFuture<Void> show() {
        QuPathGUI qupath = QuPathGUI.getInstance();
        MicroscopeController mc = MicroscopeController.getInstance();
        if (!mc.isConnected()) {
            try {
                mc.connect();
            } catch (java.io.IOException e) {
                logger.error("Failed to connect to microscope server: {}", e.getMessage());
                Dialogs.showErrorMessage(
                        "Single-Point Acquisition", "Not connected to microscope server: " + e.getMessage());
                return CompletableFuture.completedFuture(null);
            }
        }

        return showMainDialog(qupath, mc);
    }

    private static CompletableFuture<Void> showMainDialog(QuPathGUI qupath, MicroscopeController mc) {

        CompletableFuture<Void> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Z-Stack / Time-Lapse Acquisition");
            dialog.setResizable(true);

            // Current Z for Z-stack default range
            double currentZ = 0.0;
            try {
                double[] pos = mc.getSocketClient().getStageXYZ();
                currentZ = pos[2];
            } catch (Exception e) {
                logger.debug("Could not get current Z: {}", e.getMessage());
            }
            final double fCurrentZ = currentZ;

            // --- Sample & Modality section (fixes #1, #4) ---
            TextField sampleNameField = new TextField();
            sampleNameField.setPromptText("Sample name");
            // Pre-populate from open project or last used
            if (qupath.getProject() != null) {
                File projectFile = qupath.getProject().getPath().toFile();
                sampleNameField.setText(projectFile.getParentFile().getName());
            } else {
                String lastSample = PersistentPreferences.getLastSampleName();
                if (!lastSample.isEmpty()) sampleNameField.setText(lastSample);
            }

            ComboBox<String> modalityCombo = new ComboBox<>();
            try {
                MicroscopeConfigManager mgr =
                        MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
                Set<String> modalities = mgr.getAvailableModalities();
                modalityCombo.setItems(FXCollections.observableArrayList(modalities));
                String lastModality = PersistentPreferences.getLastModality();
                if (!lastModality.isEmpty() && modalities.contains(lastModality)) {
                    modalityCombo.setValue(lastModality);
                } else if (!modalities.isEmpty()) {
                    modalityCombo.setValue(modalities.iterator().next());
                }
            } catch (Exception e) {
                modalityCombo.getItems().add("brightfield");
                modalityCombo.setValue("brightfield");
                logger.warn("Could not load modalities from config: {}", e.getMessage());
            }

            GridPane setupGrid = new GridPane();
            setupGrid.setHgap(8);
            setupGrid.setVgap(6);
            setupGrid.setPadding(new Insets(8));
            ColumnConstraints labelCol = new ColumnConstraints();
            labelCol.setMinWidth(100);
            ColumnConstraints fieldCol = new ColumnConstraints();
            fieldCol.setHgrow(Priority.ALWAYS);
            setupGrid.getColumnConstraints().addAll(labelCol, fieldCol);
            setupGrid.add(new Label("Sample name:"), 0, 0);
            setupGrid.add(sampleNameField, 1, 0);
            setupGrid.add(new Label("Modality:"), 0, 1);
            setupGrid.add(modalityCombo, 1, 1);

            // --- Z-stack section ---
            CheckBox zEnableCheckbox = new CheckBox("Enable Z-stack");
            Spinner<Double> zRangeSpinner = new Spinner<>(0.1, 2000.0, 20.0, 5.0);
            zRangeSpinner.setEditable(true);
            zRangeSpinner.setPrefWidth(100);
            Spinner<Double> zStepSpinner = new Spinner<>(0.1, 50.0, 1.0, 0.5);
            zStepSpinner.setEditable(true);
            zStepSpinner.setPrefWidth(100);
            ComboBox<String> zProjectionCombo = new ComboBox<>();
            zProjectionCombo.getItems().addAll("none", "max", "min", "sum", "mean", "std");
            zProjectionCombo.setValue("none");
            Label zInfoLabel = new Label();
            zInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            Runnable updateZInfo = () -> {
                double range = zRangeSpinner.getValue();
                double step = zStepSpinner.getValue();
                int planes = (int) Math.ceil(range / step) + 1;
                zInfoLabel.setText(String.format(
                        "Range %.1f to %.1f um around Z=%.1f (%d planes)",
                        fCurrentZ - range / 2, fCurrentZ + range / 2, fCurrentZ, planes));
            };
            zRangeSpinner.valueProperty().addListener((o, a, b) -> updateZInfo.run());
            zStepSpinner.valueProperty().addListener((o, a, b) -> updateZInfo.run());
            updateZInfo.run();

            GridPane zGrid = new GridPane();
            zGrid.setHgap(8);
            zGrid.setVgap(6);
            zGrid.setPadding(new Insets(8));
            zGrid.add(new Label("Total range (um):"), 0, 0);
            zGrid.add(zRangeSpinner, 1, 0);
            zGrid.add(new Label("Step size (um):"), 0, 1);
            zGrid.add(zStepSpinner, 1, 1);
            zGrid.add(new Label("Projection:"), 0, 2);
            zGrid.add(zProjectionCombo, 1, 2);
            zGrid.add(zInfoLabel, 0, 3, 2, 1);
            zGrid.disableProperty().bind(zEnableCheckbox.selectedProperty().not());
            TitledPane zPane = new TitledPane("Z-stack", new VBox(6, zEnableCheckbox, zGrid));
            zPane.setCollapsible(false);

            // --- Time-lapse section ---
            CheckBox tEnableCheckbox = new CheckBox("Enable time-lapse");
            Spinner<Integer> tpSpinner = new Spinner<>(2, 10000, 10, 5);
            tpSpinner.setEditable(true);
            tpSpinner.setPrefWidth(100);
            Spinner<Double> tIntervalSpinner = new Spinner<>(0.0, 3600.0, 5.0, 1.0);
            tIntervalSpinner.setEditable(true);
            tIntervalSpinner.setPrefWidth(100);
            Label tInfoLabel = new Label();
            tInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            Runnable updateTInfo = () -> {
                int tp = tpSpinner.getValue();
                double interval = tIntervalSpinner.getValue();
                double totalMinutes = (tp - 1) * interval / 60.0;
                tInfoLabel.setText(String.format("%d timepoints, %.1f min total", tp, totalMinutes));
            };
            tpSpinner.valueProperty().addListener((o, a, b) -> updateTInfo.run());
            tIntervalSpinner.valueProperty().addListener((o, a, b) -> updateTInfo.run());
            updateTInfo.run();

            GridPane tGrid = new GridPane();
            tGrid.setHgap(8);
            tGrid.setVgap(6);
            tGrid.setPadding(new Insets(8));
            tGrid.add(new Label("Timepoints:"), 0, 0);
            tGrid.add(tpSpinner, 1, 0);
            tGrid.add(new Label("Interval (sec):"), 0, 1);
            tGrid.add(tIntervalSpinner, 1, 1);
            tGrid.add(tInfoLabel, 0, 2, 2, 1);
            tGrid.disableProperty().bind(tEnableCheckbox.selectedProperty().not());
            TitledPane tPane = new TitledPane("Time-lapse", new VBox(6, tEnableCheckbox, tGrid));
            tPane.setCollapsible(false);

            // --- Output section (fix #2: full-width folder field) ---
            ComboBox<OutputFormat> outputFormatCombo = new ComboBox<>();
            outputFormatCombo.getItems().addAll(OutputFormat.values());
            outputFormatCombo.setValue(OutputFormat.OME_PER_T);

            TextField outputFolderField = new TextField();
            outputFolderField.setMaxWidth(Double.MAX_VALUE);
            // Default output folder from projects folder + sample name
            String projectsFolder = QPPreferenceDialog.getProjectsFolderProperty();
            if (projectsFolder != null && !projectsFolder.isEmpty()) {
                String name = sampleNameField.getText();
                if (name != null && !name.isEmpty()) {
                    outputFolderField.setText(
                            new File(new File(projectsFolder, name), "singlepoint").getAbsolutePath());
                }
            }
            // Update output folder when sample name changes
            sampleNameField.textProperty().addListener((obs, oldVal, newVal) -> {
                String pf = QPPreferenceDialog.getProjectsFolderProperty();
                if (pf != null && !pf.isEmpty() && newVal != null && !newVal.isEmpty()) {
                    outputFolderField.setText(
                            new File(new File(pf, newVal), "singlepoint").getAbsolutePath());
                }
            });

            Button browseBtn = new Button("Browse...");
            browseBtn.setOnAction(e -> {
                javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                chooser.setTitle("Select Output Folder");
                String current = outputFolderField.getText();
                if (current != null && !current.isEmpty()) {
                    File dir = new File(current);
                    if (dir.exists()) chooser.setInitialDirectory(dir);
                    else if (dir.getParentFile() != null && dir.getParentFile().exists())
                        chooser.setInitialDirectory(dir.getParentFile());
                }
                File dir = chooser.showDialog(dialog.getOwner());
                if (dir != null) outputFolderField.setText(dir.getAbsolutePath());
            });

            VBox outContent = new VBox(6);
            outContent.setPadding(new Insets(8));
            GridPane outFormatRow = new GridPane();
            outFormatRow.setHgap(8);
            outFormatRow.add(new Label("Output format:"), 0, 0);
            outFormatRow.add(outputFormatCombo, 1, 0);
            outContent.getChildren().addAll(
                    outFormatRow,
                    new Label("Output folder:"),
                    outputFolderField,
                    browseBtn);
            TitledPane outPane = new TitledPane("Output", outContent);
            outPane.setCollapsible(false);

            Label statusLabel = new Label();
            statusLabel.setStyle("-fx-font-size: 11px;");
            statusLabel.setWrapText(true);

            VBox content = new VBox(10, setupGrid, zPane, tPane, outPane, statusLabel);
            content.setPadding(new Insets(10));

            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().setPrefWidth(520);
            dialog.getDialogPane().setPrefHeight(620);
            ButtonType startButtonType = new ButtonType("Start", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(startButtonType, ButtonType.CANCEL);

            // Validation on Start
            Button startBtn = (Button) dialog.getDialogPane().lookupButton(startButtonType);
            startBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String error = validate(
                        sampleNameField.getText(),
                        zEnableCheckbox.isSelected(),
                        tEnableCheckbox.isSelected(),
                        outputFolderField.getText());
                if (error != null) {
                    statusLabel.setText(error);
                    statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                    event.consume();
                }
            });

            dialog.resultProperty().addListener((obs, oldVal, result) -> {
                if (result == startButtonType) {
                    String modality = modalityCombo.getValue() != null ? modalityCombo.getValue() : "brightfield";
                    double range = zRangeSpinner.getValue();
                    double step = zStepSpinner.getValue();
                    double zStart = fCurrentZ - range / 2;
                    double zEnd = fCurrentZ + range / 2;

                    // Build a SampleSetupResult for compatibility with dispatch
                    SampleSetupResult sample = new SampleSetupResult(
                            sampleNameField.getText(),
                            projectsFolder != null ? new File(projectsFolder) : null,
                            modality,
                            null, // objective (not needed for single-point)
                            null  // detector
                    );

                    dispatch(
                            mc,
                            sample,
                            zEnableCheckbox.isSelected(),
                            zStart,
                            zEnd,
                            step,
                            zProjectionCombo.getValue(),
                            tEnableCheckbox.isSelected(),
                            tpSpinner.getValue(),
                            tIntervalSpinner.getValue(),
                            outputFormatCombo.getValue(),
                            outputFolderField.getText(),
                            future);
                } else {
                    future.complete(null);
                }
            });

            dialog.show();
        });

        return future;
    }

    private static String validate(String sampleName, boolean zEnabled, boolean tEnabled, String outputFolder) {
        if (sampleName == null || sampleName.trim().isEmpty()) {
            return "Please enter a sample name.";
        }
        if (!zEnabled && !tEnabled) {
            return "Enable at least one of Z-stack or Time-lapse.";
        }
        // Combined Z+T is now supported by the server (acquire_z_stack
        // accepts n_timepoints, interval_seconds and produces a SizeT x
        // SizeZ OME-TIFF per angle). The dispatch below routes through
        // the new startZStack overload that carries both sets of params.
        if (outputFolder == null || outputFolder.trim().isEmpty()) {
            return "Please specify an output folder.";
        }
        return null;
    }

    /**
     * Dispatch acquisition on a background thread.
     *
     * <p>Fix #5: Stops live camera streaming before acquisition (JAI camera
     * properties cannot be changed during streaming) and logs completion.
     */
    private static void dispatch(
            MicroscopeController mc,
            SampleSetupResult sample,
            boolean zEnabled,
            double zStart,
            double zEnd,
            double zStep,
            String zProjection,
            boolean tEnabled,
            int timepoints,
            double intervalSec,
            OutputFormat outputFormat,
            String outputFolder,
            CompletableFuture<Void> future) {

        String modality = sample.modality() != null ? sample.modality() : "brightfield";
        String wbMode = "off";
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();

        logger.info(
                "Single-point dispatch: modality={}, z={}[{}..{} step {}], t=[{}pts, {}s], outFmt={}",
                modality, zEnabled, zStart, zEnd, zStep, timepoints, intervalSec, outputFormat);

        Thread worker = new Thread(
                () -> {
                    try {
                        // Use the shared live-mode wrapper so the Live Viewer
                        // button state stays in sync with actual streaming.
                        // Calling stopContinuousAcquisition() on the socket
                        // directly bypasses LiveViewerWindow's local state and
                        // leaves the button stuck on "Live ON" after the
                        // acquisition completes. withLiveModeHandling handles
                        // stop-before / restart-after and keeps the UI honest.
                        mc.withLiveModeHandling(() -> {
                            String response;
                            if (zEnabled) {
                                // Route Z (and combined Z+T) through startZStack.
                                // When tEnabled, pass the time-lapse params so the
                                // server runs a T-outer / Z-inner loop and emits
                                // a single OME-TIFF per angle with both SizeT
                                // and SizeZ. Pure Z-stack passes timepoints=1.
                                int tpForZ = tEnabled ? timepoints : 1;
                                double intervalForZ = tEnabled ? intervalSec : 0.0;
                                response = mc.getSocketClient()
                                        .startZStack(
                                                outputFolder,
                                                zStart,
                                                zEnd,
                                                zStep,
                                                modality,
                                                null,
                                                wbMode,
                                                configPath,
                                                null,
                                                null,
                                                zProjection,
                                                false,
                                                null,
                                                null,
                                                tpForZ,
                                                intervalForZ);
                            } else {
                                response = mc.getSocketClient()
                                        .startTimeLapse(
                                                outputFolder,
                                                timepoints,
                                                intervalSec,
                                                modality,
                                                null,
                                                wbMode,
                                                configPath,
                                                null,
                                                null);
                            }
                            logger.info("Single-point acquisition complete: {}", response);
                        });

                        Platform.runLater(() ->
                                Dialogs.showInfoNotification(
                                        "Single-Point Acquisition",
                                        "Acquisition complete. Output: " + outputFolder));
                    } catch (Exception ex) {
                        logger.error("Single-point acquisition failed: {}", ex.getMessage(), ex);
                        Platform.runLater(() ->
                                Dialogs.showErrorNotification(
                                        "Single-Point Acquisition",
                                        "Acquisition failed: " + ex.getMessage()));
                    } finally {
                        future.complete(null);
                    }
                },
                "SinglePoint-Acquire");
        worker.setDaemon(true);
        worker.start();
    }
}
