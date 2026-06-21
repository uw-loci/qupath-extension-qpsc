package qupath.ext.qpsc.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

/**
 * Shared SIFT auto-alignment helper.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code SingleTileRefinement} -- single-tile refinement step in the
 *       Existing Image Workflow.</li>
 *   <li>{@code AffineTransformationController} -- per-tile confirm step in
 *       the 3-point manual alignment workflow.</li>
 * </ul>
 *
 * <p>The helper extracts a region from the WSI around the selected tile,
 * sends it to the Python server for SIFT matching against a fresh
 * microscope snapshot, and moves the stage by the resulting offset.
 *
 * <p>Returns offset + diagnostic info; callers decide how to react
 * (auto-accept vs. ask the user to verify).
 */
public final class SiftAutoAlignHelper {

    private static final Logger logger = LoggerFactory.getLogger(SiftAutoAlignHelper.class);

    private SiftAutoAlignHelper() {}

    /**
     * Performs SIFT auto-alignment against the given tile.
     *
     * <p>Side-effects: stops live view, takes a snapshot via the server,
     * moves the stage by the matched offset, restores live view.
     *
     * @param gui  QuPath GUI (for accessing the image server and project entry)
     * @param tile the tile to match against
     * @return {@code [offsetX, offsetY, inliers, confidence]} on success;
     *     {@code null} if matching failed.
     * @throws Exception if a low-level error occurs (server I/O, invalid
     *     pixel calibration, etc.). Match failures (insufficient features)
     *     return null rather than throwing.
     */
    public static double[] autoAlign(QuPathGUI gui, PathObject tile) throws Exception {

        var imageData = gui.getImageData();
        if (imageData == null) throw new IllegalStateException("No image data available");

        ImageServer<BufferedImage> server = imageData.getServer();
        double wsiPixelSize = server.getPixelCalibration().getAveragedPixelSizeMicrons();
        if (Double.isNaN(wsiPixelSize) || wsiPixelSize <= 0) {
            throw new IllegalStateException("WSI has no valid pixel size calibration");
        }

        MicroscopeController mc = MicroscopeController.getInstance();
        double microPixelSize = mc.getSocketClient().getMicroscopePixelSize();

        double marginUm = PersistentPreferences.getSiftSearchMarginUm();
        double marginPx = marginUm / wsiPixelSize;

        double tileX = tile.getROI().getBoundsX();
        double tileY = tile.getROI().getBoundsY();
        double tileW = tile.getROI().getBoundsWidth();
        double tileH = tile.getROI().getBoundsHeight();

        int regionX = Math.max(0, (int) (tileX - marginPx));
        int regionY = Math.max(0, (int) (tileY - marginPx));
        int regionW = Math.min(server.getWidth() - regionX, (int) (tileW + 2 * marginPx));
        int regionH = Math.min(server.getHeight() - regionY, (int) (tileH + 2 * marginPx));

        logger.info(
                "Extracting WSI region: ({}, {}) {}x{} pixels (margin={}um={}px)",
                regionX,
                regionY,
                regionW,
                regionH,
                marginUm,
                (int) marginPx);

        RegionRequest request = RegionRequest.createInstance(server.getPath(), 1.0, regionX, regionY, regionW, regionH);
        BufferedImage wsiRegion = server.readRegion(request);

        File tempFile = File.createTempFile("sift_wsi_region_", ".png");
        tempFile.deleteOnExit();
        ImageIO.write(wsiRegion, "PNG", tempFile);
        logger.info(
                "Saved WSI region to temp file: {} ({}x{})",
                tempFile.getAbsolutePath(),
                wsiRegion.getWidth(),
                wsiRegion.getHeight());

        ProjectImageEntry<?> entry = gui.getProject() != null && gui.getImageData() != null
                ? gui.getProject().getEntry(gui.getImageData())
                : null;
        boolean flipX = entry != null && ImageMetadataManager.isFlippedX(entry);
        boolean flipY = entry != null && ImageMetadataManager.isFlippedY(entry);

        String siftDetectorId = entry != null ? ImageMetadataManager.getDetectorId(entry) : null;
        if (siftDetectorId != null) {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                flipX ^= mgr.getDetectorFlipX(siftDetectorId);
                flipY ^= mgr.getDetectorFlipY(siftDetectorId);
            }
        }

        // (Re)draw the SIFT search range on the Stage Map (no-op if it isn't
        // open), centered on the current stage position. The box is left
        // visible after the match; the hosting refinement/confirm dialog owns
        // clearing it when it closes, so the user can see the search area for
        // the whole time the dialog is up, not just during the match.
        try {
            double[] preMovePos = mc.getStagePositionXY();
            drawSearchRangeOnStageMap(gui, tile, preMovePos[0], preMovePos[1]);
        } catch (Exception ex) {
            logger.debug("Could not draw SIFT search range on Stage Map: {}", ex.getMessage());
        }

