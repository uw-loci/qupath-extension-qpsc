package qupath.ext.qpsc.stitching;

/**
 * One stitch target's progress and time left, as QPSC keeps it: a copy of tiles-to-pyramid's
 * {@code StitchProgress} in QPSC's own type, so code that holds it never loads tiles-to-pyramid
 * classes an older installed version may not have. {@link StitchProgressBridge} makes them.
 *
 * @param target the angle, channel or derived folder
 * @param step what it is doing, e.g. "writing the image"
 * @param fraction how much of that step is done, 0 to 1
 * @param elapsedSec seconds since the target started
 * @param remainingSec estimated seconds left for the target, NaN while unknown
 * @param measured whether the estimate rests on speed measured in this stitch
 */
public record TargetEta(
        String target, String step, double fraction, double elapsedSec, double remainingSec, boolean measured) {

    /** Fully-qualified name of the tiles-to-pyramid type that proves progress reporting exists. */
    public static final String PROBE_CLASS = "qupath.ext.basicstitching.progress.StitchProgress";
}
