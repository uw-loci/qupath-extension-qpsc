package qupath.ext.qpsc.ui.liveviewer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.liveviewer.RefineFocusController.Outcome;
import qupath.ext.qpsc.ui.liveviewer.RefineFocusController.StatusCallback;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Continuous Z-sweep autofocus that ramps Z while streaming frames and
 * computes focus metrics on-the-fly to find the best focus in one pass.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Move to startZ (blocking)</li>
 *   <li>Issue non-blocking move to endZ (stage starts ramping)</li>
 *   <li>While stage is moving, grab frames and compute focus metrics</li>
 *   <li>Find the Z with the peak metric</li>
 *   <li>Move to bestZ (blocking)</li>
 * </ol>
 */
public class SweepFocusController {

    private static final Logger logger = LoggerFactory.getLogger(SweepFocusController.class);

    static final long SETTLE_TIME_MS = 100;
    static final int MIN_MEASUREMENTS = 5;
    static final long SWEEP_TIMEOUT_MS = 15000;
    static final long POLL_INTERVAL_MS = 15;
    static final double ARRIVAL_TOLERANCE_UM = 0.5;
    static final double SATURATION_ABORT_PCT = 5.0;

    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private final MicroscopeSocketClient socketClient;
    private final Supplier<FrameData> frameSupplier;
    private final double searchRangeUm;

    // Reuse the focus metric from RefineFocusController
    private final RefineFocusController metricHelper;

    public SweepFocusController(MicroscopeSocketClient socketClient, Supplier<FrameData> frameSupplier,
                                double searchRangeUm) {
        this.socketClient = socketClient;
        this.frameSupplier = frameSupplier;
        this.searchRangeUm = searchRangeUm;
        // Create a helper instance just for computeFocusMetric / checkSaturation
        this.metricHelper = new RefineFocusController(socketClient, frameSupplier, searchRangeUm);
    }

    public boolean isRunning() {
        return running;
    }

    public void cancel() {
        cancelled = true;
    }

