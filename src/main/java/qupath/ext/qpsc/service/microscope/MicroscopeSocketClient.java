package qupath.ext.qpsc.service.microscope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Socket-based client for communicating with the Python microscope control server.
 * This class manages a persistent connection to the microscope server and provides
 * thread-safe command execution with automatic reconnection capabilities.
 *
 * <p>The client uses a binary protocol with fixed-length commands (8 bytes) and
 * network byte order (big-endian) for numeric data transmission.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Connection pooling with automatic reconnection</li>
 *   <li>Thread-safe command execution</li>
 *   <li>Configurable timeouts and retry policies</li>
 *   <li>Health monitoring with heartbeat checks</li>
 *   <li>Graceful shutdown and resource cleanup</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class MicroscopeSocketClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MicroscopeSocketClient.class);

    // Protocol constants
    /** End marker expected by Python server to indicate message completion */
    private static final String END_MARKER = "ENDOFSTR";

    // Connection parameters
    private final String host;
    private final int port;
    private final int connectTimeout;
    private final int readTimeout;

    // Primary socket and streams (used for workflows/acquisitions)
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private final Object socketLock = new Object();

    // Auxiliary socket and streams (used for Live Viewer and stage control)
    // This allows live preview and stage movements to work even when primary socket
    // is busy with long-running operations (e.g., birefringence optimization)
    private Socket auxSocket;
    private DataInputStream auxInput;
    private DataOutputStream auxOutput;
    private final Object auxSocketLock = new Object();
    private final AtomicBoolean auxConnected = new AtomicBoolean(false);

    // Connection state
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    // Progress monitoring state - tracks last time progress was made or significant event occurred
    private final AtomicLong lastProgressUpdateTime = new AtomicLong(System.currentTimeMillis());

    // Acquisition error tracking
    private volatile String lastFailureMessage = null;

    // Final Z position from completed acquisition (for tilt correction model)
    private volatile Double lastAcquisitionFinalZ = null;

    // Reconnection handling
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "MicroscopeReconnect");
                t.setDaemon(true);
                return t;
            }
    );

    // Health monitoring
    private final ScheduledExecutorService healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "MicroscopeHealthCheck");
                t.setDaemon(true);
                return t;
            }
    );

    // Configuration
    private final int maxReconnectAttempts;
    private final long reconnectDelayMs;
    private final long healthCheckIntervalMs;

    /**
     * Protocol command enumeration matching the Python server commands.
     * Each command is exactly 8 bytes with trailing underscores for padding.
     */
    public enum Command {
        /** Send microscope configuration file path (CRITICAL - must be first command after connection) */
        CONFIG("config__"),
        /** Get XY stage position */
        GETXY("getxy___"),
        /** Get Z stage position */
        GETZ("getz____"),
        /** Move Z stage */
        MOVEZ("move_z__"),
        /** Move XY stage */
        MOVE("move____"),
        /** Get rotation angle in ticks */
        GETR("getr____"),
        /** Move rotation stage */
        MOVER("move_r__"),
        /** Shutdown server */
        SHUTDOWN("shutdown"),
        /** Disconnect client */
        DISCONNECT("quitclnt"),
        /** Start acquisition */
        ACQUIRE("acquire_"),
        /** Background acquisition */
        BGACQUIRE("bgacquir"),
        /** Test standard autofocus at current position */
        TESTAF("testaf__"),
        /** Test adaptive autofocus at current position */
        TESTADAF("testadaf"),
        /** Get acquisition status */
        STATUS("status__"),
        /** Get acquisition progress */
        PROGRESS("progress"),
        /** Cancel acquisition */
        CANCEL("cancel__"),
        GETFOV("getfov__"),
        /** Check if manual focus is requested */
        REQMANF("reqmanf_"),
        /** Acknowledge manual focus - retry autofocus */
        ACKMF("ackmf___"),
        /** Skip autofocus retry - use current focus */
        SKIPAF("skipaf__"),
        /** Run autofocus parameter benchmark */
        AFBENCH("afbench_"),
        /** PPM birefringence maximization test */
        PPMBIREF("ppmbiref"),
        /** Sunburst calibration for hue-to-angle mapping */
        SBCALIB("sbcalib_"),
        /** Simple white balance calibration at single exposure */
        WBSIMPLE("wbsimple"),
        /** PPM white balance calibration at 4 angles */
        WBPPM("wbppm___"),

        // Camera Control Commands
        /** Get camera name from Core */
        GETCAM("getcam__"),
        /** Get exposure/gain mode flags (individual vs unified) */
        GETMODE("getmode_"),
        /** Set exposure/gain mode flags */
        SETMODE("setmode_"),
        /** Get exposure values (unified or per-channel RGB) */
        GETEXP("getexp__"),
        /** Set exposure values */
        SETEXP("setexp__"),
        /** Get gain values (unified or per-channel RGB) */
        GETGAIN("getgain_"),
        /** Set gain values */
        SETGAIN("setgain_"),

        // White Balance Mode Control
        /** Set camera white balance mode (0=Off, 1=Continuous, 2=Once) */
        SETWBMD("setwbmd_"),

        // Live Mode Control Commands
        /** Check if live mode is currently running */
        GETLIVE("getlive_"),
        /** Set live mode on (1) or off (0) */
        SETLIVE("setlive_"),
        /** Simple snap with fixed exposure (no adaptive) */
        SNAP("snap____"),
        /** Get per-channel noise stats (multi-frame temporal analysis) */
        GETNOISE("getnoise"),

        // Live Viewer Commands (core-level, bypasses MM studio/live window)
        /** Get latest frame from MM circular buffer */
        GETFRAME("getframe"),
        /** Start continuous sequence acquisition (core-level) */
        STRTSEQ("strtseq_"),
        /** Stop continuous sequence acquisition (core-level) */
        STOPSEQ("stopseq_");

        private final byte[] value;

        Command(String value) {
            if (value.length() != 8) {
                throw new IllegalArgumentException("Command must be exactly 8 bytes");
            }
            this.value = value.getBytes();
        }

        public byte[] getValue() {
            return value.clone();
        }
    }

    /**
     * Acquisition state enumeration matching the Python server states.
     */
    public enum AcquisitionState {
        IDLE,
        RUNNING,
        CANCELLING,
        CANCELLED,
        COMPLETED,
        FAILED;

        /**
         * Parse state from server response string.
         * @param stateStr 16-byte padded state string from server
         * @return Parsed acquisition state
         */
        public static AcquisitionState fromString(String stateStr) {
            String trimmed = stateStr.trim().toUpperCase();
            try {
                return AcquisitionState.valueOf(trimmed);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown acquisition state: {}", trimmed);
                return IDLE;
            }
        }
    }
    /**
     * Represents acquisition progress information.
     */
    public static class AcquisitionProgress {
        public final int current;
        public final int total;

        public AcquisitionProgress(int current, int total) {
            this.current = current;
            this.total = total;
        }

        public double getPercentage() {
            return total > 0 ? (100.0 * current / total) : 0.0;
        }

        @Override
        public String toString() {
            return String.format("%d/%d (%.1f%%)", current, total, getPercentage());
        }
    }

    /**
     * Creates a new microscope socket client with default configuration.
     *
     * @param host Server hostname or IP address
     * @param port Server port number
     */
    public MicroscopeSocketClient(String host, int port) {
        this(host, port, 3000, 5000, 3, 5000, 30000);
    }

    /**
     * Creates a new microscope socket client with custom configuration.
     *
     * @param host Server hostname or IP address
     * @param port Server port number
     * @param connectTimeout Connection timeout in milliseconds
     * @param readTimeout Read timeout in milliseconds
     * @param maxReconnectAttempts Maximum number of reconnection attempts
     * @param reconnectDelayMs Delay between reconnection attempts in milliseconds
     * @param healthCheckIntervalMs Interval between health checks in milliseconds
     */
    public MicroscopeSocketClient(String host, int port, int connectTimeout, int readTimeout,
                                  int maxReconnectAttempts, long reconnectDelayMs, long healthCheckIntervalMs) {
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectDelayMs = reconnectDelayMs;
        this.healthCheckIntervalMs = healthCheckIntervalMs;

        // Start health monitoring
        startHealthMonitoring();
    }

    /**
     * Establishes connection to the microscope server.
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        synchronized (socketLock) {
            if (connected.get()) {
                logger.debug("Already connected to {}:{}", host, port);
                return;
            }

            logger.info("Connecting to microscope server at {}:{}", host, port);

            try {
                socket = new Socket();
                socket.setSoTimeout(readTimeout);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true); // Disable Nagle's algorithm for low latency

                socket.connect(new InetSocketAddress(host, port), connectTimeout);

                input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                connected.set(true);
                lastActivityTime.set(System.currentTimeMillis());

                logger.info("Successfully connected to microscope server");

                // CRITICAL: Send config immediately after connection
                try {
                    sendConfig();
                } catch (IllegalStateException e) {
                    // Config not set - close connection and fail
                    logger.error("Config not set: {}", e.getMessage());
                    cleanup();
                    throw new IOException("Connection failed: " + e.getMessage(), e);
                } catch (IOException e) {
                    // Config send failed - close connection and fail
                    logger.error("Failed to send config to server: {}", e.getMessage());
                    cleanup();
                    throw new IOException("Failed to configure server: " + e.getMessage(), e);
                }

            } catch (IOException e) {
                cleanup();
                throw new IOException("Failed to connect to microscope server: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Disconnects from the microscope server gracefully.
     */
    public void disconnect() {
        synchronized (socketLock) {
            if (!connected.get()) {
                return;
            }

            try {
                // Send disconnect command
                sendCommand(Command.DISCONNECT);
            } catch (Exception e) {
                logger.debug("Error sending disconnect command", e);
            }

            cleanup();
            logger.info("Disconnected from microscope server");
        }
    }

    /**
     * Establishes the auxiliary connection for Live Viewer and stage control.
     * This connection operates independently of the primary connection, allowing
     * live preview and stage movements to work during long-running operations.
     *
     * @throws IOException if connection fails
     */
    public void connectAuxiliary() throws IOException {
        synchronized (auxSocketLock) {
            if (auxConnected.get()) {
                logger.debug("Auxiliary connection already established");
                return;
            }

            logger.info("Establishing auxiliary connection to {}:{}", host, port);

            try {
                auxSocket = new Socket();
                auxSocket.setSoTimeout(readTimeout);
                auxSocket.setKeepAlive(true);
                auxSocket.setTcpNoDelay(true);

                auxSocket.connect(new InetSocketAddress(host, port), connectTimeout);

                auxInput = new DataInputStream(new BufferedInputStream(auxSocket.getInputStream()));
                auxOutput = new DataOutputStream(new BufferedOutputStream(auxSocket.getOutputStream()));

                auxConnected.set(true);

                logger.info("Auxiliary connection established, sending config...");

                // Send config on auxiliary connection
                sendConfigOnAuxiliary();

                logger.info("Auxiliary connection ready for Live Viewer and stage control");

            } catch (IOException e) {
                cleanupAuxiliary();
                throw new IOException("Failed to establish auxiliary connection: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Sends configuration on the auxiliary socket.
     * Uses the same protocol as primary socket: sends CONFIG command with path,
     * expects CFG___OK (8 bytes) or CFG_FAIL response.
     */
    private void sendConfigOnAuxiliary() throws IOException {
        String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();

        if (configPath == null || configPath.trim().isEmpty()) {
            throw new IllegalStateException("Microscope config file path not set in preferences!");
        }

        // Send CONFIG command (8 bytes)
        auxOutput.write(Command.CONFIG.getValue());
        auxOutput.flush();

        // Send config path length (4 bytes, big-endian) and path
        byte[] pathBytes = configPath.getBytes(StandardCharsets.UTF_8);
        ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
        lengthBuffer.order(ByteOrder.BIG_ENDIAN);
        lengthBuffer.putInt(pathBytes.length);
        auxOutput.write(lengthBuffer.array());
        auxOutput.write(pathBytes);
        auxOutput.flush();

        // Read response (8 bytes) - same format as primary socket
        byte[] response = new byte[8];
        auxInput.readFully(response);
        String responseStr = new String(response, StandardCharsets.UTF_8);

        if ("CFG___OK".equals(responseStr)) {
            logger.debug("Auxiliary connection configured successfully");
        } else if ("CFG_FAIL".equals(responseStr)) {
            // Read error message: 4-byte length + message
            byte[] lengthBytes = new byte[4];
            auxInput.readFully(lengthBytes);
            ByteBuffer lengthBuf = ByteBuffer.wrap(lengthBytes);
            lengthBuf.order(ByteOrder.BIG_ENDIAN);
            int errorLength = lengthBuf.getInt();

            byte[] errorBytes = new byte[errorLength];
            auxInput.readFully(errorBytes);
            String errorMsg = new String(errorBytes, StandardCharsets.UTF_8);
            throw new IOException("Auxiliary config failed: " + errorMsg);
        } else {
            throw new IOException("Unexpected auxiliary config response: " + responseStr);
        }
    }

    /**
     * Disconnects the auxiliary connection.
     */
    public void disconnectAuxiliary() {
        synchronized (auxSocketLock) {
            if (!auxConnected.get()) {
                return;
            }

            try {
                auxOutput.write(Command.DISCONNECT.getValue());
                auxOutput.flush();
            } catch (Exception e) {
                logger.debug("Error sending disconnect on auxiliary", e);
            }

            cleanupAuxiliary();
            logger.info("Auxiliary connection closed");
        }
    }

    /**
     * Ensures auxiliary connection is established, connecting if necessary.
     *
     * @throws IOException if connection cannot be established
     */
    private void ensureAuxConnected() throws IOException {
        if (!auxConnected.get()) {
            connectAuxiliary();
        }
    }

    /**
     * Cleans up auxiliary socket resources.
     */
    private void cleanupAuxiliary() {
        try {
            if (auxInput != null) {
                auxInput.close();
                auxInput = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing auxiliary input stream", e);
        }

        try {
            if (auxOutput != null) {
                auxOutput.close();
                auxOutput = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing auxiliary output stream", e);
        }

        try {
            if (auxSocket != null && !auxSocket.isClosed()) {
                auxSocket.close();
                auxSocket = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing auxiliary socket", e);
        }

        auxConnected.set(false);
    }

    /**
     * Checks if auxiliary connection is established.
     *
     * @return true if auxiliary socket is connected
     */
    public boolean isAuxConnected() {
        return auxConnected.get() && auxSocket != null && !auxSocket.isClosed();
    }

    /**
     * Send microscope configuration file path to server (CONFIG command).
     *
     * CRITICAL: This must be called immediately after connection to configure the server
     * with the correct microscope settings. Operating with wrong config could damage hardware.
     *
     * @throws IOException if communication fails
     * @throws IllegalStateException if config file path is not set in preferences
     */
    public void sendConfig() throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException("Cannot send config - not connected to server");
        }

        // Get config path from preferences
        String configPath = qupath.ext.qpsc.preferences.QPPreferenceDialog.getMicroscopeConfigFileProperty();

        // SAFETY CHECK: Config path MUST be set
        if (configPath == null || configPath.trim().isEmpty()) {
            throw new IllegalStateException(
                "Microscope config file path not set in preferences! " +
                "Go to Edit > Preferences > QPSC to set the microscope configuration file."
            );
        }

        logger.info("Sending CONFIG command with path: {}", configPath);

        synchronized (socketLock) {
            try {
                // Send CONFIG command (8 bytes)
                output.write(Command.CONFIG.getValue());
                output.flush();

                // Send config path length (4 bytes, big-endian)
                byte[] pathBytes = configPath.getBytes(StandardCharsets.UTF_8);
                ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
                lengthBuffer.order(ByteOrder.BIG_ENDIAN);
                lengthBuffer.putInt(pathBytes.length);
                output.write(lengthBuffer.array());

                // Send config path string
                output.write(pathBytes);
                output.flush();

                logger.debug("Sent config path ({} bytes)", pathBytes.length);

                // Read response (8 bytes)
                byte[] response = new byte[8];
                input.readFully(response);
                String responseStr = new String(response, StandardCharsets.UTF_8);

                if ("CFG___OK".equals(responseStr)) {
                    logger.info("Server config loaded successfully");
                } else if ("CFG_FAIL".equals(responseStr)) {
                    // Read error message: 4-byte length + message
                    byte[] lengthBytes = new byte[4];
                    input.readFully(lengthBytes);
                    ByteBuffer lengthBuf = ByteBuffer.wrap(lengthBytes);
                    lengthBuf.order(ByteOrder.BIG_ENDIAN);
                    int errorLength = lengthBuf.getInt();

                    byte[] errorBytes = new byte[errorLength];
                    input.readFully(errorBytes);
                    String errorMsg = new String(errorBytes, StandardCharsets.UTF_8);

                    throw new IOException("Server failed to load config: " + errorMsg);
                } else if ("CFG_BLCK".equals(responseStr)) {
                    // Another connection is active
                    byte[] lengthBytes = new byte[4];
                    input.readFully(lengthBytes);
                    ByteBuffer lengthBuf = ByteBuffer.wrap(lengthBytes);
                    lengthBuf.order(ByteOrder.BIG_ENDIAN);
                    int msgLength = lengthBuf.getInt();

                    byte[] msgBytes = new byte[msgLength];
                    input.readFully(msgBytes);
                    String msg = new String(msgBytes, StandardCharsets.UTF_8);

                    throw new IOException("Server connection blocked: " + msg);
                } else {
                    throw new IOException("Unexpected CONFIG response: " + responseStr);
                }
            } catch (EOFException e) {
                throw new IOException("Server closed connection during CONFIG", e);
            } catch (SocketTimeoutException e) {
                throw new IOException("Timeout waiting for CONFIG response from server", e);
            }
        }
    }

    /**
     * Gets the current camera field of view in microns.
     * This returns the actual FOV dimensions accounting for the current objective
     * and camera settings, eliminating the need for manual calculations.
     *
     * @return Array containing [width, height] in microns
     * @throws IOException if communication fails
     */
    public double[] getCameraFOV() throws IOException {
        // Use auxiliary socket for stage control operations
        byte[] response = executeCommandOnAux(Command.GETFOV, null, 8);

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float fovX = buffer.getFloat();
        float fovY = buffer.getFloat();

        logger.info("Camera FOV: {} x {} microns", fovX, fovY);
        return new double[] { fovX, fovY };
    }



    /**
     * Gets the current XY position of the microscope stage.
     *
     * @return Array containing [x, y] coordinates in microns
     * @throws IOException if communication fails
     * @throws MicroscopeHardwareException if hardware error occurs
     */
    public double[] getStageXY() throws IOException {
        // Use auxiliary socket for stage control operations
        byte[] response = executeCommandOnAux(Command.GETXY, null, 8);

        // Check for hardware error response
        String responseStr = new String(response, StandardCharsets.UTF_8);
        if (responseStr.startsWith("HW_ERROR")) {
            throw new MicroscopeHardwareException(
                "Hardware error getting XY position. Check that MicroManager is running and the XY stage is loaded."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float x = buffer.getFloat();
        float y = buffer.getFloat();

        logger.trace("Stage XY position: ({}, {})", x, y);
        return new double[] { x, y };
    }

    /**
     * Gets the current Z position of the microscope stage.
     *
     * @return Z coordinate in microns
     * @throws IOException if communication fails
     * @throws MicroscopeHardwareException if hardware error occurs
     */
    public double getStageZ() throws IOException {
        // Use auxiliary socket for stage control operations
        byte[] response = executeCommandOnAux(Command.GETZ, null, 4);

        // Check for hardware error response
        String responseStr = new String(response, StandardCharsets.UTF_8);
        if (responseStr.startsWith("HWERR")) {
            throw new MicroscopeHardwareException(
                "Hardware error getting Z position. Check that MicroManager is running and the Z stage is loaded."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float z = buffer.getFloat();
        logger.trace("Stage Z position: {}", z);
        return z;
    }

    /**
     * Gets the current rotation angle (in ticks) of the stage.
     *
     * @return Rotation angle in ticks (double the angle)
     * @throws IOException if communication fails
     * @throws MicroscopeHardwareException if hardware error occurs
     */
    public double getStageR() throws IOException {
        // Use auxiliary socket for stage control operations
        byte[] response = executeCommandOnAux(Command.GETR, null, 4);

        // Check for hardware error response
        String responseStr = new String(response, StandardCharsets.UTF_8);
        if (responseStr.startsWith("HWERR")) {
            throw new MicroscopeHardwareException(
                "Hardware error getting rotation angle. Check that MicroManager is running and the rotation stage is loaded."
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float angle = buffer.getFloat();
        logger.debug("Stage rotation ticks: {}", angle);
        return angle;
    }

    /**
     * Moves the stage to the specified XY position.
     *
     * @param x Target X coordinate in microns
     * @param y Target Y coordinate in microns
     * @throws IOException if communication fails
     */
    public void moveStageXY(double x, double y) throws IOException {
        // Use auxiliary socket for stage control operations
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putFloat((float) x);
        buffer.putFloat((float) y);

        executeCommandOnAux(Command.MOVE, buffer.array(), 0);
        logger.info("Moved stage to XY position: ({}, {})", x, y);
    }

    /**
     * Moves the stage to the specified Z position.
     *
     * @param z Target Z coordinate in microns
     * @throws IOException if communication fails
     */
    public void moveStageZ(double z) throws IOException {
        // Use auxiliary socket for stage control operations
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putFloat((float) z);

        executeCommandOnAux(Command.MOVEZ, buffer.array(), 0);
        logger.info("Moved stage to Z position: {}", z);
    }

    /**
     * Rotates the stage to the specified angle.
     *
     * @param angle Target rotation angle in degrees
     * @throws IOException if communication fails
     */
    public void moveStageR(double angle) throws IOException {
        // Use auxiliary socket for stage control operations
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putFloat((float) angle);

        executeCommandOnAux(Command.MOVER, buffer.array(), 0);
        logger.info("Rotated stage to angle: {}", angle);
    }

    /**
     * Starts an acquisition workflow on the server using a command builder.
     * This single method handles all acquisition types with their specific parameters.
     *
     * @param builder Pre-configured acquisition command builder
     * @throws IOException if communication fails
     */
    public void startAcquisition(AcquisitionCommandBuilder builder) throws IOException {
        String message = builder.buildSocketMessage() + " " + END_MARKER;
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending acquisition command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            try {
                // Send command (8 bytes)
                output.write(Command.ACQUIRE.getValue());
                output.flush();
                logger.debug("Sent ACQUIRE command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent acquisition message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                // Temporarily increase timeout for acknowledgment read
                // Server needs time to parse message and start acquisition thread
                int originalTimeout = socket.getSoTimeout();
                socket.setSoTimeout(30000); // 30 seconds for acknowledgment

                try {
                    // Wait for acknowledgment from server (16 bytes like STATUS response)
                    byte[] ackResponse = new byte[16];
                    input.readFully(ackResponse);
                    String ackStr = new String(ackResponse, StandardCharsets.UTF_8).trim();

                    if (!ackStr.startsWith("STARTED")) {
                        throw new IOException("Unexpected acquisition acknowledgment: " + ackStr);
                    }

                    logger.info("Acquisition acknowledged by server: {}", ackStr);
                } finally {
                    socket.setSoTimeout(originalTimeout);
                }

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Acquisition command sent successfully");

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Failed to send acquisition command", e));
                throw new IOException("Failed to send acquisition command", e);
            }
        }
    }

    /**
     * Starts a background acquisition workflow on the server for flat field correction.
     * This method uses the BGACQUIRE command with custom parameters that match the
     * server's expected format (--yaml, --output, --modality, --angles, --exposures).
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for background images
     * @param modality Modality name (e.g., "ppm")
     * @param angles Angle values in parentheses format (e.g., "(-5.0,0.0,5.0,90.0)")
     * @param exposures Exposure values in parentheses format (e.g., "(120.0,250.0,60.0,1.2)")
     *                  NOTE: These are ignored by server with adaptive exposure enabled
     * @param usePerAngleWB Whether to apply per-angle white balance calibration during acquisition
     * @return Map of angle (degrees) to final exposure time (ms) used by Python server
     * @throws IOException if communication fails
     */
    public Map<Double, Double> startBackgroundAcquisition(String yamlPath, String outputPath, String modality,
                                           String angles, String exposures, boolean usePerAngleWB) throws IOException {

        // Build BGACQUIRE-specific command message
        // Include white balance flag only if enabled (to maintain backward compatibility)
        String wbFlag = usePerAngleWB ? " --use_per_angle_wb" : "";
        String message = String.format("--yaml %s --output %s --modality %s --angles %s --exposures %s%s %s",
                yamlPath, outputPath, modality, angles, exposures, wbFlag, END_MARKER);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending background acquisition command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Temporarily increase socket timeout for background acquisition
            // Adaptive exposure requires multiple iterations per angle (typically 2-5, max 10)
            // With 4 angles, this can take 60-120 seconds. Allow 3 minutes to be safe.
            int originalTimeout = readTimeout;
            try {
                if (socket != null) {
                    socket.setSoTimeout(180000); // 3 minutes for background acquisition with adaptive exposure
                    logger.debug("Increased socket timeout to 180s for background acquisition");
                }

                // Send BGACQUIRE command (8 bytes)
                output.write(Command.BGACQUIRE.getValue());
                output.flush();
                logger.debug("Sent BGACQUIRE command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent background acquisition message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Background acquisition command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[1024];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected background acquisition: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Now wait for the final SUCCESS/FAILED response
                logger.info("Waiting for background acquisition to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Background acquisition failed: " + finalResponse.substring(7));
                    } else if (!finalResponse.startsWith("SUCCESS:")) {
                        logger.warn("Unexpected final response: {}", finalResponse);
                    }

                    // Parse final exposures from SUCCESS response
                    // Format: SUCCESS:/path|angle1:exposure1,angle2:exposure2,...
                    Map<Double, Double> finalExposures = new HashMap<>();
                    if (finalResponse.startsWith("SUCCESS:")) {
                        String data = finalResponse.substring(8); // Remove "SUCCESS:"
                        String[] parts = data.split("\\|");

                        // Check if exposures are included (parts[1])
                        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                            String exposuresStr = parts[1].trim();
                            logger.info("Parsing final exposures from response: {}", exposuresStr);

                            String[] exposurePairs = exposuresStr.split(",");
                            for (String pair : exposurePairs) {
                                String[] angleExposure = pair.split(":");
                                if (angleExposure.length == 2) {
                                    try {
                                        double angle = Double.parseDouble(angleExposure[0].trim());
                                        double exposure = Double.parseDouble(angleExposure[1].trim());
                                        finalExposures.put(angle, exposure);
                                        logger.debug("  Angle {}° -> {}ms", angle, exposure);
                                    } catch (NumberFormatException e) {
                                        logger.warn("Failed to parse angle:exposure pair: {}", pair);
                                    }
                                }
                            }
                            logger.info("Parsed {} final exposure values from server", finalExposures.size());
                        } else {
                            logger.warn("No exposure data in response (old server version?)");
                        }

                        lastActivityTime.set(System.currentTimeMillis());
                        return finalExposures;
                    }
                } else {
                    throw new IOException("No final response received from background acquisition");
                }

                // Fallback - shouldn't reach here
                lastActivityTime.set(System.currentTimeMillis());
                return new HashMap<>();

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Background acquisition error", e));
                throw new IOException("Background acquisition error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Test autofocus at current microscope position with diagnostic output.
     *
     * This command performs autofocus using settings from the autofocus_{microscope}.yml
     * configuration file and generates a detailed diagnostic plot showing:
     * - Focus curve with raw scores and interpolated curve
     * - Z positions tested and scores achieved
     * - Comparison of raw best vs interpolated best focus position
     * - Summary statistics and parameter settings
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for diagnostic plots
     * @param objective Objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @return Map with keys: "plot_path", "initial_z", "final_z", "z_shift"
     * @throws IOException if communication fails or autofocus test fails
     */
    public Map<String, String> testAutofocus(String yamlPath, String outputPath, String objective)
            throws IOException {

        // Build TESTAF-specific command message
        String message = String.format("--yaml %s --output %s --objective %s %s",
                yamlPath, outputPath, objective, END_MARKER);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending autofocus test command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Temporarily increase socket timeout for autofocus test
            // Autofocus scan can take 30-60 seconds depending on n_steps
            int originalTimeout = readTimeout;
            try {
                if (socket != null) {
                    socket.setSoTimeout(120000); // 2 minutes for autofocus test
                    logger.debug("Increased socket timeout to 120s for autofocus test");
                }

                // Send TESTAF command (8 bytes)
                output.write(Command.TESTAF.getValue());
                output.flush();
                logger.debug("Sent TESTAF command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent autofocus test message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Autofocus test command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[1024];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected autofocus test: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Now wait for the final SUCCESS/FAILED response
                logger.info("Waiting for autofocus test to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Autofocus test failed: " + finalResponse.substring(7));
                    } else if (!finalResponse.startsWith("SUCCESS:")) {
                        logger.warn("Unexpected final response: {}", finalResponse);
                    }

                    // Parse result from SUCCESS response
                    // Format: SUCCESS:plot_path|initial_z:final_z:z_shift
                    Map<String, String> result = new java.util.HashMap<>();
                    if (finalResponse.startsWith("SUCCESS:")) {
                        String data = finalResponse.substring(8); // Remove "SUCCESS:"
                        String[] parts = data.split("\\|");

                        if (parts.length > 0) {
                            result.put("plot_path", parts[0].trim());
                        }

                        // Parse Z position data if available
                        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                            String zData = parts[1].trim();
                            logger.info("Parsing Z position data from response: {}", zData);

                            String[] zValues = zData.split(":");
                            if (zValues.length == 3) {
                                result.put("initial_z", zValues[0].trim());
                                result.put("final_z", zValues[1].trim());
                                result.put("z_shift", zValues[2].trim());

                                logger.info("  Initial Z: {} um", zValues[0]);
                                logger.info("  Final Z: {} um", zValues[1]);
                                logger.info("  Z shift: {} um", zValues[2]);
                            }
                        }

                        lastActivityTime.set(System.currentTimeMillis());
                        return result;
                    }
                } else {
                    throw new IOException("No final response received from autofocus test");
                }

                // Fallback - shouldn't reach here
                lastActivityTime.set(System.currentTimeMillis());
                return new java.util.HashMap<>();

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Autofocus test error", e));
                throw new IOException("Autofocus test error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Test adaptive autofocus at current microscope position with diagnostic output.
     *
     * This command performs ADAPTIVE autofocus which:
     * - Starts at current Z position
     * - Searches bidirectionally (above and below)
     * - Adapts step size based on results
     * - Stops when focus is "good enough" or max steps reached
     * - Minimizes number of acquisitions needed
     *
     * This is the autofocus algorithm used during actual acquisitions.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for diagnostic data
     * @param objective Objective identifier (e.g., "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")
     * @return Map with keys: "message", "initial_z", "final_z", "z_shift"
     * @throws IOException if communication fails or autofocus test fails
     */
    public Map<String, String> testAdaptiveAutofocus(String yamlPath, String outputPath, String objective)
            throws IOException {

        // Build TESTADAF-specific command message
        String message = String.format("--yaml %s --output %s --objective %s %s",
                yamlPath, outputPath, objective, END_MARKER);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending adaptive autofocus test command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Temporarily increase socket timeout for adaptive autofocus test
            // Adaptive can take varying time depending on how quickly it converges
            int originalTimeout = readTimeout;
            try {
                if (socket != null) {
                    socket.setSoTimeout(120000); // 2 minutes for adaptive autofocus test
                    logger.debug("Increased socket timeout to 120s for adaptive autofocus test");
                }

                // Send TESTADAF command (8 bytes)
                output.write(Command.TESTADAF.getValue());
                output.flush();
                logger.debug("Sent TESTADAF command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent adaptive autofocus test message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Adaptive autofocus test command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[1024];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected adaptive autofocus test: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Now wait for the final SUCCESS/FAILED response
                logger.info("Waiting for adaptive autofocus test to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Adaptive autofocus test failed: " + finalResponse.substring(7));
                    } else if (!finalResponse.startsWith("SUCCESS:")) {
                        logger.warn("Unexpected final response: {}", finalResponse);
                    }

                    // Parse result from SUCCESS response
                    // Format: SUCCESS:message|initial_z:final_z:z_shift
                    Map<String, String> result = new java.util.HashMap<>();
                    if (finalResponse.startsWith("SUCCESS:")) {
                        String data = finalResponse.substring(8); // Remove "SUCCESS:"
                        String[] parts = data.split("\\|");

                        if (parts.length > 0) {
                            result.put("message", parts[0].trim());
                        }

                        // Parse Z position data if available
                        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                            String zData = parts[1].trim();
                            logger.info("Parsing Z position data from response: {}", zData);

                            String[] zValues = zData.split(":");
                            if (zValues.length == 3) {
                                result.put("initial_z", zValues[0].trim());
                                result.put("final_z", zValues[1].trim());
                                result.put("z_shift", zValues[2].trim());

                                logger.info("  Initial Z: {} um", zValues[0]);
                                logger.info("  Final Z: {} um", zValues[1]);
                                logger.info("  Z shift: {} um", zValues[2]);
                            }
                        }

                        lastActivityTime.set(System.currentTimeMillis());
                        return result;
                    }
                } else {
                    throw new IOException("No final response received from adaptive autofocus test");
                }

                // Fallback - shouldn't reach here
                lastActivityTime.set(System.currentTimeMillis());
                return new java.util.HashMap<>();

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Adaptive autofocus test error", e));
                throw new IOException("Adaptive autofocus test error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Runs PPM rotation sensitivity test on the server.
     * This method systematically tests PPM rotation sensitivity by acquiring images
     * at precise angles and analyzing the impact of angular deviations.
     *
     * @param params Parameters for the sensitivity test
     * @return Path to the output directory containing test results
     * @throws IOException if communication fails
     */
    public String runPPMSensitivityTest(PPMSensitivityParams params) throws IOException {
        // Build PPMSENS-specific command message
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("--yaml ").append(params.configYaml());
        messageBuilder.append(" --output ").append(params.outputDir());
        messageBuilder.append(" --test-type ").append(params.testType());
        messageBuilder.append(" --base-angle ").append(params.baseAngle());
        messageBuilder.append(" --repeats ").append(params.nRepeats());
        messageBuilder.append(" ").append(END_MARKER);

        String message = messageBuilder.toString();
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending PPM sensitivity test command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Store original timeout
            int originalTimeout = 0;
            try {
                originalTimeout = socket.getSoTimeout();
                // Increase timeout for sensitivity test (can take 10-60 minutes depending on test type)
                // Note: PPMSENS does not send progress updates, so use generous total timeout
                socket.setSoTimeout(3600000); // 60 minutes
                logger.debug("Increased socket timeout to 60 minutes for PPM sensitivity test");
            } catch (IOException e) {
                logger.warn("Failed to adjust socket timeout", e);
            }

            try {
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                // Send command (8 bytes: "ppmsens_")
                output.write("ppmsens_".getBytes(StandardCharsets.UTF_8));
                output.write(messageBytes);
                output.flush();

                logger.info("Command sent, waiting for server response...");

                // Read initial response (STARTED or FAILED)
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected PPM sensitivity test: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Wait for final SUCCESS/FAILED response
                logger.info("Waiting for PPM sensitivity test to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("PPM sensitivity test failed: " + finalResponse.substring(7));
                    } else if (finalResponse.startsWith("SUCCESS:")) {
                        // Extract output directory path from SUCCESS response
                        String outputDir = finalResponse.substring(8).trim();
                        logger.info("PPM sensitivity test successful. Output: {}", outputDir);
                        return outputDir;
                    } else {
                        throw new IOException("Unexpected final response: " + finalResponse);
                    }
                }

                throw new IOException("No response received from server");

            } catch (IOException e) {
                logger.error("Error during PPM sensitivity test", e);
                handleIOException(new IOException("PPM sensitivity test error", e));
                throw new IOException("PPM sensitivity test error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Parameters for PPM rotation sensitivity test.
     *
     * @param configYaml Path to microscope configuration YAML file
     * @param outputDir Output directory for test results (required)
     * @param testType Type of test: comprehensive, standard, deviation, repeatability, calibration
     * @param baseAngle Base angle for deviation/repeatability tests (degrees)
     * @param nRepeats Number of repetitions for repeatability test
     */
    public record PPMSensitivityParams(
            String configYaml,
            String outputDir,
            String testType,
            double baseAngle,
            int nRepeats
    ) {}

    /**
     * Functional interface for receiving benchmark progress updates.
     * Called after each trial completes to report progress.
     */
    @FunctionalInterface
    public interface BenchmarkProgressListener {
        /**
         * Called when progress is reported from the benchmark.
         *
         * @param currentTrial Current trial number (1-based)
         * @param totalTrials Total number of trials to run
         * @param statusMessage Status message describing current trial result
         */
        void onProgress(int currentTrial, int totalTrials, String statusMessage);
    }

    /**
     * Run autofocus parameter benchmark to find optimal settings.
     *
     * This command performs systematic testing of autofocus parameters to determine
     * the best configuration for speed and accuracy. The benchmark tests:
     * - Multiple n_steps values (number of Z positions sampled)
     * - Multiple search ranges (how far from current Z to search)
     * - Different interpolation methods (linear, quadratic, cubic)
     * - Different focus score metrics (laplacian, sobel, brenner)
     * - Both standard and adaptive autofocus algorithms
     *
     * Results are saved as CSV and JSON files for analysis.
     *
     * @param referenceZ Known good focus Z position (must be manually verified in focus)
     * @param outputPath Output directory for benchmark results
     * @param testDistances List of distances (um) from focus to test, or null for defaults
     * @param quickMode If true, run reduced parameter space for faster results
     * @param objective Objective identifier for safety limits (e.g., "20X")
     * @return Map with summary results including success_rate, timing_stats, and fastest configs
     * @throws IOException if communication fails or benchmark encounters errors
     */
    public Map<String, Object> runAutofocusBenchmark(
            double referenceZ,
            String outputPath,
            List<Double> testDistances,
            boolean quickMode,
            String objective) throws IOException {
        // Delegate to overload with null progress listener
        return runAutofocusBenchmark(referenceZ, outputPath, testDistances, quickMode, objective, null);
    }

    /**
     * Run autofocus parameter benchmark with progress reporting.
     *
     * This overload accepts a progress listener that receives updates after each trial.
     * Progress updates keep the socket connection alive during long benchmarks (which
     * can run for many hours with full parameter grids of 1000+ trials).
     *
     * @param referenceZ Known good focus Z position (must be manually verified in focus)
     * @param outputPath Output directory for benchmark results
     * @param testDistances List of distances (um) from focus to test, or null for defaults
     * @param quickMode If true, run reduced parameter space for faster results
     * @param objective Objective identifier for safety limits (e.g., "20X")
     * @param progressListener Optional listener for progress updates, may be null
     * @return Map with summary results including success_rate, timing_stats, and fastest configs
     * @throws IOException if communication fails or benchmark encounters errors
     */
    public Map<String, Object> runAutofocusBenchmark(
            double referenceZ,
            String outputPath,
            List<Double> testDistances,
            boolean quickMode,
            String objective,
            BenchmarkProgressListener progressListener) throws IOException {

        // Build AFBENCH command message
        StringBuilder message = new StringBuilder();
        message.append("--reference_z ").append(referenceZ);
        message.append(" --output ").append(outputPath);

        if (testDistances != null && !testDistances.isEmpty()) {
            message.append(" --distances ");
            for (int i = 0; i < testDistances.size(); i++) {
                if (i > 0) message.append(",");
                message.append(testDistances.get(i));
            }
        }

        if (quickMode) {
            message.append(" --quick true");
        }

        if (objective != null && !objective.isEmpty()) {
            message.append(" --objective ").append(objective);
        }

        message.append(" ").append(END_MARKER);

        byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

        logger.info("Sending autofocus benchmark command:");
        logger.info("  Reference Z: {} um", referenceZ);
        logger.info("  Output path: {}", outputPath);
        logger.info("  Test distances: {}", testDistances);
        logger.info("  Quick mode: {}", quickMode);
        logger.info("  Objective: {}", objective);

        synchronized (socketLock) {
            ensureConnected();

            // Per-read timeout: 10 minutes between progress updates should be sufficient
            // Even slow trials complete within a few minutes
            int perReadTimeout = 600000; // 10 minutes between messages
            int originalTimeout = readTimeout;
            try {
                if (socket != null) {
                    socket.setSoTimeout(perReadTimeout);
                    logger.debug("Set socket timeout to {} minutes for benchmark reads", perReadTimeout / 60000);
                }

                // Send AFBENCH command (8 bytes)
                output.write(Command.AFBENCH.getValue());
                output.flush();
                logger.debug("Sent AFBENCH command (8 bytes)");

                // Small delay to ensure command is processed
                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent benchmark message ({} bytes)", messageBytes.length);

                // Ensure all data is sent
                output.flush();

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Autofocus benchmark command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected autofocus benchmark: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Read responses in a loop until SUCCESS/FAILED
                // Server sends PROGRESS messages after each trial to keep connection alive
                logger.info("Waiting for autofocus benchmark to complete...");
                logger.info("Progress updates will be received after each trial...");

                while (true) {
                    bytesRead = input.read(buffer);
                    if (bytesRead <= 0) {
                        throw new IOException("Connection closed while waiting for benchmark response");
                    }

                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    lastActivityTime.set(System.currentTimeMillis());

                    // Handle progress updates
                    if (response.startsWith("PROGRESS:")) {
                        // Format: PROGRESS:current:total:status_message (consistent with PPMBIREF)
                        try {
                            String progressData = response.substring(9); // Remove "PROGRESS:"
                            String[] parts = progressData.split(":", 3); // Split into at most 3 parts

                            if (parts.length >= 2) {
                                int current = Integer.parseInt(parts[0].trim());
                                int total = Integer.parseInt(parts[1].trim());
                                String statusMsg = parts.length > 2 ? parts[2] : "";

                                // Log progress periodically (every 50 trials or at key percentages)
                                double percentComplete = (current * 100.0 / total);
                                if (current % 50 == 0 || current == 1 || current == total) {
                                    logger.info("Benchmark progress: {}/{} ({} %)",
                                            current, total, String.format("%.1f", percentComplete));
                                }

                                // Notify listener if provided
                                if (progressListener != null) {
                                    try {
                                        progressListener.onProgress(current, total, statusMsg);
                                    } catch (Exception e) {
                                        logger.warn("Progress listener threw exception: {}", e.getMessage());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Could not parse progress message: {}", response);
                        }
                        continue; // Keep reading for more messages
                    }

                    // Check for safety violation
                    if (response.startsWith("FAILED:SAFETY:")) {
                        String safetyMsg = response.substring("FAILED:SAFETY:".length());
                        throw new IOException("SAFETY VIOLATION: " + safetyMsg);
                    }

                    // Check for other failures
                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Benchmark failed: " + response.substring(7));
                    }

                    // Check for success
                    if (response.startsWith("SUCCESS:")) {
                        String jsonData = response.substring(8).trim();
                        logger.info("Benchmark completed successfully");
                        logger.info("Parsing benchmark results...");

                        Map<String, Object> results = new HashMap<>();
                        try {
                            results = parseSimpleJson(jsonData);
                            logger.info("Parsed {} result keys", results.size());
                        } catch (Exception e) {
                            logger.warn("Could not parse JSON results: {}", e.getMessage());
                            results.put("raw_response", jsonData);
                        }

                        lastActivityTime.set(System.currentTimeMillis());
                        return results;
                    }

                    // Unknown response type - log and continue
                    logger.warn("Unexpected response during benchmark: {}", response);
                }

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("Autofocus benchmark error", e));
                throw new IOException("Autofocus benchmark error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Simple JSON parser for benchmark results.
     * Handles basic types: strings, numbers, booleans, and nested objects.
     *
     * Note: This is a minimal parser for the specific benchmark response format.
     * For general JSON parsing, use a proper JSON library.
     */
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();

        // Remove outer braces
        String content = json.trim();
        if (content.startsWith("{")) content = content.substring(1);
        if (content.endsWith("}")) content = content.substring(0, content.length() - 1);

        // Split by commas (simple approach - doesn't handle nested objects properly)
        // For benchmark results, we just need top-level keys
        String[] pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

        for (String pair : pairs) {
            String[] keyValue = pair.split(":", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim().replace("\"", "");
                String value = keyValue[1].trim();

                // Parse value type
                if (value.equals("true") || value.equals("false")) {
                    result.put(key, Boolean.parseBoolean(value));
                } else if (value.equals("null")) {
                    result.put(key, null);
                } else if (value.startsWith("\"") && value.endsWith("\"")) {
                    result.put(key, value.substring(1, value.length() - 1));
                } else {
                    try {
                        if (value.contains(".")) {
                            result.put(key, Double.parseDouble(value));
                        } else {
                            result.put(key, Integer.parseInt(value));
                        }
                    } catch (NumberFormatException e) {
                        result.put(key, value); // Store as string if parsing fails
                    }
                }
            }
        }

        return result;
    }

    /**
     * Starts a polarizer calibration workflow on the server for PPM rotation stage.
     * This method uses the POLCAL command with parameters for angle sweep configuration.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for calibration report
     * @param startAngle Starting angle for sweep (degrees)
     * @param endAngle Ending angle for sweep (degrees)
     * @param stepSize Step size for angle sweep (degrees)
     * @param exposureMs Exposure time for images (milliseconds)
     * @return Path to the generated calibration report file
     * @throws IOException if communication fails
     */
    public String startPolarizerCalibration(String yamlPath, String outputPath,
                                           double startAngle, double endAngle,
                                           double stepSize, double exposureMs) throws IOException {

        // Build POLCAL-specific command message
        String message = String.format("--yaml %s --output %s --start %.1f --end %.1f --step %.1f --exposure %.1f %s",
                yamlPath, outputPath, startAngle, endAngle, stepSize, exposureMs, END_MARKER);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending polarizer calibration command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Store original timeout
            int originalTimeout = 0;
            try {
                originalTimeout = socket.getSoTimeout();
                // Increase timeout for calibration (can take several minutes)
                // With stability check (3 runs) and fine steps, allow plenty of time
                // Note: POLCAL does not send progress updates, so use generous total timeout
                socket.setSoTimeout(3600000); // 60 minutes
                logger.debug("Increased socket timeout to 60 minutes for calibration");
            } catch (IOException e) {
                logger.warn("Failed to adjust socket timeout", e);
            }

            try {
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                // Send command
                output.write("polcal__".getBytes(StandardCharsets.UTF_8));
                output.write(messageBytes);
                output.flush();

                logger.info("Command sent, waiting for server response...");

                // Read initial response (STARTED or FAILED)
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected polarizer calibration: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Wait for final SUCCESS/FAILED response
                logger.info("Waiting for polarizer calibration to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final server response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("Polarizer calibration failed: " + finalResponse.substring(7));
                    } else if (finalResponse.startsWith("SUCCESS:")) {
                        // Extract report path from SUCCESS response
                        String reportPath = finalResponse.substring(8).trim();
                        logger.info("Polarizer calibration successful. Report: {}", reportPath);
                        return reportPath;
                    } else {
                        throw new IOException("Unexpected final response: " + finalResponse);
                    }
                }

                throw new IOException("No response received from server");

            } catch (IOException e) {
                logger.error("Error during polarizer calibration", e);
                handleIOException(new IOException("Polarizer calibration error", e));
                throw new IOException("Polarizer calibration error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Runs PPM birefringence optimization test to find optimal polarizer angle.
     * This method uses the PPMBIREF command to systematically test angles and
     * identify the optimal angle for maximum birefringence signal contrast.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for test results and plots
     * @param minAngle Minimum angle to test (degrees)
     * @param maxAngle Maximum angle to test (degrees)
     * @param angleStep Step size for angle sweep (degrees)
     * @param exposureMode Exposure mode: "interpolate", "calibrate", or "fixed"
     * @param fixedExposureMs Fixed exposure in ms (required if mode="fixed", null otherwise)
     * @param targetIntensity Target intensity for calibrate mode (0-255)
     * @return Path to the results directory
     * @throws IOException if communication fails
     */
    public String runBirefringenceOptimization(String yamlPath, String outputPath,
                                               double minAngle, double maxAngle, double angleStep,
                                               String exposureMode, Double fixedExposureMs,
                                               int targetIntensity) throws IOException {
        return runBirefringenceOptimization(yamlPath, outputPath, minAngle, maxAngle, angleStep,
                exposureMode, fixedExposureMs, targetIntensity, null);
    }

    /**
     * Runs PPM birefringence optimization test with progress callback.
     * This overload supports real-time progress updates during execution.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for test results and plots
     * @param minAngle Minimum angle to test (degrees)
     * @param maxAngle Maximum angle to test (degrees)
     * @param angleStep Step size for angle sweep (degrees)
     * @param exposureMode Exposure mode: "interpolate", "calibrate", or "fixed"
     * @param fixedExposureMs Fixed exposure in ms (required if mode="fixed", null otherwise)
     * @param targetIntensity Target intensity for calibrate mode (0-255)
     * @param progressCallback Callback receiving (current, total) progress updates (can be null)
     * @return Path to the results directory
     * @throws IOException if communication fails
     */
    public String runBirefringenceOptimization(String yamlPath, String outputPath,
                                               double minAngle, double maxAngle, double angleStep,
                                               String exposureMode, Double fixedExposureMs,
                                               int targetIntensity,
                                               java.util.function.BiConsumer<Integer, Integer> progressCallback) throws IOException {
        return runBirefringenceOptimization(yamlPath, outputPath, minAngle, maxAngle, angleStep,
                exposureMode, fixedExposureMs, targetIntensity, progressCallback, null, null);
    }

    /**
     * Runs PPM birefringence optimization test with progress and stage move callbacks.
     * This overload supports real-time progress updates and stage move prompts for calibrate mode.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for test results and plots
     * @param minAngle Minimum angle to test (degrees)
     * @param maxAngle Maximum angle to test (degrees)
     * @param angleStep Step size for angle sweep (degrees)
     * @param exposureMode Exposure mode: "interpolate", "calibrate", or "fixed"
     * @param fixedExposureMs Fixed exposure in ms (required if mode="fixed", null otherwise)
     * @param targetIntensity Target intensity for calibrate mode (0-255)
     * @param progressCallback Callback receiving (current, total) progress updates (can be null)
     * @param stageMoveCallback Callback for calibrate mode stage move prompt. Returns true to continue, false to abort. (can be null)
     * @param statusCallback Callback for status messages like "Calibrating..." or "Acquiring..." (can be null)
     * @return Path to the results directory
     * @throws IOException if communication fails
     */
    public String runBirefringenceOptimization(String yamlPath, String outputPath,
                                               double minAngle, double maxAngle, double angleStep,
                                               String exposureMode, Double fixedExposureMs,
                                               int targetIntensity,
                                               java.util.function.BiConsumer<Integer, Integer> progressCallback,
                                               java.util.function.Supplier<Boolean> stageMoveCallback,
                                               java.util.function.Consumer<String> statusCallback) throws IOException {

        // Build PPMBIREF-specific command message
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("--yaml ").append(yamlPath)
                     .append(" --output ").append(outputPath)
                     .append(" --mode ").append(exposureMode)
                     .append(" --min-angle ").append(minAngle)
                     .append(" --max-angle ").append(maxAngle)
                     .append(" --step ").append(angleStep);

        if ("fixed".equals(exposureMode) && fixedExposureMs != null) {
            messageBuilder.append(" --exposure ").append(fixedExposureMs);
        }

        messageBuilder.append(" --target-intensity ").append(targetIntensity)
                     .append(" ").append(END_MARKER);

        String message = messageBuilder.toString();
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending PPM birefringence optimization command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Store original timeout
            int originalTimeout = 0;
            try {
                originalTimeout = socket.getSoTimeout();
                // Increase timeout for birefringence test (can take many minutes)
                // In calibrate mode, Phase 1 iterates all angles with adaptive exposure
                // optimization (~12 iterations per angle x 201 angles = potentially 45+ minutes)
                // Phase 2 then runs paired acquisition. Allow 90 minutes total.
                socket.setSoTimeout(5400000); // 90 minutes
                logger.debug("Increased socket timeout to 90 minutes for birefringence optimization");
            } catch (IOException e) {
                logger.warn("Failed to adjust socket timeout", e);
            }

            try {
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                // Send command
                output.write(Command.PPMBIREF.getValue());
                output.write(messageBytes);
                output.flush();

                logger.info("Command sent, waiting for server response...");

                // Read initial response (STARTED or FAILED)
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected birefringence optimization: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Read responses in a loop until SUCCESS or FAILED
                // Handles PROGRESS:current:total messages in between
                logger.info("Waiting for birefringence optimization to complete...");
                while (true) {
                    bytesRead = input.read(buffer);
                    if (bytesRead <= 0) {
                        throw new IOException("No response received from server");
                    }

                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    lastActivityTime.set(System.currentTimeMillis());

                    // Handle PROGRESS updates
                    if (response.startsWith("PROGRESS:")) {
                        // Parse "PROGRESS:current:total"
                        try {
                            String[] parts = response.substring(9).split(":");
                            if (parts.length >= 2) {
                                int current = Integer.parseInt(parts[0].trim());
                                int total = Integer.parseInt(parts[1].trim());
                                logger.debug("Progress update: {}/{}", current, total);

                                if (progressCallback != null) {
                                    progressCallback.accept(current, total);
                                }
                            }
                        } catch (NumberFormatException e) {
                            logger.warn("Failed to parse progress message: {}", response);
                        }
                        continue; // Keep reading for next message
                    }

                    // Handle STAGEMOVE request (calibrate mode - background calibration complete)
                    if (response.startsWith("STAGEMOVE:")) {
                        String stageMessage = response.substring(10);
                        logger.info("Stage move requested: {}", stageMessage);

                        // Update status if callback provided
                        if (statusCallback != null) {
                            statusCallback.accept("Background calibration complete - move stage to tissue");
                        }

                        // Call stage move callback to show dialog and wait for user
                        boolean shouldContinue = true;
                        if (stageMoveCallback != null) {
                            shouldContinue = stageMoveCallback.get();
                        }

                        // Send response back to server
                        String responseMsg = shouldContinue ? "CONTINUE" : "ABORT";
                        output.write(responseMsg.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        logger.info("Sent {} response for stage move", responseMsg);

                        if (!shouldContinue) {
                            throw new IOException("Birefringence optimization aborted by user");
                        }
                        continue; // Keep reading for next message
                    }

                    // Handle final responses
                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Birefringence optimization failed: " + response.substring(7));
                    } else if (response.startsWith("SUCCESS:")) {
                        // Extract results directory path from SUCCESS response
                        String resultPath = response.substring(8).trim();
                        logger.info("Birefringence optimization successful. Results: {}", resultPath);
                        return resultPath;
                    } else {
                        logger.warn("Unexpected response during optimization: {}", response);
                        // Continue reading - might be a partial message
                    }
                }

            } catch (IOException e) {
                logger.error("Error during birefringence optimization", e);
                handleIOException(new IOException("Birefringence optimization error", e));
                throw new IOException("Birefringence optimization error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Runs sunburst calibration for hue-to-angle mapping.
     * This method uses the SBCALIB command to acquire a calibration slide image
     * and run RadialCalibrator to create a linear regression model.
     *
     * @param yamlPath Path to microscope configuration YAML file
     * @param outputPath Output directory for calibration results
     * @param modality Modality name for exposure lookup (e.g., "ppm_20x")
     * @param expectedSpokes Number of spokes in sunburst pattern (typically 16)
     * @param saturationThreshold Minimum saturation for foreground detection (0-1)
     * @param valueThreshold Minimum brightness for foreground detection (0-1)
     * @param calibrationName Optional name for output files (auto-generated if null)
     * @param radiusInner Inner sampling radius in pixels from center
     * @param radiusOuter Outer sampling radius in pixels from center
     * @param imagePath Path to existing image to reuse (null for normal acquisition)
     * @param centerY Manual center Y pixel coordinate (null for auto-detect)
     * @param centerX Manual center X pixel coordinate (null for auto-detect)
     * @return JSON string with calibration results
     * @throws IOException if communication fails
     */
    public String runSunburstCalibration(String yamlPath, String outputPath, String modality,
                                          int expectedSpokes, double saturationThreshold,
                                          double valueThreshold, String calibrationName,
                                          int radiusInner, int radiusOuter,
                                          String imagePath, Integer centerY,
                                          Integer centerX) throws IOException {

        // Build SBCALIB-specific command message
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("--yaml ").append(yamlPath)
                     .append(" --output ").append(outputPath)
                     .append(" --modality ").append(modality)
                     .append(" --spokes ").append(expectedSpokes)
                     .append(" --saturation ").append(saturationThreshold)
                     .append(" --value ").append(valueThreshold);

        if (calibrationName != null && !calibrationName.isEmpty()) {
            messageBuilder.append(" --name ").append(calibrationName);
        }

        messageBuilder.append(" --radius_inner ").append(radiusInner)
                     .append(" --radius_outer ").append(radiusOuter);

        if (imagePath != null && !imagePath.isEmpty()) {
            messageBuilder.append(" --image_path ").append(imagePath);
        }
        if (centerY != null && centerX != null) {
            messageBuilder.append(" --center_y ").append(centerY)
                         .append(" --center_x ").append(centerX);
        }

        messageBuilder.append(" ").append(END_MARKER);

        String message = messageBuilder.toString();
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

        logger.info("Sending sunburst calibration command:");
        logger.info("  Message length: {} bytes", messageBytes.length);
        logger.info("  Message content: {}", message);

        synchronized (socketLock) {
            ensureConnected();

            // Store original timeout
            int originalTimeout = 0;
            try {
                originalTimeout = socket.getSoTimeout();
                // Calibration should be quick (single image), but allow 5 minutes for processing
                socket.setSoTimeout(300000); // 5 minutes
                logger.debug("Increased socket timeout to 5 minutes for sunburst calibration");
            } catch (IOException e) {
                logger.warn("Failed to adjust socket timeout", e);
            }

            try {
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                // Send command
                output.write(Command.SBCALIB.getValue());
                output.write(messageBytes);
                output.flush();

                logger.info("Command sent, waiting for server response...");

                // Read initial response (STARTED or FAILED)
                byte[] buffer = new byte[8192];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received initial server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("Server rejected sunburst calibration: " + response);
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial server response: {}", response);
                    }
                }

                // Wait for final response (SUCCESS with JSON or FAILED)
                logger.info("Waiting for sunburst calibration to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead <= 0) {
                    throw new IOException("No response received from server");
                }

                String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);

                if (response.startsWith("FAILED:")) {
                    throw new IOException("Sunburst calibration failed: " + response.substring(7));
                } else if (response.startsWith("SUCCESS:")) {
                    // Extract JSON result
                    String resultJson = response.substring(8).trim();
                    logger.info("Sunburst calibration successful");
                    return resultJson;
                } else {
                    logger.warn("Unexpected response: {}", response);
                    throw new IOException("Unexpected server response: " + response);
                }

            } catch (IOException e) {
                logger.error("Error during sunburst calibration", e);
                handleIOException(new IOException("Sunburst calibration error", e));
                throw new IOException("Sunburst calibration error: " + e.getMessage(), e);
            } finally {
                // Restore original timeout
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                        logger.debug("Restored socket timeout to {}ms", originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore original socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Executes a command and optionally waits for response.
     *
     * @param command Command to execute
     * @param data Optional data to send with command
     * @param expectedResponseBytes Number of bytes to read in response (0 for no response)
     * @return Response bytes or empty array if no response expected
     * @throws IOException if communication fails
     */
    private byte[] executeCommand(Command command, byte[] data, int expectedResponseBytes) throws IOException {
        synchronized (socketLock) {
            ensureConnected();

            try {
                // Send command
                output.write(command.getValue());

                // Send data if provided
                if (data != null && data.length > 0) {
                    output.write(data);
                }

                output.flush();
                lastActivityTime.set(System.currentTimeMillis());

                // Read response if expected
                if (expectedResponseBytes > 0) {
                    byte[] response = new byte[expectedResponseBytes];
                    input.readFully(response);
                    lastActivityTime.set(System.currentTimeMillis());
                    return response;
                }

                return new byte[0];

            } catch (IOException e) {
                handleIOException(e);
                throw e;
            }
        }
    }

    /**
     * Sends a command without expecting response.
     *
     * @param command Command to send
     * @throws IOException if communication fails
     */
    private void sendCommand(Command command) throws IOException {
        executeCommand(command, null, 0);
    }

    /**
     * Ensures the client is connected, attempting reconnection if necessary.
     *
     * @throws IOException if connection cannot be established
     */
    private void ensureConnected() throws IOException {
        if (!connected.get()) {
            connect();
        }
    }

    /**
     * Handles IO exceptions by triggering reconnection if appropriate.
     *
     * @param e The exception to handle
     */
    private void handleIOException(IOException e) {
        logger.error("Communication error with microscope server", e);

        // Mark as disconnected
        connected.set(false);

        // Schedule reconnection attempt
        if (!shuttingDown.get()) {
            scheduleReconnection();
        }
    }

    /**
     * Schedules automatic reconnection attempts.
     */
    private void scheduleReconnection() {
        reconnectExecutor.submit(() -> {
            int attempts = 0;

            while (attempts < maxReconnectAttempts && !connected.get() && !shuttingDown.get()) {
                attempts++;
                logger.info("Reconnection attempt {} of {}", attempts, maxReconnectAttempts);

                try {
                    Thread.sleep(reconnectDelayMs);
                    connect();
                    logger.info("Successfully reconnected to microscope server");
                    break;
                } catch (Exception e) {
                    logger.warn("Reconnection attempt {} failed: {}", attempts, e.getMessage());
                }
            }

            if (!connected.get() && !shuttingDown.get()) {
                logger.error("Failed to reconnect after {} attempts", maxReconnectAttempts);
            }
        });
    }

    /**
     * Starts health monitoring thread.
     */
    private void startHealthMonitoring() {
        healthCheckExecutor.scheduleWithFixedDelay(() -> {
            if (connected.get() && !shuttingDown.get()) {
                long idleTime = System.currentTimeMillis() - lastActivityTime.get();

                // Perform health check if idle for too long
                if (idleTime > healthCheckIntervalMs) {
                    try {
                        // Simple health check - get stage position
                        getStageXY();
                        logger.debug("Health check passed");
                    } catch (Exception e) {
                        logger.warn("Health check failed: {}", e.getMessage());
                        connected.set(false);
                        scheduleReconnection();
                    }
                }
            }
        }, healthCheckIntervalMs, healthCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Cleans up socket resources (primary and auxiliary).
     */
    private void cleanup() {
        // Clean up primary socket
        try {
            if (input != null) {
                input.close();
                input = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing input stream", e);
        }

        try {
            if (output != null) {
                output.close();
                output = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing output stream", e);
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (Exception e) {
            logger.debug("Error closing socket", e);
        }

        connected.set(false);

        // Also clean up auxiliary socket
        cleanupAuxiliary();
    }

    /**
     * Checks if the client is currently connected.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Probes a server to check if it is available and responding.
     * This is a quick connectivity test that does not establish a persistent connection.
     *
     * @param host The server host
     * @param port The server port
     * @param timeoutMs Connection timeout in milliseconds
     * @return ServerProbeResult containing connection status and details
     */
    public static ServerProbeResult probeServer(String host, int port, int timeoutMs) {
        Socket testSocket = null;
        try {
            testSocket = new Socket();
            testSocket.setSoTimeout(timeoutMs);
            testSocket.connect(new InetSocketAddress(host, port), timeoutMs);

            // Try to send a simple GETXY command
            DataOutputStream out = new DataOutputStream(testSocket.getOutputStream());
            DataInputStream in = new DataInputStream(testSocket.getInputStream());

            out.write("getxy___".getBytes());
            out.flush();

            // Read response (8 bytes for two floats)
            byte[] response = new byte[8];
            in.readFully(response);

            // Parse response
            ByteBuffer buffer = ByteBuffer.wrap(response);
            buffer.order(ByteOrder.BIG_ENDIAN);
            float x = buffer.getFloat();
            float y = buffer.getFloat();

            return new ServerProbeResult(true, true, host, port,
                    String.format("Server responding. Stage position: (%.2f, %.2f)", x, y));

        } catch (ConnectException e) {
            return new ServerProbeResult(false, false, host, port,
                    "Connection refused - no server running on port " + port);
        } catch (SocketTimeoutException e) {
            return new ServerProbeResult(false, false, host, port,
                    "Connection timed out - server may not be running");
        } catch (IOException e) {
            // Connected but had issues
            return new ServerProbeResult(true, false, host, port,
                    "Connected but server not responding correctly: " + e.getMessage());
        } finally {
            if (testSocket != null) {
                try {
                    testSocket.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Result of a server probe operation.
     */
    public static class ServerProbeResult {
        private final boolean canConnect;
        private final boolean isResponding;
        private final String host;
        private final int port;
        private final String message;

        public ServerProbeResult(boolean canConnect, boolean isResponding, String host, int port, String message) {
            this.canConnect = canConnect;
            this.isResponding = isResponding;
            this.host = host;
            this.port = port;
            this.message = message;
        }

        /** Returns true if TCP connection could be established */
        public boolean canConnect() { return canConnect; }

        /** Returns true if server is responding correctly to commands */
        public boolean isResponding() { return isResponding; }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getMessage() { return message; }

        @Override
        public String toString() {
            return String.format("ServerProbeResult[%s:%d, connect=%s, responding=%s, msg='%s']",
                    host, port, canConnect, isResponding, message);
        }
    }

    /**
     * Shuts down the microscope server.
     * This will terminate the server process.
     *
     * @throws IOException if communication fails
     */
    public void shutdownServer() throws IOException {
        logger.warn("Sending shutdown command to microscope server");
        sendCommand(Command.SHUTDOWN);
        disconnect();
    }

    /**
     * Closes the client and releases all resources.
     */
    @Override
    public void close() {
        shuttingDown.set(true);

        // Stop executors
        reconnectExecutor.shutdown();
        healthCheckExecutor.shutdown();

        try {
            reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS);
            healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while shutting down executors", e);
        }

        // Disconnect
        disconnect();

        logger.info("Microscope socket client closed");
    }
    /**
     * Gets the current acquisition status.
     *
     * @return Current acquisition state
     * @throws IOException if communication fails
     */
    public AcquisitionState getAcquisitionStatus() throws IOException {
        // First, read initial response to check state
        byte[] initialResponse = executeCommand(Command.STATUS, null, 16);
        String stateStr = new String(initialResponse, StandardCharsets.UTF_8);

        // Check if this is a FAILED message with additional details
        if (stateStr.startsWith("FAILED:")) {
            // Read additional bytes for the full error message (up to 512 bytes total)
            synchronized (socketLock) {
                try {
                    byte[] additionalBytes = new byte[496]; // 512 - 16 already read
                    int bytesRead = input.read(additionalBytes);

                    if (bytesRead > 0) {
                        // Combine initial response with additional bytes
                        String additionalStr = new String(additionalBytes, 0, bytesRead, StandardCharsets.UTF_8);
                        stateStr = stateStr + additionalStr;
                    }
                } catch (IOException e) {
                    logger.warn("Could not read additional failure message bytes", e);
                }
            }

            String failureDetails = stateStr.substring("FAILED:".length()).trim();
            lastFailureMessage = failureDetails.isEmpty() ? "Unknown server error" : failureDetails;
            logger.error("Received FAILED message during status check: {} - Details: {}",
                    stateStr.trim(), lastFailureMessage);
            return AcquisitionState.FAILED;
        } else if (stateStr.startsWith("SUCCESS:")) {
            logger.info("Received SUCCESS message during status check: {}", stateStr.trim());
            lastFailureMessage = null; // Clear any previous failure message
            return AcquisitionState.COMPLETED;
        } else if (stateStr.startsWith("COMPLETED")) {
            // Check for extended format with final_z: "COMPLETED|final_z:1234.56"
            // Read additional bytes if needed
            synchronized (socketLock) {
                try {
                    byte[] additionalBytes = new byte[64]; // Enough for "COMPLETED|final_z:1234.56"
                    int bytesRead = input.read(additionalBytes);

                    if (bytesRead > 0) {
                        String additionalStr = new String(additionalBytes, 0, bytesRead, StandardCharsets.UTF_8);
                        stateStr = stateStr + additionalStr;
                    }
                } catch (IOException e) {
                    // May timeout if no additional data - that's fine
                    logger.debug("No additional bytes for COMPLETED status");
                }
            }

            // Parse final_z if present: "COMPLETED|final_z:1234.56"
            lastFailureMessage = null;
            lastAcquisitionFinalZ = null;

            if (stateStr.contains("|final_z:")) {
                try {
                    int startIdx = stateStr.indexOf("|final_z:") + "|final_z:".length();
                    String zStr = stateStr.substring(startIdx).trim();
                    // Handle potential trailing characters
                    int endIdx = 0;
                    while (endIdx < zStr.length() &&
                           (Character.isDigit(zStr.charAt(endIdx)) || zStr.charAt(endIdx) == '.' || zStr.charAt(endIdx) == '-')) {
                        endIdx++;
                    }
                    if (endIdx > 0) {
                        lastAcquisitionFinalZ = Double.parseDouble(zStr.substring(0, endIdx));
                        logger.info("Parsed final Z from COMPLETED status: {} um", lastAcquisitionFinalZ);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Could not parse final_z from COMPLETED response: {}", stateStr);
                }
            }

            logger.info("Received COMPLETED status{}",
                lastAcquisitionFinalZ != null ? " with final_z: " + lastAcquisitionFinalZ + " um" : "");
            return AcquisitionState.COMPLETED;
        }

        AcquisitionState state = AcquisitionState.fromString(stateStr);
        logger.debug("Acquisition status: {}", state);
        return state;
    }

    /**
     * Gets the last failure message received from the server.
     * This provides detailed information about why an acquisition failed.
     * 
     * @return The last failure message, or null if no failure occurred or message is unavailable
     */
    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    /**
     * Gets the final Z-focus position from the last completed acquisition.
     *
     * <p>This value is used by the tilt correction model to build a plane
     * that predicts optimal Z positions across the slide.</p>
     *
     * @return The final Z position in micrometers, or null if not available
     */
    public Double getLastAcquisitionFinalZ() {
        return lastAcquisitionFinalZ;
    }

    /**
     * Clears the stored final Z value.
     * Should be called before starting a new acquisition.
     */
    public void clearLastAcquisitionFinalZ() {
        lastAcquisitionFinalZ = null;
    }

    /**
     * Gets the current acquisition progress.
     *
     * @return Acquisition progress (current/total images)
     * @throws IOException if communication fails
     */
    public AcquisitionProgress getAcquisitionProgress() throws IOException {
        byte[] response = executeCommand(Command.PROGRESS, null, 8);

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int current = buffer.getInt();
        int total = buffer.getInt();

        AcquisitionProgress progress = new AcquisitionProgress(current, total);
        logger.debug("Acquisition progress: {}", progress);
        return progress;
    }

    /**
     * Cancels the currently running acquisition.
     *
     * @return true if cancellation was acknowledged
     * @throws IOException if communication fails
     */
    public boolean cancelAcquisition() throws IOException {
        byte[] response = executeCommand(Command.CANCEL, null, 3);
        String ack = new String(response, StandardCharsets.UTF_8);
        boolean cancelled = "ACK".equals(ack);
        logger.info("Acquisition cancellation {}", cancelled ? "acknowledged" : "failed");
        return cancelled;
    }

    /**
     * Checks if manual focus is requested by the server and returns retry count.
     * This should be called periodically during acquisition to detect autofocus failures.
     *
     * @return number of retries remaining (0+) if manual focus is requested, or -1 if not needed
     * @throws IOException if communication fails
     */
    public int isManualFocusRequested() throws IOException {
        byte[] response = executeCommand(Command.REQMANF, null, 8);
        String status = new String(response, StandardCharsets.UTF_8).trim();

        if (status.equals("IDLE____")) {
            return -1;  // No manual focus needed
        } else if (status.startsWith("NEEDED")) {
            // Format: "NEEDEDnn" where nn is 00-99
            try {
                String retriesStr = status.substring(6);  // Get last 2 characters
                return Integer.parseInt(retriesStr);
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                logger.warn("Failed to parse retries from manual focus response: {}", status);
                return 0;  // Default to 0 retries if parsing fails
            }
        } else {
            logger.warn("Unknown manual focus status: {}", status);
            return -1;
        }
    }

    /**
     * Acknowledges manual focus completion - retry autofocus.
     * Call this after the user has manually focused and wants to retry autofocus.
     *
     * @return true if acknowledgment was successful
     * @throws IOException if communication fails
     */
    public boolean acknowledgeManualFocus() throws IOException {
        byte[] response = executeCommand(Command.ACKMF, null, 3);
        String ack = new String(response, StandardCharsets.UTF_8).trim();
        boolean acknowledged = "ACK".equals(ack);
        logger.info("Manual focus retry autofocus {}", acknowledged ? "acknowledged" : "failed");
        // Reset progress timeout since user has responded and acquisition will resume
        if (acknowledged) {
            resetProgressTimeout();
        }
        return acknowledged;
    }

    /**
     * Skip autofocus retry - use current focus position.
     * Call this when user has manually focused and wants to use current position.
     *
     * @return true if acknowledgment was successful
     * @throws IOException if communication fails
     */
    public boolean skipAutofocusRetry() throws IOException {
        byte[] response = executeCommand(Command.SKIPAF, null, 3);
        String ack = new String(response, StandardCharsets.UTF_8).trim();
        boolean acknowledged = "ACK".equals(ack);
        logger.info("Manual focus skip retry {}", acknowledged ? "acknowledged" : "failed");
        // Reset progress timeout since user has responded and acquisition will resume
        if (acknowledged) {
            resetProgressTimeout();
        }
        return acknowledged;
    }

    /**
     * Resets the progress timeout timer.
     * Call this when a significant event occurs that should reset the timeout
     * (e.g., manual focus acknowledgment, user interaction).
     */
    public void resetProgressTimeout() {
        lastProgressUpdateTime.set(System.currentTimeMillis());
        logger.debug("Progress timeout reset");
    }

    /**
     * Monitors acquisition progress until completion or timeout.
     * Calls the progress callback periodically.
     *
     * @param progressCallback Callback for progress updates (can be null)
     * @param pollIntervalMs Interval between progress checks in milliseconds
     * @param timeoutMs Maximum time to wait in milliseconds (0 for no timeout)
     * @return Final acquisition state
     * @throws IOException if communication fails
     * @throws InterruptedException if thread is interrupted
     */
    public AcquisitionState monitorAcquisition(
            Consumer<AcquisitionProgress> progressCallback,
            long pollIntervalMs,
            long timeoutMs) throws IOException, InterruptedException {
        return monitorAcquisition(progressCallback, null, pollIntervalMs, timeoutMs);
    }

    /**
     * Monitors acquisition progress until completion or timeout.
     * Calls the progress callback periodically, and handles manual focus requests
     * via the dedicated manualFocusCallback.
     *
     * @param progressCallback Callback for progress updates (can be null)
     * @param manualFocusCallback Callback for manual focus requests, receives retries remaining (can be null)
     * @param pollIntervalMs Interval between progress checks in milliseconds
     * @param timeoutMs Maximum time to wait in milliseconds (0 for no timeout)
     * @return Final acquisition state
     * @throws IOException if communication fails
     * @throws InterruptedException if thread is interrupted
     */
    public AcquisitionState monitorAcquisition(
            Consumer<AcquisitionProgress> progressCallback,
            Consumer<Integer> manualFocusCallback,
            long pollIntervalMs,
            long timeoutMs) throws IOException, InterruptedException {

        long startTime = System.currentTimeMillis();
        // Use instance field instead of local variable so it can be reset externally
        lastProgressUpdateTime.set(startTime);
        int lastProgressCount = -1;  // Initialize to -1 to detect first progress
        AcquisitionState lastState = AcquisitionState.IDLE;
        int retryCount = 0;
        final int maxInitialRetries = 3;

        while (true) {
            try {
                // Check status
                AcquisitionState currentState = getAcquisitionStatus();

                // Reset retry count on successful read
                retryCount = 0;

                // Check if terminal state reached
                if (currentState == AcquisitionState.COMPLETED ||
                        currentState == AcquisitionState.FAILED ||
                        currentState == AcquisitionState.CANCELLED) {
                    logger.info("Acquisition reached terminal state: {}", currentState);
                    return currentState;
                }

                // Check for manual focus request - skip if cancelling (server may still report NEEDED)
                if (currentState != AcquisitionState.CANCELLING) try {
                    int manualFocusRetries = isManualFocusRequested();
                    if (manualFocusRetries >= 0) {
                        // Manual focus is requested - reset timeout since we're waiting for user input
                        lastProgressUpdateTime.set(System.currentTimeMillis());
                        if (manualFocusCallback != null) {
                            // Delegate handling to caller (may block for dialog)
                            manualFocusCallback.accept(manualFocusRetries);
                            // Reset timeout again after handling (dialog may have taken time)
                            lastProgressUpdateTime.set(System.currentTimeMillis());
                        } else {
                            logger.debug("Manual focus requested (retries: {}) - resetting progress timeout (no handler)", manualFocusRetries);
                        }
                    }
                } catch (IOException e) {
                    logger.debug("Failed to check manual focus status: {}", e.getMessage());
                    // Not critical - just continue monitoring
                }

                // Get progress if running
                if (currentState == AcquisitionState.RUNNING && progressCallback != null) {
                    try {
                        AcquisitionProgress progress = getAcquisitionProgress();

                        // For background acquisition, progress might start at -1/-1, which is normal
                        // Only report valid progress values
                        if (progress != null && progress.current >= 0 && progress.total >= 0) {
                            progressCallback.accept(progress);

                            // Check if progress was actually made
                            if (progress.current > lastProgressCount) {
                                lastProgressUpdateTime.set(System.currentTimeMillis());
                                lastProgressCount = progress.current;
                                logger.debug("Progress updated: {}/{} files, resetting timeout", progress.current, progress.total);
                            }
                        } else {
                            // Invalid progress (-1/-1), but still reset timeout if we got a response
                            lastProgressUpdateTime.set(System.currentTimeMillis());
                            logger.debug("Received progress response (server still working): {}/{}",
                                    progress != null ? progress.current : "null",
                                    progress != null ? progress.total : "null");
                        }
                    } catch (IOException e) {
                        logger.debug("Failed to get progress (expected during background acquisition): {}", e.getMessage());
                        // For background acquisition, progress queries might fail, so don't treat as error
                        // Just reset timeout to show server is still responsive
                        lastProgressUpdateTime.set(System.currentTimeMillis());
                    }
                }

                // Check timeout based on last progress, not total time
                if (timeoutMs > 0) {
                    long timeSinceProgress = System.currentTimeMillis() - lastProgressUpdateTime.get();
                    if (timeSinceProgress > timeoutMs) {
                        logger.warn("No progress for {} ms (last progress: {} files), timing out",
                                timeSinceProgress, lastProgressCount);
                        break;
                    }
                }

                // Log state changes
                if (currentState != lastState) {
                    logger.info("Acquisition state changed: {} -> {}", lastState, currentState);
                    lastState = currentState;

                    // Reset progress timer on state change to RUNNING
                    if (currentState == AcquisitionState.RUNNING) {
                        lastProgressUpdateTime.set(System.currentTimeMillis());
                    }
                }

                Thread.sleep(pollIntervalMs);

            } catch (IOException e) {
                // Handle initial connection issues gracefully
                if (retryCount < maxInitialRetries &&
                        System.currentTimeMillis() - startTime < 10000) {
                    retryCount++;
                    logger.debug("Initial status check failed (attempt {}/{}), retrying: {}",
                            retryCount, maxInitialRetries, e.getMessage());
                    Thread.sleep(1000); // Wait a bit longer before retry
                    continue;
                }
                throw e;
            }
        }

        return lastState;
    }

    // ==================== WHITE BALANCE CALIBRATION ====================

    /**
     * Result of white balance calibration for a single configuration (angle/exposure).
     */
    public static class WhiteBalanceResult {
        public final double exposureRed;
        public final double exposureGreen;
        public final double exposureBlue;
        public final double gainRed;
        public final double gainGreen;
        public final double gainBlue;
        public final boolean converged;

        public WhiteBalanceResult(double exposureRed, double exposureGreen, double exposureBlue,
                                  double gainRed, double gainGreen, double gainBlue,
                                  boolean converged) {
            this.exposureRed = exposureRed;
            this.exposureGreen = exposureGreen;
            this.exposureBlue = exposureBlue;
            this.gainRed = gainRed;
            this.gainGreen = gainGreen;
            this.gainBlue = gainBlue;
            this.converged = converged;
        }

        @Override
        public String toString() {
            return String.format("WhiteBalanceResult[R=%.2f/%.3fx, G=%.2f/%.3fx, B=%.2f/%.3fx, converged=%s]",
                    exposureRed, gainRed, exposureGreen, gainGreen, exposureBlue, gainBlue, converged);
        }

        // Convenience constructor for backward compatibility (gains default to 1.0)
        public WhiteBalanceResult(double exposureRed, double exposureGreen, double exposureBlue, boolean converged) {
            this(exposureRed, exposureGreen, exposureBlue, 1.0, 1.0, 1.0, converged);
        }
    }

    /**
     * Run simple white balance calibration at a single starting exposure.
     *
     * This calibrates the camera at the current PPM angle (no rotation) using
     * the specified initial exposure as the starting point for all channels.
     *
     * @param outputPath Output directory for calibration results
     * @param initialExposureMs Starting exposure time for all channels (ms)
     * @param targetIntensity Target mean intensity (0-255, default 180)
     * @param tolerance Acceptable deviation from target (default 5)
     * @return WhiteBalanceResult with per-channel exposure times
     * @throws IOException if communication fails or calibration fails
     */
    public WhiteBalanceResult runSimpleWhiteBalance(
            String outputPath,
            double initialExposureMs,
            double targetIntensity,
            double tolerance,
            double maxGainDb,
            double gainThresholdRatio,
            int maxIterations,
            boolean calibrateBlackLevel,
            double baseGain,
            double exposureSoftCapMs,
            double boostedMaxGainDb,
            String yamlPath,
            String objective,
            String detector) throws IOException {

        // Build WBSIMPLE command message
        StringBuilder message = new StringBuilder();
        if (yamlPath != null && !yamlPath.isEmpty()) {
            message.append("--yaml ").append(yamlPath).append(" ");
        }
        if (objective != null && !objective.isEmpty()) {
            message.append("--objective ").append(objective).append(" ");
        }
        if (detector != null && !detector.isEmpty()) {
            message.append("--detector ").append(detector).append(" ");
        }
        message.append("--output ").append(outputPath);
        message.append(" --exposure ").append(initialExposureMs);
        message.append(" --target ").append(targetIntensity);
        message.append(" --tolerance ").append(tolerance);
        message.append(" --max_gain_db ").append(maxGainDb);
        message.append(" --gain_threshold ").append(gainThresholdRatio);
        message.append(" --max_iterations ").append(maxIterations);
        message.append(" --calibrate_black_level ").append(calibrateBlackLevel ? "true" : "false");
        message.append(" --base_gain ").append(baseGain);
        message.append(" --exposure_soft_cap_ms ").append(exposureSoftCapMs);
        message.append(" --boosted_max_gain_db ").append(boostedMaxGainDb);
        message.append(" ").append(END_MARKER);

        byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

        logger.info("Sending simple white balance command:");
        logger.info("  Output path: {}", outputPath);
        logger.info("  Initial exposure: {} ms", initialExposureMs);
        logger.info("  Target intensity: {}", targetIntensity);
        logger.info("  Tolerance: {}", tolerance);
        logger.info("  Advanced: maxGain={}dB, gainThreshold={}, maxIter={}, calibrateBL={}",
                maxGainDb, gainThresholdRatio, maxIterations, calibrateBlackLevel);
        logger.info("  Gain algo: baseGain={}, exposureSoftCap={}ms, boostedMaxGain={}dB",
                baseGain, exposureSoftCapMs, boostedMaxGainDb);

        synchronized (socketLock) {
            ensureConnected();

            int originalTimeout = readTimeout;
            try {
                // White balance can take 2-3 minutes for full calibration
                if (socket != null) {
                    socket.setSoTimeout(180000); // 3 minutes
                    logger.debug("Set socket timeout to 3 minutes for white balance");
                }

                // Send WBSIMPLE command (8 bytes)
                output.write(Command.WBSIMPLE.getValue());
                output.flush();
                logger.debug("Sent WBSIMPLE command (8 bytes)");

                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent white balance message ({} bytes)", messageBytes.length);

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("Simple white balance command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[2048];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("White balance failed: " + response.substring(7));
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial response: {}", response);
                    }
                }

                // Wait for final SUCCESS/FAILED response
                logger.info("Waiting for white balance calibration to complete...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("White balance failed: " + finalResponse.substring(7));
                    }

                    if (finalResponse.startsWith("SUCCESS:")) {
                        return parseSimpleWBResponse(finalResponse);
                    }
                }

                throw new IOException("No valid response received from white balance calibration");

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("White balance error", e));
                throw new IOException("White balance error: " + e.getMessage(), e);
            } finally {
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Run PPM white balance calibration at 4 standard angles with per-angle targets.
     *
     * This calibrates the camera at each of the 4 PPM angles (positive, negative,
     * crossed, uncrossed) using different starting exposures and target intensities
     * for each angle.
     *
     * @param outputPath Output directory for calibration results
     * @param positiveAngle Positive angle (typically 7.0 degrees)
     * @param positiveExposure Initial exposure for positive angle (ms)
     * @param positiveTarget Target intensity for positive angle (0-255)
     * @param negativeAngle Negative angle (typically -7.0 degrees)
     * @param negativeExposure Initial exposure for negative angle (ms)
     * @param negativeTarget Target intensity for negative angle (0-255)
     * @param crossedAngle Crossed angle (typically 0.0 degrees)
     * @param crossedExposure Initial exposure for crossed angle (ms)
     * @param crossedTarget Target intensity for crossed angle (0-255)
     * @param uncrossedAngle Uncrossed angle (typically 90.0 degrees)
     * @param uncrossedExposure Initial exposure for uncrossed angle (ms)
     * @param uncrossedTarget Target intensity for uncrossed angle (0-255)
     * @param targetIntensity Default target intensity (0-255, used as fallback)
     * @param tolerance Acceptable deviation from target (default 5)
     * @return Map of angle names to WhiteBalanceResult
     * @throws IOException if communication fails or calibration fails
     */
    public Map<String, WhiteBalanceResult> runPPMWhiteBalance(
            String outputPath,
            double positiveAngle, double positiveExposure, double positiveTarget,
            double negativeAngle, double negativeExposure, double negativeTarget,
            double crossedAngle, double crossedExposure, double crossedTarget,
            double uncrossedAngle, double uncrossedExposure, double uncrossedTarget,
            double targetIntensity,
            double tolerance,
            double maxGainDb,
            double gainThresholdRatio,
            int maxIterations,
            boolean calibrateBlackLevel,
            double baseGain,
            double exposureSoftCapMs,
            double boostedMaxGainDb,
            String yamlPath,
            String objective,
            String detector) throws IOException {

        // Build WBPPM command message
        StringBuilder message = new StringBuilder();
        if (yamlPath != null && !yamlPath.isEmpty()) {
            message.append("--yaml ").append(yamlPath).append(" ");
        }
        if (objective != null && !objective.isEmpty()) {
            message.append("--objective ").append(objective).append(" ");
        }
        if (detector != null && !detector.isEmpty()) {
            message.append("--detector ").append(detector).append(" ");
        }
        message.append("--output ").append(outputPath);
        message.append(" --positive_angle ").append(positiveAngle);
        message.append(" --positive_exp ").append(positiveExposure);
        message.append(" --target_positive ").append(positiveTarget);
        message.append(" --negative_angle ").append(negativeAngle);
        message.append(" --negative_exp ").append(negativeExposure);
        message.append(" --target_negative ").append(negativeTarget);
        message.append(" --crossed_angle ").append(crossedAngle);
        message.append(" --crossed_exp ").append(crossedExposure);
        message.append(" --target_crossed ").append(crossedTarget);
        message.append(" --uncrossed_angle ").append(uncrossedAngle);
        message.append(" --uncrossed_exp ").append(uncrossedExposure);
        message.append(" --target_uncrossed ").append(uncrossedTarget);
        message.append(" --target ").append(targetIntensity);
        message.append(" --tolerance ").append(tolerance);
        message.append(" --max_gain_db ").append(maxGainDb);
        message.append(" --gain_threshold ").append(gainThresholdRatio);
        message.append(" --max_iterations ").append(maxIterations);
        message.append(" --calibrate_black_level ").append(calibrateBlackLevel ? "true" : "false");
        message.append(" --base_gain ").append(baseGain);
        message.append(" --exposure_soft_cap_ms ").append(exposureSoftCapMs);
        message.append(" --boosted_max_gain_db ").append(boostedMaxGainDb);
        message.append(" ").append(END_MARKER);

        byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

        logger.info("Sending PPM white balance command:");
        logger.info("  Output path: {}", outputPath);
        logger.info("  Positive: {} deg, {} ms, target={}", positiveAngle, positiveExposure, positiveTarget);
        logger.info("  Negative: {} deg, {} ms, target={}", negativeAngle, negativeExposure, negativeTarget);
        logger.info("  Crossed: {} deg, {} ms, target={}", crossedAngle, crossedExposure, crossedTarget);
        logger.info("  Uncrossed: {} deg, {} ms, target={}", uncrossedAngle, uncrossedExposure, uncrossedTarget);
        logger.info("  Default target: {}, Tolerance: {}", targetIntensity, tolerance);
        logger.info("  Advanced: maxGain={}dB, gainThreshold={}, maxIter={}, calibrateBL={}",
                maxGainDb, gainThresholdRatio, maxIterations, calibrateBlackLevel);
        logger.info("  Gain algo: baseGain={}, exposureSoftCap={}ms, boostedMaxGain={}dB",
                baseGain, exposureSoftCapMs, boostedMaxGainDb);

        synchronized (socketLock) {
            ensureConnected();

            int originalTimeout = readTimeout;
            try {
                // PPM calibration involves 4 angles, can take 10-15 minutes
                if (socket != null) {
                    socket.setSoTimeout(900000); // 15 minutes
                    logger.debug("Set socket timeout to 15 minutes for PPM white balance");
                }

                // Send WBPPM command (8 bytes)
                output.write(Command.WBPPM.getValue());
                output.flush();
                logger.debug("Sent WBPPM command (8 bytes)");

                Thread.sleep(50);

                // Send message
                output.write(messageBytes);
                output.flush();
                logger.debug("Sent PPM white balance message ({} bytes)", messageBytes.length);

                lastActivityTime.set(System.currentTimeMillis());
                logger.info("PPM white balance command sent successfully");

                // Read the STARTED acknowledgment
                byte[] buffer = new byte[4096];
                int bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String response = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received server response: {}", response);

                    if (response.startsWith("FAILED:")) {
                        throw new IOException("PPM white balance failed: " + response.substring(7));
                    } else if (!response.startsWith("STARTED:")) {
                        logger.warn("Unexpected initial response: {}", response);
                    }
                }

                // Wait for final SUCCESS/FAILED response
                logger.info("Waiting for PPM white balance calibration to complete (4 angles)...");
                bytesRead = input.read(buffer);
                if (bytesRead > 0) {
                    String finalResponse = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    logger.info("Received final response: {}", finalResponse);

                    if (finalResponse.startsWith("FAILED:")) {
                        throw new IOException("PPM white balance failed: " + finalResponse.substring(7));
                    }

                    if (finalResponse.startsWith("SUCCESS:")) {
                        return parsePPMWBResponse(finalResponse);
                    }
                }

                throw new IOException("No valid response received from PPM white balance calibration");

            } catch (IOException | InterruptedException e) {
                handleIOException(new IOException("PPM white balance error", e));
                throw new IOException("PPM white balance error: " + e.getMessage(), e);
            } finally {
                if (socket != null) {
                    try {
                        socket.setSoTimeout(originalTimeout);
                    } catch (IOException e) {
                        logger.warn("Failed to restore socket timeout", e);
                    }
                }
            }
        }
    }

    /**
     * Parse response from WBSIMPLE command.
     * Format: SUCCESS:/output/path|CONVERGED|exp_r:15.2,exp_g:18.5,exp_b:22.1
     */
    private WhiteBalanceResult parseSimpleWBResponse(String response) {
        // Format: SUCCESS:{output_path}|{status}|{exp_str}|{gain_str}
        // Remove "SUCCESS:" prefix
        String data = response.substring(8);
        String[] parts = data.split("\\|");

        boolean converged = false;
        double expR = 0, expG = 0, expB = 0;
        double gainR = 1.0, gainG = 1.0, gainB = 1.0;

        if (parts.length >= 2) {
            converged = "CONVERGED".equals(parts[1].trim());
        }

        if (parts.length >= 3) {
            String expStr = parts[2].trim();
            String[] expParts = expStr.split(",");
            for (String expPart : expParts) {
                String[] kv = expPart.split(":");
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    double value = Double.parseDouble(kv[1].trim());
                    switch (key) {
                        case "exp_r" -> expR = value;
                        case "exp_g" -> expG = value;
                        case "exp_b" -> expB = value;
                    }
                }
            }
        }

        // Parse gain values (parts[3])
        if (parts.length >= 4) {
            String gainStr = parts[3].trim();
            String[] gainParts = gainStr.split(",");
            for (String gainPart : gainParts) {
                String[] kv = gainPart.split(":");
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    double value = Double.parseDouble(kv[1].trim());
                    switch (key) {
                        case "gain_r" -> gainR = value;
                        case "gain_g" -> gainG = value;
                        case "gain_b" -> gainB = value;
                    }
                }
            }
        }

        return new WhiteBalanceResult(expR, expG, expB, gainR, gainG, gainB, converged);
    }

    /**
     * Parse response from WBPPM command.
     * Format: SUCCESS:/path|name:exp_r,exp_g,exp_b:gain_r,gain_g,gain_b:Y/N|...
     */
    private Map<String, WhiteBalanceResult> parsePPMWBResponse(String response) {
        Map<String, WhiteBalanceResult> results = new HashMap<>();

        // Remove "SUCCESS:" prefix
        String data = response.substring(8);
        String[] parts = data.split("\\|");

        // Skip first part (output path)
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;

            // Format: name:exp_r,exp_g,exp_b:gain_r,gain_g,gain_b:Y/N
            String[] segments = part.split(":");
            if (segments.length >= 4) {
                // New format with gains
                String name = segments[0].trim();
                String[] exps = segments[1].split(",");
                String[] gains = segments[2].split(",");
                boolean converged = "Y".equals(segments[3].trim());

                if (exps.length >= 3 && gains.length >= 3) {
                    double expR = Double.parseDouble(exps[0].trim());
                    double expG = Double.parseDouble(exps[1].trim());
                    double expB = Double.parseDouble(exps[2].trim());
                    double gainR = Double.parseDouble(gains[0].trim());
                    double gainG = Double.parseDouble(gains[1].trim());
                    double gainB = Double.parseDouble(gains[2].trim());
                    results.put(name, new WhiteBalanceResult(expR, expG, expB, gainR, gainG, gainB, converged));
                }
            } else if (segments.length >= 3) {
                // Old format without gains (backward compatibility)
                String name = segments[0].trim();
                String[] exps = segments[1].split(",");
                boolean converged = "Y".equals(segments[2].trim());

                if (exps.length >= 3) {
                    double expR = Double.parseDouble(exps[0].trim());
                    double expG = Double.parseDouble(exps[1].trim());
                    double expB = Double.parseDouble(exps[2].trim());
                    results.put(name, new WhiteBalanceResult(expR, expG, expB, converged));
                }
            }
        }

        return results;
    }

    // ==================== Camera Control Methods ====================

    /**
     * Result of getCameraMode() containing exposure mode flag.
     * Gain is always unified (R/B analog gains are adjusted separately).
     *
     * @param isJAI True if this is a JAI 3-CCD camera with per-channel control
     * @param exposureIndividual True if per-channel exposure control is enabled
     */
    public record CameraModeResult(boolean isJAI, boolean exposureIndividual) {}

    /**
     * Result of getExposures() containing exposure values.
     *
     * @param unified Unified exposure in ms (always available)
     * @param red Red channel exposure in ms (only for JAI with individual mode)
     * @param green Green channel exposure in ms (only for JAI with individual mode)
     * @param blue Blue channel exposure in ms (only for JAI with individual mode)
     * @param isPerChannel True if per-channel values are valid
     */
    public record ExposuresResult(double unified, double red, double green, double blue, boolean isPerChannel) {
        public static ExposuresResult unified(double value) {
            return new ExposuresResult(value, 0, 0, 0, false);
        }

        public static ExposuresResult perChannel(double all, double r, double g, double b) {
            return new ExposuresResult(all, r, g, b, true);
        }
    }

    /**
     * Result of getGains() containing unified gain and R/B analog gain values.
     * The new gain model always uses unified gain mode with separate R/B analog
     * gain adjustments (green is the reference channel, not adjusted).
     *
     * @param unifiedGain Unified digital gain (1.0-8.0)
     * @param analogRed Red channel analog gain (0.47-4.0)
     * @param analogBlue Blue channel analog gain (0.47-4.0)
     */
    public record GainsResult(double unifiedGain, double analogRed, double analogBlue) {}

    /**
     * Result of getNoise() containing per-channel noise statistics.
     *
     * @param redMean Red channel mean intensity
     * @param greenMean Green channel mean intensity
     * @param blueMean Blue channel mean intensity
     * @param redStdDev Red channel temporal standard deviation
     * @param greenStdDev Green channel temporal standard deviation
     * @param blueStdDev Blue channel temporal standard deviation
     * @param redSNR Red channel signal-to-noise ratio
     * @param greenSNR Green channel signal-to-noise ratio
     * @param blueSNR Blue channel signal-to-noise ratio
     */
    public record NoiseResult(double redMean, double greenMean, double blueMean,
                              double redStdDev, double greenStdDev, double blueStdDev,
                              double redSNR, double greenSNR, double blueSNR) {}

    /**
     * Gets the current camera name from the microscope Core.
     *
     * @return Camera name (e.g., "JAICamera", "MicroPublisher")
     * @throws IOException if communication fails
     */
    public String getCameraName() throws IOException {
        byte[] response = executeCommand(Command.GETCAM, null, 32);

        // Trim null bytes and whitespace
        String name = new String(response, StandardCharsets.UTF_8).trim();
        // Also remove any null characters
        name = name.replace("\0", "").trim();

        if (name.startsWith("ERROR:")) {
            throw new IOException("Failed to get camera name: " + name.substring(6));
        }

        logger.debug("Camera name: {}", name);
        return name;
    }

    /**
     * Gets the current camera mode (individual vs unified exposure/gain).
     *
     * @return CameraModeResult with mode flags
     * @throws IOException if communication fails
     */
    public CameraModeResult getCameraMode() throws IOException {
        byte[] response = executeCommand(Command.GETMODE, null, 16);

        String modeStr = new String(response, StandardCharsets.UTF_8).trim().replace("\0", "");
        logger.debug("Camera mode response: {}", modeStr);

        if (modeStr.startsWith("ERROR:")) {
            throw new IOException("Failed to get camera mode: " + modeStr.substring(6));
        }

        if (modeStr.startsWith("UNIFIED")) {
            // Not a JAI camera - unified mode only
            return new CameraModeResult(false, false);
        }

        if (modeStr.startsWith("JAI_EXP:")) {
            // Parse JAI mode: "JAI_EXP:X_GAIN:Y"
            // Gain mode is always unified now (GAIN:0), ignore the gain flag
            boolean expIndividual = modeStr.contains("EXP:1");
            return new CameraModeResult(true, expIndividual);
        }

        // Default to unified mode
        logger.warn("Unknown camera mode format: {}", modeStr);
        return new CameraModeResult(false, false);
    }

    /**
     * Sets the camera exposure mode (individual vs unified).
     * Gain mode is always unified; R/B analog gains are adjusted separately.
     * Only works for JAI 3-CCD cameras.
     *
     * @param exposureIndividual True to enable per-channel exposure control
     * @throws IOException if communication fails or camera doesn't support individual mode
     */
    public void setCameraMode(boolean exposureIndividual) throws IOException {
        byte[] modeData = new byte[2];
        modeData[0] = (byte) (exposureIndividual ? 1 : 0);
        modeData[1] = (byte) 0;  // Gain always unified

        byte[] response = executeCommand(Command.SETMODE, modeData, 8);
        String responseStr = new String(response, StandardCharsets.UTF_8).trim();

        if (!responseStr.startsWith("ACK")) {
            if (responseStr.startsWith("ERR_NJAI")) {
                throw new IOException("Camera does not support individual mode (not a JAI camera)");
            }

            // ERR_MODE may indicate live streaming interference -- retry once after delay
            if (responseStr.startsWith("ERR_MODE")) {
                logger.warn("SETMODE returned ERR_MODE (possible live mode interference) - retrying in 500ms");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                byte[] retryData = new byte[2];
                retryData[0] = (byte) (exposureIndividual ? 1 : 0);
                retryData[1] = (byte) 0;
                byte[] retryResponse = executeCommand(Command.SETMODE, retryData, 8);
                String retryStr = new String(retryResponse, StandardCharsets.UTF_8).trim();

                if (!retryStr.startsWith("ACK")) {
                    throw new IOException("Failed to set camera mode after retry: " + retryStr);
                }
                logger.info("Camera mode set on retry: exposure_individual={}, gain=unified", exposureIndividual);
                return;
            }

            throw new IOException("Failed to set camera mode: " + responseStr);
        }

        logger.info("Camera mode set: exposure_individual={}, gain=unified", exposureIndividual);
    }

    /**
     * Gets current exposure values from the camera.
     *
     * @return ExposuresResult with exposure values in ms
     * @throws IOException if communication fails
     */
    public ExposuresResult getExposures() throws IOException {
        synchronized (socketLock) {
            ensureConnected();

            try {
                // Send command
                output.write(Command.GETEXP.getValue());
                output.flush();
                lastActivityTime.set(System.currentTimeMillis());

                // First try to read 4 bytes (could be unified or error)
                byte[] firstFloat = new byte[4];
                input.readFully(firstFloat);

                ByteBuffer buffer = ByteBuffer.wrap(firstFloat);
                buffer.order(ByteOrder.BIG_ENDIAN);
                float firstValue = buffer.getFloat();

                if (firstValue < 0) {
                    throw new IOException("Failed to get exposure values");
                }

                // Check if there's more data (per-channel mode sends 16 bytes total)
                // Use available() with a small delay to check
                Thread.sleep(20);
                int available = input.available();

                if (available >= 12) {
                    // Per-channel mode: 4 floats total (all, R, G, B)
                    byte[] remaining = new byte[12];
                    input.readFully(remaining);

                    ByteBuffer remainingBuffer = ByteBuffer.wrap(remaining);
                    remainingBuffer.order(ByteOrder.BIG_ENDIAN);

                    float red = remainingBuffer.getFloat();
                    float green = remainingBuffer.getFloat();
                    float blue = remainingBuffer.getFloat();

                    logger.debug("Got per-channel exposures: all={}, R={}, G={}, B={}", firstValue, red, green, blue);
                    return ExposuresResult.perChannel(firstValue, red, green, blue);
                } else {
                    // Unified mode: just 1 float
                    logger.debug("Got unified exposure: {}", firstValue);
                    return ExposuresResult.unified(firstValue);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while getting exposure", e);
            } catch (IOException e) {
                handleIOException(e);
                throw e;
            }
        }
    }

    /**
     * Sets exposure values on the camera.
     *
     * @param exposures Array of exposure values in ms. Length 1 for unified, 3 for per-channel (R, G, B)
     * @throws IOException if communication fails
     */
    public void setExposures(float[] exposures) throws IOException {
        if (exposures == null || (exposures.length != 1 && exposures.length != 3)) {
            throw new IllegalArgumentException("Exposures must have length 1 (unified) or 3 (R, G, B)");
        }

        // Build payload: 1 byte count + N floats
        ByteBuffer buffer = ByteBuffer.allocate(1 + exposures.length * 4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) exposures.length);
        for (float exp : exposures) {
            buffer.putFloat(exp);
        }

        byte[] response = executeCommand(Command.SETEXP, buffer.array(), 8);
        String responseStr = new String(response, StandardCharsets.UTF_8).trim();

        if (!responseStr.startsWith("ACK")) {
            if (responseStr.startsWith("ERR_NJAI")) {
                throw new IOException("Per-channel exposure requires JAI camera");
            }
            throw new IOException("Failed to set exposure: " + responseStr);
        }

        logger.info("Exposure set successfully: {}", java.util.Arrays.toString(exposures));
    }

    /**
     * Gets current gain values from the camera.
     * Always returns 3 floats: [unified_gain, analog_red, analog_blue].
     *
     * @return GainsResult with unified gain and R/B analog gains
     * @throws IOException if communication fails
     */
    public GainsResult getGains() throws IOException {
        // Server always sends 3 floats (12 bytes): unified, analog_red, analog_blue
        byte[] response = executeCommand(Command.GETGAIN, null, 12);

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        float unifiedGain = buffer.getFloat();
        float analogRed = buffer.getFloat();
        float analogBlue = buffer.getFloat();

        if (unifiedGain < 0) {
            throw new IOException("Failed to get gain values");
        }

        logger.debug("Got gains: unified={}, analog_red={}, analog_blue={}", unifiedGain, analogRed, analogBlue);
        return new GainsResult(unifiedGain, analogRed, analogBlue);
    }

    /**
     * Sets gain values on the camera.
     *
     * @param gains Array of gain values. Length 1 for unified gain only,
     *              or length 3 for [unified_gain, analog_red, analog_blue]
     * @throws IOException if communication fails
     */
    public void setGains(float[] gains) throws IOException {
        if (gains == null || (gains.length != 1 && gains.length != 3)) {
            throw new IllegalArgumentException("Gains must have length 1 (unified only) or 3 (unified, analog_red, analog_blue)");
        }

        // Build payload: 1 byte count + N floats
        ByteBuffer buffer = ByteBuffer.allocate(1 + gains.length * 4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) gains.length);
        for (float gain : gains) {
            buffer.putFloat(gain);
        }

        byte[] response = executeCommand(Command.SETGAIN, buffer.array(), 8);
        String responseStr = new String(response, StandardCharsets.UTF_8).trim();

        if (!responseStr.startsWith("ACK")) {
            if (responseStr.startsWith("ERR_NJAI")) {
                throw new IOException("Gain control requires JAI camera");
            }
            throw new IOException("Failed to set gain: " + responseStr);
        }

        logger.info("Gain set successfully: {}", java.util.Arrays.toString(gains));
    }

    // ==================== White Balance Mode Control ====================

    /**
     * Sets the camera's white balance mode.
     *
     * @param mode 0=Off, 1=Continuous, 2=Once (one-shot auto)
     * @throws IOException if communication fails
     */
    public void setWhiteBalanceMode(int mode) throws IOException {
        if (mode < 0 || mode > 2) {
            throw new IllegalArgumentException("WB mode must be 0 (Off), 1 (Continuous), or 2 (Once)");
        }

        byte[] payload = new byte[]{(byte) mode};
        byte[] response = executeCommand(Command.SETWBMD, payload, 8);
        String responseStr = new String(response, StandardCharsets.UTF_8).trim();

        if (!responseStr.startsWith("ACK")) {
            if (responseStr.startsWith("ERR_NJAI")) {
                throw new IOException("White balance mode requires JAI camera");
            }
            throw new IOException("Failed to set WB mode: " + responseStr);
        }

        String[] modeNames = {"Off", "Continuous", "Once"};
        logger.info("White balance mode set to: {}", modeNames[mode]);
    }

    // ==================== Noise Measurement Methods ====================

    /**
     * Gets per-channel noise statistics from multi-frame temporal analysis.
     * The server captures multiple frames and computes temporal mean, stddev, and SNR
     * per channel (R, G, B).
     *
     * @param numFrames Number of frames to capture for noise analysis (default 10)
     * @return NoiseResult with per-channel noise statistics
     * @throws IOException if communication fails
     */
    public NoiseResult getNoise(int numFrames) throws IOException {
        if (numFrames < 1 || numFrames > 255) {
            throw new IllegalArgumentException("numFrames must be between 1 and 255");
        }

        byte[] payload = new byte[]{(byte) numFrames};
        // Response: 9 big-endian floats (36 bytes)
        byte[] response = executeCommand(Command.GETNOISE, payload, 36);

        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.BIG_ENDIAN);

        double redMean = buffer.getFloat();
        double greenMean = buffer.getFloat();
        double blueMean = buffer.getFloat();
        double redStdDev = buffer.getFloat();
        double greenStdDev = buffer.getFloat();
        double blueStdDev = buffer.getFloat();
        double redSNR = buffer.getFloat();
        double greenSNR = buffer.getFloat();
        double blueSNR = buffer.getFloat();

        // Check for error (negative mean indicates failure)
        if (redMean < 0) {
            throw new IOException("Failed to get noise statistics");
        }

        logger.debug("Noise stats: R(mean={}, std={}, snr={}), G(mean={}, std={}, snr={}), B(mean={}, std={}, snr={})",
                redMean, redStdDev, redSNR, greenMean, greenStdDev, greenSNR, blueMean, blueStdDev, blueSNR);

        return new NoiseResult(redMean, greenMean, blueMean, redStdDev, greenStdDev, blueStdDev, redSNR, greenSNR, blueSNR);
    }

    // ==================== Live Mode Control Methods ====================

    /**
     * Checks if live mode is currently running on the microscope.
     *
     * @return true if live mode is active, false otherwise
     * @throws IOException if communication fails
     */
    public boolean isLiveModeRunning() throws IOException {
        byte[] response = executeCommand(Command.GETLIVE, null, 1);
        boolean isLive = response[0] == 1;
        logger.debug("Live mode status: {}", isLive ? "ON" : "OFF");
        return isLive;
    }

    /**
     * Sets live mode on or off.
     *
     * @param enable true to enable live mode, false to disable
     * @throws IOException if communication fails or studio is not available
     */
    public void setLiveMode(boolean enable) throws IOException {
        byte[] payload = new byte[] { (byte) (enable ? 1 : 0) };
        byte[] response = executeCommand(Command.SETLIVE, payload, 8);
        String responseStr = new String(response, StandardCharsets.UTF_8).trim();

        if (!responseStr.startsWith("ACK")) {
            if (responseStr.startsWith("ERR_NSTD")) {
                throw new IOException("No studio available to control live mode");
            }
            if (responseStr.startsWith("ERR_LIVE")) {
                throw new IOException("Failed to set live mode");
            }
            throw new IOException("Failed to set live mode: " + responseStr);
        }

        logger.info("Live mode set to: {}", enable ? "ON" : "OFF");
    }

    // ==================== Live Viewer Methods ====================

    /**
     * Gets the latest frame from MM's circular buffer.
     * This reads from the buffer (near-instant) rather than triggering a new exposure.
     * Camera must be in live mode for frames to be available.
     *
     * <p>Protocol:
     * <ul>
     *   <li>Send 8-byte GETFRAME command</li>
     *   <li>Read 20-byte header: width, height, channels, bytesPerPixel, dataLength (big-endian int32s)</li>
     *   <li>If width==0: no frame available, return null</li>
     *   <li>Read dataLength bytes of raw pixel data</li>
     * </ul>
     *
     * @return FrameData record, or null if no frame is available
     * @throws IOException if communication fails
     */
    public qupath.ext.qpsc.ui.liveviewer.FrameData getFrame() throws IOException {
        // Use auxiliary socket for Live Viewer operations
        // This allows frame polling to work even when primary socket is busy
        synchronized (auxSocketLock) {
            ensureAuxConnected();

            try {
                // Send command
                auxOutput.write(Command.GETFRAME.getValue());
                auxOutput.flush();

                // Read 20-byte header (5 big-endian int32s)
                byte[] header = new byte[20];
                auxInput.readFully(header);

                ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
                int width = headerBuf.getInt();
                int height = headerBuf.getInt();
                int channels = headerBuf.getInt();
                int bytesPerPixel = headerBuf.getInt();
                int dataLength = headerBuf.getInt();

                // Zero header means no frame available
                if (width == 0) {
                    return null;
                }

                // Read pixel data
                byte[] pixelData = new byte[dataLength];
                auxInput.readFully(pixelData);

                return new qupath.ext.qpsc.ui.liveviewer.FrameData(
                        width, height, channels, bytesPerPixel,
                        pixelData, System.currentTimeMillis()
                );

            } catch (IOException e) {
                // If auxiliary fails, clean it up so it can reconnect
                cleanupAuxiliary();
                throw e;
            }
        }
    }

    /**
     * Starts continuous sequence acquisition at the MM Core level.
     * This bypasses MM's studio/live window entirely -- the camera fills
     * the circular buffer at its native frame rate.
     *
     * @throws IOException if communication fails or the command is rejected
     */
    public void startContinuousAcquisition() throws IOException {
        // Use auxiliary socket for live mode operations
        byte[] response = executeCommandOnAux(Command.STRTSEQ, null, 8);
        String responseStr = new String(response, StandardCharsets.UTF_8).trim();
        if (!responseStr.startsWith("ACK")) {
            throw new IOException("Failed to start continuous acquisition: " + responseStr);
        }
        logger.info("Continuous sequence acquisition started (core-level)");
    }

    /**
     * Stops continuous sequence acquisition at the MM Core level.
     *
     * @throws IOException if communication fails or the command is rejected
     */
    public void stopContinuousAcquisition() throws IOException {
        // Use auxiliary socket for live mode operations
        byte[] response = executeCommandOnAux(Command.STOPSEQ, null, 8);
        String responseStr = new String(response, StandardCharsets.UTF_8).trim();
        if (!responseStr.startsWith("ACK")) {
            throw new IOException("Failed to stop continuous acquisition: " + responseStr);
        }
        logger.info("Continuous sequence acquisition stopped (core-level)");
    }

    /**
     * Executes a command on the auxiliary socket.
     * Used for Live Viewer and stage control operations that need to work
     * independently of long-running operations on the primary socket.
     *
     * @param command The command to execute
     * @param payload Optional payload data (can be null)
     * @param responseLength Expected response length in bytes
     * @return Response bytes from server
     * @throws IOException if communication fails
     */
    private byte[] executeCommandOnAux(Command command, byte[] payload, int responseLength) throws IOException {
        synchronized (auxSocketLock) {
            ensureAuxConnected();

            try {
                // Send command
                auxOutput.write(command.getValue());

                // Send payload if provided
                if (payload != null && payload.length > 0) {
                    auxOutput.write(payload);
                }
                auxOutput.flush();

                // Read response
                byte[] response = new byte[responseLength];
                auxInput.readFully(response);

                return response;

            } catch (IOException e) {
                cleanupAuxiliary();
                throw e;
            }
        }
    }

}
