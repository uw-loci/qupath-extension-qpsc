package qupath.ext.qpsc.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;
import qupath.ext.qpsc.utilities.ImageNameGenerator;
import qupath.ext.qpsc.utilities.StitchRetryExecutor;
import qupath.ext.qpsc.utilities.TileFolderInspector;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

/**
 * Standalone workflow for stitching a MicroManager-format tile folder.
 *
 * <p>Unlike {@link StitchingRecoveryWorkflow}, this workflow:
 * <ul>
 *     <li>Does not require an open QuPath project -- the user picks both
 *         input and output folders.</li>
 *     <li>Reads tile positions directly from MMStack {@code *_metadata.txt}
 *         sidecar files (no TileConfiguration.txt needed).</li>
 *     <li>Writes a {@code <stem>.mm-metadata.json} sidecar next to the
 *         stitched output preserving channel names, pixel-size details,
 *         exposure, and other MicroManager metadata that the OME writer
 *         pipeline cannot carry today.</li>
 * </ul>
 *
 * <p>Access via: Extensions &gt; QPSC &gt; Utilities &gt; Stitch MicroManager Folder...
 */
public class MicroManagerStitchWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MicroManagerStitchWorkflow.class);

    private static final String STRATEGY_NAME = "MicroManager metadata (MMStack)";

    /** Entry point - shows the dialog and runs the stitch. */
    public static void run() {
        logger.info("Starting MicroManager folder stitch workflow");
        Platform.runLater(MicroManagerStitchWorkflow::showDialog);
    }

    private static void showDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Stitch MicroManager Folder");
        dialog.setHeaderText("Stitch a folder of MicroManager OME-TIFF tiles using MMStack metadata");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // ---- input folder ----
        Label inLabel = new Label("Input folder:");
        TextField inField = new TextField(PersistentPreferences.getMmStitchInputDir());
        inField.setPromptText("Browse to a folder containing *_MMStack_*.ome.tif tiles...");
        inField.setPrefWidth(400);
        Button inBrowse = new Button("Browse...");

        // ---- output folder ----
        Label outLabel = new Label("Output folder:");
        TextField outField = new TextField(PersistentPreferences.getMmStitchOutputDir());
        outField.setPromptText("Browse to a writable output folder...");
        outField.setPrefWidth(400);
        Button outBrowse = new Button("Browse...");

        // ---- output filename ----
        Label nameLabel = new Label("Output filename:");
        TextField nameField = new TextField();
        nameField.setPromptText("Base name (without extension)");
        nameField.setPrefWidth(300);

        // ---- pixel size ----
        Label pxLabel = new Label("Pixel size (um):");
        TextField pxField = new TextField("");
        pxField.setPrefWidth(100);
        Label pxSourceLabel = new Label("");
        pxSourceLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #666;");

        // ---- format combo ----
        Label fmtLabel = new Label("Output format:");
        ComboBox<StitchingConfig.OutputFormat> fmtCombo = new ComboBox<>();
        fmtCombo.getItems().addAll(StitchingConfig.OutputFormat.values());
        try {
            fmtCombo.setValue(QPPreferenceDialog.getOutputFormatProperty());
        } catch (Exception e) {
            fmtCombo.setValue(StitchingConfig.OutputFormat.OME_TIFF);
        }

        // ---- compression combo, filtered by format ----
        Label compLabel = new Label("Compression:");
        ComboBox<OMEPyramidWriter.CompressionType> compCombo = new ComboBox<>();
        compCombo.getItems().setAll(QPPreferenceDialog.getCompressionTypesForFormat(fmtCombo.getValue()));
        OMEPyramidWriter.CompressionType prefComp;
        try {
            prefComp = QPPreferenceDialog.getCompressionTypeProperty();
        } catch (Exception e) {
            prefComp = OMEPyramidWriter.CompressionType.LZW;
        }
        compCombo.setValue(compCombo.getItems().contains(prefComp) ? prefComp : OMEPyramidWriter.CompressionType.LZW);
        fmtCombo.valueProperty().addListener((obs, oldF, newF) -> {
            List<OMEPyramidWriter.CompressionType> allowed = QPPreferenceDialog.getCompressionTypesForFormat(newF);
            OMEPyramidWriter.CompressionType current = compCombo.getValue();
            compCombo.getItems().setAll(allowed);
            compCombo.setValue(allowed.contains(current) ? current : OMEPyramidWriter.CompressionType.LZW);
        });

        // ---- metadata preview label ----
        Label metaLabel = new Label("");
        metaLabel.setWrapText(true);
        metaLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: #444;");

        // ---- input browse: resolve pixel size + suggested filename + output folder ----
        inBrowse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select MicroManager Tile Folder");
            String last = PersistentPreferences.getMmStitchInputDir();
            if (last != null && !last.isEmpty()) {
                File lastDir = new File(last);
                if (lastDir.isDirectory()) chooser.setInitialDirectory(lastDir);
            }
            File selected = chooser.showDialog(dialog.getOwner());
            if (selected != null) {
                inField.setText(selected.getAbsolutePath());
                populateFromInputFolder(selected, outField, nameField, pxField, pxSourceLabel, metaLabel);
            }
        });

        // ---- output browse ----
        outBrowse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Output Folder");
            String last = outField.getText().trim();
            if (last.isEmpty()) last = PersistentPreferences.getMmStitchOutputDir();
            if (last != null && !last.isEmpty()) {
                File lastDir = new File(last);
                if (lastDir.isDirectory()) chooser.setInitialDirectory(lastDir);
            }
            File selected = chooser.showDialog(dialog.getOwner());
            if (selected != null) outField.setText(selected.getAbsolutePath());
        });

        // If we restored an input dir from preferences, pre-populate the panel.
        if (!inField.getText().trim().isEmpty()) {
            File f = new File(inField.getText().trim());
            if (f.isDirectory()) {
                populateFromInputFolder(f, outField, nameField, pxField, pxSourceLabel, metaLabel);
            }
        }

        HBox inBox = new HBox(10, inField, inBrowse);
        HBox.setHgrow(inField, Priority.ALWAYS);
        HBox outBox = new HBox(10, outField, outBrowse);
        HBox.setHgrow(outField, Priority.ALWAYS);
        HBox pxBox = new HBox(8, pxField, pxSourceLabel);
        pxBox.setAlignment(Pos.CENTER_LEFT);

        Label infoLabel = new Label("Reads tile positions directly from MMStack *_metadata.txt sidecars.\n"
                + "Pixel size is auto-detected from FrameKey-0-0-0.PixelSizeUm; channel names\n"
                + "and remaining MicroManager metadata are saved to <stem>.mm-metadata.json.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-style: italic;");

        int r = 0;
        grid.add(infoLabel, 0, r++, 3, 1);
        grid.add(inLabel, 0, r);
        grid.add(inBox, 1, r++, 2, 1);
        grid.add(metaLabel, 0, r++, 3, 1);
        grid.add(outLabel, 0, r);
        grid.add(outBox, 1, r++, 2, 1);
        grid.add(nameLabel, 0, r);
        grid.add(nameField, 1, r++, 2, 1);
        grid.add(pxLabel, 0, r);
        grid.add(pxBox, 1, r++, 2, 1);
        grid.add(fmtLabel, 0, r);
        grid.add(fmtCombo, 1, r++);
        grid.add(compLabel, 0, r);
        grid.add(compCombo, 1, r++);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Stitch");
        okButton.setDisable(true);

        Runnable revalidate = () -> {
            boolean validIn = !inField.getText().trim().isEmpty()
                    && new File(inField.getText().trim()).isDirectory();
            boolean validOut = !outField.getText().trim().isEmpty();
            boolean validName = !nameField.getText().trim().isEmpty();
            okButton.setDisable(!(validIn && validOut && validName));
        };
        inField.textProperty().addListener((obs, o, n) -> revalidate.run());
        outField.textProperty().addListener((obs, o, n) -> revalidate.run());
        nameField.textProperty().addListener((obs, o, n) -> revalidate.run());
        revalidate.run();

        dialog.showAndWait().ifPresent(result -> {
            if (result != ButtonType.OK) return;

            String inFolder = inField.getText().trim();
            String outFolder = outField.getText().trim();
            String baseName =
                    ImageNameGenerator.sanitizeForFilename(nameField.getText().trim());
            double pixelSize;
            try {
                pixelSize = Double.parseDouble(pxField.getText().trim());
            } catch (NumberFormatException ex) {
                Dialogs.showErrorMessage("Invalid Pixel Size", "Please enter a valid number for pixel size.");
                return;
            }
            String compression = compCombo.getValue().name();
            StitchingConfig.OutputFormat outputFormat = fmtCombo.getValue();

            PersistentPreferences.setMmStitchInputDir(inFolder);
            PersistentPreferences.setMmStitchOutputDir(outFolder);

            Thread t = new Thread(
                    () -> executeStitch(inFolder, outFolder, baseName, pixelSize, compression, outputFormat));
            t.setDaemon(true);
            t.setName("MicroManagerStitch");
            t.start();
        });
    }

    /**
     * Inspect the input folder once: parse the first sidecar to resolve
     * pixel size, suggest an output filename, suggest an output folder, and
     * paint a one-line metadata preview.
     */
    private static void populateFromInputFolder(
            File inFolder,
            TextField outField,
            TextField nameField,
            TextField pxField,
            Label pxSourceLabel,
            Label metaLabel) {
        // Default suggestions if we cannot parse anything.
        if (nameField.getText().isEmpty()) {
            nameField.setText(ImageNameGenerator.sanitizeForFilename(inFolder.getName()));
        }
        if (outField.getText().isEmpty()) {
            outField.setText(new File(inFolder.getParentFile(), inFolder.getName() + "_stitched").getAbsolutePath());
        }

        JsonObject firstSidecar = readFirstMMStackSidecar(inFolder);
        Double mmPixelSize = (firstSidecar != null) ? extractPixelSizeUm(firstSidecar) : null;
        if (mmPixelSize != null && mmPixelSize > 0) {
            pxField.setText(String.valueOf(mmPixelSize));
            pxSourceLabel.setText("(from MMStack metadata)");
        } else {
            double fromTiff = TileFolderInspector.detectPixelSizeFromFolder(inFolder);
            if (fromTiff > 0) {
                pxField.setText(String.valueOf(fromTiff));
                pxSourceLabel.setText("(from TIFF resolution tag)");
            } else if (pxField.getText().isEmpty()) {
                pxField.setText("0.5");
                pxSourceLabel.setText("(default - no MMStack or TIFF resolution found)");
            }
        }

        if (firstSidecar != null) {
            List<String> channels = extractChannelNames(firstSidecar);
            String mmVersion = optString(optObject(firstSidecar, "Summary"), "MicroManagerVersion");
            metaLabel.setText("Channels: "
                    + (channels.isEmpty() ? "?" : String.join(", ", channels))
                    + (mmVersion != null ? "  |  MicroManager " + mmVersion : ""));
            metaLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: green;");
        } else {
            metaLabel.setText("No MMStack metadata detected in folder.");
            metaLabel.setStyle("-fx-font-size: 0.85em; -fx-text-fill: orange;");
        }
    }

    private static void executeStitch(
            String inFolder,
            String outFolder,
            String baseName,
            double pixelSize,
            String compression,
            StitchingConfig.OutputFormat outputFormat) {

        logger.info(
                "MicroManager stitch: in={}, out={}, base={}, pxSize={}, fmt={}, comp={}",
                inFolder,
                outFolder,
                baseName,
                pixelSize,
                outputFormat,
                compression);

        File outDir = new File(outFolder);
        if (!outDir.exists() && !outDir.mkdirs()) {
            String msg = "Failed to create output directory: " + outFolder;
            logger.error(msg);
            Platform.runLater(() -> Dialogs.showErrorMessage("Directory Error", msg));
            return;
        }

        File inDir = new File(inFolder);
        JsonObject firstSidecar = readFirstMMStackSidecar(inDir);
        double zSpacing = extractZStepUm(firstSidecar);
        if (zSpacing <= 0) zSpacing = 1.0;
        final double finalZ = zSpacing;

        StitchRetryExecutor.ConfigFactory configFactory = fmt -> {
            StitchingConfig cfg = new StitchingConfig(
                    STRATEGY_NAME,
                    inFolder,
                    outFolder,
                    compression,
                    pixelSize,
                    1, // downsample
                    ".",
                    finalZ,
                    fmt);
            cfg.outputFilename = baseName;
            return cfg;
        };

        // Between-retry cleanup: remove any partial output from a prior attempt.
        // The stitcher emits "<baseName>_<sourceFolderName>.<ext>" because the
        // MMStack strategy uses the input folder name as the subdir name.
        String stitcherStem = baseName + "_" + inDir.getName();
        Runnable cleanup = () -> deleteStitchOutputs(outDir, stitcherStem);

        String label = baseName;
        String stitchedOutPath;
        try {
            stitchedOutPath = StitchRetryExecutor.run(
                    outputFormat, configFactory, new boolean[] {false, false}, cleanup, label, false);
        } catch (Exception ex) {
            logger.error("Stitch failed for {}: {}", label, ex.getMessage(), ex);
            Platform.runLater(
                    () -> Dialogs.showErrorMessage("Stitching Failed", "Stitch failed (see log): " + ex.getMessage()));
            NotificationService.getInstance()
                    .notify(
                            "MicroManager Stitch Failed",
                            "Folder \"" + inDir.getName() + "\" stitch failed",
                            NotificationPriority.HIGH,
                            NotificationEvent.STITCHING_ERROR);
            return;
        }

        // Rename "<baseName>_<sourceFolderName>.ext" to just "<baseName>.ext"
        // so the user-typed filename is the actual output name.
        String extension = stitchedOutPath.endsWith(".ome.zarr") ? ".ome.zarr" : ".ome.tif";
        File initial = new File(stitchedOutPath);
        File desired = uniqueWithExtension(new File(outDir, baseName + extension), extension);
        String finalOutPath = stitchedOutPath;
        if (!initial.getAbsolutePath().equals(desired.getAbsolutePath())) {
            if (initial.renameTo(desired)) {
                finalOutPath = desired.getAbsolutePath();
                logger.info("Renamed stitched output: {} -> {}", initial.getName(), desired.getName());
            } else {
                logger.warn(
                        "Could not rename {} to {}; keeping stitcher default name",
                        initial.getName(),
                        desired.getName());
            }
        }

        // Sidecar JSON with as much MicroManager metadata as we can preserve.
        writeMmMetadataSidecar(finalOutPath, extension, baseName, inDir, firstSidecar);

        final String displayPath = finalOutPath;
        Platform.runLater(() -> Dialogs.showInfoNotification("Stitch Complete", "Output written to:\n" + displayPath));
        NotificationService.getInstance()
                .notify(
                        "MicroManager Stitch Complete",
                        "Folder \"" + inDir.getName() + "\" stitched to " + new File(displayPath).getName(),
                        NotificationPriority.DEFAULT,
                        NotificationEvent.STITCHING_COMPLETE);
    }

    // ====================================================================
    // MicroManager metadata helpers
    // ====================================================================

    private static final Gson GSON_READ = new Gson();
    private static final Gson GSON_WRITE = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Read and parse the first {@code *_metadata.txt} file in {@code folder}.
     *
     * @return parsed JSON object, or null if no sidecars are present
     */
    private static JsonObject readFirstMMStackSidecar(File folder) {
        if (folder == null || !folder.isDirectory()) return null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder.toPath(), "*_metadata.txt")) {
            for (Path p : stream) {
                if (p.getFileName().toString().contains(":")) continue;
                try (Reader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    JsonObject obj = GSON_READ.fromJson(reader, JsonObject.class);
                    if (obj != null) return obj;
                } catch (Exception e) {
                    logger.debug("Could not parse {}: {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.debug("Could not list MMStack sidecars in {}: {}", folder, e.getMessage());
        }
        return null;
    }

    /** Resolve the per-frame PixelSizeUm from a parsed sidecar JSON. */
    private static Double extractPixelSizeUm(JsonObject sidecar) {
        JsonObject frame = optObject(sidecar, "FrameKey-0-0-0");
        Double v = optDouble(frame, "PixelSizeUm");
        if (v == null || v <= 0) v = optDouble(optObject(sidecar, "Summary"), "PixelSize_um");
        return v;
    }

    /** Resolve the z-step (in microns) from a parsed sidecar JSON. */
    private static double extractZStepUm(JsonObject sidecar) {
        Double v = optDouble(optObject(sidecar, "Summary"), "z-step_um");
        return v != null ? v : 0;
    }

    /** Extract channel names from {@code Summary.ChNames}. Returns an empty list when absent. */
    private static List<String> extractChannelNames(JsonObject sidecar) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JsonArray arr = optArray(optObject(sidecar, "Summary"), "ChNames");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) out.add(el.getAsString());
            }
        }
        return out;
    }

    /**
     * Write a JSON sidecar next to the stitched output capturing the
     * MicroManager metadata that the writer pipeline cannot carry today.
     */
    private static void writeMmMetadataSidecar(
            String stitchedPath, String extension, String baseName, File sourceFolder, JsonObject sidecar) {
        File outDir = new File(stitchedPath).getParentFile();
        File sidecarOut = new File(outDir, baseName + ".mm-metadata.json");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source_folder", sourceFolder.getAbsolutePath());
        out.put("source_folder_name", sourceFolder.getName());
        out.put("stitched_output", new File(stitchedPath).getName());

        if (sidecar != null) {
            JsonObject summary = optObject(sidecar, "Summary");
            JsonObject frame = optObject(sidecar, "FrameKey-0-0-0");

            putIfPresent(out, "pixel_size_um", optDouble(frame, "PixelSizeUm"));
            putIfPresent(out, "pixel_size_affine", optString(frame, "PixelSizeAffine"));
            putIfPresent(out, "z_position_um", optDouble(frame, "ZPositionUm"));
            putIfPresent(out, "z_step_um", optDouble(summary, "z-step_um"));
            putIfPresent(out, "exposure_ms", optDouble(frame, "Exposure-ms"));
            putIfPresent(out, "bit_depth", optInt(frame, "BitDepth"));
            putIfPresent(out, "pixel_type", optString(summary, "PixelType"));
            putIfPresent(out, "tile_width", optInt(summary, "Width"));
            putIfPresent(out, "tile_height", optInt(summary, "Height"));
            putIfPresent(out, "channels", extractChannelNames(sidecar));
            putIfPresent(out, "start_time", optString(summary, "StartTime"));
            putIfPresent(out, "micro_manager_version", optString(summary, "MicroManagerVersion"));
            putIfPresent(out, "computer_name", optString(summary, "ComputerName"));
            putIfPresent(out, "user_name", optString(summary, "UserName"));

            // Grid extent from Summary.StagePositions.
            JsonArray positions = optArray(summary, "StagePositions");
            if (positions != null) {
                int maxRow = -1;
                int maxCol = -1;
                int count = 0;
                for (JsonElement el : positions) {
                    if (!el.isJsonObject()) continue;
                    count++;
                    JsonObject p = el.getAsJsonObject();
                    Integer row = optInt(p, "GridRow");
                    Integer col = optInt(p, "GridCol");
                    if (row != null && row > maxRow) maxRow = row;
                    if (col != null && col > maxCol) maxCol = col;
                }
                out.put("tile_count", count);
                if (maxRow >= 0) out.put("grid_rows", maxRow + 1);
                if (maxCol >= 0) out.put("grid_cols", maxCol + 1);
            }
        }

        // Pull in the per-folder comments.txt, if present.
        File commentsFile = new File(sourceFolder, "comments.txt");
        if (commentsFile.exists()) {
            try {
                String text = Files.readString(commentsFile.toPath(), StandardCharsets.UTF_8);
                out.put("comments_txt", text);
            } catch (IOException e) {
                logger.debug("Could not read comments.txt: {}", e.getMessage());
            }
        }

        try {
            Files.writeString(sidecarOut.toPath(), GSON_WRITE.toJson(out), StandardCharsets.UTF_8);
            logger.info("Wrote MMStack metadata sidecar: {}", sidecarOut.getName());
        } catch (IOException e) {
            logger.warn("Could not write metadata sidecar {}: {}", sidecarOut.getName(), e.getMessage());
        }
    }

    // ====================================================================
    // Tiny JSON helpers (mirror MicroManagerMetadataStrategy's helpers)
    // ====================================================================

    private static JsonObject optObject(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static String optString(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Double optDouble(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement e = parent.get(key);
        if (e == null || e.isJsonNull()) return null;
        try {
            return e.getAsDouble();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer optInt(JsonObject parent, String key) {
        Double d = optDouble(parent, key);
        return d != null ? d.intValue() : null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value == null) return;
        if (value instanceof java.util.Collection<?> col && col.isEmpty()) return;
        if (value instanceof String s && s.isEmpty()) return;
        map.put(key, value);
    }

    /**
     * Deletes a stitch output ({@code .ome.tif}, {@code .ome.zarr}, or the
     * {@code .writing.ome.tif} temp) before a retry so the retry's
     * rename-over does not fail on a stale file.
     */
    private static void deleteStitchOutputs(File dir, String stem) {
        for (String ext : new String[] {".ome.tif", ".ome.zarr", ".writing.ome.tif"}) {
            File f = new File(dir, stem + ext);
            if (!f.exists()) continue;
            logger.info("Removing partial stitch output before retry: {}", f.getName());
            try {
                if (f.isDirectory()) {
                    try (java.util.stream.Stream<Path> walk = Files.walk(f.toPath())) {
                        walk.sorted(java.util.Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(File::delete);
                    }
                } else {
                    Files.deleteIfExists(f.toPath());
                }
            } catch (Exception e) {
                logger.warn("Could not delete {}: {}", f.getName(), e.getMessage());
            }
        }
    }

    /**
     * Append {@code _2}, {@code _3}, ... before the extension until the
     * candidate path does not exist.
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
