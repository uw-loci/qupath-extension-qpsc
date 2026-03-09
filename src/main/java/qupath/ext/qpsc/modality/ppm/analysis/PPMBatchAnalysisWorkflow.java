package qupath.ext.qpsc.modality.ppm.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.ppm.PPMPreferences;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager.PPMAnalysisSet;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.fx.dialogs.Dialogs;

/**
 * Batch PPM analysis workflow: discovers PPM analysis sets across a project,
 * presents a selection UI, and runs polarity and/or perpendicularity analysis
 * on all annotations, storing results as measurements and exporting CSV.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMBatchAnalysisWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMBatchAnalysisWorkflow.class);

    private static final double DEFAULT_BIREF_THRESHOLD = 100.0;
    private static final int DEFAULT_HISTOGRAM_BINS = 18;

    private static final AtomicBoolean cancelled = new AtomicBoolean(false);

    private PPMBatchAnalysisWorkflow() {}

    /**
     * Main entry point.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run batch analysis workflow", e);
                Dialogs.showErrorMessage("Batch PPM Analysis", "Error: " + e.getMessage());
            }
        });
    }

    // ========================================================================
    // Discovery
    // ========================================================================

    private static void runOnFXThread() {
        QuPathGUI gui = QPEx.getQuPath();
        if (gui == null) {
            Dialogs.showErrorMessage("Batch PPM Analysis", "QuPath is not available.");
            return;
        }

        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("Batch PPM Analysis",
                    "A QuPath project is required. Create or open a project first.");
            return;
        }

        // Discover all PPM analysis sets
        List<PPMBatchAnalysisPanel.AnalysisSetItem> discoveredSets = discoverAnalysisSets(project);

        if (discoveredSets.isEmpty()) {
            Dialogs.showErrorMessage("Batch PPM Analysis",
                    "No qualified PPM analysis sets found in this project.\n\n"
                            + "Requirements: PPM modality images with a sum image and calibration.");
            return;
        }

        // Collect all annotation classes across all sum images
        List<String> allClasses = collectAnnotationClasses(project, discoveredSets);

        // Show configuration panel
        showConfigPanel(gui, project, discoveredSets, allClasses);
    }

    /**
     * Discovers all PPM analysis sets in the project.
     *
     * <p>Groups images by (image_collection, annotation_name, sample_name),
     * identifies sum/biref/angle images within each group, and checks for
     * calibration availability.</p>
     */
    @SuppressWarnings("unchecked")
    private static List<PPMBatchAnalysisPanel.AnalysisSetItem> discoverAnalysisSets(
            Project<BufferedImage> project) {

        // Group PPM images by (collection, annotation_name, sample_name)
        Map<String, List<ProjectImageEntry<BufferedImage>>> groups = new LinkedHashMap<>();

        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String modality = entry.getMetadata().get(ImageMetadataManager.MODALITY);
            if (modality == null || !modality.toLowerCase().startsWith("ppm")) continue;

            int collection = ImageMetadataManager.getImageCollection(entry);
            if (collection < 0) continue;

            String sample = ImageMetadataManager.getSampleName(entry);
            String annotation = entry.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);

            String key = collection + "|" + Objects.toString(sample, "") + "|"
                    + Objects.toString(annotation, "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        }

        List<PPMBatchAnalysisPanel.AnalysisSetItem> items = new ArrayList<>();

        for (Map.Entry<String, List<ProjectImageEntry<BufferedImage>>> groupEntry : groups.entrySet()) {
            List<ProjectImageEntry<BufferedImage>> members = groupEntry.getValue();

            // Find sum image and calibration
            ProjectImageEntry<BufferedImage> sumImage = null;
            boolean hasBiref = false;
            String calibrationPath = null;

            for (ProjectImageEntry<BufferedImage> member : members) {
                String angle = member.getMetadata().get(ImageMetadataManager.ANGLE);
                String name = member.getImageName().toLowerCase();

                if (isSumImage(angle, name)) {
                    sumImage = member;
                } else if (isBirefImage(angle, name)) {
                    hasBiref = true;
                }

                String cal = member.getMetadata().get(ImageMetadataManager.PPM_CALIBRATION);
                if (cal != null && !cal.isEmpty() && calibrationPath == null) {
                    calibrationPath = cal;
                }
            }

            // Fall back to active calibration
            if (calibrationPath == null) {
                String activeCal = PPMPreferences.getActiveCalibrationPath();
                if (activeCal != null && !activeCal.isEmpty()) {
                    calibrationPath = activeCal;
                }
            }

            // Must have sum + calibration to qualify
            if (sumImage == null || calibrationPath == null) {
                logger.debug("Skipping group {} - sum={}, cal={}",
                        groupEntry.getKey(),
                        sumImage != null ? "yes" : "no",
                        calibrationPath != null ? "yes" : "no");
                continue;
            }

            // Count annotations on the sum image
            int annotationCount = 0;
            try {
                ImageData<BufferedImage> imgData =
                        (ImageData<BufferedImage>) sumImage.readImageData();
                annotationCount = imgData.getHierarchy().getAnnotationObjects().size();
            } catch (Exception e) {
                logger.debug("Could not read annotations for {}: {}",
                        sumImage.getImageName(), e.getMessage());
            }

            int collection = ImageMetadataManager.getImageCollection(sumImage);
            String sample = ImageMetadataManager.getSampleName(sumImage);
            String annotation = sumImage.getMetadata().get(ImageMetadataManager.ANNOTATION_NAME);

            String display = String.format("[%d] %s%s%s (%d annotations%s)",
                    collection,
                    sumImage.getImageName(),
                    sample != null ? " | " + sample : "",
                    annotation != null ? " | " + annotation : "",
                    annotationCount,
                    hasBiref ? ", +biref" : "");

            items.add(new PPMBatchAnalysisPanel.AnalysisSetItem(
                    display, sumImage.getImageName(), collection,
                    sample, annotation, calibrationPath, hasBiref, annotationCount));
        }

        logger.info("Discovered {} qualified PPM analysis sets", items.size());
        return items;
    }

    /**
     * Collects all annotation classes from the sum images of discovered sets.
     */
    @SuppressWarnings("unchecked")
    private static List<String> collectAnnotationClasses(
            Project<BufferedImage> project,
            List<PPMBatchAnalysisPanel.AnalysisSetItem> sets) {

        Set<String> classes = new LinkedHashSet<>();

        for (PPMBatchAnalysisPanel.AnalysisSetItem item : sets) {
            // Find the sum image entry
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                if (entry.getImageName().equals(item.imageName)) {
                    try {
                        ImageData<BufferedImage> imgData =
                                (ImageData<BufferedImage>) entry.readImageData();
                        for (PathObject ann : imgData.getHierarchy().getAnnotationObjects()) {
                            PathClass pc = ann.getPathClass();
                            if (pc != null) {
                                classes.add(pc.toString());
                            }
                        }
                    } catch (Exception e) {
                        // skip
                    }
                    break;
                }
            }
        }

        return new ArrayList<>(classes);
    }

    // ========================================================================
    // Config UI
    // ========================================================================

    private static void showConfigPanel(
            QuPathGUI gui,
            Project<BufferedImage> project,
            List<PPMBatchAnalysisPanel.AnalysisSetItem> discoveredSets,
            List<String> allClasses) {

        Stage dialog = new Stage();
        dialog.setTitle("Batch PPM Analysis");
        dialog.initOwner(gui.getStage());

        javafx.scene.control.Button helpButton = DocumentationHelper.createHelpButton("ppmBatchAnalysis");
        PPMBatchAnalysisPanel panel = new PPMBatchAnalysisPanel(discoveredSets, allClasses);

        panel.setOnCancel(dialog::close);
        panel.setOnRun(() -> {
            List<PPMBatchAnalysisPanel.AnalysisSetItem> selected = panel.getSelectedItems();
            if (selected.isEmpty()) {
                Dialogs.showErrorMessage("Batch PPM Analysis",
                        "No analysis sets selected.");
                return;
            }
            if (!panel.isPolaritySelected() && !panel.isPerpendicularitySelected()) {
                Dialogs.showErrorMessage("Batch PPM Analysis",
                        "Select at least one analysis type.");
                return;
            }
            if (panel.isPerpendicularitySelected()
                    && (panel.getBoundaryClass() == null || panel.getBoundaryClass().isEmpty())) {
                Dialogs.showErrorMessage("Batch PPM Analysis",
                        "Select a boundary annotation class for perpendicularity analysis.");
                return;
            }

            dialog.close();

            // Run in background
            cancelled.set(false);
            runBatchAnalysis(gui, project, selected,
                    panel.isPolaritySelected(),
                    panel.isPerpendicularitySelected(),
                    panel.getBoundaryClass(),
                    panel.getDilationUm(),
                    panel.getZoneMode(),
                    panel.getTacsThreshold(),
                    panel.getFillHoles());
        });

        VBox dialogRoot = new VBox(panel);
        if (helpButton != null) {
            javafx.scene.layout.HBox helpBar = new javafx.scene.layout.HBox(helpButton);
            helpBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            helpBar.setPadding(new Insets(5, 10, 0, 10));
            dialogRoot.getChildren().add(0, helpBar);
        }
        javafx.scene.layout.VBox.setVgrow(panel, javafx.scene.layout.Priority.ALWAYS);
        dialog.setScene(new Scene(dialogRoot, 600, 700));
        dialog.show();
    }

    // ========================================================================
    // Batch execution
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static void runBatchAnalysis(
            QuPathGUI gui,
            Project<BufferedImage> project,
            List<PPMBatchAnalysisPanel.AnalysisSetItem> selectedSets,
            boolean runPolarity,
            boolean runPerpendicularity,
            String boundaryClass,
            double dilationUm,
            String zoneMode,
            double tacsThreshold,
            boolean fillHoles) {

        // Show progress
        Stage progressStage = new Stage();
        progressStage.setTitle("Batch PPM Analysis - Running");
        progressStage.initOwner(gui.getStage());

        javafx.scene.control.Label progressLabel = new javafx.scene.control.Label("Starting...");
        progressLabel.setWrapText(true);
        javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setPrefWidth(500);
        javafx.scene.control.Button cancelBtn = new javafx.scene.control.Button("Cancel");
        cancelBtn.setOnAction(e -> {
            cancelled.set(true);
            cancelBtn.setDisable(true);
            cancelBtn.setText("Cancelling...");
        });

        VBox progressBox = new VBox(10, progressLabel, progressBar, cancelBtn);
        progressBox.setPadding(new Insets(15));
        progressStage.setScene(new Scene(progressBox, 550, 120));
        progressStage.show();

        Path projectDir = project.getPath().getParent();
        Path outputDir = projectDir.resolve("analysis").resolve("batch_ppm");

        CompletableFuture.runAsync(() -> {
            PPMBatchResultsWriter writer = new PPMBatchResultsWriter();
            int totalSets = selectedSets.size();
            int processedAnnotations = 0;
            int errors = 0;

            for (int setIdx = 0; setIdx < totalSets; setIdx++) {
                if (cancelled.get()) break;

                PPMBatchAnalysisPanel.AnalysisSetItem item = selectedSets.get(setIdx);
                final int setNum = setIdx + 1;

                updateProgress(progressLabel, progressBar,
                        String.format("Set %d/%d: %s", setNum, totalSets, item.imageName),
                        (double) setIdx / totalSets);

                // Find the sum image entry
                ProjectImageEntry<BufferedImage> sumEntry = null;
                for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                    if (entry.getImageName().equals(item.imageName)) {
                        sumEntry = entry;
                        break;
                    }
                }
                if (sumEntry == null) {
                    logger.warn("Could not find sum image entry: {}", item.imageName);
                    errors++;
                    continue;
                }

                try {
                    ImageData<BufferedImage> imageData =
                            (ImageData<BufferedImage>) sumEntry.readImageData();
                    ImageServer<BufferedImage> sumServer = imageData.getServer();

                    // Get pixel size
                    PixelCalibration pixelCal = sumServer.getPixelCalibration();
                    double pixelSizeUm = pixelCal.hasPixelSizeMicrons()
                            ? pixelCal.getAveragedPixelSizeMicrons() : 1.0;

                    // Find biref server if available
                    PPMAnalysisSet analysisSet = ImageMetadataManager.findPPMAnalysisSet(
                            sumEntry, project);

                    // Get annotations to process
                    Collection<PathObject> annotations =
                            imageData.getHierarchy().getAnnotationObjects();

                    if (annotations.isEmpty()) {
                        logger.info("No annotations on {}, skipping", item.imageName);
                        continue;
                    }

                    String collectionStr = String.valueOf(item.imageCollection);

                    // Pass 1: Polarity analysis on ALL annotations
                    if (runPolarity) {
                        int annIdx = 0;
                        int totalAnn = annotations.size();
                        for (PathObject annotation : annotations) {
                            if (cancelled.get()) break;

                            annIdx++;
                            String annName = annotation.getDisplayedName();
                            String annClass = annotation.getPathClass() != null
                                    ? annotation.getPathClass().toString() : "";

                            updateProgress(progressLabel, progressBar,
                                    String.format("Set %d/%d: %s - polarity %d/%d: %s",
                                            setNum, totalSets, item.imageName,
                                            annIdx, totalAnn, annName),
                                    ((double) setIdx + (double) annIdx / totalAnn * 0.5)
                                            / totalSets);

                            ROI roi = annotation.getROI();
                            if (roi == null) continue;

                            try {
                                JsonObject polarityResult = runPolarityForAnnotation(
                                        sumServer, roi, item.calibrationPath, analysisSet);

                                writer.storePolarityMeasurements(annotation, polarityResult);
                                writer.addPolarityRow(item.imageName, collectionStr,
                                        item.sampleName, annName, annClass, polarityResult);

                                processedAnnotations++;
                            } catch (Exception e) {
                                logger.warn("Polarity failed for {} / {}: {}",
                                        item.imageName, annName, e.getMessage());
                                errors++;
                            }
                        }
                    }

                    // Pass 2: Perpendicularity on boundary-class annotations only
                    if (runPerpendicularity && boundaryClass != null) {
                        List<PathObject> boundaryAnnotations = annotations.stream()
                                .filter(a -> a.getPathClass() != null
                                        && a.getPathClass().toString().equals(boundaryClass))
                                .collect(Collectors.toList());

                        int bIdx = 0;
                        int totalB = boundaryAnnotations.size();
                        for (PathObject boundary : boundaryAnnotations) {
                            if (cancelled.get()) break;

                            bIdx++;
                            updateProgress(progressLabel, progressBar,
                                    String.format("Set %d/%d: %s - perpendicularity %d/%d: %s",
                                            setNum, totalSets, item.imageName,
                                            bIdx, totalB, boundary.getDisplayedName()),
                                    ((double) setIdx + 0.5 + (double) bIdx / totalB * 0.5)
                                            / totalSets);

                            try {
                                JsonObject perpResult = runPerpendicularityForAnnotation(
                                        sumServer, boundary.getROI(),
                                        item.calibrationPath, analysisSet,
                                        pixelSizeUm, dilationUm, zoneMode,
                                        tacsThreshold, fillHoles);

                                writer.storePerpendicularityMeasurements(
                                        boundary, perpResult);
                                writer.addPerpendicularityRow(
                                        item.imageName, collectionStr,
                                        item.sampleName,
                                        boundary.getDisplayedName(),
                                        boundaryClass, perpResult);

                                processedAnnotations++;
                            } catch (Exception e) {
                                logger.warn("Perpendicularity failed for {} / {}: {}",
                                        item.imageName,
                                        boundary.getDisplayedName(),
                                        e.getMessage());
                                errors++;
                            }
                        }
                    }

                    // Save measurements back to project
                    sumEntry.saveImageData(imageData);

                } catch (Exception e) {
                    logger.error("Failed to process set {}: {}",
                            item.imageName, e.getMessage(), e);
                    errors++;
                }
            }

            // Write CSV
            final int finalProcessed = processedAnnotations;
            final int finalErrors = errors;
            try {
                Path csvPath = outputDir.resolve("batch_results.csv");
                int rows = writer.writeCSV(csvPath);

                Platform.runLater(() -> {
                    progressStage.close();
                    String msg = String.format(
                            "Batch analysis complete.\n\n"
                                    + "Annotations processed: %d\n"
                                    + "CSV rows written: %d\n"
                                    + "Errors: %d\n\n"
                                    + "Results saved to:\n%s\n\n"
                                    + "Measurements also stored on annotations\n"
                                    + "(visible in QuPath measurement table).",
                            finalProcessed, rows, finalErrors,
                            csvPath.toString());

                    if (cancelled.get()) {
                        msg = "Analysis cancelled.\n\n" + msg;
                    }

                    Dialogs.showMessageDialog("Batch PPM Analysis", msg);
                });

            } catch (Exception e) {
                logger.error("Failed to write CSV: {}", e.getMessage(), e);
                Platform.runLater(() -> {
                    progressStage.close();
                    Dialogs.showErrorMessage("Batch PPM Analysis",
                            "Analysis completed but CSV write failed: " + e.getMessage()
                                    + "\n\nMeasurements were still stored on annotations.");
                });
            }

            logger.info("Batch PPM analysis complete: {} processed, {} errors",
                    finalProcessed, finalErrors);
        });
    }

    // ========================================================================
    // Per-annotation analysis (reuses CLI patterns from Phase 2b/3)
    // ========================================================================

    private static JsonObject runPolarityForAnnotation(
            ImageServer<BufferedImage> sumServer,
            ROI roi,
            String calibrationPath,
            PPMAnalysisSet analysisSet) throws Exception {

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        RegionRequest request = RegionRequest.createInstance(
                sumServer.getPath(), 1.0, x, y, w, h);

        Path tempDir = Files.createTempDirectory("ppm_batch_pol_");
        try {
            BufferedImage sumRegion = sumServer.readRegion(request);
            Path sumPath = tempDir.resolve("sum_region.tif");
            ImageIO.write(sumRegion, "TIFF", sumPath.toFile());

            // Write biref if available
            Path birefPath = writeBirefRegion(analysisSet, x, y, w, h, tempDir);

            // Write ROI mask
            Path roiMaskPath = tempDir.resolve("roi_mask.tif");
            writeROIMask(roi, x, y, w, h, roiMaskPath);

            // Build command
            List<String> command = new ArrayList<>();
            command.add("python");
            command.add("-m");
            command.add("ppm_library.analysis.cli");
            command.add("--sum");
            command.add(sumPath.toString());
            command.add("--calibration");
            command.add(calibrationPath);
            command.add("--bins");
            command.add(String.valueOf(DEFAULT_HISTOGRAM_BINS));
            command.add("--roi-mask");
            command.add(roiMaskPath.toString());

            if (birefPath != null) {
                command.add("--biref");
                command.add(birefPath.toString());
                command.add("--biref-threshold");
                command.add(String.valueOf(DEFAULT_BIREF_THRESHOLD));
            }

            return callPython(command);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private static JsonObject runPerpendicularityForAnnotation(
            ImageServer<BufferedImage> sumServer,
            ROI roi,
            String calibrationPath,
            PPMAnalysisSet analysisSet,
            double pixelSizeUm,
            double dilationUm,
            String zoneMode,
            double tacsThreshold,
            boolean fillHoles) throws Exception {

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        // Expand for dilation zone
        double dilationPx = dilationUm / pixelSizeUm;
        int pad = (int) Math.ceil(dilationPx) + 5;
        int expandedX = Math.max(0, x - pad);
        int expandedY = Math.max(0, y - pad);
        int expandedW = Math.min(sumServer.getWidth() - expandedX, w + 2 * pad);
        int expandedH = Math.min(sumServer.getHeight() - expandedY, h + 2 * pad);

        RegionRequest request = RegionRequest.createInstance(
                sumServer.getPath(), 1.0, expandedX, expandedY, expandedW, expandedH);

        Path tempDir = Files.createTempDirectory("ppm_batch_perp_");
        try {
            BufferedImage sumRegion = sumServer.readRegion(request);
            Path sumPath = tempDir.resolve("sum_region.tif");
            ImageIO.write(sumRegion, "TIFF", sumPath.toFile());

            Path birefPath = writeBirefRegion(analysisSet,
                    expandedX, expandedY, expandedW, expandedH, tempDir);

            // Export ROI as GeoJSON with offset
            Path geojsonPath = tempDir.resolve("boundary.geojson");
            PPMPerpendicularityWorkflow.exportRoiAsGeoJSON(
                    roi, expandedX, expandedY, geojsonPath);

            List<String> command = new ArrayList<>();
            command.add("python");
            command.add("-m");
            command.add("ppm_library.analysis.cli");
            command.add("--mode");
            command.add("perpendicularity");
            command.add("--sum");
            command.add(sumPath.toString());
            command.add("--calibration");
            command.add(calibrationPath);
            command.add("--boundary");
            command.add(geojsonPath.toString());
            command.add("--pixel-size-um");
            command.add(String.valueOf(pixelSizeUm));
            command.add("--dilation-um");
            command.add(String.valueOf(dilationUm));
            command.add("--zone-mode");
            command.add(zoneMode);
            command.add("--tacs-threshold");
            command.add(String.valueOf(tacsThreshold));

            if (!fillHoles) {
                command.add("--no-fill-holes");
            }

            if (birefPath != null) {
                command.add("--biref");
                command.add(birefPath.toString());
                command.add("--biref-threshold");
                command.add(String.valueOf(DEFAULT_BIREF_THRESHOLD));
            }

            return callPython(command);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    // ========================================================================
    // Shared helpers
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static Path writeBirefRegion(PPMAnalysisSet analysisSet,
            int x, int y, int w, int h, Path tempDir) {
        if (analysisSet == null || !analysisSet.hasBirefImage()) return null;

        try {
            ImageData<BufferedImage> birefData =
                    (ImageData<BufferedImage>) analysisSet.birefImage.readImageData();
            ImageServer<BufferedImage> birefServer = birefData.getServer();
            RegionRequest birefRequest = RegionRequest.createInstance(
                    birefServer.getPath(), 1.0, x, y, w, h);
            BufferedImage birefRegion = birefServer.readRegion(birefRequest);
            Path birefPath = tempDir.resolve("biref_region.tif");
            ImageIO.write(birefRegion, "TIFF", birefPath.toFile());
            birefServer.close();
            return birefPath;
        } catch (Exception e) {
            logger.debug("Could not read biref region: {}", e.getMessage());
            return null;
        }
    }

    private static void writeROIMask(ROI roi, int offsetX, int offsetY,
            int width, int height, Path outputPath) throws Exception {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (roi.contains(offsetX + x + 0.5, offsetY + y + 0.5)) {
                    mask.getRaster().setSample(x, y, 0, 255);
                }
            }
        }
        ImageIO.write(mask, "TIFF", outputPath.toFile());
    }

    private static JsonObject callPython(List<String> command) throws Exception {
        logger.debug("Python: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line);
            }
        }

        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (stderr.length() > 0) {
            logger.debug("Python stderr: {}", stderr.toString().trim());
        }

        if (exitCode != 0) {
            throw new RuntimeException("Python failed (exit " + exitCode + "): "
                    + stderr.toString().trim());
        }

        String jsonStr = stdout.toString().trim();
        if (jsonStr.isEmpty()) {
            throw new RuntimeException("Python returned empty output");
        }

        Gson gson = new Gson();
        JsonObject result = gson.fromJson(jsonStr, JsonObject.class);

        if (result.has("error") && !result.get("error").isJsonNull()) {
            throw new RuntimeException("Python error: " + result.get("error").getAsString());
        }

        return result;
    }

    private static void cleanupTempDir(Path tempDir) {
        try {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (Exception e) {
            logger.debug("Could not clean up temp dir: {}", e.getMessage());
        }
    }

    private static void updateProgress(javafx.scene.control.Label label,
            javafx.scene.control.ProgressBar bar, String text, double progress) {
        Platform.runLater(() -> {
            label.setText(text);
            bar.setProgress(progress);
        });
    }

    private static boolean isSumImage(String angle, String imageName) {
        if (angle != null && angle.toLowerCase().contains("sum")) return true;
        return imageName != null && imageName.contains("_sum");
    }

    private static boolean isBirefImage(String angle, String imageName) {
        if (angle != null && angle.toLowerCase().contains("biref")) return true;
        return imageName != null && imageName.contains("biref");
    }
}
