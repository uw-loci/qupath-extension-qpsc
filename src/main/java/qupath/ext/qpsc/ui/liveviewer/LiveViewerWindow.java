package qupath.ext.qpsc.ui.liveviewer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.lib.gui.QuPathGUI;

import qupath.ext.qpsc.preferences.PersistentPreferences;

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
    private Button liveToggleButton;
    private HistogramView histogramView;
    private TitledPane histogramPane;
    private NoiseStatsPanel noiseStatsPanel;
    private StageControlPanel stageControlPanel;
    private final ContrastSettings contrastSettings = new ContrastSettings();

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

    // Display mode state
    // fitToContainer = true: image scales to fill container (no scrollbars)
    // fitToContainer = false: image renders at explicitScale, scrollbars appear as needed
    private volatile boolean fitToContainer = true;
    private volatile double explicitScale = 1.0;
    private ScrollPane scrollPane;  // Store reference for mode switching
    private StackPane imageContainer;  // Inner container for centering

    // Configuration
    private static final long POLL_INTERVAL_MS = 100;  // ~10 FPS max
    private static final double WINDOW_WIDTH = 900;   // Wider to accommodate side panel
    private static final double WINDOW_HEIGHT = 720;

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

        // Toolbar with Live toggle button and display scale selector
        liveToggleButton = new Button("Live: OFF");
        liveToggleButton.setStyle("-fx-font-weight: bold;");
        updateLiveButtonStyle(false);
        liveToggleButton.setOnAction(e -> toggleLiveMode());

        // Display scale selector
        Label scaleLabel = new Label("Display:");
        ComboBox<String> scaleCombo = new ComboBox<>();
        scaleCombo.getItems().addAll("Fit", "25%", "50%", "100%");
        scaleCombo.setValue("Fit");  // Default to Fit mode
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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8, liveToggleButton, spacer, scaleLabel, scaleCombo);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Image display
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);  // Nearest-neighbor for microscopy

        // Double-click-to-center: click on image to move stage so that point becomes center
        imageView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                handleDoubleClickToCenter(event);
            }
        });

        // Container with scroll support for large images at explicit scale
        scrollPane = new ScrollPane();
        scrollPane.setStyle("-fx-background-color: black;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true);  // Allow drag-to-pan

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
        stageControlPanel.setExpanded(true);  // Start expanded so user sees controls

        // Wrap in ScrollPane to handle overflow when window is short
        ScrollPane stageScrollPane = new ScrollPane(stageControlPanel);
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

        // Status bar
        statusLabel = new Label("Ready - press Live to start");
        statusLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(4));
        statusBar.setAlignment(Pos.CENTER_LEFT);

        // Bottom pane: Histogram, Noise Stats, Status Bar
        VBox bottomPane = new VBox(histogramPane, noiseStatsPanel, statusBar);

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setRight(stageScrollPane);  // Stage control on right side
        root.setCenter(scrollPane);      // Live image in center
        root.setBottom(bottomPane);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
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
        Thread toggleThread = new Thread(() -> {
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
                    logger.info("Continuous acquisition toggled to: {}", newState ? "ON" : "OFF");

                    if (newState) {
                        updateStatus("Live ON - streaming...");
                        // Reset FPS counter on start
                        frameCount.set(0);
                        fpsWindowStart.set(System.currentTimeMillis());
                        currentFps = 0;
                    } else {
                        updateStatus("Live OFF");
                    }
                });
            } catch (IOException e) {
                logger.error("Failed to toggle continuous acquisition: {}", e.getMessage());
                Platform.runLater(() -> {
                    liveToggleButton.setDisable(false);
                    updateStatus("Error: " + e.getMessage());
                });
            }
        }, "LiveViewer-Toggle");
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

            // Update noise stats (throttled internally to ~2Hz)
            if (noiseStatsPanel.isExpanded()) {
                histogramExecutor.submit(() -> {
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
        writableImage.getPixelWriter().setPixels(
                0, 0, dstW, dstH,
                PixelFormat.getIntArgbInstance(),
                argbBuffer, 0, dstW
        );

        updateStatus(lastFrameInfo);
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

        // Capture sample movement mode setting
        boolean sampleMode = PersistentPreferences.getStageControlSampleMovement();

        // Final variables for use in background thread
        final int finalSrcW = srcW;
        final int finalSrcH = srcH;

        updateStatus("Moving stage...");

        // Run socket operations on background thread to avoid blocking FX thread
        Thread moveThread = new Thread(() -> {
            try {
                // Get FOV for pixel-to-micron conversion
                double[] fov = controller.getCameraFOV();
                if (fov[0] <= 0 || fov[1] <= 0) {
                    Platform.runLater(() -> updateStatus("Camera FOV not available"));
                    return;
                }

                // Convert to microns using pixel size
                double pixelSizeX_um = fov[0] / finalSrcW;
                double pixelSizeY_um = fov[1] / finalSrcH;
                double offsetUm_X = offsetPixelsX * pixelSizeX_um;
                double offsetUm_Y = offsetPixelsY * pixelSizeY_um;

                // Get current stage position
                double[] currentPos = controller.getStagePositionXY();

                // Apply sample movement mode (same logic as StageControlPanel)
                // Default: Match MicroManager - clicking right of center should decrease X
                // Sample mode: Invert X axis only
                double xMult = sampleMode ? 1.0 : -1.0;  // Sample: X increases; Default: X decreases
                double yMult = 1.0;  // Always: clicking below center increases Y (matches MicroManager)

                double newX = currentPos[0] + (offsetUm_X * xMult);
                double newY = currentPos[1] + (offsetUm_Y * yMult);

                // Move stage (bounds checking handled by controller)
                controller.moveStageXY(newX, newY);

                Platform.runLater(() -> updateStatus(String.format("Centered on (%.0f, %.0f)", newX, newY)));
                logger.info("Double-click-to-center: offset ({}, {}) um -> ({}, {})",
                        String.format("%.1f", offsetUm_X), String.format("%.1f", offsetUm_Y),
                        String.format("%.1f", newX), String.format("%.1f", newY));

            } catch (IOException e) {
                logger.warn("Failed to center on click: {}", e.getMessage());
                Platform.runLater(() -> updateStatus("Error: " + e.getMessage()));
            }
        }, "LiveViewer-ClickToCenter");
        moveThread.setDaemon(true);
        moveThread.start();
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
