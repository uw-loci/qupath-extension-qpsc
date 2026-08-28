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

    /**
     * How far the strongest peak may sit from the operator's manual focus, in micrometers.
     *
     * <p>Assumes the manual focus was set from the LIVE CAMERA IMAGE. The eyepiece and camera
     * port are not necessarily parfocal, so an eyepiece focus can sit tens of microns from where
     * the camera is sharp -- which would fail this check on a microscope that is working
     * perfectly. The tool's instructions say so explicitly; do not loosen this tolerance to
     * accommodate an eyepiece focus.
     */
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
     * Compares a scan taken over tissue with one taken over bare slide at the same Z range.
     *
     * <p>This is what makes the coverslip question empirical rather than a guess. A peak that
     * appears in BOTH scans cannot be tissue -- there was no tissue in the second one -- so it
     * is a surface: coverslip, slide/air interface, mounting medium. A peak present only over
     * tissue is the real focus. No threshold tuning decides this; the second scan does.
     *
     * @param tissueZ      Z samples of the over-tissue scan, in approach order
     * @param tissueMetric metric samples of the over-tissue scan
     * @param bgZ          Z samples of the over-background scan
     * @param bgMetric     metric samples of the over-background scan
     * @param manualFocusZ the Z the operator focused tissue on, or NaN to skip that check
     * @return the paired verdict
     */
    public static PairVerdict analysePair(
            double[] tissueZ, double[] tissueMetric, double[] bgZ, double[] bgMetric, double manualFocusZ) {

        Verdict tissue = analyse(tissueZ, tissueMetric, manualFocusZ);
        List<String> reasons = new ArrayList<>();
        if (Double.isNaN(tissue.globalMaxZ())) {
            reasons.add("The over-tissue scan has no usable focus peak.");
            return new PairVerdict(false, false, tissue, List.of(), false, List.copyOf(reasons));
        }

        // The tissue peak's amplitude above baseline is the yardstick the background is judged
        // against; see prominentPeaks for why its own range is the wrong one.
        double tissueAmplitude = amplitude(tissueMetric);
        List<FalsePeak> bgPeaks = prominentPeaks(bgZ, bgMetric, tissueAmplitude);

        // A blank scan that is essentially flat is the EXPECTED result, not a defect, and it
        // looks alarming: a low staircase that never reaches a maximum. Say so, so nobody reads
        // a clean pass as a failed measurement.
        double bgAmplitude = amplitude(bgMetric);
        if (tissueAmplitude > 0 && bgAmplitude < 0.15 * tissueAmplitude) {
            reasons.add(String.format(
                    "The no-tissue scan is flat (%.0f%% of the tissue scan's amplitude) and never reaches a "
                            + "peak. That is the expected result: with no sample there is nothing to focus on, "
                            + "and the steps visible in that curve are the focus metric's own quantisation "
                            + "rather than structure.",
                    100.0 * bgAmplitude / tissueAmplitude));
        }

        // How close two peaks must be to be the same surface. Scaled to the measured peak
        // width so a broad low-mag peak is not split into two by sampling jitter.
        double tolerance = Double.isNaN(tissue.peakWidthUm()) ? 5.0 : Math.max(5.0, tissue.peakWidthUm());

        boolean tissuePeakIsDistinct =
                bgPeaks.stream().noneMatch(b -> Math.abs(b.z() - tissue.globalMaxZ()) <= tolerance);

        List<FalsePeak> surfacePeaks = new ArrayList<>();
        for (FalsePeak candidate : tissue.falsePeaks()) {
            if (bgPeaks.stream().anyMatch(b -> Math.abs(b.z() - candidate.z()) <= tolerance)) {
                surfacePeaks.add(candidate);
            }
        }

        if (!tissuePeakIsDistinct) {
            reasons.add(String.format(
                    "The strongest peak over tissue (Z=%.1f um) also appears with no tissue present, so it "
                            + "is a surface rather than the sample plane. Autofocus on this "
                            + "modality/objective is keying on something other than the tissue.",
                    tissue.globalMaxZ()));
        }
        for (FalsePeak p : surfacePeaks) {
            reasons.add(String.format(
                    "A surface peak sits at Z=%.1f um, before focus at Z=%.1f um -- confirmed by the "
                            + "background scan. An approach that stops at the first peak would commit there.",
                    p.z(), tissue.globalMaxZ()));
        }
        // Findings from the tissue scan alone (flat metric, peak away from the manual focus,
        // non-monotonic rise) still apply.
        reasons.addAll(tissue.reasons().stream()
                .filter(r -> !r.contains("likely a coverslip"))
                .toList());

        // The strongest peak must be AT the sample plane the operator focused on by hand. This
        // was missing, and its absence produced a self-contradicting verdict: PASSED, alongside
        // its own finding that "the strongest peak is 207.1 um from the focus you set by hand --
        // the metric is peaking somewhere other than the sample plane". The single-scan Verdict
        // gets this right (it fails on any reason); analysePair recomputed usable from scratch
        // and dropped it.
        //
        // Passing that record is worse than failing it. approachDistanceUm is measured to the
        // STRONGEST peak, so a peak 207 um short of the sample also writes a travel bound 207 um
        // short -- and the licensed approach then scans a range that never reaches the sample at
        // all. The previous, unlicensed behaviour at least scanned past it.
        //
        // NaN means no manual focus was supplied to check against, which is not evidence of a
        // problem, so it does not fail here.
        boolean peakIsAtTheSamplePlane =
                Double.isNaN(tissue.focusOffsetUm()) || Math.abs(tissue.focusOffsetUm()) <= FOCUS_TOLERANCE_UM;
        boolean usable = tissuePeakIsDistinct
                && peakIsAtTheSamplePlane
                && !Double.isNaN(tissue.globalMaxZ())
                && tissue.risingFraction() > 0;
        return new PairVerdict(
                usable,
                !surfacePeaks.isEmpty(),
                tissue,
                List.copyOf(surfacePeaks),
                tissuePeakIsDistinct,
                List.copyOf(reasons));
    }

    /**
     * Outcome of comparing the over-tissue and over-background scans.
     *
     * @param usable             a tissue focus peak exists and is distinguishable from any surface
     * @param requiresTissueGate surface peaks sit BEFORE focus, so committing to the first peak
     *                           would land on glass -- the approach must gate on tissue detection
     * @param tissue             the over-tissue scan's own analysis
     * @param surfacePeaks       peaks before focus that the background scan confirmed are surfaces
     * @param tissuePeakIsDistinct the focus peak has no counterpart in the background scan
     * @param reasons            findings, empty when nothing is wrong
     */
    public record PairVerdict(
            boolean usable,
            boolean requiresTissueGate,
            Verdict tissue,
            List<FalsePeak> surfacePeaks,
            boolean tissuePeakIsDistinct,
            List<String> reasons) {}

    /**
     * Every prominent local maximum in a profile, measured against the deeper adjacent valley.
     * Used to inventory what the background scan contains, where there is no "true" peak to
     * measure prominence against.
     *
     * @param z      Z samples in scan order
     * @param metric metric samples
     * @return prominent peaks in scan order; empty when the profile is unusable or flat
     */
    /**
     * Peak-to-baseline amplitude of a profile: its full range after smoothing.
     *
     * @param metric the metric samples
     * @return the range, or 0 when there is nothing to measure
     */
    private static double amplitude(double[] metric) {
        if (metric == null || metric.length < 2) {
            return 0;
        }
        double min = metric[0];
        double max = metric[0];
        for (double x : metric) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        return max - min;
    }

    public static List<FalsePeak> prominentPeaks(double[] z, double[] metric, double referenceRange) {
        List<FalsePeak> peaks = new ArrayList<>();
        if (z == null || metric == null || z.length != metric.length || z.length < 5) {
            return peaks;
        }
        double[] v = smooth(metric, smoothingWindow(z));
        double min = v[0];
        double max = v[0];
        for (double x : v) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        double range = max - min;
        if (range <= 0) {
            return peaks;
        }
        // Judge prominence against the REFERENCE range when one is supplied -- for a background
        // scan that is the tissue scan's amplitude.
        //
        // Measuring a blank scan against its own range is the trap. A blank field has no focus
        // signal, so its entire dynamic range is metric quantisation: p98_p2 on 8-bit data is a
        // difference of integer percentiles averaged over three channels, so it moves in steps
        // of 1/3 and the curve is a visible staircase. Against a range that IS three steps, one
        // step clears 15% comfortably and every step edge is reported as a surface peak. Judged
        // against a tissue peak of 86 counts (PPM 20x, 2026-08-26) the same step is 0.4% and
        // correctly reads as nothing.
        //
        // This is also the right comparison physically. Both scans run at identical exposure and
        // illumination, so their metrics share a scale, and a surface only matters if the
        // approach would actually stop at it -- which it judges on a scan containing the tissue
        // peak too.
        double threshold = PROMINENCE_FRACTION * Math.max(range, referenceRange);
        for (int i = 1; i < v.length - 1; i++) {
            if (v[i] <= v[i - 1] || v[i] < v[i + 1]) {
                continue;
            }
            double leftValley = v[i];
            for (int j = i; j >= 0; j--) {
                leftValley = Math.min(leftValley, v[j]);
                if (v[j] > v[i]) {
                    break;
                }
            }
            double rightValley = v[i];
            for (int j = i; j < v.length; j++) {
                rightValley = Math.min(rightValley, v[j]);
                if (v[j] > v[i]) {
                    break;
                }
            }
            double prominence = v[i] - Math.max(leftValley, rightValley);
            if (prominence >= threshold) {
                peaks.add(new FalsePeak(z[i], v[i], prominence));
            }
        }
        return peaks;
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
