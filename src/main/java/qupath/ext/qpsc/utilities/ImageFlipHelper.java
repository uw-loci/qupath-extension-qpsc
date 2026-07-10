package qupath.ext.qpsc.utilities;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.ForwardPropagationWorkflow;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
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

        // Same-scope short-circuit. An image whose source IS the active microscope
        // (identity transform, no pair preset) or that was acquired on the active
        // microscope (pixels are already in this scope's frame) needs no flip.
        // Belt-and-suspenders against stale source_microscope tags from before
        // the active-microscope-as-source change.
        //
        // Skipped when the caller passed explicit flip flags: the caller is
        // asserting flip state out-of-band (orientation-dialog answer or
        // preset flipMacroX/Y), and that intent must win. Otherwise a stale
        // or auto-stamped source_microscope equal to the active scope
        // silently nullifies a user-requested flip -- the exact failure mode
        // hit by Microscope Alignment when StageMapWindow.onOpenedImageChanged
        // auto-stamps the active microscope on a fresh import that actually
        // came from a different scanner.
        String entrySource = openEntry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
        String entryAcquiredOn = openEntry.getMetadata().get(ImageMetadataManager.ACQUIRED_ON_MICROSCOPE);
        MicroscopeConfigManager activeMgr = MicroscopeConfigManager.getInstanceIfAvailable();
        String activeScopeName = (activeMgr != null) ? activeMgr.getMicroscopeName() : null;
        boolean callerSuppliedFlip = (explicitFlipX != null && explicitFlipY != null);
        if (!callerSuppliedFlip
                && activeScopeName != null
                && !activeScopeName.isBlank()
                && (activeScopeName.equals(entrySource) || activeScopeName.equals(entryAcquiredOn))) {
            logger.info(
                    "validateAndFlipIfNeeded: entry '{}' is in active scope's frame "
                            + "(source='{}', acquired_on='{}', active='{}') -- no flip",
                    openEntry.getImageName(),
                    entrySource,
                    entryAcquiredOn,
                    activeScopeName);
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
            // Missing source_microscope on a flip-needing scope: hard-cancel.
            // Otherwise resolveFlipFromPreset silently returns (false, false) and
            // the workflow runs against the unflipped macro on a scope that
            // requires a flip -- the exact failure 9f4fb96 fixed for entries
            // that DO carry source_microscope. Review finding H1.
            String src = openEntry.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE);
            if ((src == null || src.isBlank()) && isActiveScopeFlipNeeding()) {
                logger.error(
                        "validateAndFlipIfNeeded: entry '{}' has no SOURCE_MICROSCOPE metadata and the "
                                + "active microscope requires a flipped sibling; refusing to proceed.",
                        openEntry.getImageName());
                showMissingSourceMicroscopeDialog(openEntry.getImageName());
                // Cancel (not a RuntimeException): the missing-source dialog already informs
                // the operator. A RuntimeException here reaches the workflow's handleError,
                // which pops a SECOND "Workflow Error" dialog on top of this one -- two stacked
                // modal dialogs the user cannot dismiss (locks the app). CancellationException
                // makes handleError log-and-cleanup silently, leaving just this one dialog.
                future.completeExceptionally(new java.util.concurrent.CancellationException(
                        "source_microscope missing on a flip-needing scope"));
                return future;
            }
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
            // Re-mirror so the sibling reflects the base's current LIVE annotations
            // (including any drawn since the sibling was created) and re-runs don't
            // accumulate duplicates -- the open entry is still the base here.
            mirrorAnnotationsToSibling(gui, sibling, flipX, flipY);
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
            // Populate the freshly-created (annotation-free) sibling from the base's
            // live hierarchy. createFlippedDuplicate no longer transfers annotations.
            mirrorAnnotationsToSibling(gui, created, flipX, flipY);
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
     * Replace the flipped sibling's annotation set with a flip of the base's
     * <b>live</b> hierarchy.
     *
     * <p>The flipped sibling is a derived mirror of the base, not an independent
     * entry. Its annotations must be a deterministic function of the base's, so
     * this method <i>replaces</i> rather than appends: it clears the sibling's
     * existing annotations first, then transfers flip-transformed copies of the
     * base's annotations. Replacing is what keeps re-runs idempotent (no
     * accumulating duplicates) and lets an already-duplicated project self-heal
     * on the next run-from-base.
     *
     * <p>The caller passes the base's LIVE hierarchy
     * ({@code gui.getImageData().getHierarchy()}), not {@code baseEntry.readImageData()}
     * (the persisted {@code .qpdata} file) -- deliberate: the live hierarchy
     * carries annotations the user drew but never saved.
     *
     * <p>Must be called while the base is still the open entry -- i.e. before
     * {@link #switchOpenEntry}. Best-effort: a failure is logged, not thrown, so
     * a mirroring hiccup never aborts the workflow.
     *
     * @param baseHierarchy the base entry's live annotation hierarchy
     * @param siblingEntry the {@code (flipped ...)} entry to repopulate
     * @param flipX whether the sibling is X-flipped relative to the base
     * @param flipY whether the sibling is Y-flipped relative to the base
     * @param baseWidth base image width, for the flip transform
     * @param baseHeight base image height, for the flip transform
     */
    public static void mirrorAnnotationsToSibling(
            PathObjectHierarchy baseHierarchy,
            ProjectImageEntry<BufferedImage> siblingEntry,
            boolean flipX,
            boolean flipY,
            int baseWidth,
            int baseHeight) {
        if (baseHierarchy == null || siblingEntry == null) {
            logger.warn("mirrorAnnotationsToSibling: null base hierarchy or sibling entry; skipping");
            return;
        }
        try {
            ImageData<BufferedImage> sibData = siblingEntry.readImageData();
            PathObjectHierarchy sibHierarchy = sibData.getHierarchy();

            // Replace step: clear whatever annotations the sibling currently holds.
            List<PathObject> stale = new ArrayList<>(sibHierarchy.getAnnotationObjects());
            if (!stale.isEmpty()) {
                sibHierarchy.removeObjects(stale, false);
            }

            // Transfer flip-transformed copies of the base's annotations only
            // (detections / tiles are workflow scratch, not part of the mirror).
            AffineTransform flip = ForwardPropagationWorkflow.createFlip(flipX, flipY, baseWidth, baseHeight);
            List<PathObject> mirrored = new ArrayList<>();
            for (PathObject ann : baseHierarchy.getAnnotationObjects()) {
                if (ann.getROI() == null) {
                    continue;
                }
                PathObject copy = PathObjectTools.transformObject(ann, flip, true, true);
                if (copy != null) {
                    mirrored.add(copy);
                }
            }
            if (!mirrored.isEmpty()) {
                sibHierarchy.addObjects(mirrored);
            }
            sibHierarchy.fireHierarchyChangedEvent(sibHierarchy.getRootObject());
            siblingEntry.saveImageData(sibData);
            logger.info(
                    "mirrorAnnotationsToSibling: replaced {} stale with {} mirrored annotation(s) on '{}' "
                            + "(flipX={}, flipY={})",
                    stale.size(),
                    mirrored.size(),
                    siblingEntry.getImageName(),
                    flipX,
                    flipY);
        } catch (Exception e) {
            logger.warn("mirrorAnnotationsToSibling failed for '{}': {}", siblingEntry.getImageName(), e.getMessage());
        }
    }

    /**
     * Convenience overload: extracts the base's live hierarchy and dimensions from
     * the GUI's open image and delegates to
     * {@link #mirrorAnnotationsToSibling(PathObjectHierarchy, ProjectImageEntry, boolean, boolean, int, int)}.
     */
    private static void mirrorAnnotationsToSibling(
            QuPathGUI gui, ProjectImageEntry<BufferedImage> siblingEntry, boolean flipX, boolean flipY) {
        ImageData<BufferedImage> baseData = gui != null ? gui.getImageData() : null;
        if (baseData == null || baseData.getHierarchy() == null || baseData.getServer() == null) {
            logger.warn("mirrorAnnotationsToSibling: no open base image; skipping annotation mirror");
            return;
        }
        mirrorAnnotationsToSibling(
                baseData.getHierarchy(),
                siblingEntry,
                flipX,
                flipY,
                baseData.getServer().getWidth(),
                baseData.getServer().getHeight());
    }

    /**
     * Hard-cancel dialog for the missing-{@code source_microscope} gate
     * (review finding H1). FX-safe, blocking, OK-only.
     */
    private static void showMissingSourceMicroscopeDialog(String entryName) {
        String title = "Source Microscope Missing -- Workflow Cancelled";
        String header = "This entry has no source-scanner metadata.";
        StringBuilder body = new StringBuilder();
        body.append(String.format(
                "The open entry%n  '%s'%nhas no 'source_microscope' metadata, but the active%n"
                        + "microscope requires a flipped sibling for visual-UX during%n"
                        + "alignment (one or more saved presets for this scope have%n"
                        + "flipMacroX or flipMacroY set).%n%n",
                entryName));
        body.append("Without source_microscope we cannot resolve which preset's flip\n");
        body.append("state applies, and the workflow would silently run against the\n");
        body.append("unflipped macro -- targeting the wrong physical location.\n\n");
        body.append("To fix, set the source scanner via:\n");
        body.append("  Extensions -> QP Scope -> Stage Map -> pick the Source, or\n");
        body.append("  set it in the Multi-Slide assignment dialog, then re-run.\n\n");
        body.append("This workflow has been cancelled.");

        Runnable show = () -> {
            javafx.scene.control.Alert alert =
                    new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(body.toString());
            alert.getButtonTypes().setAll(javafx.scene.control.ButtonType.OK);
            alert.getDialogPane().setMinWidth(620);
            alert.getDialogPane().setPrefWidth(720);
            alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            javafx.scene.control.Label contentLabel =
                    (javafx.scene.control.Label) alert.getDialogPane().lookup(".content");
            if (contentLabel != null) {
                contentLabel.setWrapText(true);
                contentLabel.setMaxWidth(680);
                contentLabel.setStyle("-fx-font-family: 'monospace';");
            }
            javafx.scene.control.Label headerLabel =
                    (javafx.scene.control.Label) alert.getDialogPane().lookup(".header-panel .label");
            if (headerLabel != null) {
                headerLabel.setWrapText(true);
                headerLabel.setMaxWidth(660);
            }
            alert.showAndWait();
        };
        if (Platform.isFxApplicationThread()) {
            // Defer via runLater even on the FX thread: this can be reached while a
            // project-setup future completes inside an Animation/layout pulse, where
            // showAndWait is forbidden ("not allowed during animation or layout
            // processing"). runLater re-dispatches it into a clean pulse. Fire-and-forget
            // is fine -- the caller refuses to proceed regardless of the dialog result.
            Platform.runLater(show);
            return;
        }
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
            show.run();
            return null;
        });
        Platform.runLater(task);
        try {
            task.get();
        } catch (Exception e) {
            logger.warn("Failed to display missing-source dialog: {}", e.getMessage());
        }
    }

    /**
     * @return {@code true} when any saved preset for the active microscope has
     *     {@code flipMacroX} or {@code flipMacroY} set to true -- i.e. operating
     *     on a macro from some scanner on this scope requires building a flipped
     *     sibling for visual UX. Used as the "is the active scope flip-needing"
     *     predicate in the missing-{@code source_microscope} hard-cancel gate
     *     (review finding H1).
     */
    public static boolean isActiveScopeFlipNeeding() {
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            String activeMicroscope = mgr != null ? mgr.getMicroscopeName() : null;
            if (activeMicroscope == null || activeMicroscope.isBlank()) return false;
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isBlank()) return false;
            AffineTransformManager atm = new AffineTransformManager(new File(configPath).getParent());
            for (AffineTransformManager.TransformPreset preset : atm.getTransformsForMicroscope(activeMicroscope)) {
                if (!preset.hasFlipState()) continue;
                if (Boolean.TRUE.equals(preset.getFlipMacroX()) || Boolean.TRUE.equals(preset.getFlipMacroY())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            logger.warn("isActiveScopeFlipNeeding: probe failed: {}", e.getMessage());
            return false;
        }
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

        List<ProjectImageEntry<BufferedImage>> entries = project.getImageList();

        // Exact-name pass FIRST: the flipped sibling of THIS entry is
        // "<baseEntry name> <suffix>". This is essential for a rotated entry (e.g.
        // "X (rotated 270)"): its base_image is the ORIGINAL ("X"), so the loose
        // base_image match below would return the ORIGINAL's "X (flipped XY)" sibling and
        // silently drop the rotation. The exact-name match returns
        // "X (rotated 270) (flipped XY)" instead, preserving the rotation.
        String exact = baseEntry.getImageName() + " " + desiredSuffix;
        String exactStripped =
                qupath.lib.common.GeneralTools.stripExtension(baseEntry.getImageName()) + " " + desiredSuffix;
        for (ProjectImageEntry<BufferedImage> e : entries) {
            if (e == baseEntry) continue;
            String name = e.getImageName();
            if (name != null && (name.equals(exact) || name.equals(exactStripped))) {
                return e;
            }
        }

        // A rotated entry must NOT fall through to the loose base_image match -- that would
        // return the original's flipped sibling (wrong orientation). Return null so the
        // caller creates the rotated entry's OWN flipped sibling.
        if (isRotatedSiblingName(baseEntry.getImageName())) {
            return null;
        }

        String baseImage = ImageMetadataManager.getBaseImage(baseEntry);
        if (baseImage == null || baseImage.isBlank()) {
            baseImage = qupath.lib.common.GeneralTools.stripExtension(baseEntry.getImageName());
        }
        String baseImageFinal = baseImage;

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

    /** True if {@code name} contains a "(rotated N)" suffix from createRotatedDuplicate. */
    private static boolean isRotatedSiblingName(String name) {
        return name != null && name.contains("(rotated ");
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
     * {@code future} when the target entry's {@code ImageData} is
     * actually installed in the viewer. Always defers to a
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
     *
     * <p>Why subscribe to {@code imageDataProperty} (review finding M10):
     * {@code openImageEntry} kicks off image loading on a background
     * thread and returns before the new {@code ImageData} is installed.
     * Completing the future immediately after the call returns lets
     * downstream {@code .thenCompose} continuations read
     * {@code gui.getImageData()} and see the OLD entry's hierarchy.
     * The change listener waits for the install to commit to the
     * viewer before signalling. A 5-second timeout fallback prevents
     * deadlock if the install never fires (e.g. user cancelled a
     * checkSaveChanges dialog).
     */
    private static void switchOpenEntry(
            QuPathGUI gui, ProjectImageEntry<BufferedImage> targetEntry, CompletableFuture<Boolean> future) {
        Platform.runLater(() -> {
            String targetName = targetEntry.getImageName();
            // Listener completes the future when the viewer's ImageData
            // actually changes to a non-null value -- meaning openImageEntry's
            // background install committed. Match by name (the only stable
            // identifier we have without reading the entry's data again).
            // AtomicReference is used so the listener body can self-remove
            // without forward-reference issues.
            java.util.concurrent.atomic.AtomicReference<ChangeListener<ImageData<BufferedImage>>> listenerRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ChangeListener<ImageData<BufferedImage>> listener = (obs, oldImage, newImage) -> {
                if (newImage == null) return;
                String installedName =
                        newImage.getServer() != null && newImage.getServer().getMetadata() != null
                                ? newImage.getServer().getMetadata().getName()
                                : null;
                if (installedName == null || (targetName != null && !targetName.equals(installedName))) {
                    // Some other change (race with an unrelated open / refresh).
                    // Keep waiting for the right one; the timeout fallback
                    // below guarantees we don't deadlock.
                    return;
                }
                gui.getViewer().imageDataProperty().removeListener(listenerRef.get());
                logger.info("Switched open entry to '{}' (ImageData installed)", targetName);
                future.complete(true);
            };
            listenerRef.set(listener);
            try {
                gui.getViewer().imageDataProperty().addListener(listener);
                gui.refreshProject();
                gui.openImageEntry(targetEntry);
                logger.info("openImageEntry initiated for '{}'; waiting for ImageData install", targetName);
            } catch (Exception e) {
                gui.getViewer().imageDataProperty().removeListener(listener);
                logger.error("Failed to switch open entry to '{}'", targetName, e);
                future.completeExceptionally(e);
                return;
            }
            // Timeout fallback. If the install never fires (e.g. user cancelled
            // the checkSaveChanges dialog), the workflow chain would deadlock
            // waiting on this future. After 5 seconds, remove the listener and
            // resolve based on whether the open eventually succeeded.
            CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS)
                    .execute(() -> Platform.runLater(() -> {
                        if (future.isDone()) return;
                        gui.getViewer().imageDataProperty().removeListener(listenerRef.get());
                        ImageData<BufferedImage> current = gui.getImageData();
                        String currentName = current != null
                                        && current.getServer() != null
                                        && current.getServer().getMetadata() != null
                                ? current.getServer().getMetadata().getName()
                                : null;
                        if (targetName != null && targetName.equals(currentName)) {
                            // Install committed before listener fired (or listener missed it);
                            // accept the open.
                            logger.warn(
                                    "switchOpenEntry timeout: viewer already shows '{}'; completing future",
                                    currentName);
                            future.complete(true);
                        } else {
                            logger.error(
                                    "switchOpenEntry timeout: expected '{}' but viewer shows '{}'",
                                    targetName,
                                    currentName);
                            future.completeExceptionally(new java.util.concurrent.TimeoutException(
                                    "Image switch did not commit within 5s: expected '" + targetName
                                            + "', viewer shows '" + currentName + "'"));
                        }
                    }));
        });
    }
}
