package qupath.ext.qpsc.service.mda;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Shared "Save as MicroManager MDA..." export helper used by the per-modality
 * bounding-box UI panels. Writes one MDA file set per region via
 * {@link MdaRequestBuilder} + {@link MdaSettingsWriter}, then shows a single
 * confirmation alert (with an "Open Folder" button) or a single error alert
 * if anything failed. All alerts go through
 * {@link UIFunctions#showAlertOverParent(Alert, Window)} so they sit above
 * always-on-top parent stages.
 *
 * <p>The actual file writes run on the calling thread; callers should hand
 * this work off to a background executor (see {@code CompletableFuture})
 * because tile-count growth can make the per-region build and write
 * non-trivial. Alerts are always shown on the JavaFX Application Thread.
 */
public final class MdaExportAction {

    private static final Logger logger = LoggerFactory.getLogger(MdaExportAction.class);

    private MdaExportAction() {}

    /**
     * One region's contribution to an MDA export: the region's display name,
     * the per-region output directory the MDA files should land in, and the
     * already-resolved tile centroids in stage micrometers.
     */
    public record RegionPlan(String regionName, Path regionDir, List<TileStagePos> tiles) {}

    /**
     * Writes one MDA file set (settings + position list + notes) per region
     * using {@code cmdBuilder} as the single source of truth for channel,
     * Z, time-lapse, and inner-axis configuration. Shows one confirmation or
     * error alert when finished.
     *
     * @param parentWindow   parent stage for alert re-parenting (may be null).
     * @param sample         sample setup metadata embedded into the request.
     * @param cmdBuilder     fully-configured acquisition command builder.
     * @param regions        one entry per region to export. Must be non-empty;
     *                       caller is expected to have already validated this.
     * @param configManager  active microscope config manager (for
     *                       {@code mm_stage_devices} lookup).
     * @param channelLibrary channel id -> {@link Channel}; may be empty for
     *                       angle-only modalities (e.g. PPM).
     * @return parent folder containing the written per-region folders, or
     *         {@code null} on failure (an error alert is shown either way).
     */
    public static Path exportAndConfirm(
            Window parentWindow,
            SampleSetupResult sample,
            AcquisitionCommandBuilder cmdBuilder,
            List<RegionPlan> regions,
            MicroscopeConfigManager configManager,
            Map<String, Channel> channelLibrary) {

        if (regions == null || regions.isEmpty()) {
            Platform.runLater(() -> {
                Alert info = new Alert(Alert.AlertType.INFORMATION);
                info.setTitle("MicroManager MDA Export");
                info.setHeaderText("Nothing to export");
                info.setContentText("Select at least one region and confirm channel/Z settings before exporting MDA.");
                UIFunctions.showAlertOverParent(info, parentWindow);
            });
            return null;
        }

        List<Path> writtenRegionDirs = new ArrayList<>(regions.size());
        Path parentFolder = null;
        try {
            for (RegionPlan rp : regions) {
                if (rp == null || rp.regionDir() == null || rp.regionName() == null) {
                    throw new IOException("RegionPlan with null fields encountered");
                }
                Files.createDirectories(rp.regionDir());
                MdaRequestBuilder.Built built = MdaRequestBuilder.build(
                        rp.regionDir(),
                        rp.regionName(),
                        sample,
                        cmdBuilder,
                        rp.tiles() == null ? List.of() : rp.tiles(),
                        configManager,
                        channelLibrary);
                MdaWriteResult result = MdaSettingsWriter.write(built.request());
                writtenRegionDirs.add(rp.regionDir());
                logger.info(
                        "Wrote MDA export for region '{}' to {} ({} tiles)",
                        rp.regionName(),
                        result.settingsFile(),
                        rp.tiles() == null ? 0 : rp.tiles().size());
                if (parentFolder == null) {
                    parentFolder = rp.regionDir().getParent();
                }
            }
        } catch (IOException | RuntimeException ex) {
            logger.error("MDA export failed: {}", ex.getMessage(), ex);
            final String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            Platform.runLater(() -> {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("MicroManager MDA Export");
                err.setHeaderText("Failed to write MDA files");
                err.setContentText(msg);
                UIFunctions.showAlertOverParent(err, parentWindow);
            });
            return null;
        }

        final Path finalParent = parentFolder;
        final int regionCount = writtenRegionDirs.size();
        final String sampleRegionName = regions.get(0).regionName();
        Platform.runLater(() -> showSuccessAlert(parentWindow, finalParent, regionCount, sampleRegionName));
        return parentFolder;
    }

    private static void showSuccessAlert(
            Window parentWindow, Path parentFolder, int regionCount, String sampleRegionName) {
        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle("MicroManager MDA Export");
        info.setHeaderText(String.format("Wrote %d MicroManager MDA file set(s)", regionCount));
        String body = "Wrote " + regionCount + " MicroManager MDA file set(s) to:\n"
                + (parentFolder != null ? parentFolder.toString() : "(unknown folder)") + "\n\n"
                + "Files per region:\n"
                + "- MDA_<region>.txt  (load in MM: Multi-Dimensional Acquisition -> Load...)\n"
                + "- MDA_<region>.pos  (load in MM: Stage Position List -> Load...)\n"
                + "- MDA_NOTES.txt";
        info.setContentText(body);

        ButtonType openFolderType = new ButtonType("Open Folder", ButtonBar.ButtonData.LEFT);
        // Replace default OK with OK + Open Folder so the left-side Open button is visible.
        info.getButtonTypes().setAll(openFolderType, ButtonType.OK);

        java.util.Optional<ButtonType> picked = UIFunctions.showAlertOverParent(info, parentWindow);
        if (picked.isPresent() && picked.get() == openFolderType && parentFolder != null) {
            try {
                File target = parentFolder.toFile();
                if (!UIFunctions.revealInFileBrowser(target)) {
                    Desktop.getDesktop().open(target);
                }
            } catch (IOException | UnsupportedOperationException | SecurityException ex) {
                logger.warn("Could not open MDA export folder {}: {}", parentFolder, ex.getMessage());
            }
        }
    }
}
