package qupath.ext.qpsc.utilities;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Normalized identifier for a (modality, objective, detector) hardware
 * combination, used as the lookup key for background-correction calibration
 * data and similar per-rig settings.
 *
 * <p>The full {@code (modality, objective, detector)} triple a caller passes
 * is rich (e.g. {@code Brightfield_10x}, {@code 0.75NA_AIR_10x},
 * {@code HAMAMATSU_DCAM_01}). The on-disk and YAML storage are coarser:
 * background TIFFs live under
 * {@code <base>/<detector>/<family>/<magnification>/...}, which collapses any
 * two objectives at the same magnification into the same folder. This class
 * makes the canonical form explicit:
 *
 * <ul>
 *   <li>{@link #modalityFamily()} - modality with any trailing
 *       {@code _<digits><x|X>} stripped (e.g. {@code Brightfield_10x} -&gt;
 *       {@code Brightfield}, but {@code BF_IF_10x} -&gt; {@code BF_IF}).</li>
 *   <li>{@link #magnification()} - {@code 10x}, {@code 20x}, ... extracted from
 *       the objective ID.</li>
 *   <li>{@link #detector()} - hard axis; never softened.</li>
 *   <li>{@link #objective()} - retained verbatim for advisory match and
 *       diagnostic logging.</li>
 * </ul>
 *
 * <p>Three match policies are exposed:
 * <ul>
 *   <li>{@link #matchesExact(HardwareKey)} - all four axes equal.</li>
 *   <li>{@link #matchesFamilyObjective(HardwareKey)} - family + objective +
 *       detector match (objective string equal verbatim, modality may differ
 *       only in magnification suffix).</li>
 *   <li>{@link #matchesFamilyMagnification(HardwareKey)} - family + magnification
 *       + detector match (objective ID may differ as long as magnification
 *       collapses to the same value). This is the on-disk-equivalence policy.</li>
 * </ul>
 *
 * <p>Detector and modality family are always hard axes - cross-detector
 * exposure reuse is unsafe (different QE / well depth), and cross-modality
 * reuse is physically meaningless (Brightfield vs PPM vs Fluorescence).
 */
public final class HardwareKey {

    private static final Pattern MAGNIFICATION_TRAILING =
            Pattern.compile("_(\\d+)[xX]$");

    private final String modalityFamily;
    private final String magnification;
    private final String detector;
    private final String objective;

    private HardwareKey(String modalityFamily, String magnification, String detector, String objective) {
        this.modalityFamily = modalityFamily;
        this.magnification = magnification;
        this.detector = detector;
        this.objective = objective;
    }

    /**
     * Construct a HardwareKey from raw caller-supplied strings. Any of the
     * inputs may be null; the resulting key fields will be null in turn and
     * matching against another key will fail unless both sides are also null.
     *
     * @param modality  raw modality id (e.g. {@code Brightfield_10x}, {@code ppm})
     * @param objective raw objective id (e.g. {@code LOCI_OBJECTIVE_OLYMPUS_10X_001})
     * @param detector  raw detector id
     */
    public static HardwareKey from(String modality, String objective, String detector) {
        return new HardwareKey(stripMagnificationSuffix(modality), ObjectiveUtils.extractMagnification(objective), detector, objective);
    }

    /**
     * Construct a HardwareKey from values that have already been stored (e.g.
     * read from a YAML record). The {@code magnification} parameter is taken
     * verbatim if non-null; otherwise it is derived from the objective string.
     */
    public static HardwareKey ofStored(String modality, String objective, String detector, String storedMagnification) {
        String mag = (storedMagnification != null && !storedMagnification.isEmpty())
                ? storedMagnification
                : ObjectiveUtils.extractMagnification(objective);
        return new HardwareKey(stripMagnificationSuffix(modality), mag, detector, objective);
    }

    /**
     * Strip a trailing {@code _<digits><x|X>} segment (and only that exact
     * pattern) from a modality id. Modality families may contain internal
     * underscores (e.g. {@code BF_IF}), so we anchor the strip to the very end.
     */
    public static String stripMagnificationSuffix(String modality) {
        if (modality == null || modality.isEmpty()) {
            return modality;
        }
        return MAGNIFICATION_TRAILING.matcher(modality).replaceFirst("");
    }

    public String modalityFamily() {
        return modalityFamily;
    }

    public String magnification() {
        return magnification;
    }

    public String detector() {
        return detector;
    }

    public String objective() {
        return objective;
    }

    /** All four axes equal (modality family, magnification, detector, objective). */
    public boolean matchesExact(HardwareKey other) {
        return other != null
                && Objects.equals(this.modalityFamily, other.modalityFamily)
                && Objects.equals(this.magnification, other.magnification)
                && Objects.equals(this.detector, other.detector)
                && Objects.equals(this.objective, other.objective);
    }

    /**
     * Family + objective + detector match. Used to absorb the case where one
     * side carries a magnification-suffixed modality ({@code Brightfield_10x})
     * and the other carries the family form ({@code Brightfield}); the
     * magnification axis is implicitly equal because the objective is.
     */
    public boolean matchesFamilyObjective(HardwareKey other) {
        return other != null
                && Objects.equals(this.modalityFamily, other.modalityFamily)
                && Objects.equals(this.detector, other.detector)
                && Objects.equals(this.objective, other.objective);
    }

    /**
     * Family + magnification + detector match. Tolerates objective-ID drift
     * (e.g. {@code 0.5NA_AIR_10x} vs {@code 0.75NA_AIR_10x}); the underlying
     * background TIFF folder layout already collapses on this same key.
     */
    public boolean matchesFamilyMagnification(HardwareKey other) {
        return other != null
                && Objects.equals(this.modalityFamily, other.modalityFamily)
                && Objects.equals(this.magnification, other.magnification)
                && Objects.equals(this.detector, other.detector);
    }

    @Override
    public String toString() {
        return "HardwareKey[family=" + modalityFamily
                + ", mag=" + magnification
                + ", detector=" + detector
                + ", objective=" + objective + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HardwareKey other)) return false;
        return Objects.equals(modalityFamily, other.modalityFamily)
                && Objects.equals(magnification, other.magnification)
                && Objects.equals(detector, other.detector)
                && Objects.equals(objective, other.objective);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modalityFamily, magnification, detector, objective);
    }
}
