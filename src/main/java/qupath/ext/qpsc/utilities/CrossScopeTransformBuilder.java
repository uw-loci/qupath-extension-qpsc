package qupath.ext.qpsc.utilities;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.AffineTransformManager.TransformPreset;

/**
 * Composes a {@code pixel -> targetStage} {@link AffineTransform} for cross-microscope
 * acquisitions where the source scanner is shared (e.g. Ocus40), but the per-slide
 * alignment was saved against a different microscope than the one currently active.
 *
 * <p>Concrete use case from 2026-05-02: an OWS3 sub-acquisition was stitched and
 * auto-registered with a per-slide {@code pixel -> OWS3 stage} transform. The user
 * later opens the same sub-acquisition image on PPM, draws a sub-region annotation,
 * and wants to acquire it on PPM. Both microscopes have a saved {@link TransformPreset}
 * for the shared Ocus40 source ({@code OWS3_Ocus40_*} and {@code PPM_Ocus40_*}); the
 * composition routes the annotation pixel through the macro image -- which is the
 * lingua franca between the two scopes -- and out to PPM stage micrometers.
 *
 * <h2>Transform chain</h2>
 *
 * <pre>
 * sourceImagePixel
 *     -> sourceStage         (per-slide alignment file, scope = source microscope)
 *     -> macroPixel(src)     (inverse of source preset's macro->stage)
 *     -> macroPixel(tgt)     (mirror in macro frame if flipMacroX/Y differs between presets)
 *     -> targetStage         (target preset's macro->stage)
 * </pre>
 *
 * <p>The macro pixel coordinates between presets live in their respective
 * orientation-dialog flipped frames (see {@code MicroscopeAlignmentWorkflow.saveGeneralTransform}).
 * If both presets recorded the same {@code flipMacroX/Y}, the macro pixel frames
 * coincide and no mirror is needed. If they differ, we apply a mirror using the
 * preset's {@code macroDisplayWidth/Height}.
 *
 * <h2>Limitations / preconditions</h2>
 *
 * <ol>
 *   <li>Both presets must share the same {@code sourceScanner}; the macro source bytes
 *       must be the same physical image. Verified at the call site.</li>
 *   <li>Both presets should ideally have anchor metadata (post-{@code 98e6009}). If
 *       either lacks {@code macroDisplayWidth/Height} the mirror cannot be computed
 *       from that preset; we fall back to the other preset's dimensions, and fail
 *       loudly if neither has them.</li>
 *   <li>Both presets must have {@code flipMacroX/Y}; legacy presets without it are
 *       rejected. Re-save the alignment to populate the field.</li>
 *   <li>The source image's pixel frame must match the per-slide alignment's expected
 *       input frame. For sub-acquisitions this is automatically the sub-acq's
 *       canonical stitch orientation -- the auto-register at stitch import builds
 *       {@code pixel -> sourceStage} in that frame. For arbitrary per-slide files
 *       the caller is responsible for matching frames (the same precondition that
 *       same-scope acquisitions already enforce via the entry-flip-vs-preset check).</li>
 * </ol>
 *
 * @since 0.5.x
 */
