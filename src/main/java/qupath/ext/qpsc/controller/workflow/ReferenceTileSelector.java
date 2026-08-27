package qupath.ext.qpsc.controller.workflow;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Picks the reference tiles that multi-tile refinement aligns against, without asking an
 * operator to click them.
 *
 * <p>A human picking tiles by hand applies two criteria without thinking about them: pick
 * somewhere the camera will actually land on tissue, and pick spots far apart so the fit is
 * well conditioned. This reproduces both.
 *
 * <h2>The three filters, in order</h2>
 * <ol>
 *   <li><b>Interior</b> -- keep a tile only if its full ring of 8 grid neighbours exists and
 *       its own centroid is inside the tissue annotation. Tiling deliberately keeps edge tiles
 *       that merely <i>intersect</i> the annotation ({@code TilingUtilities}: {@code contains ||
 *       intersects}), so an un-filtered pick can put the camera half on background. Requiring
 *       the whole ring is a cheap proxy for "the camera will be on tissue even if the stage
 *       lands a little off".</li>
 *   <li><b>Texture</b> -- rank the interior tiles by gradient standard deviation over the tile
 *       region. SIFT needs structure; a uniform patch of tissue matches nothing. Gradient-std
 *       is chosen to match the metric the focus server already uses for its tissue/background
 *       decision, so Java and Python agree about what "has content" means.</li>
 *   <li><b>Spread</b> -- from the top-scoring pool, greedily take the tile farthest from those
 *       already chosen. Three clustered tiles fit a rotation about as badly as one; the fit's
 *       conditioning depends on their separation.</li>
 * </ol>
 *
 * <h2>Why K = 3</h2>
 * The similarity solve needs 2. The third is a spare: if one tile's SIFT capture fails there
 * is still a valid 2-point solve, without a round trip to the operator.
 *
 * <h2>Grouping</h2>
 * {@code Row}/{@code Column} restart at 0 for every annotation, so the neighbour test is run
 * per annotation. Tiles are grouped by the annotation-name suffix that
 * {@code TilingUtilities} bakes into the tile name ({@code "<index>_<annotationName>"}), which
 * needs no hierarchy and survives being read back from a saved project.
 *
 * <p>This class is pure computation -- no dialogs, no stage motion, no FX thread requirement.
 */
public final class ReferenceTileSelector {

    private static final Logger logger = LoggerFactory.getLogger(ReferenceTileSelector.class);

    /** Reference tiles to pick: 2 for the similarity solve, plus one spare. */
    public static final int DEFAULT_TILE_COUNT = 3;

    /**
     * How many top-scoring tiles enter the spread step, as a multiple of K (with a floor).
     * Too small and the spread step has nothing to choose between; too large and it starts
     * preferring far-apart-but-featureless tiles over textured ones.
     */
    private static final int POOL_MULTIPLIER = 5;

    private static final int POOL_FLOOR = 10;

    /** Long edge, in pixels, that a tile region is downsampled to before scoring. */
    private static final int SCORE_TARGET_PX = 256;

    /**
     * Maximum tiles whose texture is actually measured. Scoring reads an image region per
     * tile, so this bounds the cost of a selection on a slide with thousands of tiles.
     */
    private static final int SCORE_BUDGET = 60;

    private ReferenceTileSelector() {}

    // ---- automatic-batch entry point -------------------------------------------

    /**
     * True when an automatic batch is running and has not been handed back to the operator for
     * the current slide -- i.e. when {@link #autoPickIfArmed} would attempt a pick.
     *
     * <p>Exists so a caller on the FX thread can decide whether to move the (image-reading)
     * selection onto a worker thread without doing the selection itself to find out.
     */
    public static boolean wouldAutoPick() {
        return qupath.ext.qpsc.ui.AutoAdvanceController.isArmed()
                && !qupath.ext.qpsc.ui.AutoAdvanceController.isOverriddenThisSlide();
    }

