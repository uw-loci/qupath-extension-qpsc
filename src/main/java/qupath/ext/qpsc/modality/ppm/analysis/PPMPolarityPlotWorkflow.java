package qupath.ext.qpsc.modality.ppm.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager.PPMAnalysisSet;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
import qupath.fx.dialogs.Dialogs;

/**
 * Workflow for computing and displaying a PPM polarity plot (rose diagram)
 * for a selected annotation.
 *
 * <p>All angle computation, histograms, and circular statistics are performed
 * by ppm_library (Python) via its CLI entry point. Java handles QuPath I/O
 * (reading image regions, extracting annotation shapes) and displays results.</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMPolarityPlotWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMPolarityPlotWorkflow.class);

    private static Stage plotWindow;
    private static PolarHistogramPanel plotPanel;

    private static final double DEFAULT_BIREF_THRESHOLD = 100.0;
    private static final int DEFAULT_HISTOGRAM_BINS = 18;

    private PPMPolarityPlotWorkflow() {}

    /**
     * Main entry point. Shows the polarity plot for the currently selected annotation.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run polarity plot workflow", e);
                Dialogs.showErrorMessage("PPM Polarity Plot", "Error: " + e.getMessage());
            }
        });
    }

    private static void runOnFXThread() {
        QuPathGUI gui = QPEx.getQuPath();
        if (gui == null) {
            Dialogs.showErrorMessage("PPM Polarity Plot", "QuPath is not available.");
            return;
        }

        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage("PPM Polarity Plot", "No image is open.");
            return;
        }

        // Get selected annotation
        PathObject selected = imageData.getHierarchy().getSelectionModel().getSelectedObject();
        if (selected == null || !selected.isAnnotation()) {
            Dialogs.showErrorMessage("PPM Polarity Plot",
                    "Please select an annotation first.");
            return;
        }

        ROI roi = selected.getROI();
        if (roi == null) {
            Dialogs.showErrorMessage("PPM Polarity Plot", "Selected annotation has no ROI.");
            return;
        }

        // Find calibration
        Project<BufferedImage> project = gui.getProject();
        ProjectImageEntry<BufferedImage> currentEntry = project != null ? project.getEntry(imageData) : null;

        PPMAnalysisSet analysisSet = null;
        if (currentEntry != null && project != null) {
            analysisSet = ImageMetadataManager.findPPMAnalysisSet(currentEntry, project);
        }

        String calibrationPath = null;
        if (analysisSet != null && analysisSet.hasCalibration()) {
            calibrationPath = analysisSet.calibrationPath;
        }
        if (calibrationPath == null && currentEntry != null) {
            calibrationPath = ImageMetadataManager.getPPMCalibration(currentEntry);
        }
        if (calibrationPath == null) {
            String activePath = qupath.ext.qpsc.modality.ppm.PPMPreferences.getActiveCalibrationPath();
            if (activePath != null && !activePath.isEmpty()) {
                calibrationPath = activePath;
            }
        }
        if (calibrationPath == null) {
            Dialogs.showErrorMessage("PPM Polarity Plot",
                    "No PPM calibration found. Run sunburst calibration first.");
            return;
        }

        // Show plot window
        ensurePlotWindow(gui);

        // Run computation in background
        final String finalCalibrationPath = calibrationPath;
        final ImageServer<BufferedImage> sumServer = imageData.getServer();
        final String annotationName = selected.getDisplayedName();

        // Determine biref server path (if sibling exists)
        final PPMAnalysisSet finalAnalysisSet = analysisSet;

        CompletableFuture.runAsync(() -> {
            try {
                computeAndDisplay(sumServer, roi, finalCalibrationPath,
                        finalAnalysisSet, annotationName);
            } catch (Exception e) {
                logger.error("Polarity plot computation failed", e);
                Platform.runLater(() ->
                        Dialogs.showErrorMessage("PPM Polarity Plot",
                                "Computation failed: " + e.getMessage()));
            }
        });
    }

    private static void computeAndDisplay(
            ImageServer<BufferedImage> sumServer,
            ROI roi,
            String calibrationPath,
            PPMAnalysisSet analysisSet,
            String annotationName) throws Exception {

        // Create region request from annotation bounds
        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        logger.info("Computing polarity plot for '{}': region {}x{} at ({},{})",
                annotationName, w, h, x, y);

        RegionRequest request = RegionRequest.createInstance(
                sumServer.getPath(), 1.0, x, y, w, h);

        // Create temp directory for image exchange
        Path tempDir = Files.createTempDirectory("ppm_analysis_");

        try {
            // Write sum region to temp file
            BufferedImage sumRegion = sumServer.readRegion(request);
            Path sumPath = tempDir.resolve("sum_region.tif");
            ImageIO.write(sumRegion, "TIFF", sumPath.toFile());

            // Write biref region if available
            Path birefPath = null;
            if (analysisSet != null && analysisSet.hasBirefImage()) {
                try {
                    @SuppressWarnings("unchecked")
                    ImageData<BufferedImage> birefData = (ImageData<BufferedImage>) analysisSet.birefImage.readImageData();
                    ImageServer<BufferedImage> birefServer = birefData.getServer();
                    RegionRequest birefRequest = RegionRequest.createInstance(
                            birefServer.getPath(), 1.0, x, y, w, h);
                    BufferedImage birefRegion = birefServer.readRegion(birefRequest);
                    birefPath = tempDir.resolve("biref_region.tif");
                    ImageIO.write(birefRegion, "TIFF", birefPath.toFile());
                    birefServer.close();
                    logger.info("Wrote biref region to {}", birefPath);
                } catch (Exception e) {
                    logger.warn("Could not read biref sibling: {}", e.getMessage());
                }
            }

            // Write ROI mask (annotation shape)
            Path roiMaskPath = tempDir.resolve("roi_mask.tif");
            writeROIMask(roi, x, y, w, h, roiMaskPath);

            // Call Python analysis via ppm_library CLI
            JsonObject result = callPythonAnalysis(
                    sumPath, calibrationPath, birefPath, roiMaskPath);

            // Parse and display results
            displayResults(result, annotationName);

        } finally {
            // Clean up temp files
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception e) {
                logger.debug("Could not clean up temp dir: {}", e.getMessage());
            }
        }
    }

    /**
     * Calls ppm_library's CLI for region analysis.
     */
    private static JsonObject callPythonAnalysis(
            Path sumPath, String calibrationPath,
            Path birefPath, Path roiMaskPath) throws Exception {

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

        if (birefPath != null) {
            command.add("--biref");
            command.add(birefPath.toString());
            command.add("--biref-threshold");
            command.add(String.valueOf(DEFAULT_BIREF_THRESHOLD));
        }

        if (roiMaskPath != null) {
            command.add("--roi-mask");
            command.add(roiMaskPath.toString());
        }

        logger.info("Running Python analysis: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        // Read stdout (JSON result)
        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line);
            }
        }

        // Read stderr (errors/warnings)
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
            throw new RuntimeException("Python analysis failed (exit code " + exitCode + "): "
                    + stderr.toString().trim());
        }

        String jsonStr = stdout.toString().trim();
        if (jsonStr.isEmpty()) {
            throw new RuntimeException("Python analysis returned empty output");
        }

        Gson gson = new Gson();
        JsonObject result = gson.fromJson(jsonStr, JsonObject.class);

        // Check for error in JSON
        if (result.has("error") && !result.get("error").isJsonNull()) {
            throw new RuntimeException("Python analysis error: " + result.get("error").getAsString());
        }

        return result;
    }

    /**
     * Displays results from the Python analysis.
     */
    private static void displayResults(JsonObject result, String annotationName) {
        // Parse histogram
        var countsArray = result.getAsJsonArray("histogram_counts");
        int[] counts = new int[countsArray.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = countsArray.get(i).getAsInt();
        }

        // Parse stats
        double circularMean = getDoubleOrNaN(result, "circular_mean");
        double circularStd = getDoubleOrNaN(result, "circular_std");
        double resultantLength = getDoubleOrNaN(result, "resultant_length");
        int nPixels = result.has("n_pixels") ? result.get("n_pixels").getAsInt() : 0;

        logger.info("Polarity plot for '{}': {} valid pixels, mean=%.1f deg, std=%.1f deg, R=%.3f"
                        .formatted(circularMean, circularStd, resultantLength),
                annotationName, nPixels);

        Platform.runLater(() -> {
            if (plotPanel != null) {
                plotPanel.update(counts, circularMean, circularStd,
                        resultantLength, nPixels, annotationName);
            }
            if (plotWindow != null) {
                plotWindow.show();
                plotWindow.toFront();
            }
        });
    }

    private static double getDoubleOrNaN(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return Double.NaN;
        }
        return obj.get(key).getAsDouble();
    }

    /**
     * Writes a binary mask image for the ROI shape.
     */
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

    /**
     * Creates or shows the plot window.
     */
    private static void ensurePlotWindow(QuPathGUI gui) {
        if (plotWindow == null || !plotWindow.isShowing()) {
            plotPanel = new PolarHistogramPanel();
            Scene scene = new Scene(plotPanel, 400, 380);
            plotWindow = new Stage();
            plotWindow.setTitle("PPM Polarity Plot");
            plotWindow.setScene(scene);
            plotWindow.initOwner(gui.getStage());
            plotWindow.setAlwaysOnTop(false);
        }
    }
}
