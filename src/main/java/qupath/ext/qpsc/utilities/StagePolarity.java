package qupath.ext.qpsc.utilities;

/**
 * Represents the hardware polarity of the stage axes -- whether MicroManager's
 * {@code setXYPosition} command moves the physical stage in the same direction
 * as the lab frame (sample frame, in our model) or the opposite direction,
 * per axis.
 *
 * <p>This is a hardware property determined by how the stage encoder / device
 * adapter is wired. It is independent of how the camera is mounted or oriented
 * (see {@link CameraOrientation}).
 *
 * <p>To diagnose polarity on a real scope: issue
 * {@code core.setXYPosition(core.getXPosition() + 100, core.getYPosition())}
 * and observe which direction the physical stage actually moves in the lab
 * frame. If MM's "+X" command moves the stage toward lab -X, the X axis is
 * inverted.
 *
 * <p>Stored as an enum (not two booleans) so the UI can't accidentally end
 * up in an undefined state, and so the migration / diagnostic code can
 * reason about the whole-stage polarity as a single value.
 */
public enum StagePolarity {
    /** MM +X -> lab +X, MM +Y -> lab +Y. */
    NORMAL(false, false),
    /** MM +X -> lab -X, MM +Y -> lab +Y. */
    INVERT_X(true, false),
    /** MM +X -> lab +X, MM +Y -> lab -Y. */
    INVERT_Y(false, true),
    /** Both axes inverted. */
    INVERT_XY(true, true);

    public final boolean invertX;
    public final boolean invertY;

    StagePolarity(boolean invertX, boolean invertY) {
        this.invertX = invertX;
        this.invertY = invertY;
    }

    /**
     * Convert a sample-frame delta (um) to the corresponding MM-command delta
     * (um). For an inverted axis, the sign is flipped.
     *
     * @param sampleDx sample-frame X delta in um
     * @param sampleDy sample-frame Y delta in um
     * @return {@code double[2]} containing {@code [mmDx, mmDy]}
     */
    public double[] sampleToMmDelta(double sampleDx, double sampleDy) {
        return new double[] {invertX ? -sampleDx : sampleDx, invertY ? -sampleDy : sampleDy};
    }

    /**
     * Convert an MM-command delta (um) to the corresponding sample-frame
     * delta (um). For axis sign flips the operation is its own inverse.
     */
    public double[] mmToSampleDelta(double mmDx, double mmDy) {
        return sampleToMmDelta(mmDx, mmDy);
    }

    /**
     * Map two legacy independent boolean prefs to a single {@link StagePolarity}
     * value. Provided so existing preferences can be migrated in one place.
     */
    public static StagePolarity fromBooleans(boolean invertX, boolean invertY) {
        if (invertX && invertY) return INVERT_XY;
        if (invertX) return INVERT_X;
        if (invertY) return INVERT_Y;
        return NORMAL;
    }

    /** Config key, under the {@code stage} block of the microscope YAML. */
    public static final String CONFIG_KEY = "polarity";

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(StagePolarity.class);
    private static final java.util.List<Runnable> CHANGE_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile String lastWarnedConfig = null;

    /**
     * The active microscope's stage polarity, {@code stage.polarity} in its config
     * ({@code NORMAL | INVERT_X | INVERT_Y | INVERT_XY}). A property of the stage, so it lives
     * with the stage: it was a QuPath preference, one value per install, which a QuPath that
     * switches between microscopes carried from one to the next (2026-10-27).
     *
     * <p>{@link #NORMAL} when no config is loaded, and when the key is missing or unreadable;
     * the latter is logged once per config, since every tiling, stage-map and live-viewer
     * direction follows this value.
     */
    public static StagePolarity current() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            return NORMAL;
        }
        String raw = mgr.getString("stage", CONFIG_KEY);
        StagePolarity parsed = parse(raw);
        if (parsed == null) {
            String path = mgr.getConfigPath();
            if (path != null && !path.equals(lastWarnedConfig)) {
                lastWarnedConfig = path;
                logger.warn(
                        "stage.{} is {} in {}; using NORMAL. Set it to NORMAL, INVERT_X, INVERT_Y or INVERT_XY "
                                + "(Live Viewer > Calibrate Directions > Manual override writes it).",
                        CONFIG_KEY,
                        raw == null ? "missing" : "'" + raw + "' (not a polarity)",
                        path);
            }
            return NORMAL;
        }
        return parsed;
    }

    /** The polarity named by {@code value} (case and surrounding space ignored), or null. */
    public static StagePolarity parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Writes {@code polarity} to the active microscope's config ({@code stage.polarity}), reloads
     * the config and tells the listeners.
     *
     * @return true when the file changed
     * @throws java.io.IOException when there is no writable config or the write failed
     */
    public static boolean save(StagePolarity polarity) throws java.io.IOException {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null || mgr.getConfigPath() == null) {
            throw new java.io.IOException("no microscope config is loaded");
        }
        ConfigYamlEditor.Result r = ConfigYamlEditor.setTopLevelChildScalar(
                java.nio.file.Path.of(mgr.getConfigPath()), "stage", CONFIG_KEY, polarity.name());
        logger.info("Stage polarity written to {}: {} ({})", mgr.getConfigPath(), polarity, r.message);
        mgr.reload();
        CHANGE_LISTENERS.forEach(Runnable::run);
        return r.changed;
    }

    /** Runs {@code listener} after {@link #save} changes the polarity. Not called on a microscope switch. */
    public static void addChangeListener(Runnable listener) {
        CHANGE_LISTENERS.add(listener);
    }
}
