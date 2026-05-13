package qupath.ext.qpsc.utilities;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Ensures a `(flipped X|Y|XY)` sibling entry exists for the currently-open
 * project entry whenever the active `(source_scanner, target_microscope)`
 * preset says a macro flip is needed, and switches the QuPath viewer to
 * that sibling.
 *
 * <p>The flipped sibling is required for the visual UX of the alignment
 * step: during single-tile refinement and 3-point alignment the operator
 * compares the QuPath display to the live camera view, and on scopes
 * with an optical flip (e.g. PPM) the unflipped Ocus40 H&E and the live
 * camera view disagree by a mirror -- impossible to align by eye.
 *
 * <p>Step B (2026-05-04) moved the **source of truth** for flip state
 * from per-entry `FLIP_X`/`FLIP_Y` metadata to
 * `TransformPreset.flipMacroX/Y` (per `(source_scanner, target_microscope)`
 * preset) and the per-slide alignment JSON. This class respects that:
 * we resolve flip from the preset, not from per-entry metadata. The
 * `(flipped XY)` sibling is created for visual UX only -- it is no
 * longer authoritative for flip state.
 */
public final class ImageFlipHelper {

    private static final Logger logger = LoggerFactory.getLogger(ImageFlipHelper.class);

    private ImageFlipHelper() {}

    /**
     * Resolve flip from the active preset for the open entry's source
     * scanner and the active microscope, then ensure a flipped sibling
     * exists and is the open entry. Completes with {@code true} on
     * success (whether or not a flip was needed).
     */
    public static CompletableFuture<Boolean> validateAndFlipIfNeeded(
            QuPathGUI gui, Project<BufferedImage> project, String sampleName) {
        return ensureFlippedSiblingIfNeeded(gui, project, sampleName, null, null);
    }

    /**
     * Caller-resolved flip overload. Used by paths that already know the
     * flip state out-of-band (e.g. preset rebuild). The preset-driven
     * resolution is bypassed; the explicit flags are taken as truth.
     */
    public static CompletableFuture<Boolean> validateAndFlipIfNeeded(
            QuPathGUI gui,
            Project<BufferedImage> project,
            String sampleName,
            boolean explicitFlipX,
            boolean explicitFlipY) {
        return ensureFlippedSiblingIfNeeded(gui, project, sampleName, explicitFlipX, explicitFlipY);
    }

    /** Sample-result variant -- delegates to the string overload. */
    public static CompletableFuture<Boolean> validateAndFlipIfNeeded(
            QuPathGUI gui, Project<BufferedImage> project, SampleSetupResult sample) {
        return validateAndFlipIfNeeded(gui, project, sample != null ? sample.sampleName() : null);
    }

    // ---------------------------------------------------------------
    // Worker
    // ---------------------------------------------------------------