    public void execute(StatusCallback callback) {
        if (running) {
            callback.onStatusUpdate("Sweep Focus already running", Outcome.ERROR);
            return;
        }
        running = true;
        cancelled = false;

        double startZ = Double.NaN;

        try {
            // Phase 0: PREFLIGHT
            FrameData frame = frameSupplier.get();
            if (frame == null) {
                finish(callback, "No frame available -- is live mode active?", Outcome.ERROR);
                return;
            }

            double satPct = metricHelper.checkSaturation(frame);
            if (satPct > SATURATION_ABORT_PCT) {
                finish(callback, String.format(
                        "Aborted: %.1f%% saturated pixels (>%.0f%% threshold). Reduce exposure first.",
                        satPct, SATURATION_ABORT_PCT), Outcome.ERROR);
                return;
            }

            double currentZ = socketClient.getStageZ();
            startZ = currentZ;
            double range = getSearchRange();

            double sweepStart = currentZ - range / 2.0;
            double sweepEnd = currentZ + range / 2.0;

            // Clamp to stage bounds
            MicroscopeConfigManager configMgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (configMgr != null) {
                if (!configMgr.isWithinStageBounds(sweepStart)) {
                    sweepStart = currentZ;
                }
                if (!configMgr.isWithinStageBounds(sweepEnd)) {
                    sweepEnd = currentZ;
                }
            }

            if (Math.abs(sweepEnd - sweepStart) < 1.0) {
                finish(callback, "Search range too small after clamping to stage bounds", Outcome.FAILED);
                return;
            }

            logger.info("Sweep Focus: range [{} -> {}] um (current={})",
                    fmt(sweepStart), fmt(sweepEnd), fmt(currentZ));

            // Phase 1: MOVE TO START (blocking)
            if (cancelled) { returnToZ(startZ, callback); return; }
            callback.onStatusUpdate("Sweep Focus: moving to start...", Outcome.IN_PROGRESS);
            socketClient.moveStageZ(sweepStart);
            Thread.sleep(SETTLE_TIME_MS);

            // Phase 2: SWEEP
            if (cancelled) { returnToZ(startZ, callback); return; }
            callback.onStatusUpdate("Sweep Focus: sweeping...", Outcome.IN_PROGRESS);

            // Issue non-blocking move to end -- stage starts ramping
            socketClient.moveStageZNoWait(sweepEnd);

            List<double[]> measurements = new ArrayList<>();
            long lastFrameTimestamp = 0;
            long sweepStartTime = System.currentTimeMillis();

            while (!cancelled) {
                // Timeout safety
                if (System.currentTimeMillis() - sweepStartTime > SWEEP_TIMEOUT_MS) {
                    logger.warn("Sweep Focus: timeout after {}ms", SWEEP_TIMEOUT_MS);
                    break;
                }

                // Read current Z
                double z = socketClient.getStageZFast();

                // Check if we've arrived at the end
                if (Math.abs(z - sweepEnd) < ARRIVAL_TOLERANCE_UM) {
                    logger.info("Sweep Focus: arrived at end Z={}", fmt(z));
                    break;
                }

                // Get frame -- only process if fresh
                FrameData f = frameSupplier.get();
                if (f != null && f.timestampMs() > lastFrameTimestamp) {
                    lastFrameTimestamp = f.timestampMs();
                    double metric = metricHelper.computeFocusMetric(f);
                    measurements.add(new double[] {z, metric});

                    callback.onStatusUpdate(String.format(
                            "Sweep Focus: Z=%.1f, metric=%.1f (%d pts)",
                            z, metric, measurements.size()), Outcome.IN_PROGRESS);
                }

                Thread.sleep(POLL_INTERVAL_MS);
            }

            // Phase 3: FIND PEAK
            if (cancelled) { returnToZ(startZ, callback); return; }

            if (measurements.size() < MIN_MEASUREMENTS) {
                logger.warn("Sweep Focus: only {} measurements (need {})", measurements.size(), MIN_MEASUREMENTS);
                socketClient.moveStageZ(startZ);
                finish(callback, String.format(
                        "Failed: only %d measurements collected (need %d). Try wider search range.",
                        measurements.size(), MIN_MEASUREMENTS), Outcome.FAILED);
                return;
            }

            // Find the measurement with the best metric
            int bestIdx = 0;
            double bestMetric = measurements.get(0)[1];
            for (int i = 1; i < measurements.size(); i++) {
                if (measurements.get(i)[1] > bestMetric) {
                    bestMetric = measurements.get(i)[1];
                    bestIdx = i;
                }
            }
            double bestZ = measurements.get(bestIdx)[0];

            // Quadratic interpolation around peak for sub-sample refinement
            if (bestIdx > 0 && bestIdx < measurements.size() - 1) {
                double z0 = measurements.get(bestIdx - 1)[0];
                double z1 = measurements.get(bestIdx)[0];
                double z2 = measurements.get(bestIdx + 1)[0];
                double m0 = measurements.get(bestIdx - 1)[1];
                double m1 = measurements.get(bestIdx)[1];
                double m2 = measurements.get(bestIdx + 1)[1];

                double denom = 2.0 * (m0 - 2.0 * m1 + m2);
                if (Math.abs(denom) > 1e-6) {
                    double zPeak = z1 - (m2 - m0) * (z2 - z0) / (2.0 * denom);
                    // Only use interpolation if the peak is between z0 and z2
                    if (zPeak >= Math.min(z0, z2) && zPeak <= Math.max(z0, z2)) {
                        bestZ = zPeak;
                        logger.info("Sweep Focus: quadratic interpolation {} -> {}", fmt(z1), fmt(bestZ));
                    }
                }
            }

            logger.info("Sweep Focus: peak at Z={}, metric={} ({} measurements)",
                    fmt(bestZ), fmt(bestMetric), measurements.size());

            // Phase 4: MOVE TO BEST (blocking)
            socketClient.moveStageZ(bestZ);
            double shift = bestZ - startZ;
            double baseMetric = measurements.get(0)[1];
            String msg = String.format("Sweep Focus complete: shifted %.1fum, %d pts (metric %.1f -> %.1f)",
                    shift, measurements.size(), baseMetric, bestMetric);
            logger.info(msg);
            finish(callback, msg, Outcome.SUCCESS);

        } catch (IOException e) {
            logger.error("Sweep Focus failed: {}", e.getMessage());
            if (!Double.isNaN(startZ)) {
                try { socketClient.moveStageZ(startZ); } catch (IOException ignored) { }
            }
            finish(callback, "Sweep Focus error: " + e.getMessage(), Outcome.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!Double.isNaN(startZ)) {
                try { socketClient.moveStageZ(startZ); } catch (IOException ignored) { }
            }
            finish(callback, "Sweep Focus interrupted", Outcome.ERROR);
        }
    }

    private double getSearchRange() {
        if (searchRangeUm > 0) {
            return searchRangeUm;
        }
        try {
            double pixelSize = socketClient.getMicroscopePixelSize();
            if (pixelSize > 1.0) return 100.0;  // 4x-5x
            if (pixelSize > 0.5) return 60.0;   // 10x
            if (pixelSize > 0.2) return 40.0;   // 20x
            if (pixelSize > 0.1) return 30.0;   // 40x
            return 15.0;                         // 60x-100x
        } catch (IOException e) {
            logger.warn("Could not query pixel size, using default 40um: {}", e.getMessage());
            return 40.0;
        }
    }

    private void returnToZ(double z, StatusCallback callback) {
        try { socketClient.moveStageZ(z); } catch (IOException ignored) { }
        finish(callback, cancelled ? "Sweep Focus cancelled" : "Sweep Focus complete", Outcome.SUCCESS);
    }

    private void finish(StatusCallback callback, String message, Outcome outcome) {
        running = false;
        callback.onStatusUpdate(message, outcome);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
