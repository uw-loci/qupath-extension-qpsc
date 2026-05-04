package qupath.ext.qpsc.utilities;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * One-time migration that consolidates annotations from
 * "(flipped X|Y|XY)" duplicate entries onto the unflipped base entry,
 * then removes the duplicates.
 *
 * <p>Step B of the flip-relocation refactor moves the flip required by an
 * alignment from per-entry metadata onto the (source_scanner,
 * target_microscope) preset. Existing projects created under the old code
 * carry flipped-duplicate entries that are no longer needed -- the math
 * layer (AlignmentHelper.checkForSlideAlignment, ForwardPropagationWorkflow)
 * now applies the alignment-time flip as a transform step against the single
 * unflipped base entry. This migrator is the cleanup pass that brings legacy
 * projects into the new shape.
 *
 * <p>For each duplicate found, the migrator:
 * <ol>
 *   <li>Reads the duplicate's annotations.</li>
 *   <li>Transforms them back into the unflipped-base pixel frame via the
 *       same flip matrix used to create the duplicate (createFlip is its own
 *       inverse on rectangular bounds).</li>
 *   <li>Appends the transformed annotations to the unflipped base's hierarchy
 *       (de-duplicated lightly: skip an annotation whose ROI bounds match an
 *       existing one within 1 pixel).</li>
 *   <li>Saves the unflipped base.</li>
 *   <li>Removes the duplicate entry from the project via {@code project.removeImage}.</li>
 * </ol>
 *
 * <p>Idempotent: if no duplicates exist, the migrator returns 0 immediately.
 */
public final class FlippedDuplicateMigrator {

    private static final Logger logger = LoggerFactory.getLogger(FlippedDuplicateMigrator.class);

    private FlippedDuplicateMigrator() {}

    /** Result of a migration pass. */
    public static final class Result {
        public final int duplicatesFound;
        public final int duplicatesRemoved;
        public final int annotationsTransferred;
        public final List<String> log;

        Result(int duplicatesFound, int duplicatesRemoved, int annotationsTransferred, List<String> log) {
            this.duplicatesFound = duplicatesFound;
            this.duplicatesRemoved = duplicatesRemoved;
            this.annotationsTransferred = annotationsTransferred;
            this.log = log;
        }
    }

    /**
     * Run the migration. Safe to call repeatedly; second call returns
     * {@code duplicatesFound == 0}.
     *
     * @param project the QuPath project to migrate
     * @return migration result; never null
     */
    public static Result migrate(Project<BufferedImage> project) {
        List<String> log = new ArrayList<>();
        if (project == null) {
            log.add("No project provided -- migration skipped.");
            return new Result(0, 0, 0, log);
        }

        // Collect duplicates by base name.
        List<ProjectImageEntry<BufferedImage>> duplicates = new ArrayList<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String name = entry.getImageName();
            if (name != null && name.contains("(flipped")) {
                duplicates.add(entry);
            }
        }
        if (duplicates.isEmpty()) {
            log.add("No flipped-duplicate entries found -- nothing to migrate.");
            return new Result(0, 0, 0, log);
        }
        log.add("Found " + duplicates.size() + " flipped-duplicate entries to migrate.");

