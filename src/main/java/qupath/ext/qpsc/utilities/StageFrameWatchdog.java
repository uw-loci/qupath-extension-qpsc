package qupath.ext.qpsc.utilities;

import java.beans.PropertyChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the stage position stream for the coordinate frame moving underneath a run.
 *
 * <p><b>What this is for.</b> A Prior controller has no absolute reference and nothing to home
 * to, so one that restarts resumes with position 0,0 wherever it happens to be standing. From
 * then on it moves exactly where it is told and reports exactly that, which is why nothing that
 * compares a commanded position against the position reported after the move can see it. The
 * command server catches it by comparing across time (see {@code hardware/xy_motion.py}); this
 * class is the second, independent net on the QuPath side, and the one that stops a multi-slide
 * BATCH rather than a single region.
 *
 * <p><b>How it detects it.</b> {@link StagePositionManager} already polls the stage twice a
 * second for the Live Viewer and the Stage Map, and until now every one of those readings was
 * discarded. While armed for a region, this class holds that region's own stage bounding box and
 * reports a position that sits sustainedly outside it. A re-zero puts the reported position tens
 * of millimetres away from any region of any slide, so it is unmistakable -- the region box is
 * used rather than the slide box precisely because a slide box can straddle the origin (slot 2 of
 * the PPM quad holder does), which would make the origin look like a legal position.
 *
 * <p><b>Why it waits.</b> Two things keep it from crying wolf. A grace period after arming,
 * because the first thing an acquisition does is travel to the region -- possibly across the
 * whole holder -- and every reading on the way is legitimately outside. And a run of consecutive
 * violations, because a single reading taken mid-move is a point on a path, not a place. Once a
 * region is under way the stage only leaves its box by the autofocus tissue search, which moves a
 * field at a time and is covered by the margin.
 *
 * <p><b>It cannot correct anything.</b> Once the origin has moved, the holder calibration, every
 * per-slide alignment and the stage coordinates recorded beside data already on disk all describe
 * a place the sample is not. The reason is therefore sticky until {@link #clearSuspect()} is
 * called at the start of a deliberately restarted run, and recovery is the documented fiducial
 * procedure.
 *
 * @since 0.9.3
 */
public final class StageFrameWatchdog {

    private static final Logger logger = LoggerFactory.getLogger(StageFrameWatchdog.class);

    /**
     * How far outside its own bounding box a region's stage positions may stray.
     *
     * <p>Sized by the autofocus tissue search, which steps one FOV diagonal toward the centre on
     * each of up to three attempts (about 1.3 mm at 20x on PPM) and may start from a corner. Two
     * millimetres covers that with room to spare while staying far smaller than any frame shift
     * worth catching -- the 2026-10-02 run was off by more than the 11 mm height of a region.
     */
    private static final double ENVELOPE_MARGIN_UM = 2000.0;

    /**
     * Quiet period after arming, during which positions outside the envelope are expected.
     *
     * <p>An acquisition opens by travelling to its first autofocus position, which on a slot
     * change is a traverse across the holder. Every reading during it is legitimately outside the
     * region, and the slowest such move plus the initial autofocus search is well under this.
     */
    private static final long ARM_GRACE_MS = 30_000;

    /**
     * The grace period actually in force. Only the tests change it, so that they can assert the
     * behaviour after arming without waiting half a minute per case.
     */
    private volatile long graceMs = ARM_GRACE_MS;

    /**
     * Consecutive out-of-envelope samples needed before the frame is called suspect.
     *
     * <p>One sample can be a point on the way somewhere. At the sampling interval below, three in
     * a row means the stage has been sitting outside the region for over a second, which no
     * legitimate part of acquiring that region does.
     */
    private static final int CONSECUTIVE_VIOLATIONS = 3;

    /**
     * How often the armed watchdog looks at the last reported position.
     *
     * <p>It samples on its own clock rather than reacting to position events, because
     * {@link StagePositionManager} fires only when a position CHANGES -- and the signal here is a
     * stage that is sitting still somewhere it should not be. A re-zeroed controller parked at
     * 0,0 while autofocus runs produces exactly one event and then silence, so an
     * event-driven count of consecutive violations could never reach three. Matched to the
     * manager's own 500 ms poll so each sample is a fresh reading rather than a re-read of one.
     */
    private static final long SAMPLE_INTERVAL_MS = 500;

    private static volatile StageFrameWatchdog instance;
    private static final Object LOCK = new Object();

    /**
     * Registered purely to hold {@link StagePositionManager} polling open.
     *
     * <p>The manager reference-counts listeners and polls only while at least one is registered,
     * so without this an unattended run with no Live Viewer or Stage Map open would leave the
     * cached position stale forever. The evaluation itself happens in {@link #sample()}.
     */
    private final PropertyChangeListener listener = evt -> {};

    private volatile boolean armed = false;
    private volatile double minX, maxX, minY, maxY;
    private volatile String label = "";
    private volatile long armedAtMs = 0;
    private volatile int consecutiveViolations = 0;
    private volatile String suspectReason = null;
    private volatile Runnable onSuspect = null;
    private java.util.concurrent.ScheduledExecutorService sampler;

    private StageFrameWatchdog() {}

    public static StageFrameWatchdog getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new StageFrameWatchdog();
                }
            }
        }
        return instance;
    }

    /**
     * Arms the watchdog for one region.
     *
     * <p>Registering as a {@link StagePositionManager} listener is what guarantees polling is
     * running: the manager reference-counts listeners and only polls while at least one is
     * registered, so an unattended run with no Live Viewer or Stage Map open would otherwise
     * produce no readings to watch.
     *
     * @param stageMinX   region bounding box in stage coordinates (um)
     * @param stageMinY   region bounding box in stage coordinates (um)
     * @param stageMaxX   region bounding box in stage coordinates (um)
     * @param stageMaxY   region bounding box in stage coordinates (um)
     * @param regionLabel name used in log messages and in the suspect reason
     * @param onSuspect   run once, off the FX thread, the first time the frame is called
     *                    suspect; may be null
     */
    public void arm(
            double stageMinX,
            double stageMinY,
            double stageMaxX,
            double stageMaxY,
            String regionLabel,
            Runnable onSuspect) {
        if (!Double.isFinite(stageMinX)
                || !Double.isFinite(stageMinY)
                || !Double.isFinite(stageMaxX)
                || !Double.isFinite(stageMaxY)) {
            logger.debug("Stage frame watchdog not armed for {}: region bounds are not finite", regionLabel);
            return;
        }
        this.minX = Math.min(stageMinX, stageMaxX) - ENVELOPE_MARGIN_UM;
        this.maxX = Math.max(stageMinX, stageMaxX) + ENVELOPE_MARGIN_UM;
        this.minY = Math.min(stageMinY, stageMaxY) - ENVELOPE_MARGIN_UM;
        this.maxY = Math.max(stageMinY, stageMaxY) + ENVELOPE_MARGIN_UM;
        this.label = regionLabel == null ? "" : regionLabel;
        this.onSuspect = onSuspect;
        this.consecutiveViolations = 0;
        this.armedAtMs = System.currentTimeMillis();
        if (!armed) {
            armed = true;
            StagePositionManager.getInstance().addPropertyChangeListener(listener);
            sampler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "StageFrameWatchdog-Sampler");
                t.setDaemon(true);
                return t;
            });
            sampler.scheduleWithFixedDelay(
                    this::sample, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        logger.info(
                "Stage frame watchdog armed for {}: expecting X {} to {} um, Y {} to {} um (margin {} um)",
                this.label,
                Math.round(this.minX),
                Math.round(this.maxX),
                Math.round(this.minY),
                Math.round(this.maxY),
                Math.round(ENVELOPE_MARGIN_UM));
    }

    /** Disarms the watchdog and stops holding {@link StagePositionManager} polling open. */
    public void disarm() {
        if (!armed) {
            return;
        }
        armed = false;
        consecutiveViolations = 0;
        onSuspect = null;
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
        StagePositionManager.getInstance().removePropertyChangeListener(listener);
        logger.debug("Stage frame watchdog disarmed");
    }

    /**
     * Why the stage frame is no longer trustworthy, or null.
     *
     * <p>Sticky: the frame does not heal, so this outlives disarming and the end of the region
     * that detected it. That is what lets the multi-slide driver refuse to start the next slide.
     */
    public String suspectReason() {
        return suspectReason;
    }

    /** True once the frame has been called suspect. */
    public boolean isSuspect() {
        return suspectReason != null;
    }

    /**
     * Forgets a previous suspicion, for a run the operator has deliberately restarted.
     *
     * <p>Called at the start of a batch, by which point the operator has seen the alert and
     * decided to go again. Nothing clears it automatically.
     */
    public void clearSuspect() {
        if (suspectReason != null) {
            logger.info("Stage frame watchdog: previous suspicion cleared for a new run");
        }
        suspectReason = null;
    }

    /**
     * Looks once at the last reported stage position and decides what it means.
     *
     * <p>Package-private so the tests can step the watchdog deterministically instead of waiting
     * on its scheduler.
     */
    void sample() {
        if (!armed) {
            return;
        }
        StagePositionManager positions = StagePositionManager.getInstance();
        double x = positions.getX();
        double y = positions.getY();
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return;
        }
        if (System.currentTimeMillis() - armedAtMs < graceMs) {
            return;
        }
        if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
            consecutiveViolations = 0;
            return;
        }
        int count = ++consecutiveViolations;
        if (count < CONSECUTIVE_VIOLATIONS) {
            logger.warn(
                    "Stage frame watchdog: ({}, {}) um is outside {} (sample {} of {} before this is reported)",
                    Math.round(x),
                    Math.round(y),
                    label,
                    count,
                    CONSECUTIVE_VIOLATIONS);
            return;
        }
        reportSuspect(x, y);
    }

    private void reportSuspect(double x, double y) {
        if (suspectReason != null) {
            return;
        }
        String reason = String.format(
                "The stage reports (%.0f, %.0f) um, which is outside region %s (X %.0f to %.0f, "
                        + "Y %.0f to %.0f um including a %.0f um margin) for %d samples running. "
                        + "The stage is not where the coordinates in use say it is -- most likely its "
                        + "0,0 moved, which a Prior controller does whenever it restarts. Re-establish "
                        + "the frame at the calibration slide fiducial before acquiring again; the "
                        + "holder calibration and every slide alignment are measured against the old "
                        + "origin.",
                x, y, label, minX, maxX, minY, maxY, ENVELOPE_MARGIN_UM, CONSECUTIVE_VIOLATIONS);
        suspectReason = reason;
        logger.error("STAGE FRAME SUSPECT: {}", reason);
        Runnable callback = onSuspect;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                logger.error("Stage frame watchdog callback failed", e);
            }
        }
    }

    /** Visible for testing: true while registered with {@link StagePositionManager}. */
    boolean isArmed() {
        return armed;
    }

    /** Visible for testing: shorten the post-arming grace period. */
    void setGraceMillisForTesting(long millis) {
        this.graceMs = millis;
    }

    /** Visible for testing: restore the shipped grace period and forget any suspicion. */
    void resetForTesting() {
        disarm();
        this.graceMs = ARM_GRACE_MS;
        this.suspectReason = null;
    }
}
