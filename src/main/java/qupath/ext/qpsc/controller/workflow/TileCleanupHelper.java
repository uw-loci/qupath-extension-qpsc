package qupath.ext.qpsc.controller.workflow;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

/**
 * Helper for tile cleanup operations after acquisition.
 *
 * <p>This class manages the post-acquisition cleanup of temporary tile files
 * based on user preferences:
 * <ul>
 *   <li><b>Delete:</b> Remove all temporary tiles immediately</li>
 *   <li><b>Zip:</b> Archive tiles to a zip file then delete originals</li>
 *   <li><b>Keep:</b> Retain tiles for debugging or manual inspection</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class TileCleanupHelper {
    private static final Logger logger = LoggerFactory.getLogger(TileCleanupHelper.class);

    /**
     * Performs tile cleanup based on user preferences.
     *
     * <p>The cleanup method is determined by the tile handling preference:
     * <ul>
     *   <li>"Delete" - Removes all tiles, subdirectories, and the temporary folder</li>
     *   <li>"Zip" - Creates a zip archive then removes originals (only deletes if zip succeeds)</li>
     *   <li>Any other value - Keeps tiles in place</li>
     * </ul>
     *
     * @param tempTileDir Path to the temporary tile directory
     */
    public static void performCleanup(String tempTileDir) {
        performCleanup(tempTileDir, false);
    }

    /**
     * Performs tile cleanup with optional override of the "Keep" preference.
     *
     * <p>When {@code forceDelete} is {@code true}, the method ignores the user's
     * tile-handling preference and always deletes the tile directory. Used by
     * workflow error / cancel paths so a failed
     * acquisition does not leave temporary tile folders on disk even when the
     * user normally keeps them for diagnostics. "Zip" remains honored as a
     * better-than-delete fallback on the force path.
     *
     * @param tempTileDir Path to the temporary tile directory
     * @param forceDelete When true, fall through to delete even if the
     *     preference is "Keep" or any other non-Delete value (Zip still wins
     *     when successful)
     */
    public static void performCleanup(String tempTileDir, boolean forceDelete) {
        String handling = QPPreferenceDialog.getTileHandlingMethodProperty();

        logger.info("Performing tile cleanup - method: {}, force={}, path: {}", handling, forceDelete, tempTileDir);

        // Validate the path exists
        File tileDir = new File(tempTileDir);
        if (!tileDir.exists()) {
            logger.warn("Tile directory does not exist, nothing to clean up: {}", tempTileDir);
            return;
        }

        if (!tileDir.isDirectory()) {
            logger.warn("Path is not a directory: {}", tempTileDir);
            return;
        }

        if ("Zip".equals(handling)) {
            logger.info("Zipping tiles before deletion: {}", tempTileDir);
            boolean zipSuccess = TileProcessingUtilities.zipTilesAndMove(tempTileDir);
            if (zipSuccess) {
                logger.info("Zip successful, now deleting original tiles");
                TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
                logger.info("Zipped and archived temporary tiles from: {}", tempTileDir);
            } else if (forceDelete) {
                // Zip failed on the error path; delete anyway (no point keeping
                // corrupt tiles when the workflow already failed).
                logger.error("Zip failed during force-delete; falling back to delete");
                TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
            } else {
                logger.error("Zip failed - keeping original tiles for safety");
            }
        } else if ("Delete".equals(handling) || forceDelete) {
            logger.info("Deleting all tiles and subdirectories in: {}", tempTileDir);
            TileProcessingUtilities.deleteTilesAndFolder(tempTileDir);
        } else {
            logger.info("Keeping temporary tiles at: {}", tempTileDir);
        }
    }

    /**
     * Cleans up after a canceled or failed run without destroying acquired images.
     *
     * <p>A run's tile directory holds one folder per region. A region the server reported
     * COMPLETED has its stitch queued or running, and its tiles are finished data, so they are
     * left in place. A region that did not finish is deleted only when it holds no tile images
     * (its tile grid, or a refinement that never led to an acquisition). A region that stopped
     * part way holds real images -- on 2026-11-10 one stopped at the stage limit after 741
     * positions and ten hours, and its 2964 images were deleted -- so it is kept and returned,
     * and the caller asks the operator whether to stitch or delete it. With no finished and no
     * held region the whole run folder goes, as before.
     *
     * @param tempTileDir     the run's tile directory (one sub-folder per region)
     * @param regionNames     every region of the run, by annotation name
     * @param acquiredRegions the regions the server reported COMPLETED
     * @return the folders kept because they hold acquired images; empty when none
     */
    public static List<File> cleanupAfterFailedRun(
            String tempTileDir, Collection<String> regionNames, Set<String> acquiredRegions) {
        for (String region : acquiredRegions) {
            logger.info("Keeping tiles of finished region {} (stitched or stitching)", region);
        }
        List<File> held = new ArrayList<>();
        for (String dir : incompleteRegionDirs(tempTileDir, regionNames, acquiredRegions)) {
            int images = countTileImages(new File(dir).toPath());
            if (images > 0) {
                logger.warn("Keeping {} tile image(s) of unfinished region {} until the operator decides", images, dir);
                held.add(new File(dir));
            } else {
                performCleanup(dir, true);
            }
        }
        if (acquiredRegions.isEmpty() && held.isEmpty()) {
            performCleanup(tempTileDir, true);
        }
        return held;
    }

    /**
     * Tile images (.tif / .tiff) under a region folder: the folder, its angle or channel
     * folders, and z/t folders below those. Unreadable folders are skipped, not fatal.
     */
    public static int countTileImages(Path regionDir) {
        int[] count = {0};
        if (regionDir == null || !Files.isDirectory(regionDir)) {
            return 0;
        }
        try {
            Files.walkFileTree(
                    regionDir,
                    java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    4,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (attrs.isRegularFile() && (name.endsWith(".tif") || name.endsWith(".tiff"))) {
                                count[0]++;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            logger.warn("Could not count tile images in {}: {}", regionDir, e.getMessage());
        }
        return count[0];
    }

    /** The folders of the regions that did not finish, which exist on disk. */
    static List<String> incompleteRegionDirs(
            String tempTileDir, Collection<String> regionNames, Set<String> acquiredRegions) {
        List<String> dirs = new ArrayList<>();
        for (String region : regionNames) {
            if (region == null || region.isBlank() || acquiredRegions.contains(region)) {
                continue;
            }
            File dir = new File(tempTileDir, region);
            if (dir.isDirectory()) {
                dirs.add(dir.getPath());
            }
        }
        return dirs;
    }
}
