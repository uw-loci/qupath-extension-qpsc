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

        MicroscopeController.LiveViewState liveState = mc.stopAllLiveViewing();
        try {
            double minPx = PersistentPreferences.getSiftMinPixelSize();
            double ratioThreshold = PersistentPreferences.getSiftRatioThreshold();
            int minMatchCount = PersistentPreferences.getSiftMinMatchCount();
            double contrastThreshold = PersistentPreferences.getSiftContrastThreshold();
            int nFeatures = PersistentPreferences.getSiftNFeatures();
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
                            nFeatures);

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
        Button autoAlignButton = new Button("Auto-Align (SIFT)");
        autoAlignButton.setStyle(
                "-fx-font-weight: bold; -fx-border-color: #4A90D9; " + "-fx-border-width: 2; -fx-border-radius: 3;");
        autoAlignButton.setTooltip(new Tooltip(
                "Auto-align using SIFT feature matching against the selected tile.\n"
                        + "Requires distinctive tissue features visible in the live view.\n"
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
    public static void showSettingsDialog(Window ownerWindow) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("SIFT Matching Settings");
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

        Spinner<Double> minPxSpinner = new Spinner<>(0.1, 5.0, PersistentPreferences.getSiftMinPixelSize(), 0.1);
        minPxSpinner.setEditable(true);
        minPxSpinner.setPrefWidth(90);
        grid.add(new Label("Min pixel size (um):"), 0, row);
        grid.add(minPxSpinner, 1, row);
        Label minPxHelp = new Label("Downsample to this resolution. Lower = more detail but slower.");
        minPxHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        minPxHelp.setWrapText(true);
        grid.add(minPxHelp, 0, ++row, 2, 1);

        Spinner<Double> ratioSpinner = new Spinner<>(0.3, 0.95, PersistentPreferences.getSiftRatioThreshold(), 0.05);
        ratioSpinner.setEditable(true);
        ratioSpinner.setPrefWidth(90);
        grid.add(new Label("Ratio threshold:"), 0, ++row);
        grid.add(ratioSpinner, 1, row);
        Label ratioHelp = new Label("Lowe's ratio test. Higher = more permissive matching (try 0.8 if failing).");
        ratioHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        ratioHelp.setWrapText(true);
        grid.add(ratioHelp, 0, ++row, 2, 1);

        Spinner<Integer> minMatchSpinner = new Spinner<>(3, 50, PersistentPreferences.getSiftMinMatchCount(), 1);
        minMatchSpinner.setPrefWidth(90);
        grid.add(new Label("Min match count:"), 0, ++row);
        grid.add(minMatchSpinner, 1, row);
        Label matchHelp = new Label("Minimum inlier matches required. Lower = accept weaker matches.");
        matchHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        matchHelp.setWrapText(true);
        grid.add(matchHelp, 0, ++row, 2, 1);

        Spinner<Double> contrastSpinner =
                new Spinner<>(0.001, 0.2, PersistentPreferences.getSiftContrastThreshold(), 0.005);
        contrastSpinner.setEditable(true);
        contrastSpinner.setPrefWidth(90);
        grid.add(new Label("Contrast threshold:"), 0, ++row);
        grid.add(contrastSpinner, 1, row);
        Label contrastHelp = new Label("Feature detection sensitivity. Lower = detect more features in pale tissue.");
        contrastHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        contrastHelp.setWrapText(true);
        grid.add(contrastHelp, 0, ++row, 2, 1);

        Spinner<Double> marginSpinner = new Spinner<>(50.0, 500.0, PersistentPreferences.getSiftSearchMarginUm(), 10.0);
        marginSpinner.setEditable(true);
        marginSpinner.setPrefWidth(90);
        grid.add(new Label("Search margin (um):"), 0, ++row);
        grid.add(marginSpinner, 1, row);
        Label marginHelp = new Label("WSI region extends this far beyond the tile on each side.");
        marginHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        marginHelp.setWrapText(true);
        grid.add(marginHelp, 0, ++row, 2, 1);

        Spinner<Double> confSpinner = new Spinner<>(0.1, 1.0, PersistentPreferences.getSiftConfidenceThreshold(), 0.05);
        confSpinner.setEditable(true);
        confSpinner.setPrefWidth(90);
        grid.add(new Label("Auto-accept confidence:"), 0, ++row);
        grid.add(confSpinner, 1, row);
        Label confHelp = new Label("Min inlier ratio to auto-accept when Trust SIFT is enabled.");
        confHelp.setStyle("-fx-font-size: 9px; -fx-text-fill: #888;");
        confHelp.setWrapText(true);
        grid.add(confHelp, 0, ++row, 2, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                PersistentPreferences.setSiftMinPixelSize(minPxSpinner.getValue());
                PersistentPreferences.setSiftRatioThreshold(ratioSpinner.getValue());
                PersistentPreferences.setSiftMinMatchCount(minMatchSpinner.getValue());
                PersistentPreferences.setSiftContrastThreshold(contrastSpinner.getValue());
                PersistentPreferences.setSiftSearchMarginUm(marginSpinner.getValue());
                PersistentPreferences.setSiftConfidenceThreshold(confSpinner.getValue());
                logger.info(
                        "SIFT settings updated: minPx={}, ratio={}, minMatches={}, contrast={}, margin={}, confidence={}",
                        minPxSpinner.getValue(),
                        ratioSpinner.getValue(),
                        minMatchSpinner.getValue(),
                        contrastSpinner.getValue(),
                        marginSpinner.getValue(),
                        confSpinner.getValue());
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