    /**
     * Reference tiles for the slide currently open in {@code gui}, or an empty list when the
     * operator should pick by hand.
     *
     * <p>This is the single gate every "pick a tile" site consults during a multi-slide batch.
     * It returns empty -- meaning "show the dialog" -- in three cases, all of which are the
     * operator being asked rather than a bad tile being chosen silently: the batch is not in an
     * automatic mode, the operator has taken over this slide, or nothing survives the interior
     * filter (tissue thinner than three tiles, or tiles with no {@code Row}/{@code Column}
     * measurements).
     *
     * @param gui         QuPath GUI; a null GUI or no open image returns empty
     * @param annotations tissue annotations to test containment against; when null the open
     *                    image's annotation objects are used, so callers that never received an
     *                    annotation list still get the containment test
     * @param k           how many tiles to pick
     * @return chosen tiles, or empty to fall back to the selection dialog
     */
    public static List<PathObject> autoPickIfArmed(QuPathGUI gui, List<PathObject> annotations, int k) {
        if (!wouldAutoPick()) {
            return List.of();
        }
        if (gui == null || gui.getImageData() == null) {
            return List.of();
        }
        var hierarchy = gui.getImageData().getHierarchy();
        List<PathObject> tiles = hierarchy.getDetectionObjects().stream()
                .filter(o -> o.getMeasurements().containsKey("TileNumber"))
                .toList();
        Collection<PathObject> tissue = (annotations != null) ? annotations : hierarchy.getAnnotationObjects();
        Map<String, ROI> tissueByName = new HashMap<>();
        for (PathObject a : tissue) {
            if (a.getName() != null && a.getROI() != null) {
                tissueByName.put(a.getName(), a.getROI());
            }
        }
        // Tiles are matched to their annotation by NAME (the suffix TilingUtilities bakes into
        // the tile name), so an unnamed annotation contributes nothing here and its tiles get
        // no containment test -- silently leaving only the neighbour-ring filter. That is still
        // a real filter, but it is half the protection, and the difference shows up as a
        // reference tile sitting on the tissue boundary. Say so rather than degrade quietly.
        if (tissueByName.isEmpty() && !tissue.isEmpty()) {
            logger.warn(
                    "Reference tile selection: {} annotation(s) but none carry a name, so tiles cannot be matched "
                            + "to them; falling back to the neighbour-ring filter alone",
                    tissue.size());
        }
        List<PathObject> chosen = select(tiles, tissueByName, gui.getImageData().getServer(), k);
        if (chosen.isEmpty()) {
            logger.warn("Automatic reference-tile selection found nothing; falling back to manual picking");
        } else {
            logger.info(
                    "Automatic reference-tile selection picked {} tile(s): {}",
                    chosen.size(),
                    chosen.stream().map(PathObject::getName).toList());
        }
        return chosen;
    }

    // ---- step 1: interior ------------------------------------------------------

    /**
     * Tiles whose complete 8-neighbour ring exists within the same annotation's grid and whose
     * centroid lies inside that annotation's ROI.
     *
     * <p>Tiles missing {@code Row}/{@code Column} measurements cannot be tested and are
     * dropped with a warning rather than being silently treated as interior -- picking a
     * boundary tile is exactly the failure this filter exists to prevent.
     *
     * @param tiles              candidate tile detections (those carrying {@code TileNumber})
     * @param tissueRoiByName    annotation name to its ROI; a name absent from the map skips
     *                           the containment test but still requires the full neighbour ring
     * @return interior tiles, in no particular order
     */
    public static List<PathObject> interiorTiles(Collection<PathObject> tiles, Map<String, ROI> tissueRoiByName) {
        List<PathObject> result = new ArrayList<>();
        if (tiles == null || tiles.isEmpty()) {
            return result;
        }

        // Group by annotation, and index each group's occupied (row, column) cells.
        Map<String, List<PathObject>> byAnnotation = new HashMap<>();
        Map<String, Set<Long>> occupied = new HashMap<>();
        int missingGrid = 0;

        for (PathObject tile : tiles) {
            Integer row = intMeasurement(tile, "Row");
            Integer col = intMeasurement(tile, "Column");
            if (row == null || col == null) {
                missingGrid++;
                continue;
            }
            String key = annotationKey(tile);
            byAnnotation.computeIfAbsent(key, k -> new ArrayList<>()).add(tile);
            occupied.computeIfAbsent(key, k -> new HashSet<>()).add(cell(row, col));
        }
        if (missingGrid > 0) {
            logger.warn(
                    "Reference tile selection: {} tile(s) have no Row/Column measurement and were skipped; "
                            + "re-run tiling if no interior tiles are found",
                    missingGrid);
        }

        for (Map.Entry<String, List<PathObject>> group : byAnnotation.entrySet()) {
            Set<Long> cells = occupied.get(group.getKey());
            ROI tissue = (tissueRoiByName == null) ? null : tissueRoiByName.get(group.getKey());
            for (PathObject tile : group.getValue()) {
                int row = intMeasurement(tile, "Row");
                int col = intMeasurement(tile, "Column");
                if (!hasFullNeighbourRing(cells, row, col)) {
                    continue;
                }
                // A tile with no usable ROI has no position, so it cannot be scored, spread
                // against, or driven to. Dropping it here rather than downstream matters:
                // the spread step scores such a tile -1, and if it were the only candidate
                // left it would be "chosen" as null and NPE at the caller, or -- worse in an
                // automatic batch -- read as "no tile available" and open a dialog nobody is
                // there to answer.
                ROI roi = tile.getROI();
                if (roi == null || roi.isEmpty()) {
                    continue;
                }
                if (tissue != null && !tissue.contains(roi.getCentroidX(), roi.getCentroidY())) {
                    continue;
                }
                result.add(tile);
            }
        }
        return result;
    }

