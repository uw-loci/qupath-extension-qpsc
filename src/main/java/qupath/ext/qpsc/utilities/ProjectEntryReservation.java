package qupath.ext.qpsc.utilities;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.model.StitchingMetadata;
import qupath.ext.qpsc.utilities.StitchOutputPlanner.ReservedOutput;
import qupath.lib.gui.commands.ProjectCommands;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.servers.bioformats.BioFormatsServerBuilder;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Project entries reserved for a stitch job before it runs, and filled when its files land.
 *
 * <p>Each reserved entry is a real project entry with its full QPSC metadata (parent, offsets,
 * sample, modality, objective, angle, annotation, index, acquiring microscope) whose image is
 * the {@link StitchPlaceholderImage} and whose {@code qpsc_stitch_status} is {@code pending}.
 * The project browser shows it, with the puzzle thumbnail, from the moment the acquisition
 * hands the region to the stitching queue. When the stitched OME-TIFF lands, {@link #fill}
 * repoints the entry's builder at it (one call to {@code updateURIs}: no new entry, no
 * reordering), sets the image type and channel names the ordinary import would, saves the
 * image data and replaces the thumbnail. The entry is never opened in the viewer.
 *
 * <p>A reservation is a prediction. An output the job never fills (the name was predicted
 * wrong, the server did not write the derived folder, the stitch failed) is dropped by
 * {@link #dropUnfilled} when the job finishes, and the real file, if any, was imported the
 * ordinary way by the site that found no reservation for it.
 *
 * <p>{@link #reserve}, {@link #fill}, {@link #fail} and {@link #dropUnfilled} run on the FX
 * thread, like every other project mutation in the import path.
 */
public final class ProjectEntryReservation {

    private static final Logger logger = LoggerFactory.getLogger(ProjectEntryReservation.class);

    /** The key under which a reservation rides in {@code stitchParams}. */
    public static final String STITCH_PARAMS_KEY = "reservation";

    /** Metadata key: {@code pending}, {@code failed}; removed once filled. */
    public static final String STATUS_KEY = "qpsc_stitch_status";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_FAILED = "failed";

    private final Project<BufferedImage> project;
    private final URI placeholderUri;
    private final Map<String, String> entryIdsByKey = new LinkedHashMap<>();
    private final Set<String> filled = ConcurrentHashMap.newKeySet();
    private final Map<String, String> failed = new ConcurrentHashMap<>();

    private ProjectEntryReservation(Project<BufferedImage> project, URI placeholderUri) {
        this.project = project;
        this.placeholderUri = placeholderUri;
    }

    /**
     * Adds one placeholder entry per planned output. FX thread.
     *
     * @param project the project
     * @param plan the planned outputs
     * @param metadata the job's metadata (parent, offsets, sample, modality, objective, annotation, index, ...)
     * @param acquiredOnMicroscope the acquiring microscope's name, or null
     * @return the reservation, or null when nothing could be reserved (the job then imports the ordinary way)
     */
    public static ProjectEntryReservation reserve(
            Project<BufferedImage> project,
            List<ReservedOutput> plan,
            StitchingMetadata metadata,
            String acquiredOnMicroscope) {
        if (project == null || plan == null || plan.isEmpty() || project.getPath() == null) {
            return null;
        }
        Path projectDir = project.getPath().getParent();
        URI placeholderUri;
        try {
            placeholderUri = StitchPlaceholderImage.ensure(projectDir).toUri();
        } catch (IOException e) {
            logger.warn("Could not write the stitching placeholder; entries will be added when each stitch lands", e);
            return null;
        }
        ProjectEntryReservation r = new ProjectEntryReservation(project, placeholderUri);
        BufferedImage thumbnail = null;
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(placeholderUri)) {
            thumbnail = ProjectCommands.getThumbnailRGB(server);
        } catch (Exception e) {
            logger.debug("No thumbnail for the stitching placeholder: {}", e.getMessage());
        }
        for (ReservedOutput output : plan) {
            try {
                ServerBuilder<BufferedImage> builder = ImageServerBuilder.DefaultImageServerBuilder.createInstance(
                        BioFormatsServerBuilder.class, placeholderUri);
                ProjectImageEntry<BufferedImage> entry = project.addImage(builder);
                entry.setImageName(output.fileName());
                entry.setDescription("Stitching in progress");
                if (metadata != null) {
                    if (metadata.parentEntry == null
                            && metadata.baseImageOverride != null
                            && !metadata.baseImageOverride.isEmpty()) {
                        entry.getMetadata().put(ImageMetadataManager.BASE_IMAGE, metadata.baseImageOverride);
                    }
                    ImageMetadataManager.applyImageMetadata(
                            project,
                            entry,
                            metadata.parentEntry,
                            metadata.xOffset,
                            metadata.yOffset,
                            false,
                            false,
                            metadata.sampleName,
                            metadata.modality,
                            metadata.objective,
                            metadata.angle != null ? metadata.angle : output.angleSuffix(),
                            metadata.annotationName,
                            metadata.imageIndex,
                            metadata.detector,
                            metadata.sourceMicroscope);
                    if (metadata.modality != null
                            && metadata.modality.toLowerCase().startsWith("ppm")) {
                        String cal = qupath.ext.qpsc.modality.ppm.PPMPreferences.getActiveCalibrationPath();
                        if (cal != null && !cal.isEmpty()) {
                            ImageMetadataManager.setPPMCalibration(entry, cal, false);
                        }
                    }
                }
                if (acquiredOnMicroscope != null) {
                    ImageMetadataManager.setAcquiredOnMicroscope(entry, acquiredOnMicroscope);
                }
                entry.getMetadata().put(STATUS_KEY, STATUS_PENDING);
                if (thumbnail != null) {
                    try {
                        entry.setThumbnail(thumbnail);
                    } catch (IOException e) {
                        logger.debug(
                                "Could not set the placeholder thumbnail on {}: {}", output.fileName(), e.getMessage());
                    }
                }
                r.entryIdsByKey.put(output.key(), entry.getID());
                logger.info(
                        "Reserved project entry '{}' for stitch target '{}' ({})",
                        output.fileName(),
                        output.key(),
                        output.kind());
            } catch (Exception e) {
                logger.warn("Could not reserve a project entry for '{}': {}", output.fileName(), e.getMessage());
            }
        }
        if (r.entryIdsByKey.isEmpty()) {
            return null;
        }
        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.warn("Could not save the project after reserving entries: {}", e.getMessage());
        }
        return r;
    }

    /** True when an entry is reserved for this stitch target and not yet filled or failed. */
    public boolean has(String key) {
        return key != null && entryIdsByKey.containsKey(key) && !filled.contains(key) && !failed.containsKey(key);
    }

    /** The reserved entry for a key, or null. */
    public ProjectImageEntry<BufferedImage> entry(String key) {
        String id = entryIdsByKey.get(key);
        if (id == null) {
            return null;
        }
        for (ProjectImageEntry<BufferedImage> e : project.getImageList()) {
            if (id.equals(e.getID())) {
                return e;
            }
        }
        return null;
    }

    /**
     * Points the reserved entry at the stitched file and finishes it as the ordinary import
     * would. FX thread.
     *
     * @param key the stitch target
     * @param stitchedFile the file, already at its final name
     * @param handler the modality handler for image type, channel names and display ranges
     * @return the filled entry
     * @throws IOException when the file cannot be read or the entry cannot be saved
     */
    public ProjectImageEntry<BufferedImage> fill(String key, File stitchedFile, ModalityHandler handler)
            throws IOException {
        ProjectImageEntry<BufferedImage> entry = entry(key);
        if (entry == null) {
            throw new IOException("No reserved entry for stitch target '" + key + "'");
        }
        URI target = stitchedFile.toURI();
        if (!entry.updateURIs(Map.of(placeholderUri, target))) {
            throw new IOException("The reserved entry's URI could not be updated to " + target);
        }
        entry.setImageName(stitchedFile.getName());
        ImageData<BufferedImage> imageData = entry.readImageData();
        ImageServer<BufferedImage> server = imageData.getServer();
        imageData.setImageType(QPProjectFunctions.determineImageType(stitchedFile, server, imageData, handler));
        QPProjectFunctions.applyChannelNameOverride(imageData, stitchedFile, handler);
        QPProjectFunctions.applyDisplayRangeOverride(imageData, stitchedFile, handler);
        entry.saveImageData(imageData);
        try {
            entry.setThumbnail(null);
            entry.setThumbnail(ProjectCommands.getThumbnailRGB(server));
        } catch (Exception e) {
            logger.debug("Could not replace the thumbnail of {}: {}", stitchedFile.getName(), e.getMessage());
        }
        entry.getMetadata().remove(STATUS_KEY);
        entry.setDescription("");
        filled.add(key);
        project.syncChanges();
        logger.info("Filled reserved entry '{}' from stitch target '{}'", stitchedFile.getName(), key);
        return entry;
    }

    /** Marks the reserved entry failed with {@code reason}; the entry stays, so the operator sees it. FX thread. */
    public void fail(String key, String reason) {
        ProjectImageEntry<BufferedImage> entry = entry(key);
        if (entry == null || filled.contains(key)) {
            return;
        }
        entry.getMetadata().put(STATUS_KEY, STATUS_FAILED);
        entry.setDescription("Stitching failed: " + (reason == null ? "see the log" : reason));
        failed.put(key, reason == null ? "" : reason);
        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.warn("Could not save the project after marking '{}' failed: {}", key, e.getMessage());
        }
    }

    /** Marks every entry not yet filled as failed with {@code reason}. Runs on the FX thread, from any thread. */
    public void failUnfilled(String reason) {
        onFx(() -> failUnfilledNow(reason));
    }

    /** {@link #failUnfilled} on the calling thread. */
    void failUnfilledNow(String reason) {
        for (String key : entryIdsByKey.keySet()) {
            if (!filled.contains(key)) {
                fail(key, reason);
            }
        }
    }

    private static void onFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * Removes every reserved entry that was neither filled nor marked failed: the prediction
     * was wrong or the output never existed. Any real file was imported the ordinary way by
     * the site that found no reservation. Runs on the FX thread, from any thread.
     *
     * @param why for the log
     */
    public void dropUnfilled(String why) {
        onFx(() -> dropUnfilledNow(why));
    }

    /** {@link #dropUnfilled} on the calling thread. */
    int dropUnfilledNow(String why) {
        int dropped = 0;
        for (Map.Entry<String, String> e : entryIdsByKey.entrySet()) {
            String key = e.getKey();
            if (filled.contains(key) || failed.containsKey(key)) {
                continue;
            }
            ProjectImageEntry<BufferedImage> entry = entry(key);
            if (entry != null) {
                logger.info(
                        "Dropping unfilled reserved entry '{}' for target '{}' ({})", entry.getImageName(), key, why);
                project.removeImage(entry, true);
                dropped++;
            }
        }
        if (dropped > 0) {
            try {
                project.syncChanges();
            } catch (IOException ex) {
                logger.warn("Could not save the project after dropping reserved entries: {}", ex.getMessage());
            }
        }
        return dropped;
    }

    /** Keys reserved, in plan order. */
    public Set<String> keys() {
        return entryIdsByKey.keySet();
    }

    public boolean isFilled(String key) {
        return filled.contains(key);
    }
}
