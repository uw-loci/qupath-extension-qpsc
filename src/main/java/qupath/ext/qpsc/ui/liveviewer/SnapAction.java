package qupath.ext.qpsc.ui.liveviewer;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.out.OMETiffWriter;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.PositiveInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.BackgroundValidationResult;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader;
import qupath.ext.qpsc.utilities.BackgroundSettingsReader.BackgroundSettings;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Snap-and-save action for the Live Viewer toolbar.
 *
 * <p>Captures the current live frame and writes it to disk as an OME-TIFF. A
 * right-click context menu on the button exposes:
 * <ul>
 *   <li><b>Save raw bit depth</b> (default ON) -- preserve 16-bit pixel data
 *       without contrast mapping. Uncheck to apply the current display min/max
 *       and save as 8-bit.</li>
 *   <li><b>Apply background correction</b> (default OFF) -- request a corrected
 *       frame from the server via {@code CORRECTFRAME}. The menu item is
 *       greyed out unless the configured background settings (modality,
 *       objective, detector, WB mode, current angle) match the live state --
 *       same compatibility check the ACQUIRE workflow runs in
 *       {@code AcquisitionConfigurationBuilder}.</li>
 *   <li><b>Open file after save</b> (default OFF) -- hand off to
 *       {@link Desktop#open} after a successful save.</li>
 *   <li><b>Reset save folder to project</b> -- clears the per-session "last
 *       used folder" memory.</li>
 * </ul>
 *
 * <p>The file save dialog defaults to the current QuPath project folder; once
 * the user picks a different folder, subsequent snaps in the same JVM session
 * default to that folder until reset or the application exits.
 *
 * <p>Background correction validation reuses
 * {@link BackgroundSettingsReader#findBackgroundSettings(String, String, String, String, String)}
 * and {@link ModalityHandler#validateBackgroundSettings(BackgroundSettings, List, String)} --
 * the same two-step pre-flight ACQUIRE uses, so the SNAP can never apply
 * background correction with mismatched calibration.
 */
public final class SnapAction {

    private static final Logger logger = LoggerFactory.getLogger(SnapAction.class);

    /** Per-session "last used save folder". Resets on JVM exit. */
    private static File lastSaveFolder;

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Lossless OME-TIFF compression. */
    private static final String COMPRESSION = "LZW";

    // Option state -- per-button instance (one Live Viewer at a time).
    private boolean saveRaw = true;
    private boolean applyBgCorrection = false;
    private boolean openAfterSave = false;

    private final Button button;
    private final Supplier<FrameData> frameSupplier;
    private final ContrastSettings contrastSettings;
    private final ExecutorService ioExecutor;
    private final Consumer<String> statusUpdater;

    private SnapAction(
            Supplier<FrameData> frameSupplier,
            ContrastSettings contrastSettings,
            ExecutorService ioExecutor,
            Consumer<String> statusUpdater) {
        this.frameSupplier = frameSupplier;
        this.contrastSettings = contrastSettings;
        this.ioExecutor = ioExecutor;
        this.statusUpdater = statusUpdater;
        this.button = buildButton();
    }

    /**
     * Construct a configured Snap button ready to drop into the Live Viewer
     * toolbar.
     *
     * @param frameSupplier      supplier of the current live frame (typically
     *                           {@code () -> liveViewerWindow.getLastFrame()})
     * @param contrastSettings   live-viewer contrast settings, used for the
     *                           display-mapped save path
     * @param ioExecutor         single-thread executor for off-FX TIFF write
     *                           (the live viewer's existing {@code histogramExecutor}
     *                           works -- BioFormats {@code TiffWriter} must not
     *                           be invoked concurrently)
     * @param hasFreshFrame      property that is true once at least one frame
     *                           has arrived from the server
     * @param liveActive         property that is true while the auxiliary
     *                           socket is delivering frames
     */
    public static Button create(
            Supplier<FrameData> frameSupplier,
            ContrastSettings contrastSettings,
            ExecutorService ioExecutor,
            BooleanProperty hasFreshFrame,
            ReadOnlyBooleanProperty liveActive,
            Consumer<String> statusUpdater) {
        SnapAction action = new SnapAction(frameSupplier, contrastSettings, ioExecutor, statusUpdater);
        action.button.setFocusTraversable(false);
        action.button.disableProperty().bind(hasFreshFrame.not().or(liveActive.not()));
        // Re-bind tooltip text dynamically to reflect "why disabled".
        action.button
                .disableProperty()
                .addListener((ObservableValue<? extends Boolean> obs, Boolean wasDisabled, Boolean nowDisabled) ->
                        action.refreshButtonTooltip(nowDisabled, liveActive.get(), hasFreshFrame.get()));
        action.refreshButtonTooltip(action.button.isDisable(), liveActive.get(), hasFreshFrame.get());
        return action.button;
    }

    // ---------- Button + menu construction ----------

    private Button buildButton() {
        Button b = new Button("Snap");
        b.setOnAction(e -> handleSnap());

        ContextMenu menu = new ContextMenu();

        CheckMenuItem rawItem = new CheckMenuItem("Save raw bit depth");
        rawItem.setSelected(saveRaw);
        rawItem.selectedProperty().addListener((obs, was, now) -> saveRaw = now);
        installItemTooltip(
                rawItem,
                "Save the raw camera bit depth (16-bit when supported). "
                        + "Uncheck to apply the current display contrast and save as 8-bit.");

        CheckMenuItem bgItem = new CheckMenuItem("Apply background correction");
        bgItem.setSelected(applyBgCorrection);
        bgItem.selectedProperty().addListener((obs, was, now) -> applyBgCorrection = now);
        // Default tooltip; replaced on menu open based on current gate state.
        installItemTooltip(bgItem, "Apply flat-field correction using the configured background settings.");

        CheckMenuItem openItem = new CheckMenuItem("Open file after save");
        openItem.setSelected(openAfterSave);
        openItem.selectedProperty().addListener((obs, was, now) -> openAfterSave = now);
        installItemTooltip(openItem, "Open the saved snapshot in the system default viewer after saving.");

        MenuItem resetFolder = new MenuItem("Reset save folder to project");
        resetFolder.setOnAction(e -> {
            lastSaveFolder = null;
            logger.info("Snap save folder reset; next snap will default to the project folder");
        });
        installItemTooltip(
                resetFolder,
                "Forget the last-used save folder; the next snapshot will default to the current project folder.");

        menu.getItems().addAll(rawItem, bgItem, openItem, new SeparatorMenuItem(), resetFolder);

        menu.setOnShowing(e -> {
            BgCorrectionGate gate = evaluateBgCorrection();
            bgItem.setDisable(!gate.usable());
            if (!gate.usable() && bgItem.isSelected()) {
                bgItem.setSelected(false);
                applyBgCorrection = false;
            }
            String tip = gate.usable()
                    ? "Apply flat-field correction using the configured background for the current modality, "
                            + "objective, detector, WB mode, and angle."
                    : "Background correction unavailable: " + gate.reason();
            installItemTooltip(bgItem, tip);
        });

        b.setContextMenu(menu);
        return b;
    }

    private void refreshButtonTooltip(boolean disabled, boolean liveActive, boolean hasFreshFrame) {
        String tip;
        if (disabled) {
            if (!liveActive) {
                tip = "Snap unavailable: Live is OFF. Start Live to capture a frame.";
            } else if (!hasFreshFrame) {
                tip = "Snap unavailable: waiting for the first frame.";
            } else {
                tip = "Snap unavailable.";
            }
        } else {
            tip = "Capture and save the current frame. Right-click for options.";
        }
        Platform.runLater(() -> button.setTooltip(new Tooltip(tip)));
    }

    private static void installItemTooltip(MenuItem item, String text) {
        // JavaFX MenuItem does not have a setTooltip; we attach via the graphic.
        // Falls back to appending hint text to the label if the graphic path is
        // unavailable for any reason.
        try {
            javafx.scene.control.Label label = new javafx.scene.control.Label();
            Tooltip.install(label, new Tooltip(text));
            item.setGraphic(label);
        } catch (Exception e) {
            logger.debug("Falling back to label hint for menu item tooltip: {}", e.getMessage());
            item.setText(item.getText() + " -- " + text);
        }
    }

    // ---------- Click handler ----------

    private void handleSnap() {
        // Capture the current frame source. If BG correction is requested,
        // re-check the gate (defensive against menu-state drift) and fetch a
        // corrected frame; otherwise grab from the local supplier.
        FrameData frame;
        boolean correctionApplied = false;

        if (applyBgCorrection) {
            BgCorrectionGate gate = evaluateBgCorrection();
            if (!gate.usable()) {
                updateStatus("Background correction unavailable: " + gate.reason());
                logger.warn("BG correction requested at click time but gate refused: {}", gate.reason());
                frame = frameSupplier.get();
            } else {
                FrameData corrected = fetchCorrectedFrame();
                if (corrected != null) {
                    frame = corrected;
                    correctionApplied = true;
                } else {
                    updateStatus("Background correction failed; saving uncorrected frame.");
                    frame = frameSupplier.get();
                }
            }
        } else {
            frame = frameSupplier.get();
        }

        if (frame == null) {
            updateStatus("Snap: no frame available.");
            return;
        }

        File defaultFolder = resolveDefaultSaveFolder();
        String filename = suggestFilename();

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Snapshot");
        if (defaultFolder != null && defaultFolder.isDirectory()) {
            chooser.setInitialDirectory(defaultFolder);
        }
        chooser.setInitialFileName(filename);
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter("OME-TIFF (*.tif, *.tiff)", "*.tif", "*.tiff"));

        Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
        File chosen = chooser.showSaveDialog(owner);
        if (chosen == null) {
            return;
        }
        // Ensure .tif extension
        if (!chosen.getName().toLowerCase().endsWith(".tif")
                && !chosen.getName().toLowerCase().endsWith(".tiff")) {
            chosen = new File(chosen.getParentFile(), chosen.getName() + ".tif");
        }
        File destination = chosen;
        lastSaveFolder = destination.getParentFile();

        // Snapshot all option state and stage metadata BEFORE leaving the FX
        // thread -- the live frame poller and the user can keep working while
        // the TIFF write runs in the background.
        final boolean displayMapped = !saveRaw;
        final int dispMin = contrastSettings.getDisplayMin();
        final int dispMax = contrastSettings.getDisplayMax();
        final StageMetadata stage = captureStageMetadata();
        final boolean bgCorrected = correctionApplied;
        final FrameData snapshotFrame = frame;

        ioExecutor.submit(() -> {
            try {
                writeOmeTiff(destination, snapshotFrame, displayMapped, dispMin, dispMax, stage, bgCorrected);
                Platform.runLater(() -> {
                    updateStatus("Saved " + destination.getName());
                    if (openAfterSave) {
                        openFileInOs(destination);
                    }
                });
            } catch (Exception ex) {
                logger.error("Snap save failed: {}", ex.getMessage(), ex);
                Platform.runLater(() -> updateStatus("Snap save failed: " + ex.getMessage()));
            }
        });
    }

    private FrameData fetchCorrectedFrame() {
        try {
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc == null || mc.getSocketClient() == null) {
                return null;
            }
            return mc.getSocketClient().getCorrectedFrame();
        } catch (IOException e) {
            logger.warn("CORRECTFRAME failed: {}", e.getMessage());
            return null;
        }
    }

    // ---------- File helpers ----------

    private static File resolveDefaultSaveFolder() {
        if (lastSaveFolder != null && lastSaveFolder.isDirectory()) {
            return lastSaveFolder;
        }
        try {
            QuPathGUI gui = QuPathGUI.getInstance();
            if (gui != null) {
                Project<?> project = gui.getProject();
                if (project != null && project.getPath() != null) {
                    File path = project.getPath().toFile();
                    File parent = path.isDirectory() ? path : path.getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        return parent;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not resolve project folder for snap default: {}", e.getMessage());
        }
        return new File(System.getProperty("user.home"));
    }

    private static String suggestFilename() {
        String modality = readActiveModality();
        String tag = (modality == null || modality.isEmpty()) ? "snap" : ("snap_" + sanitize(modality));
        return tag + "_" + LocalDateTime.now().format(TIMESTAMP_FMT) + ".tif";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void openFileInOs(File file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
            } else {
                logger.warn("Desktop open not supported on this platform");
            }
        } catch (IOException e) {
            logger.warn("Could not open saved file: {}", e.getMessage());
        }
    }

    // ---------- Background-correction gate ----------

    /**
     * Result of the pre-flight check that decides whether the
     * "Apply background correction" menu item is usable.
     */
    public record BgCorrectionGate(boolean usable, String reason) {}

    /**
     * Mirror the gate ACQUIRE runs: read modality / objective / detector /
     * WB mode / current angle from config + hardware, then ask
     * {@link BackgroundSettingsReader} and the modality handler whether the
     * stored backgrounds match. Returns {@code usable=false} with a
     * human-readable reason on any mismatch.
     */
    public BgCorrectionGate evaluateBgCorrection() {
        try {
            MicroscopeConfigManager cfg = MicroscopeConfigManager.getInstanceIfAvailable();
            if (cfg == null) {
                return new BgCorrectionGate(false, "Microscope configuration not loaded.");
            }
            String modality = readActiveModality();
            if (modality == null || modality.isEmpty()) {
                return new BgCorrectionGate(false, "No active modality set in the microscope configuration.");
            }
            String objective = readString(cfg, "microscope", "objective_in_use");
            String detector = readString(cfg, "microscope", "detector_in_use");
            if (objective == null || objective.isEmpty()) {
                return new BgCorrectionGate(false, "No active objective set (microscope.objective_in_use).");
            }
            if (detector == null || detector.isEmpty()) {
                detector = cfg.getDefaultDetector();
            }
            if (detector == null || detector.isEmpty()) {
                return new BgCorrectionGate(false, "No active detector set.");
            }
            String wbMode = resolveWbMode(cfg, modality);
            String bgBase = cfg.getBackgroundCorrectionFolder(modality);
            if (bgBase == null || bgBase.isEmpty()) {
                return new BgCorrectionGate(false, "No background folder configured for modality '" + modality + "'.");
            }

            BackgroundSettings bg =
                    BackgroundSettingsReader.findBackgroundSettings(bgBase, modality, objective, detector, wbMode);
            if (bg == null) {
                return new BgCorrectionGate(
                        false,
                        "No background_settings.yml matching modality=" + modality + ", objective=" + objective
                                + ", detector=" + detector + ", WB=" + wbMode + ".");
            }

            AngleExposure ae = readCurrentAngleExposure();
            if (ae == null) {
                return new BgCorrectionGate(false, "Could not read current angle/exposure from the microscope.");
            }

            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            BackgroundValidationResult v = handler.validateBackgroundSettings(bg, List.of(ae), wbMode);
            if (v != null && v.anglesWithoutBackground != null && !v.anglesWithoutBackground.isEmpty()) {
                return new BgCorrectionGate(
                        false,
                        "No background image for angle " + ae.ticks() + " (exposure " + ae.exposureMs() + " ms).");
            }
            // Exposure mismatch and WB-mode mismatch are warnings, not blockers --
            // ACQUIRE applies flat-field correction in both cases.
            return new BgCorrectionGate(true, "");
        } catch (Exception e) {
            logger.debug("BG correction gate threw: {}", e.getMessage());
            return new BgCorrectionGate(false, "Could not evaluate background settings (" + e.getMessage() + ").");
        }
    }

    private static String resolveWbMode(MicroscopeConfigManager cfg, String modality) {
        // Mirror what BackgroundCollectionController / the acquisition dialog
        // do: prefer the modality default, fall back to "off" (monochrome).
        try {
            ModalityHandler handler = ModalityRegistry.getHandler(modality);
            String mode = handler == null ? null : handler.getDefaultWbMode();
            if (mode != null && !mode.isEmpty()) {
                return mode;
            }
        } catch (Exception e) {
            logger.debug("Could not resolve modality default WB mode: {}", e.getMessage());
        }
        return "off";
    }

    private static AngleExposure readCurrentAngleExposure() {
        try {
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc == null) return null;
            double angle = 0.0;
            try {
                if (mc.hasRotationStage()) {
                    angle = mc.getStagePositionR();
                }
            } catch (Exception e) {
                logger.debug("Rotation angle query failed; defaulting to 0: {}", e.getMessage());
            }
            double exposureMs = 0.0;
            try {
                MicroscopeSocketClient.ExposuresResult result =
                        mc.getSocketClient().getExposures();
                if (result != null) {
                    // Both unified mode and per-channel mode populate the
                    // "unified" field with the all-channels value the server
                    // reports first, so reading unified is correct for both.
                    exposureMs = result.unified();
                }
            } catch (Exception e) {
                logger.debug("Exposure query failed: {}", e.getMessage());
            }
            if (exposureMs <= 0) {
                return null;
            }
            return new AngleExposure(angle, exposureMs);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readActiveModality() {
        try {
            MicroscopeConfigManager cfg = MicroscopeConfigManager.getInstanceIfAvailable();
            if (cfg == null) return null;
            String m = readString(cfg, "microscope", "modality_in_use");
            if (m != null && !m.isEmpty()) return m;
            // Fallback: look for "default_modality" or similar
            m = readString(cfg, "microscope", "default_modality");
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String readString(MicroscopeConfigManager cfg, String... path) {
        try {
            Map<String, Object> all = cfg.getAllConfig();
            Object node = all;
            for (String key : path) {
                if (!(node instanceof Map)) return null;
                node = ((Map<String, Object>) node).get(key);
                if (node == null) return null;
            }
            return node.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Stage / metadata capture ----------

    private record StageMetadata(
            Double stageX,
            Double stageY,
            Double stageZ,
            Double pixelSizeUm,
            String modality,
            String objective,
            String detector) {}

    private static StageMetadata captureStageMetadata() {
        Double x = null, y = null, z = null, pixelSize = null;
        String modality = readActiveModality();
        String objective = null, detector = null;
        try {
            MicroscopeController mc = MicroscopeController.getInstance();
            if (mc != null) {
                try {
                    double[] xy = mc.getStagePositionXY();
                    if (xy != null && xy.length >= 2) {
                        x = xy[0];
                        y = xy[1];
                    }
                } catch (Exception e) {
                    logger.debug("Stage XY query failed: {}", e.getMessage());
                }
                try {
                    z = mc.getStagePositionZ();
                } catch (Exception e) {
                    logger.debug("Stage Z query failed: {}", e.getMessage());
                }
            }
            MicroscopeConfigManager cfg = MicroscopeConfigManager.getInstanceIfAvailable();
            if (cfg != null) {
                objective = readString(cfg, "microscope", "objective_in_use");
                detector = readString(cfg, "microscope", "detector_in_use");
                if (detector == null || detector.isEmpty()) {
                    detector = cfg.getDefaultDetector();
                }
                if (objective != null && detector != null) {
                    double p = cfg.getPixelSize(objective, detector);
                    if (p > 0) pixelSize = p;
                }
            }
        } catch (Exception e) {
            logger.debug("Stage metadata capture failed: {}", e.getMessage());
        }
        return new StageMetadata(x, y, z, pixelSize, modality, objective, detector);
    }

    // ---------- Pixel conversion + OME-TIFF write ----------

    /**
     * Build the pixel byte array that goes into the TIFF, applying optional
     * display contrast mapping, and return the matching BioFormats pixel type.
     */
    private static byte[] toPixelBytes(
            FrameData frame, boolean displayMapped, int dispMin, int dispMax, int[] outPixelType) {
        int w = frame.width();
        int h = frame.height();
        int c = frame.channels();
        int bpp = frame.bytesPerPixel();
        byte[] src = frame.rawPixels();

        if (displayMapped) {
            // Map to 8-bit using the current display min/max, output 8-bit.
            int range = Math.max(1, dispMax - dispMin);
            byte[] dst = new byte[w * h * c];
            if (bpp == 1) {
                for (int i = 0; i < src.length; i++) {
                    int v = src[i] & 0xFF;
                    dst[i] = (byte) clamp((v - dispMin) * 255 / range, 0, 255);
                }
            } else {
                // uint16 big-endian on wire
                for (int i = 0, j = 0; i < w * h * c; i++, j += 2) {
                    int v = ((src[j] & 0xFF) << 8) | (src[j + 1] & 0xFF);
                    dst[i] = (byte) clamp((v - dispMin) * 255 / range, 0, 255);
                }
            }
            outPixelType[0] = FormatTools.UINT8;
            return dst;
        }

        if (bpp == 1) {
            outPixelType[0] = FormatTools.UINT8;
            // Defensive copy so the live poller can swap lastFrame without
            // mutating bytes mid-write.
            byte[] dst = new byte[src.length];
            System.arraycopy(src, 0, dst, 0, src.length);
            return dst;
        }
        // 16-bit RAW: wire is big-endian. BioFormats accepts either endianness
        // when we set it in the metadata; we keep the wire order and tell the
        // writer the data is big-endian.
        byte[] dst = new byte[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        outPixelType[0] = FormatTools.UINT16;
        return dst;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void writeOmeTiff(
            File file,
            FrameData frame,
            boolean displayMapped,
            int dispMin,
            int dispMax,
            StageMetadata stage,
            boolean bgCorrected)
            throws Exception {

        int[] pixelTypeRef = new int[1];
        byte[] pixelBytes = toPixelBytes(frame, displayMapped, dispMin, dispMax, pixelTypeRef);
        int pixelType = pixelTypeRef[0];

        int w = frame.width();
        int h = frame.height();
        int c = frame.channels();
        boolean wireBigEndian = !displayMapped && frame.bytesPerPixel() == 2;
        boolean interleaved = c > 1;

        // Build OME-XML metadata. We use the low-level setters because the
        // populateMetadata helpers in different BioFormats versions take
        // different argument lists.
        IMetadata meta = MetadataTools.createOMEXMLMetadata();
        meta.createRoot();
        meta.setImageID("Image:0", 0);
        meta.setPixelsID("Pixels:0", 0);
        meta.setPixelsDimensionOrder(DimensionOrder.XYCZT, 0);
        meta.setPixelsType(PixelType.fromString(FormatTools.getPixelTypeString(pixelType)), 0);
        meta.setPixelsBigEndian(wireBigEndian, 0);
        meta.setPixelsInterleaved(interleaved, 0);
        meta.setPixelsSizeX(new PositiveInteger(w), 0);
        meta.setPixelsSizeY(new PositiveInteger(h), 0);
        meta.setPixelsSizeC(new PositiveInteger(c), 0);
        meta.setPixelsSizeZ(new PositiveInteger(1), 0);
        meta.setPixelsSizeT(new PositiveInteger(1), 0);

        if (c == 1) {
            meta.setChannelID("Channel:0:0", 0, 0);
            meta.setChannelSamplesPerPixel(new PositiveInteger(1), 0, 0);
        } else {
            // Single Channel describing N interleaved samples is the
            // OME-TIFF idiom for RGB.
            meta.setChannelID("Channel:0:0", 0, 0);
            meta.setChannelSamplesPerPixel(new PositiveInteger(c), 0, 0);
        }

        // Stage XY/Z and pixel size as OME-XML metadata.
        if (stage.pixelSizeUm() != null && stage.pixelSizeUm() > 0) {
            meta.setPixelsPhysicalSizeX(new Length(stage.pixelSizeUm(), UNITS.MICROMETER), 0);
            meta.setPixelsPhysicalSizeY(new Length(stage.pixelSizeUm(), UNITS.MICROMETER), 0);
        }
        meta.setPlaneTheC(new ome.xml.model.primitives.NonNegativeInteger(0), 0, 0);
        meta.setPlaneTheZ(new ome.xml.model.primitives.NonNegativeInteger(0), 0, 0);
        meta.setPlaneTheT(new ome.xml.model.primitives.NonNegativeInteger(0), 0, 0);
        if (stage.stageX() != null) {
            meta.setPlanePositionX(new Length(stage.stageX(), UNITS.MICROMETER), 0, 0);
        }
        if (stage.stageY() != null) {
            meta.setPlanePositionY(new Length(stage.stageY(), UNITS.MICROMETER), 0, 0);
        }
        if (stage.stageZ() != null) {
            meta.setPlanePositionZ(new Length(stage.stageZ(), UNITS.MICROMETER), 0, 0);
        }

        StringBuilder desc = new StringBuilder("QPSC Live Viewer snapshot");
        if (stage.modality() != null) desc.append("; modality=").append(stage.modality());
        if (stage.objective() != null) desc.append("; objective=").append(stage.objective());
        if (stage.detector() != null) desc.append("; detector=").append(stage.detector());
        desc.append("; raw=").append(!displayMapped);
        desc.append("; bgCorrected=").append(bgCorrected);
        meta.setImageDescription(desc.toString(), 0);

        // Delete existing file -- BioFormats writers refuse to overwrite.
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not overwrite existing file: " + file);
        }

        try (OMETiffWriter writer = new OMETiffWriter()) {
            writer.setMetadataRetrieve(meta);
            writer.setBigTiff(false);
            writer.setInterleaved(interleaved);
            try {
                writer.setCompression(COMPRESSION);
            } catch (Exception e) {
                logger.debug("LZW compression not available; falling back to uncompressed: {}", e.getMessage());
            }
            writer.setId(file.getAbsolutePath());
            writer.saveBytes(0, pixelBytes);
        }
        logger.info(
                "Snap saved: {} ({}x{}, {} ch, {}-bit{})",
                file.getAbsolutePath(),
                w,
                h,
                c,
                pixelType == FormatTools.UINT16 ? 16 : 8,
                bgCorrected ? ", BG corrected" : "");
    }

    // ---------- Status helpers ----------

    private void updateStatus(String text) {
        if (statusUpdater != null) {
            try {
                statusUpdater.accept(text);
                return;
            } catch (Exception e) {
                logger.debug("Status updater threw: {}", e.getMessage());
            }
        }
        logger.info("[snap] {}", text);
    }
}
