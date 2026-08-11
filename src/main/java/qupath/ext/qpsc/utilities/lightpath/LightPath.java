package qupath.ext.qpsc.utilities.lightpath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.LightPathModel;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.StageImageTransform;

/**
 * The single, cohesive home for a microscope's light-path orientation geometry --
 * how a slide on the stage becomes the pixels QPSC displays, stitches, and matches.
 * It consolidates what used to be scattered across {@code LightPathModel} (config
 * tokens), {@code StageImageTransform} (camera/stage parity), {@code FlipResolver}
 * (macro + per-detector flip), and the companion-composition logic.
 *
 * <h2>Model</h2>
 * A light path is a set of orientation-affecting stages, each contributing a
 * {@link Parity}. They compose by XOR. The stages, from slide to detector pixels:
 * <ol>
 *   <li><b>Slide placement</b> (per-slide): label-left "A" = identity, label-right
 *       "B" = 180. See {@link #slideRotation}.</li>
 *   <li><b>Scope face</b> + <b>optical flip</b>: the camera-vs-stage parity (why a
 *       scope needs a distinct Camera View). Measured, not derived.</li>
 *   <li><b>Camera orientation</b> (per detector): camera mounting parity.</li>
 *   <li><b>Stage polarity</b>: physical stage direction for {@code +X/+Y}.</li>
 *   <li><b>Macro pair flip</b>: the empirical raw-scanner-to-camera parity of a
 *       {@code (sourceScanner, thisScope)} alignment preset -- supplied per call,
 *       since it depends on the source, not just this scope.</li>
 * </ol>
 *
 * <h2>Multiple detectors</h2>
 * Camera orientation and the per-detector flip are keyed by {@code detectorId} via
 * {@link #detectorGeometry}. Scopes with one detector simply resolve one branch;
 * a multi-detector scope resolves the branch for whichever detector produced the
 * pixels. Shared stages (placement, optical flip, stage polarity) are common.
 *
 * <h2>Forking to a novel geometry</h2>
 * A different scope plugs in by (a) describing its factors in the {@code light_path}
 * YAML block + per-detector flip config, which {@link #forActiveScope} reads, or
 * (b) subclassing this class to override a stage's {@link Parity} or add a stage.
 * <b>Every parity here is a measured/declared input, never reasoned from geometry</b>
 * -- this class only composes them (see ORIENTATION_STACK.md, "never guess a sign").
 */
public class LightPath {

    private static final Logger logger = LoggerFactory.getLogger(LightPath.class);

    /** One detector's branch of the light path. */
    public record DetectorGeometry(String detectorId, Parity detectorFlip, Parity cameraOrientation) {}

    private final String scopeType;
    private final String defaultInsertion;
    private final String cameraOrientationName;
    private final String stagePolarityName;
    private final Parity opticalFlip;
    private final Parity stagePolarity;
    private final Parity cameraOrientation; // shared default; per-detector override via detectorGeometry

    protected LightPath(
            String scopeType,
            String defaultInsertion,
            String cameraOrientationName,
            String stagePolarityName,
            Parity opticalFlip,
            Parity stagePolarity,
            Parity cameraOrientation) {
        this.scopeType = scopeType;
        this.defaultInsertion = defaultInsertion;
        this.cameraOrientationName = cameraOrientationName;
        this.stagePolarityName = stagePolarityName;
        this.opticalFlip = opticalFlip;
        this.stagePolarity = stagePolarity;
        this.cameraOrientation = cameraOrientation;
    }

    /**
     * Build the light path for the active microscope from its config
     * ({@code light_path} block, per-detector flip) and the camera/stage
     * preferences. Values are read, never reasoned about.
     */
    public static LightPath forActiveScope() {
        StageImageTransform sit = StageImageTransform.current();
        Parity camera = Parity.of(sit.cameraFlipFlags());
        Parity stitcher = Parity.of(sit.stitcherFlipFlags());
        // stitcher = camera XOR polarity  =>  polarity = stitcher XOR camera
        Parity polarity = stitcher.xor(camera);
        Parity optical = Parity.of(LightPathModel.currentOpticalFlip());
        return new LightPath(
                LightPathModel.scopeType(),
                LightPathModel.slideInsertion(),
                String.valueOf(sit.getCameraOrientation()),
                String.valueOf(sit.getStagePolarity()),
                optical,
                polarity,
                camera);
    }

