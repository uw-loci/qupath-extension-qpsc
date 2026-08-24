package qupath.ext.qpsc.utilities;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches how close a run's achieved focus positions come to the insert's declared safe
 * (retracted) Z, and reports at the end of the run when that clearance is no longer credible.
 *
 * <h2>Why this is needed even though the safe Z is declared</h2>
 * The declared value is measured once, but the thing it must clear moves: slides vary in
 * thickness, an insert gets re-seated, a dish is swapped for a plate, a coverslip is thicker
 * than the last batch. If the sample plane drifts toward the retraction point, retracting stops
 * providing clearance -- and if it drifts PAST it, "retracting" drives the objective into the
 * sample. Neither shows up as an autofocus failure; focus still succeeds, right up until the
 * run where it does not.
 *
 * <p>So the check is cheap and continuous: every focus Z the run achieves is compared against
 * the declared safe Z, and anything alarming is reported once at the end. It runs regardless of
 * acquisition type, because the failure has nothing to do with which workflow was used.
 *
 * <h2>Why the direction is inferred rather than configured</h2>
 * Which sign is "retracted" differs per rig and is exactly the thing that is easy to get
 * backwards. Rather than ask, this reads it from the data: focus positions should all sit on
 * ONE side of the retraction point. A run whose focus positions straddle it has a safe Z inside
 * the range of sample planes, which is the serious case and needs no knowledge of stage
 * polarity to detect.
 *
 * <p>State is process-global and scoped to a run; call {@link #begin} at run start and
 * {@link #report} at the end.
 */
public final class SafeZClearanceMonitor {

    private static final Logger logger = LoggerFactory.getLogger(SafeZClearanceMonitor.class);

    /**
     * Clearance below which the declared retraction is reported as too tight. Chosen against the
     * measured slide-to-slide focus spread (236 um across one carrier, 2026-08-14): a margin
     * much smaller than that would be silent right up to the run that crosses over.
     */
    public static final double MIN_CLEARANCE_UM = 50.0;

    private static volatile boolean active = false;
    private static volatile Double safeZUm = null;
    private static volatile String context = "";
    private static final List<Double> focusPositions = java.util.Collections.synchronizedList(new ArrayList<>());

    private SafeZClearanceMonitor() {}

    /**
     * Starts watching a run.
     *
     * @param safeZ    the safe Z resolved for the insert and modality in use, or null when none
     *                 is declared (the monitor then does nothing)
     * @param contextLabel short description for the report, e.g. "quad_v / ppm_20x"
     */
    public static void begin(Double safeZ, String contextLabel) {
        focusPositions.clear();
        safeZUm = safeZ;
        context = (contextLabel == null) ? "" : contextLabel;
        active = (safeZ != null);
        if (active) {
            logger.info("Safe-Z clearance monitor armed: safe Z={} um ({})", safeZ, context);
        }
    }

    /**
     * Records one achieved focus position. Cheap and safe to call from anywhere, including when
     * no run is active.
     *
     * @param focusZUm the focus Z the microscope settled on, in micrometers
     */
    public static void recordFocus(double focusZUm) {
        if (active && !Double.isNaN(focusZUm)) {
            focusPositions.add(focusZUm);
        }
    }

    /**
     * Starts watching only if nothing else already is, and reports whether this caller now
     * owns the run.
     *
     * <p>A single-image workflow runs inside each slot of a multi-slide batch, so both want to
     * bracket "a run". The batch's boundaries are the correct ones -- reporting per slot would
     * fire the same maintenance warning four times and compare against a fraction of the
     * evidence -- so the batch arms first and the inner workflow defers to it.
     *
     * @param safeZ        safe Z resolved for the insert and modality in use, or null
     * @param contextLabel short description for the report
     * @return true when this caller armed the monitor and must therefore report it
     */
    public static boolean beginIfIdle(Double safeZ, String contextLabel) {
        if (active) {
            return false;
        }
        begin(safeZ, contextLabel);
        return active;
    }

    /** True while a run is being watched. */
    public static boolean isActive() {
        return active;
    }

    /** Stops watching without reporting. */
    public static void cancel() {
        active = false;
        focusPositions.clear();
    }

    /**
     * Ends the run and returns what should be shown to the operator, or null when the clearance
     * is fine (or nothing was measured).
     *
     * <p>Two findings, in order of seriousness:
     * <ol>
     *   <li><b>Focus positions on both sides of the safe Z</b> -- the retraction point sits
     *       inside the range of sample planes, so retracting is not a retraction for at least
     *       some samples. This is the one that can drive into the sample.</li>
     *   <li><b>Closest focus within {@value #MIN_CLEARANCE_UM} um</b> -- still on the correct
     *       side, but the margin has shrunk far enough to be worth re-measuring.</li>
     * </ol>
     *
     * @return warning text, or null when there is nothing to report
     */
    public static String report() {
        if (!active) {
            return null;
        }
        active = false;
        List<Double> observed = new ArrayList<>(focusPositions);
        focusPositions.clear();
        Double safeZ = safeZUm;
        if (safeZ == null || observed.isEmpty()) {
            return null;
        }

        int above = 0;
        int below = 0;
        double closest = Double.MAX_VALUE;
        double closestZ = Double.NaN;
        for (double z : observed) {
            double delta = z - safeZ;
            if (delta > 0) {
                above++;
            } else if (delta < 0) {
                below++;
            }
            if (Math.abs(delta) < closest) {
                closest = Math.abs(delta);
                closestZ = z;
            }
        }

        String where = context.isEmpty() ? "" : (" for " + context);
        if (above > 0 && below > 0) {
            String msg = String.format(
                    "Focus positions this run fell on BOTH sides of the configured safe Z (%.1f um)%s: "
                            + "%d above and %d below. The retraction point is inside the range of sample "
                            + "planes, so retracting to it is not a retraction for every sample. "
                            + "Re-measure the safe Z for this insert before running unattended focus.",
                    safeZ, where, above, below);
            logger.warn("Safe-Z clearance: {}", msg);
            return msg;
        }
        if (closest < MIN_CLEARANCE_UM) {
            String msg = String.format(
                    "Focus came within %.1f um of the configured safe Z (%.1f um)%s -- closest focus was "
                            + "%.1f um. The retraction is still on the correct side, but the clearance has "
                            + "shrunk below %.0f um; re-measure it for this insert.",
                    closest, safeZ, where, closestZ, MIN_CLEARANCE_UM);
            logger.warn("Safe-Z clearance: {}", msg);
            return msg;
        }
        logger.info(
                "Safe-Z clearance OK{}: {} focus position(s), closest {} um from safe Z",
                where,
                observed.size(),
                String.format("%.1f", closest));
        return null;
    }
}
