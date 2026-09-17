package qupath.ext.qpsc.utilities;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds OME-NGFF 0.6 {@code scene} metadata describing how a project's images relate to
 * each other and to the microscope stage.
 *
 * <p>This class is pure: it holds no QuPath or project state and does no I/O. The caller
 * ({@code ExportSpatialSceneWorkflow}) gathers transforms from the project using the same
 * builders propagation uses, and this class only lays them out in the 0.6 shape.
 *
 * <p>Layout decisions, all checked against the 0.6 JSON schemas:
 *
 * <ul>
 *   <li><b>Every coordinate system is declared in the scene itself</b> and referenced by
 *       {@code name} only. Our images are OME-TIFF, not Zarr subgroups, so a {@code path}
 *       reference would point at something that is not an OME-Zarr image. Which file each
 *       image coordinate system belongs to is recorded under {@code attributes.qpsc.images}.
 *   <li><b>Axes are ordered {@code (x, y)}</b> in every coordinate system, as in the spec's
 *       own stitching example, so an affine row is exactly a {@link AffineTransform} row:
 *       {@code [[m00, m01, m02], [m10, m11, m12]]}. No axis permutation happens anywhere.
 *   <li><b>Mirrors are {@code affine}, never {@code scale}.</b> The 0.6 schema requires every
 *       scale factor to be strictly positive, and {@code rotation} requires determinant +1,
 *       so an optical flip can only be written as an affine.
 *   <li><b>One scene per connected component.</b> The spec requires every pair of coordinate
 *       systems in a scene to be linked by some chain of transforms. Images anchored to
 *       different stages with nothing linking them go into separate scenes.
 * </ul>
 */
public final class NgffSceneBuilder {

    /** The NGFF version this builder writes. */
    public static final String NGFF_VERSION = "0.6";

    private static final String STAGE_PREFIX = "stage:";
    private static final String IMAGE_PREFIX = "image:";

    private final Map<String, Map<String, Object>> systems = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> imageInfo = new LinkedHashMap<>();
    private final List<TransformRecord> transforms = new ArrayList<>();
    private final List<Map<String, String>> skipped = new ArrayList<>();

    private record TransformRecord(
            String name, String input, String output, List<AffineTransform> steps, Map<String, Object> provenance) {}

    /** One scene: the coordinate systems linked to each other, ready to serialize. */
    public record Scene(String label, Set<String> stageNames, Map<String, Object> document) {}

    /**
     * Declare the stage coordinate system for one microscope, in micrometres.
     *
     * @return the coordinate system name to pass to {@link #addTransform}
     */
    public String addStage(String microscope) {
        String name = STAGE_PREFIX + microscope;
        if (!systems.containsKey(name)) {
            Map<String, Object> cs = new LinkedHashMap<>();
            cs.put("name", name);
            cs.put("axes", List.of(stageAxis("x"), stageAxis("y")));
            systems.put(name, cs);
        }
        return name;
    }

    /**
     * Declare the full-resolution pixel coordinate system of one project image.
     *
     * @param imageName the project entry's name; made unique if another image already uses it
     * @param info facts about the image recorded under {@code attributes.qpsc.images}
     * @return the coordinate system name to pass to {@link #addTransform}
     */
    public String addImage(String imageName, Map<String, Object> info) {
        String name = IMAGE_PREFIX + imageName;
        int n = 2;
        while (systems.containsKey(name)) {
            name = IMAGE_PREFIX + imageName + " #" + n++;
        }
        Map<String, Object> cs = new LinkedHashMap<>();
        cs.put("name", name);
        cs.put("axes", List.of(pixelAxis("x"), pixelAxis("y")));
        systems.put(name, cs);
        imageInfo.put(name, new LinkedHashMap<>(info));
        return name;
    }

    /**
     * Add a transform from {@code input} to {@code output}.
     *
     * @param steps applied in list order: the first step acts on input coordinates. One step is
     *     written as a plain {@code affine}; several as a {@code sequence}.
     * @param provenance where the transform came from, recorded under
     *     {@code attributes.qpsc.transforms}
     */
    public void addTransform(
            String name, String input, String output, List<AffineTransform> steps, Map<String, Object> provenance) {
        if (!systems.containsKey(input) || !systems.containsKey(output)) {
            throw new IllegalArgumentException("Undeclared coordinate system in transform '" + name + "'");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Transform '" + name + "' has no steps");
        }
        transforms.add(new TransformRecord(name, input, output, List.copyOf(steps), new LinkedHashMap<>(provenance)));
    }

