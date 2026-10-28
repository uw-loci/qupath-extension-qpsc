package qupath.ext.qpsc.utilities;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The picture a reserved project entry shows until its stitch lands: four interlocking puzzle
 * pieces on a plain ground, with "Stitching..." under them.
 *
 * <p>A reserved entry must point at a file that exists, or QuPath's project browser marks it
 * missing (dimmed thumbnail, warning icon, and a cached "URI missing" state that lasts until
 * the project is reopened). So every reservation points at this one baseline TIFF under the
 * project folder, which Bio-Formats reads like the OME-TIFF that replaces it. Drawn with Java2D
 * at first use; never shipped as a resource, so there is nothing to keep in sync.
 */
public final class StitchPlaceholderImage {

    private static final Logger logger = LoggerFactory.getLogger(StitchPlaceholderImage.class);

    /** Folder under the project directory that holds QPSC's own files. */
    public static final String PROJECT_SUBFOLDER = "qpsc";

    /** File name of the placeholder. */
    public static final String FILE_NAME = "stitching-pending.tif";

    /** Pixel size of the square picture. */
    public static final int SIZE = 512;

    private StitchPlaceholderImage() {}

    /**
     * The placeholder file for a project, written when missing.
     *
     * @param projectDir the project directory (the folder holding project.qpproj)
     * @return the TIFF path
     * @throws IOException when the folder or file cannot be written
     */
    public static Path ensure(Path projectDir) throws IOException {
        Path file = projectDir.resolve(PROJECT_SUBFOLDER).resolve(FILE_NAME);
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            return file;
        }
        Files.createDirectories(file.getParent());
        BufferedImage img = draw();
        if (!ImageIO.write(img, "tiff", file.toFile())) {
            throw new IOException("No TIFF writer available for the stitching placeholder");
        }
        logger.info("Wrote the stitching placeholder picture: {}", file);
        return file;
    }

    /** The picture itself, for tests and for drawing elsewhere. */
    public static BufferedImage draw() {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0xF2, 0xF2, 0xF0));
            g.fillRect(0, 0, SIZE, SIZE);

            // Four pieces in a 2x2 block, each with one tab and one notch so they interlock.
            double unit = SIZE * 0.32;
            double ox = (SIZE - 2 * unit) / 2.0;
            double oy = (SIZE - 2 * unit) / 2.0 - SIZE * 0.04;
            Color[] fills = {
                new Color(0x4C, 0x8B, 0xC9), new Color(0xE0, 0x9A, 0x3C),
                new Color(0x6A, 0xB0, 0x6E), new Color(0xB8, 0x6B, 0xC9)
            };
            for (int i = 0; i < 4; i++) {
                int col = i % 2;
                int row = i / 2;
                double x = ox + col * unit;
                double y = oy + row * unit;
                Path2D.Double piece = piece(x, y, unit, col == 0, row == 0);
                g.setColor(fills[i]);
                g.fill(piece);
                g.setColor(new Color(0x33, 0x33, 0x33));
                g.setStroke(new BasicStroke((float) (unit * 0.035)));
                g.draw(piece);
            }
            g.setColor(new Color(0x44, 0x44, 0x44));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, SIZE / 14));
            String text = "Stitching...";
            int w = g.getFontMetrics().stringWidth(text);
            g.drawString(text, (SIZE - w) / 2, (int) (oy + 2 * unit + SIZE * 0.10));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, SIZE / 26));
            String sub = "This image is being stitched in the background";
            int w2 = g.getFontMetrics().stringWidth(sub);
            g.drawString(sub, (SIZE - w2) / 2, (int) (oy + 2 * unit + SIZE * 0.15));
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * One square piece of side {@code s} at (x, y). The left column has a tab on its right edge,
     * the right column a matching notch on its left edge; the top row a tab on its bottom edge,
     * the bottom row a matching notch on its top edge. Outer edges are straight.
     */
    private static Path2D.Double piece(double x, double y, double s, boolean leftColumn, boolean topRow) {
        double r = s * 0.14; // tab radius
        double n = s * 0.5; // tab centre along the edge
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x, y);
        // top edge (left to right)
        if (topRow) {
            p.lineTo(x + s, y);
        } else {
            p.lineTo(x + n - r, y);
            p.append(new Arc2D.Double(x + n - r, y - r, 2 * r, 2 * r, 180, 180, Arc2D.OPEN), true); // notch down
            p.lineTo(x + s, y);
        }
        // right edge (top to bottom)
        if (leftColumn) {
            p.lineTo(x + s, y + n - r);
            p.append(new Arc2D.Double(x + s - r, y + n - r, 2 * r, 2 * r, 90, -180, Arc2D.OPEN), true); // tab out
            p.lineTo(x + s, y + s);
        } else {
            p.lineTo(x + s, y + s);
        }
        // bottom edge (right to left)
        if (topRow) {
            p.lineTo(x + n + r, y + s);
            p.append(new Arc2D.Double(x + n - r, y + s - r, 2 * r, 2 * r, 0, -180, Arc2D.OPEN), true); // tab down
            p.lineTo(x, y + s);
        } else {
            p.lineTo(x, y + s);
        }
        // left edge (bottom to top)
        if (leftColumn) {
            p.lineTo(x, y);
        } else {
            p.lineTo(x, y + n + r);
            p.append(new Arc2D.Double(x - r, y + n - r, 2 * r, 2 * r, 270, 180, Arc2D.OPEN), true); // notch in
            p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }
}
