package qupath.ext.qpsc.controller;

import com.google.gson.GsonBuilder;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.AffineTransformManager;
import qupath.ext.qpsc.utilities.AffineTransformManager.SavedAlignment;
import qupath.ext.qpsc.utilities.ImageFlipHelper;
import qupath.ext.qpsc.utilities.ImageMetadataManager;
import qupath.ext.qpsc.utilities.NgffSceneBuilder;
import qupath.ext.qpsc.utilities.VersionInfo;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * "Export Spatial Relationships (OME-NGFF 0.6)" utility.
 *
 * <p>Writes the project's image-to-stage and image-to-image transforms as OME-NGFF 0.6
 * {@code scene} metadata, one JSON file per connected group, to {@code <project>/ngff/}.
 * Nothing about acquisition or propagation reads these files; they describe the project for
 * other tools and for the record.
 *
 * <p><b>No new transform math lives here.</b> Every transform comes from the function the
 * rest of QPSC already uses for the same relationship:
 *
 * <ul>
 *   <li>Sub-frame alignment JSON (stitched bounding-box output): the saved transform as-is.
 *   <li>Annotation sub-acquisition: {@link ForwardPropagationWorkflow#buildSubToStageTransform},
 *       the half-FOV-corrected offset propagation uses.
 *   <li>Bounding-box entry with no JSON: {@link ImageMetadataManager#buildBoundingBoxPixelToStageTransform},
 *       the tier-3 alignment fallback.
 *   <li>Macro alignment JSON: {@link ForwardPropagationWorkflow#createFlip} into the saved frame,
 *       then the saved transform -- the same chain forward propagation builds.
 *   <li>"(Camera View)" companion: {@link ForwardPropagationWorkflow#createFlip} of the parity
 *       difference to its base.
 * </ul>
 *
 * <p>Where the recorded state is not enough to place an image exactly, the image is skipped
 * with the reason written into the file, rather than exported with a guess. Rotated entries
 * are the main such case today.
 */
public class ExportSpatialSceneWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ExportSpatialSceneWorkflow.class);

    static final String OUTPUT_DIR = "ngff";
    static final String FILE_PREFIX = "qpsc_scene_";

    private ExportSpatialSceneWorkflow() {}

    public static void run(QuPathGUI qupath) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("Export Spatial Relationships", "No project is open. Open a project first.");
            return;
        }
        Thread worker = new Thread(
                () -> {
                    try {
                        Result result = export(project);
                        Platform.runLater(() -> showResult(result));
                    } catch (Exception e) {
                        logger.error("NGFF scene export failed", e);
                        Platform.runLater(() -> Dialogs.showErrorMessage(
                                "Export Spatial Relationships", "Export failed: " + e.getMessage()));
                    }
                },
                "qpsc-ngff-scene-export");
        worker.setDaemon(true);
        worker.start();
    }

    record Result(Path outputDir, List<Path> files, int transformCount, List<Map<String, String>> skipped) {}

    static Result export(Project<BufferedImage> project) throws IOException {
        File projectDir = project.getPath().toFile().getParentFile();
        List<ProjectImageEntry<BufferedImage>> entries = project.getImageList();
        Collector c = new Collector(entries);

        List<SavedAlignment> alignments = AffineTransformManager.listSavedAlignments(projectDir);
        for (SavedAlignment a : alignments) {
            String frame = a.result().getPixelFrame();
            if (a.microscope() == null) {
                c.builder.skip(
                        a.sampleName(),
                        "Alignment file " + a.file().getName() + " records no microscope, so its stage is unknown.");
                continue;
            }
            if (AffineTransformManager.PIXEL_FRAME_SUB.equals(frame)) {
                c.addSubFrameAlignment(a, projectDir);
            }
        }
        c.addEntryMetadataTransforms();
        for (SavedAlignment a : alignments) {
            if (a.microscope() != null
                    && AffineTransformManager.PIXEL_FRAME_MACRO.equals(
                            a.result().getPixelFrame())) {
                c.addMacroAlignment(a, projectDir);
            }
        }
        c.addCameraViewCompanions();
        c.skipUnplaced();

        Path outDir = projectDir.toPath().resolve(OUTPUT_DIR);
        Files.createDirectories(outDir);
        try (Stream<Path> old = Files.list(outDir)) {
            for (Path p : old.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(FILE_PREFIX) && n.endsWith(".json")) Files.delete(p);
            }
        }

        List<Path> written = new ArrayList<>();
        String generatedBy = "QPSC " + VersionInfo.getQpscVersion();
        for (NgffSceneBuilder.Scene scene :
                c.builder.build(generatedBy, OffsetDateTime.now().toString())) {
            Path file = outDir.resolve(FILE_PREFIX + scene.label().replaceAll("[^A-Za-z0-9._+-]", "_") + ".json");
            String json = new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .create()
                    .toJson(scene.document());
            Files.writeString(file, json, StandardCharsets.UTF_8);
            written.add(file);
            logger.info(
                    "Wrote NGFF 0.6 scene {} ({} stage frame(s))",
                    file,
                    scene.stageNames().size());
        }
        return new Result(outDir, written, c.builder.transformCount(), c.builder.getSkipped());
    }

    /** Gathers transforms from project state into a {@link NgffSceneBuilder}. */
    private static final class Collector {
        final NgffSceneBuilder builder = new NgffSceneBuilder();
        final List<ProjectImageEntry<BufferedImage>> entries;
        final Map<ProjectImageEntry<BufferedImage>, String> systems = new HashMap<>();
        final Map<ProjectImageEntry<BufferedImage>, ServerFacts> facts = new HashMap<>();
        final Set<ProjectImageEntry<BufferedImage>> anchored = new HashSet<>();

        Collector(List<ProjectImageEntry<BufferedImage>> entries) {
            this.entries = entries;
        }

        record ServerFacts(int width, int height, double pixelSizeMicrons) {}

        void addSubFrameAlignment(SavedAlignment a, File projectDir) {
            ProjectImageEntry<BufferedImage> entry = findByStrippedName(a.sampleName(), false);
            if (entry == null) {
                builder.skip(
                        a.sampleName(), "Sub-frame alignment " + a.file().getName() + " matches no project image.");
                return;
            }
            if (anchored.contains(entry)) return;
            Map<String, Object> prov = provenance("sub-frame alignment JSON", a.microscope());
            prov.put("file", relative(projectDir, a.file()));
            addToStage(entry, a.microscope(), List.of(a.result().getTransform()), prov);
        }

        void addEntryMetadataTransforms() {
            for (ProjectImageEntry<BufferedImage> entry : entries) {
                if (anchored.contains(entry) || ImageMetadataManager.isCameraView(entry)) continue;
                boolean isSub = ImageFlipHelper.isSubAcquisitionEntry(entry);
                boolean hasBounds = ImageMetadataManager.getBoundingBoxStageBounds(entry) != null;
                if (!isSub && !hasBounds) continue;

                String scope = ImageMetadataManager.getAcquiredOnMicroscope(entry);
                if (scope == null || scope.isBlank()) {
                    builder.skip(
                            entry.getImageName(),
                            "Acquired image has no acquired_on_microscope metadata, so its stage is unknown.");
                    continue;
                }
                ServerFacts f = facts(entry);
                if (f == null) {
                    builder.skip(entry.getImageName(), "Could not open the image to read its size.");
                    continue;
                }

                if (isSub) {
                    double[] fov = ForwardPropagationWorkflow.resolveFovForEntry(entry);
                    if (fov == null) {
                        builder.skip(
                                entry.getImageName(),
                                "Field of view unknown (no fov_x_um/fov_y_um metadata and no config match), "
                                        + "so the half-FOV offset correction cannot be applied.");
                        continue;
                    }
                    if (!(f.pixelSizeMicrons > 0)) {
                        builder.skip(entry.getImageName(), "Image has no pixel size.");
                        continue;
                    }
                    AffineTransform t = ForwardPropagationWorkflow.buildSubToStageTransform(
                            f.pixelSizeMicrons, ImageMetadataManager.getXYOffset(entry), entry);
                    Map<String, Object> prov = provenance("xy_offset metadata with half-FOV correction", scope);
                    prov.put("fovMicrons", List.of(fov[0], fov[1]));
                    addToStage(entry, scope, List.of(t), prov);
                } else {
                    AffineTransform t =
                            ImageMetadataManager.buildBoundingBoxPixelToStageTransform(entry, f.width, f.height);
                    if (t == null) {
                        builder.skip(entry.getImageName(), "Stage bounds metadata is degenerate.");
                        continue;
                    }
                    addToStage(entry, scope, List.of(t), provenance("stage bounds metadata", scope));
                }
            }
        }

        void addMacroAlignment(SavedAlignment a, File projectDir) {
            ProjectImageEntry<BufferedImage> base = findMacroBase(a.sampleName());
            if (base == null) {
                builder.skip(
                        a.sampleName(), "Alignment " + a.file().getName() + " matches no base image in the project.");
                return;
            }
            // A per-slide JSON saved on a rotated working entry is in that rotated frame; the
            // base -> working-entry step is then a rotation plus flip we do not export yet.
            for (ProjectImageEntry<BufferedImage> e : entries) {
                if (e != base
                        && a.sampleName().equals(ImageMetadataManager.getBaseImage(e))
                        && ImageMetadataManager.getRotationDegrees(e) != 0) {
                    builder.skip(
                            base.getImageName(),
                            "Alignment " + a.file().getName() + " belongs to a slide with a rotated working entry ("
                                    + e.getImageName() + "); rotated frames are not exported yet.");
                    return;
                }
            }
            AffineTransformManager.SlideAlignmentResult r = a.result();
            boolean fx = Boolean.TRUE.equals(r.getFlipMacroX());
            boolean fy = Boolean.TRUE.equals(r.getFlipMacroY());
            List<AffineTransform> steps = new ArrayList<>();
            if (fx || fy) {
                ServerFacts f = facts(base);
                if (f == null) {
                    builder.skip(base.getImageName(), "Could not open the image to read its size for the flip.");
                    return;
                }
                steps.add(ForwardPropagationWorkflow.createFlip(fx, fy, f.width, f.height));
            }
            steps.add(r.getTransform());

            Map<String, Object> prov = provenance("macro alignment JSON", a.microscope());
            prov.put("file", relative(projectDir, a.file()));
            prov.put("flipMacroX", fx);
            prov.put("flipMacroY", fy);
            if (r.getObjective() != null) prov.put("objective", r.getObjective());
            addToStage(base, a.microscope(), steps, prov);
        }

        void addCameraViewCompanions() {
            for (ProjectImageEntry<BufferedImage> companion : entries) {
                if (!ImageMetadataManager.isCameraView(companion)) continue;
                String baseName = ImageMetadataManager.getBaseImage(companion);
                ProjectImageEntry<BufferedImage> base = baseName == null ? null : findMacroBase(baseName);
                if (base == null || !systems.containsKey(base)) {
                    builder.skip(
                            companion.getImageName(), "Camera View companion's base image has no stage transform.");
                    continue;
                }
                if (ImageMetadataManager.getRotationDegrees(companion) != 0
                        || ImageMetadataManager.getRotationDegrees(base) != 0) {
                    builder.skip(companion.getImageName(), "Rotated Camera View companions are not exported yet.");
                    continue;
                }
                ServerFacts cf = facts(companion);
                ServerFacts bf = facts(base);
                if (cf == null || bf == null) {
                    builder.skip(companion.getImageName(), "Could not open the companion or its base to read sizes.");
                    continue;
                }
                if (cf.width != bf.width || cf.height != bf.height) {
                    builder.skip(
                            companion.getImageName(),
                            "Companion and base dimensions differ, so the relationship is not a pure mirror.");
                    continue;
                }
                boolean[] cp = ImageMetadataManager.bakedParity(companion);
                boolean[] bp = ImageMetadataManager.bakedParity(base);
                boolean fx = cp[0] ^ bp[0];
                boolean fy = cp[1] ^ bp[1];
                Map<String, Object> prov = new LinkedHashMap<>();
                prov.put("source", "baked parity difference between Camera View companion and base");
                prov.put("mirrorX", fx);
                prov.put("mirrorY", fy);
                String cs = system(companion);
                builder.addTransform(
                        companion.getImageName() + " -> " + base.getImageName(),
                        cs,
                        systems.get(base),
                        List.of(ForwardPropagationWorkflow.createFlip(fx, fy, bf.width, bf.height)),
                        prov);
                anchored.add(companion);
            }
        }

        void skipUnplaced() {
            for (ProjectImageEntry<BufferedImage> e : entries) {
                if (!anchored.contains(e) && !systems.containsKey(e) && !alreadySkipped(e.getImageName())) {
                    builder.skip(e.getImageName(), "No alignment, stage offset or stage bounds recorded.");
                }
            }
        }

        private void addToStage(
                ProjectImageEntry<BufferedImage> entry,
                String scope,
                List<AffineTransform> steps,
                Map<String, Object> prov) {
            facts(entry); // so the image record carries its size
            String stage = builder.addStage(scope);
            builder.addTransform(entry.getImageName() + " -> " + stage, system(entry), stage, steps, prov);
            anchored.add(entry);
        }

        private String system(ProjectImageEntry<BufferedImage> entry) {
            return systems.computeIfAbsent(entry, e -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("entryId", e.getID());
                try {
                    info.put("uris", e.getURIs().stream().map(Object::toString).toList());
                } catch (IOException ex) {
                    logger.debug("No URIs for {}: {}", e.getImageName(), ex.getMessage());
                }
                ServerFacts f = facts.get(e);
                if (f != null) {
                    info.put("widthPx", f.width);
                    info.put("heightPx", f.height);
                    if (f.pixelSizeMicrons > 0) info.put("pixelSizeMicrons", f.pixelSizeMicrons);
                }
                putIfPresent(info, "acquiredOnMicroscope", ImageMetadataManager.getAcquiredOnMicroscope(e));
                putIfPresent(info, "sourceMicroscope", e.getMetadata().get(ImageMetadataManager.SOURCE_MICROSCOPE));
                putIfPresent(info, "baseImage", ImageMetadataManager.getBaseImage(e));
                if (ImageMetadataManager.isCameraView(e)) info.put("cameraView", true);
                return builder.addImage(e.getImageName(), info);
            });
        }

        private ServerFacts facts(ProjectImageEntry<BufferedImage> entry) {
            if (facts.containsKey(entry)) return facts.get(entry);
            try (ImageServer<BufferedImage> server = entry.getServerBuilder().build()) {
                ServerFacts f = new ServerFacts(
                        server.getWidth(),
                        server.getHeight(),
                        server.getPixelCalibration().getAveragedPixelSizeMicrons());
                facts.put(entry, f);
                return f;
            } catch (Exception e) {
                logger.warn("Could not open {} to read its size: {}", entry.getImageName(), e.getMessage());
                facts.put(entry, null);
                return null;
            }
        }

        /** The non-companion, non-sub-acquisition entry a macro-frame alignment key refers to. */
        private ProjectImageEntry<BufferedImage> findMacroBase(String key) {
            ProjectImageEntry<BufferedImage> byName = findByStrippedName(key, true);
            if (byName != null) return byName;
            for (ProjectImageEntry<BufferedImage> e : entries) {
                if (isMacroCandidate(e) && key.equals(ImageMetadataManager.getBaseImage(e))) return e;
            }
            return null;
        }

        private ProjectImageEntry<BufferedImage> findByStrippedName(String name, boolean macroOnly) {
            for (ProjectImageEntry<BufferedImage> e : entries) {
                if (macroOnly && !isMacroCandidate(e)) continue;
                if (name.equals(GeneralTools.stripExtension(e.getImageName()))) return e;
            }
            return null;
        }

        private static boolean isMacroCandidate(ProjectImageEntry<BufferedImage> e) {
            return !ImageMetadataManager.isCameraView(e) && !ImageFlipHelper.isSubAcquisitionEntry(e);
        }

        private boolean alreadySkipped(String imageName) {
            return builder.getSkipped().stream().anyMatch(s -> imageName.equals(s.get("image")));
        }

        private static Map<String, Object> provenance(String source, String microscope) {
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("source", source);
            prov.put("microscope", microscope);
            return prov;
        }

        private static void putIfPresent(Map<String, Object> map, String key, String value) {
            if (value != null && !value.isBlank()) map.put(key, value);
        }

        private static String relative(File projectDir, File file) {
            return projectDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        }
    }

    private static void showResult(Result result) {
        StringBuilder summary = new StringBuilder();
        if (result.files().isEmpty()) {
            summary.append("Nothing to export: no image in this project has a recorded stage transform.");
        } else {
            summary.append("Wrote ")
                    .append(result.transformCount())
                    .append(" transform(s) in ")
                    .append(result.files().size())
                    .append(" scene file(s) to\n")
                    .append(result.outputDir());
        }
        if (!result.skipped().isEmpty()) {
            summary.append("\n\n")
                    .append(result.skipped().size())
                    .append(" image(s) were not placed -- expand Details for the reasons.");
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Export Spatial Relationships");
        alert.setHeaderText("OME-NGFF 0.6 scene export");
        alert.setContentText(summary.toString());
        if (!result.skipped().isEmpty()) {
            StringBuilder details = new StringBuilder();
            for (Map<String, String> s : result.skipped()) {
                details.append(s.get("image"))
                        .append(": ")
                        .append(s.get("reason"))
                        .append('\n');
            }
            TextArea area = new TextArea(details.toString());
            area.setEditable(false);
            area.setWrapText(true);
            alert.getDialogPane().setExpandableContent(area);
        }
        alert.showAndWait();
    }
}
