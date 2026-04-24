package qupath.ext.qpsc.ui;

import java.io.File;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.OutputFormat;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Scaffold for the unified single-point acquisition dialog (Z-stack + time-lapse)
 * that will eventually replace {@link qupath.ext.qpsc.controller.StackTimeLapseWorkflow}.
 *
 * <p>Currently feature-flagged behind
 * {@link QPPreferenceDialog#getSinglePointDialogEnabledProperty()}. When the flag
 * is off this dialog is not reachable from the menu.
 *
 * <p>Dispatch is still via the legacy {@code startZStack}/{@code startTimeLapse}
 * socket methods so that existing server behavior is preserved. The pass-through
 * modality (from {@link SampleSetupController}) is now honored instead of the
 * previous hardcoded "brightfield".
 *
 * <p>Combined Z+T acquisitions are blocked with a validation message until the
 * server-side {@code single_point.py} path lands.
 */
public class SinglePointAcquisitionController {

    private static final Logger logger = LoggerFactory.getLogger(SinglePointAcquisitionController.class);

    private SinglePointAcquisitionController() {}

    /**
     * Launch the dialog. Sample setup runs first (modality, objective, output folder).
     * The main acquisition dialog is shown if setup completes successfully.
     *
     * @return a future that completes when the user dismisses the dialog
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

        return SampleSetupController.showDialog().thenCompose(sample -> {
            if (sample == null) {
                logger.info("Sample setup cancelled; single-point dialog not opened");
                return CompletableFuture.completedFuture(null);
            }
            return showMainDialog(qupath, mc, sample);
        });
    }

    private static CompletableFuture<Void> showMainDialog(
            QuPathGUI qupath, MicroscopeController mc, SampleSetupResult sample) {

        CompletableFuture<Void> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Single-Point Acquisition (experimental)");
            dialog.setHeaderText(String.format(
                    "Sample: %s    Modality: %s    Objective: %s",
                    sample.sampleName(), sample.modality(), sample.objective()));

            // Current Z for Z-stack default range
            double currentZ = 0.0;
            try {
                double[] pos = mc.getSocketClient().getStageXYZ();
                currentZ = pos[2];
            } catch (Exception e) {
                logger.debug("Could not get current Z: {}", e.getMessage());
            }
            final double fCurrentZ = currentZ;

            // --- Z-stack section ---
            CheckBox zEnableCheckbox = new CheckBox("Enable Z-stack");
            Spinner<Double> zRangeSpinner = new Spinner<>(0.1, 200.0, 20.0, 5.0);
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

            // --- Output format + output folder ---
            ComboBox<OutputFormat> outputFormatCombo = new ComboBox<>();
            outputFormatCombo.getItems().addAll(OutputFormat.values());
            outputFormatCombo.setValue(OutputFormat.OME_PER_T);

            TextField outputFolderField = new TextField();
            outputFolderField.setText(defaultOutputFolder(sample));
            Button browseBtn = new Button("Browse...");
            browseBtn.setOnAction(e -> {
                javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
                chooser.setTitle("Select Output Folder");
                File dir = chooser.showDialog(dialog.getOwner());
                if (dir != null) outputFolderField.setText(dir.getAbsolutePath());
            });

            GridPane outGrid = new GridPane();
            outGrid.setHgap(8);
            outGrid.setVgap(6);
            outGrid.setPadding(new Insets(8));
            outGrid.add(new Label("Output format:"), 0, 0);
            outGrid.add(outputFormatCombo, 1, 0);
            outGrid.add(new Label("Output folder:"), 0, 1);
            HBox folderRow = new HBox(4, outputFolderField, browseBtn);
            HBox.setHgrow(outputFolderField, Priority.ALWAYS);
            outGrid.add(folderRow, 1, 1);
            TitledPane outPane = new TitledPane("Output", outGrid);
            outPane.setCollapsible(false);

            Label statusLabel = new Label();
            statusLabel.setStyle("-fx-font-size: 11px;");
            statusLabel.setWrapText(true);

            VBox content = new VBox(10, zPane, tPane, outPane, statusLabel);
            content.setPadding(new Insets(10));

            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().setPrefWidth(520);
            ButtonType startButtonType = new ButtonType("Start", ButtonType.OK.getButtonData());
            dialog.getDialogPane().getButtonTypes().addAll(startButtonType, ButtonType.CANCEL);

            // Consume the Start action if validation fails, so the dialog stays open
            Button startBtn = (Button) dialog.getDialogPane().lookupButton(startButtonType);
            startBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String error = validate(
                        zEnableCheckbox.isSelected(),
                        tEnableCheckbox.isSelected(),
                        outputFolderField.getText());
                if (error != null) {
                    statusLabel.setText(error);
                    statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                    event.consume();
                }
            });

            dialog.setOnCloseRequest(e -> {
                // Cancel path: nothing to clean up
            });

            dialog.resultProperty().addListener((obs, oldVal, result) -> {
                if (result == startButtonType) {
                    double range = zRangeSpinner.getValue();
                    double step = zStepSpinner.getValue();
                    double zStart = fCurrentZ - range / 2;
                    double zEnd = fCurrentZ + range / 2;
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

    /**
     * Validate user selections. Returns an error string to display, or null when valid.
     *
     * <p>Combined Z+T is rejected until the server-side single_point.py path lands.
     */
    private static String validate(boolean zEnabled, boolean tEnabled, String outputFolder) {
        if (!zEnabled && !tEnabled) {
            return "Enable at least one of Z-stack or Time-lapse.";
        }
        if (zEnabled && tEnabled) {
            return "Combined Z-stack + Time-lapse is not yet supported on the server. "
                    + "Enable only one for now.";
        }
        if (outputFolder == null || outputFolder.trim().isEmpty()) {
            return "Please specify an output folder.";
        }
        return null;
    }

    /**
     * Dispatch to the existing socket methods on a background thread. The new builder
     * fields ({@code outputFormat}, {@code .timeLapse(...)}) are plumbed through the
     * builder but the legacy {@code startZStack}/{@code startTimeLapse} methods are
     * still used for wire transport -- the server will honor the per-command defaults
     * until the unified ACQUIRE path is adopted.
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
                modality,
                zEnabled,
                zStart,
                zEnd,
                zStep,
                timepoints,
                intervalSec,
                outputFormat);

        Thread worker = new Thread(
                () -> {
                    try {
                        String response;
                        if (zEnabled) {
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
                                            null);
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
                    } catch (Exception ex) {
                        logger.error("Single-point acquisition failed: {}", ex.getMessage(), ex);
                    } finally {
                        future.complete(null);
                    }
                },
                "SinglePoint-Acquire");
        worker.setDaemon(true);
        worker.start();
    }

    private static String defaultOutputFolder(SampleSetupResult sample) {
        File projectsFolder = sample.projectsFolder();
        String sampleName = sample.sampleName();
        if (projectsFolder != null && sampleName != null && !sampleName.isEmpty()) {
            return new File(new File(projectsFolder, sampleName), "singlepoint").getAbsolutePath();
        }
        return "";
    }
}
