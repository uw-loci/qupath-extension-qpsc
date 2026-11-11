package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import qupath.ext.qpsc.geometry.TransformationFunctions;

/**
 * Which tiles of a region sit past the stage's configured travel ({@code stage.limits}).
 *
 * <p>The server refuses to move outside those limits, so a region that reaches past them stops
 * at the first such tile. On 2026-11-10 a region on slot 4 of the quad_v holder reached X
 * -56790 um against a limit of -56723 um; it stopped 741 tiles in, overnight. Checking every
 * tile before the run turns that into a question asked while someone is there.
 */
public final class StageLimitCheck {

    private StageLimitCheck() {}

    /**
     * The tiles of one region past the limits.
     *
     * @param region the region (annotation) name
     * @param tileCount tiles in the region
     * @param outside tiles past a limit
     * @param firstOutside the first such tile in acquisition order, which is where the run stops
     * @param worstAxis "X" or "Y" for the largest overshoot
     * @param worstPositionUm that tile's position on that axis
     * @param worstLimitUm the limit it passes
     */
    public record RegionOverrun(
            String region,
            int tileCount,
            int outside,
            String firstOutside,
            String worstAxis,
            double worstPositionUm,
            double worstLimitUm) {

        /** How far past the limit the worst tile is, um. */
        public double overshootUm() {
            return Math.abs(worstPositionUm - worstLimitUm);
        }

        /** One line for a dialog, the log or a notification. */
        public String describe() {
            return String.format(
                    Locale.ROOT,
                    "Region '%s': %d of %d tiles are past the stage limit, up to %.0f um past %s %.0f um."
                            + " The acquisition would stop at tile %s.",
                    region,
                    outside,
                    tileCount,
                    overshootUm(),
                    worstAxis,
                    worstLimitUm,
                    firstOutside);
        }
    }

    /**
     * @param region the region name
     * @param tiles the region's tile centers in stage um, acquisition order
     * @param limits stage limits in um: x low, x high, y low, y high (either order per axis)
     * @return the overrun, or null when every tile is inside
     */
    public static RegionOverrun check(String region, List<TileConfigurationFile.Entry> tiles, double[] limits) {
        double xLow = Math.min(limits[0], limits[1]);
        double xHigh = Math.max(limits[0], limits[1]);
        double yLow = Math.min(limits[2], limits[3]);
        double yHigh = Math.max(limits[2], limits[3]);
        int outside = 0;
        String first = null;
        String worstAxis = null;
        double worstPos = 0;
        double worstLimit = 0;
        double worst = 0;
        for (TileConfigurationFile.Entry t : tiles) {
            double[][] checks = {
                {t.x(), xLow, xLow - t.x()},
                {t.x(), xHigh, t.x() - xHigh},
                {t.y(), yLow, yLow - t.y()},
                {t.y(), yHigh, t.y() - yHigh}
            };
            boolean past = false;
            for (int i = 0; i < checks.length; i++) {
                double over = checks[i][2];
                if (over > 0) {
                    past = true;
                    if (over > worst) {
                        worst = over;
                        worstAxis = i < 2 ? "X" : "Y";
                        worstPos = checks[i][0];
                        worstLimit = checks[i][1];
                    }
                }
            }
            if (past) {
                outside++;
                if (first == null) {
                    first = t.name();
                }
            }
        }
        if (outside == 0) {
            return null;
        }
        return new RegionOverrun(region, tiles.size(), outside, first, worstAxis, worstPos, worstLimit);
    }

    /** {@link #check} for every region; empty when all fit. */
    public static List<RegionOverrun> checkAll(
            List<String> regions, List<List<TileConfigurationFile.Entry>> tilesPerRegion, double[] limits) {
        List<RegionOverrun> out = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            RegionOverrun r = check(regions.get(i), tilesPerRegion.get(i), limits);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Checks the tile configurations of a run's regions.
     *
     * @param tileDir the run's tile directory (one folder per region)
     * @param regions region (annotation) names
     * @param pixelToStage null to read each region's TileConfiguration.txt as stage um (after
     *     the acquisition transformed it); otherwise TileConfiguration_QP.txt is read in
     *     full-resolution pixels and mapped through this transform (setup, before acquisition)
     * @param limits stage limits, see {@link #check}
     * @return the regions with tiles past the limits; regions without a readable file are skipped
     */
    public static List<RegionOverrun> checkRegionDirs(
            Path tileDir, List<String> regions, AffineTransform pixelToStage, double[] limits) {
        List<RegionOverrun> out = new ArrayList<>();
        for (String region : regions) {
            Path file = tileDir.resolve(region)
                    .resolve(pixelToStage == null ? "TileConfiguration.txt" : "TileConfiguration_QP.txt");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            List<TileConfigurationFile.Entry> tiles;
            try {
                tiles = TileConfigurationFile.entries(Files.readAllLines(file));
            } catch (IOException e) {
                continue;
            }
            if (pixelToStage != null) {
                List<TileConfigurationFile.Entry> stage = new ArrayList<>(tiles.size());
                for (TileConfigurationFile.Entry t : tiles) {
                    double[] xy = TransformationFunctions.transformQuPathFullResToStage(
                            new double[] {t.x(), t.y()}, pixelToStage);
                    stage.add(new TileConfigurationFile.Entry(t.name(), xy[0], xy[1]));
                }
                tiles = stage;
            }
            RegionOverrun r = check(region, tiles, limits);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }
}
