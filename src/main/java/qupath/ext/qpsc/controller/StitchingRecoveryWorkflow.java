package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Recovery workflow for re-stitching tiles after a failed stitching step.
 *
 * <p>When acquisition completes but stitching fails, tiles remain in the TempTiles
 * directory with their TileConfiguration.txt files. This workflow lets the user
 * select that tile folder, re-run stitching, and import the results into the
 * current QuPath project.
 *
 * <p>Access via: Extensions > QPSC > Utilities > Re-stitch Tiles
 */
public class StitchingRecoveryWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(StitchingRecoveryWorkflow.class);

    /**
     * Entry point - shows dialog and runs stitching recovery.
     */
    public static void run() {
        logger.info("Starting stitching recovery workflow");

        QuPathGUI gui = QuPathGUI.getInstance();
        Project<BufferedImage> project = gui.getProject();

        if (project == null) {
            Dialogs.showErrorMessage(
                    "No Project Open",
                    "Please open or create a QuPath project first.\n"
                            + "The stitched images will be added to the current project.");
            return;
        }

        Platform.runLater(() -> showRecoveryDialog(gui, project));
    }

    private static void showRecoveryDialog(QuPathGUI gui, Project<BufferedImage> project) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Re-stitch Tiles");
        dialog.setHeaderText("Recover a failed stitching step by re-stitching existing tiles");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // Tile folder selection
        Label folderLabel = new Label("Tile folder:");
        TextField folderField = new TextField();
        folderField.setPromptText("Browse to folder containing TileConfiguration.txt...");
        folderField.setPrefWidth(400);
        Button browseButton = new Button("Browse...");

        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Tile Folder");

            // Try to start at the project directory
            try {
                File projectDir = project.getPath().getParent().toFile();
                if (projectDir.exists()) {
                    chooser.setInitialDirectory(projectDir);
                }
            } catch (Exception ignored) {
                // Use default directory
            }

            File selected = chooser.showDialog(dialog.getOwner());
            if (selected != null) {
                folderField.setText(selected.getAbsolutePath());
            }
        });

        HBox folderBox = new HBox(10, folderField, browseButton);
        HBox.setHgrow(folderField, Priority.ALWAYS);

        // Pixel size - try to get from microscope config
        Label pixelLabel = new Label("Pixel size (um):");
        TextField pixelField = new TextField();
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath != null && !configPath.isEmpty()) {
                MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
                // Try to get pixel size from the first available modality/objective/detector
                var modalities = configManager.getAvailableModalities();
                if (!modalities.isEmpty()) {
                    String mod = modalities.iterator().next();
                    var objectives = configManager.getAvailableObjectivesForModality(mod);
                    if (!objectives.isEmpty()) {
                        String obj = objectives.iterator().next();
                        var detectors = configManager.getAvailableDetectorsForModalityObjective(mod, obj);
                        if (!detectors.isEmpty()) {
                            double ps = configManager.getModalityPixelSize(
                                    mod, obj, detectors.iterator().next());
                            if (ps > 0) {
                                pixelField.setText(String.valueOf(ps));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not get pixel size from config", e);
        }
        if (pixelField.getText().isEmpty()) {
            pixelField.setText("0.5"); // fallback
        }
        pixelField.setPrefWidth(100);

        // Compression
        Label compressionLabel = new Label("Compression:");
        ComboBox<String> compressionCombo = new ComboBox<>();
        compressionCombo.getItems().addAll("LZW", "JPEG", "zstd", "Uncompressed");
        compressionCombo.setValue("LZW");

        // Output format
        Label formatLabel = new Label("Output format:");
        ComboBox<String> formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll("OME_TIFF", "OME_ZARR");
        try {
            StitchingConfig.OutputFormat currentFormat = QPPreferenceDialog.getOutputFormatProperty();
            formatCombo.setValue(currentFormat.name());
        } catch (Exception e) {
            formatCombo.setValue("OME_TIFF");
        }

        // Matching string
        Label matchLabel = new Label("Matching string:");
        TextField matchField = new TextField(".");
        matchField.setPromptText(". = all subdirs, or specific angle like 0.0");
        matchField.setPrefWidth(200);

        // Info label
        Label infoLabel = new Label("Select the folder that contains tile subdirectories (e.g., angle folders)\n"
                + "with TileConfiguration.txt files. Use \".\" as matching string to stitch\n"
                + "all subdirectories, or enter a specific name to stitch one.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-style: italic;");

        grid.add(infoLabel, 0, 0, 3, 1);
        grid.add(folderLabel, 0, 1);
        grid.add(folderBox, 1, 1, 2, 1);
        grid.add(pixelLabel, 0, 2);
        grid.add(pixelField, 1, 2);
        grid.add(compressionLabel, 0, 3);
        grid.add(compressionCombo, 1, 3);
        grid.add(formatLabel, 0, 4);
        grid.add(formatCombo, 1, 4);
        grid.add(matchLabel, 0, 5);
        grid.add(matchField, 1, 5);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Disable OK until folder is set
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Stitch & Import");
        okButton.setDisable(true);

        folderField.textProperty().addListener((obs, old, val) -> {
            boolean valid = val != null && !val.trim().isEmpty() && new File(val.trim()).isDirectory();
            okButton.setDisable(!valid);
        });

        dialog.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                String tileFolder = folderField.getText().trim();
                double pixelSize;
                try {
                    pixelSize = Double.parseDouble(pixelField.getText().trim());
                } catch (NumberFormatException e) {
                    Dialogs.showErrorMessage("Invalid Pixel Size", "Please enter a valid number for pixel size.");
                    return;
                }
                String compression = compressionCombo.getValue();
                String matchingString = matchField.getText().trim();
                if (matchingString.isEmpty()) {
                    matchingString = ".";
                }
                StitchingConfig.OutputFormat outputFormat =
                        StitchingConfig.OutputFormat.valueOf(formatCombo.getValue());

                // Run stitching in background
                final String finalMatch = matchingString;
                Thread stitchThread = new Thread(() -> {
                    executeRecoveryStitching(
                            tileFolder, pixelSize, compression, finalMatch, outputFormat, gui, project);
                });
                stitchThread.setDaemon(true);
                stitchThread.setName("StitchingRecovery");
                stitchThread.start();
            }
        });
    }

    private static void executeRecoveryStitching(
            String tileFolder,
            double pixelSize,
            String compression,
            String matchingString,
            StitchingConfig.OutputFormat outputFormat,
            QuPathGUI gui,
            Project<BufferedImage> project) {

        logger.info(
                "Executing recovery stitching: folder={}, pixelSize={}, compression={}, match='{}', format={}",
                tileFolder,
                pixelSize,
                compression,
                matchingString,
                outputFormat);

        // Output goes to a "SlideImages" sibling directory (or same parent if no parent)
        File tileFolderFile = new File(tileFolder);
        File parentDir = tileFolderFile.getParentFile();
        String outputFolder;

        // If the tile folder is inside a TempTiles directory, put output in sibling SlideImages
        if (parentDir != null && parentDir.getName().equals("TempTiles")) {
            outputFolder = new File(parentDir.getParentFile(), "SlideImages").getAbsolutePath();
        } else {
            outputFolder = new File(parentDir, "SlideImages").getAbsolutePath();
        }

        File outputDir = new File(outputFolder);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            logger.error("Failed to create output directory: {}", outputFolder);
            Platform.runLater(() ->
                    Dialogs.showErrorMessage("Directory Error", "Failed to create output directory: " + outputFolder));
            return;
        }

        logger.info("Output directory: {}", outputFolder);

        try {
            StitchingConfig config = new StitchingConfig(
                    "Coordinates in TileConfiguration.txt file",
                    tileFolder,
                    outputFolder,
                    compression,
                    pixelSize,
                    1, // downsample
                    matchingString,
                    1.0, // zSpacingMicrons
                    outputFormat);

            // Use the tile folder name as output filename prefix
            config.outputFilename = tileFolderFile.getName();

            Platform.runLater(() -> Dialogs.showInfoNotification(
                    "Stitching Started", "Re-stitching tiles from: " + tileFolderFile.getName()));

            logger.info("Starting stitching workflow...");
            String outPath = StitchingWorkflow.run(config);

            if (outPath == null) {
                logger.error("Stitching returned null - no tiles were processed");
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        "Stitching Failed",
                        "No tiles were stitched. Check that the folder contains "
                                + "TileConfiguration.txt files and tile images."));
                return;
            }

            logger.info("Stitching completed: {}", outPath);

            // Find all newly created output files (handles batch mode with multiple angles)
            File[] outputFiles =
                    outputDir.listFiles((dir, name) -> name.endsWith(".ome.tif") || name.endsWith(".ome.zarr"));

            if (outputFiles == null || outputFiles.length == 0) {
                logger.error("No output files found after stitching");
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        "Stitching Failed", "Stitching completed but no output files were found."));
                return;
            }

            logger.info("Found {} stitched output files to import", outputFiles.length);

            // Import all output files to the project
            Platform.runLater(() -> {
                int imported = 0;
                for (File outputFile : outputFiles) {
                    try {
                        QPProjectFunctions.addImageToProject(outputFile, project, false, false, null);
                        logger.info("Imported: {}", outputFile.getName());
                        imported++;
                    } catch (IOException e) {
                        logger.error("Failed to import {}: {}", outputFile.getName(), e.getMessage());
                    }
                }

                gui.setProject(project);
                gui.refreshProject();

                if (imported > 0) {
                    Dialogs.showInfoNotification(
                            "Stitching Recovery Complete",
                            String.format("Successfully stitched and imported %d image(s) to the project.", imported));
                } else {
                    Dialogs.showErrorMessage(
                            "Import Failed", "Stitching completed but no images could be imported to the project.");
                }
            });

        } catch (Exception e) {
            logger.error("Stitching recovery failed", e);
            Platform.runLater(() ->
                    Dialogs.showErrorMessage("Stitching Recovery Failed", "Error during stitching: " + e.getMessage()));
        }
    }
}
