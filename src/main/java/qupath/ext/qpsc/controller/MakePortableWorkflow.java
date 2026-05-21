package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
import qupath.ext.qpsc.utilities.TileProcessingUtilities;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * "Make Project Portable" utility.
 *
 * <p>Scans the current project for images backed by .ome.zarr directories and
 * lets the user pick, independently:
 *
 * <ul>
 *   <li><b>ZARR handling</b> -- one of:
 *     <ul>
 *       <li><i>Convert to OME-TIFF</i>: swap each project entry from ZARR to a
 *           sibling .ome.tif and delete the ZARR. ZARR files that have no
 *           .ome.tif yet (e.g. produced by re-stitch recovery, or whose
 *           background conversion never ran) are converted on the spot via
 *           {@link StitchingHelper#convertSingleZarrToTiff}. This can take
 *           several minutes per file.</li>
 *       <li><i>Zip ZARR</i>: zip each .ome.zarr directory into a sibling
 *           .ome.zarr.zip archive and delete the directory. The project entry
 *           is NOT repointed -- QuPath's image reader cannot open a zipped
 *           ZARR, so the .ome.zarr.zip archives must be extracted back to
 *           .ome.zarr directories before the project is reopened.</li>
 *       <li><i>Leave untouched</i>: do not touch the ZARR files (use this with
 *           tile deletion to only clean up raw tiles).</li>
 *     </ul></li>
 *   <li><b>Tile images</b> -- whether to delete the raw individual tile images
 *       (the per-mode acquisition folders alongside {@code SlideImages}), which
 *       are only needed to re-stitch.</li>
 * </ul>
 *
 * <p>The dialog warns that the deletions are permanent and asks for
 * confirmation before doing any work.
 *
 * <p>For the Convert path, all annotations, detections, image type, metadata,
 * and thumbnails are preserved because {@code updateURIs()} only changes the
 * backing file URI without touching the entry's data directory. Acquisition
 * metadata files inside the tile folders are also preserved -- only raw tile
 * images are removed.
 */
public class MakePortableWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MakePortableWorkflow.class);

    /** Compression used for the on-the-spot ZARR -> OME-TIFF conversion. LZW is
     * lossless and valid for every OME-TIFF writer path. */
    private static final String TIFF_COMPRESSION = "LZW";

    /** Entry point from the menu. */
    public static void run(QuPathGUI gui) {
        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("Make Project Portable", "No project is open. Open a project first.");
            return;
        }

        // Scan for ZARR-backed entries
        List<ZarrEntry> zarrEntries = findZarrEntries(project);

        if (zarrEntries.isEmpty()) {
            Dialogs.showInfoNotification(
                    "Make Project Portable", "No ZARR-backed images found. Project is already portable.");
            return;
        }

        // Show the dialog
        showPortabilityDialog(gui, project, zarrEntries);
    }

    // ------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------

    private enum TiffStatus {
        READY,
        CONVERTING,
        MISSING
    }

    /** What the user wants done with the .ome.zarr files. */
    private enum ZarrAction {
        CONVERT_TIFF("Convert ZARR to OME-TIFF (recommended)"),
        ZIP("Zip ZARR to .ome.zarr.zip archive"),
        LEAVE("Leave ZARR untouched");

        private final String label;

        ZarrAction(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static class ZarrEntry {
        final ProjectImageEntry<BufferedImage> entry;
        final String entryName;
        final URI zarrUri;
        final Path zarrPath;
        final Path tiffPath;
        TiffStatus status;
        long zarrSizeBytes;

        ZarrEntry(ProjectImageEntry<BufferedImage> entry, String name, URI zarrUri, Path zarrPath, Path tiffPath) {
            this.entry = entry;
            this.entryName = name;
            this.zarrUri = zarrUri;
            this.zarrPath = zarrPath;
            this.tiffPath = tiffPath;
            this.status = computeStatus();
            this.zarrSizeBytes = computeZarrSize();
        }

        private TiffStatus computeStatus() {
            if (Files.exists(tiffPath)) return TiffStatus.READY;
            // Check for .writing temp file (conversion in progress)
            Path writing = Path.of(tiffPath + ".writing");
            if (Files.exists(writing)) return TiffStatus.CONVERTING;
            return TiffStatus.MISSING;
        }

        private long computeZarrSize() {
            try (Stream<Path> walk = Files.walk(zarrPath)) {
                return walk.filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
            } catch (IOException e) {
                return 0;
            }
        }

        void refresh() {
            this.status = computeStatus();
        }
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private static List<ZarrEntry> findZarrEntries(Project<BufferedImage> project) {
        List<ZarrEntry> results = new ArrayList<>();

        for (var entry : project.getImageList()) {
            try {
                for (URI uri : entry.getURIs()) {
                    String uriStr = uri.toString();
                    if (uriStr.contains(".ome.zarr")) {
                        Path zarrPath = Path.of(uri);
                        String tiffPathStr = zarrPath.toString().replaceAll("\\.ome\\.zarr$", ".ome.tif");
                        Path tiffPath = Path.of(tiffPathStr);

                        results.add(new ZarrEntry(entry, entry.getImageName(), uri, zarrPath, tiffPath));
                        break; // one ZARR URI per entry is enough
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not check URIs for entry {}: {}", entry.getImageName(), e.getMessage());
            }
        }

        logger.info("Found {} ZARR-backed entries in project", results.size());
        return results;
    }

    // ------------------------------------------------------------------
    // Individual tile images
    // ------------------------------------------------------------------

    /** Result of scanning the sample folder for individual (un-stitched) tile images. */
    private static class TileScan {
        final List<Path> tileDirs;
        final long tileFileCount;
        final long tileSizeBytes;

        TileScan(List<Path> tileDirs, long tileFileCount, long tileSizeBytes) {
            this.tileDirs = tileDirs;
            this.tileFileCount = tileFileCount;
            this.tileSizeBytes = tileSizeBytes;
        }
    }

    /**
     * Scan the sample folder for acquisition tile directories -- the per-mode
     * folders (e.g. {@code ppm_10x_1}) that sit alongside {@code SlideImages}
     * and hold the raw, un-stitched tile images.
     *
     * <p>The sample folder is derived from a ZARR entry's path:
     * {@code <sampleDir>/SlideImages/<name>.ome.zarr}. A tile directory is any
     * direct child of the sample folder other than {@code SlideImages} and the
     * QuPath {@code data} directory that contains at least one raw tile image.
     */
    private static TileScan scanTileDirectories(List<ZarrEntry> entries) {
        List<Path> tileDirs = new ArrayList<>();
        if (entries.isEmpty()) return new TileScan(tileDirs, 0, 0);

        Path slideImagesDir = entries.get(0).zarrPath.getParent();
        if (slideImagesDir == null) return new TileScan(tileDirs, 0, 0);
        Path sampleDir = slideImagesDir.getParent();
        if (sampleDir == null || !Files.isDirectory(sampleDir)) return new TileScan(tileDirs, 0, 0);

        long totalCount = 0;
        long totalBytes = 0;
        try (Stream<Path> children = Files.list(sampleDir)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String name = child.getFileName().toString();
                if (name.equals("SlideImages") || name.equals("data")) continue;
                long[] stat = tileStats(child);
                if (stat[0] > 0) {
                    tileDirs.add(child);
                    totalCount += stat[0];
                    totalBytes += stat[1];
                }
            }
        } catch (IOException e) {
            logger.warn("Could not scan sample folder for tile images: {}", e.getMessage());
        }

        logger.info(
                "Found {} tile image(s) across {} acquisition folder(s) under {}",
                totalCount,
                tileDirs.size(),
                sampleDir);
        return new TileScan(tileDirs, totalCount, totalBytes);
    }

    /** Returns {@code [rawTileCount, totalBytes]} for raw tile images under {@code dir}. */
    private static long[] tileStats(Path dir) {
        long count = 0;
        long bytes = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (!isRawTile(p)) continue;
                count++;
                try {
                    bytes += Files.size(p);
                } catch (IOException ignored) {
                    // size unavailable; count still increments
                }
            }
        } catch (IOException e) {
            logger.debug("Could not stat tile dir {}: {}", dir, e.getMessage());
        }
        return new long[] {count, bytes};
    }

    /** True for raw acquisition tile images (.tif/.tiff, excluding stitched .ome.tif). */
    private static boolean isRawTile(Path p) {
        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".ome.tif") || name.endsWith(".ome.tiff")) return false;
        return name.endsWith(".tif") || name.endsWith(".tiff");
    }

    /** Format a byte count as a human-readable GB/MB string. */
    private static String formatBytes(long bytes) {
        return bytes > 1_000_000_000
                ? String.format("%.1f GB", bytes / 1_000_000_000.0)
                : String.format("%d MB", bytes / 1_000_000);
    }

    // ------------------------------------------------------------------
    // Dialog
    // ------------------------------------------------------------------

    private static void showPortabilityDialog(QuPathGUI gui, Project<BufferedImage> project, List<ZarrEntry> entries) {

        TileScan tileScan = scanTileDirectories(entries);
        boolean hasTiles = !tileScan.tileDirs.isEmpty();

        Stage dialog = new Stage();
        dialog.setTitle("Make Project Portable");
        dialog.initOwner(gui.getStage());

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));

        // Summary label
        Label summaryLabel = new Label();

        // Entry list
        VBox entryList = new VBox(4);
        for (ZarrEntry ze : entries) {
            Label entryLabel = new Label();
            updateEntryLabel(entryLabel, ze);
            entryList.getChildren().add(entryLabel);
        }
        ScrollPane scrollPane = new ScrollPane(entryList);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(150);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // ZARR handling selector
        ComboBox<ZarrAction> zarrActionCombo = new ComboBox<>();
        zarrActionCombo.getItems().addAll(ZarrAction.values());
        zarrActionCombo.setValue(ZarrAction.CONVERT_TIFF);
        HBox zarrActionBox = new HBox(8, new Label("ZARR handling:"), zarrActionCombo);
        zarrActionBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Individual-tile-image controls
        CheckBox keepTilesCheckbox = new CheckBox("Keep individual tile images");
        keepTilesCheckbox.setSelected(false);
        keepTilesCheckbox.setDisable(!hasTiles);

        Label tileInfoLabel = new Label();
        tileInfoLabel.setWrapText(true);
        if (hasTiles) {
            tileInfoLabel.setText(String.format(
                    "Found %d individual tile image(s) in %d acquisition folder(s) (%s). "
                            + "These are deleted by default -- they are only needed to re-stitch. "
                            + "Check the box above to keep them.",
                    tileScan.tileFileCount, tileScan.tileDirs.size(), formatBytes(tileScan.tileSizeBytes)));
            tileInfoLabel.setStyle("-fx-text-fill: #b06000;");
        } else {
            tileInfoLabel.setText("No individual tile images found in the project folder.");
        }

        // Warning label (mode-aware)
        Label warningLabel = new Label();
        warningLabel.setWrapText(true);

        // Progress bar (hidden initially)
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        Label statusLabel = new Label();

        // Buttons
        Button refreshBtn = new Button("Refresh");
        Button makePortableBtn = new Button("Make Portable");
        Button closeBtn = new Button("Close");

        // Shared UI-state refresh: summary, warning, button enablement.
        Runnable refreshState = () -> {
            ZarrAction action = zarrActionCombo.getValue();
            boolean willDeleteTiles = hasTiles && !keepTilesCheckbox.isSelected();
            updateSummary(summaryLabel, entries, action);
            updateWarning(warningLabel, action, willDeleteTiles);
            makePortableBtn.setDisable(!hasWorkToDo(entries, action, willDeleteTiles));
        };
        refreshState.run();

        zarrActionCombo.valueProperty().addListener((obs, o, n) -> refreshState.run());
        keepTilesCheckbox.selectedProperty().addListener((obs, o, n) -> refreshState.run());

        refreshBtn.setOnAction(e -> {
            entries.forEach(ZarrEntry::refresh);
            for (int idx = 0;
                    idx < entries.size() && idx < entryList.getChildren().size();
                    idx++) {
                updateEntryLabel((Label) entryList.getChildren().get(idx), entries.get(idx));
            }
            refreshState.run();
        });

        makePortableBtn.setOnAction(e -> {
            ZarrAction action = zarrActionCombo.getValue();
            boolean keepTiles = keepTilesCheckbox.isSelected();
            boolean willDeleteTiles = hasTiles && !keepTiles;

            boolean confirmed = Dialogs.showConfirmDialog(
                    "Make Project Portable", buildConfirmMessage(entries, tileScan, action, willDeleteTiles, hasTiles));
            if (!confirmed) {
                return;
            }

            makePortableBtn.setDisable(true);
            refreshBtn.setDisable(true);
            keepTilesCheckbox.setDisable(true);
            zarrActionCombo.setDisable(true);
            progressBar.setVisible(true);

            Thread worker = new Thread(
                    () -> runPortability(
                            project,
                            entries,
                            tileScan,
                            action,
                            willDeleteTiles,
                            progressBar,
                            statusLabel,
                            summaryLabel,
                            makePortableBtn,
                            refreshBtn),
                    "make-portable-worker");
            worker.setDaemon(true);
            worker.start();
        });

        closeBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, refreshBtn, makePortableBtn, closeBtn);

        root.getChildren()
                .addAll(
                        summaryLabel,
                        scrollPane,
                        zarrActionBox,
                        keepTilesCheckbox,
                        tileInfoLabel,
                        warningLabel,
                        progressBar,
                        statusLabel,
                        buttons);

        dialog.setScene(new Scene(root, 580, 540));
        dialog.show();
    }

    /** True when the selected options would change something on disk. */
    private static boolean hasWorkToDo(List<ZarrEntry> entries, ZarrAction action, boolean willDeleteTiles) {
        if (action == ZarrAction.LEAVE) {
            return willDeleteTiles;
        }
        // Convert / Zip: any non-CONVERTING ZARR entry is processable. Even with
        // all entries mid-conversion, a pending tile cleanup is still work.
        boolean anyProcessableZarr = entries.stream().anyMatch(ze -> ze.status != TiffStatus.CONVERTING);
        return anyProcessableZarr || willDeleteTiles;
    }

    private static void updateSummary(Label label, List<ZarrEntry> entries, ZarrAction action) {
        long ready = entries.stream().filter(e -> e.status == TiffStatus.READY).count();
        long converting =
                entries.stream().filter(e -> e.status == TiffStatus.CONVERTING).count();
        long missing =
                entries.stream().filter(e -> e.status == TiffStatus.MISSING).count();
        long totalBytes = entries.stream().mapToLong(e -> e.zarrSizeBytes).sum();

        StringBuilder sb = new StringBuilder(String.format(
                "Found %d ZARR-backed images (%s).%n" + "OME-TIFF ready: %d | Converting: %d | OME-TIFF missing: %d",
                entries.size(), formatBytes(totalBytes), ready, converting, missing));

        if (converting > 0) {
            sb.append("\n(Background TIFF conversion still running -- click Refresh to check.)");
        }
        if (action == ZarrAction.CONVERT_TIFF && missing > 0) {
            sb.append("\n(Missing OME-TIFFs will be converted from ZARR now -- this may take a while.)");
        }
        label.setText(sb.toString());
    }

    /** Mode-aware warning text shown above the buttons. */
    private static void updateWarning(Label label, ZarrAction action, boolean willDeleteTiles) {
        String msg;
        switch (action) {
            case CONVERT_TIFF ->
                msg = "Warning: ZARR intermediates are permanently deleted after conversion"
                        + (willDeleteTiles ? " and the individual tile images are deleted" : "")
                        + ". ZARR files with no existing OME-TIFF are converted now -- this may take "
                        + "several minutes per file depending on its size. This cannot be undone.";
            case ZIP ->
                msg = "Warning: each ZARR is zipped to a .ome.zarr.zip archive and the original "
                        + ".ome.zarr directory is deleted"
                        + (willDeleteTiles ? "; individual tile images are also deleted" : "")
                        + ". You MUST extract each .ome.zarr.zip back to a .ome.zarr directory before "
                        + "reopening this project -- QuPath's image reader cannot open a zipped ZARR. "
                        + "This cannot be undone.";
            default ->
                msg = willDeleteTiles
                        ? "Warning: individual tile images are permanently deleted. ZARR files are left "
                                + "untouched. This cannot be undone."
                        : "Nothing selected to change: ZARR files are left untouched and tile images are kept.";
        }
        label.setText(msg);
        boolean inert = action == ZarrAction.LEAVE && !willDeleteTiles;
        label.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (inert ? "gray" : "red") + ";");
    }

    /** Build the confirmation-dialog message for the chosen options. */
    private static String buildConfirmMessage(
            List<ZarrEntry> entries, TileScan tileScan, ZarrAction action, boolean willDeleteTiles, boolean hasTiles) {

        StringBuilder m = new StringBuilder();
        long converting =
                entries.stream().filter(e -> e.status == TiffStatus.CONVERTING).count();
        long processable = entries.size() - converting;

        switch (action) {
            case CONVERT_TIFF -> {
                long missing = entries.stream()
                        .filter(e -> e.status == TiffStatus.MISSING)
                        .count();
                m.append(String.format(
                        "%d ZARR image(s) will be swapped to OME-TIFF and the ZARR intermediates deleted.%n",
                        processable));
                if (missing > 0) {
                    m.append(String.format(
                            "  %d of them have no OME-TIFF yet and will be converted now -- "
                                    + "this may take several minutes per file depending on size.%n",
                            missing));
                }
                if (converting > 0) {
                    m.append(String.format(
                            "  %d are still being converted in the background and will be skipped "
                                    + "(use Refresh later).%n",
                            converting));
                }
                m.append("\n");
            }
            case ZIP -> {
                m.append(String.format(
                        "%d ZARR director(ies) will be zipped to .ome.zarr.zip and the originals deleted.%n",
                        entries.size()));
                m.append("IMPORTANT: extract the .ome.zarr.zip archives back to .ome.zarr directories "
                        + "before reopening this project, or the images will not load.\n\n");
            }
            default -> m.append("ZARR files will be left untouched.\n\n");
        }

        if (willDeleteTiles) {
            m.append(String.format(
                    "%d individual tile image(s) (%s) will be DELETED.%n%n",
                    tileScan.tileFileCount, formatBytes(tileScan.tileSizeBytes)));
        } else if (hasTiles) {
            m.append("Individual tile images will be KEPT.\n\n");
        }

        m.append("This cannot be undone. Continue?");
        return m.toString();
    }

    private static void updateEntryLabel(Label label, ZarrEntry ze) {
        String statusStr =
                switch (ze.status) {
                    case READY -> "[OME-TIFF READY]";
                    case CONVERTING -> "[CONVERTING...]";
                    case MISSING -> "[OME-TIFF MISSING]";
                };
        label.setText(String.format("  %s  %s  (%s)", statusStr, ze.entryName, formatBytes(ze.zarrSizeBytes)));
        label.setStyle(
                ze.status == TiffStatus.READY
                        ? "-fx-text-fill: green;"
                        : ze.status == TiffStatus.CONVERTING ? "-fx-text-fill: orange;" : "-fx-text-fill: red;");
    }

    // ------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------

    /**
     * Off-EDT worker that applies the chosen ZARR action and tile cleanup.
     * UI updates are marshalled back via {@link Platform#runLater}.
     */
    private static void runPortability(
            Project<BufferedImage> project,
            List<ZarrEntry> entries,
            TileScan tileScan,
            ZarrAction action,
            boolean willDeleteTiles,
            ProgressBar progressBar,
            Label statusLabel,
            Label summaryLabel,
            Button makePortableBtn,
            Button refreshBtn) {

        // CONVERTING entries are mid background-conversion -- never touch them.
        List<ZarrEntry> toProcess = action == ZarrAction.LEAVE
                ? List.of()
                : entries.stream()
                        .filter(ze -> ze.status != TiffStatus.CONVERTING)
                        .toList();

        int total = toProcess.size();
        int succeeded = 0;
        int failed = 0;
        long freedBytes = 0;

        for (int i = 0; i < total; i++) {
            ZarrEntry ze = toProcess.get(i);
            final int idx = i;
            final boolean willConvert = action == ZarrAction.CONVERT_TIFF && ze.status == TiffStatus.MISSING;
            Platform.runLater(() -> {
                String verb = action == ZarrAction.ZIP
                        ? "Zipping"
                        : willConvert ? "Converting (large files may take several minutes)" : "Swapping";
                statusLabel.setText(verb + " " + (idx + 1) + "/" + total + ": " + ze.entryName);
                progressBar.setProgress((double) idx / total);
            });

            try {
                if (action == ZarrAction.ZIP) {
                    if (zipZarrDirectory(ze.zarrPath)) {
                        long zipSize = 0;
                        try {
                            zipSize = Files.size(Path.of(ze.zarrPath + ".zip"));
                        } catch (IOException ignored) {
                            // size unavailable -- freed estimate just omits it
                        }
                        deleteZarrDirectory(ze.zarrPath);
                        freedBytes += Math.max(0, ze.zarrSizeBytes - zipSize);
                        succeeded++;
                    } else {
                        failed++;
                    }
                } else {
                    // CONVERT_TIFF
                    if (willConvert) {
                        boolean converted =
                                StitchingHelper.convertSingleZarrToTiff(ze.zarrPath.toString(), TIFF_COMPRESSION);
                        if (!converted || !Files.exists(ze.tiffPath)) {
                            logger.error("ZARR -> TIFF conversion failed for '{}'; skipping swap", ze.entryName);
                            failed++;
                            continue;
                        }
                    }
                    if (swapEntryToTiff(ze, project)) {
                        deleteZarrDirectory(ze.zarrPath);
                        freedBytes += ze.zarrSizeBytes;
                        succeeded++;
                    } else {
                        failed++;
                    }
                }
            } catch (Exception ex) {
                failed++;
                logger.error("Failed to make portable: {}: {}", ze.entryName, ex.getMessage(), ex);
            }
        }

        // Delete individual tile images when the user asked for it.
        if (willDeleteTiles) {
            Platform.runLater(() -> statusLabel.setText("Deleting individual tile images..."));
            for (Path tileDir : tileScan.tileDirs) {
                try {
                    TileProcessingUtilities.deleteTilesAndFolder(tileDir.toString());
                } catch (Exception ex) {
                    logger.error("Failed to delete tile images in {}: {}", tileDir, ex.getMessage(), ex);
                }
            }
            freedBytes += tileScan.tileSizeBytes;
        }

        final int s = succeeded;
        final int f = failed;
        final long freed = freedBytes;
        Platform.runLater(() -> {
            progressBar.setProgress(1.0);
            try {
                project.syncChanges();
            } catch (IOException ex) {
                logger.error("Failed to sync project: {}", ex.getMessage());
            }

            String freedStr = formatBytes(freed);
            String summary = portabilityResultMessage(action, s, f, freedStr, willDeleteTiles);
            statusLabel.setText(summary);
            if (f == 0) {
                Dialogs.showInfoNotification("Make Project Portable", summary);
            } else {
                Dialogs.showWarningNotification("Make Project Portable", summary);
            }

            entries.forEach(ZarrEntry::refresh);
            updateSummary(summaryLabel, entries, action);
            makePortableBtn.setDisable(true);
            refreshBtn.setDisable(false);
        });
    }

    /** Compose the human-readable result line for the completed operation. */
    private static String portabilityResultMessage(
            ZarrAction action, int succeeded, int failed, String freedStr, boolean deletedTiles) {
        String tilePart = deletedTiles ? " Tile images deleted." : "";
        return switch (action) {
            case CONVERT_TIFF ->
                failed == 0
                        ? String.format(
                                "Project is now portable. %d image(s) swapped to OME-TIFF, %s freed.%s",
                                succeeded, freedStr, tilePart)
                        : String.format(
                                "Done with errors: %d converted, %d failed, %s freed.%s",
                                succeeded, failed, freedStr, tilePart);
            case ZIP ->
                failed == 0
                        ? String.format(
                                "%d ZARR director(ies) zipped, %s freed. Extract the .ome.zarr.zip "
                                        + "archives before reopening this project.%s",
                                succeeded, freedStr, tilePart)
                        : String.format(
                                "Done with errors: %d zipped, %d failed, %s freed.%s",
                                succeeded, failed, freedStr, tilePart);
            default ->
                deletedTiles
                        ? String.format("Tile cleanup complete: %s freed. ZARR files left untouched.", freedStr)
                        : "Nothing to do.";
        };
    }

    // ------------------------------------------------------------------
    // ZARR operations
    // ------------------------------------------------------------------

    /**
     * Swap a project entry's backing file from ZARR to TIFF using
     * {@code entry.updateURIs()}, which preserves all data (annotations,
     * image type, metadata, thumbnails).
     */
    private static boolean swapEntryToTiff(ZarrEntry ze, Project<BufferedImage> project) {
        try {
            URI tiffUri = ze.tiffPath.toUri();
            Map<URI, URI> replacements = new HashMap<>();
            replacements.put(ze.zarrUri, tiffUri);

            boolean changed = ze.entry.updateURIs(replacements);
            if (changed) {
                logger.info("Swapped entry '{}': {} -> {}", ze.entryName, ze.zarrUri, tiffUri);
                return true;
            } else {
                logger.warn("updateURIs returned false for '{}' -- URI may not have matched", ze.entryName);
                return false;
            }
        } catch (IOException e) {
            logger.error("Failed to update URI for '{}': {}", ze.entryName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Zip a .ome.zarr directory into a sibling {@code <name>.ome.zarr.zip}
     * archive. Archive entries are stored relative to the directory's parent
     * (so the archive expands back to a {@code .ome.zarr} directory in place).
     *
     * <p>The project entry is intentionally NOT repointed -- QuPath's image
     * reader cannot open a zipped ZARR, so the entry keeps referencing the
     * original {@code .ome.zarr} path, which becomes valid again once the user
     * extracts the archive on the destination machine.
     *
     * @return true if the archive was written and is non-empty
     */
    static boolean zipZarrDirectory(Path zarrDir) {
        if (!Files.isDirectory(zarrDir) || !zarrDir.toString().endsWith(".ome.zarr")) {
            logger.warn("Refusing to zip non-ZARR path: {}", zarrDir);
            return false;
        }
        Path zipPath = Path.of(zarrDir + ".zip");
        Path base = zarrDir.getParent();
        if (base == null) {
            logger.warn("ZARR directory has no parent, cannot zip: {}", zarrDir);
            return false;
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()));
                Stream<Path> walk = Files.walk(zarrDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                // Forward slashes for cross-platform archive entry names.
                String rel = base.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                Files.copy(p, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            logger.error("Failed to zip ZARR {}: {}", zarrDir, e.getMessage(), e);
            try {
                if (Files.exists(zipPath)) Files.delete(zipPath);
            } catch (IOException ignored) {
                // best-effort cleanup of the partial archive
            }
            return false;
        }

        try {
            if (Files.exists(zipPath) && Files.size(zipPath) > 0) {
                logger.info("Zipped ZARR {} -> {}", zarrDir.getFileName(), zipPath.getFileName());
                return true;
            }
        } catch (IOException ignored) {
            // fall through to the failure log
        }
        logger.error("ZIP archive missing or empty after zipping {}", zarrDir);
        return false;
    }

    /**
     * Recursively delete a .ome.zarr directory and all its contents.
     * Only deletes paths ending in .ome.zarr as a safety check.
     */
    static void deleteZarrDirectory(Path zarrDir) {
        try {
            if (!Files.isDirectory(zarrDir) || !zarrDir.toString().endsWith(".ome.zarr")) {
                logger.warn("Refusing to delete non-ZARR path: {}", zarrDir);
                return;
            }

            try (Stream<Path> walk = Files.walk(zarrDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        logger.warn("Could not delete {}: {}", p, e.getMessage());
                    }
                });
            }

            if (!Files.exists(zarrDir)) {
                logger.info("Deleted ZARR intermediate: {}", zarrDir.getFileName());
            } else {
                logger.warn("ZARR directory partially deleted (some files locked?): {}", zarrDir);
            }
        } catch (Exception e) {
            logger.warn("Failed to delete ZARR directory {}: {}", zarrDir, e.getMessage());
        }
    }
}