public final class CrossScopeTransformBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CrossScopeTransformBuilder.class);

    private CrossScopeTransformBuilder() {}

    /**
     * Composes a {@code sourceImagePixel -> targetStage} transform.
     *
     * @param sourceImageToSourceStage  per-slide alignment (the scope-tagged JSON's transform).
     *                                  Maps source-image pixels to source-microscope stage micrometers.
     * @param sourcePreset              saved alignment preset for (sourceScanner, sourceMicroscope).
     *                                  Provides the source-microscope's macro->stage transform.
     * @param targetPreset              saved alignment preset for (sourceScanner, targetMicroscope).
     *                                  Provides the target microscope's macro->stage transform.
     * @return composed {@code pixel -> targetStage} {@link AffineTransform}
     * @throws IllegalArgumentException if any input is null, scanners disagree, or preset
     *                                  flip / dimension metadata is missing
     * @throws NoninvertibleTransformException if the source preset's macro->stage isn't invertible
     *                                  (degenerate scale)
     */
    public static AffineTransform compose(
            AffineTransform sourceImageToSourceStage,
            TransformPreset sourcePreset,
            TransformPreset targetPreset)
            throws NoninvertibleTransformException {

        if (sourceImageToSourceStage == null) {
            throw new IllegalArgumentException("sourceImageToSourceStage transform is required");
        }
        if (sourcePreset == null || targetPreset == null) {
            throw new IllegalArgumentException("Both source and target presets are required");
        }
        if (!sourcePreset.hasFlipState() || !targetPreset.hasFlipState()) {
            throw new IllegalArgumentException(
                    "Both presets must have flipMacroX/Y captured; re-save legacy presets via Microscope Alignment");
        }
        String srcScanner = sourcePreset.getSourceScanner();
        String tgtScanner = targetPreset.getSourceScanner();
        if (srcScanner == null || tgtScanner == null || !srcScanner.equals(tgtScanner)) {
            throw new IllegalArgumentException(String.format(
                    "Cross-scope composition requires identical sourceScanner on both presets; got src='%s' tgt='%s'",
                    srcScanner, tgtScanner));
        }

        // Step 1: target preset's macro->stage (macro_tgt -> targetStage).
        AffineTransform composed = new AffineTransform(targetPreset.getTransform());

        // Step 2: macro frame mirror from src-displayed-flipped to tgt-displayed-flipped, if flips
        // differ. We compose right-to-left: composed = T_tgt * mirror, so applying composed to a
        // vector v evaluates as T_tgt(mirror(v)) -- i.e. mirror applied first, then T_tgt.
        boolean mirrorX = sourcePreset.getFlipMacroX() ^ targetPreset.getFlipMacroX();
        boolean mirrorY = sourcePreset.getFlipMacroY() ^ targetPreset.getFlipMacroY();
        if (mirrorX || mirrorY) {
            int macroW = pickMacroWidth(sourcePreset, targetPreset);
            int macroH = pickMacroHeight(sourcePreset, targetPreset);
            if (macroW <= 0 || macroH <= 0) {
                throw new IllegalArgumentException(
                        "Macro frames differ but neither preset captured macroDisplayWidth/Height; cannot mirror");
            }
            AffineTransform mirror = new AffineTransform();
            if (mirrorX && mirrorY) {
                mirror.scale(-1, -1);
                mirror.translate(-macroW, -macroH);
            } else if (mirrorX) {
                mirror.scale(-1, 1);
                mirror.translate(-macroW, 0);
            } else {
                mirror.scale(1, -1);
                mirror.translate(0, -macroH);
            }
            composed.concatenate(mirror);
            logger.info(
                    "CrossScope: macro frame mirror applied (mirrorX={}, mirrorY={}, macroW={}, macroH={})",
                    mirrorX,
                    mirrorY,
                    macroW,
                    macroH);
        } else {
            logger.info(
                    "CrossScope: presets agree on flipMacro=({}, {}); no macro frame mirror needed",
                    sourcePreset.getFlipMacroX(),
                    sourcePreset.getFlipMacroY());
        }

        // Step 3: invert source preset's macro->stage to get (sourceStage -> macro_src).
        AffineTransform sourceMacroToStageInv = sourcePreset.getTransform().createInverse();
        composed.concatenate(sourceMacroToStageInv);

        // Step 4: prepend per-slide alignment (pixel -> sourceStage).
        composed.concatenate(sourceImageToSourceStage);

        logger.info(
                "CrossScope: composed pixel->{} transform via macro of '{}': "
                        + "src='{}' (flipMacro=({}, {})), tgt='{}' (flipMacro=({}, {}))",
                targetPreset.getMicroscope(),
                srcScanner,
                sourcePreset.getName(),
                sourcePreset.getFlipMacroX(),
                sourcePreset.getFlipMacroY(),
                targetPreset.getName(),
                targetPreset.getFlipMacroX(),
                targetPreset.getFlipMacroY());
        logger.info(
                "CrossScope: result scale=({}, {}) translate=({}, {})",
                String.format("%.6f", composed.getScaleX()),
                String.format("%.6f", composed.getScaleY()),
                String.format("%.2f", composed.getTranslateX()),
                String.format("%.2f", composed.getTranslateY()));

        return composed;
    }

    /**
     * Returns the source preset's macro display width if recorded, otherwise the target's.
     * The two presets are expected to share the same macro source (same scanner), so the
     * cropped macro dimensions should match -- preferring the source is a stable choice.
     */
    private static int pickMacroWidth(TransformPreset src, TransformPreset tgt) {
        int w = src.getMacroDisplayWidth();
        if (w > 0) return w;
        return tgt.getMacroDisplayWidth();
    }

    private static int pickMacroHeight(TransformPreset src, TransformPreset tgt) {
        int h = src.getMacroDisplayHeight();
        if (h > 0) return h;
        return tgt.getMacroDisplayHeight();
    }
}
