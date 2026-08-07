package qupath.ext.qpsc.utilities;

import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.AffineTransformManager.TransformPreset;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Single, read-only <strong>aggregator</strong> of every orientation / directional factor that
 * stands between a slide on the stage and the pixels QPSC displays and stitches. This is the one
 * place to look when reasoning about an alignment or "which way does it flip" problem on a given
 * microscope: it names each factor, points at the exact variable / config that owns it, and
 * resolves the current value.
 *
 * <p>It is deliberately an aggregator, not a new store: every value is read from its existing
 * authoritative source (preferences, per-detector YAML, the active alignment preset). Nothing here
 * is a second copy that can drift. The companion narrative -- why these are independent, why the
 * composite is correct while the decomposition is incomplete -- lives in
 * {@code documentation/developer/ORIENTATION_STACK.md} and the Stage Map's published visual model.
 *
 * <h2>The orientation stack (light path, slide to pixels)</h2>
 * <ol>
 *   <li><b>Slide placement</b> -- two binary factors that span all four axis-aligned orientations:
 *       <b>(1a) scope type</b> -- upright (face up) vs inverted (face down); and <b>(1b) slide
 *       insertion</b> -- which side the label is on (an in-plane 180). There is no separate
 *       "turn-over axis": flipping a slide over its long vs short axis differs only by that in-plane
 *       180, so it is already captured by insertion. Neither changes the camera view of a given
 *       feature (the coverslip always faces the objective), but together they set how the slide reads
 *       on the stage. Recorded per microscope in the YAML {@code light_path} block
 *       ({@link #scopeType()} / {@link #slideInsertion()}) and reduced to a bench flip by
 *       {@link #currentBenchFlip()}; the Stage Map and setup wizard both derive their Stage View
 *       from it.</li>
 *   <li><b>Optical flip</b> -- objective + tube-lens parity ({@link #opticalFlip()}, YAML
 *       {@code light_path.optical_flip}). This is why a scope needs a distinct Camera View at all
 *       (e.g. PPM's objective inversion = 180/XY); it feeds the Stage Map Camera View, display only.
 *       The per-detector {@code flip_x/flip_y} remains for macro-image resolution.</li>
 *   <li><b>Camera orientation</b> -- the camera's mounting rotation on the port,
 *       {@link CameraOrientation} via {@link QPPreferenceDialog#getCameraOrientationProperty()}.</li>
 *   <li><b>Stage polarity</b> -- which way the stage physically moves for a {@code +X/+Y} command,
 *       {@link StagePolarity} via {@link QPPreferenceDialog#getStagePolarityProperty()}. This is the
 *       <em>net</em> of any MicroManager-level axis inversion (e.g. OWS3 inverts X in MM) plus the
 *       physical wiring: it is detected from observed behaviour, so never add a QPSC flip to
 *       "compensate" for the MM invert -- polarity already contains it.</li>
 * </ol>
 *
 * <p>Plus the per-(source-scanner, target-microscope) <b>macro image flip</b>
 * ({@code flipMacroX/Y} on the active {@link TransformPreset}, resolved by {@link FlipResolver}),
 * which orients the macro overlay image, not the camera-vs-stage relationship.
 *
 * <h2>What is trustworthy</h2>
 * <p>Factors 3 and 4 compose into {@link StageImageTransform} -- its
 * {@link StageImageTransform#stitcherFlipFlags()} (stage+camera composite) drives acquisition and
 * stitching. The per-slide alignment transform, fit to real image-to-stage correspondences,
 * captures the <em>combined</em> effect of all factors and is the empirically correct relationship,
 * which is why acquisition/navigation/stitching are correct regardless of the display factors below.
 *
 * <p>The Stage Map is the surface that reads the decomposition, so it needs factors 1 and 2 named
 * explicitly: <b>Camera View</b> = {@link StageImageTransform#cameraFlipFlags()} XOR
 * {@link #currentOpticalFlip()} (so the map matches the Live Viewer), and <b>Stage View</b> =
 * {@link #currentBenchFlip()} (so it matches the slide on the bench). These are display only and
 * never touch acquisition.
 */
public final class LightPathModel {

    private static final Logger logger = LoggerFactory.getLogger(LightPathModel.class);

    private LightPathModel() {}

    // ------------------------------------------------------------------
    // Factor 1 (slide placement) -- per-microscope YAML light_path block
    // ------------------------------------------------------------------

    /** Top-level YAML block holding the per-microscope slide-placement factors. */
    public static final String BLOCK = "light_path";

    public static final String KEY_SCOPE_TYPE = "scope_type"; // factor 1a
    public static final String KEY_SLIDE_INSERTION = "slide_insertion"; // factor 1b
    public static final String KEY_OPTICAL_FLIP = "optical_flip"; // factor 2: camera-vs-stage optical flip

    public static final String SCOPE_UPRIGHT = "upright";
    public static final String SCOPE_INVERTED = "inverted";
    public static final String INSERT_A = "A";
    public static final String INSERT_B = "B";
    public static final String OPTICAL_NONE = "none";
    public static final String OPTICAL_X = "x";
    public static final String OPTICAL_Y = "y";
    public static final String OPTICAL_XY = "xy";

    /** Scope type from config; defaults to {@link #SCOPE_UPRIGHT} (identity bench flip) when unset. */
    public static String scopeType() {
        return readOr(KEY_SCOPE_TYPE, SCOPE_UPRIGHT);
    }

    /** Default slide insertion from config; defaults to {@link #INSERT_A} (identity) when unset. */
    public static String slideInsertion() {
        return readOr(KEY_SLIDE_INSERTION, INSERT_A);
    }

    /**
     * Factor 2: the optical flip between what the camera sees and how the slide sits on the stage
     * (objective + tube-lens parity). Defaults to {@link #OPTICAL_NONE}. This is the reason a
     * microscope needs a distinct Camera View at all -- on a scope with no optical flip, the camera
     * view and the stage view coincide.
     *
     * <p><b>Display only.</b> This feeds the Stage Map's Camera View so the map matches the Live
     * Viewer; it does NOT touch acquisition/navigation/stitching, which are already correct via the
     * empirically calibrated alignment transform. Because the camera orientation (factor 3) is
     * {@code NORMAL} on the current rigs, this value equals the net camera-vs-stage flip the
     * operator observes (e.g. reversed-in-X on OWS3, XY on PPM) -- set it by matching, do not try to
     * split it from the camera mounting.
     */
    public static String opticalFlip() {
        return readOr(KEY_OPTICAL_FLIP, OPTICAL_NONE);
    }

    /**
     * Reduce an optical-flip token to {flipX, flipY}. {@code none} = identity.
     *
     * @param value {@link #OPTICAL_NONE} / {@link #OPTICAL_X} / {@link #OPTICAL_Y} / {@link #OPTICAL_XY}
     * @return {@code {flipX, flipY}}; never null
     */
    public static boolean[] opticalFlipFlags(String value) {
        if (OPTICAL_X.equalsIgnoreCase(value)) {
            return new boolean[] {true, false};
        }
        if (OPTICAL_Y.equalsIgnoreCase(value)) {
            return new boolean[] {false, true};
        }
        if (OPTICAL_XY.equalsIgnoreCase(value)) {
            return new boolean[] {true, true};
        }
        return new boolean[] {false, false};
    }

    /** The optical flip for the active microscope, read from config (identity default). */
    public static boolean[] currentOpticalFlip() {
        return opticalFlipFlags(opticalFlip());
    }

    private static String readOr(String field, String dflt) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            return dflt;
        }
        String v = mgr.getString(BLOCK, field);
        return (v == null || v.isBlank()) ? dflt : v.trim();
    }

    /**
     * Derive the bench flip -- the {flipX, flipY} that takes the camera-oriented view to how the
     * slide physically sits on the stage -- from the two placement factors. Pure geometry: the
     * scope-face flip (upright = identity; inverted = mirror about the turn-over axis) composed with
     * the insertion (Way B = in-plane 180). Axis-aligned, so it reduces to two booleans.
     *
     * <p>This is the single source of truth shared by the Stage Map's Stage View and the setup
     * wizard, so the two never diverge. {@code upright + A} = identity, matching the historical inert
     * default (Stage View == Camera View).
     *
     * <p>Two binary factors span all four axis-aligned orientations, so there is no separate
     * "turn-over axis": turning a slide over about its long vs short axis differs only by an in-plane
     * 180, which IS the insertion. The inverted face-flip is therefore a fixed single mirror (Y) and
     * the insertion supplies the other degree of freedom.
     *
     * @param scopeType {@link #SCOPE_UPRIGHT} (face up) or {@link #SCOPE_INVERTED} (face down)
     * @param insertion {@link #INSERT_A} or {@link #INSERT_B} (which side the label is on)
     * @return {@code {flipX, flipY}}; never null
     */
    public static boolean[] benchFlipFlags(String scopeType, String insertion) {
        // Diagonal-only 2x2 (a, d) with a,d in {+1,-1}: flipX = a<0, flipY = d<0.
        // Inverted (face down) = mirror Y; insertion B = in-plane 180. The four combinations give the
        // four axis-aligned orientations: upright+A id, upright+B (T,T), inverted+A (F,T), inverted+B (T,F).
        int sa = 1;
        int sd = SCOPE_INVERTED.equalsIgnoreCase(scopeType) ? -1 : 1;
        int ia = INSERT_B.equalsIgnoreCase(insertion) ? -1 : 1;
        int id = ia;
        return new boolean[] {sa * ia < 0, sd * id < 0};
    }

    /** The bench flip for the active microscope, read from config (or the identity default). */
    public static boolean[] currentBenchFlip() {
        return benchFlipFlags(scopeType(), slideInsertion());
    }

    /**
     * Persist one slide-placement factor to the active microscope's YAML {@code light_path} block.
     * No-op (returns false) when there is no writable config. Callers should reload the config
     * afterwards if they need the change reflected in subsequent reads.
     *
     * @param field one of {@link #KEY_SCOPE_TYPE}, {@link #KEY_SLIDE_INSERTION},
     *              {@link #KEY_OPTICAL_FLIP}
     * @param value the value to write
     * @return {@code true} if the file was changed
     */
    public static boolean writeFactor(String field, String value) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null || mgr.getConfigPath() == null) {
            logger.warn("LightPathModel.writeFactor({}={}): no writable config", field, value);
            return false;
        }
        try {
            ConfigYamlEditor.Result r = ConfigYamlEditor.setTopLevelChildScalar(
                    java.nio.file.Path.of(mgr.getConfigPath()), BLOCK, field, value);
            logger.info("LightPathModel.writeFactor: {}", r.message);
            return r.changed;
        } catch (Exception e) {
            logger.error("LightPathModel.writeFactor({}={}) failed: {}", field, value, e.getMessage());
            return false;
        }
    }

    /**
     * Resolve and format the full orientation stack for the current preferences, active detector,
     * and (optionally) a project entry + alignment preset. Safe to call offline -- unknown values
     * are reported as such rather than throwing. Intended for the startup / setup log dump and for
     * ad-hoc diagnosis.
     *
     * @param entry  project entry whose macro flip applies; may be {@code null}
     * @param preset active alignment preset; may be {@code null}
     * @return a multi-line, ASCII-only description; never null
     */
    public static String describe(ProjectImageEntry<BufferedImage> entry, TransformPreset preset) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        String scope = mgr != null ? mgr.getMicroscopeName() : "(no config)";
        boolean offline = mgr != null && mgr.isOfflineScope();
        String detectorId = mgr != null ? mgr.getActiveDetector() : null;

        StageImageTransform sit = StageImageTransform.current();
        StagePolarity polarity = sit.getStagePolarity();
        CameraOrientation camera = sit.getCameraOrientation();
        boolean[] composite = sit.stitcherFlipFlags();
        boolean[] cameraOnly = sit.cameraFlipFlags();

        // Factor 2 -- the display optical flip (light_path.optical_flip), plus the per-detector flip
        // used for macro-image resolution (false when no detector / no config).
        boolean[] optical = currentOpticalFlip();
        String detectorFlip;
        if (mgr != null && detectorId != null) {
            detectorFlip =
                    String.format("(%s, %s)", mgr.getDetectorFlipX(detectorId), mgr.getDetectorFlipY(detectorId));
        } else {
            detectorFlip = "(unresolved)";
        }

        // Macro preset flip (factor +) -- resolved through FlipResolver so it matches acquisition.
        String macroFlip;
        if (preset != null || entry != null) {
            boolean mfx = FlipResolver.resolveFlipX(entry, preset, detectorId);
            boolean mfy = FlipResolver.resolveFlipY(entry, preset, detectorId);
            macroFlip = String.format("(%s, %s)", mfx, mfy);
        } else {
            macroFlip = "(no entry/preset in context)";
        }

        boolean[] bench = currentBenchFlip();

        StringBuilder sb = new StringBuilder(1024);
        sb.append("LightPathModel -- orientation stack for microscope '")
                .append(scope)
                .append("'");
        if (offline) {
            sb.append(" [OFFLINE/placeholder]");
        }
        sb.append(":").append(System.lineSeparator());
        sb.append("  1. Slide placement    : scope=")
                .append(scopeType())
                .append(", insertion=")
                .append(slideInsertion())
                .append(" -> bench flip (")
                .append(bench[0])
                .append(", ")
                .append(bench[1])
                .append(")  [YAML light_path.*]")
                .append(System.lineSeparator());
        sb.append("  2. Optical flip       : ")
                .append(opticalFlip())
                .append(" -> (")
                .append(optical[0])
                .append(", ")
                .append(optical[1])
                .append(")  [YAML light_path.optical_flip; drives map Camera View. detector flip ")
                .append(detectorFlip)
                .append(" for macro]")
                .append(System.lineSeparator());
        sb.append("  3. Camera orientation : ")
                .append(camera)
                .append("  [pref CameraOrientation]")
                .append(System.lineSeparator());
        sb.append("  4. Stage polarity     : ")
                .append(polarity)
                .append("  [pref StagePolarity; net of MM invert + wiring]")
                .append(System.lineSeparator());
        sb.append("  +  Macro image flip   : ")
                .append(macroFlip)
                .append("  [preset flipMacroX/Y via FlipResolver]")
                .append(System.lineSeparator());
        sb.append("  => Composite (stitch) : (")
                .append(composite[0])
                .append(", ")
                .append(composite[1])
                .append(")  [stitcherFlipFlags = polarity XOR camera; drives acquisition + stitching]")
                .append(System.lineSeparator());
        sb.append("  => Camera-only (map)  : (")
                .append(cameraOnly[0])
                .append(", ")
                .append(cameraOnly[1])
                .append(")  [cameraFlipFlags; drives Stage Map Camera View]");
        return sb.toString();
    }

    /** Convenience: {@link #describe(ProjectImageEntry, TransformPreset)} with no entry/preset context. */
    public static String describe() {
        return describe(null, null);
    }

    /** Log the current orientation stack at INFO. Call at startup / setup and when diagnosing flips. */
    public static void logCurrent(String reason) {
        logger.info("{}{}{}", reason == null ? "" : reason + ": ", System.lineSeparator(), describe());
    }
}
