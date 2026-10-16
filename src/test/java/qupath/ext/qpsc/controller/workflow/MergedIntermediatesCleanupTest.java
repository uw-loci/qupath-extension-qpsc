package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.modality.lcpolscope.LCPolScopeModalityHandler;

/**
 * After a channel merge only the per-channel files the merge consumed are removed, with their stitch
 * records; everything else in the shared SlideImages folder stays. The first LC-PolScope stitch
 * (2026-10-16) left all five state pyramids beside the merged one.
 */
class MergedIntermediatesCleanupTest {

    @TempDir
    Path dir;

    private Path file(String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void removesOnlyTheConsumedChannelsAndTheirRecords() throws Exception {
        Path merged = file("S_lcps_20x_r1_1.ome.tif", "merged");
        Path s0 = file("S_lcps_20x_r1_1_State0.ome.tif", "s0");
        Path s1 = file("S_lcps_20x_r1_1_State1.ome.tif", "s1");
        Path s0Record = file("S_lcps_20x_r1_1_State0.stitch-info.txt", "rec");
        Path split = file("S_lcps_20x_r1_1_State4.ome.tif", "kept: split out by the user");
        Path older = file("S_lcps_20x_r0_1_State0.ome.tif", "an earlier run");
        Path retardance = file("S_lcps_20x_r1_1_retardance.ome.tif", "result");

        StitchingHelper.deleteMergedIntermediates(merged.toString(), List.of(s0.toString(), s1.toString()));

        assertFalse(Files.exists(s0));
        assertFalse(Files.exists(s1));
        assertFalse(Files.exists(s0Record), "a record goes with its image");
        for (Path kept : List.of(merged, split, older, retardance)) {
            assertTrue(Files.exists(kept), kept.getFileName() + " must survive");
        }
    }

    @Test
    void removesNothingWhenTheMergedOutputIsMissingOrEmpty() throws Exception {
        Path s0 = file("a_State0.ome.tif", "s0");
        StitchingHelper.deleteMergedIntermediates(dir.resolve("missing.ome.tif").toString(), List.of(s0.toString()));
        assertTrue(Files.exists(s0));

        Path empty = file("empty.ome.tif", "");
        StitchingHelper.deleteMergedIntermediates(empty.toString(), List.of(s0.toString()));
        assertTrue(Files.exists(s0));
    }

    @Test
    void neverDeletesTheMergedFileEvenIfListedAsASource() throws Exception {
        Path merged = file("m.ome.tif", "merged");
        StitchingHelper.deleteMergedIntermediates(merged.toString(), List.of(merged.toString()));
        assertTrue(Files.exists(merged));
    }

    @Test
    void removesAZarrIntermediateAsATree() throws Exception {
        Path merged = file("m.ome.zarr.tif", "merged");
        Path zarr = Files.createDirectories(dir.resolve("a_State0.ome.zarr").resolve("0"));
        Files.writeString(zarr.resolve("chunk"), "x");
        StitchingHelper.deleteMergedIntermediates(
                merged.toString(), List.of(dir.resolve("a_State0.ome.zarr").toString()));
        assertFalse(Files.exists(dir.resolve("a_State0.ome.zarr")));
    }

    @Test
    void lcPolScopeDeclaresItsReconstructedOutputs() {
        LCPolScopeModalityHandler handler = new LCPolScopeModalityHandler();
        assertEquals(List.of("retardance", "orientation"), handler.getPostProcessingDirectorySuffixes());
        assertEquals("Retardance (nm x100)", handler.getChannelNameForOutput("S_lcps_20x_r1_1_retardance.ome.tif"));
        assertEquals("Orientation (deg x100)", handler.getChannelNameForOutput("S_lcps_20x_r1_1_orientation.ome.tif"));
        assertNull(handler.getChannelNameForOutput("S_lcps_20x_r1_1_State0.ome.tif"));
    }
}
