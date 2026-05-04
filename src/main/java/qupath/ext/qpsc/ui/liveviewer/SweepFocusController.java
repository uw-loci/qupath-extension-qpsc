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

    static final int NUM_STEPS = 30; // number of Z positions to sample during sweep
    static final double SATURATION_ABORT_PCT = 5.0;
    /** Stop sweeping after this many consecutive steps below peak metric. */
    static final int EARLY_STOP_DECLINE_COUNT = 5;
    /** Only consider early stop after this fraction of the sweep range has been covered. */
    static final double EARLY_STOP_MIN_PROGRESS = 0.25;

    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private final MicroscopeSocketClient socketClient;
    private final Supplier<FrameData> frameSupplier;
    private final double searchRangeUm;

    // Reuse the focus metric from RefineFocusController
    private final RefineFocusController metricHelper;

    public SweepFocusController(
            MicroscopeSocketClient socketClient, Supplier<FrameData> frameSupplier, double searchRangeUm) {
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
                finish(
                        callback,
                        String.format(
                                "Aborted: %.1f%% saturated pixels (>%.0f%% threshold). Reduce exposure first.",
                                satPct, SATURATION_ABORT_PCT),
                        Outcome.ERROR);
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

            double actualRange = Math.abs(sweepEnd - sweepStart);
            if (actualRange < 1.0) {
                finish(callback, "Search range too small after clamping to stage bounds", Outcome.FAILED);
                return;
            }

            double stepSize = actualRange / NUM_STEPS;
            logger.info(
                    "Sweep Focus: {} steps of {}um over [{} -> {}] (current={})",
                    NUM_STEPS,
                    fmt(stepSize),
                    fmt(sweepStart),
                    fmt(sweepEnd),
                    fmt(currentZ));

            // Phase 1: MOVE TO START (blocking)
            if (cancelled) {
                returnToZ(startZ, callback);
                return;
            }
            callback.onStatusUpdate("Sweep Focus: moving to start...", Outcome.IN_PROGRESS);
            socketClient.moveStageZ(sweepStart);

            // Phase 2: SWEEP -- rapid blocking small-step moves
            // Each moveStageZ blocks only for hardware transit time (~20-50ms for 1um).
            // No settle delay needed: wait_for_device is the settle.
            // Grab cached frame (from live stream) at each position -- no freshness check.
            if (cancelled) {
                returnToZ(startZ, callback);
                return;
            }
            callback.onStatusUpdate("Sweep Focus: sweeping...", Outcome.IN_PROGRESS);

            List<double[]> measurements = new ArrayList<>();
            long sweepStartTime = System.currentTimeMillis();
            double peakMetric = Double.NEGATIVE_INFINITY;
            int peakIdx = -1;
            double minMetric = Double.POSITIVE_INFINITY;
            int stepsSincePeak = 0;
            int minStepsBeforeEarlyStop = (int) (NUM_STEPS * EARLY_STOP_MIN_PROGRESS);
            boolean stoppedEarly = false;

            for (int i = 0; i <= NUM_STEPS; i++) {
                if (cancelled) break;

                double z = sweepStart + i * stepSize;
                long preTs = captureTimestamp();
                socketClient.moveStageZ(z);

                // Wait for a fresh frame that was captured AFTER the move.
                // moveStageZ uses the primary socket so it no longer blocks
                // the aux-socket frame poller, but we still need a frame
                // taken at the new Z (not a pre-move stale frame).
                FrameData f = waitForFreshFrame(preTs);
                if (f == null) {
                    f = frameSupplier.get();
                }
                if (f != null) {
                    double metric = metricHelper.computeFocusMetric(f);
                    int idx = measurements.size();
                    measurements.add(new double[] {z, metric});

                    // Track peak and consecutive decline for early stop
                    if (metric >= peakMetric) {
                        peakMetric = metric;
                        peakIdx = idx;
                        stepsSincePeak = 0;
                    } else {
                        stepsSincePeak++;
                    }
                    if (metric < minMetric) {
                        minMetric = metric;
                    }

                    // Early stop: if we've passed the peak by enough steps and
                    // have covered enough of the range, stop to save time.
                    //
                    // Two guards prevent false early-stop on noise (OWS3 BF 10x
                    // 2026-05-04: a flat-metric scan early-stopped at step 8/30
                    // with peak at the random first sample, then the boundary-
                    // peak retry shifted the next scan AWAY from true focus by
                    // 30 um, then again by another 60 um, ending ~100 um below
                    // focus):
                    //   1. The running peak must NOT be at index 0. A peak
                    //      that never moved off the first sample means we
                    //      saw monotonic decline, not a rise-then-fall, so
                    //      "passed the peak" is meaningless.
                    //   2. The metric range so far must exceed the noise
                    //      floor (mirrors streaming-AF FLAT_METRIC_FRACTION
                    //      = 5%; use 2% here matching the boundary-peak
                    //      retry threshold below).
                    if (i >= minStepsBeforeEarlyStop && stepsSincePeak >= EARLY_STOP_DECLINE_COUNT) {
                        boolean peakAtStart = (peakIdx <= 0);
                        double rangeFracSoFar =
                                (peakMetric - minMetric) / Math.max(Math.abs(peakMetric), 1.0);
                        boolean metricFlat = rangeFracSoFar < 0.02;
                        if (peakAtStart || metricFlat) {
                            logger.info(
                                    "Sweep Focus: suppressed early-stop at step {}/{} "
                                            + "(peakIdx={}, range {} of peak {}) -- "
                                            + "continuing scan",
                                    i, NUM_STEPS, peakIdx,
                                    String.format("%.2f%%", rangeFracSoFar * 100.0),
                                    fmt(peakMetric));
                            // Reset decline counter so the same condition
                            // doesn't retrigger on the very next sample.
                            stepsSincePeak = 0;
                        } else {
                            logger.info(
                                    "Sweep Focus: early stop at step {}/{} -- {} consecutive "
                                            + "steps below peak (metric {} vs peak {})",
                                    i,
                                    NUM_STEPS,
                                    stepsSincePeak,
                                    fmt(metric),
                                    fmt(peakMetric));
                            stoppedEarly = true;
                            break;
                        }
                    }
                }

                if (i % 5 == 0) {
                    callback.onStatusUpdate(
                            String.format("Sweep Focus: %d/%d (Z=%.1f)", i, NUM_STEPS, z), Outcome.IN_PROGRESS);
                }
            }

            long sweepMs = System.currentTimeMillis() - sweepStartTime;
            logger.info(
                    "Sweep Focus: collected {} measurements in {}ms{}",
                    measurements.size(),
                    sweepMs,
                    stoppedEarly ? " (early stop)" : "");

            // Phase 3: FIND PEAK
            if (cancelled) {
                returnToZ(startZ, callback);
                return;
            }

            if (measurements.size() < 3) {
                logger.warn("Sweep Focus: only {} measurements", measurements.size());
                socketClient.moveStageZ(startZ);
                finish(callback, "Failed: insufficient measurements collected", Outcome.FAILED);
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

            // Edge retry: when peak is at boundary with a real score trend,
            // extend the sweep in the peak direction (up to 2 retries).
            int maxEdgeRetries = 2;
            double half = range / 2.0;
            for (int retry = 0; retry < maxEdgeRetries; retry++) {
                if (cancelled) break;
                if (bestIdx > 0 && bestIdx < measurements.size() - 1) break;

                double minM = Double.MAX_VALUE, maxM = Double.NEGATIVE_INFINITY, sumM = 0;
                for (double[] m : measurements) {
                    minM = Math.min(minM, m[1]);
                    maxM = Math.max(maxM, m[1]);
                    sumM += m[1];
                }
                double meanM = sumM / measurements.size();
                double rangePct = (maxM - minM) / Math.max(meanM, 1.0) * 100;
                if (rangePct < 2.0) break;

                double boundaryZ = measurements.get(bestIdx)[0];
                double newCenter = (bestIdx == measurements.size() - 1) ? boundaryZ + half : boundaryZ - half;
                double extStart = newCenter - half;
                double extEnd = newCenter + half;

                if (configMgr != null) {
                    if (!configMgr.isWithinStageBounds(extStart) || !configMgr.isWithinStageBounds(extEnd)) {
                        logger.info("Sweep Focus: edge retry {} would exceed stage bounds, stopping", retry + 1);
                        break;
                    }
                }

                logger.info(
                        "Sweep Focus: peak at boundary (idx={}), retry {} [{} -> {}]",
                        bestIdx,
                        retry + 1,
                        fmt(extStart),
                        fmt(extEnd));
                callback.onStatusUpdate(
                        String.format("Sweep Focus: extending (retry %d)...", retry + 1), Outcome.IN_PROGRESS);

                double extStep = (extEnd - extStart) / NUM_STEPS;
                measurements.clear();
                socketClient.moveStageZ(extStart);
                for (int i = 0; i <= NUM_STEPS; i++) {
                    if (cancelled) break;
                    double z = extStart + i * extStep;
                    long preTs = captureTimestamp();
                    socketClient.moveStageZ(z);
                    FrameData f = waitForFreshFrame(preTs);
                    if (f == null) f = frameSupplier.get();
                    if (f != null) {
                        double metric = metricHelper.computeFocusMetric(f);
                        measurements.add(new double[] {z, metric});
                    }
                }

                if (measurements.size() < 3) break;
                bestIdx = 0;
                bestMetric = measurements.get(0)[1];
                for (int i = 1; i < measurements.size(); i++) {
                    if (measurements.get(i)[1] > bestMetric) {
                        bestMetric = measurements.get(i)[1];
                        bestIdx = i;
                    }
                }
            }

            // Reject persistent boundary peaks. After edge retries
            // exhaust, a peak at the very first or last sampled Z means
            // the search window did not bracket focus -- moving to that
            // Z drives an in-focus stage off-focus (OWS3 incident
            // 2026-05-03: ground-truth Z=2003 with bestIdx at boundary
            // of a 60 um sweep produced bestZ=1944.41, 75 um below
            // truth, then "succeeded" via Phase 5 refinement around
            // that wrong Z). Stay at startZ and report failure to the
            // operator; manual focus is the safer fallback than an
            // unverified boundary guess.
            boolean peakAtBoundary = (bestIdx == 0) || (bestIdx == measurements.size() - 1);
            if (peakAtBoundary) {
                String edgeLabel = (bestIdx == 0) ? "low" : "high";
                logger.warn(
                        "Sweep Focus: peak at {} edge of search window after retries "
                                + "(bestIdx={}/{}, Z={}); search did not bracket focus",
                        edgeLabel, bestIdx, measurements.size() - 1,
                        fmt(measurements.get(bestIdx)[0]));
                try {
                    socketClient.moveStageZ(startZ);
                } catch (IOException ignored) {
                }
                finish(
                        callback,
                        String.format(
                                "Failed: peak at %s edge of %.0fum search window. "
                                        + "Widen the range or pre-focus closer before retrying.",
                                edgeLabel, range),
                        Outcome.FAILED);
                return;
            }

            double bestZ = measurements.get(bestIdx)[0];

            // Quadratic interpolation around peak for sub-sample refinement.
            // Requires at least 2 flanking samples on each side, not just 1:
            // bestIdx==1 with rising-into-z0 scores produces an interpolated
            // peak in [z0, z2] that is mathematically valid but physically
            // misleading -- the true peak is almost always below z0, and
            // committing to an interpolated value near the boundary still
            // walks the stage off-focus.
            boolean canInterpolate = bestIdx >= 2 && bestIdx <= measurements.size() - 3;
            if (canInterpolate) {
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
            } else {
                logger.info(
                        "Sweep Focus: peak too close to edge for safe interpolation "
                                + "(bestIdx={}/{}); using raw sample Z",
                        bestIdx, measurements.size() - 1);
            }

            logger.info(
                    "Sweep Focus: peak at Z={}, metric={} ({} measurements)",
                    fmt(bestZ),
                    fmt(bestMetric),
                    measurements.size());

            // Phase 4: MOVE TO BEST (blocking)
            socketClient.moveStageZ(bestZ);
            double sweepShift = bestZ - startZ;
            double baseMetric = measurements.get(0)[1];
            logger.info(
                    "Sweep phase complete: shifted {}um, {} pts (metric {} -> {})",
                    fmt(sweepShift),
                    measurements.size(),
                    fmt(baseMetric),
                    fmt(bestMetric));

            // Phase 5: AUTO-REFINE around the sweep peak with tight range
            if (cancelled) {
                finish(callback, "Sweep Focus cancelled", Outcome.SUCCESS);
                return;
            }
            callback.onStatusUpdate("Sweep Focus: refining...", Outcome.IN_PROGRESS);

            double refineRange = 3.0; // tight range around the sweep peak
            RefineFocusController refiner = new RefineFocusController(socketClient, frameSupplier, refineRange);

            // Track refinement result via a simple holder
            final double[] refineResult = {bestZ, bestMetric};
            refiner.execute((refMsg, refOutcome) -> {
                if (refOutcome == Outcome.SUCCESS) {
                    // Extract the final Z from the refine message
                    try {
                        double finalZ = socketClient.getStageZ();
                        refineResult[0] = finalZ;
                        logger.info("Refinement succeeded: final Z={}", fmt(finalZ));
                    } catch (IOException ignored) {
                    }
                } else {
                    // Refinement didn't improve -- that's OK, sweep was accurate enough
                    logger.info("Refinement found no improvement -- sweep result is best");
                }
            });

            double finalShift = refineResult[0] - startZ;
            String msg = String.format(
                    "Sweep+Refine complete: shifted %.1fum, %d sweep pts", finalShift, measurements.size());
            logger.info(msg);
            finish(callback, msg, Outcome.SUCCESS);

        } catch (IOException e) {
            logger.error("Sweep Focus failed: {}", e.getMessage());
            if (!Double.isNaN(startZ)) {
                try {
                    socketClient.moveStageZ(startZ);
                } catch (IOException ignored) {
                }
            }
            finish(callback, "Sweep Focus error: " + e.getMessage(), Outcome.ERROR);
        }
    }

    private long captureTimestamp() {
        FrameData f = frameSupplier.get();
        return (f != null) ? f.timestampMs() : 0;
    }

    private FrameData waitForFreshFrame(long afterTimestamp) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            FrameData f = frameSupplier.get();
            if (f != null && f.timestampMs() > afterTimestamp) {
                return f;
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private double getSearchRange() {
        if (searchRangeUm > 0) {
            return searchRangeUm;
        }
        try {
            double pixelSize = socketClient.getMicroscopePixelSize();
            if (pixelSize > 1.0) return 100.0; // 4x-5x
            if (pixelSize > 0.5) return 60.0; // 10x
            if (pixelSize > 0.2) return 40.0; // 20x
            if (pixelSize > 0.1) return 30.0; // 40x
            return 15.0; // 60x-100x
        } catch (IOException e) {
            logger.warn("Could not query pixel size, using default 40um: {}", e.getMessage());
            return 40.0;
        }
    }

    private void returnToZ(double z, StatusCallback callback) {
        try {
            socketClient.moveStageZ(z);
        } catch (IOException ignored) {
        }
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
