package qupath.ext.qpsc.utilities;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a focus-metric profile measured while approaching the sample from a retracted
 * (safe) Z, and decides whether approach-from-safe-Z autofocus can be trusted on this system.
 *
 * <h2>What is being tested, and why it cannot be assumed</h2>
 * Approach-from-safe-Z commits to the first real peak it meets. That is only safe if the metric
 * rises monotonically from the retracted position to the sample plane and peaks there. On a
 * well-tuned system it does. On an arbitrary one it may not: approaching from outside, the scan
 * can cross a coverslip or slide/air interface that produces a genuine contrast peak BEFORE the
 * tissue -- and at high magnification the coverslip is the first thing it meets. Committing
 * there would focus on glass.
 *
 * <p>It also varies by modality: fluorescence is a dark field with signal only near focus, PPM
 * near-extinction angles are dark by design, brightfield is neither. So the profile is measured
 * per (microscope, modality, objective) rather than reasoned about.
 *
 * <h2>What it produces besides pass/fail</h2>
 * The measurement is worth running even where the assumption obviously holds, because it yields
 * the parameters the approach needs: the inventory of false peaks and where they sit, the peak
 * width (which bounds how fast the approach may scan without stepping over focus), and the
 * distance from safe Z to focus (which sets the expected scan duration).
 *
 * <p>Pure computation over an already-captured profile -- no hardware, no I/O, no FX.
 */
public final class FocusProfileAnalysis {

    /**
     * A local maximum in the profile that is not the global one.
     *
     * @param z          stage Z of the peak, in micrometers
     * @param value      metric value at the peak
     * @param prominence how far the metric falls between this peak and the global maximum;
     *                   a shallow valley means this is a shoulder, a deep one means the
     *                   approach really would have stopped here
     */
    public record FalsePeak(double z, double value, double prominence) {}

    /**
     * @param usable            true when an approach committing to the first prominent peak
     *                          would land on the operator's focus
     * @param globalMaxZ        Z of the strongest peak in the profile
     * @param focusOffsetUm     signed distance from the operator's manual focus to
     *                          {@code globalMaxZ}; NaN when no manual focus was supplied
     * @param peakWidthUm       full width at half maximum around the global peak, above the
     *                          profile's baseline; NaN when it cannot be bracketed
     * @param approachDistanceUm distance from the first sample to the global peak
     * @param falsePeaks        prominent peaks encountered BEFORE the global one, nearest-first
     * @param risingFraction    fraction of the approach up to the global peak that ascends
     * @param reasons           human-readable findings, empty when everything passed
     */
    public record Verdict(
            boolean usable,
            double globalMaxZ,
            double focusOffsetUm,
            double peakWidthUm,
            double approachDistanceUm,
            List<FalsePeak> falsePeaks,
            double risingFraction,
            List<String> reasons) {}

    /**
     * A peak must clear this fraction of the profile's full range to count as one the approach
     * would stop at. Below it, the bump is noise or a shoulder rather than a focus plane.
     */
    private static final double PROMINENCE_FRACTION = 0.15;

    /** Fraction of the approach that must ascend for the monotonic assumption to hold. */
    private static final double MIN_RISING_FRACTION = 0.80;

    /** How far the strongest peak may sit from the operator's manual focus, in micrometers. */
    private static final double FOCUS_TOLERANCE_UM = 5.0;

    private FocusProfileAnalysis() {}

