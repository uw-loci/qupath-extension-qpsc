package qupath.ext.qpsc.modality;

import java.util.Set;

/**
 * Result of validating background correction settings against acquisition angles.
 *
 * <p>Captures which angles lack background images, which have exposure mismatches,
 * and whether the white balance mode differs between background and acquisition.</p>
 *
 * @author Mike Nelson
 * @since 2.0
 */
public class BackgroundValidationResult {

    /** Angles selected for acquisition that have no matching background image. */
    public final Set<Double> anglesWithoutBackground;

    /** Angles where the background exposure differs from the acquisition exposure. */
    public final Set<Double> angleswithExposureMismatches;

    /** True if the WB mode used for background differs from the current WB mode. */
    public final boolean wbModeMismatch;

    /** The WB mode that was active when backgrounds were collected (may be null). */
    public final String backgroundWbMode;

    /** The WB mode configured for the current acquisition (may be null). */
    public final String currentWbMode;

    /** Human-readable summary of all validation issues found. */
    public final String userMessage;

    /** Sentinel instance indicating no validation issues. */
    public static final BackgroundValidationResult EMPTY =
            new BackgroundValidationResult(Set.of(), Set.of(), false, null, null, "");

    public BackgroundValidationResult(
            Set<Double> anglesWithoutBackground,
            Set<Double> angleswithExposureMismatches,
            boolean wbModeMismatch,
            String backgroundWbMode,
            String currentWbMode,
            String userMessage) {
        this.anglesWithoutBackground = anglesWithoutBackground;
        this.angleswithExposureMismatches = angleswithExposureMismatches;
        this.wbModeMismatch = wbModeMismatch;
        this.backgroundWbMode = backgroundWbMode;
        this.currentWbMode = currentWbMode;
        this.userMessage = userMessage;
    }

    /**
     * Returns true if any validation issues were found.
     */
    public boolean hasIssues() {
        return !anglesWithoutBackground.isEmpty() || !angleswithExposureMismatches.isEmpty() || wbModeMismatch;
    }
}
