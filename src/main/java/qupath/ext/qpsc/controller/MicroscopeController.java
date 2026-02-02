package qupath.ext.qpsc.controller;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.TransformationFunctions;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

/**
 * Central controller for microscope operations, providing high-level methods
 * for stage control and coordinate transformation via socket communication only.
 *
 * <p>This controller acts as a facade for the microscope hardware, managing:
 * <ul>
 *   <li>Socket-based communication with the Python microscope server</li>
 *   <li>Coordinate transformations between QuPath and stage coordinates</li>
 *   <li>Stage movement and position queries</li>
 *   <li>Error handling and user notifications</li>
 * </ul>
 *
 * <p>The controller uses a singleton pattern to ensure a single connection
 * to the microscope server throughout the application lifecycle.</p>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class MicroscopeController {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeController.class);

    /** Singleton instance */
    private static MicroscopeController instance;

    /** Socket client for server communication */
    private final MicroscopeSocketClient socketClient;

    /** Current affine transform for coordinate conversion */
    private final AtomicReference<AffineTransform> currentTransform = new AtomicReference<>();

    /**
     * Private constructor for singleton pattern.
     * Initializes the socket connection to the microscope server.
     */
    private MicroscopeController() {
        // Get connection parameters from preferences
        String host = QPPreferenceDialog.getMicroscopeServerHost();
        int port = QPPreferenceDialog.getMicroscopeServerPort();
        boolean autoConnect = QPPreferenceDialog.getAutoConnectToServer();

        // Get advanced settings from persistent preferences
        int connectTimeout = PersistentPreferences.getSocketConnectionTimeoutMs();
        int readTimeout = PersistentPreferences.getSocketReadTimeoutMs();
        int maxReconnects = PersistentPreferences.getSocketMaxReconnectAttempts();
        long reconnectDelay = PersistentPreferences.getSocketReconnectDelayMs();
        long healthCheckInterval = PersistentPreferences.getSocketHealthCheckIntervalMs();

        // Initialize socket client with configuration from preferences
        this.socketClient = new MicroscopeSocketClient(
                host,
                port,
                connectTimeout,
                readTimeout,
                maxReconnects,
                reconnectDelay,
                healthCheckInterval
        );

        // Attempt initial connection if auto-connect is enabled
        if (autoConnect) {
            try {
                socketClient.connect();
                logger.info("Successfully connected to microscope server at {}:{}", host, port);
                PersistentPreferences.setSocketLastConnectionStatus("Connected");
                PersistentPreferences.setSocketLastConnectionTime(
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())
                );
            } catch (IOException e) {
                String errorMsg = e.getMessage();

                // Provide specific guidance based on error type
                if (errorMsg != null && errorMsg.contains("config file path not set")) {
                    logger.warn("Cannot connect: Microscope config file not set in preferences");
                    logger.warn("Go to Edit > Preferences > QPSC to set the configuration file path");
                    PersistentPreferences.setSocketLastConnectionStatus("Config Not Set - See Preferences");
                } else if (errorMsg != null && errorMsg.contains("connection blocked")) {
                    logger.warn("Cannot connect: Another QuPath instance is already connected to the server");
                    logger.warn("Only one connection allowed at a time for safety");
                    PersistentPreferences.setSocketLastConnectionStatus("Blocked - Another Connection Active");
                } else if (errorMsg != null && errorMsg.contains("Failed to load config")) {
                    logger.warn("Cannot connect: Server failed to load config - {}", errorMsg);
                    logger.warn("Check that the config file path is correct and file is valid");
                    PersistentPreferences.setSocketLastConnectionStatus("Config Load Failed");
                } else {
                    logger.warn("Failed to connect to microscope server on startup: {}", errorMsg);
                    logger.info("Will attempt to connect when first command is sent");
                    PersistentPreferences.setSocketLastConnectionStatus("Failed: " + errorMsg);
                }
            }
        }

        // Register shutdown hook to cleanly disconnect
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                socketClient.close();
            } catch (Exception e) {
                logger.debug("Error closing socket client during shutdown", e);
            }
        }));
    }

    /**
     * Gets the singleton instance of the MicroscopeController.
     *
     * @return The singleton instance
     */
    public static synchronized MicroscopeController getInstance() {
        if (instance == null) {
            instance = new MicroscopeController();
        }
        return instance;
    }

    /**
     * Queries the microscope for its current X,Y stage position.
     *
     * @return A two-element array [x, y] in microns
     * @throws IOException if communication fails
     */
    public double[] getStagePositionXY() throws IOException {
        try {
            double[] position = socketClient.getStageXY();
            logger.trace("Stage XY position: ({}, {})", position[0], position[1]);
            return position;
        } catch (IOException e) {
            logger.error("Failed to get stage XY position: {}", e.getMessage());
            throw new IOException("Failed to get stage XY position via socket", e);
        }
    }

    /**
     * Queries the microscope for its current Z stage position.
     *
     * @return The Z coordinate in microns
     * @throws IOException if communication fails
     */
    public double getStagePositionZ() throws IOException {
        try {
            double z = socketClient.getStageZ();
            logger.trace("Stage Z position: {}", z);
            return z;
        } catch (IOException e) {
            logger.error("Failed to get stage Z position: {}", e.getMessage());
            throw new IOException("Failed to get stage Z position via socket", e);
        }
    }

    /**
     * Queries the current rotation angle (in ticks) from the stage.
     *
     * @return The rotation angle in ticks
     * @throws IOException if communication fails
     */
    public double getStagePositionR() throws IOException {
        try {
            double angle = socketClient.getStageR();
            logger.info("Stage rotation angle: {} ticks", angle);
            return angle;
        } catch (IOException e) {
            logger.error("Failed to get stage rotation angle: {}", e.getMessage());
            throw new IOException("Failed to get stage rotation angle via socket", e);
        }
    }

