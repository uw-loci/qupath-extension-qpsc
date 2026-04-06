package qupath.ext.qpsc.ui.liveviewer;

import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized lock manager for the Live Viewer.
 *
 * <p>When a lock is active, interactive controls that could interfere with
 * hardware operations are disabled. Only passive viewing controls remain:
 * <ul>
 *   <li>Histogram and brightness/contrast sliders</li>
 *   <li>Tile overlay checkbox</li>
 *   <li>Window close button</li>
 *   <li>Status text</li>
 * </ul>
 *
 * <p>Locked controls include:
 * <ul>
 *   <li>Live ON/OFF toggle</li>
 *   <li>Refine Focus / Sweep Focus buttons</li>
 *   <li>Stage control panel (XY/Z/R movement, joystick, presets)</li>
 *   <li>Stage control toggle button</li>
 *   <li>Double-click-to-move on the image</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * // Acquire lock before starting operation
 * LiveViewerLockManager lock = LiveViewerWindow.getInstance().getLockManager();
 * lock.acquire("White Balance Calibration");
 * try {
 *     // ... run calibration ...
 * } finally {
 *     lock.release();
 * }
 * }</pre>
 *
 * <p>Only one lock can be held at a time. Attempting to acquire while
 * already locked logs a warning and returns false.
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class LiveViewerLockManager {

    private static final Logger logger = LoggerFactory.getLogger(LiveViewerLockManager.class);

    /** Current lock holder description, or null if unlocked */
    private final AtomicReference<String> lockHolder = new AtomicReference<>(null);

    /** Callback to apply lock/unlock state to UI controls */
    private final Runnable onLock;

    private final Runnable onUnlock;

    /**
     * Creates a lock manager with callbacks to enable/disable UI controls.
     *
     * @param onLock   Called on FX thread when lock is acquired
     * @param onUnlock Called on FX thread when lock is released
     */
    LiveViewerLockManager(Runnable onLock, Runnable onUnlock) {
        this.onLock = onLock;
        this.onUnlock = onUnlock;
    }

    /**
     * Acquires the lock, disabling interactive controls.
     *
     * @param reason Human-readable description (e.g., "White Balance Calibration")
     * @return true if lock was acquired, false if already locked by another holder
     */
    public boolean acquire(String reason) {
        String previous = lockHolder.getAndSet(reason);
        if (previous != null) {
            logger.warn("Live Viewer lock already held by '{}', overriding with '{}'", previous, reason);
        }
        logger.info("Live Viewer locked: {}", reason);
        Platform.runLater(onLock);
        return true;
    }

    /**
     * Releases the lock, re-enabling interactive controls.
     */
    public void release() {
        String holder = lockHolder.getAndSet(null);
        if (holder == null) {
            logger.debug("Live Viewer lock release called but no lock was held");
            return;
        }
        logger.info("Live Viewer unlocked (was: {})", holder);
        Platform.runLater(onUnlock);
    }

    /**
     * Checks if the Live Viewer is currently locked.
     *
     * @return true if locked
     */
    public boolean isLocked() {
        return lockHolder.get() != null;
    }

    /**
     * Gets the current lock holder description.
     *
     * @return Lock holder reason, or null if unlocked
     */
    public String getLockHolder() {
        return lockHolder.get();
    }
}