    private static boolean hasFullNeighbourRing(Set<Long> cells, int row, int col) {
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) {
                    continue;
                }
                if (!cells.contains(cell(row + dr, col + dc))) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- steps 2 + 3: texture rank, then spread --------------------------------

    /**
     * Rank by texture, then take a well-separated subset.
     *
     * @param candidates interior tiles from {@link #interiorTiles}
     * @param scorer     texture score; higher is better
     * @param k          how many to return; fewer come back when the tissue is small
     * @return chosen tiles, highest-scoring first, then in the order the spread step added them
     */
    public static List<PathObject> rankAndSpread(
            List<PathObject> candidates, ToDoubleFunction<PathObject> scorer, int k) {
        if (candidates == null || candidates.isEmpty() || k <= 0) {
            return List.of();
        }
        List<PathObject> toScore = boundScoringSet(candidates);
        // Score ONCE per tile into a map, then sort on the map. Sorting with the scorer as the
        // comparator would call it O(n log n) times, and each call is a region read -- on a
        // slide with a thousand tiles that is thousands of image reads to pick three tiles.
        Map<PathObject, Double> scores = new HashMap<>();
        for (PathObject tile : toScore) {
            scores.put(tile, scorer.applyAsDouble(tile));
        }
        List<PathObject> ranked = new ArrayList<>(toScore);
        ranked.sort(Comparator.comparingDouble((PathObject t) -> scores.getOrDefault(t, 0.0))
                .reversed());

        int poolSize = Math.min(ranked.size(), Math.max(POOL_FLOOR, k * POOL_MULTIPLIER));
        List<PathObject> pool = new ArrayList<>(ranked.subList(0, poolSize));

        List<PathObject> chosen = new ArrayList<>();
        chosen.add(pool.remove(0)); // best-scoring tile seeds the set
        while (chosen.size() < k && !pool.isEmpty()) {
            PathObject best = null;
            double bestDistance = -1;
            for (PathObject candidate : pool) {
                double d = minDistanceTo(candidate, chosen);
                if (d > bestDistance) {
                    bestDistance = d;
                    best = candidate;
                }
            }
            chosen.add(best);
            pool.remove(best);
        }
        return chosen;
    }

    /**
     * At most {@value #SCORE_BUDGET} tiles to score, sampled at an even stride across the
     * interior set.
     *
     * <p>Texture scoring reads an image region per tile. A 20x acquisition can have thousands
     * of interior tiles, and reading all of them to choose three would cost more than the
     * alignment it is serving -- long enough to look like a hang. A fixed stride keeps the
     * sampled set spread across the whole tissue (rather than clustered at whichever corner
     * the tiling happened to emit first) and keeps the cost independent of tile count. It is
     * deterministic, so the same slide picks the same tiles on a re-run.
     */
    private static List<PathObject> boundScoringSet(List<PathObject> candidates) {
        if (candidates.size() <= SCORE_BUDGET) {
            return candidates;
        }
        List<PathObject> sampled = new ArrayList<>(SCORE_BUDGET);
        double stride = candidates.size() / (double) SCORE_BUDGET;
        for (int i = 0; i < SCORE_BUDGET; i++) {
            sampled.add(candidates.get((int) (i * stride)));
        }
        logger.info(
                "Reference tile selection: scoring {} of {} interior tiles (even stride) to bound image reads",
                sampled.size(),
                candidates.size());
        return sampled;
    }

    private static double minDistanceTo(PathObject candidate, List<PathObject> chosen) {
        ROI a = candidate.getROI();
        if (a == null) {
            return -1;
        }
        double min = Double.MAX_VALUE;
        for (PathObject other : chosen) {
            ROI b = other.getROI();
            if (b == null) {
                continue;
            }
            double dx = a.getCentroidX() - b.getCentroidX();
            double dy = a.getCentroidY() - b.getCentroidY();
            min = Math.min(min, Math.hypot(dx, dy));
        }
        return (min == Double.MAX_VALUE) ? -1 : min;
    }

    // ---- full pipeline ---------------------------------------------------------

