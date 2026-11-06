package qupath.ext.qpsc.controller.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.stitching.JobEta;
import qupath.ext.qpsc.stitching.StitchProgressBridge;

/**
 * How long stitch targets and whole jobs should take before they start, from their tile folders
 * (tile count, tile size, mosaic size, channels and bit depth, read by
 * {@link StitchMemoryCheck.Grid}) at the speeds tiles-to-pyramid learned on this computer.
 * Every value is NaN when tiles-to-pyramid is too old to predict or a folder cannot be read.
 */
final class StitchTimePrediction {

    private static final Logger logger = LoggerFactory.getLogger(StitchTimePrediction.class);

    /** Overlap the solve's band estimate assumes before it has measured the grid. */
    static final double TYPICAL_OVERLAP = 0.17;

    private StitchTimePrediction() {}

    /** Seconds each target folder's stitch should take (scan + write; no solve). */
    static Map<String, Double> perTarget(Path tileBaseDir, List<String> targets, String compression) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String t : targets) {
            out.put(t, targetSeconds(tileBaseDir.resolve(t), compression));
        }
        return out;
    }

    /** Seconds one folder's stitch should take (scan + write), NaN when unknown. */
    static double targetSeconds(Path folder, String compression) {
        if (!StitchProgressBridge.available()) {
            return Double.NaN;
        }
        try {
            StitchMemoryCheck.Grid g = StitchMemoryCheck.Grid.read(folder);
            int bytesPerPixel = Math.max(1, g.bands()) * Math.max(1, (g.bitsPerSample() + 7) / 8);
            return StitchProgressBridge.predictSeconds(
                    g.tileCount(), 0, (int) g.width(), (int) g.height(), bytesPerPixel, compression);
        } catch (IOException | RuntimeException e) {
            logger.debug("No stitch time prediction for {}: {}", folder, e.toString());
            return Double.NaN;
        }
    }

    /** Seconds a registration solve over one folder's grid should take, NaN when unknown. */
    static double solveSeconds(Path folder, String compression) {
        if (!StitchProgressBridge.available()) {
            return Double.NaN;
        }
        try {
            StitchMemoryCheck.Grid g = StitchMemoryCheck.Grid.read(folder);
            // About two overlap bands per tile (right and lower neighbor).
            long band = (long) (2 * TYPICAL_OVERLAP * g.tileCount() * (long) g.tileWidth() * g.tileHeight());
            // Tile headers plus the bands; the solve scans the reference folder's headers itself.
            return StitchProgressBridge.predictSeconds(g.tileCount(), band, 1, 1, 1, compression);
        } catch (IOException | RuntimeException e) {
            return Double.NaN;
        }
    }

    /**
     * Seconds a whole job in {@code tileBaseDir} should take: the solve (when registration is on)
     * on the first folder, then every folder holding a {@code TileConfiguration.txt} on
     * {@code slots} writers.
     */
    static double jobSeconds(Path tileBaseDir, String compression, int slots, boolean solve) {
        List<Path> folders = new ArrayList<>();
        try (Stream<Path> s = Files.list(tileBaseDir)) {
            s.filter(p -> Files.isRegularFile(p.resolve("TileConfiguration.txt")))
                    .filter(p -> !p.getFileName().toString().startsWith("_temp_"))
                    .sorted()
                    .forEach(folders::add);
        } catch (IOException | RuntimeException e) {
            return Double.NaN;
        }
        if (folders.isEmpty()) {
            return Double.NaN;
        }
        double[] durations = new double[folders.size()];
        for (int i = 0; i < durations.length; i++) {
            durations[i] = targetSeconds(folders.get(i), compression);
        }
        double stitch = JobEta.makespan(new double[0], durations, slots);
        return solve ? solveSeconds(folders.get(0), compression) + stitch : stitch;
    }
}
