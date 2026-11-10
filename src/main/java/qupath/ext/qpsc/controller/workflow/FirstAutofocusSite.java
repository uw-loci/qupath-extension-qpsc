package qupath.ext.qpsc.controller.workflow;

import java.util.List;

/**
 * Where the server runs a region's first autofocus, in the slide image's full-resolution pixels.
 *
 * <p>A Z hint is applied at that tile, so a focus prediction is evaluated there rather than at the
 * annotation's centroid: on a slide tilted 6 um/mm, a corner tile 10 mm from the centroid sits about
 * 60 um away in Z. The rule mirrors the server's {@code AutofocusUtils.get_autofocus_positions}: the
 * tissue-scored tile QPSC sends wins; otherwise a grid of nine tiles or fewer starts at its first
 * tile, and a larger one at the tile nearest one diagonal field of view in from the first tile toward
 * the grid's center.
 */
public final class FirstAutofocusSite {

    private FirstAutofocusSite() {}

    /** One tile: its acquisition index and center in full-resolution pixels. */
    public record Site(int index, double x, double y) {}

    /**
     * @param sites the region's tiles in acquisition order (the order of TileConfiguration_QP.txt)
     * @param preferredIndex the tissue-scored tile sent as {@code --preferred-af-tile}, or a
     *     negative value when none was sent
     * @param frameWidthPx camera frame width in the same pixels
     * @param frameHeightPx camera frame height in the same pixels
     * @return the site, or null when there are no sites
     */
    public static Site of(List<Site> sites, int preferredIndex, double frameWidthPx, double frameHeightPx) {
        if (sites == null || sites.isEmpty()) {
            return null;
        }
        if (preferredIndex >= 0) {
            for (Site s : sites) {
                if (s.index() == preferredIndex) {
                    return s;
                }
            }
        }
        Site first = sites.get(0);
        if (sites.size() <= 9) {
            return first;
        }
        double cx = 0;
        double cy = 0;
        for (Site s : sites) {
            cx += s.x();
            cy += s.y();
        }
        cx /= sites.size();
        cy /= sites.size();
        double dx = cx - first.x();
        double dy = cy - first.y();
        double len = Math.hypot(dx, dy);
        if (!(len > 0)) {
            return first;
        }
        double diagonal = Math.hypot(frameWidthPx, frameHeightPx);
        double tx = first.x() + dx / len * diagonal;
        double ty = first.y() + dy / len * diagonal;
        Site nearest = first;
        double best = Double.POSITIVE_INFINITY;
        for (Site s : sites) {
            double d = Math.hypot(s.x() - tx, s.y() - ty);
            if (d < best) {
                best = d;
                nearest = s;
            }
        }
        return nearest;
    }
}
