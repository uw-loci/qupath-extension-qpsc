package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.controller.workflow.StitchingHelper.StitchingOptions;
import qupath.ext.qpsc.service.OutputFormat;

/**
 * Unit tests for {@link StitchingHelper.StitchingOptions}, the per-acquisition
 * decision of which channels are written as their own file vs merged. The
 * channel-partition loop in {@code stitchChannelDirectories} is driven entirely
 * by {@link StitchingOptions#isSplit(String)}, so verifying that predicate (plus
 * the partition it produces) covers the grouping behavior.
 */
public class StitchingOptionsTest {

    @Test
    public void defaultsMergeEverything() {
        StitchingOptions o = StitchingOptions.defaults();
        assertEquals(OutputFormat.OME_SINGLE, o.organization());
        assertTrue(o.splitChannelIds().isEmpty());
        for (String ch : List.of("DAPI", "FITC", "TRITC")) {
            assertFalse(o.isSplit(ch), ch + " must merge under defaults");
        }
    }

    @Test
    public void perChannelSplitsEveryChannel() {
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_PER_CHANNEL, Set.of(), List.of(), null);
        for (String ch : List.of("DAPI", "FITC", "TRITC")) {
            assertTrue(o.isSplit(ch), ch + " must split under OME_PER_CHANNEL");
        }
    }

    @Test
    public void explicitSetSplitsOnlyListedChannels() {
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of("FITC"), List.of(), null);
        assertFalse(o.isSplit("DAPI"));
        assertTrue(o.isSplit("FITC"));
        assertFalse(o.isSplit("TRITC"));
    }

    @Test
    public void nullsAreNormalized() {
        StitchingOptions o = new StitchingOptions(null, null, List.of(), null);
        assertEquals(OutputFormat.OME_SINGLE, o.organization());
        assertNotNull(o.splitChannelIds());
        assertTrue(o.splitChannelIds().isEmpty());
    }

    // ------------------------------------------------------------ tile alignment

    private static final List<String> ACQUIRED = List.of("DAPI", "FITC", "TRITC");

    @Test
    public void tickedAlignmentChannelsWin() {
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of(), List.of("FITC", "TRITC"), "DAPI");
        assertEquals(List.of("FITC", "TRITC"), o.alignmentFor(ACQUIRED));
    }

    @Test
    public void nothingTickedAlignsOnTheFocusChannel() {
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of(), List.of(), "TRITC");
        assertEquals(List.of("TRITC"), o.alignmentFor(ACQUIRED));
    }

    @Test
    public void noFocusChannelMergesEveryAcquiredChannel() {
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of(), List.of(), null);
        assertEquals(ACQUIRED, o.alignmentFor(ACQUIRED));
    }

    @Test
    public void focusChannelThatWasNotAcquiredFallsBackToTheMerge() {
        // A dedicated focus channel need not be one the run images.
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of(), List.of(), "Cy5");
        assertEquals(ACQUIRED, o.alignmentFor(ACQUIRED));
    }

    @Test
    public void tickedChannelsThatWereNotAcquiredAreDropped() {
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of(), List.of("Cy5", "FITC"), "DAPI");
        assertEquals(List.of("FITC"), o.alignmentFor(ACQUIRED));
        StitchingOptions none = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of(), List.of("Cy5"), "DAPI");
        assertEquals(List.of("DAPI"), none.alignmentFor(ACQUIRED), "no ticked channel acquired -> focus channel");
    }

    /** The partition that stitchChannelDirectories performs, expressed directly over isSplit. */
    @Test
    public void partitionMatchesExpectedGrouping() {
        List<String> channels = List.of("DAPI", "FITC", "TRITC");
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of("TRITC"), List.of(), null);

        List<String> merged = channels.stream().filter(c -> !o.isSplit(c)).toList();
        List<String> split = channels.stream().filter(o::isSplit).toList();

        assertEquals(List.of("DAPI", "FITC"), merged, "unsplit channels merge together");
        assertEquals(List.of("TRITC"), split, "split channel stands alone");
    }
}
