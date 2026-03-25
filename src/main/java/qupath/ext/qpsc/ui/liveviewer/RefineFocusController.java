package qupath.ext.qpsc.ui.liveviewer;

import java.io.IOException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Automated focus refinement using histogram width (P2-P98 percentile range)
 * as a focus metric with a hill-climbing algorithm that refines down to 0.1um steps.
 * <p>
 * Best used when already close to focus -- the algorithm searches a limited range
 * scaled to the current objective's depth of field.
 * <p>
 * Thread model: {@link #execute(StatusCallback)} runs on the caller's thread
 * (expected to be a background daemon thread). All UI updates go through the
 * {@link StatusCallback}. The algorithm can be cancelled via {@link #cancel()}.
 */
public class RefineFocusController {

    private static final Logger logger = LoggerFactory.getLogger(RefineFocusController.class);

    // Configuration
    static final double MIN_STEP_UM = 0.1;
    static final long SETTLE_TIME_MS = 50; // reduced from 150: wait_for_device already blocks until settled
    static final int FRAMES_TO_AVERAGE = 1; // reduced from 2: single frame is sufficient for P2-P98 metric
    static final double SATURATION_ABORT_PCT = 5.0;
    static final double IMPROVEMENT_THRESHOLD = 0.5; // min metric improvement (in bins)

    // State
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private final MicroscopeSocketClient socketClient;
    private final Supplier<FrameData> frameSupplier;
    private final double searchRangeUm; // 0 = auto from pixel size

    /** Outcome of the refine focus operation. */
    public enum Outcome {
        IN_PROGRESS,
        SUCCESS,
        FAILED,
        ERROR
    }

    /**
     * Callback for status updates from the focus algorithm.
     */
    @FunctionalInterface
    public interface StatusCallback {
        /**
         * @param message Human-readable status text
         * @param outcome current outcome state
         */
        void onStatusUpdate(String message, Outcome outcome);
    }

    /**
     * @param socketClient  socket client for stage moves
     * @param frameSupplier supplies the latest camera frame
     * @param searchRangeUm user-selected search range in um, or 0 for auto (based on pixel size)
     */
    public RefineFocusController(
            MicroscopeSocketClient socketClient, Supplier<FrameData> frameSupplier, double searchRangeUm) {
        this.socketClient = socketClient;
        this.frameSupplier = frameSupplier;
        this.searchRangeUm = searchRangeUm;
    }

    public boolean isRunning() {
        return running;
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * Runs the hill-climbing focus refinement algorithm.
     * Blocks the calling thread until complete or cancelled.
     */
    public void execute(StatusCallback callback) {
        if (running) {
            callback.onStatusUpdate("Refine Focus already running", Outcome.ERROR);
            return;
        }
        running = true;
        cancelled = false;

        double bestZ = Double.NaN;
        double startZ = Double.NaN;

        try {
            // Phase 0: PREFLIGHT
            if (cancelled) {
                finish(callback, "Cancelled", Outcome.SUCCESS);
                return;
            }

            FrameData frame = frameSupplier.get();
            if (frame == null) {
                finish(callback, "No frame available -- is live mode active?", Outcome.ERROR);
                return;
            }

            double satPct = checkSaturation(frame);
            if (satPct > SATURATION_ABORT_PCT) {
                finish(
                        callback,
                        String.format(
                                "Aborted: %.1f%% saturated pixels (>%.0f%% threshold). Reduce exposure first.",
                                satPct, SATURATION_ABORT_PCT),
                        Outcome.ERROR);
                return;
            }

            double initialStep = getInitialStepUm();
            double maxTravel = getMaxTravelUm();
            startZ = socketClient.getStageZ();
            bestZ = startZ;

            callback.onStatusUpdate("Refine Focus: measuring baseline...", Outcome.IN_PROGRESS);
            long ts = captureTimestamp();
            double baseMetric = measureFocus(ts);
            double bestMetric = baseMetric;
            logger.info(
                    "Refine Focus: startZ={}, baseMetric={}, step={}, maxTravel={}",
                    fmt(startZ),
                    fmt(baseMetric),
                    fmt(initialStep),
                    fmt(maxTravel));

            // Phase 1: DETERMINE DIRECTION
            if (cancelled) {
                moveAndFinish(bestZ, startZ, callback);
                return;
            }
            callback.onStatusUpdate("Refine Focus: determining direction...", Outcome.IN_PROGRESS);

            // Try positive direction
            double testZPlus = startZ + initialStep;
            ts = captureTimestamp();
            moveZ(testZPlus);
            settle();
            double metricPlus = measureFocus(ts);

            if (cancelled) {
                moveAndFinish(bestZ, startZ, callback);
                return;
            }

            // Try negative direction
            double testZMinus = startZ - initialStep;
            ts = captureTimestamp();
            moveZ(testZMinus);
            settle();
            double metricMinus = measureFocus(ts);

            // Return to start before deciding
            moveZ(startZ);

            logger.info(
                    "Refine Focus direction probe: base={}, plus={}, minus={}",
                    fmt(baseMetric),
                    fmt(metricPlus),
                    fmt(metricMinus));

            // Check if neither direction improves focus
            if (metricPlus <= baseMetric + IMPROVEMENT_THRESHOLD && metricMinus <= baseMetric + IMPROVEMENT_THRESHOLD) {
                // Both directions are worse -- either already at focus or featureless
                if (baseMetric > 20) {
                    // Decent baseline metric = likely already at best focus
                    String msg = String.format(
                            "Already at best focus (metric=%.0f, no improvement at +/-%.1fum)",
                            baseMetric, initialStep);
                    logger.info("Refine Focus: {}", msg);
                    finish(callback, msg, Outcome.SUCCESS);
                } else {
                    // Low baseline metric = featureless or very far from focus
                    finish(
                            callback,
                            "Failed to find focus! Get closer to focus manually, or widen the search range.",
                            Outcome.FAILED);
                }
                return;
            }

            // Determine best direction
            int direction;
            if (metricPlus > metricMinus) {
                direction = 1;
                if (metricPlus > bestMetric) {
                    bestMetric = metricPlus;
                    bestZ = testZPlus;
                }
            } else {
                direction = -1;
                if (metricMinus > bestMetric) {
                    bestMetric = metricMinus;
                    bestZ = testZMinus;
                }
            }

            // Start hill climb from the best position found by the direction probe
            // so we don't waste a move re-discovering what the probe already found
            double currentZ;
            if (Math.abs(bestZ - startZ) > 0.01) {
                moveZ(bestZ);
                settle();
                currentZ = bestZ;
            } else {
                currentZ = startZ;
            }
            double stepUm = initialStep;

            // Phase 2: HILL CLIMB
            // On improvement: advance currentZ, keep direction and step
            // On non-improvement: halve step, keep direction, stay at currentZ
            //   (bisects interval between last good position and overshoot)
            // On boundary hit: reverse direction + halve step (physical constraint)
            while (stepUm >= MIN_STEP_UM) {
                if (cancelled) {
                    moveAndFinish(bestZ, startZ, callback);
                    return;
                }

                double targetZ = currentZ + (direction * stepUm);

                // Check max travel from start -- reverse for physical constraint
                if (Math.abs(targetZ - startZ) > maxTravel) {
                    logger.info("Refine Focus: max travel exceeded at target={}, reversing", fmt(targetZ));
                    direction = -direction;
                    stepUm /= 2.0;
                    continue;
                }

                // Check stage bounds -- reverse for physical constraint
                MicroscopeConfigManager configMgr = MicroscopeConfigManager.getInstanceIfAvailable();
                if (configMgr != null && !configMgr.isWithinStageBounds(targetZ)) {
                    logger.info("Refine Focus: stage bounds exceeded at target={}, reversing", fmt(targetZ));
                    direction = -direction;
                    stepUm /= 2.0;
                    continue;
                }

                ts = captureTimestamp();
                moveZ(targetZ);
                settle();
                double metric = measureFocus(ts);

                callback.onStatusUpdate(
                        String.format("Refine Focus: step=%.1fum, metric=%.1f (best=%.1f)", stepUm, metric, bestMetric),
                        Outcome.IN_PROGRESS);
                logger.info(
                        "Refine Focus: z={}, step={}, dir={}, metric={}, best={}",
                        fmt(targetZ),
                        fmt(stepUm),
                        direction,
                        fmt(metric),
                        fmt(bestMetric));

                if (metric > bestMetric + IMPROVEMENT_THRESHOLD) {
                    bestMetric = metric;
                    bestZ = targetZ;
                    currentZ = targetZ;
                    // Continue same direction, same step
                } else {
                    stepUm /= 2.0;
                    // Stay at currentZ (last improving position), try smaller step
                    // same direction -- bisects the interval toward the peak
                }
            }

            // Phase 3: FINALIZE
            moveZ(bestZ);
            double shift = bestZ - startZ;
            String msg = String.format(
                    "Refine Focus complete: shifted %.1fum (metric %.1f -> %.1f)", shift, baseMetric, bestMetric);
            logger.info(msg);
            finish(callback, msg, Outcome.SUCCESS);

        } catch (IOException e) {
            logger.error("Refine Focus failed: {}", e.getMessage());
            // Try to return to best known position
            if (!Double.isNaN(bestZ)) {
                try {
                    socketClient.moveStageZ(bestZ);
                } catch (IOException ignored) {
                }
            }
            finish(callback, "Refine Focus error: " + e.getMessage(), Outcome.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!Double.isNaN(bestZ)) {
                try {
                    socketClient.moveStageZ(bestZ);
                } catch (IOException ignored) {
                }
            }
            finish(callback, "Refine Focus interrupted", Outcome.ERROR);
        }
    }

    // --- Focus metric ---

    /**
     * Computes the histogram width (P2-P98 percentile range) as the focus metric.
     * Higher values indicate better contrast/focus.
     */
    double computeFocusMetric(FrameData frame) {
        int[] histogram = new int[256];
        byte[] raw = frame.rawPixels();
        int bpp = frame.bytesPerPixel();
        int channels = frame.channels();
        int maxVal = frame.maxValue();
        int pixelCount = frame.pixelCount();

        if (channels == 1) {
            // Grayscale
            for (int i = 0; i < pixelCount; i++) {
                int val;
                if (bpp == 1) {
                    val = raw[i] & 0xFF;
                } else {
                    int off = i * 2;
                    val = ((raw[off] & 0xFF) << 8) | (raw[off + 1] & 0xFF);
                }
                int bin = val * 255 / maxVal;
                if (bin > 255) bin = 255;
                histogram[bin]++;
            }
        } else {
            // RGB: compute luminance
            int stride = channels * bpp;
            for (int i = 0; i < pixelCount; i++) {
                int off = i * stride;
                int r, g, b;
                if (bpp == 1) {
                    r = raw[off] & 0xFF;
                    g = raw[off + 1] & 0xFF;
                    b = raw[off + 2] & 0xFF;
                } else {
                    r = ((raw[off] & 0xFF) << 8) | (raw[off + 1] & 0xFF);
                    g = ((raw[off + 2] & 0xFF) << 8) | (raw[off + 3] & 0xFF);
                    b = ((raw[off + 4] & 0xFF) << 8) | (raw[off + 5] & 0xFF);
                }
                double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                int bin = (int) (lum * 255.0 / maxVal);
                if (bin > 255) bin = 255;
                histogram[bin]++;
            }
        }

        // Walk cumulative distribution to find P2 and P98
        long totalPixels = pixelCount;
        double p2Count = totalPixels * 0.02;
        double p98Count = totalPixels * 0.98;

        int p2Bin = 0;
        int p98Bin = 255;
        long cumulative = 0;
        boolean foundP2 = false;
        for (int bin = 0; bin < 256; bin++) {
            cumulative += histogram[bin];
            if (!foundP2 && cumulative >= p2Count) {
                p2Bin = bin;
                foundP2 = true;
            }
            if (cumulative >= p98Count) {
                p98Bin = bin;
                break;
            }
        }

        return (double) (p98Bin - p2Bin);
    }

    /**
     * Measures focus using a frame captured after the pre-move timestamp.
     * This ensures the frame reflects the current Z position, not a stale
     * cached frame from before the stage moved.
     *
     * @param preMoveTimestamp timestamp of the frame cached before the move
     */
    double measureFocus(long preMoveTimestamp) throws InterruptedException {
        double sum = 0;
        long lastTs = preMoveTimestamp;
        for (int i = 0; i < FRAMES_TO_AVERAGE; i++) {
            FrameData frame = waitForFreshFrame(lastTs);
            if (frame == null) {
                frame = frameSupplier.get();
                if (frame == null) return 0;
            }
            lastTs = frame.timestampMs();
            sum += computeFocusMetric(frame);
        }
        return sum / FRAMES_TO_AVERAGE;
    }

    /** Captures the current frame timestamp (call before moveZ). */
    long captureTimestamp() {
        FrameData f = frameSupplier.get();
        return (f != null) ? f.timestampMs() : 0;
    }

    /**
     * Waits for a frame with a timestamp newer than the given value.
     * Times out after 2 seconds and returns null.
     */
    private FrameData waitForFreshFrame(long afterTimestamp) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            FrameData frame = frameSupplier.get();
            if (frame != null && frame.timestampMs() > afterTimestamp) {
                return frame;
            }
            Thread.sleep(50);
        }
        return null;
    }

    /**
     * Returns the percentage of saturated pixels (at max value) in the frame.
     */
    double checkSaturation(FrameData frame) {
        byte[] raw = frame.rawPixels();
        int bpp = frame.bytesPerPixel();
        int channels = frame.channels();
        int maxVal = frame.maxValue();
        int pixelCount = frame.pixelCount();
        int saturatedCount = 0;

        if (channels == 1) {
            for (int i = 0; i < pixelCount; i++) {
                int val;
                if (bpp == 1) {
                    val = raw[i] & 0xFF;
                } else {
                    int off = i * 2;
                    val = ((raw[off] & 0xFF) << 8) | (raw[off + 1] & 0xFF);
                }
                if (val >= maxVal) saturatedCount++;
            }
        } else {
            int stride = channels * bpp;
            for (int i = 0; i < pixelCount; i++) {
                int off = i * stride;
                boolean anySaturated = false;
                for (int c = 0; c < channels; c++) {
                    int val;
                    if (bpp == 1) {
                        val = raw[off + c] & 0xFF;
                    } else {
                        int byteOff = off + c * 2;
                        val = ((raw[byteOff] & 0xFF) << 8) | (raw[byteOff + 1] & 0xFF);
                    }
                    if (val >= maxVal) {
                        anySaturated = true;
                        break;
                    }
                }
                if (anySaturated) saturatedCount++;
            }
        }

        return (saturatedCount * 100.0) / pixelCount;
    }

    // --- Step size from pixel size ---

    private double getInitialStepUm() {
        if (searchRangeUm > 0) {
            // User-selected range: initial step is range/3 (probe +-step from start)
            return Math.max(MIN_STEP_UM, searchRangeUm / 3.0);
        }
        try {
            double pixelSize = socketClient.getMicroscopePixelSize();
            // Steps sized to ~2-3x depth of field per objective
            if (pixelSize > 1.0) return 20.0; // 4x-5x
            if (pixelSize > 0.5) return 10.0; // 10x
            if (pixelSize > 0.2) return 5.0; // 20x
            if (pixelSize > 0.1) return 3.0; // 40x
            return 1.0; // 60x-100x
        } catch (IOException e) {
            logger.warn("Could not query pixel size for step sizing, using default 5.0um: {}", e.getMessage());
            return 5.0;
        }
    }

    private double getMaxTravelUm() {
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
            logger.warn("Could not query pixel size for max travel, using default 40um: {}", e.getMessage());
            return 40.0;
        }
    }

    // --- Helpers ---

    private void moveZ(double z) throws IOException {
        socketClient.moveStageZ(z);
    }

    private void settle() throws InterruptedException {
        Thread.sleep(SETTLE_TIME_MS);
    }

    private void moveAndFinish(double bestZ, double startZ, StatusCallback callback) {
        try {
            socketClient.moveStageZ(bestZ);
        } catch (IOException ignored) {
        }
        double shift = bestZ - startZ;
        String msg = cancelled
                ? String.format("Refine Focus cancelled: shifted %.1fum to best position", shift)
                : String.format("Refine Focus complete: shifted %.1fum", shift);
        finish(callback, msg, Outcome.SUCCESS);
    }

    private void finish(StatusCallback callback, String message, Outcome outcome) {
        running = false;
        callback.onStatusUpdate(message, outcome);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
