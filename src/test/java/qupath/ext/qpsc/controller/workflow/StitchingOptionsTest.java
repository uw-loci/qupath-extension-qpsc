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
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_PER_CHANNEL, Set.of());
        for (String ch : List.of("DAPI", "FITC", "TRITC")) {
            assertTrue(o.isSplit(ch), ch + " must split under OME_PER_CHANNEL");
        }
    }

    @Test
    public void explicitSetSplitsOnlyListedChannels() {
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of("FITC"));
        assertFalse(o.isSplit("DAPI"));
        assertTrue(o.isSplit("FITC"));
        assertFalse(o.isSplit("TRITC"));
    }

    @Test
    public void nullsAreNormalized() {
        StitchingOptions o = new StitchingOptions(null, null);
        assertEquals(OutputFormat.OME_SINGLE, o.organization());
        assertNotNull(o.splitChannelIds());
        assertTrue(o.splitChannelIds().isEmpty());
    }

    /** The partition that stitchChannelDirectories performs, expressed directly over isSplit. */
    @Test
    public void partitionMatchesExpectedGrouping() {
        List<String> channels = List.of("DAPI", "FITC", "TRITC");
        StitchingOptions o = new StitchingOptions(OutputFormat.OME_SINGLE, Set.of("TRITC"));

        List<String> merged = channels.stream().filter(c -> !o.isSplit(c)).toList();
        List<String> split = channels.stream().filter(o::isSplit).toList();

        assertEquals(List.of("DAPI", "FITC"), merged, "unsplit channels merge together");
        assertEquals(List.of("TRITC"), split, "split channel stands alone");
    }
}
