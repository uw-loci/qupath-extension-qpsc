package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StitchMemoryCheckTest {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;

    /** The user's run: 5 states + retardance, orientation, orientation_color (RGB), pool of 4. */
    private static StitchMemoryCheck.Plan userRun(boolean derivedConcurrent) {
        return new StitchMemoryCheck.Plan(
                5, 3, 1, 4, true, derivedConcurrent, true, 2048, 2048, 1810, 16, 101864, 87085);
    }

    private static long s() {
        return StitchMemoryCheck.estimate(userRun(true)).stitchBytes();
    }

    private static long rgb() {
        return StitchMemoryCheck.estimate(userRun(true)).rgbStitchBytes();
    }

    private static StitchMemoryCheck.Detected detected(long max, long committed, Long free) {
        return new StitchMemoryCheck.Detected(
                max,
                committed / 2,
                committed,
                free == null ? OptionalLong.empty() : OptionalLong.of(64 * GB),
                free == null ? OptionalLong.empty() : OptionalLong.of(free),
                free == null ? "test: unknown" : "test");
    }

    @Test
    void termsComeFromTheMeasuredConstants() {
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(userRun(true));
        long pixels = 101864L * 87085L;
        assertEquals(47 * MB + Math.round(0.0095 * pixels), e.stitchBytes());
        assertEquals(155 * MB, e.rgbStitchBytes());
        assertEquals(26 * MB + Math.round(0.0104 * pixels), e.mergeBytes());
        assertEquals(145 * MB, e.solveBytes());
        // The user's run: 127 MB per grayscale stitch, 114 MB for the merge; four stitches at once
        // (three grayscale, the RGB one at worst) are the peak, 537 MB.
        assertEquals(127, e.stitchBytes() / MB);
        assertEquals(113, e.mergeBytes() / MB);
        assertEquals(537, e.peakBytes() / MB);
        assertEquals("4 stitches at once", e.peakPhase());
    }

    @Test
    void concurrentPeakIsTheLargestPhase() {
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(userRun(true));
        long siblings = 3 * s() + rgb(); // four slots, the RGB derived among them at worst
        long merge = e.mergeBytes() + 2 * s() + rgb(); // merge beside all three derived outputs
        long expected = Math.max(e.solveBytes(), Math.max(siblings, merge));
        assertEquals(expected, e.peakBytes());
    }

    @Test
    void offRunsTheMergeAloneAndDerivedOutputsAfterIt() {
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(userRun(false));
        long siblings = 4 * s();
        long after = 2 * s() + rgb();
        long expected = Math.max(e.solveBytes(), Math.max(siblings, Math.max(e.mergeBytes(), after)));
        assertEquals(expected, e.peakBytes());
        assertTrue(e.peakBytes() <= StitchMemoryCheck.estimate(userRun(true)).peakBytes(), "off never needs more");
    }

    @Test
    void noMergeNoSolveNoDerived() {
        StitchMemoryCheck.Plan p =
                new StitchMemoryCheck.Plan(2, 0, 0, 4, false, true, false, 2048, 2048, 100, 16, 20000, 20000);
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(p);
        assertEquals(0, e.mergeBytes());
        assertEquals(0, e.solveBytes());
        assertEquals(2 * e.stitchBytes(), e.peakBytes());
        assertEquals(2, e.stitchesAtOnce());
    }

    @Test
    void warnsPastEightyPercentOfTheMaximumHeap() {
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(userRun(true));
        long peak = e.peakBytes();
        // Just above and just below 80 % of the heap, physical RAM unknown.
        assertTrue(StitchMemoryCheck.shouldWarn(e, detected((long) (peak / 0.8) - MB, MB, null)));
        assertFalse(StitchMemoryCheck.shouldWarn(e, detected((long) (peak / 0.8) + MB, MB, null)));
    }

    @Test
    void warnsWhenTheHeapWouldHaveToGrowIntoRamOthersUse() {
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(userRun(true));
        long peak = e.peakBytes();
        long committed = peak / 4;
        assertTrue(StitchMemoryCheck.shouldWarn(e, detected(32 * GB, committed, peak - committed - MB)));
        assertFalse(StitchMemoryCheck.shouldWarn(e, detected(32 * GB, committed, peak - committed + MB)));
    }

    @Test
    void unknownPhysicalRamNeverWarnsOnItsOwn() {
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(userRun(true));
        assertFalse(StitchMemoryCheck.shouldWarn(e, detected(32 * GB, MB, null)));
    }

    @Test
    void warningTextNamesTheEstimateTheDetectedValuesAndThePreferences() {
        StitchMemoryCheck.Plan p = userRun(true);
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(p);
        StitchMemoryCheck.Detected d = new StitchMemoryCheck.Detected(
                512 * MB, 300 * MB, 400 * MB, OptionalLong.of(64 * GB), OptionalLong.of(3 * GB), "test meter");
        String text = StitchMemoryCheck.message(p, e, d);
        assertTrue(text.contains("Estimated memory for this stitch job: " + StitchMemoryCheck.gb(e.peakBytes())), text);
        assertTrue(text.contains("estimate"), text);
        assertTrue(text.contains("QuPath maximum heap 512 MB"), text);
        assertTrue(text.contains("heap in use now 300 MB"), text);
        assertTrue(text.contains("heap committed 400 MB"), text);
        assertTrue(text.contains("Physical RAM (test meter): 64.0 GB total, 3.0 GB available now"), text);
        assertTrue(text.contains("1810 tiles of 2048 x 2048 px, 16-bit, a 101864 x 87085 px mosaic"), text);
        assertTrue(text.contains("\"Stitch derived outputs concurrently\""), text);
        assertTrue(text.contains("\"Stitching concurrency\""), text);
        assertTrue(text.chars().allMatch(c -> c < 128), "ASCII only");
        String line = StitchMemoryCheck.shortLine(e, d);
        assertTrue(line.contains("estimated " + StitchMemoryCheck.gb(e.peakBytes())), line);
        assertTrue(line.contains("QuPath heap max 512 MB"), line);
        assertTrue(line.contains("RAM available 3.0 GB"), line);
    }

    @Test
    void warningTextSaysWhenPhysicalRamIsUnknown() {
        StitchMemoryCheck.Plan p = userRun(false);
        StitchMemoryCheck.Estimate e = StitchMemoryCheck.estimate(p);
        StitchMemoryCheck.Detected d =
                new StitchMemoryCheck.Detected(2 * GB, GB, GB, OptionalLong.empty(), OptionalLong.empty(), "no meter");
        String text = StitchMemoryCheck.message(p, e, d);
        assertTrue(text.contains("Physical RAM: could not be read (no meter)"), text);
        assertTrue(text.contains("derived outputs after the merge"), text);
        assertFalse(StitchMemoryCheck.shortLine(e, d).contains("RAM available"));
    }

    @Test
    void detectReadsTheHeap() {
        StitchMemoryCheck.Detected d = StitchMemoryCheck.detect();
        assertEquals(Runtime.getRuntime().maxMemory(), d.maxHeap());
        assertTrue(d.committedHeap() > 0 && d.usedHeap() > 0);
        assertEquals(d.physicalTotal().isPresent(), d.physicalFree().isPresent());
        assertNotNull(d.physicalSource());
    }

    @Test
    void planIsReadFromTheTileFolders(@TempDir Path dir) throws Exception {
        writeFolder(dir.resolve("State0"), BufferedImage.TYPE_USHORT_GRAY);
        writeFolder(dir.resolve("retardance"), BufferedImage.TYPE_USHORT_GRAY);
        writeFolder(dir.resolve("orientation_color"), BufferedImage.TYPE_INT_RGB);
        StitchMemoryCheck.Plan p = StitchMemoryCheck.Plan.read(
                dir, List.of("State0"), List.of("retardance", "orientation_color"), 4, false, true, true);
        assertNotNull(p);
        assertEquals(64, p.tileWidth());
        assertEquals(48, p.tileHeight());
        assertEquals(16, p.bitsPerSample());
        assertEquals(6, p.tileCount());
        assertEquals(1, p.derivedRgb());
        assertEquals(2 * 60 + 64, p.outputWidth());
        assertEquals(40 + 48, p.outputHeight());
    }

    @Test
    void unreadableFolderMeansNoPlan(@TempDir Path dir) {
        assertNull(StitchMemoryCheck.Plan.read(dir, List.of("missing"), List.of(), 4, true, true, true));
        assertNull(StitchMemoryCheck.Plan.read(dir, List.of(), List.of(), 4, true, true, true));
    }

    private static void writeFolder(Path folder, int type) throws Exception {
        Files.createDirectories(folder);
        StringBuilder cfg = new StringBuilder("dim = 2\n");
        int i = 0;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                String name = "tile_" + i++ + ".tif";
                ImageIO.write(
                        new BufferedImage(64, 48, type),
                        "tif",
                        folder.resolve(name).toFile());
                cfg.append(name)
                        .append("; ; (")
                        .append(col * 60)
                        .append(".0, ")
                        .append(row * 40)
                        .append(".0)\n");
            }
        }
        Files.writeString(folder.resolve("TileConfiguration.txt"), cfg.toString());
    }
}
