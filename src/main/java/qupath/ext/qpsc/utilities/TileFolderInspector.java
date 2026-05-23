package qupath.ext.qpsc.utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only utilities for inspecting tile folders. Centralizes the pixel-size
 * detection logic used by both the standard
 * {@code StitchingRecoveryWorkflow} and the standalone
 * {@code MicroManagerStitchWorkflow}.
 */
public final class TileFolderInspector {

    private static final Logger logger = LoggerFactory.getLogger(TileFolderInspector.class);

    private TileFolderInspector() {}

    /**
     * Scan a folder (and its immediate subdirectories) for the first TIFF
     * file and read pixel size from its baseline-TIFF resolution metadata.
     *
     * @return pixel size in microns, or -1 if no usable resolution metadata
     *         is found
     */
    public static double detectPixelSizeFromFolder(File folder) {
        double ps = detectPixelSizeFromTiffsIn(folder.toPath());
        if (ps > 0) return ps;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder.toPath())) {
            for (Path subdir : stream) {
                if (Files.isDirectory(subdir)) {
                    ps = detectPixelSizeFromTiffsIn(subdir);
                    if (ps > 0) return ps;
                }
            }
        } catch (IOException e) {
            logger.debug("Error scanning subdirectories for pixel size: {}", e.getMessage());
        }
        logger.debug("No pixel size metadata found in tiles under {}", folder);
        return -1;
    }

    /** Find the first TIFF in {@code dir} and read its baseline-TIFF pixel size. */
    public static double detectPixelSizeFromTiffsIn(Path dir) {
        try (DirectoryStream<Path> tifs = Files.newDirectoryStream(dir, "*.tif*")) {
            for (Path tif : tifs) {
                double ps = readTiffPixelSize(tif.toFile());
                if (ps > 0) {
                    return ps;
                }
            }
        } catch (IOException e) {
            logger.debug("Error scanning for TIFFs in {}: {}", dir, e.getMessage());
        }
        return -1;
    }

    /**
     * Read pixel size in microns from a TIFF file's baseline X-resolution
     * tag, honoring the resolution-unit tag (inches vs centimeters).
     *
     * @return pixel size in microns, or -1 if metadata is missing or unusable
     */
    public static double readTiffPixelSize(File file) {
        try (FileInputStream fis = new FileInputStream(file);
                ImageInputStream iis = ImageIO.createImageInputStream(fis)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("TIFF");
            if (!readers.hasNext()) {
                return -1;
            }
            ImageReader reader = readers.next();
            reader.setInput(iis);
            IIOMetadata metadata = reader.getImageMetadata(0);
            TIFFDirectory tiffDir = TIFFDirectory.createFromMetadata(metadata);

            var xResField = tiffDir.getTIFFField(BaselineTIFFTagSet.TAG_X_RESOLUTION);
            if (xResField == null) {
                reader.dispose();
                return -1;
            }
            long[] rational = xResField.getAsRational(0);
            double xRes = rational[0] / (double) rational[1];
            if (xRes <= 0) {
                reader.dispose();
                return -1;
            }

            int resUnit = 2; // TIFF default: inches
            var unitField = tiffDir.getTIFFField(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT);
            if (unitField != null) {
                resUnit = unitField.getAsInt(0);
            }

            reader.dispose();

            if (resUnit == 3) {
                return 10000.0 / xRes;
            } else if (resUnit == 2) {
                return 25400.0 / xRes;
            } else {
                return -1;
            }
        } catch (Exception e) {
            logger.debug("Could not read pixel size from {}: {}", file.getName(), e.getMessage());
            return -1;
        }
    }
}
