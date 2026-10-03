package qupath.ext.qpsc.utilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tilts measured on the slides loaded in the holder right now.
 *
 * <p>Why a holder-level idea exists at all: measured across ten regions, a slide's focus
 * plane tilts anywhere from 1.2 to 10.8 um/mm, so tilt cannot be calibrated once and
 * reused. But within one loading it looks close to reproducible between slots -- the
 * 2026-08-12 session measured (-7.67, -4.78) and (-7.65, -5.59) um/mm on two different
 * slides -- while the intercepts moved freely, from -157 to -379 um. The reading is that
 * the tilt is mostly the holder's and the height is the slide's. If that holds, a slide
 * needs one focus measurement rather than three, because the tilt can be inherited and
 * only the offset has to be measured.
 *
 * <p>That is two slides of evidence, which is not enough to build on. So this registry
 * does not assume the premise -- it <b>tests it every time</b>. Nothing is inherited
 * until at least two slides in the current loading have produced tilts that agree with
 * each other, and the moment they disagree the registry says so and offers nothing.
 * When the premise is false on a given rig or holder, the feature turns itself off
 * rather than quietly handing out a wrong tilt.
 *
 * <p>Session-scoped and in-memory on purpose: it describes the slides physically in the
 * holder. Call {@link #clear} whenever that stops being true -- a new insert, a new
 * loading, or a stage re-zero, which moves the whole frame the tilts were measured in.
 */
public final class HolderTiltRegistry {

    private static final Logger logger = LoggerFactory.getLogger(HolderTiltRegistry.class);

    /**
     * How closely two slides' tilts must agree, um per mm, before either is treated as
     * telling us about the holder.
     *
     * <p>2.0 is wider than the 0.1 um/mm spread the two 2026-08-12 slides showed and far
     * narrower than the 12 um/mm range across sessions, so it separates "same holder" from
     * "different seating" without being a coin flip. At a 14 mm region, 2 um/mm of tilt
     * error is 28 um of focus error at the far corner -- which is why an inherited tilt is
     * only ever used as an approach hint.
     */
    private static final double AGREEMENT_UM_PER_MM = 2.0;

    /** Slides needed before anything is inherited. Two is the minimum that can agree. */
    private static final int MIN_AGREEING_SLIDES = 2;

    private static final Map<String, double[]> tiltsBySlide = new LinkedHashMap<>();

    private HolderTiltRegistry() {}

    /** A tilt the holder's slides agree on, as {@code {umPerMmX, umPerMmY}}. */
    public record HolderTilt(double umPerMmX, double umPerMmY, int slideCount, double spreadUmPerMm) {}

    /**
     * Record the tilt measured on one slide.
     *
     * <p>Only surfaces with redundancy are accepted. A three-point plane fits its points
     * exactly whatever they were, so its tilt could be anything and it must not become
     * evidence about the holder.
     *
     * @param slideName the slide's lookup key
     * @param surface the surface measured on it
     */
    public static synchronized void record(String slideName, AffineTransformManager.SlideFocusSurface surface) {
        if (slideName == null || surface == null) {
            return;
        }
        if (!surface.hasRedundancy()) {
            logger.debug(
                    "Holder tilt: not recording '{}' -- its surface came from {} points, so its " + "tilt is unchecked",
                    slideName,
                    surface.pointCount());
            return;
        }
        tiltsBySlide.put(slideName, new double[] {surface.tiltXUmPerMm(), surface.tiltYUmPerMm()});
        logger.info(
                "Holder tilt: recorded '{}' at ({}, {}) um/mm; {} slide(s) now measured",
                slideName,
                String.format("%.2f", surface.tiltXUmPerMm()),
                String.format("%.2f", surface.tiltYUmPerMm()),
                tiltsBySlide.size());
    }

    /**
     * The tilt this holder's slides agree on, or empty with the reason logged.
     *
     * <p>Agreement is judged on the spread of the measured tilts, not on their average:
     * averaging two tilts that disagree produces a number that describes neither slide.
     *
     * @param excludeSlide a slide to leave out, so a slide cannot inherit from itself
     */
    public static synchronized Optional<HolderTilt> agreedTilt(String excludeSlide) {
        List<double[]> tilts = new ArrayList<>();
        for (Map.Entry<String, double[]> e : tiltsBySlide.entrySet()) {
            if (!e.getKey().equals(excludeSlide)) {
                tilts.add(e.getValue());
            }
        }
        if (tilts.size() < MIN_AGREEING_SLIDES) {
            logger.debug(
                    "Holder tilt: {} other slide(s) measured, need {} before a tilt can be inherited",
                    tilts.size(),
                    MIN_AGREEING_SLIDES);
            return Optional.empty();
        }
        double meanX = tilts.stream().mapToDouble(t -> t[0]).average().orElse(0);
        double meanY = tilts.stream().mapToDouble(t -> t[1]).average().orElse(0);
        double spread = 0;
        for (double[] t : tilts) {
            spread = Math.max(spread, Math.hypot(t[0] - meanX, t[1] - meanY));
        }
        if (spread > AGREEMENT_UM_PER_MM) {
            logger.info(
                    "Holder tilt: the {} measured slides disagree by {} um/mm (limit {}), so tilt "
                            + "is NOT a property of this holder and nothing is inherited. Each "
                            + "slide's focus is measured on its own, as before.",
                    tilts.size(),
                    String.format("%.2f", spread),
                    String.format("%.2f", AGREEMENT_UM_PER_MM));
            return Optional.empty();
        }
        return Optional.of(new HolderTilt(meanX, meanY, tilts.size(), spread));
    }

    /**
     * Forget everything. Call when the slides in the holder change, the insert changes,
     * or the stage is re-zeroed -- a re-zero translates the frame these tilts were
     * measured in, so they stop describing anything.
     */
    public static synchronized void clear() {
        if (!tiltsBySlide.isEmpty()) {
            logger.info("Holder tilt: forgetting {} measured slide tilt(s)", tiltsBySlide.size());
        }
        tiltsBySlide.clear();
    }

    /** How many slides have contributed a checked tilt. */
    public static synchronized int slideCount() {
        return tiltsBySlide.size();
    }
}