    /**
     * Select reference tiles for one slide.
     *
     * @param tiles           tile detections carrying {@code TileNumber}
     * @param tissueRoiByName annotation name to ROI (see {@link #interiorTiles})
     * @param server          image server used to score texture; when null every tile scores 0
     *                        and selection degrades to interior-plus-spread
     * @param k               how many tiles to return
     * @return chosen tiles, or an empty list when nothing survives the interior filter
     */
    public static List<PathObject> select(
            Collection<PathObject> tiles, Map<String, ROI> tissueRoiByName, ImageServer<BufferedImage> server, int k) {
        List<PathObject> interior = interiorTiles(tiles, tissueRoiByName);
        if (interior.isEmpty()) {
            logger.warn(
                    "Reference tile selection found no interior tiles among {} candidate(s); "
                            + "the tissue may be thinner than 3 tiles across",
                    (tiles == null) ? 0 : tiles.size());
            return List.of();
        }
        ToDoubleFunction<PathObject> scorer = (server == null) ? t -> 0.0 : t -> textureScore(server, t.getROI());
        List<PathObject> chosen = rankAndSpread(interior, scorer, k);
        logger.info(
                "Reference tile selection: {} candidate(s) -> {} interior -> {} chosen",
                (tiles == null) ? 0 : tiles.size(),
                interior.size(),
                chosen.size());
        return chosen;
    }

    // ---- texture ---------------------------------------------------------------

    /**
     * Standard deviation of gradient magnitude over the tile region, downsampled to roughly
     * {@value #SCORE_TARGET_PX} px on its long edge.
     *
     * <p>Standard deviation rather than mean: a uniformly grainy region (noise, or an evenly
     * stained blank) has a high mean gradient but little structure, while real tissue has a
     * wide spread of edge strengths. Returns 0 when the region cannot be read, which ranks the
     * tile last rather than aborting the selection.
     */
    public static double textureScore(ImageServer<BufferedImage> server, ROI roi) {
        if (server == null || roi == null) {
            return 0.0;
        }
        try {
            double longEdge = Math.max(roi.getBoundsWidth(), roi.getBoundsHeight());
            double downsample = Math.max(1.0, longEdge / SCORE_TARGET_PX);
            RegionRequest request = RegionRequest.createInstance(
                    server.getPath(),
                    downsample,
                    (int) Math.round(roi.getBoundsX()),
                    (int) Math.round(roi.getBoundsY()),
                    (int) Math.round(roi.getBoundsWidth()),
                    (int) Math.round(roi.getBoundsHeight()));
            BufferedImage img = server.readRegion(request);
            return gradientStd(img);
        } catch (Exception e) {
            logger.debug("Reference tile selection: could not score tile region ({})", e.getMessage());
            return 0.0;
        }
    }

    /** Standard deviation of forward-difference gradient magnitude over a grayscale view. */
    static double gradientStd(BufferedImage img) {
        if (img == null || img.getWidth() < 2 || img.getHeight() < 2) {
            return 0.0;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        double[] gray = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[y * w + x] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }
        int n = (w - 1) * (h - 1);
        double sum = 0;
        double sumSq = 0;
        for (int y = 0; y < h - 1; y++) {
            for (int x = 0; x < w - 1; x++) {
                int i = y * w + x;
                double dx = gray[i + 1] - gray[i];
                double dy = gray[i + w] - gray[i];
                double mag = Math.hypot(dx, dy);
                sum += mag;
                sumSq += mag * mag;
            }
        }
        double mean = sum / n;
        double variance = Math.max(0.0, (sumSq / n) - (mean * mean));
        return Math.sqrt(variance);
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Annotation grouping key from the tile name that {@code TilingUtilities} writes:
     * {@code "<index>_<annotationName>"}, or just {@code "<index>"} for bounding-box tiling.
     * Annotation names contain underscores themselves ({@code "<x>_<y>"}), so this splits on
     * the FIRST underscore only.
     */
    static String annotationKey(PathObject tile) {
        String name = (tile == null) ? null : tile.getName();
        if (name == null) {
            return "";
        }
        int i = name.indexOf('_');
        return (i < 0) ? "" : name.substring(i + 1);
    }

    private static Integer intMeasurement(PathObject tile, String key) {
        if (tile == null || !tile.getMeasurements().containsKey(key)) {
            return null;
        }
        double v = tile.getMeasurements().get(key).doubleValue();
        return Double.isNaN(v) ? null : (int) Math.round(v);
    }

    /** Pack a (row, column) pair into one long so a HashSet lookup needs no allocation. */
    private static long cell(int row, int col) {
        return (((long) row) << 32) ^ (col & 0xFFFFFFFFL);
    }
}
