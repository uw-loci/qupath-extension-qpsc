package qupath.ext.qpsc.ui.liveviewer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Singleton floating window that displays a live camera feed from the microscope.
 * <p>
 * Uses MM's continuous acquisition mode (live mode) and reads frames from the
 * circular buffer via the GETFRAME socket command. Frames are rendered with
 * adjustable contrast and a throttled luminance histogram.
 * <p>
 * Thread model:
 * <ul>
 *   <li>Frame poller (ScheduledExecutorService): polls GETFRAME at ~10 FPS</li>
 *   <li>Histogram computer (ExecutorService): computes histogram, throttled to ~5 Hz</li>
 *   <li>FX Application Thread: renders frames, updates UI</li>
 * </ul>
 */
public class LiveViewerWindow {

    private static final Logger logger = LoggerFactory.getLogger(LiveViewerWindow.class);

    // Singleton
    private static LiveViewerWindow instance;

    // Window components
    private Stage stage;
    private ImageView imageView;
    private Label statusLabel;
    private Label cursorLabel;
    private Button liveToggleButton;
    private Button refineFocusButton;
    private Button sweepFocusButton;
    private Button smoothFocusButton;
    private SmoothFocusController smoothFocusController;
    private ComboBox<String> focusRangeCombo;
    private HistogramView histogramView;
    private TitledPane histogramPane;
    private NoiseStatsPanel noiseStatsPanel;
    private StageControlPanel stageControlPanel;
    private ScrollPane stageScrollPane;
    private ToggleButton stageControlToggle;
    private final ContrastSettings contrastSettings = new ContrastSettings();

    // Focus controllers
    private RefineFocusController refineFocusController;
    private SweepFocusController sweepFocusController;

    // Live mode state (camera streaming on/off, independent of window visibility)
    private volatile boolean liveActive = false;

    // Frame rendering state
    private WritableImage writableImage;
    private int[] argbBuffer;
    private int lastFrameWidth = 0;
    private int lastFrameHeight = 0;

    // Source image dimensions for double-click-to-center coordinate conversion
    private volatile int sourceImageWidth = 0;
    private volatile int sourceImageHeight = 0;

    // Latest frame for cursor pixel readout (works even when not streaming)
    private volatile FrameData lastFrame;

    // Thread pools
    private ScheduledExecutorService framePoller;
    private ExecutorService histogramExecutor;

    // FPS tracking
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final AtomicLong fpsWindowStart = new AtomicLong(System.currentTimeMillis());
    private volatile double currentFps = 0;

    // State
    private volatile boolean polling = false;
    private volatile String lastFrameInfo = "";

    // Status message hold: important messages (e.g. refine focus result) are held
    // for a few seconds before the frame-rate ticker overwrites them.
    private volatile long statusHoldUntil = 0;
    private static final long STATUS_HOLD_MS = 5000;

    // Desync detection and auto-recovery
    private volatile long lastFrameArrivalTime = 0;
    private volatile long liveOnTimestamp = 0;
    private static final long NO_FRAME_TIMEOUT_MS = 10_000; // 10s before considering desync
    private static final long GRACE_PERIOD_MS = 10_000; // 10s grace after turning ON
    private static final int MAX_RECOVERY_ATTEMPTS = 2;
    private volatile int recoveryAttempts = 0;
    private volatile boolean recoveryInProgress = false;

    // Display mode state
    // fitToContainer = true: image scales to fill container (no scrollbars)
    // fitToContainer = false: image renders at explicitScale, scrollbars appear as needed
    private volatile boolean fitToContainer = true;
    private volatile double explicitScale = 1.0;
    private ScrollPane scrollPane; // Store reference for mode switching
    private StackPane imageContainer; // Inner container for centering

    // Tile viewing during acquisition
    private CheckBox showTilesCheckBox;
    private volatile boolean showTilesEnabled = false;
    private static final long TILE_DISPLAY_THROTTLE_MS = 8000; // At most once per 8 seconds
    private volatile long lastTileDisplayTime = 0;

    // Root content pane
    private BorderPane expandedContent;

    // Lock manager for disabling controls during operations
    private LiveViewerLockManager lockManager;

    // Configuration
    private static final long POLL_INTERVAL_MS = 100; // ~10 FPS max
    private static final double WINDOW_WIDTH = 900; // Wider to accommodate side panel
    private static final double WINDOW_HEIGHT = 720;

    private LiveViewerWindow() {
        buildUI();
        lockManager = new LiveViewerLockManager(this::applyLock, this::releaseLock);
    }

    /**
     * Gets the lock manager for disabling Live Viewer controls during operations.
     *
     * @return The lock manager instance
     */
    public LiveViewerLockManager getLockManager() {
        return lockManager;
    }

    /** Disable interactive controls (called on FX thread by lock manager). */
    private void applyLock() {
        String reason = lockManager.getLockHolder();
        liveToggleButton.setDisable(true);
        refineFocusButton.setDisable(true);
        sweepFocusButton.setDisable(true);
        if (smoothFocusButton != null) smoothFocusButton.setDisable(true);
        if (stageControlToggle != null) stageControlToggle.setDisable(true);
        if (stageControlPanel != null) stageControlPanel.setDisable(true);
        updateStatus("LOCKED: " + (reason != null ? reason : "operation in progress"));
    }

    /** Re-enable interactive controls (called on FX thread by lock manager). */
    private void releaseLock() {
        liveToggleButton.setDisable(false);
        // Refine/sweep depend on live state
        updateRefineFocusButtonState();
        if (stageControlToggle != null) stageControlToggle.setDisable(false);
        if (stageControlPanel != null) stageControlPanel.setDisable(false);
        updateStatus(liveActive ? "Live ON" : "Live OFF");
    }

    /**
     * Acquires the Live Viewer lock, disabling interactive controls.
     * Safe to call even if the Live Viewer has not been created yet.
     *
     * @param reason Human-readable reason (shown in status bar)
     * @return true if lock was acquired
     */
    public static boolean lockControls(String reason) {
        if (instance != null && instance.lockManager != null) {
            return instance.lockManager.acquire(reason);
        }
        return false;
    }

    /**
     * Releases the Live Viewer lock, re-enabling interactive controls.
     * Safe to call even if the Live Viewer has not been created yet.
     */
    public static void unlockControls() {
        if (instance != null && instance.lockManager != null) {
            instance.lockManager.release();
        }
    }

    /**
     * Checks if the Live Viewer controls are currently locked.
     *
     * @return true if locked
     */
    public static boolean isLocked() {
        return instance != null && instance.lockManager != null && instance.lockManager.isLocked();
    }

    /**
     * Refreshes the camera preset buttons in the Stage Control panel.
     * Call after white balance calibration updates the imageprocessing YAML.
     * Safe to call from any thread; runs on FX thread.
     */
    public static void refreshCameraPresets() {
        if (instance != null && instance.stageControlPanel != null) {
            Platform.runLater(() -> instance.stageControlPanel.refreshCameraPresets());
        }
    }

    /**
     * Shows the Live Viewer window, creating it if necessary.
     * Starts live mode and begins frame polling.
     */
    public static void show() {
        Platform.runLater(() -> {
            if (instance == null) {
                instance = new LiveViewerWindow();
            }

            if (!instance.stage.isShowing()) {
                instance.stage.show();
                instance.startLiveViewing();
            } else {
                instance.stage.toFront();
            }
        });
    }

    /**
     * Hides the Live Viewer window and stops live mode.
     */
    public static void hide() {
        Platform.runLater(() -> {
            if (instance != null) {
                instance.stopAndDispose();
            }
        });
    }

    /**
     * Checks if the Live Viewer window is currently visible.
     */
    public static boolean isVisible() {
        return instance != null && instance.stage != null && instance.stage.isShowing();
    }

