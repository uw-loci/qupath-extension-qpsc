package qupath.ext.qpsc.display.orientation;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import qupath.ext.qpsc.display.orientation.OrientationColormap.Kind;
import qupath.ext.qpsc.display.orientation.OrientationColormap.PpmHue;
import qupath.lib.gui.images.stores.ImageRenderer;

/**
 * Turns orientation (band 0) and retardance (band 1, optional) tiles into the orientation
 * colour image, for the live overlay. Values are the LC-PolScope stored counts: orientation in
 * deg x 100 (0..18000), retardance in nm x 100. With one band, or with "colour only", every
 * pixel is at full brightness.
 *
 * <p>Changing the settings bumps the timestamp, which makes the overlay drop its cached RGB tiles
 * and recolour them from the raw tiles it already holds; nothing is read again from disk.
 */
public final class OrientationColorRenderer implements ImageRenderer {

    /** LC-PolScope stored counts per degree (orientation) and per nm (retardance). */
    static final double COUNTS_PER_UNIT = 100.0;

    /** Everything the colours depend on. */
    public record Settings(Kind kind, PpmHue ppm, double ceilingNm, boolean colourOnly, int sign, double offsetDeg) {
        public Settings {
            if (kind == Kind.PPM && ppm == null) {
                throw new IllegalArgumentException("The PPM map needs a PPM hue calibration");
            }
            if (!(ceilingNm > 0)) {
                throw new IllegalArgumentException("The retardance ceiling must be above 0 nm");
            }
            if (sign != 1 && sign != -1) {
                throw new IllegalArgumentException("The registration sign must be 1 or -1");
            }
        }

        String key() {
            return String.format(
                    Locale.ROOT,
                    "%s|%s|%.4f|%b|%d|%.4f",
                    kind,
                    ppm == null ? "-" : ppm.toString(),
                    ceilingNm,
                    colourOnly,
                    sign,
                    offsetDeg);
        }
    }

    private static final AtomicLong CLOCK = new AtomicLong(System.currentTimeMillis());

    private volatile Settings settings;
    private volatile long timestamp;

    public OrientationColorRenderer(Settings settings) {
        setSettings(settings);
    }

    public Settings getSettings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = java.util.Objects.requireNonNull(settings);
        this.timestamp = CLOCK.incrementAndGet();
    }

    @Override
    public BufferedImage applyTransforms(BufferedImage imgInput, BufferedImage imgOutput) {
        int w = imgInput.getWidth();
        int h = imgInput.getHeight();
        if (imgOutput == null
                || imgOutput.getWidth() != w
                || imgOutput.getHeight() != h
                || imgOutput.getType() != BufferedImage.TYPE_INT_RGB) {
            imgOutput = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        Settings s = settings;
        Raster raster = imgInput.getRaster();
        boolean hasRetardance = raster.getNumBands() > 1 && !s.colourOnly();
        int[] out = ((DataBufferInt) imgOutput.getRaster().getDataBuffer()).getData();
        double[] ori = raster.getSamples(0, 0, w, h, 0, (double[]) null);
        double[] ret = hasRetardance ? raster.getSamples(0, 0, w, h, 1, (double[]) null) : null;
        double perCeiling = 1.0 / (s.ceilingNm() * COUNTS_PER_UNIT);
        for (int i = 0; i < ori.length; i++) {
            double value = ret == null ? 1.0 : ret[i] * perCeiling;
            out[i] = OrientationColormap.rgb(
                    s.kind(), s.ppm(), s.sign(), s.offsetDeg(), ori[i] / COUNTS_PER_UNIT, value);
        }
        return imgOutput;
    }

    @Override
    public long getLastChangeTimestamp() {
        return timestamp;
    }

    @Override
    public String getUniqueID() {
        return "qpsc-orientation-colour|" + settings.key();
    }
}