// Remove the isWithinBoundsXY and isWithinBoundsZ methods entirely
// Update the moveStageXY method:

    /**
     * Moves the stage in X,Y only. Z position is not affected.
     *
     * @param x Target X coordinate in microns
     * @param y Target Y coordinate in microns
     */
    public void moveStageXY(double x, double y) {
        // Validate bounds using ConfigManager directly
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        if (!mgr.isWithinStageBounds(x, y)) {
            UIFunctions.notifyUserOfError(
                    String.format("Target position (%.2f, %.2f) is outside stage limits", x, y),
                    "Stage Limits Exceeded"
            );
            return;
        }

        try {
            socketClient.moveStageXY(x, y);
            logger.info("Successfully moved stage to XY: ({}, {})", x, y);
        } catch (IOException e) {
            logger.error("Failed to move stage XY: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    "Failed to move stage XY: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }

    /**
     * Moves the stage in Z only.
     *
     * @param z Target Z coordinate in microns
     */
    public void moveStageZ(double z) {
        // Validate bounds using ConfigManager directly
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        if (!mgr.isWithinStageBounds(z)) {
            UIFunctions.notifyUserOfError(
                    String.format("Target Z position %.2f is outside stage limits", z),
                    "Stage Limits Exceeded"
            );
            return;
        }

        try {
            socketClient.moveStageZ(z);
            logger.info("Successfully moved stage to Z: {}", z);
        } catch (IOException e) {
            logger.error("Failed to move stage Z: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    "Failed to move stage Z: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }

    /**
     * Rotates the stage to the given angle.
     *
     * @param angle The target rotation angle in ticks
     */
    public void moveStageR(double angle) {
        try {
            socketClient.moveStageR(angle);
            logger.info("Successfully rotated stage to {} ticks", angle);
        } catch (IOException e) {
            logger.error("Failed to rotate stage: {}", e.getMessage());
            UIFunctions.notifyUserOfError(
                    "Failed to rotate stage: " + e.getMessage(),
                    "Stage Move Error"
            );
        }
    }

    /**
     * Gets the underlying socket client for advanced operations.
     *
     * @return The socket client instance
     */
    public MicroscopeSocketClient getSocketClient() {
        return socketClient;
    }

    /**
     * Starts an acquisition workflow on the microscope.
     * Uses the builder pattern to support any combination of parameters
     * required by different microscope types and modalities.
     *
     * @param builder Pre-configured acquisition command builder
     * @throws IOException if communication fails
     */
    public void startAcquisition(AcquisitionCommandBuilder builder) throws IOException {
        try {
            socketClient.startAcquisition(builder);
            logger.info("Started acquisition workflow");
        } catch (IOException e) {
            logger.error("Failed to start acquisition: {}", e.getMessage());
            throw new IOException("Failed to start acquisition via socket", e);
        }
    }

    /**
     * Moves the microscope stage to the center of the given tile.
     *
     * @param tile The tile to move to
     */
    public void onMoveButtonClicked(PathObject tile) {
        if (currentTransform.get() == null) {
            UIFunctions.notifyUserOfError(
                    "No transformation set. Please run alignment workflow first.",
                    "No Transform"
            );
            return;
        }

        // Compute stage coordinates from QuPath coordinates
        double[] qpCoords = {tile.getROI().getCentroidX(), tile.getROI().getCentroidY()};
        double[] stageCoords = TransformationFunctions.transformQuPathFullResToStage(
                qpCoords, currentTransform.get()
        );

        logger.info("Moving to tile center - QuPath: ({}, {}) -> Stage: ({}, {})",
                qpCoords[0], qpCoords[1], stageCoords[0], stageCoords[1]);

        moveStageXY(stageCoords[0], stageCoords[1]);
    }

    /**
     * Sets the current affine transform for coordinate conversion.
     *
     * @param transform The transform to use
     */
    public void setCurrentTransform(AffineTransform transform) {
        this.currentTransform.set(transform);
        logger.info("Updated current transform: {}", transform);
    }

    /**
     * Gets the current affine transform.
     *
     * @return The current transform, or null if none set
     */
    public AffineTransform getCurrentTransform() {
        return this.currentTransform.get();
    }
    /**
     * Gets the current camera field of view in microns.
     * This queries the microscope server for the actual FOV dimensions,
     * which accounts for the current objective and camera configuration.
     *
     * @return Array containing [width, height] in microns
     * @throws IOException if communication fails
     */
    public double[] getCameraFOV() throws IOException {
        try {
            double[] fov = socketClient.getCameraFOV();
            logger.info("Camera FOV: {} x {} microns", fov[0], fov[1]);
            return fov;
        } catch (IOException e) {
            logger.error("Failed to get camera FOV: {}", e.getMessage());
            throw new IOException("Failed to get camera FOV via socket", e);
        }
    }

    /**
     * Calculates camera field of view using explicit hardware configuration.
     * This method is preferred when the objective and detector are already known
     * (e.g., from sample setup), as it avoids ambiguity from multiple matching profiles.
     *
     * @param modality The imaging modality (e.g., "bf_10x", "ppm_20x")
     * @param objectiveId The objective identifier from configuration
     * @param detectorId The detector identifier from configuration
     * @return Array containing [width, height] in microns
     * @throws IOException if configuration is missing or invalid
     */
    public double[] getCameraFOVFromConfig(String modality, String objectiveId, String detectorId) throws IOException {
        logger.info("Calculating camera FOV from config with explicit hardware");
        logger.info("  Modality: {}, Objective: {}, Detector: {}", modality, objectiveId, detectorId);

        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);

        // Handle indexed modality names (e.g., "bf_10x_1" -> "bf_10x")
        String baseModality = modality;
        if (baseModality.matches(".*_\\d+$")) {
            baseModality = baseModality.substring(0, baseModality.lastIndexOf('_'));
        }

        // Get pixel size using the explicit hardware configuration
        double pixelSize = mgr.getModalityPixelSize(baseModality, objectiveId, detectorId);
        if (pixelSize <= 0) {
            throw new IOException(String.format(
                    "Invalid pixel size (%.4f) for modality '%s' with objective '%s' and detector '%s'",
                    pixelSize, baseModality, objectiveId, detectorId));
        }

        // Get detector dimensions
        int[] dimensions = mgr.getDetectorDimensions(detectorId);
        if (dimensions == null) {
            throw new IOException(String.format(
                    "No detector dimensions found for detector '%s'",
                    detectorId));
        }

        double fovWidth = dimensions[0] * pixelSize;
        double fovHeight = dimensions[1] * pixelSize;

        logger.info("CRITICAL FOV CALCULATION (explicit hardware):");
        logger.info("  Pixel size from config: {} µm/pixel", pixelSize);
        logger.info("  Detector dimensions: {}x{} pixels", dimensions[0], dimensions[1]);
        logger.info("  Calculated FOV: {} x {} µm", fovWidth, fovHeight);
        logger.info("  FOV calculation: {}px * {}µm/px = {}µm width", dimensions[0], pixelSize, fovWidth);

        return new double[]{fovWidth, fovHeight};
    }

    /**
     * Checks if the socket client is connected.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return socketClient.isConnected();
    }

    /**
     * Manually connects to the microscope server.
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        socketClient.connect();
    }

    /**
     * Manually disconnects from the microscope server.
     */
    public void disconnect() {
        socketClient.disconnect();
    }

    // ==================== Camera Control Methods ====================

    /**
     * Gets the current camera name from the microscope.
     *
     * @return Camera name (e.g., "JAICamera", "MicroPublisher")
     * @throws IOException if communication fails
     */
    public String getCameraName() throws IOException {
        try {
            return socketClient.getCameraName();
        } catch (IOException e) {
            logger.error("Failed to get camera name: {}", e.getMessage());
            throw new IOException("Failed to get camera name via socket", e);
        }
    }

    /**
     * Gets the current camera mode (individual vs unified exposure/gain).
     *
     * @return CameraModeResult with mode flags
     * @throws IOException if communication fails
     */
    public MicroscopeSocketClient.CameraModeResult getCameraMode() throws IOException {
        try {
            return socketClient.getCameraMode();
        } catch (IOException e) {
            logger.error("Failed to get camera mode: {}", e.getMessage());
            throw new IOException("Failed to get camera mode via socket", e);
        }
    }

    /**
     * Sets the camera mode (individual vs unified exposure/gain).
     *
     * @param exposureIndividual True to enable per-channel exposure control
     * @param gainIndividual True to enable per-channel gain control
     * @throws IOException if communication fails or camera doesn't support individual mode
     */
    public void setCameraMode(boolean exposureIndividual, boolean gainIndividual) throws IOException {
        try {
            socketClient.setCameraMode(exposureIndividual, gainIndividual);
            logger.info("Set camera mode: exposure_individual={}, gain_individual={}", exposureIndividual, gainIndividual);
        } catch (IOException e) {
            logger.error("Failed to set camera mode: {}", e.getMessage());
            throw new IOException("Failed to set camera mode via socket", e);
        }
    }

    /**
     * Gets current exposure values from the camera.
     *
     * @return ExposuresResult with exposure values in ms
     * @throws IOException if communication fails
     */
    public MicroscopeSocketClient.ExposuresResult getExposures() throws IOException {
        try {
            return socketClient.getExposures();
        } catch (IOException e) {
            logger.error("Failed to get exposures: {}", e.getMessage());
            throw new IOException("Failed to get exposures via socket", e);
        }
    }

    /**
     * Sets exposure values on the camera.
     *
     * @param exposures Array of exposure values in ms. Length 1 for unified, 3 for per-channel (R, G, B)
     * @throws IOException if communication fails
     */
    public void setExposures(float[] exposures) throws IOException {
        try {
            socketClient.setExposures(exposures);
            logger.info("Set exposures: {}", java.util.Arrays.toString(exposures));
        } catch (IOException e) {
            logger.error("Failed to set exposures: {}", e.getMessage());
            throw new IOException("Failed to set exposures via socket", e);
        }
    }

    /**
     * Gets current gain values from the camera.
     *
     * @return GainsResult with gain values
     * @throws IOException if communication fails
     */
    public MicroscopeSocketClient.GainsResult getGains() throws IOException {
        try {
            return socketClient.getGains();
        } catch (IOException e) {
            logger.error("Failed to get gains: {}", e.getMessage());
            throw new IOException("Failed to get gains via socket", e);
        }
    }

    /**
     * Sets gain values on the camera.
     *
     * @param gains Array of gain values. Length 1 for unified, 3 for per-channel (R, G, B)
     * @throws IOException if communication fails
     */
    public void setGains(float[] gains) throws IOException {
        try {
            socketClient.setGains(gains);
            logger.info("Set gains: {}", java.util.Arrays.toString(gains));
        } catch (IOException e) {
            logger.error("Failed to set gains: {}", e.getMessage());
            throw new IOException("Failed to set gains via socket", e);
        }
    }

    /**
     * Applies camera settings for a specific PPM angle.
     * This sets the exposure and gain values from the calibration profile
     * AND moves the rotation stage to the specified angle.
     *
     * @param angleName The angle name (e.g., "uncrossed", "crossed", "positive", "negative")
     * @param exposures Per-channel exposures [R, G, B] in ms
     * @param gains Per-channel gains [R, G, B]
     * @param rotationDegrees The rotation angle in degrees to move to
     * @throws IOException if communication fails
     */
    public void applyCameraSettingsForAngle(String angleName, float[] exposures, float[] gains, double rotationDegrees) throws IOException {
        logger.info("Applying camera settings for angle '{}' at {} degrees", angleName, rotationDegrees);

        // First move the rotation stage
        try {
            socketClient.moveStageR(rotationDegrees);
            logger.info("Moved rotation stage to {} degrees", rotationDegrees);
        } catch (IOException e) {
            logger.error("Failed to move rotation stage: {}", e.getMessage());
            throw new IOException("Failed to move rotation stage to " + rotationDegrees + " degrees", e);
        }

        // Set exposures (this will auto-enable individual mode if needed)
        try {
            socketClient.setExposures(exposures);
            logger.info("Set exposures for {}: R={}, G={}, B={}", angleName, exposures[0], exposures[1], exposures[2]);
        } catch (IOException e) {
            logger.error("Failed to set exposures: {}", e.getMessage());
            throw new IOException("Failed to set exposures for " + angleName, e);
        }

        // Set gains (this will auto-enable individual mode if needed)
        try {
            socketClient.setGains(gains);
            logger.info("Set gains for {}: R={}, G={}, B={}", angleName, gains[0], gains[1], gains[2]);
        } catch (IOException e) {
            logger.error("Failed to set gains: {}", e.getMessage());
            throw new IOException("Failed to set gains for " + angleName, e);
        }

        logger.info("Camera settings applied successfully for angle '{}'", angleName);
    }

    // ==================== Live Mode Control Methods ====================

    /**
     * Checks if live mode is currently running on the microscope.
     *
     * @return true if live mode is active, false otherwise
     * @throws IOException if communication fails
     */
    public boolean isLiveModeRunning() throws IOException {
        try {
            return socketClient.isLiveModeRunning();
        } catch (IOException e) {
            logger.error("Failed to check live mode status: {}", e.getMessage());
            throw new IOException("Failed to check live mode status via socket", e);
        }
    }

    /**
     * Sets live mode on or off.
     *
     * @param enable true to enable live mode, false to disable
     * @throws IOException if communication fails or studio is not available
     */
    public void setLiveMode(boolean enable) throws IOException {
        try {
            socketClient.setLiveMode(enable);
            logger.info("Live mode set to: {}", enable ? "ON" : "OFF");
        } catch (IOException e) {
            logger.error("Failed to set live mode: {}", e.getMessage());
            throw new IOException("Failed to set live mode via socket", e);
        }
    }

    /**
     * Wraps a camera operation with live mode stop/restart handling.
     *
     * <p>If live mode is running, it will be stopped before the operation executes
     * and restored afterward. This is required for JAI camera property changes
     * (e.g., ExposureIsIndividual, GainIsIndividual) that cannot be modified
     * during live streaming (error 11018).
     *
     * @param operation The camera operation to execute
     * @throws IOException if the operation itself fails
     */
    public void withLiveModeHandling(IORunnable operation) throws IOException {
        boolean wasLive = false;
        try {
            wasLive = isLiveModeRunning();
            if (wasLive) {
                logger.info("Live mode is running - turning off for camera operation");
                setLiveMode(false);
                Thread.sleep(100);
            }
        } catch (IOException e) {
            logger.warn("Could not check/set live mode: {} - proceeding", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            operation.run();
        } finally {
            if (wasLive) {
                try {
                    Thread.sleep(100);
                    setLiveMode(true);
                    logger.info("Live mode restored after camera operation");
                } catch (IOException | InterruptedException e) {
                    logger.warn("Could not restore live mode: {}", e.getMessage());
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * Functional interface for IO operations that may throw IOException.
     * Used with {@link #withLiveModeHandling(IORunnable)} to wrap camera
     * operations that require live mode to be stopped.
     */
    @FunctionalInterface
    public interface IORunnable {
        void run() throws IOException;
    }
}