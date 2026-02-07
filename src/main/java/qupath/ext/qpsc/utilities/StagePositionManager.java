package qupath.ext.qpsc.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized manager for stage position polling and event notification.
 *
 * <p>This singleton polls all stage axes (X, Y, Z, R) at a configurable interval
 * and fires property change events when positions change. UI components can
 * register as listeners to receive real-time position updates without each
 * component needing its own polling thread.
 *
 * <p>Key features:
 * <ul>
 *   <li>Single source of truth for stage positions</li>
 *   <li>Reference counting for automatic start/stop of polling</li>
 *   <li>Tolerance-based change detection to avoid spurious updates</li>
 *   <li>Thread-safe position caching</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * // Register for updates
 * StagePositionManager.getInstance().addPropertyChangeListener(evt -> {
 *     double newVal = (Double) evt.getNewValue();
 *     switch (evt.getPropertyName()) {
 *         case "posX" -> updateXField(newVal);
 *         case "posY" -> updateYField(newVal);
 *         case "posZ" -> updateZField(newVal);
 *         case "posR" -> updateRField(newVal);
 *     }
 * });
 *
 * // Unregister when done
 * StagePositionManager.getInstance().removePropertyChangeListener(listener);
 * }</pre>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class StagePositionManager {

    private static final Logger logger = LoggerFactory.getLogger(StagePositionManager.class);

    /** Singleton instance */
    private static volatile StagePositionManager instance;

    /** Lock object for singleton initialization */
    private static final Object LOCK = new Object();

    /** Polling interval in milliseconds */
    private static final long POLL_INTERVAL_MS = 500;

    /**
     * Position change tolerance in microns.
     * Changes smaller than this are ignored to prevent spurious updates.
     */
    private static final double POSITION_TOLERANCE = 0.01;

    /** Property name for X position change events */
    public static final String PROP_POS_X = "posX";

    /** Property name for Y position change events */
    public static final String PROP_POS_Y = "posY";

    /** Property name for Z position change events */
    public static final String PROP_POS_Z = "posZ";

    /** Property name for R (rotation) position change events */
    public static final String PROP_POS_R = "posR";

    // Cached positions (volatile for thread-safe reads)
    private volatile double posX = Double.NaN;
    private volatile double posY = Double.NaN;
    private volatile double posZ = Double.NaN;
    private volatile double posR = Double.NaN;

    /** Property change support for event notification */
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    /** Scheduler for position polling */
    private ScheduledExecutorService poller;

    /** Reference count of registered listeners */
    private final AtomicInteger listenerCount = new AtomicInteger(0);

    /** Flag to track if polling is currently active */
    private volatile boolean pollingActive = false;

    /**
     * Private constructor for singleton pattern.
     */
    private StagePositionManager() {
        logger.debug("StagePositionManager created");
    }

    /**
     * Gets the singleton instance of StagePositionManager.
     *
     * @return The singleton instance
     */
    public static StagePositionManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new StagePositionManager();
                }
            }
        }
        return instance;
    }

    /**
     * Registers a property change listener for position updates.
     *
     * <p>Polling automatically starts when the first listener is added
     * and stops when the last listener is removed.
     *
     * @param listener The listener to register
     */
    public void addPropertyChangeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener);
        int count = listenerCount.incrementAndGet();
        logger.debug("Listener added, count: {}", count);

        if (count == 1) {
            startPolling();
        }
    }

    /**
     * Unregisters a property change listener.
     *
     * <p>Polling automatically stops when the last listener is removed.
     *
     * @param listener The listener to unregister
     */
    public void removePropertyChangeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener);
        int count = listenerCount.decrementAndGet();
        logger.debug("Listener removed, count: {}", count);

        if (count <= 0) {
            stopPolling();
            listenerCount.set(0); // Ensure non-negative
        }
    }

    /**
     * Starts the position polling thread.
     * Called automatically when the first listener is added.
     */
    private synchronized void startPolling() {
        if (pollingActive) {
            logger.debug("Polling already active, skipping start");
            return;
        }

        logger.info("Starting stage position polling at {}ms interval", POLL_INTERVAL_MS);
        pollingActive = true;

        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StagePositionManager-Poller");
            t.setDaemon(true);
            return t;
        });

        poller.scheduleWithFixedDelay(
                this::pollPositions,
                0,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stops the position polling thread.
     * Called automatically when the last listener is removed.
     */
    private synchronized void stopPolling() {
        if (!pollingActive) {
            logger.debug("Polling not active, skipping stop");
            return;
        }

        logger.info("Stopping stage position polling");
        pollingActive = false;

        if (poller != null) {
            poller.shutdownNow();
            try {
                if (!poller.awaitTermination(1, TimeUnit.SECONDS)) {
                    logger.warn("Poller did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while stopping poller");
            }
            poller = null;
        }
    }

    /**
     * Polls all stage positions and fires events for any that changed.
     * Called periodically by the polling thread.
     */
    private void pollPositions() {
        try {
            MicroscopeController controller = MicroscopeController.getInstance();
            if (controller == null || !controller.isConnected()) {
                logger.trace("Controller not connected, skipping position poll");
                return;
            }

            // Poll XY position
            try {
                double[] xy = controller.getStagePositionXY();
                updatePosition(PROP_POS_X, posX, xy[0], v -> posX = v);
                updatePosition(PROP_POS_Y, posY, xy[1], v -> posY = v);
            } catch (Exception e) {
                logger.trace("Failed to poll XY position: {}", e.getMessage());
            }

            // Poll Z position
            try {
                double z = controller.getStagePositionZ();
                updatePosition(PROP_POS_Z, posZ, z, v -> posZ = v);
            } catch (Exception e) {
                logger.trace("Failed to poll Z position: {}", e.getMessage());
            }

            // Poll R position
            try {
                double r = controller.getStagePositionR();
                updatePosition(PROP_POS_R, posR, r, v -> posR = v);
            } catch (Exception e) {
                logger.trace("Failed to poll R position: {}", e.getMessage());
            }

        } catch (Exception e) {
            logger.debug("Error in position poll cycle: {}", e.getMessage());
        }
    }

    /**
     * Updates a cached position and fires a property change event if changed.
     *
     * @param propertyName The property name for the event
     * @param oldValue The current cached value
     * @param newValue The new value from the hardware
     * @param setter A function to update the cached value
     */
    private void updatePosition(String propertyName, double oldValue, double newValue,
                                 java.util.function.DoubleConsumer setter) {
        // Check if value changed beyond tolerance
        boolean isInitialValue = Double.isNaN(oldValue);
        boolean hasChanged = isInitialValue || Math.abs(newValue - oldValue) > POSITION_TOLERANCE;

        if (hasChanged) {
            setter.accept(newValue);
            pcs.firePropertyChange(propertyName, oldValue, newValue);
            logger.trace("Position {} changed: {} -> {}", propertyName, oldValue, newValue);
        }
    }

    /**
     * Gets the cached X position without querying hardware.
     *
     * @return The cached X position, or NaN if not yet polled
     */
    public double getX() {
        return posX;
    }

    /**
     * Gets the cached Y position without querying hardware.
     *
     * @return The cached Y position, or NaN if not yet polled
     */
    public double getY() {
        return posY;
    }

    /**
     * Gets the cached Z position without querying hardware.
     *
     * @return The cached Z position, or NaN if not yet polled
     */
    public double getZ() {
        return posZ;
    }

    /**
     * Gets the cached R (rotation) position without querying hardware.
     *
     * @return The cached R position, or NaN if not yet polled
     */
    public double getR() {
        return posR;
    }

    /**
     * Forces an immediate poll of all positions.
     * Useful for getting the initial state on startup.
     */
    public void forceRefresh() {
        pollPositions();
    }

    /**
     * Checks if the position manager is currently polling.
     *
     * @return true if polling is active
     */
    public boolean isPollingActive() {
        return pollingActive;
    }

    /**
     * Gets the number of registered listeners.
     *
     * @return The listener count
     */
    public int getListenerCount() {
        return listenerCount.get();
    }
}