    /**
     * Evaluate a captured profile.
     *
     * @param z            stage Z per sample, in approach order (first sample is the retracted
     *                     end); may ascend or descend depending on which way the rig retracts
     * @param metric       focus metric per sample, same length as {@code z}
     * @param manualFocusZ the Z the operator focused on by hand, or {@link Double#NaN} to skip
     *                     that check
     * @return the verdict, never null
     */
    public static Verdict analyse(double[] z, double[] metric, double manualFocusZ) {
        List<String> reasons = new ArrayList<>();
        if (z == null || metric == null || z.length != metric.length || z.length < 5) {
            reasons.add("Profile too short to evaluate (need at least 5 samples).");
            return new Verdict(false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, List.of(), 0, reasons);
        }

        double[] smooth = smooth(metric, smoothingWindow(z));

        int globalIdx = 0;
        for (int i = 1; i < smooth.length; i++) {
            if (smooth[i] > smooth[globalIdx]) {
                globalIdx = i;
            }
        }
        double globalMaxZ = z[globalIdx];
        double approachDistanceUm = Math.abs(globalMaxZ - z[0]);

        double min = smooth[0];
        double max = smooth[0];
        for (double v : smooth) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double range = max - min;
        if (range <= 0) {
            reasons.add("Focus metric is flat across the whole approach -- nothing to focus on, "
                    + "or the metric is not responding on this modality.");
            return new Verdict(false, globalMaxZ, Double.NaN, Double.NaN, approachDistanceUm, List.of(), 0, reasons);
        }

        // Peaks BEFORE the global one are the ones that matter: an approach committing to the
        // first prominent peak would stop at the nearest of these and never reach focus.
        List<FalsePeak> falsePeaks = new ArrayList<>();
        for (int i = 1; i < globalIdx - 1; i++) {
            if (smooth[i] <= smooth[i - 1] || smooth[i] < smooth[i + 1]) {
                continue;
            }
            // Depth of the valley between this bump and the true peak. A shallow valley means
            // the metric barely dipped -- a shoulder on the way up, not a separate plane.
            double valley = smooth[i];
            for (int j = i; j <= globalIdx; j++) {
                valley = Math.min(valley, smooth[j]);
            }
            double prominence = smooth[i] - valley;
            if (prominence >= PROMINENCE_FRACTION * range) {
                falsePeaks.add(new FalsePeak(z[i], smooth[i], prominence));
            }
        }

        int rising = 0;
        for (int i = 1; i <= globalIdx; i++) {
            if (smooth[i] >= smooth[i - 1]) {
                rising++;
            }
        }
        double risingFraction = (globalIdx > 0) ? ((double) rising / globalIdx) : 0.0;

        double focusOffsetUm = Double.isNaN(manualFocusZ) ? Double.NaN : (globalMaxZ - manualFocusZ);
        double peakWidthUm = fullWidthHalfMax(z, smooth, globalIdx, min);

        if (!falsePeaks.isEmpty()) {
            FalsePeak first = falsePeaks.get(0);
            reasons.add(String.format(
                    "A prominent peak sits at Z=%.1f um, before focus at Z=%.1f um. An approach that "
                            + "stops at the first peak would commit there (likely a coverslip or slide "
                            + "surface), not on tissue.",
                    first.z(), globalMaxZ));
        }
        if (risingFraction < MIN_RISING_FRACTION) {
            reasons.add(String.format(
                    "The metric ascends over only %.0f%% of the approach; the monotonic-rise assumption "
                            + "does not hold on this system/modality.",
                    risingFraction * 100));
        }
        if (!Double.isNaN(focusOffsetUm) && Math.abs(focusOffsetUm) > FOCUS_TOLERANCE_UM) {
            reasons.add(String.format(
                    "The strongest peak is %.1f um from the focus you set by hand (tolerance %.1f um). "
                            + "The metric is peaking somewhere other than the sample plane.",
                    focusOffsetUm, FOCUS_TOLERANCE_UM));
        }

        return new Verdict(
                reasons.isEmpty(),
                globalMaxZ,
                focusOffsetUm,
                peakWidthUm,
                approachDistanceUm,
                List.copyOf(falsePeaks),
                risingFraction,
                List.copyOf(reasons));
    }

    /**
     * Full width at half maximum around {@code peakIdx}, measured above {@code baseline}.
     * Returns NaN when the profile does not fall to half height on both sides -- which is
     * itself informative: the peak was not fully captured.
     */
    private static double fullWidthHalfMax(double[] z, double[] v, int peakIdx, double baseline) {
        double half = baseline + (v[peakIdx] - baseline) / 2.0;
        int left = -1;
        for (int i = peakIdx; i > 0; i--) {
            if (v[i] <= half) {
                left = i;
                break;
            }
        }
        int right = -1;
        for (int i = peakIdx; i < v.length; i++) {
            if (v[i] <= half) {
                right = i;
                break;
            }
        }
        if (left < 0 || right < 0) {
            return Double.NaN;
        }
        return Math.abs(z[right] - z[left]);
    }

    /**
     * Moving-average window, in samples. Sized to about 1 um of travel so it suppresses
     * per-frame noise without blurring a peak that may only be a couple of microns wide at high
     * magnification. Always odd and at least 1.
     */
    private static int smoothingWindow(double[] z) {
        double span = Math.abs(z[z.length - 1] - z[0]);
        if (span <= 0) {
            return 1;
        }
        double samplesPerUm = z.length / span;
        int w = (int) Math.round(samplesPerUm);
        if (w < 1) {
            return 1;
        }
        return (w % 2 == 0) ? w + 1 : w;
    }

    /** Centred moving average; edges shrink the window rather than padding. */
    static double[] smooth(double[] v, int window) {
        if (window <= 1) {
            return v.clone();
        }
        int half = window / 2;
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            int lo = Math.max(0, i - half);
            int hi = Math.min(v.length - 1, i + half);
            double sum = 0;
            for (int j = lo; j <= hi; j++) {
                sum += v[j];
            }
            out[i] = sum / (hi - lo + 1);
        }
        return out;
    }
}