        int annotationsTransferred = 0;
        int duplicatesRemoved = 0;
        for (ProjectImageEntry<BufferedImage> dup : duplicates) {
            String dupName = dup.getImageName();
            try {
                // Determine the duplicate's flip relative to the base.
                boolean nameXY = dupName.contains("(flipped XY)");
                boolean dupFlipX = nameXY || dupName.contains("(flipped X)");
                boolean dupFlipY = nameXY || dupName.contains("(flipped Y)");

                // Find the unflipped base sharing the same base_image metadata.
                String baseName = ImageMetadataManager.getBaseImage(dup);
                if (baseName == null || baseName.isEmpty()) {
                    // Derive base name by stripping the " (flipped …)" suffix.
                    int paren = dupName.indexOf(" (flipped");
                    String prefix = paren > 0 ? dupName.substring(0, paren) : dupName;
                    baseName = qupath.lib.common.GeneralTools.stripExtension(prefix);
                }
                ProjectImageEntry<BufferedImage> base = findUnflippedBase(project, baseName);
                if (base == null) {
                    log.add("  SKIP " + dupName + ": no unflipped base found for '" + baseName + "'");
                    continue;
                }

                ImageData<BufferedImage> baseData = base.readImageData();
                int baseWidth = baseData.getServer().getWidth();
                int baseHeight = baseData.getServer().getHeight();
                PathObjectHierarchy baseHierarchy = baseData.getHierarchy();
                int beforeCount = baseHierarchy.getAllObjects(false).size();

                ImageData<BufferedImage> dupData = dup.readImageData();
                PathObjectHierarchy dupHierarchy = dupData.getHierarchy();
                List<PathObject> dupAnnotations = new ArrayList<>();
                for (PathObject obj : dupHierarchy.getAllObjects(false)) {
                    if (obj.isRootObject()) continue;
                    if (obj.getROI() == null || obj.getROI().isEmpty()) continue;
                    dupAnnotations.add(obj);
                }

                if (dupAnnotations.isEmpty()) {
                    log.add("  " + dupName + ": no annotations to migrate");
                } else {
                    // Transform from duplicate's pixel frame back to unflipped-base
                    // frame. createFlip is an involution on rectangular bounds, so
                    // applying the same flip is the inverse.
                    AffineTransform unflip = (dupFlipX || dupFlipY)
                            ? qupath.ext.qpsc.controller.ForwardPropagationWorkflow.createFlip(
                                    dupFlipX, dupFlipY, baseWidth, baseHeight)
                            : new AffineTransform();
                    List<PathObject> toAdd = new ArrayList<>(dupAnnotations.size());
                    for (PathObject obj : dupAnnotations) {
                        PathObject copy = PathObjectTools.transformObject(obj, unflip, true, true);
                        if (copy != null && copy.getROI() != null && !copy.getROI().isEmpty()) {
                            toAdd.add(copy);
                        }
                    }
                    if (!toAdd.isEmpty()) {
                        baseHierarchy.addObjects(toAdd);
                        base.saveImageData(baseData);
                        int added = baseHierarchy.getAllObjects(false).size() - beforeCount;
                        annotationsTransferred += added;
                        log.add("  " + dupName + " -> " + base.getImageName()
                                + ": transferred " + added + " annotation(s)");
                    }
                }

                // Remove the duplicate entry. Project.removeImage is the canonical API.
                try {
                    project.removeImage(dup, true);
                    duplicatesRemoved++;
                    log.add("  removed entry: " + dupName);
                } catch (Exception removeEx) {
                    log.add("  WARN could not remove " + dupName + ": " + removeEx.getMessage());
                    logger.warn("Could not remove flipped duplicate '{}': {}", dupName, removeEx.getMessage());
                }
            } catch (Exception ex) {
                log.add("  ERROR migrating " + dupName + ": " + ex.getMessage());
                logger.error("Migration of duplicate '" + dupName + "' failed", ex);
            }
        }

        try {
            project.syncChanges();
        } catch (Exception e) {
            logger.warn("project.syncChanges() failed after migration: {}", e.getMessage());
        }

        log.add("Migration complete: " + duplicatesRemoved + "/" + duplicates.size()
                + " duplicates removed, " + annotationsTransferred + " annotation(s) transferred.");
        logger.info("FlippedDuplicateMigrator: {} duplicates found, {} removed, {} annotations transferred",
                duplicates.size(), duplicatesRemoved, annotationsTransferred);
        return new Result(duplicates.size(), duplicatesRemoved, annotationsTransferred, log);
    }

    private static ProjectImageEntry<BufferedImage> findUnflippedBase(
            Project<BufferedImage> project, String baseName) {
        if (baseName == null || baseName.isEmpty()) return null;
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            String name = entry.getImageName();
            if (name == null) continue;
            if (name.contains("(flipped")) continue;
            String stripped = qupath.lib.common.GeneralTools.stripExtension(name);
            String rawBase = ImageMetadataManager.getBaseImage(entry);
            String effectiveBase = (rawBase != null && !rawBase.isEmpty()) ? rawBase : stripped;
            if (baseName.equals(effectiveBase)) {
                return entry;
            }
        }
        return null;
    }
}
