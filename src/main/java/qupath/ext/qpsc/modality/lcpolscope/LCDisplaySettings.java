package qupath.ext.qpsc.modality.lcpolscope;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * The LC-PolScope orientation color image settings: which color map the server uses for the
 * display-only orientation color image of the next runs, and the retardance shown at full
 * brightness (the display ceiling). Both are remembered across sessions and edited in
 * {@code LCColormapPane}. "As in the config" sends nothing, so
 * {@code reconstruction.display_colormap} applies; a ceiling left at the config's value sends
 * nothing either. {@link LCPolScopeModalityHandler#configureCommandBuilder} sends them as
 * {@code --display-colormap} and {@code --display-ceiling-nm}. The ceiling also sets the display
 * range QPSC gives a stitched retardance image and the starting ceiling of the Orientation Color
 * View. Maps: claude-reports/design/2026-10-22_orientation-colormaps.md.
 */
public final class LCDisplaySettings {

    private static final StringProperty CHOICE = PathPrefs.createPersistentPreference("lcpolscope.displayColormap", "");

    /** The chosen ceiling in nm; 0 (or anything not above 0) means the config's own value. */
    private static final DoubleProperty CEILING =
            PathPrefs.createPersistentPreference("lcpolscope.displayCeilingNm", 0.0);

    public static final double FALLBACK_CEILING_NM = 20.0;
    public static final double MIN_CEILING_NM = 0.5;
    public static final double MAX_CEILING_NM = 300.0;

    private LCDisplaySettings() {}

    /** The saved color map choice as stored: "" for "as in the config". */
    public static String savedChoice() {
        return CHOICE.get();
    }

    public static void setChoice(String value) {
        CHOICE.set(value);
    }

    /** Saves a ceiling in nm; 0 returns to the config's value. */
    public static void setCeilingNm(double nm) {
        CEILING.set(nm);
    }

    /**
     * The ceiling to send with the next acquisition as {@code --display-ceiling-nm}, or null to
     * send nothing: when it was never changed from the config's value.
     */
    public static Double sendableCeilingNm() {
        return sendableCeiling(CEILING.get(), configuredCeilingNm(MicroscopeConfigManager.current()));
    }

    /**
     * The display ceiling in force: the saved value when one was set, else the config's
     * {@code display_retardance_ceiling_nm}, else 20 nm.
     */
    public static double effectiveCeilingNm() {
        double saved = CEILING.get();
        return saved > 0 && Double.isFinite(saved) ? saved : configuredCeilingNm(MicroscopeConfigManager.current());
    }

    /** The saved ceiling if it is a usable number that differs from the config's; else null. */
    static Double sendableCeiling(double saved, double configured) {
        if (!(saved > 0) || !Double.isFinite(saved)) {
            return null;
        }
        return Math.abs(saved - configured) < 1e-9 ? null : saved;
    }

    /** {@code reconstruction.display_retardance_ceiling_nm}, else 20 nm. */
    public static double configuredCeilingNm(MicroscopeConfigManager mgr) {
        try {
            Double c = mgr == null
                    ? null
                    : mgr.getDouble("modalities", "lcpolscope", "reconstruction", "display_retardance_ceiling_nm");
            if (c != null && c > 0 && Double.isFinite(c)) {
                return c;
            }
        } catch (RuntimeException e) {
            // an unreadable config falls back below
        }
        return FALLBACK_CEILING_NM;
    }

    /**
     * The color map to send with the next acquisition, or null to send nothing (the config's
     * own setting applies).
     */
    public static String selectedColormap() {
        return effectiveChoice(CHOICE.get(), ppmCalibrationSource(MicroscopeConfigManager.current()) != null);
    }

    /**
     * The saved choice as it can be sent: null for "as in the config", an unknown value, or "ppm"
     * on a config without a PPM hue calibration (the server would refuse the whole reconstruction).
     */
    public static String effectiveChoice(String saved, boolean hasPpmCalibration) {
        if (saved == null) {
            return null;
        }
        return switch (saved) {
            case "uniform", "hsv" -> saved;
            case "ppm" -> hasPpmCalibration ? saved : null;
            default -> null;
        };
    }

    /** {@code reconstruction.display_colormap}, else "uniform". */
    public static String configuredColormap(MicroscopeConfigManager mgr) {
        if (mgr != null) {
            Object value = mgr.getConfigItem("modalities", "lcpolscope", "reconstruction", "display_colormap");
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "uniform";
    }

    /** The source named by the PPM hue calibration, or null when the config has none. */
    public static String ppmCalibrationSource(MicroscopeConfigManager mgr) {
        if (mgr == null
                || mgr.getDouble("modalities", "lcpolscope", "reconstruction", "display_hue_calibration", "slope")
                        == null) {
            return null;
        }
        Object source =
                mgr.getConfigItem("modalities", "lcpolscope", "reconstruction", "display_hue_calibration", "source");
        return source == null ? "calibration" : source.toString();
    }
}