    /** Record an image that could not be placed, and why. */
    public void skip(String imageName, String reason) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("image", imageName);
        s.put("reason", reason);
        skipped.add(s);
    }

    public int transformCount() {
        return transforms.size();
    }

    public List<Map<String, String>> getSkipped() {
        return List.copyOf(skipped);
    }

    /**
     * Split into connected components and lay each out as a Zarr v3 group document carrying
     * {@code attributes.ome.scene}. Coordinate systems no transform touches are left out.
     *
     * @param generatedBy recorded under {@code attributes.qpsc.generatedBy}
     * @param timestamp recorded under {@code attributes.qpsc.generated}
     */
    public List<Scene> build(String generatedBy, String timestamp) {
        Map<String, String> parent = new HashMap<>();
        for (TransformRecord t : transforms) {
            union(parent, t.input, t.output);
        }

        Map<String, List<TransformRecord>> byRoot = new LinkedHashMap<>();
        for (TransformRecord t : transforms) {
            byRoot.computeIfAbsent(find(parent, t.input), k -> new ArrayList<>())
                    .add(t);
        }

        List<Scene> scenes = new ArrayList<>();
        for (List<TransformRecord> group : byRoot.values()) {
            Set<String> members = new LinkedHashSet<>();
            for (TransformRecord t : group) {
                members.add(t.input);
                members.add(t.output);
            }
            Set<String> stages = new TreeSet<>();
            for (String m : members) {
                if (m.startsWith(STAGE_PREFIX)) stages.add(m.substring(STAGE_PREFIX.length()));
            }
            String label = stages.isEmpty()
                    ? "unanchored_" + members.iterator().next().substring(IMAGE_PREFIX.length())
                    : String.join("+", stages);
            scenes.add(new Scene(label, stages, sceneDocument(group, members, generatedBy, timestamp)));
        }
        return scenes;
    }

    private Map<String, Object> sceneDocument(
            List<TransformRecord> group, Set<String> members, String generatedBy, String timestamp) {
        List<Map<String, Object>> cts = new ArrayList<>();
        Map<String, Object> provenance = new LinkedHashMap<>();
        for (TransformRecord t : group) {
            Map<String, Object> ct = new LinkedHashMap<>();
            if (t.steps.size() == 1) {
                ct.putAll(affineStep(t.steps.get(0)));
            } else {
                ct.put("type", "sequence");
                List<Map<String, Object>> inner = new ArrayList<>();
                for (AffineTransform step : t.steps) inner.add(affineStep(step));
                ct.put("transformations", inner);
            }
            ct.put("name", t.name);
            ct.put("input", Map.of("name", t.input));
            ct.put("output", Map.of("name", t.output));
            cts.add(ct);
            provenance.put(t.name, t.provenance);
        }

        List<Map<String, Object>> css = new ArrayList<>();
        Map<String, Object> images = new LinkedHashMap<>();
        for (String name : systems.keySet()) {
            if (!members.contains(name)) continue;
            css.add(systems.get(name));
            if (imageInfo.containsKey(name)) images.put(name, imageInfo.get(name));
        }

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("coordinateSystems", css);
        scene.put("coordinateTransformations", cts);

        Map<String, Object> ome = new LinkedHashMap<>();
        ome.put("version", NGFF_VERSION);
        ome.put("scene", scene);

        Map<String, Object> qpsc = new LinkedHashMap<>();
        qpsc.put("generatedBy", generatedBy);
        qpsc.put("generated", timestamp);
        qpsc.put(
                "note",
                "Sidecar metadata, not an OME-Zarr store: coordinate systems are named, not "
                        + "referenced by path. 'images' maps each image coordinate system to its "
                        + "project entry. Pixel coordinates are QuPath full-resolution pixels.");
        qpsc.put("images", images);
        qpsc.put("transforms", provenance);
        if (!skipped.isEmpty()) qpsc.put("skipped", skipped);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("ome", ome);
        attributes.put("qpsc", qpsc);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("zarr_format", 3);
        doc.put("node_type", "group");
        doc.put("attributes", attributes);
        return doc;
    }

    /**
     * The 0.6 {@code affine} for a 2D {@link AffineTransform}, axes {@code (x, y)}: a 2x3 matrix
     * in homogeneous form, so {@code x' = m00*x + m01*y + m02} and
     * {@code y' = m10*x + m11*y + m12}.
     */
    static Map<String, Object> affineStep(AffineTransform t) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", "affine");
        step.put(
                "affine",
                List.of(
                        List.of(t.getScaleX(), t.getShearX(), t.getTranslateX()),
                        List.of(t.getShearY(), t.getScaleY(), t.getTranslateY())));
        return step;
    }

    private static Map<String, Object> stageAxis(String name) {
        Map<String, Object> axis = new LinkedHashMap<>();
        axis.put("name", name);
        axis.put("type", "space");
        axis.put("unit", "micrometer");
        return axis;
    }

    private static Map<String, Object> pixelAxis(String name) {
        Map<String, Object> axis = new LinkedHashMap<>();
        axis.put("name", name);
        axis.put("type", "array");
        return axis;
    }

    private static String find(Map<String, String> parent, String x) {
        String p = parent.getOrDefault(x, x);
        if (p.equals(x)) return x;
        String root = find(parent, p);
        parent.put(x, root);
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(rb, ra);
    }
}
