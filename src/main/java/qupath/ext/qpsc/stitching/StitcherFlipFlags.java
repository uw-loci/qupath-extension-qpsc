package qupath.ext.qpsc.stitching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;

/**
 * Sets tiles-to-pyramid's static stitch flip flags for the length of a stitch that several
 * threads may run at once.
 *
 * <p>The flags are global. A stitch that reset them to false when it finished could do so while a
 * sibling on the same pool had set them and not yet read them, and that sibling would then stitch
 * unflipped (and refuse its registration solution, which records the flips). Holders are counted:
 * the flags are set by the first and reset only when the last one closes. Every QPSC caller sets
 * {@code StageImageTransform.current().stitcherFlipFlags()}, so concurrent holders agree.
 */
public final class StitcherFlipFlags {

    private static final Logger logger = LoggerFactory.getLogger(StitcherFlipFlags.class);

    private static final Object LOCK = new Object();
    private static int holders;

    private StitcherFlipFlags() {}

    /** One stitch's hold on the flags; closing it more than once is harmless. */
    public static final class Hold implements AutoCloseable {
        private boolean open = true;

        private Hold() {}

        @Override
        public void close() {
            synchronized (LOCK) {
                if (!open) {
                    return;
                }
                open = false;
                holders--;
                if (holders == 0) {
                    TileConfigurationTxtStrategy.flipStitchingX = false;
                    TileConfigurationTxtStrategy.flipStitchingY = false;
                }
            }
        }
    }

    /**
     * Sets the flags and holds them until the returned hold is closed.
     *
     * @param flags {@code {flipX, flipY}}
     * @return the hold, to close when the stitch has finished
     */
    public static Hold hold(boolean[] flags) {
        synchronized (LOCK) {
            if (holders > 0
                    && (TileConfigurationTxtStrategy.flipStitchingX != flags[0]
                            || TileConfigurationTxtStrategy.flipStitchingY != flags[1])) {
                logger.warn(
                        "Stitch flip flags changed from ({}, {}) to ({}, {}) while {} other stitch(es) run;"
                                + " those may be placed with the new flips",
                        TileConfigurationTxtStrategy.flipStitchingX,
                        TileConfigurationTxtStrategy.flipStitchingY,
                        flags[0],
                        flags[1],
                        holders);
            }
            TileConfigurationTxtStrategy.flipStitchingX = flags[0];
            TileConfigurationTxtStrategy.flipStitchingY = flags[1];
            holders++;
            return new Hold();
        }
    }

    /** Number of stitches holding the flags; for tests. */
    static int holders() {
        synchronized (LOCK) {
            return holders;
        }
    }
}
