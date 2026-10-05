package qupath.ext.qpsc.controller.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers that a slide's alignment could not be confirmed against the sample, so the rest
 * of a batch does not run on the same assumption.
 *
 * <p>Separate from the stage-position watchdog on purpose, because the two say different
 * things and the operator needs to know which happened. The watchdog says the stage is not
 * where it claims to be; this says the sample is not where the alignment says it is. The
 * second can be true with a perfectly behaved controller -- which is exactly the 2026-10-02
 * case, where the position counter was provably clean across 34 hours while three slides
 * came out offset.
 *
 * <p>Sticky for the same reason: every slide in a batch was aligned in the same frame, so
 * one slide failing to confirm means the others are suspect too. Cleared only when the
 * operator deliberately starts a pass again, by which point they have seen the alert.
 *
 * @since 0.9.3
 */
public final class AlignmentVerificationGate {

    private static final Logger logger = LoggerFactory.getLogger(AlignmentVerificationGate.class);

    private static volatile String reason = null;
    private static volatile AlignmentVerification.Verdict lastVerdict = null;

    private AlignmentVerificationGate() {}

    /**
     * Records a failed verification.
     *
     * @param slideLabel name of the slide that could not be confirmed
     * @param verdict    the verdict, kept so the alert can distinguish a measured
     *                   disagreement from a failure to match at all
     */
    public static void record(String slideLabel, AlignmentVerification.Verdict verdict) {
        if (reason != null) {
            return;
        }
        lastVerdict = verdict;
        reason = slideLabel + ": " + (verdict == null ? "alignment could not be confirmed" : verdict.message());
        logger.error("ALIGNMENT NOT CONFIRMED: {}", reason);
    }

    /** Why the batch should not continue, or null. */
    public static String reason() {
        return reason;
    }

    /** True once a slide has failed to confirm. */
    public static boolean isBlocked() {
        return reason != null;
    }

    /**
     * The verdict behind {@link #reason()}, or null.
     *
     * <p>Lets a caller tell MISALIGNED (a measurement: the slide is this far off) from
     * INCONCLUSIVE (nothing matched, which is consistent with a large displacement but also
     * with a blank or defocused field). The recovery differs, so the operator is told which.
     */
    public static AlignmentVerification.Verdict lastVerdict() {
        return lastVerdict;
    }

    /** Forgets a previous failure, for a pass the operator has deliberately restarted. */
    public static void clear() {
        if (reason != null) {
            logger.info("Alignment-verification gate cleared for a new run");
        }
        reason = null;
        lastVerdict = null;
    }
}
