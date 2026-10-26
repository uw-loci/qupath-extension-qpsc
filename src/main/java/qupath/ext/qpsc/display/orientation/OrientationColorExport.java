package qupath.ext.qpsc.display.orientation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.assembly.direct.DirectTiffOutputWriter;
import qupath.ext.basicstitching.utilities.UtilityFunctions;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Writes the orientation colour image (what {@link OrientationColorViewWindow} shows) to a
 * pyramidal RGB OME-TIFF beside the stitched images, and adds it to the project with the
 * colour settings as entry metadata.
 *
 * <p>The file is written tile by tile through tiles-to-pyramid's {@link DirectTiffOutputWriter},
 * the same writer the stitcher uses, so a whole slide costs minutes and little memory. The
 * writer records no custom tags, so the settings live in the entry's metadata (and in the
 * file name), not in the file.
 */
public final class OrientationColorExport {

    private static final Logger logger = LoggerFactory.getLogger(OrientationColorExport.class);

    /** Entry metadata key prefix for the settings the file was coloured with. */
    public static final String METADATA_PREFIX = "orientation_colour.";

    static final int TILE_SIZE = 512;

    private OrientationColorExport() {}

    /**
     * The file stem for an export: the orientation or retardance image's stem with its role
     * replaced by {@code orientation_colour}, then the map and the ceiling (or {@code hueonly}).
     * {@code slide_orientation.ome.tif} with the uniform map at 20 nm gives
     * {@code slide_orientation_colour_uniform_20nm}.
     */
    public static String outputStem(String imageName, OrientationColorRenderer.Settings settings) {
        String stem = GeneralTools.stripExtension(imageName);
        String lower = stem.toLowerCase(Locale.ROOT);
        int o = lower.lastIndexOf("orientation");
        int r = lower.lastIndexOf("retardance");
        if (o >= 0 || r >= 0) {
            int at = Math.max(o, r);
            int len = o > r ? "orientation".length() : "retardance".length();
            stem = stem.substring(0, at) + "orientation_colour" + stem.substring(at + len);
        } else {
            stem = stem + "_orientation_colour";
        }
        String brightness = settings.colourOnly()
                ? "hueonly"
                : String.format(Locale.ROOT, "%snm", trimNumber(settings.ceilingNm()));
        return stem + "_" + settings.kind().configName() + "_" + brightness;
    }

    /**
     * Where the export goes: the folder of the first file-backed image behind {@code server}
     * (the orientation image's own folder), or the project folder when none is file-backed.
     */
    public static Path outputDirectory(ImageServer<BufferedImage> server, Project<BufferedImage> project) {
        for (URI uri : server.getURIs()) {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path parent = Paths.get(uri).getParent();
                if (parent != null) {
                    return parent;
                }
            }
        }
        Path projectPath = project == null ? null : project.getPath();
        if (projectPath != null) {
            return Files.isDirectory(projectPath) ? projectPath : projectPath.getParent();
        }
        throw new IllegalStateException(
                "No folder to export into: the image is not file-backed and there is no project");
    }

    /**
     * Writes the colour image for {@code pair} (an orientation + retardance server, or
     * orientation alone) to {@code directory/stem.ome.tif}, choosing a fresh name if that
     * file exists. Blocking; call off the FX thread.
     *
     * @param progress 0..1 as tiles are written; may be null
     * @return the file written
     * @throws IOException if the write fails; a partial file is removed first
     */
    public static Path write(
            ImageServer<BufferedImage> pair,
            OrientationColorRenderer.Settings settings,
            Path directory,
            String stem,
            OMEPyramidWriter.CompressionType compression,
            Consumer<Double> progress)
            throws IOException {
        Files.createDirectories(directory);
        Path target = Paths.get(UtilityFunctions.getUniqueFilePath(
                directory.resolve(stem + ".ome.tif").toString()));
        logger.info("Writing orientation colour image {} ({})", target, settings);
        long start = System.currentTimeMillis();
        try (ImageServer<BufferedImage> colour = new OrientationColorExportServer(pair, settings)) {
            DirectTiffOutputWriter.write(colour, target.toString(), compression, TILE_SIZE, 1.0, progress);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanup) {
                logger.warn("Could not remove the partial export {}: {}", target, cleanup.getMessage());
            }
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        logger.info("Wrote {} in {} s", target.getFileName(), (System.currentTimeMillis() - start) / 1000);
        return target;
    }

    /**
     * Adds a written colour image to the project. It inherits the source entry's metadata
     * (same acquisition, same frame) and gains the colour settings under
     * {@link #METADATA_PREFIX}.
     *
     * @param source the entry the export was made from (orientation or retardance)
     * @return the new entry
     */
    public static ProjectImageEntry<BufferedImage> addToProject(
            Project<BufferedImage> project,
            Path file,
            ProjectImageEntry<BufferedImage> source,
            OrientationColorRenderer.Settings settings,
            String pairDescription)
            throws IOException {
        ImageServer<BufferedImage> server = ImageServers.buildServer(file.toUri());
        ProjectImageEntry<BufferedImage> entry;
        try {
            entry = project.addImage(server.getBuilder());
            entry.setImageName(file.getFileName().toString());
            ImageData<BufferedImage> imageData = entry.readImageData();
            imageData.setImageType(ImageData.ImageType.OTHER);
            entry.saveImageData(imageData);
        } finally {
            try {
                server.close();
            } catch (Exception e) {
                logger.debug("Closing the exported image failed: {}", e.getMessage());
            }
        }
        if (source != null) {
            for (Map.Entry<String, String> m : source.getMetadata().entrySet()) {
                entry.getMetadata().put(m.getKey(), m.getValue());
            }
        }
        for (Map.Entry<String, String> m : metadata(settings, pairDescription).entrySet()) {
            entry.getMetadata().put(m.getKey(), m.getValue());
        }
        project.syncChanges();
        return entry;
    }

    /** The colour settings as entry metadata. */
    static Map<String, String> metadata(OrientationColorRenderer.Settings settings, String pairDescription) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(METADATA_PREFIX + "display_only", "true; measure on the retardance and orientation images");
        m.put(METADATA_PREFIX + "map", settings.kind().configName());
        m.put(METADATA_PREFIX + "hue", "slow-axis orientation, one colour cycle over 0..180 deg");
        m.put(
                METADATA_PREFIX + "brightness",
                settings.colourOnly()
                        ? "none; full brightness everywhere"
                        : "retardance, full brightness at " + trimNumber(settings.ceilingNm()) + " nm");
        if (settings.ppm() != null) {
            m.put(METADATA_PREFIX + "ppm_calibration", settings.ppm().source());
        }
        m.put(
                METADATA_PREFIX + "registration",
                String.format(Locale.ROOT, "angle x %d + %s deg", settings.sign(), trimNumber(settings.offsetDeg())));
        if (pairDescription != null) {
            m.put(METADATA_PREFIX + "source_images", pairDescription);
        }
        return m;
    }

    private static String trimNumber(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
