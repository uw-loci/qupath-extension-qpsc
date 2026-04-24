package qupath.ext.qpsc.controller;

import java.awt.image.BufferedImage;
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
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * "Make Project Portable" utility.
 *
 * <p>Scans the current project for images backed by .ome.zarr directories,
 * checks whether a matching .ome.tif file exists alongside each one (produced
 * by the OME_TIFF_VIA_ZARR background conversion), and offers to swap the
 * project entries from ZARR to TIFF using {@code entry.updateURIs()}.
 *
 * <p>After swapping, the ZARR intermediates are deleted. The result is a
 * project that uses only single-file OME-TIFFs, making it easy to copy
 * off the acquisition workstation.
 *
 * <p>All annotations, detections, image type, metadata, and thumbnails are
 * preserved because {@code updateURIs()} only changes the backing file URI
 * without touching the entry's data directory.
 */
public class MakePortableWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(MakePortableWorkflow.class);

    /** Entry point from the menu. */
    public static void run(QuPathGUI gui) {
        Project<BufferedImage> project = gui.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("Make Project Portable",
                    "No project is open. Open a project first.");
            return;
        }

        // Scan for ZARR-backed entries
        List<ZarrEntry> zarrEntries = findZarrEntries(project);

        if (zarrEntries.isEmpty()) {
            Dialogs.showInfoNotification("Make Project Portable",
                    "No ZARR-backed images found. Project is already portable.");
            return;
        }

        // Show the dialog
        showPortabilityDialog(gui, project, zarrEntries);
    }

    // ------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------

    private enum TiffStatus {
        READY, CONVERTING, MISSING
    }

    private static class ZarrEntry {
        final ProjectImageEntry<BufferedImage> entry;
        final String entryName;
        final URI zarrUri;
        final Path zarrPath;
        final Path tiffPath;
        TiffStatus status;
        long zarrSizeBytes;

        ZarrEntry(ProjectImageEntry<BufferedImage> entry, String name,
                  URI zarrUri, Path zarrPath, Path tiffPath) {
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
                            try { return Files.size(p); }
                            catch (IOException e) { return 0; }
                        }).sum();
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
                        String tiffPathStr = zarrPath.toString()
                                .replaceAll("\\.ome\\.zarr$", ".ome.tif");
                        Path tiffPath = Path.of(tiffPathStr);

                        results.add(new ZarrEntry(
                                entry, entry.getImageName(),
                                uri, zarrPath, tiffPath));
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
    // Dialog
    // ------------------------------------------------------------------

    private static void showPortabilityDialog(
            QuPathGUI gui, Project<BufferedImage> project, List<ZarrEntry> entries) {

        Stage dialog = new Stage();
        dialog.setTitle("Make Project Portable");
        dialog.initOwner(gui.getStage());

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));

        // Summary label
        Label summaryLabel = new Label();
        updateSummary(summaryLabel, entries);

        // Entry list
        VBox entryList = new VBox(4);
        for (ZarrEntry ze : entries) {
            Label entryLabel = new Label();
            updateEntryLabel(entryLabel, ze);
            entryList.getChildren().add(entryLabel);
        }
        ScrollPane scrollPane = new ScrollPane(entryList);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(200);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Progress bar (hidden initially)
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        Label statusLabel = new Label();

        // Buttons
        Button refreshBtn = new Button("Refresh");
        Button makePortableBtn = new Button("Make Portable");
        Button closeBtn = new Button("Close");

        long readyCount = entries.stream().filter(e -> e.status == TiffStatus.READY).count();
        makePortableBtn.setDisable(readyCount == 0);

        refreshBtn.setOnAction(e -> {
            entries.forEach(ZarrEntry::refresh);
            entries.forEach(ze -> {
                int idx = entries.indexOf(ze);
                if (idx < entryList.getChildren().size()) {
                    updateEntryLabel((Label) entryList.getChildren().get(idx), ze);
                }
            });
            updateSummary(summaryLabel, entries);
            long ready = entries.stream().filter(ze -> ze.status == TiffStatus.READY).count();
            makePortableBtn.setDisable(ready == 0);
        });

        makePortableBtn.setOnAction(e -> {
            makePortableBtn.setDisable(true);
            refreshBtn.setDisable(true);
            progressBar.setVisible(true);

            Thread worker = new Thread(() -> {
                List<ZarrEntry> readyEntries = entries.stream()
                        .filter(ze -> ze.status == TiffStatus.READY)
                        .toList();

                int total = readyEntries.size();
                int succeeded = 0;
                int failed = 0;
                long freedBytes = 0;

                for (int i = 0; i < total; i++) {
                    ZarrEntry ze = readyEntries.get(i);
                    final int idx = i;
                    Platform.runLater(() -> {
                        statusLabel.setText("Swapping " + (idx + 1) + "/" + total + ": " + ze.entryName);
                        progressBar.setProgress((double) idx / total);
                    });

                    try {
                        boolean ok = swapEntryToTiff(ze, project);
                        if (ok) {
                            long freed = ze.zarrSizeBytes;
                            deleteZarrDirectory(ze.zarrPath);
                            freedBytes += freed;
                            succeeded++;
                        } else {
                            failed++;
                        }
                    } catch (Exception ex) {
                        failed++;
                        logger.error("Failed to make portable: {}: {}",
                                ze.entryName, ex.getMessage(), ex);
                    }
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

                    String freedStr = freed > 1_000_000_000
                            ? String.format("%.1f GB", freed / 1_000_000_000.0)
                            : String.format("%d MB", freed / 1_000_000);

                    if (f == 0) {
                        statusLabel.setText(String.format(
                                "Done! %d images converted, %s freed.", s, freedStr));
                        Dialogs.showInfoNotification("Make Project Portable",
                                String.format("Project is now portable. %d images swapped to TIFF, %s freed.",
                                        s, freedStr));
                    } else {
                        statusLabel.setText(String.format(
                                "Done with errors: %d succeeded, %d failed, %s freed.", s, f, freedStr));
                    }

                    // Refresh the entry list
                    entries.forEach(ZarrEntry::refresh);
                    updateSummary(summaryLabel, entries);
                    makePortableBtn.setDisable(true);
                    refreshBtn.setDisable(false);
                });
            }, "make-portable-worker");
            worker.setDaemon(true);
            worker.start();
        });

        closeBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, refreshBtn, makePortableBtn, closeBtn);

        root.getChildren().addAll(summaryLabel, scrollPane, progressBar, statusLabel, buttons);

        dialog.setScene(new Scene(root, 550, 380));
        dialog.show();
    }

    private static void updateSummary(Label label, List<ZarrEntry> entries) {
        long ready = entries.stream().filter(e -> e.status == TiffStatus.READY).count();
        long converting = entries.stream().filter(e -> e.status == TiffStatus.CONVERTING).count();
        long missing = entries.stream().filter(e -> e.status == TiffStatus.MISSING).count();
        long totalBytes = entries.stream().mapToLong(e -> e.zarrSizeBytes).sum();
        String sizeStr = totalBytes > 1_000_000_000
                ? String.format("%.1f GB", totalBytes / 1_000_000_000.0)
                : String.format("%d MB", totalBytes / 1_000_000);

        label.setText(String.format(
                "Found %d ZARR-backed images (%s).\n"
                + "Ready for swap: %d | Converting: %d | Missing TIFF: %d",
                entries.size(), sizeStr, ready, converting, missing));

        if (converting > 0) {
            label.setText(label.getText()
                    + "\n(Background TIFF conversion still running -- click Refresh to check.)");
        }
        if (missing > 0) {
            label.setText(label.getText()
                    + "\n(Missing TIFFs may need re-stitching with OME_TIFF_VIA_ZARR format.)");
        }
    }

    private static void updateEntryLabel(Label label, ZarrEntry ze) {
        String statusStr = switch (ze.status) {
            case READY -> "[READY]";
            case CONVERTING -> "[CONVERTING...]";
            case MISSING -> "[TIFF MISSING]";
        };
        String sizeStr = ze.zarrSizeBytes > 1_000_000_000
                ? String.format("%.1f GB", ze.zarrSizeBytes / 1_000_000_000.0)
                : String.format("%d MB", ze.zarrSizeBytes / 1_000_000);

        label.setText(String.format("  %s  %s  (%s)", statusStr, ze.entryName, sizeStr));
        label.setStyle(ze.status == TiffStatus.READY ? "-fx-text-fill: green;"
                : ze.status == TiffStatus.CONVERTING ? "-fx-text-fill: orange;"
                : "-fx-text-fill: red;");
    }

    // ------------------------------------------------------------------
    // Swap logic
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
                logger.info("Swapped entry '{}': {} -> {}",
                        ze.entryName, ze.zarrUri, tiffUri);
                return true;
            } else {
                logger.warn("updateURIs returned false for '{}' -- URI may not have matched",
                        ze.entryName);
                return false;
            }
        } catch (IOException e) {
            logger.error("Failed to update URI for '{}': {}", ze.entryName, e.getMessage(), e);
            return false;
        }
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
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
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
