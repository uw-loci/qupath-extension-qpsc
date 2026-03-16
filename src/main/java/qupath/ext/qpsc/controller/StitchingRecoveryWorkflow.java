package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Properties;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.ImageNameGenerator;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

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

        // Pixel size - initialize from saved preference, then update from tile metadata on folder selection
        Label pixelLabel = new Label("Pixel size (um):");
        TextField pixelField = new TextField();
        Label pixelSourceLabel = new Label();
        pixelSourceLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #666;");

        // Initial value: saved preference > microscope config > 0.5 fallback
        String savedPixelSize = PersistentPreferences.getRestitchPixelSize();
        if (savedPixelSize != null && !savedPixelSize.isEmpty()) {
            pixelField.setText(savedPixelSize);
            pixelSourceLabel.setText("(last used)");
        } else {
            pixelField.setText("0.5");
            pixelSourceLabel.setText("(default -- select folder to auto-detect)");
        }
        pixelField.setPrefWidth(100);

        // Metadata status label (updated when folder is selected)
        Label metadataLabel = new Label();
        metadataLabel.setWrapText(true);
        metadataLabel.setStyle("-fx-font-size: 0.85em;");

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
                // Auto-detect pixel size from tile TIFF metadata
                double detected = detectPixelSizeFromFolder(selected);
                if (detected > 0) {
                    pixelField.setText(String.valueOf(detected));
                    pixelSourceLabel.setText("(from tile metadata)");
                    logger.info("Auto-detected pixel size from tiles: {} um", detected);
                }
                // Show acquisition metadata status
                Properties info = readAcquisitionInfo(selected);
                if (info != null) {
                    metadataLabel.setText("Sample: " + info.getProperty("sample_name", "?")
                            + " | Modality: " + info.getProperty("modality", "?")
                            + " | Objective: " + info.getProperty("objective", "?"));
                    metadataLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: green;");
                } else {
                    metadataLabel.setText("No acquisition metadata found -- will use folder name");
                    metadataLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: orange;");
                }
            }
        });

        HBox folderBox = new HBox(10, folderField, browseButton);
        HBox.setHgrow(folderField, Priority.ALWAYS);
        HBox pixelBox = new HBox(8, pixelField, pixelSourceLabel);
        pixelBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

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
                + "all subdirectories, or enter a specific name to stitch one.\n\n"
                + "If you see _temp_* folders from a crash, select the _temp_* folder\n"
                + "and use the angle name (e.g., \"90.0\") as matching string.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-style: italic;");

        grid.add(infoLabel, 0, 0, 3, 1);
        grid.add(folderLabel, 0, 1);
        grid.add(folderBox, 1, 1, 2, 1);
        grid.add(metadataLabel, 0, 2, 3, 1);
        grid.add(pixelLabel, 0, 3);
        grid.add(pixelBox, 1, 3, 2, 1);
        grid.add(compressionLabel, 0, 4);
        grid.add(compressionCombo, 1, 4);
        grid.add(formatLabel, 0, 5);
        grid.add(formatCombo, 1, 5);
        grid.add(matchLabel, 0, 6);
        grid.add(matchField, 1, 6);

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
                // Remember for next time
                PersistentPreferences.setRestitchPixelSize(pixelField.getText().trim());
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

    /**
     * Scan a folder (and its immediate subdirectories) for the first TIFF file
     * and read pixel size from its resolution metadata.
     *
     * @return pixel size in microns, or -1 if not found
     */
    private static double detectPixelSizeFromFolder(File folder) {
        // Try TIFF files directly in the folder
        double ps = detectPixelSizeFromTiffsIn(folder.toPath());
        if (ps > 0) return ps;

        // Try immediate subdirectories (angle folders)
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder.toPath())) {
            for (Path subdir : stream) {
                if (Files.isDirectory(subdir)) {
                    ps = detectPixelSizeFromTiffsIn(subdir);
                    if (ps > 0) return ps;
                }
            }
        } catch (IOException e) {
            logger.debug("Error scanning subdirectories for pixel size: {}", e.getMessage());
        }

        logger.debug("No pixel size metadata found in tiles under {}", folder);
        return -1;
    }

    /**
     * Find the first TIFF in a directory and read its pixel size from resolution tags.
     */
    private static double detectPixelSizeFromTiffsIn(Path dir) {
        try (DirectoryStream<Path> tifs = Files.newDirectoryStream(dir, "*.tif*")) {
            for (Path tif : tifs) {
                double ps = readTiffPixelSize(tif.toFile());
                if (ps > 0) {
                    return ps;
                }
            }
        } catch (IOException e) {
            logger.debug("Error scanning for TIFFs in {}: {}", dir, e.getMessage());
        }
        return -1;
    }

    /**
     * Read pixel size in microns from a TIFF file's resolution tags.
     *
     * @return pixel size in microns, or -1 if resolution metadata is missing or unusable
     */
    private static double readTiffPixelSize(File file) {
        try (FileInputStream fis = new FileInputStream(file);
                ImageInputStream iis = ImageIO.createImageInputStream(fis)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("TIFF");
            if (!readers.hasNext()) {
                return -1;
            }
            ImageReader reader = readers.next();
            reader.setInput(iis);
            IIOMetadata metadata = reader.getImageMetadata(0);
            TIFFDirectory tiffDir = TIFFDirectory.createFromMetadata(metadata);

            // Read X resolution (pixels per unit)
            var xResField = tiffDir.getTIFFField(BaselineTIFFTagSet.TAG_X_RESOLUTION);
            if (xResField == null) {
                reader.dispose();
                return -1;
            }
            long[] rational = xResField.getAsRational(0);
            double xRes = rational[0] / (double) rational[1];
            if (xRes <= 0) {
                reader.dispose();
                return -1;
            }

            // Resolution unit: 2 = inches (default per TIFF spec), 3 = centimeters
            int resUnit = 2;
            var unitField = tiffDir.getTIFFField(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT);
            if (unitField != null) {
                resUnit = unitField.getAsInt(0);
            }

            reader.dispose();

            if (resUnit == 3) {
                // Centimeters: pixel size = 10000/xRes um
                return 10000.0 / xRes;
            } else if (resUnit == 2) {
                // Inches: pixel size = 25400/xRes um
                return 25400.0 / xRes;
            } else {
                // No unit -- can't derive microns
                return -1;
            }
        } catch (Exception e) {
            logger.debug("Could not read pixel size from {}: {}", file.getName(), e.getMessage());
            return -1;
        }
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

        File tileFolderFile = new File(tileFolder);
        String annotationName = tileFolderFile.getName(); // tile folder = annotation name

        // Read acquisition metadata from scan type directory
        Properties info = readAcquisitionInfo(tileFolderFile);
        String sampleName;
        String modality = null;
        String objective = null;
        ProjectImageEntry<BufferedImage> parentEntry = null;

        if (info != null) {
            sampleName = info.getProperty("sample_name", tileFolderFile.getName());
            modality = info.getProperty("modality");
            objective = info.getProperty("objective");
            String parentImageName = info.getProperty("parent_image");
            parentEntry = findParentEntry(project, parentImageName);
            logger.info(
                    "Recovery using acquisition info: sample={}, modality={}, objective={}, parent={}",
                    sampleName,
                    modality,
                    objective,
                    parentImageName);
        } else {
            // Fallback: no acquisition_info.txt (old acquisitions)
            sampleName = tileFolderFile.getName();
            logger.info("No acquisition_info.txt found -- using folder name as sample: {}", sampleName);
        }

        // Compute the next available image index for this sample
        int imageIndex = 1;
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String name = entry.getImageName();
            if (name != null && name.startsWith(sampleName)) {
                imageIndex++;
            }
        }

        // Output goes to a "SlideImages" sibling directory (or same parent if no parent)
        File parentDir = tileFolderFile.getParentFile();
        String outputFolder;
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

        // Discover angle subdirectories that match and have TileConfiguration.txt
        java.util.List<File> angleDirs = new java.util.ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tileFolderFile.toPath())) {
            for (Path path : stream) {
                if (Files.isDirectory(path)
                        && path.getFileName().toString().contains(matchingString)
                        && Files.exists(path.resolve("TileConfiguration.txt"))) {
                    angleDirs.add(path.toFile());
                }
            }
        } catch (IOException e) {
            logger.error("Error scanning for angle subdirectories: {}", e.getMessage());
        }

        // Sort for deterministic processing order
        angleDirs.sort(java.util.Comparator.comparing(File::getName));

        if (angleDirs.isEmpty()) {
            // No matching subdirectories -- check if root folder itself has TileConfiguration.txt
            if (new File(tileFolder, "TileConfiguration.txt").exists()) {
                logger.info("No angle subdirectories found. Processing root folder directly.");
                angleDirs.add(tileFolderFile);
            } else {
                logger.error("No TileConfiguration.txt files found in {} or its subdirectories", tileFolder);
                Platform.runLater(() -> Dialogs.showErrorMessage(
                        "No Tiles Found",
                        "No TileConfiguration.txt files found in the selected folder or its subdirectories."));
                return;
            }
        }

        logger.info("Found {} angle(s) to process: {}", angleDirs.size(), angleDirs);
        final String displaySampleName = sampleName;
        Platform.runLater(() -> Dialogs.showInfoNotification(
                "Stitching Started",
                String.format("Re-stitching %d angle(s) from: %s", angleDirs.size(), displaySampleName)));

        int successCount = 0;
        int failureCount = 0;

        // Capture fields for use inside lambda (must be effectively final)
        final String finalSampleName = sampleName;
        final String finalModality = modality;
        final String finalObjective = objective;
        final String finalAnnotationName = annotationName;
        final ProjectImageEntry<BufferedImage> finalParentEntry = parentEntry;
        final int finalImageIndex = imageIndex;

        // Process each angle individually so we can import immediately after each completes
        for (int i = 0; i < angleDirs.size(); i++) {
            File angleDir = angleDirs.get(i);
            String angleName = angleDir.getName();
            boolean isRootDir = angleDir.equals(tileFolderFile);

            logger.info("=== Processing angle {}/{}: '{}' ===", i + 1, angleDirs.size(), angleName);

            try {
                // Each angle is stitched independently by pointing StitchingWorkflow
                // at the angle directory directly. TileConfigurationTxtStrategy will
                // fall back to root-directory processing when no matching subdirs exist.
                StitchingConfig config = new StitchingConfig(
                        "Coordinates in TileConfiguration.txt file",
                        angleDir.getAbsolutePath(),
                        outputFolder,
                        compression,
                        pixelSize,
                        1, // downsample
                        ".", // match everything in this single-angle directory
                        1.0, // zSpacingMicrons
                        outputFormat);

                // Generate output filename using ImageNameGenerator (respects Preferences)
                String extension = outputFormat == StitchingConfig.OutputFormat.OME_ZARR ? ".ome.zarr" : ".ome.tif";
                String generatedName = ImageNameGenerator.generateImageName(
                        sampleName,
                        imageIndex,
                        modality,
                        objective,
                        annotationName,
                        isRootDir ? null : angleName,
                        extension);
                // Strip extension since StitchingWorkflow appends it
                config.outputFilename = GeneralTools.stripExtension(generatedName);

                String outPath = StitchingWorkflow.run(config);

                if (outPath == null) {
                    logger.error("Stitching returned null for angle '{}'", angleName);
                    failureCount++;
                    continue;
                }

                logger.info("Stitching completed for '{}': {}", angleName, outPath);
                successCount++;

                // Import immediately to the project with metadata
                final String finalOutPath = outPath;
                final String finalAngle = isRootDir ? null : angleName;
                final int angleNum = i + 1;
                final int totalAngles = angleDirs.size();

                Platform.runLater(() -> {
                    try {
                        File outputFile = new File(finalOutPath);

                        QPProjectFunctions.addImageToProjectWithMetadata(
                                project,
                                outputFile,
                                finalParentEntry,
                                0, // xOffset -- unknown in recovery
                                0, // yOffset -- unknown in recovery
                                false, // isFlippedX -- stitched images don't need flipping
                                false, // isFlippedY -- stitched images don't need flipping
                                finalSampleName,
                                finalModality,
                                finalObjective,
                                finalAngle,
                                finalAnnotationName,
                                finalImageIndex,
                                null); // modalityHandler

                        gui.refreshProject();

                        logger.info("Imported angle '{}' to project ({}/{})", finalAngle, angleNum, totalAngles);
                        Dialogs.showInfoNotification(
                                "Angle Imported",
                                String.format("Imported %s (%d/%d)", outputFile.getName(), angleNum, totalAngles));
                    } catch (IOException e) {
                        logger.error("Failed to import {}: {}", finalOutPath, e.getMessage());
                    }
                });

            } catch (Exception e) {
                logger.error("Exception processing angle '{}': {}", angleName, e.getMessage(), e);
                failureCount++;
            }
        }

        // Final summary
        final int finalSuccess = successCount;
        final int finalFailure = failureCount;
        logger.info("=== STITCHING RECOVERY COMPLETE: {} succeeded, {} failed ===", successCount, failureCount);

        Platform.runLater(() -> {
            if (finalSuccess > 0 && finalFailure == 0) {
                Dialogs.showInfoNotification(
                        "Stitching Recovery Complete",
                        String.format("All %d angle(s) stitched and imported successfully.", finalSuccess));
            } else if (finalSuccess > 0) {
                Dialogs.showWarningNotification(
                        "Stitching Recovery Partial",
                        String.format("%d angle(s) succeeded, %d failed.", finalSuccess, finalFailure));
            } else {
                Dialogs.showErrorMessage("Stitching Recovery Failed", "No angles were successfully stitched.");
            }
        });
    }

    /**
     * Reads acquisition metadata from the scan type directory (parent of the
     * selected tile folder).
     *
     * @param tileFolderFile The tile folder selected by the user
     * @return Properties with acquisition info, or null if file not found/unreadable
     */
    private static Properties readAcquisitionInfo(File tileFolderFile) {
        // Scan type dir is the parent of the tile folder
        // e.g., tileFolderFile = .../ppm_20x_2/71940_42472
        //        parent        = .../ppm_20x_2/
        File scanTypeDir = tileFolderFile.getParentFile();
        if (scanTypeDir == null) return null;

        File infoFile = new File(scanTypeDir, "acquisition_info.txt");
        if (!infoFile.exists()) return null;

        Properties props = new Properties();
        try (BufferedReader r = Files.newBufferedReader(infoFile.toPath(), StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            logger.warn("Could not read acquisition info: {}", e.getMessage());
            return null;
        }
        logger.info("Read acquisition info from: {}", infoFile);
        return props;
    }

    /**
     * Finds a project image entry by its image name.
     *
     * @param project The QuPath project to search
     * @param parentImageName The image name to match
     * @return The matching entry, or null if not found
     */
    private static ProjectImageEntry<BufferedImage> findParentEntry(
            Project<BufferedImage> project, String parentImageName) {
        if (parentImageName == null || parentImageName.isEmpty()) return null;
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            if (parentImageName.equals(entry.getImageName())) {
                return entry;
            }
        }
        logger.warn("Parent image '{}' not found in project", parentImageName);
        return null;
    }
}
