package qupath.ext.qpsc.display.orientation;

/**
 * Colour maps for axial orientation images: hue = orientation over 0..180 deg, brightness =
 * magnitude / ceiling. A Java port of the microscope server's
 * {@code modality/orientation_colormap.py}; the two must give the same colours, and
 * {@code OrientationColormapTest} checks this class against the server's reference values.
 * Constants and rationale: claude-reports/design/2026-10-22_orientation-colormaps.md.
 */
public final class OrientationColormap {

    /** The three maps, by their config names. */
    public enum Kind {
        UNIFORM("uniform", "Uniform lightness"),
        PPM("ppm", "PPM colours"),
        HSV("hsv", "HSV rainbow (OpenPolScope)");

        private final String configName;
        private final String label;

        Kind(String configName, String label) {
            this.configName = configName;
            this.label = label;
        }

        public String configName() {
            return configName;
        }

        @Override
        public String toString() {
            return label;
        }

        /** The kind for a config name, or {@code UNIFORM} for null or an unknown name. */
        public static Kind fromConfigName(String name) {
            for (Kind k : values()) {
                if (k.configName.equals(name)) {
                    return k;
                }
            }
            return UNIFORM;
        }
    }

    /**
     * The PPM map's hue line: raw PPM hue at 90 deg and the direction it runs (+1 or -1 per
     * 180 deg), from a PPM sunburst calibration with its slope forced to one cycle per 180 deg.
     */
    public record PpmHue(String source, double hueAt90, int direction) {

        /**
         * From the calibration's {@code slope}, {@code intercept} and {@code hue_offset}
         * ({@code hue = slope * angle + intercept + hue_offset, mod 1}).
         *
         * @throws IllegalArgumentException if the calibration does not cover about one hue
         *     cycle per 180 deg (0.8 to 1.2), which means the wrong file
         */
        public static PpmHue fromCalibration(String source, double slope, double intercept, double hueOffset) {
            double span = Math.abs(slope) * 180.0;
            if (span < 0.8 || span > 1.2) {
                throw new IllegalArgumentException(String.format(
                        "The PPM calibration covers %.2f of the hue circle per 180 deg; one is expected", span));
            }
            double h90 = mod(slope * 90.0 + intercept + hueOffset, 1.0);
            return new PpmHue(source, h90, slope > 0 ? 1 : -1);
        }
    }

    static final double UNIFORM_L = 0.75;
    static final double UNIFORM_C = 0.125;
    static final double UNIFORM_HUE0_DEG = 29.2;
    static final int UNIFORM_LUT_SIZE = 1000;

    private static final double[][] M1 = {
        {1.0, 0.3963377774, 0.2158037573},
        {1.0, -0.1055613458, -0.0638541728},
        {1.0, -0.0894841775, -1.2914855480},
    };
    private static final double[][] M2 = {
        {4.0767416621, -3.3077115913, 0.2309699292},
        {-1.2684380046, 2.6097574011, -0.3413193965},
        {-0.0041960863, -0.7034186147, 1.7076147010},
    };

    private static final double[][] UNIFORM_LUT = buildUniformLut();

    private OrientationColormap() {}

    /**
     * Packed RGB ({@code 0xRRGGBB}) for one pixel.
     *
     * @param kind the map
     * @param ppm the PPM hue line; required for {@link Kind#PPM}, ignored otherwise
     * @param sign registration: +1, or -1 to mirror the angle
     * @param offsetDeg registration: degrees added after the sign
     * @param angleDeg the measured orientation in degrees
     * @param value brightness 0..1 (magnitude / ceiling, clipped by the caller or here)
     */
    public static int rgb(Kind kind, PpmHue ppm, int sign, double offsetDeg, double angleDeg, double value) {
        if (!Double.isFinite(angleDeg) || !Double.isFinite(value)) {
            return 0;
        }
        double deg = mod(sign * angleDeg + offsetDeg, 180.0);
        double v = Math.max(0.0, Math.min(1.0, value));
        double[] full;
        switch (kind) {
            case UNIFORM -> {
                int idx = (int) (Math.rint(deg * (UNIFORM_LUT_SIZE / 180.0)) % UNIFORM_LUT_SIZE);
                full = UNIFORM_LUT[idx];
            }
            case PPM -> {
                if (ppm == null) {
                    throw new IllegalArgumentException("The PPM map needs a PPM hue calibration");
                }
                full = hsvFull(mod(ppm.hueAt90() + ppm.direction() * (deg - 90.0) / 180.0, 1.0));
            }
            default -> full = hsvFull(deg / 180.0);
        }
        int r = (int) Math.rint(full[0] * v * 255.0);
        int g = (int) Math.rint(full[1] * v * 255.0);
        int b = (int) Math.rint(full[2] * v * 255.0);
        return (r << 16) | (g << 8) | b;
    }

    /** OKLCH to gamma-encoded sRGB in [0, 1]; out-of-gamut values clipped. */
    static double[] oklchToSrgb(double lightness, double chroma, double hueDeg) {
        double h = Math.toRadians(hueDeg);
        double[] lab = {lightness, chroma * Math.cos(h), chroma * Math.sin(h)};
        double[] lms = new double[3];
        for (int i = 0; i < 3; i++) {
            double s = M1[i][0] * lab[0] + M1[i][1] * lab[1] + M1[i][2] * lab[2];
            lms[i] = s * s * s;
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double lin = M2[i][0] * lms[0] + M2[i][1] * lms[1] + M2[i][2] * lms[2];
            lin = Math.max(0.0, Math.min(1.0, lin));
            out[i] = lin <= 0.0031308 ? 12.92 * lin : 1.055 * Math.pow(lin, 1.0 / 2.4) - 0.055;
        }
        return out;
    }

    private static double[][] buildUniformLut() {
        double[][] lut = new double[UNIFORM_LUT_SIZE][];
        for (int i = 0; i < UNIFORM_LUT_SIZE; i++) {
            double angle = i * (180.0 / UNIFORM_LUT_SIZE);
            lut[i] = oklchToSrgb(UNIFORM_L, UNIFORM_C, UNIFORM_HUE0_DEG + 2.0 * angle);
        }
        return lut;
    }

    private static double[] hsvFull(double hue) {
        double h6 = hue * 6.0;
        int sector = ((int) Math.floor(h6) % 6 + 6) % 6;
        double f = h6 - Math.floor(h6);
        return switch (sector) {
            case 0 -> new double[] {1, f, 0};
            case 1 -> new double[] {1 - f, 1, 0};
            case 2 -> new double[] {0, 1, f};
            case 3 -> new double[] {0, 1 - f, 1};
            case 4 -> new double[] {f, 0, 1};
            default -> new double[] {1, 0, 1 - f};
        };
    }

    private static double mod(double x, double m) {
        double r = x % m;
        return r < 0 ? r + m : r;
    }
}