    /** The scope-wide default slide placement token ({@code "A"}/{@code "B"}). */
    public String defaultInsertion() {
        return defaultInsertion;
    }

    /**
     * The per-slide placement rotation: {@code A} (label-left / as-scanned) is
     * identity, {@code B} (label-right) is a 180 (both axes). Independent of scope
     * type by design (the scope-face inversion is already carried by stage polarity
     * and the alignment transform -- see ORIENTATION_STACK.md).
     */
    public Parity slideRotation(String insertion) {
        return Parity.of(LightPathModel.slideRotationFlipFlags(insertion));
    }

    /**
     * The net parity to bake into the "(Camera View)" companion so it matches the
     * LIVE camera at a given slide placement. {@code rawToCamera} is the source
     * preset's empirical {@code flipMacroX/Y}; the only per-scope term added here is
     * the placement 180. This is THE composition the companion is built from.
     */
    public Parity companionBake(String placement, Parity rawToCamera) {
        Parity raw = rawToCamera != null ? rawToCamera : Parity.IDENTITY;
        return raw.xor(slideRotation(placement));
    }

    /** Resolve one detector's branch (per-detector flip + camera orientation). */
    public DetectorGeometry detectorGeometry(String detectorId) {
        Parity detFlip = Parity.IDENTITY;
        if (detectorId != null && !detectorId.isBlank()) {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                detFlip = new Parity(mgr.getDetectorFlipX(detectorId), mgr.getDetectorFlipY(detectorId));
            }
        }
        // Camera orientation is shared across detectors today; a multi-camera scope
        // overrides this per detector (the documented extension point).
        return new DetectorGeometry(detectorId, detFlip, cameraOrientation);
    }

    /**
     * Camera-View parity for a detector: camera orientation XOR optical flip. Orients
     * a surface already in the sample/stage frame to match the Live Viewer (the Stage
     * Map's Camera View). Does NOT include stage polarity (that surface already
     * carries it) nor slide placement (that is not part of camera view).
     */
    public Parity cameraView(String detectorId) {
        return detectorGeometry(detectorId).cameraOrientation().xor(opticalFlip);
    }

    /**
     * Stage-frame (stitcher) parity for a detector: camera orientation XOR stage
     * polarity. Orients raw stage-frame tiles into the camera-oriented stitched image.
     */
    public Parity stageFrame(String detectorId) {
        return detectorGeometry(detectorId).cameraOrientation().xor(stagePolarity);
    }

    /** The per-detector hardware flip (config {@code flip_x/flip_y}). */
    public Parity detectorFlip(String detectorId) {
        return detectorGeometry(detectorId).detectorFlip();
    }

    /**
     * Assemble the immutable {@link LightPathSnapshot} to stamp on an entry: this
     * scope's factors + the source preset's macro flip + the entry's per-slide
     * placement + the detector + the net parity baked into the pixels.
     *
     * @param preset      source alignment preset (may be null -&gt; macro flip identity)
     * @param placement   this slide's placement token ({@code A}/{@code B})
     * @param detectorId  detector that produced these pixels (may be null)
     * @param bakedParity net parity baked into the entry's pixels
     */
    public LightPathSnapshot capture(
            AffineTransformManager.TransformPreset preset, String placement, String detectorId, Parity bakedParity) {
        boolean macroX = preset != null && Boolean.TRUE.equals(preset.getFlipMacroX());
        boolean macroY = preset != null && Boolean.TRUE.equals(preset.getFlipMacroY());
        Parity baked = bakedParity != null ? bakedParity : Parity.IDENTITY;
        String insertion = (placement == null || placement.isBlank()) ? LightPathModel.INSERT_A : placement;
        return new LightPathSnapshot(
                scopeType,
                insertion,
                LightPathModel.opticalFlip(),
                cameraOrientationName,
                stagePolarityName,
                detectorId,
                macroX,
                macroY,
                baked.flipX(),
                baked.flipY());
    }
}
