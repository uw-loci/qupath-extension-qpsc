package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NgffSceneBuilderTest {

    /** Apply a 0.6 affine step, axes (x, y), the way a spec-conformant reader would. */
    @SuppressWarnings("unchecked")
    private static double[] applyStep(Map<String, Object> step, double x, double y) {
        List<List<Double>> m = (List<List<Double>>) step.get("affine");
        return new double[] {
            m.get(0).get(0) * x + m.get(0).get(1) * y + m.get(0).get(2),
            m.get(1).get(0) * x + m.get(1).get(1) * y + m.get(1).get(2)
        };
    }

    @Test
    void affineRowsMapPointsExactlyAsTheJavaTransformDoes() {
        // Shear and rotation terms included, so a transposed matrix cannot pass.
        AffineTransform t = new AffineTransform(0.25, 0.03, -0.07, -0.26, 12345.6, -7890.1);
        Map<String, Object> step = NgffSceneBuilder.affineStep(t);
        for (double[] p : new double[][] {{0, 0}, {1000, 0}, {0, 2000}, {4321, 987}}) {
            Point2D expected = t.transform(new Point2D.Double(p[0], p[1]), null);
            double[] got = applyStep(step, p[0], p[1]);
            assertEquals(expected.getX(), got[0], 1e-9);
            assertEquals(expected.getY(), got[1], 1e-9);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sequenceAppliesStepsInTheOrderForwardPropagationComposesThem() {
        // Forward propagation: combined = baseToStage.concatenate(flip), i.e. flip acts first.
        AffineTransform flip = qupath.ext.qpsc.controller.ForwardPropagationWorkflow.createFlip(true, true, 4000, 3000);
        AffineTransform baseToStage = new AffineTransform(0.25, 0, 0, 0.25, 1000, 2000);
        AffineTransform combined = new AffineTransform(baseToStage);
        combined.concatenate(flip);

        NgffSceneBuilder b = new NgffSceneBuilder();
        String stage = b.addStage("PPM");
        String img = b.addImage("slide", Map.of());
        b.addTransform("t", img, stage, List.of(flip, baseToStage), Map.of());
        Map<String, Object> doc = b.build("test", "now").get(0).document();
        Map<String, Object> ct = firstTransform(doc);
        assertEquals("sequence", ct.get("type"));
        List<Map<String, Object>> steps = (List<Map<String, Object>>) ct.get("transformations");

        double[] p = {123, 456};
        for (Map<String, Object> s : steps) p = applyStep(s, p[0], p[1]);
        Point2D expected = combined.transform(new Point2D.Double(123, 456), null);
        assertEquals(expected.getX(), p[0], 1e-9);
        assertEquals(expected.getY(), p[1], 1e-9);
    }

    @Test
    void mirrorsAreNeverWrittenAsScaleBecauseTheSchemaForbidsNegativeScale() {
        NgffSceneBuilder b = new NgffSceneBuilder();
        String stage = b.addStage("PPM");
        String img = b.addImage("slide", Map.of());
        b.addTransform(
                "t",
                img,
                stage,
                List.of(qupath.ext.qpsc.controller.ForwardPropagationWorkflow.createFlip(true, false, 10, 10)),
                Map.of());
        String json =
                new GsonBuilder().create().toJson(b.build("test", "now").get(0).document());
        assertFalse(json.contains("\"scale\""), json);
    }

    @Test
    void unlinkedStagesBecomeSeparateScenesAndALinkMergesThem() {
        NgffSceneBuilder b = new NgffSceneBuilder();
        String ppm = b.addStage("PPM");
        String ows3 = b.addStage("OWS3");
        String a = b.addImage("a", Map.of());
        String c = b.addImage("c", Map.of());
        b.addTransform("a->ppm", a, ppm, List.of(new AffineTransform()), Map.of());
        b.addTransform("c->ows3", c, ows3, List.of(new AffineTransform()), Map.of());
        assertEquals(2, b.build("test", "now").size());

        // The same macro aligned on both scopes links the two stage frames.
        b.addTransform("a->ows3", a, ows3, List.of(new AffineTransform()), Map.of());
        List<NgffSceneBuilder.Scene> scenes = b.build("test", "now");
        assertEquals(1, scenes.size());
        assertEquals("OWS3+PPM", scenes.get(0).label());
    }

    @Test
    void duplicateImageNamesGetDistinctCoordinateSystems() {
        NgffSceneBuilder b = new NgffSceneBuilder();
        assertNotEquals(b.addImage("same", Map.of()), b.addImage("same", Map.of()));
    }

    /**
     * The golden file is the contract other tools read. It was validated against the official
     * 0.6 scene schema (github.com/ome/ngff-spec, tag 0.6, schemas/scene.schema) with
     * jsonschema's Draft202012Validator; re-validate it if this test's expected output changes.
     */
    @Test
    void outputMatchesTheSchemaValidatedGoldenFile() throws Exception {
        NgffSceneBuilder b = new NgffSceneBuilder();
        String stage = b.addStage("PPM");
        Map<String, Object> baseInfo = new java.util.LinkedHashMap<>(); // Map.of iteration order is not stable
        baseInfo.put("widthPx", 4000);
        baseInfo.put("heightPx", 3000);
        String base = b.addImage("slide.ome.tif", baseInfo);
        String cam = b.addImage("slide.ome.tif (Camera View)", Map.of("cameraView", true));
        String sub = b.addImage("slide_1.ome.tif", Map.of("pixelSizeMicrons", 0.25));
        AffineTransform flip = qupath.ext.qpsc.controller.ForwardPropagationWorkflow.createFlip(true, true, 4000, 3000);
        b.addTransform(
                "slide.ome.tif -> stage:PPM",
                base,
                stage,
                List.of(flip, new AffineTransform(-0.25, 0, 0, -0.25, 1000, 2000)),
                Map.of("source", "macro alignment JSON"));
        b.addTransform("cam -> base", cam, base, List.of(flip), Map.of("source", "baked parity difference"));
        b.addTransform(
                "slide_1.ome.tif -> stage:PPM",
                sub,
                stage,
                List.of(new AffineTransform(0.25, 0, 0, 0.25, 500, 600)),
                Map.of("source", "xy_offset metadata with half-FOV correction"));
        b.skip("unplaced.ome.tif", "No alignment, stage offset or stage bounds recorded.");

        List<NgffSceneBuilder.Scene> scenes = b.build("QPSC test", "2026-09-17T00:00:00Z");
        assertEquals(1, scenes.size());
        String actual = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(scenes.get(0).document());
        try (InputStream in = getClass().getResourceAsStream("ngff_scene_golden.json")) {
            assertNotNull(in, "golden file missing");
            String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Windows checkouts may convert the golden file to CRLF; Gson always writes LF.
            assertEquals(expected.replace("\r\n", "\n").strip(), actual.strip());
        }
    }

    @Test
    void listSavedAlignmentsReadsBothDirectoriesWithScopeAndKey(@TempDir Path dir) throws Exception {
        Path flat = Files.createDirectories(dir.resolve("alignmentFiles"));
        Path derived = Files.createDirectories(flat.resolve("derived"));
        Files.writeString(
                flat.resolve("slideA_PPM_alignment.json"),
                "{\"sampleName\":\"slideA\",\"microscope\":\"PPM\",\"transform\":[1,0,0,1,5,6],"
                        + "\"flipMacroX\":true,\"flipMacroY\":true,\"flipFrameVerified\":true,\"pixelFrame\":\"macro\"}");
        Files.writeString(
                derived.resolve("slideA_1_PPM_alignment.json"),
                "{\"sampleName\":\"slideA_1\",\"microscope\":\"PPM\",\"transform\":[0.25,0,0,0.25,1,2],"
                        + "\"pixelFrame\":\"sub\"}");
        Files.writeString(flat.resolve("broken_PPM_alignment.json"), "{\"microscope\":\"PPM\"}");

        List<AffineTransformManager.SavedAlignment> all = AffineTransformManager.listSavedAlignments(dir.toFile());
        assertEquals(2, all.size());
        AffineTransformManager.SavedAlignment sub = all.get(1);
        assertEquals("slideA_1", sub.sampleName());
        assertEquals("PPM", sub.microscope());
        assertEquals(AffineTransformManager.PIXEL_FRAME_SUB, sub.result().getPixelFrame());
        AffineTransformManager.SavedAlignment macro = all.get(0);
        assertEquals("slideA", macro.sampleName());
        assertTrue(macro.result().getFlipMacroX());
        assertEquals(5.0, macro.result().getTransform().getTranslateX());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstTransform(Map<String, Object> doc) {
        Map<String, Object> attrs = (Map<String, Object>) doc.get("attributes");
        Map<String, Object> ome = (Map<String, Object>) attrs.get("ome");
        Map<String, Object> scene = (Map<String, Object>) ome.get("scene");
        return ((List<Map<String, Object>>) scene.get("coordinateTransformations")).get(0);
    }
}
