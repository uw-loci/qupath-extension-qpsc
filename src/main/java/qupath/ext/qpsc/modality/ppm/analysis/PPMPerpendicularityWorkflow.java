package qupath.ext.qpsc.modality.ppm.analysis;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.ImageMetadataManager.PPMAnalysisSet;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Workflow for surface perpendicularity analysis of fiber orientation relative
 * to annotation boundaries.
 *
 * <p>Implements two analysis approaches:</p>
 * <ul>
 *   <li><b>Simple perpendicularity</b>: Distance-transform based surface normals,
 *       average deviation angle per pixel.</li>
 *   <li><b>PS-TACS scoring</b>: Per-contour-pixel TACS scoring with Gaussian
 *       distance weighting, based on Qian et al. (2025).</li>
 * </ul>
 *
 * <p>All computation is performed by {@code ppm_library.analysis.surface_analysis}
 * (Python) via the CLI. Java handles QuPath I/O, annotation discovery, GeoJSON
 * export, and results display.</p>
 *
 * <p>Reference: Qian et al., "Computationally Enabled Polychromatic Polarized
 * Imaging Enables Mapping of Matrix Architectures that Promote Pancreatic Ductal
 * Adenocarcinoma Dissemination", Am J Pathol 2025; 195:1242-1253.
 * DOI: <a href="https://doi.org/10.1016/j.ajpath.2025.04.017">10.1016/j.ajpath.2025.04.017</a></p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMPerpendicularityWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(PPMPerpendicularityWorkflow.class);

    private static final double DEFAULT_BIREF_THRESHOLD = 100.0;
    private static final double DEFAULT_DILATION_UM = 50.0;
    private static final double DEFAULT_TACS_THRESHOLD = 30.0;

    private static Stage resultWindow;
    private static PPMPerpendicularityPanel resultPanel;

    private PPMPerpendicularityWorkflow() {}

    /**
     * Main entry point. Shows a configuration dialog, then runs analysis.
     */
    public static void run() {
        Platform.runLater(() -> {
            try {
                runOnFXThread();
            } catch (Exception e) {
                logger.error("Failed to run perpendicularity workflow", e);
                Dialogs.showErrorMessage("Surface Perpendicularity Analysis", "Error: " + e.getMessage());
            }
        });
    }

    private static void runOnFXThread() {
        QuPathGUI gui = QPEx.getQuPath();
        if (gui == null) {
            Dialogs.showErrorMessage("Surface Perpendicularity Analysis", "QuPath is not available.");
            return;
        }

        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage("Surface Perpendicularity Analysis", "No image is open.");
            return;
        }

        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(
                    "Surface Perpendicularity Analysis",
                    "A QuPath project is required for this analysis.\n" + "Create or open a project first.");
            return;
        }

        // Find calibration
        ProjectImageEntry<BufferedImage> currentEntry = project.getEntry(imageData);
        String calibrationPath = findCalibrationPath(currentEntry, project);
        if (calibrationPath == null) {
            Dialogs.showErrorMessage(
                    "Surface Perpendicularity Analysis", "No PPM calibration found. Run sunburst calibration first.");
            return;
        }

        // Get pixel calibration
        PixelCalibration pixelCal = imageData.getServer().getPixelCalibration();
        double pixelSizeUm;
        if (pixelCal.hasPixelSizeMicrons()) {
            pixelSizeUm = pixelCal.getAveragedPixelSizeMicrons();
        } else {
            // Ask user
            String input = Dialogs.showInputDialog(
                    "Pixel Size Required",
                    "Image has no pixel size metadata.\n" + "Enter pixel size in microns:",
                    "1.0");
            if (input == null || input.isEmpty()) return;
            try {
                pixelSizeUm = Double.parseDouble(input.trim());
            } catch (NumberFormatException e) {
                Dialogs.showErrorMessage("Surface Perpendicularity Analysis", "Invalid pixel size: " + input);
                return;
            }
            if (pixelSizeUm <= 0) {
                Dialogs.showErrorMessage("Surface Perpendicularity Analysis", "Pixel size must be positive.");
                return;
            }
        }

        // Collect available annotation classes
        Collection<PathObject> allAnnotations = imageData.getHierarchy().getAnnotationObjects();
        List<String> classNames = allAnnotations.stream()
                .map(PathObject::getPathClass)
                .filter(pc -> pc != null)
                .map(PathClass::toString)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (classNames.isEmpty()) {
            Dialogs.showErrorMessage(
                    "Surface Perpendicularity Analysis",
                    "No classified annotations found.\n" + "Assign a class to boundary annotations first.");
            return;
        }

        // Show configuration dialog
        showConfigDialog(
                gui, imageData, project, currentEntry, calibrationPath, pixelSizeUm, classNames, allAnnotations);
    }

    private static void showConfigDialog(
            QuPathGUI gui,
            ImageData<BufferedImage> imageData,
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> currentEntry,
            String calibrationPath,
            double pixelSizeUm,
            List<String> classNames,
            Collection<PathObject> allAnnotations) {

        Stage dialog = new Stage();
        dialog.setTitle("Surface Perpendicularity Analysis");
        dialog.initOwner(gui.getStage());

        Button helpButton = DocumentationHelper.createHelpButton("ppmPerpendicularity");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(15));

        int row = 0;

        // Annotation class selector
        grid.add(new Label("Boundary annotation class:"), 0, row);
        ChoiceBox<String> classChoice = new ChoiceBox<>();
        classChoice.getItems().addAll(classNames);
        classChoice.setValue(classNames.get(0));
        grid.add(classChoice, 1, row);

        // Annotation count label
        Label countLabel = new Label();
        grid.add(countLabel, 2, row);
        row++;

        // Update count when class changes
        Runnable updateCount = () -> {
            String selected = classChoice.getValue();
            if (selected == null) return;
            long count = allAnnotations.stream()
                    .filter(a -> a.getPathClass() != null
                            && a.getPathClass().toString().equals(selected))
                    .count();
            countLabel.setText("(" + count + " annotations)");
        };
        classChoice.setOnAction(e -> updateCount.run());
        updateCount.run();

        // Dilation
        grid.add(new Label("Border zone width (um):"), 0, row);
        Spinner<Double> dilationSpinner =
                new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 500, DEFAULT_DILATION_UM, 5));
        dilationSpinner.setEditable(true);
        grid.add(dilationSpinner, 1, row);
        row++;

        // Zone mode
        grid.add(new Label("Zone mode:"), 0, row);
        ChoiceBox<String> zoneChoice = new ChoiceBox<>();
        zoneChoice.getItems().addAll("outside", "inside", "both");
        zoneChoice.setValue("outside");
        grid.add(zoneChoice, 1, row);
        row++;

        // TACS threshold
        grid.add(new Label("TACS threshold (deg from normal):"), 0, row);
        Spinner<Double> tacsSpinner =
                new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(5, 85, DEFAULT_TACS_THRESHOLD, 5));
        tacsSpinner.setEditable(true);
        grid.add(tacsSpinner, 1, row);
        row++;

        // Fill holes
        CheckBox fillHolesBox = new CheckBox("Fill holes in boundary");
        fillHolesBox.setSelected(true);
        grid.add(fillHolesBox, 0, row, 2, 1);
        row++;

        // Pixel size display
        grid.add(new Label("Pixel size:"), 0, row);
        grid.add(new Label(String.format("%.4f um/px", pixelSizeUm)), 1, row);
        row++;

        // Paper reference
        Label refLabel = new Label("Based on PS-TACS: Qian et al., Am J Pathol 2025");
        refLabel.setStyle("-fx-text-fill: #4444aa; -fx-font-size: 10px;");
        grid.add(refLabel, 0, row, 3, 1);
        row++;

        // DOI link
        Label doiLabel = new Label("DOI: 10.1016/j.ajpath.2025.04.017");
        doiLabel.setStyle("-fx-text-fill: #4444aa; -fx-font-size: 10px; -fx-cursor: hand; -fx-underline: true;");
        doiLabel.setOnMouseClicked(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI("https://doi.org/10.1016/j.ajpath.2025.04.017"));
            } catch (Exception ex) {
                logger.debug("Could not open DOI link: {}", ex.getMessage());
            }
        });
        grid.add(doiLabel, 0, row, 3, 1);
        row++;

        // Buttons
        Button runButton = new Button("Run Analysis");
        Button cancelButton = new Button("Cancel");
        HBox buttons = new HBox(10, runButton, cancelButton);
        buttons.setPadding(new Insets(10, 0, 0, 0));
        grid.add(buttons, 0, row, 3, 1);

        cancelButton.setOnAction(e -> dialog.close());
        runButton.setOnAction(e -> {
            dialog.close();

            String selectedClass = classChoice.getValue();
            double dilationUm = dilationSpinner.getValue();
            String zoneMode = zoneChoice.getValue();
            double tacsThreshold = tacsSpinner.getValue();
            boolean fillHoles = fillHolesBox.isSelected();

            // Collect matching annotations
            List<PathObject> matchingAnnotations = allAnnotations.stream()
                    .filter(a -> a.getPathClass() != null
                            && a.getPathClass().toString().equals(selectedClass))
                    .collect(Collectors.toList());

            if (matchingAnnotations.isEmpty()) {
                Dialogs.showErrorMessage(
                        "Surface Perpendicularity Analysis",
                        "No annotations found with class '" + selectedClass + "'.");
                return;
            }

            // Show results window
            ensureResultWindow(gui);

            // Find analysis set for biref
            PPMAnalysisSet analysisSet = null;
            if (currentEntry != null) {
                analysisSet = ImageMetadataManager.findPPMAnalysisSet(currentEntry, project);
            }

            // Run in background
            final PPMAnalysisSet finalAnalysisSet = analysisSet;
            final ImageServer<BufferedImage> sumServer = imageData.getServer();

            // Build output dir
            Path projectDir = project.getPath().getParent();
            String imageName = currentEntry != null ? currentEntry.getImageName() : "unknown";
            Path outputDir = projectDir
                    .resolve("analysis")
                    .resolve("perpendicularity")
                    .resolve(imageName.replaceAll("[^a-zA-Z0-9._-]", "_"));

            CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < matchingAnnotations.size(); i++) {
                        PathObject annotation = matchingAnnotations.get(i);
                        String annotationName = annotation.getDisplayedName();
                        int annotationIndex = i + 1;
                        int totalAnnotations = matchingAnnotations.size();

                        logger.info(
                                "Analyzing annotation {}/{}: '{}'", annotationIndex, totalAnnotations, annotationName);

                        Platform.runLater(() -> {
                            if (resultPanel != null) {
                                resultPanel.setStatus(String.format(
                                        "Analyzing %d/%d: %s...", annotationIndex, totalAnnotations, annotationName));
                            }
                        });

                        Path annotationOutputDir = outputDir.resolve("annotation_" + annotationIndex);

                        JsonObject result = computeForAnnotation(
                                sumServer,
                                annotation.getROI(),
                                calibrationPath,
                                finalAnalysisSet,
                                pixelSizeUm,
                                dilationUm,
                                zoneMode,
                                tacsThreshold,
                                fillHoles,
                                annotationOutputDir);

                        // Save JSON result
                        annotationOutputDir.toFile().mkdirs();
                        Path jsonPath = annotationOutputDir.resolve("results.json");
                        try (Writer writer = Files.newBufferedWriter(jsonPath)) {
                            new Gson().toJson(result, writer);
                        }

                        // Display results
                        final JsonObject finalResult = result;
                        final String finalName = annotationName;
                        final int idx = annotationIndex;
                        final int total = totalAnnotations;
                        Platform.runLater(() -> {
                            if (resultPanel != null) {
                                resultPanel.addResult(finalResult, finalName, idx, total);
                            }
                        });
                    }

                    Platform.runLater(() -> {
                        if (resultPanel != null) {
                            resultPanel.setStatus("Analysis complete. Results saved to: " + outputDir);
                        }
                        if (resultWindow != null) {
                            resultWindow.show();
                            resultWindow.toFront();
                        }
                    });

                    logger.info("Perpendicularity analysis complete. Results: {}", outputDir);

                } catch (Exception ex) {
                    logger.error("Perpendicularity analysis failed", ex);
                    Platform.runLater(() -> Dialogs.showErrorMessage(
                            "Surface Perpendicularity Analysis", "Analysis failed: " + ex.getMessage()));
                }
            });
        });

        VBox dialogRoot = new VBox(grid);
        if (helpButton != null) {
            HBox helpBar = new HBox(helpButton);
            helpBar.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            helpBar.setPadding(new Insets(5, 10, 0, 10));
            dialogRoot.getChildren().add(0, helpBar);
        }
        dialog.setScene(new Scene(dialogRoot));
        dialog.sizeToScene();
        dialog.show();
    }

    private static JsonObject computeForAnnotation(
            ImageServer<BufferedImage> sumServer,
            ROI roi,
            String calibrationPath,
            PPMAnalysisSet analysisSet,
            double pixelSizeUm,
            double dilationUm,
            String zoneMode,
            double tacsThreshold,
            boolean fillHoles,
            Path outputDir)
            throws Exception {

        int x = (int) roi.getBoundsX();
        int y = (int) roi.getBoundsY();
        int w = (int) Math.ceil(roi.getBoundsWidth());
        int h = (int) Math.ceil(roi.getBoundsHeight());

        // Expand region to include dilation zone
        double dilationPx = dilationUm / pixelSizeUm;
        int pad = (int) Math.ceil(dilationPx) + 5;
        int expandedX = Math.max(0, x - pad);
        int expandedY = Math.max(0, y - pad);
        int expandedW = Math.min(sumServer.getWidth() - expandedX, w + 2 * pad);
        int expandedH = Math.min(sumServer.getHeight() - expandedY, h + 2 * pad);

        logger.info(
                "Region: {}x{} at ({},{}) padded to {}x{} at ({},{})",
                w,
                h,
                x,
                y,
                expandedW,
                expandedH,
                expandedX,
                expandedY);

        RegionRequest request =
                RegionRequest.createInstance(sumServer.getPath(), 1.0, expandedX, expandedY, expandedW, expandedH);

        Path tempDir = Files.createTempDirectory("ppm_perp_");

        try {
            // Write sum region
            BufferedImage sumRegion = sumServer.readRegion(request);
            Path sumPath = tempDir.resolve("sum_region.tif");
            ImageIO.write(sumRegion, "TIFF", sumPath.toFile());

            // Write biref region if available
            Path birefPath = null;
            if (analysisSet != null && analysisSet.hasBirefImage()) {
                try {
                    @SuppressWarnings("unchecked")
                    ImageData<BufferedImage> birefData =
                            (ImageData<BufferedImage>) analysisSet.birefImage.readImageData();
                    ImageServer<BufferedImage> birefServer = birefData.getServer();
                    RegionRequest birefRequest = RegionRequest.createInstance(
                            birefServer.getPath(), 1.0, expandedX, expandedY, expandedW, expandedH);
                    BufferedImage birefRegion = birefServer.readRegion(birefRequest);
                    birefPath = tempDir.resolve("biref_region.tif");
                    ImageIO.write(birefRegion, "TIFF", birefPath.toFile());
                    birefServer.close();
                } catch (Exception e) {
                    logger.warn("Could not read biref sibling: {}", e.getMessage());
                }
            }

            // Export ROI as GeoJSON (coordinates relative to expanded region)
            Path geojsonPath = tempDir.resolve("boundary.geojson");
            exportRoiAsGeoJSON(roi, expandedX, expandedY, geojsonPath);

            // Call Python
            JsonObject result = callPythonPerpendicularity(
                    sumPath,
                    calibrationPath,
                    geojsonPath,
                    birefPath,
                    pixelSizeUm,
                    dilationUm,
                    zoneMode,
                    tacsThreshold,
                    fillHoles,
                    outputDir);

            return result;

        } finally {
            // Clean up temp files
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception e) {
                logger.debug("Could not clean up temp dir: {}", e.getMessage());
            }
        }
    }

    /**
     * Exports an ROI as a GeoJSON Feature with coordinates adjusted to be
     * relative to an offset (expanded region origin).
     */
    static void exportRoiAsGeoJSON(ROI roi, int offsetX, int offsetY, Path outputPath) throws Exception {
        // Use QuPath's built-in GeoJSON serialization for the ROI geometry
        Gson gson = GsonTools.getInstance();

        // Create a PathObject wrapper for the ROI to use QuPath's serialization
        PathObject tempObj = qupath.lib.objects.PathObjects.createAnnotationObject(roi);
        String json = gson.toJson(tempObj);
        JsonObject feature = new Gson().fromJson(json, JsonObject.class);

        // Adjust coordinates by subtracting the offset
        JsonObject geometry = feature.getAsJsonObject("geometry");
        if (geometry != null) {
            adjustGeoJSONCoordinates(geometry, -offsetX, -offsetY);
        }

        try (Writer writer = Files.newBufferedWriter(outputPath)) {
            new Gson().toJson(feature, writer);
        }
    }

    /**
     * Recursively adjusts all coordinate values in a GeoJSON geometry object.
     */
    private static void adjustGeoJSONCoordinates(JsonElement element, double dx, double dy) {
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            if (arr.size() >= 2
                    && arr.get(0).isJsonPrimitive()
                    && arr.get(0).getAsJsonPrimitive().isNumber()) {
                // This is a coordinate pair [x, y]
                double x = arr.get(0).getAsDouble() + dx;
                double y = arr.get(1).getAsDouble() + dy;
                arr.set(0, new com.google.gson.JsonPrimitive(x));
                arr.set(1, new com.google.gson.JsonPrimitive(y));
            } else {
                // Array of coordinates or rings
                for (int i = 0; i < arr.size(); i++) {
                    adjustGeoJSONCoordinates(arr.get(i), dx, dy);
                }
            }
        } else if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("coordinates")) {
                adjustGeoJSONCoordinates(obj.get("coordinates"), dx, dy);
            }
            if (obj.has("geometries")) {
                adjustGeoJSONCoordinates(obj.get("geometries"), dx, dy);
            }
        }
    }

    private static JsonObject callPythonPerpendicularity(
            Path sumPath,
            String calibrationPath,
            Path geojsonPath,
            Path birefPath,
            double pixelSizeUm,
            double dilationUm,
            String zoneMode,
            double tacsThreshold,
            boolean fillHoles,
            Path outputDir)
            throws Exception {

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

        if (outputDir != null) {
            command.add("--output-dir");
            command.add(outputDir.toString());
        }

        logger.info("Running perpendicularity analysis: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line);
            }
        }

        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
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

        if (result.has("error") && !result.get("error").isJsonNull()) {
            throw new RuntimeException("Python error: " + result.get("error").getAsString());
        }

        return result;
    }

    private static String findCalibrationPath(ProjectImageEntry<BufferedImage> entry, Project<BufferedImage> project) {
        PPMAnalysisSet analysisSet = null;
        if (entry != null && project != null) {
            analysisSet = ImageMetadataManager.findPPMAnalysisSet(entry, project);
        }

        String calibrationPath = null;
        if (analysisSet != null && analysisSet.hasCalibration()) {
            calibrationPath = analysisSet.calibrationPath;
        }
        if (calibrationPath == null && entry != null) {
            calibrationPath = ImageMetadataManager.getPPMCalibration(entry);
        }
        if (calibrationPath == null) {
            String activePath = qupath.ext.qpsc.modality.ppm.PPMPreferences.getActiveCalibrationPath();
            if (activePath != null && !activePath.isEmpty()) {
                calibrationPath = activePath;
            }
        }
        return calibrationPath;
    }

    private static void ensureResultWindow(QuPathGUI gui) {
        if (resultWindow == null || !resultWindow.isShowing()) {
            resultPanel = new PPMPerpendicularityPanel();
            Scene scene = new Scene(resultPanel, 550, 600);
            resultWindow = new Stage();
            resultWindow.setTitle("Surface Perpendicularity Analysis");
            resultWindow.setScene(scene);
            resultWindow.initOwner(gui.getStage());
        } else {
            resultPanel.clear();
        }
        resultWindow.show();
        resultWindow.toFront();
    }
}
