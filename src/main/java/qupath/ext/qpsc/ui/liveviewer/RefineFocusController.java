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
    static final long SETTLE_TIME_MS = 250;
    static final int FRAMES_TO_AVERAGE = 3;
    static final double SATURATION_ABORT_PCT = 5.0;
    static final double IMPROVEMENT_THRESHOLD = 0.5; // min metric improvement (in bins)

    // State
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private final MicroscopeSocketClient socketClient;
    private final Supplier<FrameData> frameSupplier;

    /**
     * Callback for status updates from the focus algorithm.
     */
    @FunctionalInterface
    public interface StatusCallback {
        /**
         * @param message    Human-readable status text
         * @param isComplete true if the algorithm has finished (success or abort)
         * @param isError    true if the algorithm ended due to an error
         */
        void onStatusUpdate(String message, boolean isComplete, boolean isError);
    }

    public RefineFocusController(MicroscopeSocketClient socketClient, Supplier<FrameData> frameSupplier) {
        this.socketClient = socketClient;
        this.frameSupplier = frameSupplier;
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
            callback.onStatusUpdate("Refine Focus already running", true, true);
            return;
        }
        running = true;
        cancelled = false;

        double bestZ = Double.NaN;
        double startZ = Double.NaN;

        try {
            // Phase 0: PREFLIGHT
            if (cancelled) { finish(callback, "Cancelled", false); return; }

            FrameData frame = frameSupplier.get();
            if (frame == null) {
                finish(callback, "No frame available -- is live mode active?", true);
                return;
            }

            double satPct = checkSaturation(frame);
            if (satPct > SATURATION_ABORT_PCT) {
                finish(callback, String.format(
                        "Aborted: %.1f%% saturated pixels (>%.0f%% threshold). Reduce exposure first.",
                        satPct, SATURATION_ABORT_PCT), true);
                return;
            }

            double initialStep = getInitialStepUm();
            double maxTravel = getMaxTravelUm();
            startZ = socketClient.getStageZ();
            bestZ = startZ;

            callback.onStatusUpdate("Refine Focus: measuring baseline...", false, false);
            double baseMetric = measureFocus();
            double bestMetric = baseMetric;
            logger.info("Refine Focus: startZ={}, baseMetric={}, step={}, maxTravel={}",
                    fmt(startZ), fmt(baseMetric), fmt(initialStep), fmt(maxTravel));

            // Phase 1: DETERMINE DIRECTION
            if (cancelled) { moveAndFinish(bestZ, startZ, callback); return; }
            callback.onStatusUpdate("Refine Focus: determining direction...", false, false);

            // Try positive direction
            double testZPlus = startZ + initialStep;
            moveZ(testZPlus);
            settle();
            double metricPlus = measureFocus();

            if (cancelled) { moveAndFinish(bestZ, startZ, callback); return; }

            // Try negative direction
            double testZMinus = startZ - initialStep;
            moveZ(testZMinus);
            settle();
            double metricMinus = measureFocus();

            // Return to start before deciding
            moveZ(startZ);

            logger.info("Refine Focus direction probe: base={}, plus={}, minus={}",
                    fmt(baseMetric), fmt(metricPlus), fmt(metricMinus));

            // Check if already at focus (or featureless)
            if (metricPlus <= baseMetric + IMPROVEMENT_THRESHOLD
                    && metricMinus <= baseMetric + IMPROVEMENT_THRESHOLD) {
                finish(callback, "Already in focus (no improvement found in either direction)", false);
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

            double currentZ = startZ;
            double stepUm = initialStep;

            // Phase 2: HILL CLIMB
            while (stepUm >= MIN_STEP_UM) {
                if (cancelled) { moveAndFinish(bestZ, startZ, callback); return; }

                double targetZ = currentZ + (direction * stepUm);

                // Check max travel from start
                if (Math.abs(targetZ - startZ) > maxTravel) {
                    logger.info("Refine Focus: max travel exceeded at target={}, reversing", fmt(targetZ));
                    direction = -direction;
                    stepUm /= 2.0;
                    continue;
                }

                // Check stage bounds
                MicroscopeConfigManager configMgr = MicroscopeConfigManager.getInstanceIfAvailable();
                if (configMgr != null && !configMgr.isWithinStageBounds(targetZ)) {
                    logger.info("Refine Focus: stage bounds exceeded at target={}, reversing", fmt(targetZ));
                    direction = -direction;
                    stepUm /= 2.0;
                    continue;
                }

                moveZ(targetZ);
                settle();
                double metric = measureFocus();

                callback.onStatusUpdate(String.format(
                        "Refine Focus: step=%.1fum, metric=%.1f (best=%.1f)",
                        stepUm, metric, bestMetric), false, false);
                logger.debug("Refine Focus: z={}, step={}, metric={}, best={}",
                        fmt(targetZ), fmt(stepUm), fmt(metric), fmt(bestMetric));

                if (metric > bestMetric + IMPROVEMENT_THRESHOLD) {
                    bestMetric = metric;
                    bestZ = targetZ;
                    currentZ = targetZ;
                    // Continue same direction, same step
                } else {
                    direction = -direction;
                    stepUm /= 2.0;
                    // Stay at currentZ, refine from here
                }
            }

            // Phase 3: FINALIZE
            moveZ(bestZ);
            double shift = bestZ - startZ;
            String msg = String.format("Refine Focus complete: shifted %.1fum (metric %.1f -> %.1f)",
                    shift, baseMetric, bestMetric);
            logger.info(msg);
            finish(callback, msg, false);

        } catch (IOException e) {
            logger.error("Refine Focus failed: {}", e.getMessage());
            // Try to return to best known position
            if (!Double.isNaN(bestZ)) {
                try { socketClient.moveStageZ(bestZ); } catch (IOException ignored) { }
            }
            finish(callback, "Refine Focus error: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!Double.isNaN(bestZ)) {
                try { socketClient.moveStageZ(bestZ); } catch (IOException ignored) { }
            }
            finish(callback, "Refine Focus interrupted", true);
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
     * Averages the focus metric over multiple frames for noise reduction.
     */
    double measureFocus() throws InterruptedException {
        double sum = 0;
        long lastTimestamp = 0;
        for (int i = 0; i < FRAMES_TO_AVERAGE; i++) {
            FrameData frame = waitForFreshFrame(lastTimestamp);
            if (frame == null) {
                // Use whatever we have
                frame = frameSupplier.get();
                if (frame == null) return 0;
            }
            lastTimestamp = frame.timestampMs();
            sum += computeFocusMetric(frame);
            if (i < FRAMES_TO_AVERAGE - 1) {
                Thread.sleep(100); // wait for next distinct frame
            }
        }
        return sum / FRAMES_TO_AVERAGE;
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
                    if (val >= maxVal) { anySaturated = true; break; }
                }
                if (anySaturated) saturatedCount++;
            }
        }

        return (saturatedCount * 100.0) / pixelCount;
    }

    // --- Step size from pixel size ---

    private double getInitialStepUm() {
        try {
            double pixelSize = socketClient.getMicroscopePixelSize();
            if (pixelSize > 1.0) return 10.0;
            if (pixelSize > 0.5) return 5.0;
            if (pixelSize > 0.2) return 2.0;
            if (pixelSize > 0.1) return 1.0;
            return 0.5;
        } catch (IOException e) {
            logger.warn("Could not query pixel size for step sizing, using default 2.0um: {}", e.getMessage());
            return 2.0;
        }
    }

    private double getMaxTravelUm() {
        try {
            double pixelSize = socketClient.getMicroscopePixelSize();
            if (pixelSize > 1.0) return 50.0;
            if (pixelSize > 0.5) return 40.0;
            if (pixelSize > 0.2) return 20.0;
            if (pixelSize > 0.1) return 15.0;
            return 10.0;
        } catch (IOException e) {
            logger.warn("Could not query pixel size for max travel, using default 20um: {}", e.getMessage());
            return 20.0;
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
        try { socketClient.moveStageZ(bestZ); } catch (IOException ignored) { }
        double shift = bestZ - startZ;
        String msg = cancelled
                ? String.format("Refine Focus cancelled: shifted %.1fum to best position", shift)
                : String.format("Refine Focus complete: shifted %.1fum", shift);
        finish(callback, msg, false);
    }

    private void finish(StatusCallback callback, String message, boolean isError) {
        running = false;
        callback.onStatusUpdate(message, true, isError);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