    private static CompletableFuture<Boolean> ensureFlippedSiblingIfNeeded(
            QuPathGUI gui,
            Project<BufferedImage> project,
            String sampleName,
            Boolean explicitFlipX,
            Boolean explicitFlipY) {

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (gui == null || project == null || gui.getImageData() == null) {
            logger.warn("validateAndFlipIfNeeded: missing gui/project/imageData -- skipping");
            future.complete(true);
            return future;
        }

        ProjectImageEntry<BufferedImage> openEntry = project.getEntry(gui.getImageData());
        if (openEntry == null) {
            logger.warn("validateAndFlipIfNeeded: open imageData has no project entry -- skipping");
            future.complete(true);
            return future;
        }

        // If the open entry IS already a flipped sibling, nothing to do.
        if (isFlippedSiblingName(openEntry.getImageName())) {
            logger.info(
                    "validateAndFlipIfNeeded: open entry '{}' is already a flipped sibling -- no-op",
                    openEntry.getImageName());
            future.complete(true);
            return future;
        }

        // If the open entry is a sub-acquisition (stitched output with xy_offset
        // metadata and a base_image distinct from its own name), no flipped sibling
        // applies. Sub-images are pyramid outputs from the active microscope's
        // camera, so the (source_scanner, target_microscope) preset flip is a
        // property of the parent macro -- not of the sub-image. Worse,
        // findFlippedSibling matches by shared base_image, which means a
        // sub-image and the macro's (flipped XY) companion appear as siblings;
        // without this guard, the helper switches the open entry from the
        // sub-image to the macro's flipped companion, dropping all sub-image
        // annotations and producing the broken-sub-image-acquisition regression.
        if (isSubAcquisitionEntry(openEntry)) {
            logger.info(
                    "validateAndFlipIfNeeded: open entry '{}' is a sub-acquisition -- no-op", openEntry.getImageName());
            future.complete(true);
            return future;
        }

        // Resolve the flip we need.
        boolean flipX;
        boolean flipY;
        if (explicitFlipX != null && explicitFlipY != null) {
            flipX = explicitFlipX;
            flipY = explicitFlipY;
            logger.info(
                    "validateAndFlipIfNeeded: using explicit flip flags ({}, {}) for sample='{}'",
                    flipX,
                    flipY,
                    sampleName);
        } else {
            boolean[] resolved = resolveFlipFromPreset(openEntry);
            flipX = resolved[0];
            flipY = resolved[1];
        }

        if (!flipX && !flipY) {
            logger.info(
                    "validateAndFlipIfNeeded: active preset for entry '{}' requires no flip -- no-op",
                    openEntry.getImageName());
            future.complete(true);
            return future;
        }

        // Look for an existing flipped sibling of this base entry.
        ProjectImageEntry<BufferedImage> sibling = findFlippedSibling(project, openEntry, flipX, flipY);
        if (sibling != null) {
            logger.info(
                    "validateAndFlipIfNeeded: flipped sibling already exists ({}); switching open entry",
                    sibling.getImageName());
            switchOpenEntry(gui, sibling, future);
            return future;
        }

        // No sibling; create one and switch to it.
        try {
            logger.info(
                    "validateAndFlipIfNeeded: creating flipped duplicate of '{}' (flipX={}, flipY={})",
                    openEntry.getImageName(),
                    flipX,
                    flipY);
            ProjectImageEntry<BufferedImage> created =
                    QPProjectFunctions.createFlippedDuplicate(project, openEntry, flipX, flipY, sampleName);
            if (created == null) {
                logger.warn("validateAndFlipIfNeeded: createFlippedDuplicate returned null");
                future.complete(false);
                return future;
            }
            switchOpenEntry(gui, created, future);
        } catch (Exception e) {
            logger.error("validateAndFlipIfNeeded: failed to create flipped duplicate", e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Resolve `(flipMacroX, flipMacroY)` from the preset that matches
     * the open entry's source scanner and the currently active
     * microscope. Returns `(false, false)` if no match is found.
     *
     * <p>Public so that save-site code (e.g. `ManualAlignmentPath`) can
     * record the same flip frame the alignment was actually built in,
     * matching what `ImageFlipHelper.validateAndFlipIfNeeded` saw at
     * the time it switched the open entry to the flipped sibling.
     *
     * @return a 2-element array {flipMacroX, flipMacroY}.
     */
    public static boolean[] resolveRequiredFlipFromPreset(ProjectImageEntry<BufferedImage> openEntry) {
        return resolveFlipFromPreset(openEntry);
    }

    /**
     * Resolve `(flipMacroX, flipMacroY)` from the preset that matches
     * the open entry's source scanner and the currently active
     * microscope. Returns `(false, false)` if no match is found -- the
     * caller treats that as "no flip needed".
     */
    private static boolean[] resolveFlipFromPreset(ProjectImageEntry<BufferedImage> openEntry) {
        try {
            String sourceScanner = openEntry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
            if (sourceScanner == null || sourceScanner.isBlank()) {
                logger.info(
                        "validateAndFlipIfNeeded: entry '{}' has no SOURCE_MICROSCOPE metadata; cannot resolve preset",
                        openEntry.getImageName());
                return new boolean[] {false, false};
            }

            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            String activeMicroscope = mgr != null ? mgr.getMicroscopeName() : null;
            if (activeMicroscope == null || activeMicroscope.isBlank()) {
                logger.info("validateAndFlipIfNeeded: no active microscope name; cannot resolve preset");
                return new boolean[] {false, false};
            }

            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isBlank()) {
                logger.info("validateAndFlipIfNeeded: no microscope config path; cannot resolve preset");
                return new boolean[] {false, false};
            }

            AffineTransformManager atm = new AffineTransformManager(new File(configPath).getParent());
            AffineTransformManager.TransformPreset preset = atm.getBestPresetForPair(sourceScanner, activeMicroscope);
            if (preset == null || !preset.hasFlipState()) {
                logger.info(
                        "validateAndFlipIfNeeded: no preset (or no flip state) for ({}, {}); treating as no flip",
                        sourceScanner,
                        activeMicroscope);
                return new boolean[] {false, false};
            }

            boolean flipX = Boolean.TRUE.equals(preset.getFlipMacroX());
            boolean flipY = Boolean.TRUE.equals(preset.getFlipMacroY());
            logger.info(
                    "validateAndFlipIfNeeded: preset '{}' for ({}, {}) -> flipMacroX={}, flipMacroY={}",
                    preset.getName(),
                    sourceScanner,
                    activeMicroscope,
                    flipX,
                    flipY);
            return new boolean[] {flipX, flipY};
        } catch (Exception e) {
            logger.warn("validateAndFlipIfNeeded: preset resolution failed: {}", e.getMessage());
            return new boolean[] {false, false};
        }
    }

    /**
     * Find an existing `(flipped X|Y|XY)` sibling of {@code baseEntry}
     * matching the requested flip axes. Matches by `base_image`
     * metadata if available, falling back to name prefix.
     */
    private static ProjectImageEntry<BufferedImage> findFlippedSibling(
            Project<BufferedImage> project, ProjectImageEntry<BufferedImage> baseEntry, boolean flipX, boolean flipY) {

        String desiredSuffix;
        if (flipX && flipY) {
            desiredSuffix = "(flipped XY)";
        } else if (flipX) {
            desiredSuffix = "(flipped X)";
        } else {
            desiredSuffix = "(flipped Y)";
        }

        String baseImage = ImageMetadataManager.getBaseImage(baseEntry);
        if (baseImage == null || baseImage.isBlank()) {
            baseImage = qupath.lib.common.GeneralTools.stripExtension(baseEntry.getImageName());
        }
        String baseImageFinal = baseImage;

        List<ProjectImageEntry<BufferedImage>> entries = project.getImageList();
        for (ProjectImageEntry<BufferedImage> e : entries) {
            if (e == baseEntry) continue;
            String name = e.getImageName();
            if (name == null || !name.contains(desiredSuffix)) continue;
            // Prefer entries that share base_image; fall back to name-prefix match.
            String candBase = ImageMetadataManager.getBaseImage(e);
            if (candBase != null && candBase.equals(baseImageFinal)) {
                return e;
            }
            if (name.startsWith(baseEntry.getImageName())
                    || name.startsWith(qupath.lib.common.GeneralTools.stripExtension(baseEntry.getImageName()))) {
                return e;
            }
        }
        return null;
    }

    /**
     * @return true if {@code name} ends with one of the flipped-sibling
     *     suffixes produced by {@code QPProjectFunctions.createFlippedDuplicate}.
     */
    public static boolean isFlippedSiblingName(String name) {
        if (name == null) return false;
        return name.endsWith("(flipped X)") || name.endsWith("(flipped Y)") || name.endsWith("(flipped XY)");
    }

    /**
     * @return true if {@code entry} looks like a sub-acquisition: it carries a
     *     non-zero {@code xy_offset} and a {@code base_image} distinct from its
     *     own stripped name. Mirrors {@code ExistingImageWorkflowV2.isSubAcquisition}.
     */
    private static boolean isSubAcquisitionEntry(ProjectImageEntry<BufferedImage> entry) {
        if (entry == null) return false;
        double[] offset = ImageMetadataManager.getXYOffset(entry);
        if (offset[0] == 0 && offset[1] == 0) return false;
        String baseImage = ImageMetadataManager.getBaseImage(entry);
        if (baseImage == null || baseImage.isEmpty()) return false;
        String ownName = qupath.lib.common.GeneralTools.stripExtension(entry.getImageName());
        return !baseImage.equals(ownName);
    }

    /**
     * Switch the QuPath viewer to {@code targetEntry} and complete
     * {@code future} when the open is initiated. Always defers to a
     * later FX pulse via {@link Platform#runLater(Runnable)}.
     *
     * <p>Why always-defer (not "run now if on FX thread"): callers may
     * arrive inside a JavaFX animation pulse -- e.g. when this is a
     * synchronous continuation of a CompletableFuture completed by
     * {@code ProjectHelper.setupProject}'s post-creation Timeline.
     * {@code QuPathGUI.openImageEntry} calls {@code checkSaveChanges},
     * which calls {@code Dialogs.showAndWait}; JavaFX throws
     * "showAndWait is not allowed during animation or layout
     * processing" if invoked inside a pulse, and the dialog returns
     * null, causing an NPE in {@code Optional.orElse}. Deferring puts
     * the open on the next pulse where modal dialogs are legal.
     */
    private static void switchOpenEntry(
            QuPathGUI gui, ProjectImageEntry<BufferedImage> targetEntry, CompletableFuture<Boolean> future) {
        Platform.runLater(() -> {
            try {
                gui.refreshProject();
                gui.openImageEntry(targetEntry);
                logger.info("Switched open entry to '{}'", targetEntry.getImageName());
                future.complete(true);
            } catch (Exception e) {
                logger.error("Failed to switch open entry to '{}'", targetEntry.getImageName(), e);
                future.completeExceptionally(e);
            }
        });
    }
}
