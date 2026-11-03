package qupath.ext.qpsc.stitching;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;

/** A stitch finishing must not reset the flip flags under a sibling that has not read them yet. */
class StitcherFlipFlagsTest {

    @Test
    void flagsStayUntilTheLastStitchCloses() {
        StitcherFlipFlags.Hold first = StitcherFlipFlags.hold(new boolean[] {true, false});
        StitcherFlipFlags.Hold second = StitcherFlipFlags.hold(new boolean[] {true, false});
        first.close();
        assertTrue(TileConfigurationTxtStrategy.flipStitchingX, "a sibling still stitching keeps its flips");
        assertFalse(TileConfigurationTxtStrategy.flipStitchingY);
        first.close();
        assertEquals(1, StitcherFlipFlags.holders(), "closing twice releases once");
        second.close();
        assertFalse(TileConfigurationTxtStrategy.flipStitchingX, "reset once nobody stitches");
        assertEquals(0, StitcherFlipFlags.holders());
    }
}
