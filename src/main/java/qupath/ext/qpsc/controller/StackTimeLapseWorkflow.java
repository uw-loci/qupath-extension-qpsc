package qupath.ext.qpsc.controller;

import java.io.File;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Dialog and workflow for basic Z-stack and time-lapse acquisition.
 *
 * <p>Single-tile acquisitions at the current stage position:
 * <ul>
 *   <li>Z-stack: multiple Z planes around current focus</li>
 *   <li>Time-lapse: repeat acquisition at regular intervals</li>
 * </ul>
 *
 * <p>Designed for future expansion to multi-tile Z-stacks and time series.
 */
public class StackTimeLapseWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(StackTimeLapseWorkflow.class);

    /**
     * Show the Z-stack/time-lapse dialog.
     */
    public static void show(QuPathGUI qupath) {
        MicroscopeController mc = MicroscopeController.getInstance();
        if (!mc.isConnected()) {
            try {
                mc.connect();
            } catch (java.io.IOException e) {
                logger.error("Failed to connect to microscope server: {}", e.getMessage());
                Dialogs.showErrorMessage("Z-Stack / Time-Lapse",
                        "Not connected to microscope server: " + e.getMessage());
                return;
            }
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Z-Stack / Time-Lapse");
        dialog.setHeaderText("Single-tile acquisition at current position");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // --- Z-Stack Tab ---
        Tab zTab = new Tab("Z-Stack");
        GridPane zGrid = new GridPane();
        zGrid.setHgap(8);
        zGrid.setVgap(6);
        zGrid.setPadding(new Insets(10));

        // Get current Z for default range
        double currentZ = 0;
        try {
            double[] pos = mc.getSocketClient().getStageXYZ();
            currentZ = pos[2];
        } catch (Exception e) {
            logger.debug("Could not get current Z: {}", e.getMessage());
        }

        Spinner<Double> zRangeSpinner = new Spinner<>(1.0, 200.0, 20.0, 5.0);
        zRangeSpinner.setEditable(true);
        zRangeSpinner.setPrefWidth(100);
        Spinner<Double> zStepSpinner = new Spinner<>(0.1, 50.0, 1.0, 0.5);
        zStepSpinner.setEditable(true);
        zStepSpinner.setPrefWidth(100);

        Label zInfoLabel = new Label();
        zInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
        final double fCurrentZ = currentZ;

        Runnable updateZInfo = () -> {
            double range = zRangeSpinner.getValue();
            double step = zStepSpinner.getValue();
            int planes = (int) Math.ceil(range / step) + 1;
            zInfoLabel.setText(String.format(
                    "Z range: %.1f to %.1f um (%d planes, centered on current Z=%.1f)",
                    fCurrentZ - range / 2, fCurrentZ + range / 2, planes, fCurrentZ));
        };

        zRangeSpinner.valueProperty().addListener((obs, o, n) -> updateZInfo.run());
        zStepSpinner.valueProperty().addListener((obs, o, n) -> updateZInfo.run());
        updateZInfo.run();

        TextField zOutputField = new TextField();
        zOutputField.setPromptText("Output folder for Z-stack images");
        // Default output path
        String defaultZOutput = getDefaultOutputFolder("zstack");
        zOutputField.setText(defaultZOutput);

        Button zBrowseBtn = new Button("Browse...");
        zBrowseBtn.setOnAction(e -> {
            var chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Select Z-Stack Output Folder");
            File dir = chooser.showDialog(dialog.getOwner());
            if (dir != null) zOutputField.setText(dir.getAbsolutePath());
        });

        zGrid.add(new Label("Total range (um):"), 0, 0);
        zGrid.add(zRangeSpinner, 1, 0);
        zGrid.add(new Label("Step size (um):"), 0, 1);
        zGrid.add(zStepSpinner, 1, 1);
        zGrid.add(zInfoLabel, 0, 2, 2, 1);
        zGrid.add(new Label("Output:"), 0, 3);
        zGrid.add(new HBox(4, zOutputField, zBrowseBtn), 1, 3);
        HBox.setHgrow(zOutputField, Priority.ALWAYS);

        Label zStatusLabel = new Label();
        zStatusLabel.setStyle("-fx-font-size: 11px;");
        zStatusLabel.setWrapText(true);

        Button zStartBtn = new Button("Start Z-Stack");
        zStartBtn.setStyle("-fx-font-weight: bold;");
        zStartBtn.setOnAction(e -> {
            double range = zRangeSpinner.getValue();
            double step = zStepSpinner.getValue();
            double zStart = fCurrentZ - range / 2;
            double zEnd = fCurrentZ + range / 2;
            String output = zOutputField.getText().trim();
            if (output.isEmpty()) {
                zStatusLabel.setText("Please specify an output folder.");
                return;
            }

            zStartBtn.setDisable(true);
            zStatusLabel.setText("Acquiring Z-stack...");
            zStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

            new Thread(
                            () -> {
                                try {
                                    String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                                    String response = mc.getSocketClient()
                                            .startZStack(
                                                    output,
                                                    zStart,
                                                    zEnd,
                                                    step,
                                                    "brightfield",
                                                    null,
                                                    "off",
                                                    configPath,
                                                    null,
                                                    null);
                                    javafx.application.Platform.runLater(() -> {
                                        zStatusLabel.setText("Complete: " + response);
                                        zStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
                                        zStartBtn.setDisable(false);
                                    });
                                } catch (Exception ex) {
                                    logger.error("Z-stack failed: {}", ex.getMessage());
                                    javafx.application.Platform.runLater(() -> {
                                        zStatusLabel.setText("Failed: " + ex.getMessage());
                                        zStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                                        zStartBtn.setDisable(false);
                                    });
                                }
                            },
                            "ZStack-Acquire")
                    .start();
        });

        VBox zContent = new VBox(8, zGrid, zStartBtn, zStatusLabel);
        zContent.setPadding(new Insets(8));
        zTab.setContent(zContent);

        // --- Time-Lapse Tab ---
        Tab tTab = new Tab("Time-Lapse");
        GridPane tGrid = new GridPane();
        tGrid.setHgap(8);
        tGrid.setVgap(6);
        tGrid.setPadding(new Insets(10));

        Spinner<Integer> tpSpinner = new Spinner<>(2, 10000, 10, 5);
        tpSpinner.setEditable(true);
        tpSpinner.setPrefWidth(100);
        Spinner<Double> intervalSpinner = new Spinner<>(0.0, 3600.0, 5.0, 1.0);
        intervalSpinner.setEditable(true);
        intervalSpinner.setPrefWidth(100);

        Label tInfoLabel = new Label();
        tInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

        Runnable updateTInfo = () -> {
            int tp = tpSpinner.getValue();
            double interval = intervalSpinner.getValue();
            double totalMinutes = (tp - 1) * interval / 60.0;
            tInfoLabel.setText(String.format("%d timepoints, %.1f min total duration", tp, totalMinutes));
        };
        tpSpinner.valueProperty().addListener((obs, o, n) -> updateTInfo.run());
        intervalSpinner.valueProperty().addListener((obs, o, n) -> updateTInfo.run());
        updateTInfo.run();

        TextField tOutputField = new TextField();
        tOutputField.setPromptText("Output folder for time-lapse images");
        tOutputField.setText(getDefaultOutputFolder("timelapse"));

        Button tBrowseBtn = new Button("Browse...");
        tBrowseBtn.setOnAction(e -> {
            var chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Select Time-Lapse Output Folder");
            File dir = chooser.showDialog(dialog.getOwner());
            if (dir != null) tOutputField.setText(dir.getAbsolutePath());
        });

        tGrid.add(new Label("Timepoints:"), 0, 0);
        tGrid.add(tpSpinner, 1, 0);
        tGrid.add(new Label("Interval (sec):"), 0, 1);
        tGrid.add(intervalSpinner, 1, 1);
        tGrid.add(tInfoLabel, 0, 2, 2, 1);
        tGrid.add(new Label("Output:"), 0, 3);
        tGrid.add(new HBox(4, tOutputField, tBrowseBtn), 1, 3);
        HBox.setHgrow(tOutputField, Priority.ALWAYS);

        Label tStatusLabel = new Label();
        tStatusLabel.setStyle("-fx-font-size: 11px;");
        tStatusLabel.setWrapText(true);

        Button tStartBtn = new Button("Start Time-Lapse");
        tStartBtn.setStyle("-fx-font-weight: bold;");
        tStartBtn.setOnAction(e -> {
            int tp = tpSpinner.getValue();
            double interval = intervalSpinner.getValue();
            String output = tOutputField.getText().trim();
            if (output.isEmpty()) {
                tStatusLabel.setText("Please specify an output folder.");
                return;
            }

            tStartBtn.setDisable(true);
            tStatusLabel.setText("Acquiring time-lapse...");
            tStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

            new Thread(
                            () -> {
                                try {
                                    String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
                                    String response = mc.getSocketClient()
                                            .startTimeLapse(
                                                    output,
                                                    tp,
                                                    interval,
                                                    "brightfield",
                                                    null,
                                                    "off",
                                                    configPath,
                                                    null,
                                                    null);
                                    javafx.application.Platform.runLater(() -> {
                                        tStatusLabel.setText("Complete: " + response);
                                        tStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: green;");
                                        tStartBtn.setDisable(false);
                                    });
                                } catch (Exception ex) {
                                    logger.error("Time-lapse failed: {}", ex.getMessage());
                                    javafx.application.Platform.runLater(() -> {
                                        tStatusLabel.setText("Failed: " + ex.getMessage());
                                        tStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: red;");
                                        tStartBtn.setDisable(false);
                                    });
                                }
                            },
                            "TimeLapse-Acquire")
                    .start();
        });

        VBox tContent = new VBox(8, tGrid, tStartBtn, tStatusLabel);
        tContent.setPadding(new Insets(8));
        tTab.setContent(tContent);

        tabs.getTabs().addAll(zTab, tTab);

        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(500);

        dialog.showAndWait();
    }

    private static String getDefaultOutputFolder(String type) {
        try {
            var project = QuPathGUI.getInstance().getProject();
            if (project != null) {
                File projectDir = project.getPath().toFile().getParentFile();
                return new File(projectDir, type).getAbsolutePath();
            }
        } catch (Exception e) {
            // Fall through
        }
        return "";
    }
}
