package qupath.ext.qpsc.utilities;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Deprecated stub retained for binary compatibility with existing call sites
 * (MicroscopeAlignmentWorkflow, ManualAlignmentPath, ExistingAlignmentPath).
 *
 * <p>Step B of the flip-relocation refactor moved the macro flip from per-entry
 * metadata to the (source_scanner, target_microscope) preset. The previous
 * job of this class -- creating "(flipped X|Y|XY)" duplicate entries and
 * switching the QuPath viewer to them -- is no longer needed: the alignment-
 * time flip is baked into {@code state.transform} by
 * {@link qupath.ext.qpsc.controller.workflow.AlignmentHelper#checkForSlideAlignment}
 * and applied as a transform step inside
 * {@link qupath.ext.qpsc.controller.ForwardPropagationWorkflow#propagateBack}.
 *
 * <p>All four {@code validateAndFlipIfNeeded} overloads complete immediately
 * with {@code true}. They will be removed entirely once every caller is
 * updated to stop calling them.
 */
public final class ImageFlipHelper {

    private static final Logger logger = LoggerFactory.getLogger(ImageFlipHelper.class);

    private ImageFlipHelper() {}

    /** No-op stub. Always returns true. */
    public static CompletableFuture<Boolean> validateAndFlipIfNeeded(
            QuPathGUI gui, Project<BufferedImage> project, String sampleName) {
        logger.debug("validateAndFlipIfNeeded(sample='{}'): no-op stub (Step B)", sampleName);
        return CompletableFuture.completedFuture(true);
    }

    /** No-op stub. Always returns true. */
    public static CompletableFuture<Boolean> validateAndFlipIfNeeded(
            QuPathGUI gui,
            Project<BufferedImage> project,
            String sampleName,
            boolean explicitFlipX,
            boolean explicitFlipY) {
        logger.debug(
                "validateAndFlipIfNeeded(sample='{}', flipX={}, flipY={}): no-op stub (Step B)",
                sampleName, explicitFlipX, explicitFlipY);
        return CompletableFuture.completedFuture(true);
    }

    /** No-op stub. Always returns true. */
    public static CompletableFuture<Boolean> validateAndFlipIfNeeded(
            QuPathGUI gui, Project<BufferedImage> project, SampleSetupResult sample) {
        return validateAndFlipIfNeeded(gui, project, sample != null ? sample.sampleName() : null);
    }
}