    /**
     * Checks whether the QPSC Live Viewer is actively streaming (continuous acquisition).
     * Thread-safe -- can be called from any thread.
     *
     * @return true if the Live Viewer exists and its continuous acquisition is active
     */
    public static boolean isStreamingActive() {
        return instance != null && instance.liveActive;
    }

    /**
     * Synchronously stops continuous acquisition without hiding the window.
     * <p>
     * This is intended for short-lived camera operations (e.g., property changes)
     * that need exclusive hardware access. The window stays open and a UI button
     * update is scheduled via {@code Platform.runLater()} (cosmetic only).
     * <p>
     * Thread-safe -- can be called from any thread including the FX thread.
     *
     * @return true if streaming was active and was stopped, false if it was already off
     */
    public static boolean stopStreaming() {
        if (instance == null || !instance.liveActive) {
            return false;
        }
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller != null) {
                controller.stopContinuousAcquisition();
            }
        } catch (IOException e) {
            logger.warn("Failed to stop QPSC Live Viewer streaming: {}", e.getMessage());
        }
        instance.liveActive = false;
        instance.lastFrameArrivalTime = 0;
        // Cosmetic UI update -- non-blocking
        Platform.runLater(() -> {
            if (instance != null) {
                instance.updateLiveButtonStyle(false);
                instance.updateRefineFocusButtonState();
                instance.updateStatus("Live OFF (paused for camera operation)");
            }
        });
        logger.info("QPSC Live Viewer streaming stopped (window stays open)");
        return true;
    }

    /**
     * Re-starts continuous acquisition after a prior {@link #stopStreaming()} call.
     * <p>
     * Thread-safe -- can be called from any thread. Schedules a cosmetic UI update
     * via {@code Platform.runLater()}.
     */
    public static void restartStreaming() {
        if (instance == null) {
            return;
        }
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller != null) {
                // Force a clean restart: stop any stale sequence, then start fresh.
                // This handles cases where the camera mode was changed during WB/BG
                // and the old sequence is no longer producing valid frames.
                try {
                    controller.stopContinuousAcquisition();
                    Thread.sleep(200); // Let camera fully release
                } catch (Exception e) {
                    logger.debug("Stop before restart: {}", e.getMessage());
                }
                controller.startContinuousAcquisition();
                instance.liveActive = true;
                // Reset FPS counter, desync tracking, and recovery state
                instance.frameCount.set(0);
                instance.fpsWindowStart.set(System.currentTimeMillis());
                instance.currentFps = 0;
                instance.liveOnTimestamp = System.currentTimeMillis();
                instance.lastFrameArrivalTime = System.currentTimeMillis();
                instance.recoveryAttempts = 0;
                instance.recoveryInProgress = false;
                // Cosmetic UI update
                Platform.runLater(() -> {
                    if (instance != null) {
                        instance.updateLiveButtonStyle(true);
                        instance.updateRefineFocusButtonState();
                        instance.updateStatus("Live ON - streaming...");
                    }
                });
                logger.info("QPSC Live Viewer streaming restarted");
            }
        } catch (IOException e) {
            logger.warn("Failed to restart QPSC Live Viewer streaming: {}", e.getMessage());
        }
    }

    /**
     * Returns true if the "Show Tiles" checkbox is checked and the viewer is open.
     * Called by AcquisitionManager to decide whether to load and display tiles.
     */
    public static boolean isShowTilesEnabled() {
        return instance != null && instance.showTilesEnabled;
    }

    /**
     * Display an acquired tile image in the Live Viewer.
     * Reads the TIFF from disk on a background thread, converts to FrameData,
     * and renders via the existing renderFrame pipeline.
     * <p>
     * Throttled to at most once per TILE_DISPLAY_THROTTLE_MS to avoid I/O overhead.
     * Thread-safe -- can be called from any thread.
     *
     * @param tilePath Absolute path to a TIFF tile file
     */
    public static void showAcquiredTile(String tilePath) {
        if (instance == null || !instance.showTilesEnabled) return;

        long now = System.currentTimeMillis();
        if (now - instance.lastTileDisplayTime < TILE_DISPLAY_THROTTLE_MS) return;
        instance.lastTileDisplayTime = now;

        // Read TIFF on a background thread to avoid blocking the caller
        ExecutorService histExec = instance.histogramExecutor;
        if (histExec == null || histExec.isShutdown()) return;

        histExec.submit(() -> {
            try {
                File tileFile = new File(tilePath);
                if (!tileFile.exists()) {
                    logger.debug("Tile file not found: {}", tilePath);
                    return;
                }

                BufferedImage img = loadTileImage(tileFile);
                if (img == null) {
                    logger.warn("Failed to read tile image (ImageIO + BioFormats both returned null): {}", tilePath);
                    return;
                }

                // Convert the BufferedImage to a FrameData in the SAME FORMAT
                // the live feed uses for this camera -- this way the user's
                // current contrast slider settings apply naturally to the tile
                // preview. If we gave the renderer an 8-bit RGB frame while
                // contrast was set to a 16-bit range (e.g. min=5000, max=30000
                // for Hamamatsu), every pixel in 0..255 would be clipped to
                // black and the Live Viewer would "never change" even though
                // renderFrame was being called correctly.
                //
                // 16-bit grayscale (Hamamatsu sCMOS): raw samples in big-endian
                //   2-byte order, channels=1, bpp=2 -- matches renderFrame's
                //   grayscale branch at line ~1195.
                //
                // 8-bit RGB (JAI 3-CCD): interleaved RGB bytes, channels=3,
                //   bpp=1 -- matches renderFrame's RGB branch at line ~1199.
                FrameData frame = bufferedImageToFrameData(img);
                if (frame == null) {
                    logger.debug("Unsupported tile image format for {}: type={}, bands={}",
                            tileFile.getName(), img.getType(),
                            img.getRaster().getNumBands());
                    return;
                }

                String fileName = tileFile.getName();
                Platform.runLater(() -> {
                    if (instance != null) {
                        instance.renderFrame(frame);
                        instance.updateStatus("Tile: " + fileName);
                    }
                });

            } catch (Exception e) {
                logger.debug("Error displaying acquired tile: {}", e.getMessage());
            }
        });
    }

    /**
     * Convert a BufferedImage to a FrameData in the native format of the
     * source pixels, so the live-mode contrast settings apply correctly.
     *
     * <ul>
     *   <li>16-bit grayscale -> channels=1, bpp=2, big-endian sample bytes</li>
     *   <li>8-bit grayscale  -> channels=1, bpp=1</li>
     *   <li>8-bit RGB        -> channels=3, bpp=1, interleaved R,G,B</li>
     * </ul>
     *
     * Returns null for unsupported formats.
     */
    private static FrameData bufferedImageToFrameData(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        java.awt.image.Raster raster = img.getRaster();
        int transferType = raster.getTransferType();
        int numBands = raster.getNumBands();

        // Single-band (grayscale)
        if (numBands == 1) {
            int[] samples = new int[w * h];
            raster.getSamples(0, 0, w, h, 0, samples);

            if (transferType == java.awt.image.DataBuffer.TYPE_USHORT) {
                // 16-bit grayscale: big-endian 2 bytes per sample, matches
                // renderFrame's grayscale-16 branch.
                byte[] bytes = new byte[w * h * 2];
                for (int i = 0; i < samples.length; i++) {
                    int v = samples[i] & 0xFFFF;
                    bytes[i * 2] = (byte) ((v >> 8) & 0xFF);     // high byte first
                    bytes[i * 2 + 1] = (byte) (v & 0xFF);
                }
                return new FrameData(w, h, 1, 2, bytes, System.currentTimeMillis());
            } else {
                // 8-bit grayscale
                byte[] bytes = new byte[w * h];
                for (int i = 0; i < samples.length; i++) {
                    int v = samples[i];
                    if (v < 0) v = 0;
                    else if (v > 255) v = 255;
                    bytes[i] = (byte) v;
                }
                return new FrameData(w, h, 1, 1, bytes, System.currentTimeMillis());
            }
        }

        // Three-band (RGB). Use getRGB to normalize arbitrary ColorModels
        // (TYPE_INT_RGB, TYPE_3BYTE_BGR, TYPE_INT_ARGB, etc.) into a plain
        // interleaved 8-bit RGB byte stream.
        if (numBands >= 3) {
            byte[] rgb = new byte[w * h * 3];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pixel = img.getRGB(x, y);
                    int idx = (y * w + x) * 3;
                    rgb[idx] = (byte) ((pixel >> 16) & 0xFF); // R
                    rgb[idx + 1] = (byte) ((pixel >> 8) & 0xFF); // G
                    rgb[idx + 2] = (byte) (pixel & 0xFF); // B
                }
            }
            return new FrameData(w, h, 3, 1, rgb, System.currentTimeMillis());
        }

        return null;
    }

    /**
     * Scan the given tile directory for the most recently modified TIFF
     * and display it in the Live Viewer via {@link #showAcquiredTile(String)}.
     * <p>
     * Handles both layouts:
     * <ul>
     *   <li>Non-rotation modalities (brightfield, fluorescence): tiles live
     *       directly in {@code tileDirPath}.</li>
     *   <li>Rotation modalities (PPM): tiles live in angle subdirectories
     *       like {@code 90/}, {@code 7/}, {@code -7/} under {@code tileDirPath}.</li>
     * </ul>
     * Both layouts are scanned in one pass; the newest modification time wins.
     * <p>
     * Safe no-op if Show Tiles is disabled or the Live Viewer is not open --
     * {@link #showAcquiredTile(String)} handles those early-exit cases.
     *
     * @param tileDirPath the annotation-level tile directory
     *                    (e.g. {@code .../Brightfield_10x_7/bounds})
     */
    public static void scanAndShowLatestTile(String tileDirPath) {
        try {
            java.nio.file.Path tileDir = java.nio.file.Paths.get(tileDirPath);
            if (!java.nio.file.Files.exists(tileDir)) return;

            File bestFile = null;
            long bestTime = 0;

            File rootDir = tileDir.toFile();

            // Non-rotation layout: tiles directly in tileDirPath
            File[] rootTiffs = rootDir.listFiles(f -> f.isFile() && f.getName().endsWith(".tif"));
            if (rootTiffs != null) {
                for (File tif : rootTiffs) {
                    long mod = tif.lastModified();
                    if (mod > bestTime) {
                        bestTime = mod;
                        bestFile = tif;
                    }
                }
            }

            // Rotation layout: tiles in angle subdirectories (90/, 7/, -7/, 0/).
            // Only scan dirs whose name parses as a number -- this excludes
            // post-processing output directories like 7.0.biref/ and 7.0.sum/
            // whose biref TIFFs are computed last (newest timestamp) but are
            // 16-bit grayscale and render poorly with the 8-bit RGB contrast
            // settings of the Live Viewer.
            File[] subdirs = rootDir.listFiles(f -> f.isDirectory() && isAngleDirectory(f.getName()));
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    File[] tiffs = subdir.listFiles(f -> f.isFile() && f.getName().endsWith(".tif"));
                    if (tiffs == null) continue;
                    for (File tif : tiffs) {
                        long mod = tif.lastModified();
                        if (mod > bestTime) {
                            bestTime = mod;
                            bestFile = tif;
                        }
                    }
                }
            }

            if (bestFile != null) {
                showAcquiredTile(bestFile.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.debug("scanAndShowLatestTile failed for {}: {}", tileDirPath, e.getMessage());
        }
    }

    /**
     * Returns true if the directory name looks like an angle value (e.g. "90",
     * "7.0", "-7.0"). Post-processing directories like "7.0.biref" or "7.0.sum"
     * fail to parse and are excluded.
     */
    private static boolean isAngleDirectory(String name) {
        try {
            Double.parseDouble(name);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Load a tile image into a BufferedImage, trying multiple readers.
     * <p>
     * Order:
     * <ol>
     *   <li>Standard {@code ImageIO.read()} -- fast, works for 8-bit RGB TIFFs
     *       (e.g. JAI 3-CCD PPM output).</li>
     *   <li>BioFormats {@code BufferedImageReader} fallback -- required for
     *       16-bit monochrome OME-TIFFs (e.g. Hamamatsu sCMOS brightfield
     *       output), which the stock JRE ImageIO cannot decode.</li>
     * </ol>
     * Returns {@code null} only if both paths fail.
     */
    private static BufferedImage loadTileImage(File tileFile) {
        // Fast path: standard ImageIO
        try {
            BufferedImage img = ImageIO.read(tileFile);
            if (img != null) {
                return img;
            }
        } catch (IOException e) {
            logger.debug("ImageIO could not read {}: {}", tileFile.getName(), e.getMessage());
        }

        // Fallback: BioFormats (handles 16-bit mono, OME-TIFF, and anything else
        // the stock ImageIO decoder chokes on). Provided at runtime by QuPath's
        // bioformats extension (build.gradle.kts compileOnly dependency).
        try (loci.formats.gui.BufferedImageReader reader =
                     new loci.formats.gui.BufferedImageReader(new loci.formats.ImageReader())) {
            reader.setId(tileFile.getAbsolutePath());
            return reader.openImage(0);
        } catch (Throwable t) {
            // Catch Throwable so a missing BioFormats at runtime downgrades to a
            // warning instead of crashing the preview thread.
            logger.debug("BioFormats could not read {}: {}", tileFile.getName(), t.getMessage());
            return null;
        }
    }

    private void buildUI() {
        stage = new Stage();
        stage.setTitle("Live Viewer");
        stage.initModality(Modality.NONE);

        // Set QuPath as owner so the Live Viewer stays above QuPath
        // but does not float above all other Windows applications.
        QuPathGUI qupath = QuPathGUI.getInstance();
        if (qupath != null && qupath.getStage() != null) {
            stage.initOwner(qupath.getStage());
        }

        // Toolbar with Live toggle button and display scale selector
        liveToggleButton = new Button("Live: OFF");
        liveToggleButton.setStyle("-fx-font-weight: bold;");
        updateLiveButtonStyle(false);
        liveToggleButton.setOnAction(e -> toggleLiveMode());

        // Refine focus button and search range selector
        refineFocusButton = new Button("Refine Focus");
        refineFocusButton.setTooltip(new Tooltip(
                "Automatically refine Z focus using histogram contrast. " + "Best used when already close to focus."));
        refineFocusButton.setDisable(true); // disabled until live is ON
        refineFocusButton.setOnAction(e -> handleRefineFocus());

        sweepFocusButton = new Button("Sweep Focus");
        sweepFocusButton.setTooltip(new Tooltip("Stepped-Z autofocus. Moves Z point by point and "
                + "snaps at each step to find the best focus. Slower but works on any camera."));
        sweepFocusButton.setDisable(true);
        sweepFocusButton.setOnAction(e -> handleSweepFocus());

        smoothFocusButton = new Button("Smooth Focus");
        smoothFocusButton.setTooltip(new Tooltip(
                "Streaming-based continuous-Z autofocus.\n"
                + "Reads sweep_range_um per objective from autofocus_*.yml.\n"
                + "Automatically refuses and falls back to stepped Sweep Focus if:\n"
                + "  - Exposure is too long (motion blur exceeds DOF budget)\n"
                + "  - Image is > 5% saturated (metric unreliable)\n"
                + "  - Stage has no speed-control property\n"
                + "Typical scan time: ~1 second on PPM."));
        smoothFocusButton.setDisable(true);
        smoothFocusButton.setOnAction(e -> handleSmoothFocus());

        focusRangeCombo = new ComboBox<>();
        focusRangeCombo.getItems().addAll("Auto", "1um", "2um", "5um", "10um", "20um");
        focusRangeCombo.setValue("Auto");
        focusRangeCombo.setPrefWidth(70);
        focusRangeCombo.setTooltip(new Tooltip("Search range for Refine Focus.\n"
                + "Auto: determined from objective magnification.\n"
                + "Smaller = faster but must be closer to focus.\n"
                + "Larger = searches wider but takes longer."));
        focusRangeCombo.setDisable(true);

        // Display scale selector
        Label scaleLabel = new Label("Display:");
        ComboBox<String> scaleCombo = new ComboBox<>();
        scaleCombo.getItems().addAll("Fit", "25%", "50%", "100%");
        scaleCombo.setValue("Fit"); // Default to Fit mode
        scaleCombo.setPrefWidth(75);
        scaleCombo.setOnAction(e -> {
            String selected = scaleCombo.getValue();
            fitToContainer = "Fit".equals(selected);

            if (fitToContainer) {
                // Fit mode: image scales to fill container, hide scrollbars
                scrollPane.setFitToWidth(true);
                scrollPane.setFitToHeight(true);
                imageView.fitWidthProperty().bind(scrollPane.widthProperty().subtract(2));
                imageView.fitHeightProperty().bind(scrollPane.heightProperty().subtract(2));
            } else {
                // Explicit scale mode: unbind, show scrollbars as needed
                scrollPane.setFitToWidth(false);
                scrollPane.setFitToHeight(false);
                imageView.fitWidthProperty().unbind();
                imageView.fitHeightProperty().unbind();
                imageView.setFitWidth(0);
                imageView.setFitHeight(0);

                switch (selected) {
                    case "25%" -> explicitScale = 0.25;
                    case "50%" -> explicitScale = 0.5;
                    case "100%" -> explicitScale = 1.0;
                }
            }

            // Force frame redraw with new settings
            lastFrameWidth = 0;
            lastFrameHeight = 0;
            logger.info("Display mode changed to {}", selected);
        });

        // Show acquired tiles checkbox
        showTilesCheckBox = new CheckBox("Show Tiles");
        showTilesCheckBox.setTooltip(new Tooltip("During acquisition, display each acquired tile in this viewer.\n"
                + "Useful for checking focus drift without waiting for stitching.\n"
                + "Updates at most once every 8 seconds to avoid I/O overhead."));
        showTilesCheckBox.setSelected(PersistentPreferences.getShowTilesDuringAcquisition());
        showTilesEnabled = showTilesCheckBox.isSelected();
        showTilesCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            showTilesEnabled = newVal;
            PersistentPreferences.setShowTilesDuringAcquisition(newVal);
            logger.info("Show tiles during acquisition: {}", newVal);
        });

        // Stage control toggle button
        stageControlToggle = new ToggleButton("Stage Control");
        stageControlToggle.setSelected(true);
        stageControlToggle.setStyle("-fx-font-size: 11px;");
        stageControlToggle.setTooltip(new Tooltip("Show/hide stage control panel"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button docHelpButton = DocumentationHelper.createHelpButton("liveViewer");

        HBox toolbar = new HBox(
                8,
                liveToggleButton,
                refineFocusButton,
                sweepFocusButton,
                smoothFocusButton,
                focusRangeCombo,
                showTilesCheckBox,
                spacer,
                stageControlToggle,
                scaleLabel,
                scaleCombo);
        if (docHelpButton != null) toolbar.getChildren().add(docHelpButton);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Image display
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false); // Nearest-neighbor for microscopy

        // Double-click-to-center: click on image to move stage so that point becomes center.
        // Must use addEventFilter (capture phase) instead of setOnMouseClicked (bubble phase)
        // because ScrollPane.setPannable(true) consumes mouse events during bubbling.
        imageView.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getClickCount() == 2) {
                handleDoubleClickToCenter(event);
            }
        });

        // Cursor pixel readout on mouse move
        imageView.setOnMouseMoved(this::updateCursorPixelInfo);
        imageView.setOnMouseExited(event -> cursorLabel.setText("Pixel: --"));

        // Container with scroll support for large images at explicit scale
        scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: black;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true); // Allow drag-to-pan

        // Inner container for centering when image is smaller than viewport
        imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: black;");
        imageContainer.setAlignment(Pos.CENTER);
        scrollPane.setContent(imageContainer);

        // Default to Fit mode: image scales to fill container
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        imageView.fitWidthProperty().bind(scrollPane.widthProperty().subtract(2));
        imageView.fitHeightProperty().bind(scrollPane.heightProperty().subtract(2));

        // Stage control panel (on right side, expanded by default for visibility)
        stageControlPanel = new StageControlPanel();

        // Wrap in ScrollPane to handle overflow when window is short
        stageScrollPane = new ScrollPane(stageControlPanel);
        stageScrollPane.setFitToWidth(true);
        stageScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        stageScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        stageScrollPane.setStyle("-fx-background-color: transparent;");
        stageScrollPane.setPrefWidth(280);
        stageScrollPane.setMinWidth(250);

        // Histogram + contrast controls (wrapped in collapsible TitledPane)
        histogramView = new HistogramView(contrastSettings);
        histogramPane = new TitledPane("Histogram & Contrast", histogramView);
        histogramPane.setExpanded(true);
        histogramPane.setAnimated(false);

        // Noise stats panel (collapsible, below histogram)
        noiseStatsPanel = new NoiseStatsPanel();

        // Status bar with cursor pixel readout on the right
        statusLabel = new Label("Ready - press Live to start");
        statusLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        cursorLabel = new Label("Pixel: --");
        cursorLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(8, statusLabel, statusSpacer, cursorLabel);
        statusBar.setPadding(new Insets(4));
        statusBar.setAlignment(Pos.CENTER_LEFT);

        // Bottom pane: Histogram, Noise Stats, Status Bar
        VBox bottomPane = new VBox(histogramPane, noiseStatsPanel, statusBar);

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setRight(stageScrollPane); // Stage control on right side (starts visible)
        root.setCenter(scrollPane); // Live image in center
        root.setBottom(bottomPane);

        // Wire toggle to show/hide the stage control panel
        stageControlToggle.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            if (isSelected) {
                root.setRight(stageScrollPane);
            } else {
                root.setRight(null);
            }
        });

        expandedContent = root;

        Scene scene = new Scene(expandedContent, WINDOW_WIDTH, WINDOW_HEIGHT);
        stage.setScene(scene);
        stage.setMinWidth(500);
        stage.setMinHeight(400);

        // Keyboard event handler for WASD/arrow stage movement
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            // Only handle if not focused on a text field
            if (event.getTarget() instanceof TextField) {
                return;
            }
            if (stageControlPanel.handleKeyEvent(event)) {
                event.consume();
            }
        });

        // Clean shutdown on close
        stage.setOnCloseRequest(e -> stopAndDispose());
    }

    /**
     * Toggles continuous acquisition on/off. Called by the Live button.
     * Uses core-level sequence acquisition -- does NOT interact with MM's
     * studio live mode or live window.
     * <p>
     * Socket operations run on background thread to avoid blocking FX thread
     * when socket lock is held by another operation (e.g., birefringence optimization).
     */
    private void toggleLiveMode() {
        if (lockManager != null && lockManager.isLocked()) {
            updateStatus("LOCKED: " + lockManager.getLockHolder());
            return;
        }
        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null) {
            updateStatus("Not connected to microscope");
            return;
        }

        boolean newState = !liveActive;

        // Disable button and show pending state
        liveToggleButton.setDisable(true);
        updateStatus(newState ? "Starting live..." : "Stopping live...");

        // Run socket operation on background thread
        Thread toggleThread = new Thread(
                () -> {
                    try {
                        if (newState) {
                            controller.startContinuousAcquisition();
                        } else {
                            controller.stopContinuousAcquisition();
                        }

                        // Update state and UI on FX thread
                        Platform.runLater(() -> {
                            liveActive = newState;
                            updateLiveButtonStyle(newState);
                            liveToggleButton.setDisable(false);
                            updateRefineFocusButtonState();
                            logger.info("Continuous acquisition toggled to: {}", newState ? "ON" : "OFF");

                            if (newState) {
                                updateStatus("Live ON - streaming...");
                                // Reset FPS counter, desync tracking, and recovery state
                                frameCount.set(0);
                                fpsWindowStart.set(System.currentTimeMillis());
                                currentFps = 0;
                                liveOnTimestamp = System.currentTimeMillis();
                                lastFrameArrivalTime = System.currentTimeMillis();
                                recoveryAttempts = 0;
                                recoveryInProgress = false;
                            } else {
                                updateStatus("Live OFF");
                                lastFrameArrivalTime = 0;
                                recoveryAttempts = 0;
                                recoveryInProgress = false;
                            }
                        });
                    } catch (IOException e) {
                        logger.error("Failed to toggle continuous acquisition: {}", e.getMessage());
                        Platform.runLater(() -> {
                            liveToggleButton.setDisable(false);
                            updateStatus("Error: " + e.getMessage());
                        });
                    }
                },
                "LiveViewer-Toggle");
        toggleThread.setDaemon(true);
        toggleThread.start();
    }

    private void updateLiveButtonStyle(boolean active) {
        if (active) {
            liveToggleButton.setText("Live: ON");
            liveToggleButton.setStyle("-fx-font-weight: bold; -fx-base: #4CAF50;");
        } else {
            liveToggleButton.setText("Live: OFF");
            liveToggleButton.setStyle("-fx-font-weight: bold; -fx-base: #9E9E9E;");
        }
    }

    /**
     * Updates the Refine Focus button enabled/disabled state and text
     * based on current live mode and refine focus running state.
     * Must be called on FX thread.
     */
    private void updateRefineFocusButtonState() {
        boolean refineRunning = refineFocusController != null && refineFocusController.isRunning();
        boolean sweepRunning = sweepFocusController != null && sweepFocusController.isRunning();
        if (refineRunning || sweepRunning) {
            // Keep showing cancel while either is running
            return;
        }
        boolean smoothRunning = smoothFocusController != null && smoothFocusController.isRunning();
        if (smoothRunning) {
            return;
        }
        boolean enabled = liveActive;
        refineFocusButton.setText("Refine Focus");
        refineFocusButton.setStyle("");
        refineFocusButton.setDisable(!enabled);
        sweepFocusButton.setText("Sweep Focus");
        sweepFocusButton.setStyle("");
        sweepFocusButton.setDisable(!enabled);
        smoothFocusButton.setText("Smooth Focus");
        smoothFocusButton.setStyle("");
        smoothFocusButton.setDisable(!enabled);
        focusRangeCombo.setDisable(!enabled);
    }

    private void handleRefineFocus() {
        if (refineFocusController != null && refineFocusController.isRunning()) {
            // Already running -- cancel
            refineFocusController.cancel();
            refineFocusButton.setText("Cancelling...");
            refineFocusButton.setDisable(true);
            return;
        }

        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !liveActive) {
            updateStatus("Cannot refine focus: not connected or live not active");
            return;
        }

        if (controller.isAcquisitionActive()) {
            updateStatus("Cannot refine focus: acquisition in progress");
            return;
        }

        // Parse search range from combo
        double searchRange = 0; // 0 = auto
        String rangeSelection = focusRangeCombo.getValue();
        if (rangeSelection != null && rangeSelection.endsWith("um")) {
            try {
                searchRange = Double.parseDouble(rangeSelection.replace("um", ""));
            } catch (NumberFormatException ignored) {
            }
        }

        // Create controller with frame supplier reading lastFrame
        refineFocusController = new RefineFocusController(controller.getSocketClient(), () -> lastFrame, searchRange);

        // Update button to cancel mode, lock live toggle during focus
        refineFocusButton.setText("Cancel Focus");
        refineFocusButton.setStyle("");
        sweepFocusButton.setDisable(true);
        smoothFocusButton.setDisable(true);
        focusRangeCombo.setDisable(true);
        liveToggleButton.setDisable(true);

        // Status callback -- hold completion messages so FPS ticker doesn't overwrite them
        RefineFocusController.StatusCallback callback = (msg, outcome) -> {
            Platform.runLater(() -> {
                boolean done = outcome != RefineFocusController.Outcome.IN_PROGRESS;
                if (done) {
                    updateStatusHeld(msg);
                } else {
                    updateStatus(msg);
                }
                if (done) {
                    liveToggleButton.setDisable(false);
                    focusRangeCombo.setDisable(!liveActive);
                    sweepFocusButton.setDisable(!liveActive);
                    smoothFocusButton.setDisable(!liveActive);
                    if (stageControlPanel != null) {
                        stageControlPanel.refreshPositions();
                    }

                    if (outcome == RefineFocusController.Outcome.FAILED) {
                        refineFocusButton.setText("FAILED");
                        refineFocusButton.setStyle("-fx-font-size: 11; -fx-base: #F44336;");
                        refineFocusButton.setTooltip(new Tooltip("No focus improvement found within search range.\n"
                                + "- Get closer to focus manually (scroll Z), then retry\n"
                                + "- Or widen the search range dropdown"));
                        refineFocusButton.setDisable(!liveActive);
                    } else {
                        refineFocusButton.setText("Refine Focus");
                        refineFocusButton.setStyle("");
                        refineFocusButton.setDisable(!liveActive);
                    }
                }
            });
        };

        // Run on background daemon thread
        Thread focusThread = new Thread(() -> refineFocusController.execute(callback));
        focusThread.setDaemon(true);
        focusThread.setName("LiveViewer-RefineFocus");
        focusThread.start();
    }

    private void handleSweepFocus() {
        if (sweepFocusController != null && sweepFocusController.isRunning()) {
            sweepFocusController.cancel();
            sweepFocusButton.setText("Cancelling...");
            sweepFocusButton.setDisable(true);
            return;
        }

        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !liveActive) {
            updateStatus("Cannot sweep focus: not connected or live not active");
            return;
        }
        if (controller.isAcquisitionActive()) {
            updateStatus("Cannot sweep focus: acquisition in progress");
            return;
        }

        double searchRange = 0;
        String rangeSelection = focusRangeCombo.getValue();
        if (rangeSelection != null && rangeSelection.endsWith("um")) {
            try {
                searchRange = Double.parseDouble(rangeSelection.replace("um", ""));
            } catch (NumberFormatException ignored) {
            }
        }

        sweepFocusController = new SweepFocusController(controller.getSocketClient(), () -> lastFrame, searchRange);

        sweepFocusButton.setText("Cancel Sweep");
        sweepFocusButton.setStyle("");
        refineFocusButton.setDisable(true);
        smoothFocusButton.setDisable(true);
        focusRangeCombo.setDisable(true);
        liveToggleButton.setDisable(true);

        RefineFocusController.StatusCallback callback = (msg, outcome) -> {
            Platform.runLater(() -> {
                boolean done = outcome != RefineFocusController.Outcome.IN_PROGRESS;
                if (done) {
                    updateStatusHeld(msg);
                } else {
                    updateStatus(msg);
                }
                if (done) {
                    liveToggleButton.setDisable(false);
                    focusRangeCombo.setDisable(!liveActive);
                    refineFocusButton.setDisable(!liveActive);
                    smoothFocusButton.setDisable(!liveActive);
                    if (stageControlPanel != null) {
                        stageControlPanel.refreshPositions();
                    }
                    if (outcome == RefineFocusController.Outcome.FAILED) {
                        sweepFocusButton.setText("FAILED");
                        sweepFocusButton.setStyle("-fx-font-size: 11; -fx-base: #F44336;");
                        sweepFocusButton.setDisable(!liveActive);
                    } else {
                        sweepFocusButton.setText("Sweep Focus");
                        sweepFocusButton.setStyle("");
                        sweepFocusButton.setDisable(!liveActive);
                    }
                }
            });
        };

        Thread sweepThread = new Thread(() -> sweepFocusController.execute(callback));
        sweepThread.setDaemon(true);
        sweepThread.setName("LiveViewer-SweepFocus");
        sweepThread.start();
    }

    private void handleSmoothFocus() {
        if (smoothFocusController != null && smoothFocusController.isRunning()) {
            // Smooth scans are short (<2s); we do not currently support cancel.
            // Ignore double-clicks while running.
            return;
        }

        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !liveActive) {
            updateStatus("Cannot smooth focus: not connected or live not active");
            return;
        }
        if (controller.isAcquisitionActive()) {
            updateStatus("Cannot smooth focus: acquisition in progress");
            return;
        }

        smoothFocusController = new SmoothFocusController(controller.getSocketClient());

        // Pass null for objective: the server resolves via pixel-size
        // match against config.hardware.objectives. This avoids depending
        // on a specific client-side selection state that differs across
        // workflows (Camera Control dialog, Autofocus Editor, acquisition
        // wizard). When the Live Viewer is invoked mid-acquisition we
        // could in theory thread the active objective through, but that
        // plumbing does not exist yet and the server auto-detect is
        // accurate enough for the Smooth use case.
        String objective = null;

        // Read the modality from the Camera tab's modality dropdown.
        // The server uses this to pick a modality-appropriate saturation
        // refusal threshold: brightfield tolerates heavy saturation
        // (bright background, dark tissue), PPM is moderate, and
        // fluorescence / laser-scanning need strict thresholds because
        // the signal is confined to a small fraction of pixels and
        // saturating any of them means losing focus information.
        String modality = null;
        if (stageControlPanel != null) {
            modality = stageControlPanel.getCurrentCameraModality();
        }

        // Read the focus range dropdown value. "Auto" -> NaN -> server
        // uses sweep_range_um from the yaml. Any explicit "Num_um" value
        // overrides. This gives the user a quick way to widen the scan
        // window when they suspect they are far from focus (the default
        // sweep_range_um=6 is tuned for small drift corrections, not
        // initial acquisition from scratch).
        double smoothRangeOverride = Double.NaN;
        String rangeSelection = focusRangeCombo.getValue();
        if (rangeSelection != null && rangeSelection.endsWith("um")) {
            try {
                smoothRangeOverride = Double.parseDouble(rangeSelection.replace("um", ""));
            } catch (NumberFormatException ignored) {
            }
        }

        smoothFocusButton.setText("Scanning...");
        smoothFocusButton.setStyle("");
        smoothFocusButton.setDisable(true);
        refineFocusButton.setDisable(true);
        sweepFocusButton.setDisable(true);
        focusRangeCombo.setDisable(true);
        liveToggleButton.setDisable(true);

        final String objectiveParam = objective;
        final String modalityParam = modality;
        RefineFocusController.StatusCallback callback = (msg, outcome) -> {
            Platform.runLater(() -> {
                boolean done = outcome != RefineFocusController.Outcome.IN_PROGRESS;
                if (done) {
                    updateStatusHeld(msg);
                } else {
                    updateStatus(msg);
                }
                if (done) {
                    liveToggleButton.setDisable(false);
                    focusRangeCombo.setDisable(!liveActive);
                    refineFocusButton.setDisable(!liveActive);
                    sweepFocusButton.setDisable(!liveActive);
                    if (stageControlPanel != null) {
                        stageControlPanel.refreshPositions();
                    }
                    if (outcome == RefineFocusController.Outcome.FAILED) {
                        // UNAVAILABLE / pre-flight refusal -- show in orange,
                        // not red, so the user understands this is a soft
                        // fallback path rather than an error. Show the full
                        // reason in a dialog AND in the status area so the
                        // user can't miss it -- the old tooltip-only path
                        // was invisible unless the user hovered, and users
                        // reported missing the explanation entirely.
                        smoothFocusButton.setText("Unavailable");
                        smoothFocusButton.setStyle("-fx-font-size: 11; -fx-base: #FF9800;");
                        smoothFocusButton.setTooltip(new Tooltip(msg + "\nFall back to Sweep Focus."));
                        smoothFocusButton.setDisable(!liveActive);
                        // Strip the 'Smooth Focus unavailable: ' prefix if
                        // present so the dialog body is the raw reason.
                        String reason = msg;
                        if (reason.startsWith("Smooth Focus unavailable: ")) {
                            reason = reason.substring("Smooth Focus unavailable: ".length());
                        }
                        Dialogs.showInfoNotification("Smooth Focus unavailable", reason);
                    } else if (outcome == RefineFocusController.Outcome.ERROR) {
                        smoothFocusButton.setText("FAILED");
                        smoothFocusButton.setStyle("-fx-font-size: 11; -fx-base: #F44336;");
                        smoothFocusButton.setTooltip(new Tooltip(msg));
                        smoothFocusButton.setDisable(!liveActive);
                    } else {
                        smoothFocusButton.setText("Smooth Focus");
                        smoothFocusButton.setStyle("");
                        smoothFocusButton.setDisable(!liveActive);
                    }
                }
            });
        };

        final double rangeOverride = smoothRangeOverride;
        Thread smoothThread = new Thread(() ->
                smoothFocusController.execute(objectiveParam, modalityParam,
                        rangeOverride, callback));
        smoothThread.setDaemon(true);
        smoothThread.setName("LiveViewer-SmoothFocus");
        smoothThread.start();
    }

    /**
     * Starts the polling threads. Does NOT start live mode -- user controls
     * live mode via the toggle button.
     */
    private void startLiveViewing() {
        if (polling) return;
        polling = true;

        // Frame poller thread
        framePoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LiveViewer-FramePoller");
            t.setDaemon(true);
            return t;
        });

        // Histogram computation thread
        histogramExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LiveViewer-Histogram");
            t.setDaemon(true);
            return t;
        });

        framePoller.scheduleWithFixedDelay(this::pollFrame, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("Live viewer polling started (live mode controlled by button)");
    }

    private void pollFrame() {
        if (!polling) return;

        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller == null) {
                Platform.runLater(() -> updateStatus("Disconnected"));
                return;
            }

            // Gate rendering on liveActive -- prevents desync Scenario A
            // (button says OFF but histogram is moving)
            if (!liveActive) {
                FrameData frame = controller.getFrame();
                if (frame != null) {
                    lastFrame = frame; // Keep for cursor readout
                    handleUnexpectedFrame();
                }
                return;
            }

            FrameData frame = controller.getFrame();
            if (frame == null) {
                // No-frame timeout with auto-recovery
                long now = System.currentTimeMillis();
                long elapsed = now - liveOnTimestamp;
                long sinceLast = now - lastFrameArrivalTime;
                if (elapsed > GRACE_PERIOD_MS && sinceLast > NO_FRAME_TIMEOUT_MS && !recoveryInProgress) {
                    if (recoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
                        // Auto-recovery: stop camera, restart, try again
                        recoveryInProgress = true;
                        recoveryAttempts++;
                        logger.info(
                                "No frames for {}ms -- auto-recovery attempt {}/{}",
                                sinceLast,
                                recoveryAttempts,
                                MAX_RECOVERY_ATTEMPTS);
                        Platform.runLater(() -> updateStatus("Reconnecting camera..."));
                        attemptAutoRecovery(controller);
                    } else {
                        // Recovery exhausted -- turn off
                        logger.warn("No frames for {}ms, recovery exhausted -- turning live OFF", sinceLast);
                        liveActive = false;
                        try {
                            controller.stopContinuousAcquisition();
                        } catch (IOException ex) {
                            logger.debug("Stop during final shutdown: {}", ex.getMessage());
                        }
                        Platform.runLater(() -> {
                            updateLiveButtonStyle(false);
                            updateRefineFocusButtonState();
                            updateStatus("Live OFF (no frames -- check camera connection)");
                        });
                    }
                }
                return;
            }

            // Frame arrived -- track arrival time and reset recovery counter
            lastFrame = frame;
            lastFrameArrivalTime = System.currentTimeMillis();
            if (recoveryAttempts > 0) {
                logger.info("Frames restored after {} recovery attempt(s)", recoveryAttempts);
                recoveryAttempts = 0;
                Platform.runLater(() -> updateStatus("Live ON - streaming..."));
            }

            // Track FPS
            int count = frameCount.incrementAndGet();
            long now = System.currentTimeMillis();
            long elapsed = now - fpsWindowStart.get();
            if (elapsed >= 1000) {
                currentFps = count * 1000.0 / elapsed;
                frameCount.set(0);
                fpsWindowStart.set(now);
            }

            // Build frame info string
            String bitDepth = frame.bytesPerPixel() == 2 ? "16-bit" : "8-bit";
            String colorMode = frame.isRGB() ? "RGB" : "Grayscale";
            lastFrameInfo = String.format(
                    "FPS: %.1f | %dx%d | %s %s", currentFps, frame.width(), frame.height(), colorMode, bitDepth);

            // Submit histogram computation (throttled internally)
            // Capture local ref: stopAndDispose() may null the field concurrently
            ExecutorService histExec = histogramExecutor;
            if (histExec == null || histExec.isShutdown()) return;

            histExec.submit(() -> {
                try {
                    Platform.runLater(() -> histogramView.updateHistogram(frame));
                } catch (Exception e) {
                    logger.debug("Histogram update failed: {}", e.getMessage());
                }
            });

            // Update noise stats (throttled internally to ~2Hz)
            if (noiseStatsPanel.isExpanded()) {
                histExec.submit(() -> {
                    try {
                        noiseStatsPanel.updateFromFrame(frame);
                    } catch (Exception e) {
                        logger.debug("Noise stats update failed: {}", e.getMessage());
                    }
                });
            }

            // Render frame on FX thread
            Platform.runLater(() -> renderFrame(frame));

        } catch (IOException e) {
            logger.debug("Frame poll failed: {}", e.getMessage());
            Platform.runLater(() -> updateStatus("Connection error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error in frame poll", e);
        }
    }

    private void renderFrame(FrameData frame) {
        int srcW = frame.width();
        int srcH = frame.height();

        // Store for coordinate conversion in double-click handler
        this.sourceImageWidth = srcW;
        this.sourceImageHeight = srcH;

        int dstW, dstH;
        if (fitToContainer) {
            // Fit mode: render at full resolution, ImageView scales it down via binding
            dstW = srcW;
            dstH = srcH;
        } else {
            // Explicit scale mode: render at scaled resolution
            dstW = Math.max(1, (int) (srcW * explicitScale));
            dstH = Math.max(1, (int) (srcH * explicitScale));
        }

        // Compute scale for pixel mapping (for explicit scale mode)
        double scale = fitToContainer ? 1.0 : explicitScale;

        // Recreate WritableImage if display dimensions changed
        if (dstW != lastFrameWidth || dstH != lastFrameHeight) {
            writableImage = new WritableImage(dstW, dstH);
            argbBuffer = new int[dstW * dstH];
            lastFrameWidth = dstW;
            lastFrameHeight = dstH;
            imageView.setImage(writableImage);

            // Apply full range for new bit depth
            contrastSettings.applyFullRange(frame);
        }

        // Apply contrast mapping and convert to ARGB with subsampling
        int min = contrastSettings.getDisplayMin();
        int max = contrastSettings.getDisplayMax();
        int range = Math.max(1, max - min);

        byte[] raw = frame.rawPixels();
        int bpp = frame.bytesPerPixel();
        int channels = frame.channels();

        for (int dy = 0; dy < dstH; dy++) {
            // Map display row to source row
            int sy = (int) (dy / scale);
            if (sy >= srcH) sy = srcH - 1;

            for (int dx = 0; dx < dstW; dx++) {
                // Map display col to source col
                int sx = (int) (dx / scale);
                if (sx >= srcW) sx = srcW - 1;

                int srcIdx = sy * srcW + sx;

                if (channels == 1) {
                    int val;
                    if (bpp == 1) {
                        val = raw[srcIdx] & 0xFF;
                    } else {
                        int byteOff = srcIdx * 2;
                        val = ((raw[byteOff] & 0xFF) << 8) | (raw[byteOff + 1] & 0xFF);
                    }
                    int mapped = clamp((val - min) * 255 / range, 0, 255);
                    argbBuffer[dy * dstW + dx] = 0xFF000000 | (mapped << 16) | (mapped << 8) | mapped;
                } else {
                    int bytesPerSample = bpp;
                    int stride = channels * bytesPerSample;
                    int byteOff = srcIdx * stride;
                    int r, g, b;
                    if (bpp == 1) {
                        r = raw[byteOff] & 0xFF;
                        g = raw[byteOff + 1] & 0xFF;
                        b = raw[byteOff + 2] & 0xFF;
                    } else {
                        r = ((raw[byteOff] & 0xFF) << 8) | (raw[byteOff + 1] & 0xFF);
                        g = ((raw[byteOff + 2] & 0xFF) << 8) | (raw[byteOff + 3] & 0xFF);
                        b = ((raw[byteOff + 4] & 0xFF) << 8) | (raw[byteOff + 5] & 0xFF);
                    }
                    r = clamp((r - min) * 255 / range, 0, 255);
                    g = clamp((g - min) * 255 / range, 0, 255);
                    b = clamp((b - min) * 255 / range, 0, 255);
                    argbBuffer[dy * dstW + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }

        // Batch write to WritableImage
        writableImage
                .getPixelWriter()
                .setPixels(0, 0, dstW, dstH, PixelFormat.getIntArgbInstance(), argbBuffer, 0, dstW);

        // Only overwrite status with FPS ticker if no held message is active
        if (System.currentTimeMillis() >= statusHoldUntil) {
            updateStatus(lastFrameInfo);
        }
    }

    /**
     * Handles double-click on the live image to center that point.
     * Calculates the offset from click position to image center, converts to microns
     * using the camera FOV, and moves the stage accordingly.
     * <p>
     * All socket operations run on a background thread to avoid blocking the FX thread,
     * which could cause "Not Responding" if the socket is held by another operation.
     *
     * @param event The mouse event from the double-click
     */
    private void handleDoubleClickToCenter(javafx.scene.input.MouseEvent event) {
        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !controller.isConnected()) {
            updateStatus("Cannot move: not connected");
            return;
        }

        // Capture UI state on FX thread
        int srcW = sourceImageWidth;
        int srcH = sourceImageHeight;
        if (srcW <= 0 || srcH <= 0) {
            updateStatus("No frame data available");
            return;
        }

        // Calculate displayed image dimensions based on display mode (must be on FX thread)
        double displayWidth, displayHeight;
        if (fitToContainer) {
            displayWidth = imageView.getBoundsInLocal().getWidth();
            displayHeight = imageView.getBoundsInLocal().getHeight();
        } else {
            displayWidth = srcW * explicitScale;
            displayHeight = srcH * explicitScale;
        }

        // Calculate source pixel coordinates from click position
        double sourceClickX = event.getX() * (srcW / displayWidth);
        double sourceClickY = event.getY() * (srcH / displayHeight);

        // Calculate offset from image center (in pixels)
        double offsetPixelsX = sourceClickX - (srcW / 2.0);
        double offsetPixelsY = sourceClickY - (srcH / 2.0);

        // Capture the stage/image transform. Double-click-to-center is
        // semantically unambiguous ("put the clicked point at the new
        // centre"), so unlike the arrow/joystick controls it does NOT
        // apply the sample-movement inversion -- the sample must always
        // move toward the clicked point.
        qupath.ext.qpsc.utilities.StageImageTransform transform =
                qupath.ext.qpsc.utilities.StageImageTransform.current();

        // Final variables for use in background thread
        final int finalSrcW = srcW;
        final int finalSrcH = srcH;

        updateStatus("Moving stage...");

        // Run socket operations on background thread to avoid blocking FX thread
        Thread moveThread = new Thread(
                () -> {
                    try {
                        // Get FOV for pixel-to-micron conversion
                        double[] fov = controller.getCameraFOV();
                        if (fov[0] <= 0 || fov[1] <= 0) {
                            Platform.runLater(() -> updateStatus("Camera FOV not available"));
                            return;
                        }

                        // Compute per-axis pixel size from the camera FOV,
                        // then ask the transform to turn the pixel offset
                        // into a new MM target.
                        double pixelSizeX_um = fov[0] / finalSrcW;
                        double pixelSizeY_um = fov[1] / finalSrcH;

                        // Get current stage position
                        double[] currentPos = controller.getStagePositionXY();

                        double[] target = transform.clickOffsetToMmTarget(
                                currentPos[0], currentPos[1],
                                offsetPixelsX, offsetPixelsY,
                                pixelSizeX_um, pixelSizeY_um);
                        double newX = target[0];
                        double newY = target[1];

                        // Move stage (bounds checking handled by controller)
                        controller.moveStageXY(newX, newY);

                        Platform.runLater(() -> updateStatus(String.format("Centered on (%.0f, %.0f)", newX, newY)));
                        logger.info(
                                "Double-click-to-center: transform={}, offsetPx=({}, {}) -> newStage=({}, {})",
                                transform,
                                String.format("%.1f", offsetPixelsX),
                                String.format("%.1f", offsetPixelsY),
                                String.format("%.1f", newX),
                                String.format("%.1f", newY));

                    } catch (IOException e) {
                        logger.warn("Failed to center on click: {}", e.getMessage());
                        Platform.runLater(() -> updateStatus("Error: " + e.getMessage()));
                    }
                },
                "LiveViewer-ClickToCenter");
        moveThread.setDaemon(true);
        moveThread.start();
    }

    /**
     * Updates the cursor pixel readout label with R,G,B values at the mouse position.
     * Works even when not streaming, as long as a frame has been displayed.
     */
    private void updateCursorPixelInfo(javafx.scene.input.MouseEvent event) {
        FrameData frame = lastFrame;
        if (frame == null) return;

        int srcW = frame.width();
        int srcH = frame.height();
        if (srcW <= 0 || srcH <= 0) return;

        // Convert display coordinates to source pixel coordinates
        double displayWidth, displayHeight;
        if (fitToContainer) {
            displayWidth = imageView.getBoundsInLocal().getWidth();
            displayHeight = imageView.getBoundsInLocal().getHeight();
        } else {
            displayWidth = srcW * explicitScale;
            displayHeight = srcH * explicitScale;
        }

        if (displayWidth <= 0 || displayHeight <= 0) return;

        int srcX = (int) (event.getX() * srcW / displayWidth);
        int srcY = (int) (event.getY() * srcH / displayHeight);

        // Bounds check
        if (srcX < 0 || srcX >= srcW || srcY < 0 || srcY >= srcH) {
            cursorLabel.setText("Pixel: --");
            return;
        }

        int channels = frame.channels();
        int bpp = frame.bytesPerPixel();
        int pixelIndex = srcY * srcW + srcX;

        if (channels == 1) {
            int val = frame.readPixelValue(pixelIndex * bpp);
            cursorLabel.setText(String.format("Pixel [%d, %d]: %d", srcX, srcY, val));
        } else {
            int stride = channels * bpp;
            int byteOffset = pixelIndex * stride;
            int r = frame.readPixelValue(byteOffset);
            int g = frame.readPixelValue(byteOffset + bpp);
            int b = frame.readPixelValue(byteOffset + 2 * bpp);
            cursorLabel.setText(String.format("Pixel [%d, %d]: R=%d  G=%d  B=%d", srcX, srcY, r, g, b));
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateStatus(String text) {
        statusLabel.setText(text);
    }

    /**
     * Updates the status label and holds the message for a few seconds so the
     * frame-rate ticker does not immediately overwrite it.
     */
    private void updateStatusHeld(String text) {
        statusLabel.setText(text);
        statusHoldUntil = System.currentTimeMillis() + STATUS_HOLD_MS;
    }

    /**
     * Attempts to recover from a no-frames condition by stopping and restarting
     * continuous acquisition. Runs on the frame poller thread (background).
     */
    private void attemptAutoRecovery(MicroscopeController controller) {
        try {
            // Stop camera to reset state
            controller.stopContinuousAcquisition();
            Thread.sleep(500);

            // Restart
            controller.startContinuousAcquisition();

            // Reset tracking timestamps to give camera time to start
            liveOnTimestamp = System.currentTimeMillis();
            lastFrameArrivalTime = System.currentTimeMillis();
            logger.info("Auto-recovery: restarted continuous acquisition");
            Platform.runLater(() -> updateStatus("Live ON - reconnecting..."));
        } catch (IOException e) {
            logger.warn("Auto-recovery failed: {}", e.getMessage());
            Platform.runLater(() -> updateStatus("Recovery failed: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            recoveryInProgress = false;
        }
    }

    /**
     * Called when frames arrive but liveActive is false.
     * Camera is still streaming after user clicked OFF -- notify via status.
     *
     * <p>Guard: re-check liveActive inside Platform.runLater because the toggle
     * thread may have set it to true between the poller's check and FX execution.
     * Without this guard, the button flips back to OFF immediately after turning ON.
     */
    private void handleUnexpectedFrame() {
        Platform.runLater(() -> {
            if (!liveActive) {
                updateStatus("Live OFF (camera still sending frames -- click Live to sync)");
                updateLiveButtonStyle(false);
            }
        });
    }

    private void stopAndDispose() {
        polling = false;
        logger.info("Stopping live viewer...");

        // Stop polling threads
        if (framePoller != null) {
            framePoller.shutdownNow();
            framePoller = null;
        }
        if (histogramExecutor != null) {
            histogramExecutor.shutdownNow();
            histogramExecutor = null;
        }

        // Cancel any running refine focus
        if (refineFocusController != null && refineFocusController.isRunning()) {
            refineFocusController.cancel();
        }

        // Stop stage control panel (joystick executor)
        if (stageControlPanel != null) {
            stageControlPanel.stop();
        }

        // Stop continuous acquisition if it was active
        if (liveActive) {
            try {
                MicroscopeController controller = MicroscopeController.getInstance();
                if (controller != null) {
                    controller.stopContinuousAcquisition();
                    logger.info("Continuous acquisition stopped on window close");
                }
            } catch (IOException e) {
                logger.warn("Failed to stop continuous acquisition: {}", e.getMessage());
            }
            liveActive = false;
        }

        // Close window
        if (stage != null) {
            stage.hide();
        }

        // Clear singleton
        instance = null;
        logger.info("Live viewer disposed");
    }
}
