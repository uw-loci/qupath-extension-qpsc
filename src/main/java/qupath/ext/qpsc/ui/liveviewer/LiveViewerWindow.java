package qupath.ext.qpsc.ui.liveviewer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.lib.gui.QuPathGUI;

import java.io.IOException;
import java.nio.IntBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private HistogramView histogramView;
    private final ContrastSettings contrastSettings = new ContrastSettings();

    // Frame rendering state
    private WritableImage writableImage;
    private int[] argbBuffer;
    private int lastFrameWidth = 0;
    private int lastFrameHeight = 0;

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

    // Configuration
    private static final long POLL_INTERVAL_MS = 100;  // ~10 FPS max
    private static final double WINDOW_WIDTH = 520;
    private static final double WINDOW_HEIGHT = 600;

    private LiveViewerWindow() {
        buildUI();
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

    private void buildUI() {
        stage = new Stage();
        stage.setTitle("Live Viewer");
        stage.initModality(Modality.NONE);

        // Set owner to QuPath main window so it stays above
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            stage.initOwner(gui.getStage());
        }

        // Image display
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);  // Nearest-neighbor for microscopy

        // Bind image size to container
        BorderPane imageContainer = new BorderPane(imageView);
        imageContainer.setStyle("-fx-background-color: black;");
        imageView.fitWidthProperty().bind(imageContainer.widthProperty());
        imageView.fitHeightProperty().bind(imageContainer.heightProperty().subtract(200));

        // Histogram + contrast controls
        histogramView = new HistogramView(contrastSettings);

        // Status bar
        statusLabel = new Label("Connecting...");
        statusLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(4));
        statusBar.setAlignment(Pos.CENTER_LEFT);

        // Layout
        VBox bottomPane = new VBox(histogramView, statusBar);

        BorderPane root = new BorderPane();
        root.setCenter(imageContainer);
        root.setBottom(bottomPane);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        stage.setScene(scene);
        stage.setMinWidth(320);
        stage.setMinHeight(400);

        // Clean shutdown on close
        stage.setOnCloseRequest(e -> stopAndDispose());
    }

    private void startLiveViewing() {
        if (polling) return;
        polling = true;

        // Start live mode on the microscope
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller == null) {
                updateStatus("Not connected to microscope");
                return;
            }
            controller.setLiveMode(true);
            logger.info("Live mode started for live viewer");
        } catch (IOException e) {
            logger.error("Failed to start live mode: {}", e.getMessage());
            updateStatus("Failed to start live mode: " + e.getMessage());
            polling = false;
            return;
        }

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
        logger.info("Live viewer frame polling started");
    }

    private void pollFrame() {
        if (!polling) return;

        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller == null) {
                Platform.runLater(() -> updateStatus("Disconnected"));
                return;
            }

            FrameData frame = controller.getFrame();
            if (frame == null) {
                // No frame available yet - camera may still be starting
                return;
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
            lastFrameInfo = String.format("FPS: %.1f | %dx%d | %s %s",
                    currentFps, frame.width(), frame.height(), colorMode, bitDepth);

            // Submit histogram computation (throttled internally)
            histogramExecutor.submit(() -> {
                try {
                    Platform.runLater(() -> histogramView.updateHistogram(frame));
                } catch (Exception e) {
                    logger.debug("Histogram update failed: {}", e.getMessage());
                }
            });

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
        int w = frame.width();
        int h = frame.height();

        // Recreate WritableImage if dimensions changed
        if (w != lastFrameWidth || h != lastFrameHeight) {
            writableImage = new WritableImage(w, h);
            argbBuffer = new int[w * h];
            lastFrameWidth = w;
            lastFrameHeight = h;
            imageView.setImage(writableImage);

            // Apply full range for new dimensions/bit depth
            contrastSettings.applyFullRange(frame);
        }

        // Apply contrast mapping and convert to ARGB
        int min = contrastSettings.getDisplayMin();
        int max = contrastSettings.getDisplayMax();
        int range = Math.max(1, max - min);

        byte[] raw = frame.rawPixels();
        int bpp = frame.bytesPerPixel();
        int channels = frame.channels();

        if (channels == 1) {
            // Grayscale
            for (int i = 0; i < w * h; i++) {
                int val;
                if (bpp == 1) {
                    val = raw[i] & 0xFF;
                } else {
                    int offset = i * 2;
                    val = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
                }
                int mapped = clamp((val - min) * 255 / range, 0, 255);
                argbBuffer[i] = 0xFF000000 | (mapped << 16) | (mapped << 8) | mapped;
            }
        } else {
            // RGB
            int bytesPerSample = bpp;
            int stride = channels * bytesPerSample;
            for (int i = 0; i < w * h; i++) {
                int offset = i * stride;
                int r, g, b;
                if (bpp == 1) {
                    r = raw[offset] & 0xFF;
                    g = raw[offset + 1] & 0xFF;
                    b = raw[offset + 2] & 0xFF;
                } else {
                    r = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
                    g = ((raw[offset + 2] & 0xFF) << 8) | (raw[offset + 3] & 0xFF);
                    b = ((raw[offset + 4] & 0xFF) << 8) | (raw[offset + 5] & 0xFF);
                }
                r = clamp((r - min) * 255 / range, 0, 255);
                g = clamp((g - min) * 255 / range, 0, 255);
                b = clamp((b - min) * 255 / range, 0, 255);
                argbBuffer[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        // Batch write to WritableImage
        writableImage.getPixelWriter().setPixels(
                0, 0, w, h,
                PixelFormat.getIntArgbInstance(),
                argbBuffer, 0, w
        );

        updateStatus(lastFrameInfo);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateStatus(String text) {
        statusLabel.setText(text);
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

        // Stop live mode
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller != null) {
                controller.setLiveMode(false);
                logger.info("Live mode stopped");
            }
        } catch (IOException e) {
            logger.warn("Failed to stop live mode: {}", e.getMessage());
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
