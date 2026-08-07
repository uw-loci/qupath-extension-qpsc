package qupath.ext.qpsc.utilities;

import java.awt.image.BufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
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
 *   <li><b>Slide placement</b> -- two independent sub-parts, both physical and both unencoded:
 *       <b>(1a) scope type</b> -- inverted scope (objective below, coverslip down) vs upright
 *       (objective above, coverslip up), which mirrors the naked-eye stage view relative to the
 *       camera; and <b>(1b) slide insertion</b> -- a slide can be slotted into the holder two ways
 *       (an in-plane 180-degree rotation, frosted label at either end). Neither changes the
 *       eyepiece/camera view of a given feature (the coverslip always faces the objective), but
 *       together they set how the slide reads on the stage. <b>Not encoded anywhere</b> in QPSC
 *       config today. The operator supplies the net effect on the Stage Map by eye via
 *       {@link PersistentPreferences#getStageViewOrientation()} (a display-only proxy, not a
 *       per-microscope hardware fact).</li>
 *   <li><b>Optical flip</b> -- objective + tube-lens parity, nominally the per-detector
 *       {@code flip_x/flip_y} in the microscope YAML, read via
 *       {@link MicroscopeConfigManager#getDetectorFlipX(String)} /
 *       {@link MicroscopeConfigManager#getDetectorFlipY(String)}. False on both current rigs.</li>
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
 * stitching, and its {@link StageImageTransform#cameraFlipFlags()} (camera only) drives the Stage
 * Map. The per-slide alignment transform, fit to real image-to-stage correspondences, captures the
 * <em>combined</em> effect of factors 1-4 and is the empirically correct relationship. Factors 1
 * and 2 being unencoded is why any surface that reads the decomposition alone (the Stage Map) can
 * be wrong even when the composite/transform are right.
 */
public final class LightPathModel {

    private static final Logger logger = LoggerFactory.getLogger(LightPathModel.class);

    private LightPathModel() {}

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

        // Factor 2 -- per-detector optical flip (false when no detector / no config).
        String opticalFlip;
        if (mgr != null && detectorId != null) {
            opticalFlip = String.format("(%s, %s)", mgr.getDetectorFlipX(detectorId), mgr.getDetectorFlipY(detectorId));
        } else {
            opticalFlip = "(unresolved -- no active detector/config)";
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

        String stageViewPref = PersistentPreferences.getStageViewOrientation();

        StringBuilder sb = new StringBuilder(1024);
        sb.append("LightPathModel -- orientation stack for microscope '")
                .append(scope)
                .append("'");
        if (offline) {
            sb.append(" [OFFLINE/placeholder]");
        }
        sb.append(":").append(System.lineSeparator());
        sb.append("  1. Slide placement    : NOT ENCODED (inverted vs upright; physical). ")
                .append("Stage Map proxy = stageViewOrientation pref '")
                .append(stageViewPref)
                .append("'")
                .append(System.lineSeparator());
        sb.append("  2. Optical flip       : ")
                .append(opticalFlip)
                .append("  [YAML id_detector.flip_x/flip_y, detector='")
                .append(detectorId != null ? detectorId : "?")
                .append("']")
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
