package qupath.ext.qpsc.ui.liveviewer;

import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.AutofocusEditorWorkflow;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.controller.TestAutofocusWorkflow;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.state.ModalityState;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.utilities.DocumentationHelper;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.ObjectiveUtils;
import qupath.ext.qpsc.utilities.StagePositionManager;
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
    private Label fovLabel;
    private Button liveToggleButton;
    // Single Autofocus button. The method it runs (streaming continuous-Z
    // scan or stepped sweep) is chosen by the radio in the Autofocus
    // Configuration dialog and read from PersistentPreferences at click time.
    private Button autofocusButton;
    private StreamingFocusController streamingFocusController;

    /** Tooltip shown on the Autofocus button when it is idle (not mid-scan). */
    private static final String STREAMING_AF_TOOLTIP = "Autofocus.\n"
            + "Runs the method selected in Autofocus Configuration\n"
            + "(Streaming continuous-Z scan, or stepped Sweep).\n"
            + "Click again while scanning to cancel.";

    private ComboBox<String> focusRangeCombo;
    private HistogramView histogramView;
    private TitledPane histogramPane;
    private NoiseStatsPanel noiseStatsPanel;
    // Histogram + Noise group that can dock either below the image (default) or
    // in the right-hand column (vertical). See setHistogramDockedRight().
    private javafx.scene.layout.VBox histoNoiseGroup;
    private javafx.scene.layout.HBox rightArea;
    private javafx.scene.layout.VBox bottomPane;
    private ToggleButton histogramDockToggle;
    private StageControlPanel stageControlPanel;
    private ToggleButton stageControlToggle;

    /** Detached in {@link #stopAndDispose()} to avoid leaking on Live Viewer close. */
    private javafx.beans.value.ChangeListener<String> modalityTitleListener;

    private final ContrastSettings contrastSettings = new ContrastSettings();

    // Sweep autofocus now runs entirely server-side via TESTADAF
    // (testAdaptiveAutofocus). The Live Viewer just tracks "is a sweep
    // request in flight" so the button can show "Sweeping..." and ignore
    // re-clicks while the single socket round-trip is outstanding. The
    // old client-side step-and-snap controller is preserved in
    // claude-reports/design/2026-05-24_sweep-focus-controller-removal.md.
    private volatile boolean sweepRunning = false;

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

    // Snap button gate: latches true once at least one frame has been
    // received from the server. Combined with liveActive in the binding so
    // the Snap button is disabled when Live is OFF (avoids saving stale
    // 30-minute-old buffer) and before the first frame arrives.
    private final SimpleBooleanProperty hasFreshFrame = new SimpleBooleanProperty(false);
    private final SimpleBooleanProperty liveActiveProperty = new SimpleBooleanProperty(false);

    // Thread pools
    private ScheduledExecutorService framePoller;
    private ExecutorService histogramExecutor;

    /**
     * Single-thread executor for OME-TIFF writes triggered by the Snap
     * button. Separate from {@link #histogramExecutor} so a slow disk write
     * cannot stall the histogram update cadence. BioFormats TiffWriter is
     * not thread-safe, so this executor is single-threaded by design.
     */
    private ExecutorService snapIoExecutor;

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

    // Live XYZ(R) position overlay on the image + its toolbar toggle.
    private Label positionOverlayLabel;
    private ToggleButton positionOverlayToggle;
    private PropertyChangeListener positionListener;
    private javafx.beans.value.ChangeListener<qupath.lib.gui.prefs.PathPrefs.FontSize> positionFontSizeListener;

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

        // Structural state sync: bind the Live button's visual state to the
        // MicroscopeSocketClient's streamingActiveProperty. Any workflow that
        // successfully issues STRTSEQ/STOPSEQ (including ones that bypass
        // this window and call the socket directly) updates the property, so
        // the button cannot drift from the actual server streaming state.
        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller != null && controller.getSocketClient() != null) {
            controller
                    .getSocketClient()
                    .streamingActiveProperty()
                    .addListener((obs, wasActive, nowActive) ->
                            Platform.runLater(() -> syncLiveStateFromServer(nowActive != null && nowActive)));
        }
    }

    /**
     * Lazily create the Snap-write executor. Daemon thread so it doesn't
     * block JVM shutdown if a write is in flight.
     */
    private synchronized ExecutorService getOrCreateIoExecutor() {
        if (snapIoExecutor == null) {
            snapIoExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LiveViewer-SnapIO");
                t.setDaemon(true);
                return t;
            });
        }
        return snapIoExecutor;
    }

    /**
     * Reconcile local liveActive + button visuals with the authoritative
     * streaming state reported by the socket client. Called from the
     * streamingActiveProperty listener so any path that stops/starts
     * streaming keeps the UI honest.
     */
    private void syncLiveStateFromServer(boolean nowActive) {
        if (liveActive == nowActive) {
            return;
        }
        liveActive = nowActive;
        liveActiveProperty.set(nowActive);
        updateLiveButtonStyle(nowActive);
        updateStatus(nowActive ? "Live ON" : "Live OFF");
        logger.info("Live button synced to server streaming state: {}", nowActive ? "ACTIVE" : "INACTIVE");
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
        if (autofocusButton != null) autofocusButton.setDisable(true);
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

            // The streamingActiveProperty listener only fires on change, so if
            // the camera is ALREADY streaming when the viewer opens (e.g. a
            // workflow started it), reconcile the button + green live border to
            // the current server state now.
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller != null && controller.getSocketClient() != null) {
                instance.syncLiveStateFromServer(
                        controller.getSocketClient().streamingActiveProperty().get());
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
        instance.liveActiveProperty.set(false);
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
     * <p>
     * The UI state always reflects the actual outcome: on success the focus
     * buttons re-enable and the status reads "Live ON"; on failure liveActive
     * stays false, the status reads "Live OFF (restart failed)" and the user
     * can retry via the Live toggle. The previous version skipped the UI
     * update when {@link MicroscopeController#startContinuousAcquisition()}
     * threw, leaving the focus buttons stuck disabled with no feedback --
     * observed after the Editor's Test Sweep Autofocus on PPM 40x
     * 2026-05-04.
     */
    public static void restartStreaming() {
        if (instance == null) {
            return;
        }
        boolean started = false;
        String failureReason = null;
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
                started = true;
                // Reset FPS counter, desync tracking, and recovery state
                instance.frameCount.set(0);
                instance.fpsWindowStart.set(System.currentTimeMillis());
                instance.currentFps = 0;
                instance.liveOnTimestamp = System.currentTimeMillis();
                instance.lastFrameArrivalTime = System.currentTimeMillis();
                instance.recoveryAttempts = 0;
                instance.recoveryInProgress = false;
                logger.info("QPSC Live Viewer streaming restarted");
            } else {
                failureReason = "no microscope controller";
            }
        } catch (IOException e) {
            failureReason = e.getMessage();
            logger.warn("Failed to restart QPSC Live Viewer streaming: {}", failureReason);
        } finally {
            // Always sync UI state. If the restart threw, leave liveActive
            // false but RE-ENABLE the live toggle and reset the focus
            // button text so the user can recover by clicking Live ON.
            // Without this, the focus buttons stay setDisable(true) and
            // any prior "FAILED" sweep label sticks indefinitely.
            final boolean activeNow = started;
            final String reason = failureReason;
            instance.liveActive = activeNow;
            instance.liveActiveProperty.set(activeNow);
            Platform.runLater(() -> {
                if (instance == null) return;
                instance.updateLiveButtonStyle(activeNow);
                instance.updateRefineFocusButtonState();
                if (activeNow) {
                    instance.updateStatus("Live ON - streaming...");
                } else {
                    instance.liveToggleButton.setDisable(false);
                    String suffix = (reason != null && !reason.isEmpty()) ? " (" + reason + ")" : "";
                    instance.updateStatus("Live OFF (restart failed -- click Live to retry)" + suffix);
                }
            });
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
                    logger.debug(
                            "Unsupported tile image format for {}: type={}, bands={}",
                            tileFile.getName(),
                            img.getType(),
                            img.getRaster().getNumBands());
                    return;
                }

                String fileName = tileFile.getName();
                Platform.runLater(() -> {
                    if (instance != null) {
                        // Drive histogram first so renderFrame uses the
                        // tile-derived range when "Always Auto-Scale" is
                        // enabled. When it isn't, the user's manual
                        // contrast carries through.
                        if (instance.histogramView != null) {
                            instance.histogramView.updateHistogram(frame);
                        }
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
                    bytes[i * 2] = (byte) ((v >> 8) & 0xFF); // high byte first
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

            // Subdirectory layout: tiles in angle subdirs (90/, 7/, -7/) for PPM,
            // or channel subdirs (DAPI/, FITC/, TRITC/) for fluorescence/BF-IF.
            // Exclude PPM post-processing dirs like 7.0.biref/ and 7.0.sum/
            // whose biref TIFFs are computed last (newest timestamp) but are
            // 16-bit grayscale and render poorly with the 8-bit RGB contrast
            // settings of the Live Viewer.
            File[] subdirs = rootDir.listFiles(f -> f.isDirectory() && !isPostProcessingDirectory(f.getName()));
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    File[] tiffs =
                            subdir.listFiles(f -> f.isFile() && f.getName().endsWith(".tif"));
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
     * Returns true if the directory name looks like a PPM post-processing
     * output (e.g. "7.0.biref", "7.0.sum"). These contain computed images
     * that are 16-bit grayscale and render poorly in the Live Viewer.
     * Pattern: a numeric prefix followed by a dot and a non-numeric suffix.
     */
    private static boolean isPostProcessingDirectory(String name) {
        // Post-processing dirs have the form "<number>.<suffix>" where suffix
        // is NOT purely numeric (e.g. "7.0.biref", "90.0.sum").
        // Angle dirs like "7.0" or "-7" parse as Double; channel dirs like
        // "DAPI" or "FITC" have no dots. Neither should be excluded.
        int lastDot = name.lastIndexOf('.');
        if (lastDot <= 0) return false;
        String suffix = name.substring(lastDot + 1);
        if (suffix.isEmpty()) return false;
        // If the part after the last dot is non-numeric, it's a post-processing dir
        try {
            Double.parseDouble(suffix);
            return false; // e.g. "7.0" -- the suffix "0" is numeric, this is an angle dir
        } catch (NumberFormatException e) {
            return true; // e.g. "7.0.biref" -- suffix "biref" is non-numeric
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

        autofocusButton = new Button("Autofocus");
        autofocusButton.setTooltip(
                new Tooltip(STREAMING_AF_TOOLTIP + "\n\nRight-click: open Autofocus Configuration."));
        autofocusButton.setDisable(true);
        autofocusButton.setOnAction(e -> handleAutofocus());
        // Right-click opens the Autofocus Configuration editor without
        // disturbing the current focus state. Uses MOUSE_CLICKED rather than
        // contextMenuRequested so the editor opens even when the button is
        // disabled (e.g. between live frames or during cancel pending) -- the
        // user often wants to switch methods exactly when the button is greyed
        // out for an unrelated reason.
        autofocusButton.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                e.consume();
                AutofocusEditorWorkflow.run();
            }
        });

        focusRangeCombo = new ComboBox<>();
        // Options are repopulated per-objective via updateFocusRangeOptions();
        // start with a safe conservative set until the first objective resolves.
        focusRangeCombo.getItems().addAll("Config", "10um", "20um");
        focusRangeCombo.setValue("Config");
        // Width must fit "Config" plus the 4-character "<n>um" labels with a
        // dropdown chevron; 70 px clipped "20um" -> "20" on JavaFX 21.
        focusRangeCombo.setPrefWidth(95);
        focusRangeCombo.setTooltip(new Tooltip("Search range for autofocus.\n"
                + "Config: uses sweep_range_um from the autofocus YAML\n"
                + "(set in the Autofocus Configuration Editor).\n"
                + "Explicit values override the YAML for Streaming AF only;\n"
                + "Sweep Autofocus always reads from the YAML.\n\n"
                + "Options are capped per-objective: 10x allows up to 100um,\n"
                + "20x up to 50um, 40x up to 20um, 60x+ up to 10um.\n"
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

        // Toggle for the XYZ(R) position overlay on the live image. Bound
        // bidirectionally to the preference so the toolbar and the Preferences
        // pane stay in sync, and the overlay label (which binds to the same
        // preference) shows/hides accordingly.
        positionOverlayToggle = new ToggleButton("XYZ");
        positionOverlayToggle.setStyle("-fx-font-size: 11px;");
        positionOverlayToggle.setTooltip(
                new Tooltip("Show/hide the current XYZ(R) stage position overlaid on the live image"));
        positionOverlayToggle
                .selectedProperty()
                .bindBidirectional(QPPreferenceDialog.showLiveViewerPositionOverlayProperty());

        // Toggle that docks the Histogram & Contrast + Noise Stats panel on the
        // right (vertical) instead of below the image, to use horizontal space.
        histogramDockToggle = new ToggleButton("Hist: Right");
        histogramDockToggle.setStyle("-fx-font-size: 11px;");
        histogramDockToggle.setTooltip(new Tooltip("Dock the Histogram & Contrast + Noise Stats panel on the\n"
                + "right (vertical histogram) instead of below the image."));

        // Snap button: captures current frame, opens file save dialog, writes
        // OME-TIFF (BG correction routed through CORRECTFRAME socket command
        // when requested and settings match). Right-click for options.
        Button snapButton = SnapAction.create(
                () -> lastFrame,
                contrastSettings,
                getOrCreateIoExecutor(),
                hasFreshFrame,
                liveActiveProperty,
                this::updateStatus);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button docHelpButton = DocumentationHelper.createHelpButton("liveViewer");

        HBox toolbar = new HBox(
                8,
                liveToggleButton,
                autofocusButton,
                focusRangeCombo,
                showTilesCheckBox,
                snapButton,
                positionOverlayToggle,
                histogramDockToggle,
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

        // Overlay canvas for drawing objective FoV rectangles on the live image
        fovOverlayCanvas = new Canvas();
        fovOverlayCanvas.setMouseTransparent(true);

        // Live XYZ(R) position readout overlaid on the image (top-left corner).
        // Visibility follows the "show position overlay" preference (also driven
        // by the toolbar toggle). Mouse-transparent so it never blocks clicks.
        positionOverlayLabel = new Label();
        positionOverlayLabel.setMouseTransparent(true);
        StackPane.setAlignment(positionOverlayLabel, Pos.TOP_LEFT);
        StackPane.setMargin(positionOverlayLabel, new Insets(6));
        positionOverlayLabel.visibleProperty().bind(QPPreferenceDialog.showLiveViewerPositionOverlayProperty());
        positionOverlayLabel.managedProperty().bind(positionOverlayLabel.visibleProperty());
        applyPositionOverlayStyle();

        // Inner container for centering when image is smaller than viewport
        imageContainer = new StackPane(imageView, fovOverlayCanvas, positionOverlayLabel);
        imageContainer.setStyle("-fx-background-color: black;");
        imageContainer.setAlignment(Pos.CENTER);
        scrollPane.setContent(imageContainer);

        // Refresh the readout when the position changes (reuse the shared
        // StagePositionManager poller -- no new poll loop) and when the overlay
        // is switched on. Update the styling when the font-size pref changes.
        positionListener = evt -> Platform.runLater(this::updatePositionOverlay);
        StagePositionManager.getInstance().addPropertyChangeListener(positionListener);
        QPPreferenceDialog.showLiveViewerPositionOverlayProperty()
                .addListener((obs, was, now) -> Platform.runLater(this::updatePositionOverlay));
        positionFontSizeListener = (obs, was, now) -> Platform.runLater(this::applyPositionOverlayStyle);
        QPPreferenceDialog.liveViewerPositionFontSizeProperty().addListener(positionFontSizeListener);
        updatePositionOverlay();

        // Redraw overlay when image bounds change (resize/zoom)
        imageView.boundsInParentProperty().addListener((obs, oldB, newB) -> {
            if (fovOverlayVisible) drawFovOverlay();
        });

        // Default to Fit mode: image scales to fill container
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        imageView.fitWidthProperty().bind(scrollPane.widthProperty().subtract(2));
        imageView.fitHeightProperty().bind(scrollPane.heightProperty().subtract(2));

        // Stage control panel (on right side, expanded by default for visibility)
        stageControlPanel = new StageControlPanel();
        stageControlPanel.setLiveActiveProperty(liveActiveProperty);
        stageControlPanel.setOnHardwareChanged(() -> {
            updateFovLabel();
            updateFocusRangeOptions();
            updateWindowTitle();
        });
        stageControlPanel.setOnModalityChanged(this::updateWindowTitle);
        stageControlPanel.setOnFovOverlayToggle(this::toggleFovOverlay);
        // Title also updates when modality changes from any other surface
        // (Wizard, Background Collection, etc.) via the central state. Listener
        // detached in stopAndDispose() to avoid leaking a closed window.
        modalityTitleListener = (obs, oldV, newV) -> javafx.application.Platform.runLater(this::updateWindowTitle);
        ModalityState.getInstance().modalityProperty().addListener(modalityTitleListener);

        // Reflect the modality + objective magnification detected at construction
        // in the window title (e.g. "Live Viewer (Brightfield) (10x)").
        updateWindowTitle();

        // Mount the stage panel directly (no outer ScrollPane) so the TabPane
        // headers stay fixed at the top; each tab's content scrolls inside its
        // own ScrollPane (see StageControlPanel.tabScroll). Wrapping the whole
        // panel in a ScrollPane used to scroll the tab headers off-screen.
        stageControlPanel.setPrefWidth(280);
        stageControlPanel.setMinWidth(250);

        // Histogram + contrast controls (wrapped in collapsible TitledPane)
        histogramView = new HistogramView(contrastSettings);
        histogramPane = new TitledPane("Histogram & Contrast", histogramView);
        histogramPane.setExpanded(true);
        histogramPane.setAnimated(false);

        // Noise stats panel (collapsible, below histogram)
        noiseStatsPanel = new NoiseStatsPanel();

        // Status bar with FoV and cursor pixel readout
        statusLabel = new Label("Ready - press Live to start");
        statusLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        fovLabel = new Label("FoV: --");
        fovLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11; -fx-font-weight: bold;");
        cursorLabel = new Label("Pixel: --");
        cursorLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(8, statusLabel, statusSpacer, fovLabel, cursorLabel);
        statusBar.setPadding(new Insets(4));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        updateFovLabel();
        updateFocusRangeOptions();

        // Histogram + Noise group -- relocatable between the bottom and the
        // right column. The histogram TitledPane grows to fill the group so the
        // vertical histogram is tall when docked right; in the bottom dock the
        // group sits at its preferred height (Vgrow is a no-op there).
        histoNoiseGroup = new VBox(8, histogramPane, noiseStatsPanel);
        VBox.setVgrow(histogramPane, Priority.ALWAYS);

        // Bottom pane (status bar always lives here; the histo/noise group is
        // added/removed by the dock toggle).
        bottomPane = new VBox(histoNoiseGroup, statusBar);

        // Right column: an HBox so the histo/noise group can sit as its own
        // column LEFT of the stage control without fighting the stage panel's
        // vertical-grow (each column is independent).
        rightArea = new HBox(stageControlPanel);

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setRight(rightArea); // stage control (+ histo/noise when docked right)
        root.setCenter(scrollPane); // Live image in center
        root.setBottom(bottomPane);

        // Apply the persisted dock state (default: bottom).
        setHistogramDockedRight(QPPreferenceDialog.getLiveViewerHistogramDockRight());
        histogramDockToggle.setSelected(QPPreferenceDialog.getLiveViewerHistogramDockRight());
        histogramDockToggle.selectedProperty().addListener((obs, was, dockRight) -> {
            setHistogramDockedRight(dockRight);
            QPPreferenceDialog.setLiveViewerHistogramDockRight(dockRight);
        });

        // Wire toggle to show/hide the stage control panel (within the right
        // column, so it doesn't disturb a right-docked histogram).
        stageControlToggle.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            stageControlPanel.setVisible(isSelected);
            stageControlPanel.setManaged(isSelected);
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
                            liveActiveProperty.set(newState);
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

    /**
     * Moves the Histogram + Noise group between the bottom dock (horizontal,
     * below the image) and the right column (vertical, beside the stage
     * control). Default is the bottom dock. FX thread only.
     */
    private void setHistogramDockedRight(boolean right) {
        if (histoNoiseGroup == null || rightArea == null || bottomPane == null) {
            return;
        }
        if (right) {
            bottomPane.getChildren().remove(histoNoiseGroup);
            if (!rightArea.getChildren().contains(histoNoiseGroup)) {
                rightArea.getChildren().add(0, histoNoiseGroup); // left of the stage control
            }
            histoNoiseGroup.setMinWidth(220);
            histoNoiseGroup.setPrefWidth(250);
            histogramView.setVertical(true);
        } else {
            rightArea.getChildren().remove(histoNoiseGroup);
            if (!bottomPane.getChildren().contains(histoNoiseGroup)) {
                bottomPane.getChildren().add(0, histoNoiseGroup); // above the status bar
            }
            histoNoiseGroup.setMinWidth(0);
            histoNoiseGroup.setPrefWidth(Region.USE_COMPUTED_SIZE);
            histogramView.setVertical(false);
        }
    }

    private void updateLiveButtonStyle(boolean active) {
        if (active) {
            liveToggleButton.setText("Live: ON");
            liveToggleButton.setStyle("-fx-font-weight: bold; -fx-base: #4CAF50;");
        } else {
            liveToggleButton.setText("Live: OFF");
            liveToggleButton.setStyle("-fx-font-weight: bold; -fx-base: #9E9E9E;");
        }
        updateLiveBorder(active);
    }

    /**
     * Outlines the live-image area with a thick green border while streaming is
     * active so it is obvious at a glance that the viewer is live (vs. showing a
     * stale last frame). Driven from {@link #updateLiveButtonStyle} so the border
     * tracks the same authoritative state as the Live button. Null-guarded
     * because this can be called from the toolbar setup before the image
     * container is built.
     */
    private void updateLiveBorder(boolean active) {
        if (imageContainer == null) {
            return;
        }
        if (active) {
            imageContainer.setStyle(
                    "-fx-background-color: black; -fx-border-color: #4CAF50; -fx-border-width: 3; -fx-border-insets: 0;");
        } else {
            imageContainer.setStyle("-fx-background-color: black;");
        }
    }

    /**
     * Applies the position-overlay label styling, including the font size from
     * the {@code liveViewerPositionFontSize} preference (which defaults to
     * QuPath's viewer location-text size). FX thread only.
     */
    private void applyPositionOverlayStyle() {
        if (positionOverlayLabel == null) {
            return;
        }
        String emSize = QPPreferenceDialog.getLiveViewerPositionFontSize().getFontSize();
        positionOverlayLabel.setStyle("-fx-font-family: monospace; -fx-text-fill: white; "
                + "-fx-background-color: rgba(0,0,0,0.55); -fx-padding: 3 8 3 8; -fx-background-radius: 4; "
                + "-fx-font-size: " + emSize + ";");
    }

    /**
     * Refreshes the XYZ(R) readout from the shared {@link StagePositionManager}
     * cache. R is shown only on rotation scopes. No-op (cheap) when the overlay
     * is hidden. FX thread only.
     */
    private void updatePositionOverlay() {
        if (positionOverlayLabel == null || !positionOverlayLabel.isVisible()) {
            return;
        }
        StagePositionManager mgr = StagePositionManager.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("X %+9.2f  Y %+9.2f  Z %+8.2f", mgr.getX(), mgr.getY(), mgr.getZ()));
        boolean hasR = false;
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            hasR = controller != null && controller.hasRotationStage();
        } catch (Exception ignored) {
            // No rotation info available -- omit R.
        }
        if (hasR) {
            sb.append(String.format("  R %+7.2f", mgr.getR()));
        }
        positionOverlayLabel.setText(sb.toString());
    }

    /**
     * Updates focus button enabled/disabled state and text based on
     * current live mode and running state. Must be called on FX thread.
     */
    private void updateRefineFocusButtonState() {
        if (sweepRunning) {
            // Server-side sweep in flight: button is "Sweeping..." and
            // disabled; leave that state alone until the sweep completes.
            return;
        }
        boolean streamingRunning = streamingFocusController != null && streamingFocusController.isRunning();
        if (streamingRunning) {
            return;
        }
        boolean enabled = liveActive;
        autofocusButton.setText("Autofocus");
        autofocusButton.setStyle("");
        autofocusButton.setDisable(!enabled);
        focusRangeCombo.setDisable(!enabled);
    }

    // handleRefineFocus removed -- Sweep Focus includes Refine as Phase 5

    /**
     * Autofocus button handler. Dispatches to streaming or sweep based on the
     * method selected in the Autofocus Configuration dialog. While a scan is
     * already running, routes to the matching handler so the second click
     * cancels it (not the configured method, which may have since changed).
     */
    private void handleAutofocus() {
        if (sweepRunning) {
            // A sweep is in flight and the button is showing "Cancel
            // Autofocus": a click requests cancellation (ABORTAF).
            cancelSweepFocus();
            return;
        }
        if (streamingFocusController != null && streamingFocusController.isRunning()) {
            handleStreamingFocus();
            return;
        }
        if ("SWEEP".equals(PersistentPreferences.getLiveViewerAutofocusMethod())) {
            handleSweepFocus();
        } else {
            handleStreamingFocus();
        }
    }

    /**
     * Shows a modal dialog when autofocus fails, with a button that opens the
     * Autofocus Configuration dialog so the user can switch methods.
     *
     * @param method "Streaming" or "Sweep" -- the method that just failed
     * @param reason failure detail from the focus controller
     */
    private void showAutofocusFailedDialog(String method, String reason) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
        alert.setTitle("Autofocus failed");
        alert.setHeaderText(method + " autofocus could not find focus.");
        javafx.scene.control.Label msg =
                new javafx.scene.control.Label((reason == null || reason.isBlank() ? "No focus found." : reason)
                        + "\n\nThe stage was left at its current Z. If this autofocus method "
                        + "does not suit this sample or microscope, open Autofocus "
                        + "Configuration and switch methods.");
        msg.setWrapText(true);
        msg.setMaxWidth(420);
        alert.getDialogPane().setContent(msg);

        ButtonType openConfig = new ButtonType("Open Autofocus Configuration");
        alert.getButtonTypes().setAll(openConfig, ButtonType.CLOSE);

        UIFunctions.showAlertOverParent(alert, stage).ifPresent(bt -> {
            if (bt == openConfig) {
                AutofocusEditorWorkflow.run();
            }
        });
    }

    /**
     * Requests cancellation of an in-flight server-side sweep autofocus.
     * Sends ABORTAF on the auxiliary socket (the primary is blocked inside the
     * TESTADAF round-trip); the server's sweep loop polls the per-IP abort
     * event, restores Z to the pre-sweep position, and the TESTADAF call
     * returns a CANCELLED result handled in {@link #handleSweepFocus}.
     */
    private void cancelSweepFocus() {
        autofocusButton.setText("Cancelling...");
        autofocusButton.setDisable(true);
        updateStatus("Sweep Autofocus: cancelling...");
        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || controller.getSocketClient() == null) {
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        controller.getSocketClient().abortStreamingFocus();
                    } catch (IOException e) {
                        logger.warn("Failed to send sweep-AF abort: {}", e.getMessage());
                    }
                },
                "LiveViewer-SweepCancel");
        t.setDaemon(true);
        t.start();
    }

    private void handleSweepFocus() {
        if (sweepRunning) {
            // Already running -- a click here means cancel.
            cancelSweepFocus();
            return;
        }

        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !liveActive) {
            updateStatus("Cannot autofocus: not connected or live not active");
            return;
        }
        if (controller.isAcquisitionActive()) {
            updateStatus("Cannot autofocus: acquisition in progress");
            return;
        }

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.isBlank()) {
            updateStatus("Cannot autofocus: microscope config not set");
            return;
        }
        MicroscopeConfigManager configManager = MicroscopeConfigManager.getInstance(configPath);
        String objective = TestAutofocusWorkflow.getCurrentObjective(configManager);
        if (objective == null) {
            updateStatus("Cannot autofocus: could not determine current objective");
            return;
        }
        String outputPath = TestAutofocusWorkflow.getDefaultOutputPath();

        sweepRunning = true;
        autofocusButton.setText("Cancel Autofocus");
        autofocusButton.setStyle("-fx-base: #E57373;");
        autofocusButton.setTooltip(new Tooltip(
                "Sweep autofocus running on the server. Click to cancel " + "(Z is restored to the start position)."));
        autofocusButton.setDisable(false);
        focusRangeCombo.setDisable(true);
        liveToggleButton.setDisable(true);
        updateStatus("Sweep Autofocus: running... (click Cancel to stop)");

        Thread sweepThread = new Thread(() -> {
            String errorMsg = null;
            try {
                controller.withAllLiveViewingOff(() -> {
                    Map<String, String> result =
                            controller.getSocketClient().testAdaptiveAutofocus(configPath, outputPath, objective);
                    if ("true".equals(result.get("cancelled"))) {
                        // User cancelled; server restored Z. Not a failure.
                        Platform.runLater(() -> updateStatusHeld("Sweep Autofocus cancelled (Z restored)"));
                        return;
                    }
                    String z0 = result.get("initial_z");
                    String z1 = result.get("final_z");
                    String dz = result.get("z_shift");
                    // Record AF result on the Z-bar tic history
                    if (z1 != null) {
                        try {
                            AfHistoryService.add(Double.parseDouble(z1));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    Platform.runLater(() -> updateStatusHeld(
                            String.format("Sweep Autofocus complete: Z shifted %s um (%s -> %s)", dz, z0, z1)));
                });
            } catch (IOException ex) {
                errorMsg = ex.getMessage();
                logger.error("Sweep Autofocus failed: {}", errorMsg, ex);
            } catch (RuntimeException ex) {
                errorMsg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                logger.error("Sweep Autofocus failed: {}", errorMsg, ex);
            }
            final String failure = errorMsg;
            Platform.runLater(() -> {
                sweepRunning = false;
                autofocusButton.setText("Autofocus");
                autofocusButton.setStyle("");
                autofocusButton.setTooltip(new Tooltip(STREAMING_AF_TOOLTIP));
                autofocusButton.setDisable(!liveActive);
                focusRangeCombo.setDisable(!liveActive);
                liveToggleButton.setDisable(false);
                if (stageControlPanel != null) {
                    stageControlPanel.refreshPositions();
                }
                if (failure != null) {
                    showAutofocusFailedDialog("Sweep", failure);
                }
            });
        });
        sweepThread.setDaemon(true);
        sweepThread.setName("LiveViewer-SweepFocus");
        sweepThread.start();
    }

    private void handleStreamingFocus() {
        if (streamingFocusController != null && streamingFocusController.isRunning()) {
            // Second click while a scan is running = cancel. The server
            // stops the scan, restores Z to the pre-scan position, and the
            // STRMAFZ call returns CANCELLED (handled in the callback below).
            streamingFocusController.cancel();
            autofocusButton.setText("Cancelling...");
            autofocusButton.setDisable(true);
            updateStatus("Autofocus: cancelling...");
            return;
        }

        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !liveActive) {
            updateStatus("Cannot autofocus: not connected or live not active");
            return;
        }
        if (controller.isAcquisitionActive()) {
            updateStatus("Cannot autofocus: acquisition in progress");
            return;
        }

        // Note: no client-side exposure pre-check and no auto-fallback to
        // Sweep. The autofocus method is the user's explicit choice (radio
        // in Autofocus Configuration). If streaming refuses (long exposure,
        // saturated, no slow-speed stage, rapid_jump), the server returns
        // UNAVAILABLE and showAutofocusFailedDialog() surfaces the reason
        // with a button to open Autofocus Configuration and switch methods.
        streamingFocusController = new StreamingFocusController(controller.getSocketClient());

        // Pass null for objective: the server resolves via pixel-size
        // match against config.hardware.objectives. This avoids depending
        // on a specific client-side selection state that differs across
        // workflows (Camera Control dialog, Autofocus Editor, acquisition
        // wizard). When the Live Viewer is invoked mid-acquisition we
        // could in theory thread the active objective through, but that
        // plumbing does not exist yet and the server auto-detect is
        // accurate enough for the streaming AF use case.
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

        // Read the focus range dropdown value. "Config" -> NaN -> server
        // uses sweep_range_um from the yaml. Any explicit "Num_um" value
        // overrides. This gives the user a quick way to widen the scan
        // window when they suspect they are far from focus (the default
        // sweep_range_um=6 is tuned for small drift corrections, not
        // initial acquisition from scratch).
        double streamingRangeOverride = Double.NaN;
        String rangeSelection = focusRangeCombo.getValue();
        if (rangeSelection != null && rangeSelection.endsWith("um")) {
            try {
                streamingRangeOverride = Double.parseDouble(rangeSelection.replace("um", ""));
            } catch (NumberFormatException ignored) {
            }
        }

        // The Autofocus button stays ENABLED during the scan and becomes a
        // Cancel button -- a second click routes to the cancel branch at the
        // top of handleStreamingFocus(). Sweep / range / live stay disabled.
        autofocusButton.setText("Cancel Autofocus");
        autofocusButton.setStyle("");
        autofocusButton.setTooltip(new Tooltip("Click to cancel the running autofocus scan.\n"
                + "Z is restored to the position autofocus started from."));
        autofocusButton.setDisable(false);

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
                    if (stageControlPanel != null) {
                        stageControlPanel.refreshPositions();
                    }
                    // Streaming AF success leaves the stage at the chosen focus
                    // plane. Record it on the Z-bar tic history. Read Z off the
                    // FX thread to avoid blocking a socket call here.
                    if (outcome == RefineFocusController.Outcome.SUCCESS) {
                        Thread t = new Thread(
                                () -> {
                                    try {
                                        double z = MicroscopeController.getInstance()
                                                .getStageZFast();
                                        AfHistoryService.add(z);
                                    } catch (Exception ignored) {
                                    }
                                },
                                "LiveViewer-AfHistory");
                        t.setDaemon(true);
                        t.start();
                    }
                    autofocusButton.setText("Autofocus");
                    autofocusButton.setStyle("");
                    autofocusButton.setTooltip(new Tooltip(STREAMING_AF_TOOLTIP));
                    autofocusButton.setDisable(!liveActive);

                    // FAILED (UNAVAILABLE / pre-flight refusal) and ERROR both
                    // leave the stage where it is. No auto-fallback to Sweep --
                    // the method is the user's explicit choice in Autofocus
                    // Configuration. Surface the reason and offer to open that
                    // dialog so the user can switch methods. CANCELLED and
                    // SUCCESS need no popup.
                    if (outcome == RefineFocusController.Outcome.FAILED
                            || outcome == RefineFocusController.Outcome.ERROR) {
                        String reason = msg;
                        if (reason != null && reason.startsWith("Autofocus unavailable: ")) {
                            reason = reason.substring("Autofocus unavailable: ".length());
                        }
                        showAutofocusFailedDialog("Streaming", reason);
                    }
                }
            });
        };

        final double rangeOverride = streamingRangeOverride;
        Thread streamingThread = new Thread(
                () -> streamingFocusController.execute(objectiveParam, modalityParam, rangeOverride, callback));
        streamingThread.setDaemon(true);
        streamingThread.setName("LiveViewer-StreamingFocus");
        streamingThread.start();
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
                checkDesyncAndRecover(controller);
                return;
            }

            // Frame arrived -- track arrival time and reset recovery counter
            lastFrame = frame;
            if (!hasFreshFrame.get()) {
                Platform.runLater(() -> hasFreshFrame.set(true));
            }
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

            // Focus-metric trace: compute per-frame Brenner gradient, pair with
            // the current polled Z, and push to the ZBarPanel's trace model.
            // Cleared on > 1 um XY moves by StageControlPanel.onPositionChanged.
            histExec.submit(() -> {
                try {
                    if (stageControlPanel == null || stageControlPanel.getZBarPanel() == null) return;
                    double metric = qupath.ext.qpsc.utilities.FocusMetricCalculator.brennerGradient(frame);
                    double z = MicroscopeController.getInstance().getStageZFast();
                    stageControlPanel.getZBarPanel().getFocusTrace().addSample(z, metric);
                } catch (Exception e) {
                    logger.debug("Focus-metric update failed: {}", e.getMessage());
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
            // Socket timeouts (e.g. during stage-move storms) raise here without
            // ever entering the frame==null branch, so the poller would show
            // "Live: ON" forever while frames never actually arrived. Run the
            // same desync check on exception paths so auto-recovery fires if
            // enough consecutive polls fail.
            MicroscopeController c = MicroscopeController.getInstance();
            if (c != null) {
                checkDesyncAndRecover(c);
            }
        } catch (Exception e) {
            logger.error("Unexpected error in frame poll", e);
        }
    }

    /**
     * Shared desync check used by both the "frame == null" branch of
     * {@link #pollFrame()} and its IOException catch block. Fires
     * auto-recovery if frames have been absent for longer than
     * {@link #NO_FRAME_TIMEOUT_MS}, after a {@link #GRACE_PERIOD_MS}
     * startup grace period. Escalates to forcing the Live toggle OFF
     * once {@link #MAX_RECOVERY_ATTEMPTS} have been exhausted so the
     * button state stops lying about an effectively-dead stream.
     *
     * <p>Before this helper existed, exception paths (socket read
     * timeouts during stage-move storms) would keep the button green
     * and the status "Live ON - streaming" even though no frames had
     * arrived in 30+ seconds -- the user had to toggle manually to
     * resync. Both no-frame and exception paths now funnel through
     * this single check.
     */
    private void checkDesyncAndRecover(MicroscopeController controller) {
        // Guard against stale state: when live is off, or when the arrival
        // timestamp has been reset to 0, skip entirely. Without this, the
        // IOException catch in pollFrame() drives this method regardless of
        // liveActive, and sinceLast collapses to (now - 0) = raw epoch ms,
        // producing "No frames for 1776...ms" spam every 100 ms during
        // acquisition-induced aux socket contention.
        if (!liveActive || lastFrameArrivalTime == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - liveOnTimestamp;
        long sinceLast = now - lastFrameArrivalTime;
        if (elapsed <= GRACE_PERIOD_MS || sinceLast <= NO_FRAME_TIMEOUT_MS || recoveryInProgress) {
            return;
        }
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
            // Recovery exhausted -- turn off so the button stops lying
            logger.warn("No frames for {}ms, recovery exhausted -- turning live OFF", sinceLast);
            liveActive = false;
            liveActiveProperty.set(false);
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
                                currentPos[0],
                                currentPos[1],
                                offsetPixelsX,
                                offsetPixelsY,
                                pixelSizeX_um,
                                pixelSizeY_um);
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

    // ---- FoV indicator and objective overlay ----

    /** Overlay canvas for drawing objective FoV rectangles on top of the image. */
    private Canvas fovOverlayCanvas;
    /** Whether the FoV overlay is currently visible. */
    private boolean fovOverlayVisible = false;

    /**
     * Repopulates the streaming-AF range dropdown based on the current objective.
     *
     * <p>Higher magnifications have shallower depth-of-field, so wide search ranges
     * waste time and may misfocus on debris far from the tissue plane. This caps
     * the offered options by magnification:
     * <ul>
     *   <li>4x/5x: up to 200 um</li>
     *   <li>10x: up to 100 um</li>
     *   <li>20x: up to 50 um</li>
     *   <li>40x: up to 20 um</li>
     *   <li>60x or higher: up to 10 um</li>
     *   <li>Unknown objective: conservative default (10-30 um)</li>
     * </ul>
     *
     * <p>"Config" always remains as the first option and defers to the per-objective
     * {@code sweep_range_um} field in {@code autofocus_<scope>.yml}. Sweep Autofocus
     * always reads from the YAML regardless of dropdown selection; only Streaming AF
     * honors the explicit um overrides. The previously selected value is preserved
     * when still valid; otherwise the dropdown falls back to "Config".
     */
    private void updateFocusRangeOptions() {
        if (focusRangeCombo == null) return;
        String objId = stageControlPanel != null ? stageControlPanel.getCurrentObjectiveId() : null;
        String previousSelection = focusRangeCombo.getValue();

        List<String> options = new ArrayList<>();
        options.add("Config");
        int[] umOptions = focusRangeOptionsForObjective(objId);
        for (int um : umOptions) {
            options.add(um + "um");
        }

        focusRangeCombo.getItems().setAll(options);
        // Migrate any persisted "Auto" selection from older builds to "Config".
        String migrated = "Auto".equals(previousSelection) ? "Config" : previousSelection;
        if (migrated != null && options.contains(migrated)) {
            focusRangeCombo.setValue(migrated);
        } else {
            focusRangeCombo.setValue("Config");
        }
    }

    /**
     * Returns the magnification-appropriate range options (in um) for an objective.
     * Excludes "Config"; caller prepends it. Conservative fallback when magnification
     * cannot be parsed.
     */
    private static int[] focusRangeOptionsForObjective(String objId) {
        String magStr = objId == null ? null : ObjectiveUtils.extractMagnification(objId);
        int mag = 0;
        if (magStr != null && magStr.endsWith("x")) {
            try {
                mag = Integer.parseInt(magStr.substring(0, magStr.length() - 1));
            } catch (NumberFormatException ignore) {
                // mag stays 0 -> falls into the unknown bucket below
            }
        }
        if (mag <= 0) {
            return new int[] {10, 20, 30};
        }
        if (mag <= 5) {
            return new int[] {20, 50, 100, 150, 200};
        }
        if (mag <= 10) {
            return new int[] {10, 20, 30, 50, 75, 100};
        }
        if (mag <= 20) {
            return new int[] {6, 10, 15, 20, 30, 50};
        }
        if (mag <= 40) {
            return new int[] {4, 6, 10, 15, 20};
        }
        return new int[] {3, 5, 8, 10};
    }

    /**
     * Updates the FoV size label in the status bar using the current objective/detector.
     * Shows the field of view in microns. Turns RED if pixel size is unavailable.
     */
    private void updateFovLabel() {
        if (fovLabel == null) return;
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr == null) {
                fovLabel.setText("FoV: no config");
                fovLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11; "
                        + "-fx-font-weight: bold; -fx-text-fill: red;");
                return;
            }
            String objId = stageControlPanel != null ? stageControlPanel.getCurrentObjectiveId() : null;
            String detId = stageControlPanel != null ? stageControlPanel.getCurrentDetectorId() : null;
            if (objId == null || "Unknown".equals(objId) || detId == null || "Unknown".equals(detId)) {
                fovLabel.setText("FoV: unknown obj");
                fovLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11; "
                        + "-fx-font-weight: bold; -fx-text-fill: red;");
                return;
            }
            Double pixelSize = mgr.getHardwarePixelSize(objId, detId);
            int[] dims = mgr.getDetectorDimensions(detId);
            if (pixelSize == null || pixelSize <= 0 || dims == null) {
                fovLabel.setText("FoV: no pixel size");
                fovLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11; "
                        + "-fx-font-weight: bold; -fx-text-fill: red;");
                return;
            }
            double fovW = dims[0] * pixelSize;
            double fovH = dims[1] * pixelSize;
            fovLabel.setText(String.format("FoV: %.0f x %.0f um", fovW, fovH));
            fovLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11; -fx-font-weight: bold;");
        } catch (Exception e) {
            fovLabel.setText("FoV: error");
            fovLabel.setStyle(
                    "-fx-font-family: monospace; -fx-font-size: 11; " + "-fx-font-weight: bold; -fx-text-fill: red;");
        }
    }

    /**
     * Sets the window title to reflect the current modality and the expected
     * objective magnification, e.g. {@code "Live Viewer (Brightfield) (10x)"}.
     *
     * <p>Driven by the Camera-tab modality dropdown and the detected objective
     * (see {@link StageControlPanel#setOnModalityChanged} /
     * {@link StageControlPanel#setOnHardwareChanged}). Each part is omitted when
     * its value is unknown, so the title degrades gracefully to "Live Viewer".
     */
    private void updateWindowTitle() {
        if (stage == null) return;
        String modality = stageControlPanel != null ? stageControlPanel.getCurrentCameraModality() : null;
        String objId = stageControlPanel != null ? stageControlPanel.getCurrentObjectiveId() : null;

        StringBuilder sb = new StringBuilder("Live Viewer");
        String modalityName = prettyModality(modality);
        if (modalityName != null) {
            sb.append(" (").append(modalityName).append(')');
        }
        String mag = ObjectiveUtils.extractMagnification(objId);
        if (mag != null && !mag.isBlank()) {
            sb.append(" (").append(mag).append(')');
        }
        String title = sb.toString();

        if (Platform.isFxApplicationThread()) {
            stage.setTitle(title);
        } else {
            Platform.runLater(() -> stage.setTitle(title));
        }
    }

    /**
     * Resolves a raw modality name (e.g. {@code "bf"}, {@code "ppm"}) to a
     * human-readable display name via the modality handler registry. Falls back
     * to a capitalized form of the raw name when no handler exposes one, and
     * returns {@code null} for a null/blank modality.
     */
    private static String prettyModality(String modality) {
        if (modality == null || modality.isBlank()) return null;
        String display = ModalityRegistry.getHandler(modality).getDisplayName();
        if (display != null && !display.isBlank()) return display;
        return Character.toUpperCase(modality.charAt(0)) + modality.substring(1);
    }

    /**
     * Toggles the objective FoV overlay on/off. When enabled, draws a centered
     * rectangle for each objective's FoV on the live image, color-coded and labeled.
     */
    private void toggleFovOverlay() {
        fovOverlayVisible = !fovOverlayVisible;
        if (fovOverlayVisible) {
            drawFovOverlay();
        } else {
            clearFovOverlay();
        }
    }

    /**
     * Clears the FoV overlay canvas.
     */
    private void clearFovOverlay() {
        if (fovOverlayCanvas != null) {
            GraphicsContext gc = fovOverlayCanvas.getGraphicsContext2D();
            gc.clearRect(0, 0, fovOverlayCanvas.getWidth(), fovOverlayCanvas.getHeight());
        }
    }

    /**
     * Standard hue-separated colors for up to 12 objectives.
     * Uses saturated, bright colors that are visible on both brightfield and
     * dark IF backgrounds.
     */
    private static final Color[] OBJECTIVE_COLORS = {
        Color.rgb(255, 50, 50), // red
        Color.rgb(50, 220, 50), // green
        Color.rgb(80, 130, 255), // blue
        Color.rgb(255, 200, 30), // yellow
        Color.rgb(255, 50, 255), // magenta
        Color.rgb(50, 230, 230), // cyan
        Color.rgb(255, 140, 30), // orange
        Color.rgb(160, 80, 255), // purple
        Color.rgb(50, 255, 140), // spring green
        Color.rgb(255, 80, 160), // pink
        Color.rgb(180, 210, 40), // lime
        Color.rgb(100, 200, 255), // sky blue
    };

    /**
     * Draws FoV rectangles for all configured objectives, centered on the image.
     * Each rectangle shows the relative FoV size of that objective compared to
     * the current one and is labeled with the objective ID.
     */
    private void drawFovOverlay() {
        if (fovOverlayCanvas == null || imageView == null) return;

        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) return;

        String currentObjId = stageControlPanel != null ? stageControlPanel.getCurrentObjectiveId() : null;
        String currentDetId = stageControlPanel != null ? stageControlPanel.getCurrentDetectorId() : null;
        if (currentDetId == null || "Unknown".equals(currentDetId)) return;
        if (currentObjId == null || "Unknown".equals(currentObjId)) return;

        int[] dims = mgr.getDetectorDimensions(currentDetId);
        if (dims == null) return;
        int sensorW = dims[0];
        int sensorH = dims[1];

        // Current objective's FoV in microns -- this defines the image edges
        Double currentPx = mgr.getHardwarePixelSize(currentObjId, currentDetId);
        if (currentPx == null || currentPx <= 0) return;
        double currentFovW = sensorW * currentPx;
        double currentFovH = sensorH * currentPx;

        // Get all objectives
        Set<String> objectives = mgr.getAvailableObjectives();
        if (objectives == null || objectives.isEmpty()) return;

        // Collect per-objective FoV in microns
        List<String> objIds = new ArrayList<>(objectives);
        List<double[]> fovs = new ArrayList<>();
        for (String obj : objIds) {
            Double px = mgr.getHardwarePixelSize(obj, currentDetId);
            if (px != null && px > 0) {
                fovs.add(new double[] {sensorW * px, sensorH * px});
            } else {
                fovs.add(null);
            }
        }

        // Size the canvas to match the imageView's rendered size
        double canvasW = imageView.getBoundsInParent().getWidth();
        double canvasH = imageView.getBoundsInParent().getHeight();
        fovOverlayCanvas.setWidth(canvasW);
        fovOverlayCanvas.setHeight(canvasH);

        GraphicsContext gc = fovOverlayCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvasW, canvasH);

        // Scale: the CURRENT objective's FoV maps exactly to the canvas edges.
        // Other objectives scale proportionally -- lower mag objectives extend
        // beyond the canvas (clipped), higher mag sit inside.
        double scaleX = canvasW / currentFovW;
        double scaleY = canvasH / currentFovH;

        double cx = canvasW / 2.0;
        double cy = canvasH / 2.0;

        // Draw from largest to smallest so smaller FoVs are on top
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < objIds.size(); i++) indices.add(i);
        indices.sort((a, b) -> {
            double areaA = fovs.get(a) != null ? fovs.get(a)[0] * fovs.get(a)[1] : 0;
            double areaB = fovs.get(b) != null ? fovs.get(b)[0] * fovs.get(b)[1] : 0;
            return Double.compare(areaB, areaA); // largest first
        });

        for (int idx : indices) {
            double[] fov = fovs.get(idx);
            if (fov == null) continue;

            String objId = objIds.get(idx);
            boolean isCurrent = objId.equals(currentObjId);
            Color color = OBJECTIVE_COLORS[idx % OBJECTIVE_COLORS.length];

            double rectW = fov[0] * scaleX;
            double rectH = fov[1] * scaleY;
            // Line thickness = 2% of this FoV's shorter screen dimension
            double lineWidth = Math.max(1.5, Math.min(rectW, rectH) * 0.02);
            double x = cx - rectW / 2.0;
            double y = cy - rectH / 2.0;

            gc.setStroke(color);
            gc.setLineWidth(isCurrent ? lineWidth * 1.5 : lineWidth);
            if (isCurrent) {
                // Dashed outline for the current objective to distinguish it
                gc.setLineDashes(8, 4);
            } else {
                gc.setLineDashes(null);
            }
            gc.strokeRect(x, y, rectW, rectH);
            gc.setLineDashes(null); // reset

            // Label -- place inside the rectangle if it fits, otherwise outside
            gc.setFill(color);
            double fontSize = Math.max(10, Math.min(14, lineWidth * 5));
            gc.setFont(javafx.scene.text.Font.font("SansSerif", javafx.scene.text.FontWeight.BOLD, fontSize));
            String label = isCurrent
                    ? String.format("[%s] %.0f x %.0f um", objId, fov[0], fov[1])
                    : String.format("%s  %.0f x %.0f um", objId, fov[0], fov[1]);
            double labelX = Math.max(2, x + lineWidth + 2);
            double labelY = Math.max(fontSize + 2, y + lineWidth + fontSize + 2);
            gc.fillText(label, labelX, labelY);
        }
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

        // Detach the central-state title listener so a closed window doesn't
        // retain a reference and keep getting modality updates.
        if (modalityTitleListener != null) {
            ModalityState.getInstance().modalityProperty().removeListener(modalityTitleListener);
            modalityTitleListener = null;
        }

        // Detach the position-overlay listeners (releases the StagePositionManager
        // reference so its poller can stop when no one else is listening).
        if (positionListener != null) {
            StagePositionManager.getInstance().removePropertyChangeListener(positionListener);
            positionListener = null;
        }
        if (positionFontSizeListener != null) {
            QPPreferenceDialog.liveViewerPositionFontSizeProperty().removeListener(positionFontSizeListener);
            positionFontSizeListener = null;
        }

        // Stop polling threads
        if (framePoller != null) {
            framePoller.shutdownNow();
            framePoller = null;
        }
        if (histogramExecutor != null) {
            histogramExecutor.shutdownNow();
            histogramExecutor = null;
        }
        if (snapIoExecutor != null) {
            snapIoExecutor.shutdown();
            snapIoExecutor = null;
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
            liveActiveProperty.set(false);
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
