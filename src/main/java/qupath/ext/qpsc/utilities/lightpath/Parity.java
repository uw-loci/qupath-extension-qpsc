package qupath.ext.qpsc.utilities.lightpath;

/**
 * A per-axis mirror parity -- whether an orientation-affecting stage of the light
 * path flips the X and/or Y axis. The atomic unit that every {@link LightPath}
 * composition is built from, replacing bare {@code boolean[]} pairs.
 *
 * <p>Parities compose by per-axis XOR ({@link #xor}): applying two mirrors on the
 * same axis cancels. {@link #IDENTITY} is the no-flip element. This is deliberately
 * a value type with no rotation component -- axis-aligned mirrors are all the
 * current light paths need; a genuine camera rotation is handled separately (see
 * the rotated-insert geometry in {@code StageImageTransform}). A forked scope that
 * needs rotation would extend the model there, not here.</p>
 *
 * @param flipX true if this stage mirrors the X axis
 * @param flipY true if this stage mirrors the Y axis
 */
public record Parity(boolean flipX, boolean flipY) {

    /** The no-flip element. */
    public static final Parity IDENTITY = new Parity(false, false);

    /** Compose two parities: per-axis XOR (same-axis mirrors cancel). */
    public Parity xor(Parity other) {
        return new Parity(flipX ^ other.flipX, flipY ^ other.flipY);
    }

    /** True when neither axis is flipped. */
    public boolean isIdentity() {
        return !flipX && !flipY;
    }

    /** As a {@code {flipX, flipY}} array, for the many APIs still on that shape. */
    public boolean[] toArray() {
        return new boolean[] {flipX, flipY};
    }

    /** From a {@code {flipX, flipY}} array (null or short -&gt; identity axes). */
    public static Parity of(boolean[] a) {
        if (a == null) {
            return IDENTITY;
        }
        return new Parity(a.length > 0 && a[0], a.length > 1 && a[1]);
    }
}