        MicroscopeController.LiveViewState liveState = mc.stopAllLiveViewing();
        try {
            double minPx = PersistentPreferences.getSiftMinPixelSize();
            double ratioThreshold = PersistentPreferences.getSiftRatioThreshold();
            int minMatchCount = PersistentPreferences.getSiftMinMatchCount();
            double contrastThreshold = PersistentPreferences.getSiftContrastThreshold();
            int nFeatures = PersistentPreferences.getSiftNFeatures();
            String monoNorm = PersistentPreferences.getSiftMonoNormalization();
            double pctLow = PersistentPreferences.getSiftPercentileLow();
            double pctHigh = PersistentPreferences.getSiftPercentileHigh();
            boolean claheEnabled = PersistentPreferences.isSiftClaheEnabled();
            double claheClip = PersistentPreferences.getSiftClaheClipLimit();
            boolean coarseToFine = PersistentPreferences.isSiftCoarseToFineEnabled();
            double coarsePx = PersistentPreferences.getSiftCoarsePixelSizeUm();
            String response = mc.getSocketClient()
                    .siftAutoAlign(
                            tempFile.getAbsolutePath(),
                            microPixelSize,
                            wsiPixelSize,
                            flipX,
                            flipY,
                            minPx,
                            ratioThreshold,
                            minMatchCount,
                            contrastThreshold,
                            nFeatures,
                            monoNorm,
                            pctLow,
                            pctHigh,
                            claheEnabled,
                            claheClip,
                            coarseToFine,
                            coarsePx);

            if (!response.startsWith("SUCCESS:")) {
                logger.warn("SIFT auto-align did not succeed: {}", response);
                return null;
            }

            String[] parts = response.substring(8).split("\\|");
            String[] offsets = parts[0].split(",");
            double offsetX = Double.parseDouble(offsets[0]);
            double offsetY = Double.parseDouble(offsets[1]);

            int inliers = 0;
            double confidence = 0;
            for (String part : parts) {
                if (part.startsWith("inliers:")) inliers = Integer.parseInt(part.substring(8));
                if (part.startsWith("confidence:")) confidence = Double.parseDouble(part.substring(11));
            }

            logger.info("SIFT offset: ({}, {}) um, inliers={}, confidence={}", offsetX, offsetY, inliers, confidence);

            double[] currentPos = mc.getStagePositionXY();
            double newX = currentPos[0] + offsetX;
            double newY = currentPos[1] + offsetY;
            logger.info("Moving stage from ({}, {}) to ({}, {})", currentPos[0], currentPos[1], newX, newY);
            mc.moveStageXY(newX, newY);

            return new double[] {offsetX, offsetY, inliers, confidence};

        } finally {
            mc.restoreLiveViewState(liveState);
            if (!tempFile.delete()) {
                logger.debug("Could not delete temp file: {}", tempFile);
            }
        }
    }

    // Context of the search-range box currently shown on the Stage Map, so it
    // can be re-drawn live when the user changes the Search margin in Settings.
    // Written from background threads (autoAlign / position fetch) and read on
    // the FX thread (spinner preview), so volatile for visibility. A slightly
    // stale center during a live preview is harmless.
    private static volatile QuPathGUI searchRangeGui;
    private static volatile PathObject searchRangeTile;
    private static volatile double searchRangeCenterX = Double.NaN;
    private static volatile double searchRangeCenterY = Double.NaN;
    private static volatile boolean searchRangeActive = false;

    /**
     * Draws the SIFT search-range box on the Stage Map (no-op if the Stage Map
     * window is closed), centered on the given stage position and sized to one
     * tile field-of-view plus the configured search margin on each side. With
     * coarse-to-fine enabled this is the area the coarse pass scans.
     *
     * <p>The box is NOT auto-cleared -- the caller (the refinement / position
     * confirmation dialog) clears it via {@link #clearSearchRangeOnStageMap()}
     * when it closes, so it stays visible for the dialog's whole lifetime. While
     * shown it tracks the Search margin preference: changing the margin in the
     * SIFT Settings dialog re-draws it (see {@link #refreshSearchRangePreview()}
     * and {@link #previewSearchRangeMargin(double)}).
     *
     * @param gui QuPath GUI (for the WSI pixel calibration). No-op if null.
     * @param tile tile being matched (its bounds give the FOV size). No-op if null.
     * @param centerStageX stage X (um) the box centers on
     * @param centerStageY stage Y (um) the box centers on
     */
    public static void drawSearchRangeOnStageMap(
            QuPathGUI gui, PathObject tile, double centerStageX, double centerStageY) {
        searchRangeGui = gui;
        searchRangeTile = tile;
        searchRangeCenterX = centerStageX;
        searchRangeCenterY = centerStageY;
        searchRangeActive = true;
        drawSearchRangeBox(gui, tile, centerStageX, centerStageY, PersistentPreferences.getSiftSearchMarginUm());
    }

    /** Shared draw with an explicit margin (used for live preview while tuning). */
    private static void drawSearchRangeBox(
            QuPathGUI gui, PathObject tile, double centerStageX, double centerStageY, double marginUm) {
        try {
            if (gui == null || tile == null || tile.getROI() == null) {
                return;
            }
            var imageData = gui.getImageData();
            if (imageData == null) {
                return;
            }
            double wsiPixelSize = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
            if (Double.isNaN(wsiPixelSize) || wsiPixelSize <= 0) {
                return;
            }
            double halfWidthUm = (tile.getROI().getBoundsWidth() * wsiPixelSize) / 2.0 + marginUm;
            double halfHeightUm = (tile.getROI().getBoundsHeight() * wsiPixelSize) / 2.0 + marginUm;
            qupath.ext.qpsc.ui.stagemap.StageMapWindow.setSearchRangePreview(
                    centerStageX - halfWidthUm,
                    centerStageY - halfHeightUm,
                    centerStageX + halfWidthUm,
                    centerStageY + halfHeightUm);
        } catch (Exception ex) {
            logger.debug("Could not draw SIFT search range on Stage Map: {}", ex.getMessage());
        }
    }

    /**
     * Convenience variant of
     * {@link #drawSearchRangeOnStageMap(QuPathGUI, PathObject, double, double)}
     * that centers on the CURRENT stage position. The position is read on a
     * background thread so this is safe to call from the FX thread (e.g. when a
     * dialog opens).
     */
    public static void drawSearchRangeAtCurrentPosition(QuPathGUI gui, PathObject tile) {
        new Thread(
                        () -> {
                            try {
                                double[] pos =
                                        MicroscopeController.getInstance().getStagePositionXY();
                                drawSearchRangeOnStageMap(gui, tile, pos[0], pos[1]);
                            } catch (Exception ex) {
                                logger.debug(
                                        "Could not resolve stage position for SIFT search range: {}", ex.getMessage());
                            }
                        },
                        "SIFT-SearchRange-Draw")
                .start();
    }

    /**
     * Re-draws the currently-shown search-range box with an explicit margin,
     * for a live preview as the user drags the Search margin spinner. No-op if
     * no box is currently shown.
     */
    public static void previewSearchRangeMargin(double marginUm) {
        if (!searchRangeActive || searchRangeTile == null) {
            return;
        }
        drawSearchRangeBox(searchRangeGui, searchRangeTile, searchRangeCenterX, searchRangeCenterY, marginUm);
    }

    /**
     * Re-draws the currently-shown search-range box using the persisted Search
     * margin preference. Called after the SIFT Settings dialog closes so the box
     * reflects the saved value (and reverts a cancelled live preview). No-op if
     * no box is currently shown.
     */
    public static void refreshSearchRangePreview() {
        if (!searchRangeActive || searchRangeTile == null) {
            return;
        }
        drawSearchRangeBox(
                searchRangeGui,
                searchRangeTile,
                searchRangeCenterX,
                searchRangeCenterY,
                PersistentPreferences.getSiftSearchMarginUm());
    }

    /** Hides the SIFT search-range box on the Stage Map, if any. */
    public static void clearSearchRangeOnStageMap() {
        searchRangeActive = false;
        searchRangeGui = null;
        searchRangeTile = null;
        searchRangeCenterX = Double.NaN;
        searchRangeCenterY = Double.NaN;
        try {
            qupath.ext.qpsc.ui.stagemap.StageMapWindow.clearSearchRangePreview();
        } catch (Exception ex) {
            logger.debug("Could not clear SIFT search range overlay: {}", ex.getMessage());
        }
    }

    /**
     * Builds an HBox containing an "Auto-Align (SIFT)" button and a
     * "Settings..." button. The buttons drive {@link #autoAlign} and
     * {@link #showSettingsDialog} respectively. Status feedback is written
     * to {@code statusLabel} (which the caller may also use elsewhere in
     * its layout).
     *
     * <p>Designed to be a drop-in row that both the SingleTileRefinement
     * dialog and the per-tile confirm dialog in the alignment workflow
     * embed.
     */
    public static HBox buildSiftButtonRow(
            QuPathGUI gui, PathObject targetTile, Window settingsOwnerSupplierTarget, Label statusLabel) {
        return buildSiftButtonRow(gui, targetTile, settingsOwnerSupplierTarget, statusLabel, null);
    }

    /**
     * Overload that invokes {@code onSiftSuccess} on the FX thread after SIFT
     * has run AND returned a valid offset. Used by callers that gate further UI
     * (e.g. the SingleTileRefinement Save button) on a real refinement having
     * occurred so a quick "Save" click can't silently skip refinement.
     */
    public static HBox buildSiftButtonRow(
            QuPathGUI gui,
            PathObject targetTile,
            Window settingsOwnerSupplierTarget,
            Label statusLabel,
            Runnable onSiftSuccess) {
        Button autoAlignButton = new Button("Auto-Align (SIFT)");
        autoAlignButton.setStyle(
                "-fx-font-weight: bold; -fx-border-color: #4A90D9; " + "-fx-border-width: 2; -fx-border-radius: 3;");
        autoAlignButton.setTooltip(new Tooltip("Auto-align using SIFT feature matching against the selected tile.\n"
                + "SIFT is a refinement, not a search: the live view must already\n"
                + "overlap the target tile by at least a few hundred microns.\n"
                + "Bit-depth normalization (16-bit camera vs 8-bit H&E WSI) and\n"
                + "CLAHE preprocessing run server-side using the values in Settings.\n"
                + "If matching fails, refine manually and confirm."));

        Button settingsButton = new Button("Settings...");
        settingsButton.setStyle("-fx-font-size: 10px;");
        settingsButton.setOnAction(e -> {
            // Resolve the owner lazily: by the time the button is clicked,
            // the dialog has been shown and its window is available.
            Window owner = settingsButton.getScene() != null
                    ? settingsButton.getScene().getWindow()
                    : settingsOwnerSupplierTarget;
            showSettingsDialog(owner);
        });

        autoAlignButton.setOnAction(e -> {
            autoAlignButton.setDisable(true);
            statusLabel.setText("Running SIFT matching...");
            statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");

            new Thread(
                            () -> {
                                try {
                                    double[] result = autoAlign(gui, targetTile);
                                    Platform.runLater(() -> {
                                        autoAlignButton.setDisable(false);
                                        if (result != null && result.length >= 2) {
                                            String confStr = result.length >= 4
                                                    ? String.format(" (%.0f%% confidence)", result[3] * 100)
                                                    : "";
                                            statusLabel.setText(String.format(
                                                    "Aligned! Offset: (%.1f, %.1f) um%s. Verify and Confirm.",
                                                    result[0], result[1], confStr));
                                            statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
                                            if (onSiftSuccess != null) {
                                                try {
                                                    onSiftSuccess.run();
                                                } catch (Exception cbEx) {
                                                    logger.warn("onSiftSuccess callback failed", cbEx);
                                                }
                                            }
                                        } else {
                                            statusLabel.setText("SIFT matching failed. Align manually.");
                                            statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: orange;");
                                        }
                                    });
                                } catch (Exception ex) {
                                    String msg = ex.getMessage();
                                    boolean isMatchFailure = msg != null
                                            && (msg.contains("insufficient features")
                                                    || msg.contains("matching failed"));
                                    if (isMatchFailure) {
                                        logger.info("SIFT matching did not find enough features -- "
                                                + "the selected tile may be outside the search range or "
                                                + "lack distinctive tissue features");
                                    } else {
                                        logger.error("Auto-align failed: {}", msg);
                                    }
                                    String userMsg = isMatchFailure
                                            ? "SIFT could not match. Try driving the stage closer to the tile, or adjust Settings."
                                            : "Error: " + msg;
                                    Platform.runLater(() -> {
                                        autoAlignButton.setDisable(false);
                                        statusLabel.setText(userMsg);
                                        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: orange;");
                                    });
                                }
                            },
                            "SIFT-AutoAlign")
                    .start();
        });

        HBox row = new HBox(8, autoAlignButton, settingsButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Async variant returning a CompletableFuture instead of relying on a
     * JavaFX status label. Used when the caller wants to chain logic
     * after match completion.
     */
    public static CompletableFuture<double[]> autoAlignAsync(QuPathGUI gui, PathObject tile) {
        CompletableFuture<double[]> future = new CompletableFuture<>();
        new Thread(
                        () -> {
                            try {
                                future.complete(autoAlign(gui, tile));
                            } catch (Exception e) {
                                future.completeExceptionally(e);
                            }
                        },
                        "SIFT-AutoAlign-Async")
                .start();
        return future;
    }

    /**
     * Shows a dialog for tuning SIFT matching parameters. Changes are
     * saved to persistent preferences and take effect on the next SIFT
     * run.
     *
     * <p>Non-modal and docked next to the owner window so the user can
     * see both at once.
     *
     * @param ownerWindow the window the settings dialog should dock next
     *     to. May be null, in which case the dialog falls back to
     *     screen-center positioning.
     */
    /**
     * Read/write strategy for the SIFT settings dialog. Lets the same dialog
     * body persist into different preference namespaces -- the default impl
     * targets the single-tile-refinement keys; the propagation impl targets
     * the propagation-refinement keys.
     */
    public interface SiftPrefsAccessor {
        double getMinPixelSize();

        void setMinPixelSize(double v);

        double getRatioThreshold();

        void setRatioThreshold(double v);

        int getMinMatchCount();

        void setMinMatchCount(int v);

        double getContrastThreshold();

        void setContrastThreshold(double v);

        double getSearchMarginUm();

        void setSearchMarginUm(double v);

        double getConfidenceThreshold();

        void setConfidenceThreshold(double v);

        String getMonoNormalization();

        void setMonoNormalization(String v);

        double getPercentileLow();

        void setPercentileLow(double v);

        double getPercentileHigh();

        void setPercentileHigh(double v);

        boolean isClaheEnabled();

        void setClaheEnabled(boolean v);

        double getClaheClipLimit();

        void setClaheClipLimit(double v);
    }

    /** Single-tile-refinement / autoAlign SIFT settings. Default scope. */
    public static final SiftPrefsAccessor DEFAULT_PREFS = new SiftPrefsAccessor() {
        public double getMinPixelSize() {
            return PersistentPreferences.getSiftMinPixelSize();
        }

        public void setMinPixelSize(double v) {
            PersistentPreferences.setSiftMinPixelSize(v);
        }

        public double getRatioThreshold() {
            return PersistentPreferences.getSiftRatioThreshold();
        }

        public void setRatioThreshold(double v) {
            PersistentPreferences.setSiftRatioThreshold(v);
        }

        public int getMinMatchCount() {
            return PersistentPreferences.getSiftMinMatchCount();
        }

        public void setMinMatchCount(int v) {
            PersistentPreferences.setSiftMinMatchCount(v);
        }

        public double getContrastThreshold() {
            return PersistentPreferences.getSiftContrastThreshold();
        }

        public void setContrastThreshold(double v) {
            PersistentPreferences.setSiftContrastThreshold(v);
        }

        public double getSearchMarginUm() {
            return PersistentPreferences.getSiftSearchMarginUm();
        }

        public void setSearchMarginUm(double v) {
            PersistentPreferences.setSiftSearchMarginUm(v);
        }

        public double getConfidenceThreshold() {
            return PersistentPreferences.getSiftConfidenceThreshold();
        }

        public void setConfidenceThreshold(double v) {
            PersistentPreferences.setSiftConfidenceThreshold(v);
        }

        public String getMonoNormalization() {
            return PersistentPreferences.getSiftMonoNormalization();
        }

        public void setMonoNormalization(String v) {
            PersistentPreferences.setSiftMonoNormalization(v);
        }

        public double getPercentileLow() {
            return PersistentPreferences.getSiftPercentileLow();
        }

        public void setPercentileLow(double v) {
            PersistentPreferences.setSiftPercentileLow(v);
        }

        public double getPercentileHigh() {
            return PersistentPreferences.getSiftPercentileHigh();
        }

        public void setPercentileHigh(double v) {
            PersistentPreferences.setSiftPercentileHigh(v);
        }

        public boolean isClaheEnabled() {
            return PersistentPreferences.isSiftClaheEnabled();
        }

        public void setClaheEnabled(boolean v) {
            PersistentPreferences.setSiftClaheEnabled(v);
        }

        public double getClaheClipLimit() {
            return PersistentPreferences.getSiftClaheClipLimit();
        }

        public void setClaheClipLimit(double v) {
            PersistentPreferences.setSiftClaheClipLimit(v);
        }
    };

    /** Propagation refinement SIFT settings. Independent scope. */
    public static final SiftPrefsAccessor PROPAGATION_PREFS = new SiftPrefsAccessor() {
        public double getMinPixelSize() {
            return PersistentPreferences.getPropSiftMinPixelSize();
        }

        public void setMinPixelSize(double v) {
            PersistentPreferences.setPropSiftMinPixelSize(v);
        }

        public double getRatioThreshold() {
            return PersistentPreferences.getPropSiftRatioThreshold();
        }

        public void setRatioThreshold(double v) {
            PersistentPreferences.setPropSiftRatioThreshold(v);
        }

        public int getMinMatchCount() {
            return PersistentPreferences.getPropSiftMinMatchCount();
        }

        public void setMinMatchCount(int v) {
            PersistentPreferences.setPropSiftMinMatchCount(v);
        }

        public double getContrastThreshold() {
            return PersistentPreferences.getPropSiftContrastThreshold();
        }

        public void setContrastThreshold(double v) {
            PersistentPreferences.setPropSiftContrastThreshold(v);
        }

        public double getSearchMarginUm() {
            return PersistentPreferences.getPropSiftSearchMarginUm();
        }

        public void setSearchMarginUm(double v) {
            PersistentPreferences.setPropSiftSearchMarginUm(v);
        }

        public double getConfidenceThreshold() {
            return PersistentPreferences.getPropSiftConfidenceThreshold();
        }

        public void setConfidenceThreshold(double v) {
            PersistentPreferences.setPropSiftConfidenceThreshold(v);
        }

        public String getMonoNormalization() {
            return PersistentPreferences.getPropSiftMonoNormalization();
        }

        public void setMonoNormalization(String v) {
            PersistentPreferences.setPropSiftMonoNormalization(v);
        }

        public double getPercentileLow() {
            return PersistentPreferences.getPropSiftPercentileLow();
        }

        public void setPercentileLow(double v) {
            PersistentPreferences.setPropSiftPercentileLow(v);
        }

        public double getPercentileHigh() {
            return PersistentPreferences.getPropSiftPercentileHigh();
        }

        public void setPercentileHigh(double v) {
            PersistentPreferences.setPropSiftPercentileHigh(v);
        }

        public boolean isClaheEnabled() {
            return PersistentPreferences.isPropSiftClaheEnabled();
        }

        public void setClaheEnabled(boolean v) {
            PersistentPreferences.setPropSiftClaheEnabled(v);
        }

        public double getClaheClipLimit() {
            return PersistentPreferences.getPropSiftClaheClipLimit();
        }

        public void setClaheClipLimit(double v) {
            PersistentPreferences.setPropSiftClaheClipLimit(v);
        }
    };

    /**
     * Show the SIFT settings dialog using propagation-scoped preferences.
     * Same UI as {@link #showSettingsDialog(Window)} but persists into a
     * separate preference namespace so the propagation refinement settings
     * don't trample the single-tile-refinement defaults.
     */
    public static void showPropagationSettingsDialog(Window ownerWindow) {
        buildAndShowSettingsDialog(ownerWindow, PROPAGATION_PREFS, "SIFT Settings (Propagation Refinement)");
    }

    public static void showSettingsDialog(Window ownerWindow) {
        buildAndShowSettingsDialog(ownerWindow, DEFAULT_PREFS, "SIFT Matching Settings");
    }

    private static void buildAndShowSettingsDialog(Window ownerWindow, SiftPrefsAccessor prefs, String title) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(400);
        dialog.initModality(Modality.NONE);
        if (ownerWindow != null) {
            dialog.initOwner(ownerWindow);
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(15));

        int row = 0;

        Spinner<Double> minPxSpinner = new Spinner<>(0.1, 5.0, prefs.getMinPixelSize(), 0.1);
        minPxSpinner.setEditable(true);
        minPxSpinner.setPrefWidth(90);
        grid.add(new Label("Min pixel size (um):"), 0, row);
        grid.add(minPxSpinner, 1, row);
        Label minPxHelp = new Label("Downsample to this resolution. Lower = more detail but slower.");
        minPxHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        minPxHelp.setWrapText(true);
        grid.add(minPxHelp, 0, ++row, 2, 1);

        Spinner<Double> ratioSpinner = new Spinner<>(0.3, 0.95, prefs.getRatioThreshold(), 0.05);
        ratioSpinner.setEditable(true);
        ratioSpinner.setPrefWidth(90);
        grid.add(new Label("Ratio threshold:"), 0, ++row);
        grid.add(ratioSpinner, 1, row);
        Label ratioHelp = new Label("Lowe's ratio test. Higher = more permissive matching (try 0.8 if failing).");
        ratioHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        ratioHelp.setWrapText(true);
        grid.add(ratioHelp, 0, ++row, 2, 1);

        Spinner<Integer> minMatchSpinner = new Spinner<>(3, 50, prefs.getMinMatchCount(), 1);
        minMatchSpinner.setPrefWidth(90);
        grid.add(new Label("Min match count:"), 0, ++row);
        grid.add(minMatchSpinner, 1, row);
        Label matchHelp = new Label("Minimum inlier matches required. Lower = accept weaker matches.");
        matchHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        matchHelp.setWrapText(true);
        grid.add(matchHelp, 0, ++row, 2, 1);

        Spinner<Double> contrastSpinner = new Spinner<>(0.001, 0.2, prefs.getContrastThreshold(), 0.005);
        contrastSpinner.setEditable(true);
        contrastSpinner.setPrefWidth(90);
        grid.add(new Label("Contrast threshold:"), 0, ++row);
        grid.add(contrastSpinner, 1, row);
        Label contrastHelp = new Label("Feature detection sensitivity. Lower = detect more features in pale tissue.");
        contrastHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        contrastHelp.setWrapText(true);
        grid.add(contrastHelp, 0, ++row, 2, 1);

        Spinner<Double> marginSpinner = new Spinner<>(50.0, 5000.0, prefs.getSearchMarginUm(), 10.0);
        marginSpinner.setEditable(true);
        marginSpinner.setPrefWidth(90);
        grid.add(new Label("Search margin (um):"), 0, ++row);
        grid.add(marginSpinner, 1, row);
        // Live-preview the search area on the Stage Map as the margin changes, so
        // the user can see how big the searched region gets (and how close they
        // need to drive the stage) before committing.
        marginSpinner.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                previewSearchRangeMargin(newV);
            }
        });
        Label marginHelp = new Label("WSI region extends this far beyond the tile on each side. "
                + "With coarse-to-fine enabled you can raise this without slowing matching.");
        marginHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        marginHelp.setWrapText(true);
        grid.add(marginHelp, 0, ++row, 2, 1);

        CheckBox coarseToFineCheckbox = new CheckBox("Coarse-to-fine search (faster large areas)");
        coarseToFineCheckbox.setSelected(PersistentPreferences.isSiftCoarseToFineEnabled());
        grid.add(coarseToFineCheckbox, 0, ++row, 2, 1);
        Label c2fHelp = new Label("Match a downsampled view of the whole search area first, then refine at full "
                + "resolution over a small crop. Lets the search margin grow without paying for full-resolution "
                + "matching over the whole region. Applies to SIFT auto-align (camera refinement).");
        c2fHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        c2fHelp.setWrapText(true);
        grid.add(c2fHelp, 0, ++row, 2, 1);

        Spinner<Double> coarsePxSpinner =
                new Spinner<>(1.0, 16.0, PersistentPreferences.getSiftCoarsePixelSizeUm(), 0.5);
        coarsePxSpinner.setEditable(true);
        coarsePxSpinner.setPrefWidth(90);
        grid.add(new Label("Coarse pixel size (um):"), 0, ++row);
        grid.add(coarsePxSpinner, 1, row);
        Label coarsePxHelp = new Label("Resolution of the coarse pass. Higher = faster and larger reach but a coarser "
                + "rough step. Only used when coarser than Min pixel size above.");
        coarsePxHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        coarsePxHelp.setWrapText(true);
        grid.add(coarsePxHelp, 0, ++row, 2, 1);

        Spinner<Double> confSpinner = new Spinner<>(0.1, 1.0, prefs.getConfidenceThreshold(), 0.05);
        confSpinner.setEditable(true);
        confSpinner.setPrefWidth(90);
        grid.add(new Label("Auto-accept confidence:"), 0, ++row);
        grid.add(confSpinner, 1, row);
        Label confHelp = new Label("Min inlier ratio to auto-accept when Trust SIFT is enabled.");
        confHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        confHelp.setWrapText(true);
        grid.add(confHelp, 0, ++row, 2, 1);

        // ---- Bit-depth normalization controls ----
        Label normHeader = new Label("Bit-depth normalization");
        normHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        grid.add(normHeader, 0, ++row, 2, 1);

        ChoiceBox<String> monoNormChoice = new ChoiceBox<>();
        monoNormChoice.getItems().addAll("PERCENTILE", "MIN_MAX", "BIT_SHIFT");
        String currentMono = prefs.getMonoNormalization();
        monoNormChoice.setValue(monoNormChoice.getItems().contains(currentMono) ? currentMono : "PERCENTILE");
        monoNormChoice.setPrefWidth(150);
        grid.add(new Label("16-bit -> 8-bit:"), 0, ++row);
        grid.add(monoNormChoice, 1, row);
        Label monoHelp = new Label("How to compress >8-bit grayscale (microscope camera) to 8-bit before matching. "
                + "PERCENTILE clips outliers and stretches; best when the camera doesn't use the full bit "
                + "range (typical 12-14 bit cameras). MIN_MAX uses the actual data extremes. "
                + "BIT_SHIFT is the legacy /256 behaviour.");
        monoHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        monoHelp.setWrapText(true);
        grid.add(monoHelp, 0, ++row, 2, 1);

        Spinner<Double> pctLowSpinner = new Spinner<>(0.0, 50.0, prefs.getPercentileLow(), 0.5);
        pctLowSpinner.setEditable(true);
        pctLowSpinner.setPrefWidth(90);
        grid.add(new Label("Percentile low:"), 0, ++row);
        grid.add(pctLowSpinner, 1, row);

        Spinner<Double> pctHighSpinner = new Spinner<>(50.0, 100.0, prefs.getPercentileHigh(), 0.5);
        pctHighSpinner.setEditable(true);
        pctHighSpinner.setPrefWidth(90);
        grid.add(new Label("Percentile high:"), 0, ++row);
        grid.add(pctHighSpinner, 1, row);
        Label pctHelp = new Label("Used only when normalization = PERCENTILE. Defaults 2 / 98 are robust against a few "
                + "saturated pixels. Lower the high (e.g. 95) if the camera is over-exposed; raise the "
                + "low (e.g. 5) for noisy backgrounds.");
        pctHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        pctHelp.setWrapText(true);
        grid.add(pctHelp, 0, ++row, 2, 1);

        CheckBox claheCheckbox = new CheckBox("Apply CLAHE (contrast equalisation)");
        claheCheckbox.setSelected(prefs.isClaheEnabled());
        grid.add(claheCheckbox, 0, ++row, 2, 1);
        Label claheHelp =
                new Label("Cross-modality contrast normalisation. Strongly recommended when matching a monochrome "
                        + "brightfield camera against an H&E (RGB) WSI -- the staining and the camera have very "
                        + "different intensity statistics, and CLAHE makes the keypoints commensurate.");
        claheHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        claheHelp.setWrapText(true);
        grid.add(claheHelp, 0, ++row, 2, 1);

        Spinner<Double> claheClipSpinner = new Spinner<>(0.5, 10.0, prefs.getClaheClipLimit(), 0.25);
        claheClipSpinner.setEditable(true);
        claheClipSpinner.setPrefWidth(90);
        grid.add(new Label("CLAHE clip limit:"), 0, ++row);
        grid.add(claheClipSpinner, 1, row);
        Label clipHelp =
                new Label("Higher = more aggressive equalisation. 2.0 default; raise to 4.0 if matches are scarce.");
        clipHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        clipHelp.setWrapText(true);
        grid.add(clipHelp, 0, ++row, 2, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                prefs.setMinPixelSize(minPxSpinner.getValue());
                prefs.setRatioThreshold(ratioSpinner.getValue());
                prefs.setMinMatchCount(minMatchSpinner.getValue());
                prefs.setContrastThreshold(contrastSpinner.getValue());
                prefs.setSearchMarginUm(marginSpinner.getValue());
                PersistentPreferences.setSiftCoarseToFineEnabled(coarseToFineCheckbox.isSelected());
                PersistentPreferences.setSiftCoarsePixelSizeUm(coarsePxSpinner.getValue());
                prefs.setConfidenceThreshold(confSpinner.getValue());
                prefs.setMonoNormalization(monoNormChoice.getValue());
                prefs.setPercentileLow(pctLowSpinner.getValue());
                prefs.setPercentileHigh(pctHighSpinner.getValue());
                prefs.setClaheEnabled(claheCheckbox.isSelected());
                prefs.setClaheClipLimit(claheClipSpinner.getValue());
                logger.info(
                        "{} updated: minPx={}, ratio={}, minMatches={}, contrast={}, margin={}, "
                                + "confidence={}, monoNorm={}, pctLow={}, pctHigh={}, clahe={}, claheClip={}",
                        title,
                        minPxSpinner.getValue(),
                        ratioSpinner.getValue(),
                        minMatchSpinner.getValue(),
                        contrastSpinner.getValue(),
                        marginSpinner.getValue(),
                        confSpinner.getValue(),
                        monoNormChoice.getValue(),
                        pctLowSpinner.getValue(),
                        pctHighSpinner.getValue(),
                        claheCheckbox.isSelected(),
                        claheClipSpinner.getValue());
            }
            return null;
        });

        dialog.setOnShown(evt -> {
            Window dialogWindow = dialog.getDialogPane().getScene() != null
                    ? dialog.getDialogPane().getScene().getWindow()
                    : null;
            if (dialogWindow instanceof Stage) {
                ((Stage) dialogWindow).setAlwaysOnTop(true);
            }
            if (ownerWindow != null && dialogWindow != null) {
                dockNextTo(dialogWindow, ownerWindow);
            }
        });

        // On close (OK persisted the new margin; Cancel discarded it), re-draw the
        // live search box from the persisted value so a cancelled preview reverts.
        dialog.setOnHidden(evt -> refreshSearchRangePreview());

        dialog.show();
    }

    /**
     * Position {@code child} immediately to the right of {@code owner}.
     * If the right side doesn't fit within the containing screen's
     * visual bounds, fall back to the left. If neither fits, center
     * horizontally. Y-align with the owner's top edge.
     */
    private static void dockNextTo(Window child, Window owner) {
        double gap = 10;
        double childW = child.getWidth();
        double childH = child.getHeight();

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double ownerCx = owner.getX() + owner.getWidth() / 2.0;
        double ownerCy = owner.getY() + owner.getHeight() / 2.0;
        for (Screen s : Screen.getScreens()) {
            if (s.getBounds().contains(ownerCx, ownerCy)) {
                bounds = s.getVisualBounds();
                break;
            }
        }

        double rightX = owner.getX() + owner.getWidth() + gap;
        double leftX = owner.getX() - gap - childW;

        if (rightX + childW <= bounds.getMaxX()) {
            child.setX(rightX);
        } else if (leftX >= bounds.getMinX()) {
            child.setX(leftX);
        } else {
            child.setX(bounds.getMinX() + Math.max(0, (bounds.getWidth() - childW) / 2.0));
        }

        double y = owner.getY();
        if (y + childH > bounds.getMaxY()) {
            y = Math.max(bounds.getMinY(), bounds.getMaxY() - childH);
        }
        if (y < bounds.getMinY()) {
            y = bounds.getMinY();
        }
        child.setY(y);
    }
}
