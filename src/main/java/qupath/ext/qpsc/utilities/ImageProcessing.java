package qupath.ext.qpsc.utilities;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

public class ImageProcessing {
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessing.class);
    /**
     * Detects the data bounding box of an acquired full-resolution image using a
     * user-supplied pixel classifier. Generic across scanners and modalities -- the
     * classifier defines what counts as "background" so the inverse becomes the
     * data region.
     *
     * <p>Per-scanner / per-modality the right classifier differs:
     * <ul>
     *   <li><b>Ocus40 brightfield</b>: white-background classifier (e.g. {@code WhiteBackground.json})
     *       to exclude the asymmetric white padding added during pyramid generation.</li>
     *   <li><b>Aperio SVS brightfield</b>: same -- white-on-tissue classifier.</li>
     *   <li><b>Hamamatsu mrxs</b>: black-border classifier; tissue sits inside a dark frame.</li>
     *   <li><b>Widefield fluorescence</b>: dark-background classifier marks the background, the
     *       inverse is the lit tissue/signal region.</li>
     * </ul>
     *
     * <p>The user picks the appropriate classifier via the
     * {@code dataBoundsClassifierProperty} preference and swaps it when changing
     * sample class.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Run {@code createAnnotationsFromPixelClassifier} on the open image,
     *       producing one or more annotations classed as "Other" (= background).</li>
     *   <li>{@code makeInverseAnnotation()} -- the complement is the data region.</li>
     *   <li>Tag the data region as "Bounds" and return its bounding box.</li>
     * </ol>
     *
     * <p>If no background is detected (e.g. the image was cropped to data already),
     * the method returns the full image bounds.
     *
     * <p><b>Coordinate frame:</b> the method operates on the image as it exists in
     * QuPath, which means any flips applied during import have already been
     * performed. The returned bounds are in the flipped coordinate system, which
     * matches the green-box detection coordinates.
     *
     * @param gui The QuPath GUI instance with the acquired image open.
     * @param classifierPathStr Absolute path to the pixel classifier {@code .json}.
     * @return Rectangle of the data region in image pixels (flipped frame).
     *         Returns full image bounds when no background was detected.
     *         Returns null only if the classifier file is missing or an exception occurred.
     */
    /**
     * Why the "Data Bounds Classifier" preference cannot be used, or {@code null} when it names
     * an existing file. Microscope Alignment cannot save without it, so it checks this before
     * its first step rather than failing after all the tile work (2026-10-19).
     */
    private static void removeAddedAnnotations(
            qupath.lib.objects.hierarchy.PathObjectHierarchy hierarchy,
            java.util.Set<qupath.lib.objects.PathObject> before) {
        var added = hierarchy.getAnnotationObjects().stream()
                .filter(a -> !before.contains(a))
                .toList();
        if (!added.isEmpty()) {
            hierarchy.removeObjects(added, true);
            logger.info("Removed {} annotation(s) left by data bounds detection", added.size());
        }
    }

    public static String dataBoundsClassifierProblem(String classifierPathStr) {
        String where = "Set it in Edit > Preferences > QuPath SCope > Data Bounds Classifier: a pixel classifier "
                + "(.json) that separates the scanned data from the padding around it (for example "
                + "WhiteBackground.json for brightfield scans).";
        if (classifierPathStr == null || classifierPathStr.isBlank()) {
            return "No Data Bounds Classifier is set.\n\n" + where;
        }
        if (!Files.isRegularFile(Paths.get(classifierPathStr))) {
            return "The Data Bounds Classifier file was not found:\n" + classifierPathStr + "\n\n"
                    + "Copy the classifier to that location, or point the preference at where it is. " + where;
        }
        return null;
    }

    public static Rectangle detectImageDataBounds(QuPathGUI gui, String classifierPathStr) {
        logger.info("Detecting image data bounds via pixel classifier: {}", classifierPathStr);

        java.util.Set<qupath.lib.objects.PathObject> before = null;
        try {
            if (classifierPathStr == null || classifierPathStr.isBlank()) {
                logger.error("Data bounds classifier path is null or blank");
                return null;
            }
            Path classifierPath = Paths.get(classifierPathStr);
            if (!Files.exists(classifierPath)) {
                logger.error("Pixel classifier file not found at: {}", classifierPath);
                return null;
            }

            // Build the detection script
            // The script returns what the classifier produced and whether the inversion worked.
            // 2026-10-19: on two Liver slides this step reported "no whitespace" although the
            // same classifier marks their padding when run by hand, and the session log had
            // nothing to say which half failed -- QuPath's own warnings go to its main log.
            String script = String.format(
                    "resetSelection()\n"
                            + "createAnnotationsFromPixelClassifier(\"%s\", 1000000.0, 0, \"SELECT_NEW\")\n"
                            + "def cal = getCurrentServer().getPixelCalibration()\n"
                            + "def created = getSelectedObjects().collect{ (it.getPathClass() == null ? 'none' : it.getPathClass().toString())"
                            + " + ' ' + String.format('%%.2f', it.getROI().getScaledArea(cal.getPixelWidthMicrons(), cal.getPixelHeightMicrons()) / 1e6) + ' mm2' }\n"
                            + "whitespace = getAnnotationObjects().findAll{it.getPathClass().toString().contains(\"Other\")}\n"
                            + "def inverted = makeInverseAnnotation()\n"
                            + "removeObjects(whitespace, true)\n"
                            + "getSelectedObjects().each{it.setPathClass(getPathClass(\"Bounds\"))}\n"
                            + "return 'classifier regions: ' + created.size() + ' ' + created + '; inverse created: ' + inverted\n",
                    classifierPath.toString().replace("\\", "\\\\"));

            logger.debug("Running white background detection script");

            var hierarchy = gui.getViewer().getHierarchy();
            // Everything the script adds is scaffolding: remember what was there before so all
            // of it can be removed afterwards. Removing only the "Other" class and the Bounds
            // annotation left any other class the classifier produced on the image (2026-10-19).
            before = new java.util.HashSet<>(hierarchy.getAnnotationObjects());

            // Run the script
            Object summary = gui.runScript(null, script);
            logger.info(
                    "Data bounds classifier on '{}' ({}x{}): {}",
                    gui.getImageData().getServer().getMetadata().getName(),
                    gui.getImageData().getServer().getWidth(),
                    gui.getImageData().getServer().getHeight(),
                    summary);

            // Get image dimensions for fallback
            int imageWidth = gui.getImageData().getServer().getWidth();
            int imageHeight = gui.getImageData().getServer().getHeight();

            // Find the Bounds annotation
            var boundsAnnotation = hierarchy.getAnnotationObjects().stream()
                    .filter(ann -> ann.getPathClass() != null
                            && "Bounds".equals(ann.getPathClass().getName()))
                    .findFirst()
                    .orElse(null);

            Rectangle dataBounds;

            if (boundsAnnotation == null) {
                // No whitespace detected - the entire image is data with no padding
                logger.warn(
                        "No Bounds annotation from the data bounds classifier ({}); using the full image. "
                                + "If this scan has padding, the alignment will be off by its width.",
                        summary);
                dataBounds = new Rectangle(0, 0, imageWidth, imageHeight);
                logger.info("Using full image as data bounds: width={}, height={}", imageWidth, imageHeight);
            } else {
                // Whitespace detected - use the Bounds annotation
                ROI boundsROI = boundsAnnotation.getROI();
                dataBounds = new Rectangle(
                        (int) boundsROI.getBoundsX(),
                        (int) boundsROI.getBoundsY(),
                        (int) boundsROI.getBoundsWidth(),
                        (int) boundsROI.getBoundsHeight());

                logger.info(
                        "Detected data bounds: x={}, y={}, width={}, height={}",
                        dataBounds.x,
                        dataBounds.y,
                        dataBounds.width,
                        dataBounds.height);
                logger.info(
                        "Centroid in QuPath pixels of data bounds X {} Y {}",
                        boundsROI.getCentroidX(),
                        boundsROI.getCentroidY());

                // Calculate padding amounts for logging
                int leftPadding = dataBounds.x;
                int topPadding = dataBounds.y;
                int rightPadding = imageWidth - (dataBounds.x + dataBounds.width);
                int bottomPadding = imageHeight - (dataBounds.y + dataBounds.height);

                logger.info(
                        "Detected padding - Left: {}, Top: {}, Right: {}, Bottom: {}",
                        leftPadding,
                        topPadding,
                        rightPadding,
                        bottomPadding);
            }

            return dataBounds;

        } catch (Exception e) {
            logger.error("Error detecting Ocus40 data bounds", e);
            return null;
        } finally {
            if (before != null) {
                removeAddedAnnotations(gui.getViewer().getHierarchy(), before);
                QP.resetSelection();
            }
        }
    }
}
