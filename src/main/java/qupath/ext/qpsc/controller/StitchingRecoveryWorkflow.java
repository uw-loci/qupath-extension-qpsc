package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;
import qupath.ext.qpsc.utilities.ImageNameGenerator;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.QPProjectFunctions;
import qupath.ext.qpsc.utilities.StitchRetryExecutor;
import qupath.ext.qpsc.utilities.TileFolderInspector;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
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
                // Resolve pixel size: acquisition_info.txt -> microscope config -> tile metadata.
                PixelSizeResolution ps = resolveRecoveryPixelSize(selected);
                if (ps != null) {
                    pixelField.setText(String.valueOf(ps.micronsPerPixel()));
                    pixelSourceLabel.setText(ps.source());
                    logger.info("Resolved recovery pixel size: {} um {}", ps.micronsPerPixel(), ps.source());
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

        // Output format -- exposes all three options (OME_TIFF, OME_ZARR,
        // OME_TIFF_VIA_ZARR). Declared before compression so the format-change
        // listener can re-filter the compression choices.
        Label formatLabel = new Label("Output format:");
        ComboBox<StitchingConfig.OutputFormat> formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll(StitchingConfig.OutputFormat.values());
        try {
            formatCombo.setValue(QPPreferenceDialog.getOutputFormatProperty());
        } catch (Exception e) {
            formatCombo.setValue(StitchingConfig.OutputFormat.OME_TIFF);
        }

        // Compression -- choices filtered by the current format. Mirrors
        // QPPreferenceDialog.getCompressionTypesForFormat so the recovery
        // dialog can never select a codec the writer would reject (the
        // previous hardcoded "Uncompressed" / "zstd" strings would throw
        // IllegalArgumentException at UtilityFunctions.getCompressionType
        // for the TIFF path).
        Label compressionLabel = new Label("Compression:");
        ComboBox<OMEPyramidWriter.CompressionType> compressionCombo = new ComboBox<>();
        compressionCombo.getItems().setAll(QPPreferenceDialog.getCompressionTypesForFormat(formatCombo.getValue()));
        OMEPyramidWriter.CompressionType prefCompression;
        try {
            prefCompression = QPPreferenceDialog.getCompressionTypeProperty();
        } catch (Exception e) {
            prefCompression = OMEPyramidWriter.CompressionType.LZW;
        }
        compressionCombo.setValue(
                compressionCombo.getItems().contains(prefCompression)
                        ? prefCompression
                        : OMEPyramidWriter.CompressionType.LZW);

        formatCombo.valueProperty().addListener((obs, oldFormat, newFormat) -> {
            List<OMEPyramidWriter.CompressionType> allowed = QPPreferenceDialog.getCompressionTypesForFormat(newFormat);
            OMEPyramidWriter.CompressionType current = compressionCombo.getValue();
            compressionCombo.getItems().setAll(allowed);
            compressionCombo.setValue(allowed.contains(current) ? current : OMEPyramidWriter.CompressionType.LZW);
        });

        // Matching string
        Label matchLabel = new Label("Matching string:");
        TextField matchField = new TextField(".");
        matchField.setPromptText(". = all subdirs, or specific angle like 0.0");
        matchField.setPrefWidth(200);

        // Parallel angles checkbox
        CheckBox parallelCheck = new CheckBox("Stitch angles in parallel");
        parallelCheck.setSelected(PersistentPreferences.getRestitchParallelAngles());
        parallelCheck.setTooltip(new Tooltip("Stitch all angle directories simultaneously. Faster on SSDs.\n"
                + "Disable for spinning disk HDDs to avoid I/O thrashing."));

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
        grid.add(formatLabel, 0, 4);
        grid.add(formatCombo, 1, 4);
        grid.add(compressionLabel, 0, 5);
        grid.add(compressionCombo, 1, 5);
        grid.add(matchLabel, 0, 6);
        grid.add(matchField, 1, 6);
        grid.add(parallelCheck, 0, 7, 3, 1);

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
                PersistentPreferences.setRestitchParallelAngles(parallelCheck.isSelected());
                // Compression must be passed as the enum's name() so
                // UtilityFunctions.getCompressionType(...) can map it back via
                // OMEPyramidWriter.CompressionType.valueOf.
                String compression = compressionCombo.getValue().name();
                String matchingString = matchField.getText().trim();
                if (matchingString.isEmpty()) {
                    matchingString = ".";
                }
                StitchingConfig.OutputFormat outputFormat = formatCombo.getValue();
                boolean parallel = parallelCheck.isSelected();

                // Run stitching in background
                final String finalMatch = matchingString;
                Thread stitchThread = new Thread(() -> {
                    executeRecoveryStitching(
                            tileFolder, pixelSize, compression, finalMatch, outputFormat, parallel, gui, project);
                });
                stitchThread.setDaemon(true);
                stitchThread.setName("StitchingRecovery");
                stitchThread.start();
            }
        });
    }

    /** A resolved pixel size (microns/pixel) and a human-readable label for where it came from. */
    private record PixelSizeResolution(double micronsPerPixel, String source) {}

    /**
     * Resolves the best available pixel size for a selected tile folder, in
     * priority order: the {@code pixel_size} recorded in acquisition_info.txt;
     * then the objective + detector resolved against the microscope config;
     * then the tiles' own TIFF resolution metadata.
     *
     * @return the resolved pixel size and its source label, or null if none apply
     */
    private static PixelSizeResolution resolveRecoveryPixelSize(File folder) {
        Properties info = readAcquisitionInfo(folder);
        if (info != null) {
            // 1. Explicit pixel_size recorded at acquisition time (authoritative).
            String recorded = info.getProperty("pixel_size");
            if (recorded != null && !recorded.isBlank()) {
                try {
                    double ps = Double.parseDouble(recorded.trim());
                    if (ps > 0) {
                        return new PixelSizeResolution(ps, "(from acquisition info)");
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to the next source
                }
            }
            // 2. Derive from objective + detector against the microscope config.
            String objective = info.getProperty("objective");
            String detector = info.getProperty("detector_id");
            if (objective != null && !objective.isBlank() && detector != null && !detector.isBlank()) {
                try {
                    MicroscopeConfigManager mgr =
                            MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
                    Double ps = mgr.getHardwarePixelSize(objective, detector);
                    if (ps != null && ps > 0) {
                        return new PixelSizeResolution(ps, "(from config)");
                    }
                } catch (Exception ex) {
                    logger.debug("Could not derive recovery pixel size from config: {}", ex.getMessage());
                }
            }
        }
        // 3. The tiles' own TIFF resolution metadata.
        double detected = TileFolderInspector.detectPixelSizeFromFolder(folder);
        if (detected > 0) {
            return new PixelSizeResolution(detected, "(from tile metadata)");
        }
        return null;
    }

    private static void executeRecoveryStitching(
            String tileFolder,
            double pixelSize,
            String compression,
            String matchingString,
            StitchingConfig.OutputFormat outputFormat,
            boolean parallel,
            QuPathGUI gui,
            Project<BufferedImage> project) {

        logger.info(
                "Executing recovery stitching: folder={}, pixelSize={}, compression={}, match='{}', format={}, parallel={}",
                tileFolder,
                pixelSize,
                compression,
                matchingString,
                outputFormat,
                parallel);

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

        // Resolve the modality handler so the recovery import sets the same
        // image type and channel names as the normal post-acquisition stitch.
        // Passing null here was why recovered images came in as generic R/G/B
        // channels with an unnamed (rather than "PPM Subtracted") channel.
        final ModalityHandler modalityHandler =
                (modality != null && !modality.isBlank()) ? ModalityRegistry.getHandler(modality) : null;

        // Compute the next available image index for this sample
        int imageIndex = 1;
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String name = entry.getImageName();
            if (name != null && name.startsWith(sampleName)) {
                imageIndex++;
            }
        }

        // Output goes to <projectDir>/SlideImages, matching the regular
        // acquisition path (TileProcessingUtilities.stitchImagesAndUpdateProject
        // line 131 builds <projectsFolder>/<sampleLabel>/SlideImages).
        // project.getPath() is .../<sampleName>/<sampleName>.qpproj, so
        // project.getPath().getParent() resolves to the sample folder.
        // Previously this used tileFolder.getParent()/SlideImages, which placed
        // output under the imaging-mode folder (or the annotation folder for
        // legacy _temp_* selections) instead of alongside the project.
        final String outputFolder = resolveSlideImagesFolder(project, tileFolderFile);

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

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        // Collected for the OME_TIFF_VIA_ZARR background conversion at the end.
        // Synchronized because angle workers may run in parallel.
        java.util.List<String> successfulOutputs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        // Capture fields for use inside lambda (must be effectively final)
        final String finalSampleName = sampleName;
        final String finalModality = modality;
        final String finalObjective = objective;
        final String finalAnnotationName = annotationName;
        final ProjectImageEntry<BufferedImage> finalParentEntry = parentEntry;
        final int finalImageIndex = imageIndex;
        final int totalAngles = angleDirs.size();

        // Determine thread pool size: all angles in parallel, or 1 for sequential
        int threadCount = parallel ? angleDirs.size() : 1;
        logger.info("Processing {} angle(s) with {} thread(s)", angleDirs.size(), threadCount);
        ExecutorService stitchPool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("recovery-stitch");
            return t;
        });

        // Submit all angles to the pool
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < angleDirs.size(); i++) {
            final File angleDir = angleDirs.get(i);
            final String angleName = angleDir.getName();
            final boolean isRootDir = angleDir.equals(tileFolderFile);
            final int angleNum = i + 1;

            // Capture loop-scoped copies for filename generation
            final String fnSampleName = sampleName;
            final String fnModality = modality;
            final String fnObjective = objective;
            final String fnAnnotationName = annotationName;
            final int fnImageIndex = imageIndex;
            final String fnOutputFolder = outputFolder;

            futures.add(stitchPool.submit(() -> {
                logger.info("=== Processing angle {}/{}: '{}' ===", angleNum, totalAngles, angleName);

                try {
                    // Builds a config for a requested output format. The
                    // stitcher appends "_<subdirName>" (the angle dir name) so
                    // it produces "<sampleName>_<angle>.<ext>"; the user-pattern
                    // rename happens after the stitch (below). Pushing the full
                    // ImageNameGenerator name in here caused duplication because
                    // the index landed before the appended subdir name.
                    StitchRetryExecutor.ConfigFactory configFactory = fmt -> {
                        StitchingConfig config = new StitchingConfig(
                                "Coordinates in TileConfiguration.txt file",
                                angleDir.getAbsolutePath(),
                                fnOutputFolder,
                                compression,
                                pixelSize,
                                1, // downsample
                                ".", // match everything in this single-angle directory
                                1.0, // zSpacingMicrons
                                fmt);
                        config.outputFilename = ImageNameGenerator.sanitizeForFilename(fnSampleName);
                        return config;
                    };

                    // Composite stage/camera transform; must match the
                    // TileProcessingUtilities main-acquisition path so
                    // re-stitching reproduces the original layout.
                    boolean[] stitcherFlags = qupath.ext.qpsc.utilities.StageImageTransform.current()
                            .stitcherFlipFlags();

                    // Between-retry cleanup: remove the angle's partial output
                    // so a retry's rename-over does not fail on Windows.
                    final String outputStem =
                            ImageNameGenerator.sanitizeForFilename(fnSampleName) + "_" + angleDir.getName();
                    Runnable cleanup = () -> deleteStitchOutputs(new File(fnOutputFolder), outputStem);

                    // Detect OMEPyramidWriter tile-write errors, retry, and
                    // escalate to ZARR. Non-interactive: the recovery workflow
                    // shows its own end-of-run summary, so no per-angle dialog.
                    String stitchedOutPath = StitchRetryExecutor.run(
                            outputFormat, configFactory, stitcherFlags, cleanup, angleName, false);

                    // run() returns a real path or throws. Escalation may have
                    // produced a .ome.zarr even when the user picked OME_TIFF,
                    // so derive the extension from the actual output path.
                    String extension = stitchedOutPath.endsWith(".ome.zarr") ? ".ome.zarr" : ".ome.tif";
                    String desiredName = ImageNameGenerator.generateImageName(
                            fnSampleName,
                            fnImageIndex,
                            fnModality,
                            fnObjective,
                            fnAnnotationName,
                            isRootDir ? null : angleName,
                            extension);
                    final String outPath = renameStitchedOutput(stitchedOutPath, desiredName, extension);

                    logger.info("Stitching completed for '{}': {}", angleName, outPath);
                    successCount.incrementAndGet();
                    successfulOutputs.add(outPath);

                    // Import to project with metadata
                    final String finalAngle = isRootDir ? null : angleName;
                    Platform.runLater(() -> {
                        try {
                            File outputFile = new File(outPath);

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
                                    modalityHandler);

                            gui.refreshProject();

                            logger.info("Imported angle '{}' to project ({}/{})", finalAngle, angleNum, totalAngles);
                            Dialogs.showInfoNotification(
                                    "Angle Imported",
                                    String.format("Imported %s (%d/%d)", outputFile.getName(), angleNum, totalAngles));
                        } catch (IOException e) {
                            logger.error("Failed to import {}: {}", outPath, e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    logger.error("Exception processing angle '{}': {}", angleName, e.getMessage(), e);
                    failureCount.incrementAndGet();
                }
            }));
        }

        // Wait for all angles to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("Angle stitching future failed: {}", e.getMessage());
            }
        }
        stitchPool.shutdown();

        // Final summary
        final int finalSuccess = successCount.get();
        final int finalFailure = failureCount.get();
        logger.info("=== STITCHING RECOVERY COMPLETE: {} succeeded, {} failed ===", finalSuccess, finalFailure);

        // Queue the background ZARR->TIFF conversion for any output that is a
        // .ome.zarr but whose requested final format is TIFF. Covers both an
        // OME_TIFF_VIA_ZARR preference and an OME_TIFF stitch that the retry
        // logic escalated to ZARR. Mirrors the main acquisition path.
        if (outputFormat.finalFormatIsTiff()) {
            var zarrOutputs = successfulOutputs.stream()
                    .filter(p -> p.endsWith(".ome.zarr"))
                    .collect(java.util.stream.Collectors.toList());
            if (!zarrOutputs.isEmpty()) {
                StitchingHelper.queueBackgroundZarrToTiffConversion(
                        zarrOutputs, compression, "Recovery: " + sampleName);
            }
        }

        Platform.runLater(() -> {
            if (finalSuccess > 0 && finalFailure == 0) {
                Dialogs.showInfoNotification(
                        "Stitching Recovery Complete",
                        String.format("All %d angle(s) stitched and imported successfully.", finalSuccess));
            } else if (finalSuccess > 0) {
                Dialogs.showWarningNotification(
                        "Stitching Recovery Partial",
                        String.format(
                                "%d angle(s) succeeded, %d failed.%n"
                                        + "Failed angles could not be stitched even after retries "
                                        + "and the ZARR fallback (check the log for details).",
                                finalSuccess, finalFailure));
            } else {
                Dialogs.showErrorMessage(
                        "Stitching Recovery Failed",
                        "No angles were successfully stitched, even after retries and the "
                                + "ZARR fallback. Check the log for stitching errors.");
            }
        });

        // Send push notification for recovery results
        if (finalFailure == 0 && finalSuccess > 0) {
            NotificationService.getInstance()
                    .notify(
                            "Stitching Recovery Complete",
                            "Sample \"" + sampleName + "\" - " + finalSuccess + " angle(s) stitched successfully",
                            NotificationPriority.DEFAULT,
                            NotificationEvent.STITCHING_COMPLETE);
        } else if (finalFailure > 0) {
            NotificationService.getInstance()
                    .notify(
                            "Stitching Recovery " + (finalSuccess > 0 ? "Partial" : "Failed"),
                            "Sample \"" + sampleName + "\"\n" + finalSuccess + " succeeded, " + finalFailure
                                    + " failed",
                            NotificationPriority.HIGH,
                            NotificationEvent.STITCHING_ERROR);
        }
    }

    /**
     * Deletes an angle's partial stitch output ({@code .ome.tif},
     * {@code .ome.zarr}, or the {@code .writing.ome.tif} temp) before a retry,
     * so the retry's rename-over does not fail on a stale file.
     */
    private static void deleteStitchOutputs(File dir, String stem) {
        for (String ext : new String[] {".ome.tif", ".ome.zarr", ".writing.ome.tif"}) {
            File f = new File(dir, stem + ext);
            if (!f.exists()) {
                continue;
            }
            logger.info("Removing partial stitch output before retry: {}", f.getName());
            try {
                if (f.isDirectory()) {
                    try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(f.toPath())) {
                        walk.sorted(java.util.Comparator.reverseOrder())
                                .map(java.nio.file.Path::toFile)
                                .forEach(File::delete);
                    }
                } else {
                    java.nio.file.Files.deleteIfExists(f.toPath());
                }
            } catch (Exception e) {
                logger.warn("Could not delete {}: {}", f.getName(), e.getMessage());
            }
        }
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

    /**
     * Resolve the SlideImages output folder for recovery. Prefers the
     * project's parent directory (matches the regular acquisition path's
     * sample-level SlideImages folder), falling back to the tile folder's
     * parent if the project path is unavailable.
     */
    private static String resolveSlideImagesFolder(Project<BufferedImage> project, File tileFolderFile) {
        try {
            File projectDir = project.getPath().getParent().toFile();
            return new File(projectDir, "SlideImages").getAbsolutePath();
        } catch (Exception e) {
            File parentDir = tileFolderFile.getParentFile();
            String fallback = new File(parentDir, "SlideImages").getAbsolutePath();
            logger.warn("Could not resolve project directory for SlideImages output; falling back to {}", fallback);
            return fallback;
        }
    }

    /**
     * Rename a stitcher output (TIFF file or ZARR directory) to the configured
     * naming pattern, returning the new absolute path. Falls back to the
     * original path when rename fails or the target equals the source.
     *
     * <p>If the desired name already exists, appends {@code _2}, {@code _3}, ...
     * before the extension until a unique name is found, matching the
     * {@code UtilityFunctions.getUniqueFilePath} convention.
     *
     * @param stitchedPath absolute path the stitcher returned
     * @param desiredName  bare filename produced by {@link ImageNameGenerator}
     *                     (includes extension)
     * @param extension    expected extension, either {@code .ome.tif} or
     *                     {@code .ome.zarr}
     */
    private static String renameStitchedOutput(String stitchedPath, String desiredName, String extension) {
        File initial = new File(stitchedPath);
        File desired = new File(initial.getParent(), desiredName);
        if (initial.getAbsolutePath().equals(desired.getAbsolutePath())) {
            return stitchedPath;
        }
        File target = uniqueWithExtension(desired, extension);
        if (initial.renameTo(target)) {
            logger.info("Renamed stitched output to configured pattern: {} -> {}", initial.getName(), target.getName());
            return target.getAbsolutePath();
        }
        logger.warn(
                "Could not rename stitched output {} -> {}; keeping stitcher default name",
                initial.getName(),
                target.getName());
        return stitchedPath;
    }

    /**
     * Append {@code _2}, {@code _3}, ... before the extension until the
     * candidate path does not exist. If {@code file} already doesn't exist,
     * returns it unchanged.
     */
    private static File uniqueWithExtension(File file, String extension) {
        if (!file.exists()) return file;
        String name = file.getName();
        String stem = name.endsWith(extension) ? name.substring(0, name.length() - extension.length()) : name;
        int n = 2;
        while (true) {
            File candidate = new File(file.getParent(), stem + "_" + n + extension);
            if (!candidate.exists()) return candidate;
            n++;
        }
    }
}
