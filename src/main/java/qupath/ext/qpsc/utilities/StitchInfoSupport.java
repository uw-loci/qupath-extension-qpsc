package qupath.ext.qpsc.utilities;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.workflow.StitchInfoFile;
import qupath.ext.qpsc.model.StitchingMetadata;

/**
 * QPSC's part of the stitch record that tiles-to-pyramid writes beside every stitched image
 * ({@code <image stem>.stitch-info.txt}): keep it attached to the image through QPSC's renames, and
 * add what only QPSC knows -- the acquisition.
 *
 * <p>Every method swallows failures, including {@link LinkageError}: the record describes an image,
 * it is never a reason to fail producing one, and a tiles-to-pyramid older than 0.7.1 has no
 * {@code StitchInfoFile} at all -- the image then simply has no record.
 */
public final class StitchInfoSupport {

    private static final Logger logger = LoggerFactory.getLogger(StitchInfoSupport.class);

    /** Files the acquisition leaves beside the tiles that belong in the record, by name prefix. */
    private static final String[] ACQUISITION_FILES = {
        "acquisition_command_",
        "acquisition_metadata",
        "MMproperties",
        "autofocus_diagnostic_",
        "tile_manifest",
        "tile_measurements",
        "image_positions_metadata",
        "TileConfiguration_Stage",
        "TileRegistration.txt"
    };

    private StitchInfoSupport() {}

    /**
     * Move the record to follow its image after a rename.
     *
     * @param from the image's old path
     * @param to the image's new path
     */
    public static void moveWith(File from, File to) {
        try {
            StitchInfoFile.moveWith(from.toPath(), to.toPath());
        } catch (LinkageError | RuntimeException e) {
            logger.debug("Stitch record not moved ({}): {}", from.getName(), e.toString());
        }
    }

    /**
     * Append the {@code [acquisition]} section: what was acquired, with which hardware and
     * settings, where the acquisition's own files are, and the acquisition command itself.
     *
     * @param image the stitched image, at its final name
     * @param metadata the stitch metadata, or null when the caller has none (recovery)
     * @param tileFolder folder the tiles were stitched from; a temporary isolation folder is
     *     resolved to the acquisition folder it came from
     * @param extra additional entries the caller knows (e.g. angle, how it was produced); may be empty
     */
    public static void appendAcquisition(
            String image, StitchingMetadata metadata, File tileFolder, Map<String, String> extra) {
        if (image == null) {
            return;
        }
        try {
            Map<String, String> entries = new LinkedHashMap<>();
            if (metadata != null) {
                put(entries, "sample", metadata.sampleName);
                put(entries, "modality", metadata.modality);
                put(entries, "objective", metadata.objective);
                put(entries, "detector", metadata.detector);
                put(entries, "microscope", metadata.sourceMicroscope);
                put(entries, "region", metadata.annotationName);
                put(entries, "angle / channel", metadata.angle);
                put(entries, "image index", metadata.imageIndex);
                if (metadata.parentEntry != null) {
                    put(entries, "parent image", metadata.parentEntry.getImageName());
                }
                put(entries, "offset in parent (um)", metadata.xOffset + ", " + metadata.yOffset);
                put(entries, "flipped relative to parent (X, Y)", metadata.flipX + ", " + metadata.flipY);
                if (metadata.stageBoundsX1Um != null) {
                    put(
                            entries,
                            "stage bounds (um)",
                            metadata.stageBoundsX1Um + ", " + metadata.stageBoundsY1Um + " to "
                                    + metadata.stageBoundsX2Um + ", " + metadata.stageBoundsY2Um);
                }
                if (metadata.fovXUm != null) {
                    put(entries, "field of view (um)", metadata.fovXUm + " x " + metadata.fovYUm);
                }
            }
            entries.putAll(extra);
            try {
                StageImageTransform t = StageImageTransform.current();
                boolean[] flips = t.stitcherFlipFlags();
                put(entries, "stage/camera transform", t.toString());
                put(entries, "stitcher axis negation (X, Y)", flips[0] + ", " + flips[1]);
            } catch (RuntimeException | LinkageError e) {
                // Preference-backed; must not cost the rest of the section when unavailable.
                logger.debug("Stage/camera transform unavailable for the stitch record: {}", e.toString());
            }
            String version = qupath.lib.common.GeneralTools.getPackageVersion(StitchInfoSupport.class);
            put(entries, "QPSC", version != null ? version : "dev");

            File acquisitionDir = acquisitionFolder(tileFolder);
            List<String> lines = new ArrayList<>();
            if (acquisitionDir != null) {
                put(entries, "acquisition folder", acquisitionDir.getAbsolutePath());
                File[] files = acquisitionDir.listFiles(File::isFile);
                if (files != null) {
                    Arrays.sort(files);
                    List<String> recorded = new ArrayList<>();
                    for (File f : files) {
                        for (String prefix : ACQUISITION_FILES) {
                            if (f.getName().startsWith(prefix)) {
                                recorded.add(f.getName());
                                break;
                            }
                        }
                    }
                    if (!recorded.isEmpty()) {
                        put(entries, "acquisition files", String.join(", ", recorded));
                    }
                    lines.addAll(acquisitionCommand(files));
                }
            }
            StitchInfoFile.append(Path.of(image), List.of(new StitchInfoFile.Section("acquisition", entries, lines)));
        } catch (LinkageError | RuntimeException e) {
            logger.debug("Acquisition section not added to stitch record for {}: {}", image, e.toString());
        }
    }

    /**
     * The folder the acquisition wrote, from the folder a stitch read. QPSC stitches each
     * angle/channel out of a temporary {@code _temp_*} directory inside the acquisition folder, so
     * walk up past those.
     */
    static File acquisitionFolder(File tileFolder) {
        File dir = tileFolder;
        while (dir != null && dir.getName().startsWith("_temp_")) {
            dir = dir.getParentFile();
        }
        return dir;
    }

    /**
     * The newest acquisition command, verbatim minus its comment header: every flag the server was
     * started with (channels, exposures, focus channel, autofocus, white balance, Z-stack...).
     */
    private static List<String> acquisitionCommand(File[] files) {
        File newest = null;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("acquisition_command_")
                    && n.endsWith(".txt")
                    && (newest == null || n.compareTo(newest.getName()) > 0)) {
                newest = f;
            }
        }
        if (newest == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("acquisition command (" + newest.getName() + "):");
        try {
            for (String line : Files.readAllLines(newest.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add("    " + t);
                }
            }
        } catch (IOException e) {
            out.add("    (unreadable: " + e.getMessage() + ")");
        }
        return out;
    }

    private static void put(Map<String, String> entries, String key, Object value) {
        if (value != null) {
            entries.put(key, String.valueOf(value));
        }
    }
}
