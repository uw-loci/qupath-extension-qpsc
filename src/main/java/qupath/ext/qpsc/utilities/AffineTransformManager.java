package qupath.ext.qpsc.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.projects.Project;

/**
 * Manages saved affine transforms for different microscopes and mounting configurations.
 * Transforms are persisted as JSON and can be reused across imaging sessions.
 *
 * @since 0.3.0
 */
public class AffineTransformManager {
    private static final Logger logger = LoggerFactory.getLogger(AffineTransformManager.class);
    private static final String TRANSFORMS_FILE = "saved_transforms.json";

    private final Path transformsPath;
    private final Map<String, TransformPreset> transforms;
    private final Gson gson;

    /**
     * Creates a new transform manager, loading existing transforms from the config directory.
     *
     * @param configDirectory Directory containing the microscope configuration files
     */
    public AffineTransformManager(String configDirectory) {
        this.transformsPath = Paths.get(configDirectory, TRANSFORMS_FILE);
        this.gson = new GsonBuilder()
                .registerTypeAdapter(AffineTransform.class, new AffineTransformAdapter())
                .setPrettyPrinting()
                .create();
        this.transforms = loadTransforms();
        logger.info("Loaded {} transform presets from {}", transforms.size(), transformsPath);
    }

    /**
     * Represents a saved transform preset with metadata.
     */
    public static class TransformPreset {
        private final String name;
        private final String microscope;
        private final String mountingMethod;
        private final AffineTransform transform;
        private final Date createdDate;
        private final String notes;
        private final GreenBoxDetector.DetectionParams greenBoxParams;
        /** Z scale factor for 3D transforms (null = default 1.0 pass-through). Wrapper type for Gson backward compat. */
        private final Double zScale;
        /** Z offset for 3D transforms (null = default 0.0). Wrapper type for Gson backward compat. */
        private final Double zOffset;
        /** Macro-image X flip captured at alignment time (null = old preset, fall back to global pref). */
        private final Boolean flipMacroX;
        /** Macro-image Y flip captured at alignment time (null = old preset, fall back to global pref). */
        private final Boolean flipMacroY;
        /** Source scanner that produced the macro image, e.g. "Ocus40" (null = old preset). */
        private final String sourceScanner;

        // ---- Anchor metadata (added 2026-04-30) ---------------------------
        // The Stage Map macro overlay is placed on screen by anchoring on the
        // alignment build point: the green-box center on the displayed-flipped
        // macro <-> the data-region center in stage. Both coordinates were
        // computed during saveGeneralTransform but not persisted before --
        // recovering them required re-applying the saved macro->stage transform,
        // which is unsafe when the transform was built in the wrong frame
        // (a class of bug observed on 2026-04-30 when alignment ran with the
        // un-flipped entry open). Storing these explicitly makes Stage Map
        // placement immune to any extrapolation or frame error in the
        // transform itself.

        /** Green-box X center in the orientation-dialog flipped (displayed) macro frame, or null for legacy presets. */
        private final Double greenBoxDisplayCenterX;
        /** Green-box Y center in the displayed macro frame, or null for legacy presets. */
        private final Double greenBoxDisplayCenterY;
        /** Stage X micrometer position the green-box center maps to (i.e. data-region stage center). */
        private final Double stageAnchorX;
        /** Stage Y micrometer position the green-box center maps to. */
        private final Double stageAnchorY;
        /** Cropped macro pixel width at alignment time, or null for legacy presets. */
        private final Integer macroDisplayWidth;
        /** Cropped macro pixel height at alignment time, or null for legacy presets. */
        private final Integer macroDisplayHeight;
        /** Macro pixel size in micrometers at alignment time (e.g. 81.0 for Ocus40), or null for legacy presets. */
        private final Double macroPixelSizeUm;

        /**
         * Full constructor including anchor metadata for transform-frame-immune
         * Stage Map overlay placement.
         */
        public TransformPreset(
                String name,
                String microscope,
                String mountingMethod,
                AffineTransform transform,
                String notes,
                GreenBoxDetector.DetectionParams greenBoxParams,
                double zScale,
                double zOffset,
                Boolean flipMacroX,
                Boolean flipMacroY,
                String sourceScanner,
                Double greenBoxDisplayCenterX,
                Double greenBoxDisplayCenterY,
                Double stageAnchorX,
                Double stageAnchorY,
                Integer macroDisplayWidth,
                Integer macroDisplayHeight,
                Double macroPixelSizeUm) {
            this.name = name;
            this.microscope = microscope;
            this.mountingMethod = mountingMethod;
            this.transform = new AffineTransform(transform);
            this.createdDate = new Date();
            this.notes = notes;
            this.greenBoxParams = greenBoxParams;
            this.zScale = (zScale == 1.0) ? null : zScale; // null = default 1.0 (omit from JSON)
            this.zOffset = (zOffset == 0.0) ? null : zOffset; // null = default 0.0 (omit from JSON)
            this.flipMacroX = flipMacroX;
            this.flipMacroY = flipMacroY;
            this.sourceScanner = sourceScanner;
            this.greenBoxDisplayCenterX = greenBoxDisplayCenterX;
            this.greenBoxDisplayCenterY = greenBoxDisplayCenterY;
            this.stageAnchorX = stageAnchorX;
            this.stageAnchorY = stageAnchorY;
            this.macroDisplayWidth = macroDisplayWidth;
            this.macroDisplayHeight = macroDisplayHeight;
            this.macroPixelSizeUm = macroPixelSizeUm;
        }

        /**
         * Backward-compatible constructor preserving the pre-anchor signature.
         * Anchor fields are stored as null; the placement code falls back to
         * the legacy 4-corner path for these presets.
         */
        public TransformPreset(
                String name,
                String microscope,
                String mountingMethod,
                AffineTransform transform,
                String notes,
                GreenBoxDetector.DetectionParams greenBoxParams,
                double zScale,
                double zOffset,
                Boolean flipMacroX,
                Boolean flipMacroY,
                String sourceScanner) {
            this(name, microscope, mountingMethod, transform, notes, greenBoxParams,
                    zScale, zOffset, flipMacroX, flipMacroY, sourceScanner,
                    null, null, null, null, null, null, null);
        }

        /**
         * Backward-compatible constructor for callers that have not yet been migrated to record
         * per-pair flip state. Stores null for flipMacroX, flipMacroY, sourceScanner -- callers
         * of {@link FlipResolver} will fall through to detector config / global pref.
         */
        public TransformPreset(
                String name,
                String microscope,
                String mountingMethod,
                AffineTransform transform,
                String notes,
                GreenBoxDetector.DetectionParams greenBoxParams,
                double zScale,
                double zOffset) {
            this(name, microscope, mountingMethod, transform, notes, greenBoxParams,
                    zScale, zOffset, null, null, null);
        }

        // Getters
        public String getName() {
            return name;
        }

        public String getMicroscope() {
            return microscope;
        }

        public String getMountingMethod() {
            return mountingMethod;
        }

        public AffineTransform getTransform() {
            return new AffineTransform(transform);
        }

        public Date getCreatedDate() {
            return createdDate;
        }

        public String getNotes() {
            return notes;
        }

        public GreenBoxDetector.DetectionParams getGreenBoxParams() {
            return greenBoxParams;
        }

        /** Z scale factor (1.0 = no Z scaling, default for 2D presets). Null-safe for old JSON. */
        public double getZScale() {
            return zScale != null ? zScale : 1.0;
        }

        /** Z offset in micrometers (0.0 = no offset, default for 2D presets). Null-safe for old JSON. */
        public double getZOffset() {
            return zOffset != null ? zOffset : 0.0;
        }

        /** Whether this preset has non-default Z parameters. */
        public boolean has3DTransform() {
            return getZScale() != 1.0 || getZOffset() != 0.0;
        }

        /**
         * Macro X flip captured when this preset was saved. {@code null} for presets saved before
         * per-pair flip support was introduced -- callers should fall back to detector config /
         * global pref via {@link FlipResolver}.
         */
        public Boolean getFlipMacroX() {
            return flipMacroX;
        }

        /** Macro Y flip captured when this preset was saved. See {@link #getFlipMacroX()}. */
        public Boolean getFlipMacroY() {
            return flipMacroY;
        }

        /**
         * Source scanner that produced the macro image (e.g. "Ocus40"), captured when this preset
         * was saved. {@code null} for old presets; in that case the scanner is encoded only in the
         * preset name.
         */
        public String getSourceScanner() {
            return sourceScanner;
        }

        /** True when this preset has captured per-pair flip values (not an older preset). */
        public boolean hasFlipState() {
            return flipMacroX != null && flipMacroY != null;
        }

        /** Green-box X center in the displayed (flipped) macro frame, or NaN if not captured. */
        public double getGreenBoxDisplayCenterX() {
            return greenBoxDisplayCenterX != null ? greenBoxDisplayCenterX : Double.NaN;
        }

        /** Green-box Y center in the displayed macro frame, or NaN if not captured. */
        public double getGreenBoxDisplayCenterY() {
            return greenBoxDisplayCenterY != null ? greenBoxDisplayCenterY : Double.NaN;
        }

        /** Stage X micrometer anchor (data-region center in stage), or NaN if not captured. */
        public double getStageAnchorX() {
            return stageAnchorX != null ? stageAnchorX : Double.NaN;
        }

        /** Stage Y micrometer anchor, or NaN if not captured. */
        public double getStageAnchorY() {
            return stageAnchorY != null ? stageAnchorY : Double.NaN;
        }

        /** Cropped macro pixel width at alignment time, or -1 if not captured. */
        public int getMacroDisplayWidth() {
            return macroDisplayWidth != null ? macroDisplayWidth : -1;
        }

        /** Cropped macro pixel height at alignment time, or -1 if not captured. */
        public int getMacroDisplayHeight() {
            return macroDisplayHeight != null ? macroDisplayHeight : -1;
        }

        /** Macro pixel size in micrometers at alignment time, or NaN if not captured. */
        public double getMacroPixelSizeUm() {
            return macroPixelSizeUm != null ? macroPixelSizeUm : Double.NaN;
        }

        /**
         * True when this preset has the full anchor metadata required for
         * transform-frame-immune Stage Map overlay placement (added 2026-04-30).
         * Legacy presets without this data fall back to 4-corner placement.
         */
        public boolean hasOverlayAnchor() {
            return greenBoxDisplayCenterX != null
                    && greenBoxDisplayCenterY != null
                    && stageAnchorX != null
                    && stageAnchorY != null
                    && macroPixelSizeUm != null
                    && macroPixelSizeUm > 0;
        }

        /** Creates an {@link AffineTransform3D} combining the 2D XY transform with Z scale/offset. */
        public AffineTransform3D getTransform3D() {
            return AffineTransform3D.from2D(transform, getZScale(), getZOffset());
        }

        @Override
        public String toString() {
            return String.format("%s (%s - %s)", name, microscope, mountingMethod);
        }
    }

    /**
     * Custom Gson adapter for AffineTransform serialization.
     */
    private static class AffineTransformAdapter extends com.google.gson.TypeAdapter<AffineTransform> {

        @Override
        public void write(com.google.gson.stream.JsonWriter out, AffineTransform transform) throws IOException {
            if (transform == null) {
                out.nullValue();
                return;
            }

            double[] matrix = new double[6];
            transform.getMatrix(matrix);

            out.beginObject();
            out.name("m00").value(matrix[0]);
            out.name("m10").value(matrix[1]);
            out.name("m01").value(matrix[2]);
            out.name("m11").value(matrix[3]);
            out.name("m02").value(matrix[4]);
            out.name("m12").value(matrix[5]);
            out.endObject();
        }

        @Override
        public AffineTransform read(com.google.gson.stream.JsonReader in) throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            double m00 = 0, m10 = 0, m01 = 0, m11 = 0, m02 = 0, m12 = 0;

            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "m00" -> m00 = in.nextDouble();
                    case "m10" -> m10 = in.nextDouble();
                    case "m01" -> m01 = in.nextDouble();
                    case "m11" -> m11 = in.nextDouble();
                    case "m02" -> m02 = in.nextDouble();
                    case "m12" -> m12 = in.nextDouble();
                    default -> in.skipValue();
                }
            }
            in.endObject();

            return new AffineTransform(m00, m10, m01, m11, m02, m12);
        }
    }

    /**
     * Loads transforms from the JSON file.
     */
    private Map<String, TransformPreset> loadTransforms() {
        if (!Files.exists(transformsPath)) {
            logger.info("No saved transforms file found at {}", transformsPath);
            return new HashMap<>();
        }

        try {
            String json = Files.readString(transformsPath);
            var type = new TypeToken<Map<String, TransformPreset>>() {}.getType();
            Map<String, TransformPreset> loaded = gson.fromJson(json, type);

            // Verify all presets have green box params; warn about presets lacking per-pair flip
            // state so users know to re-save them with the current flip values captured.
            if (loaded != null) {
                loaded.forEach((key, preset) -> {
                    if (preset.getGreenBoxParams() == null) {
                        logger.warn("Transform preset {} is missing green box params", key);
                    }
                    if (!preset.hasFlipState()) {
                        logger.warn(
                                "Transform preset '{}' was saved before per-pair flip support; "
                                        + "using global pref for flip until re-saved.",
                                key);
                    }
                });
            }

            return loaded != null ? loaded : new HashMap<>();
        } catch (IOException e) {
            logger.error("Failed to load transforms from {}", transformsPath, e);
            return new HashMap<>();
        }
    }

    /**
     * Saves all transforms to the JSON file.
     */
    private void saveTransforms() {
        try {
            String json = gson.toJson(transforms);
            Files.writeString(transformsPath, json);
            logger.info("Saved {} transforms to {}", transforms.size(), transformsPath);
        } catch (IOException e) {
            logger.error("Failed to save transforms to {}", transformsPath, e);
        }
    }

    /**
     * Saves a transform preset to persistent storage.
     * The preset will be immediately written to the JSON file.
     *
     * @param preset The transform preset to save
     */
    public void savePreset(TransformPreset preset) {
        transforms.put(preset.getName(), preset);
        persistTransforms(); // renamed from saveTransforms
        logger.info("Saved transform preset: {}", preset.getName());
    }
    /**
     * Writes all transforms to the JSON file.
     * This is called automatically when presets are added or removed.
     */
    private void persistTransforms() { // renamed from saveTransforms
        try {
            String json = gson.toJson(transforms);
            Files.writeString(transformsPath, json);
            logger.debug("Persisted {} transforms to {}", transforms.size(), transformsPath);
        } catch (IOException e) {
            logger.error("Failed to persist transforms to {}", transformsPath, e);
        }
    }
    /**
     * Gets a transform preset by name.
     *
     * @param name The preset name
     * @return The transform preset, or null if not found
     */
    public TransformPreset getTransform(String name) {
        return transforms.get(name);
    }

    /**
     * Gets all transform presets for a specific microscope.
     *
     * @param microscope The microscope identifier
     * @return List of matching presets
     */
    public List<TransformPreset> getTransformsForMicroscope(String microscope) {
        return transforms.values().stream()
                .filter(t -> t.getMicroscope().equals(microscope))
                .sorted(Comparator.comparing(TransformPreset::getName))
                .toList();
    }

    /**
     * Distinct, sorted source-scanner names that have at least one preset whose target is
     * {@code targetMicroscope}. Presets with no captured {@code sourceScanner} are excluded.
     *
     * @param targetMicroscope the target microscope (e.g. "PPM", "OWS3"); null returns empty
     * @return source scanners (e.g. ["Ocus40", "ScanScope"]); never null
     */
    public List<String> getDistinctSourceScannersForMicroscope(String targetMicroscope) {
        if (targetMicroscope == null) return List.of();
        return transforms.values().stream()
                .filter(t -> targetMicroscope.equals(t.getMicroscope()))
                .map(TransformPreset::getSourceScanner)
                .filter(s -> s != null && !s.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Returns the most-recently-created preset matching ({@code sourceScanner}, {@code targetMicroscope}),
     * or {@code null} if none. When several presets exist for the pair, the latest {@code createdDate}
     * wins -- this is the tiebreaker used by the Stage Map source selector.
     *
     * @param sourceScanner    source scanner name, e.g. "Ocus40"
     * @param targetMicroscope target microscope name, e.g. "PPM"
     * @return matching preset or null
     */
    public TransformPreset getBestPresetForPair(String sourceScanner, String targetMicroscope) {
        if (sourceScanner == null || targetMicroscope == null) return null;
        return transforms.values().stream()
                .filter(t -> targetMicroscope.equals(t.getMicroscope())
                        && sourceScanner.equals(t.getSourceScanner()))
                .max(Comparator.comparing(
                        TransformPreset::getCreatedDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /**
     * Gets all available transform presets.
     *
     * @return Unmodifiable collection of all presets
     */
    public Collection<TransformPreset> getAllTransforms() {
        return Collections.unmodifiableCollection(transforms.values());
    }

    /**
     * Deletes a transform preset.
     *
     * @param name The preset name to delete
     * @return true if deleted, false if not found
     */
    public boolean deleteTransform(String name) {
        if (transforms.remove(name) != null) {
            saveTransforms();
            logger.info("Deleted transform preset: {}", name);
            return true;
        }
        return false;
    }

    /**
     * Validates a transform by checking if it produces reasonable stage coordinates
     * for a given QuPath coordinate range.
     *
     * @param transform The transform to validate
     * @param imageWidth Width of the image in pixels
     * @param imageHeight Height of the image in pixels
     * @param stageXMin Minimum expected stage X coordinate
     * @param stageXMax Maximum expected stage X coordinate
     * @param stageYMin Minimum expected stage Y coordinate
     * @param stageYMax Maximum expected stage Y coordinate
     * @return true if the transform produces coordinates within expected bounds
     */
    public static boolean validateTransform(
            AffineTransform transform,
            double imageWidth,
            double imageHeight,
            double stageXMin,
            double stageXMax,
            double stageYMin,
            double stageYMax) {
        // Test corners of the image
        double[][] testPoints = {
            {0, 0},
            {imageWidth, 0},
            {0, imageHeight},
            {imageWidth, imageHeight},
            {imageWidth / 2, imageHeight / 2}
        };

        for (double[] point : testPoints) {
            double[] transformed = TransformationFunctions.transformQuPathFullResToStage(point, transform);

            if (transformed[0] < stageXMin
                    || transformed[0] > stageXMax
                    || transformed[1] < stageYMin
                    || transformed[1] > stageYMax) {
                logger.warn(
                        "Transform validation failed: point ({}, {}) -> ({}, {}) " + "is outside stage bounds",
                        point[0],
                        point[1],
                        transformed[0],
                        transformed[1]);
                return false;
            }
        }

        logger.info("Transform validation passed for all test points");
        return true;
    }

    /**
     * Saves a slide-specific alignment transform and its associated processed macro image to the project folder.
     *
     * <p>This method creates a persistent record of the alignment between macro coordinates and stage coordinates
     * for a specific slide. The alignment data is saved as a JSON file containing the transform matrix,
     * metadata, and timestamp. Additionally, the processed (flipped and cropped) macro image used for
     * the alignment is saved as a PNG file.</p>
     *
     * <p>Both files are saved in an "alignmentFiles" subdirectory within the project folder, using the
     * sample name as the base filename:</p>
     * <ul>
     *   <li>{sampleName}_alignment.json - Contains the transform and metadata</li>
     *   <li>{sampleName}_alignment.png - Contains the processed macro image</li>
     * </ul>
     *
     * <p>The JSON file structure includes:</p>
     * <pre>
     * {
     *   "sampleName": "sample identifier",
     *   "modality": "imaging modality",
     *   "timestamp": "creation timestamp",
     *   "transform": [scaleX, shearY, shearX, scaleY, translateX, translateY]
     * }
     * </pre>
     *
     * <p>The transform array represents the affine transformation matrix in the standard Java
     * AffineTransform format, mapping macro image coordinates to stage micrometers.</p>
     *
     * @param project The QuPath project containing the slide. Must not be null and must have a valid path.
     * @param sampleName The unique identifier for the sample/slide. Used as the base filename for saved files.
     *                   Must not be null or empty.
     * @param modality The imaging modality used (e.g., "Brightfield", "Polarized"). Stored as metadata
     *                 in the JSON file. May be null.
     * @param transform The affine transform mapping macro coordinates to stage coordinates. Must not be null.
     *                  The transform components are extracted and saved as a 6-element array.
     * @param processedMacroImage The cropped macro image for alignment. If not null,
     *                            this image is saved as a PNG file alongside the alignment data.
     *                            The image should be cropped according to the scanner configuration
     *                            but NOT display-flipped, so downstream consumers (Stage Map overlay)
     *                            can apply their own display flips consistently.
     *
     * @throws NullPointerException if project, sampleName, or transform is null
     * @throws IllegalStateException if the project path cannot be determined
     * @throws IOException if there is an error creating directories or writing files (caught internally
     *                     and logged, does not propagate)
     *
     * @see #loadSlideAlignment(Project, String)
     * @see AffineTransform
     * @see javax.imageio.ImageIO#write(RenderedImage, String, File)
     *
     * @since 0.4.0
     */
    /**
     * Build a pixel -> stage affine transform from known acquisition stage
     * bounds and the resulting stitched image's pixel dimensions.
     *
     * <p>Intended for BoundingBox acquisitions (and any flow where the stage
     * coverage of the stitched output is known up front). The math assumes
     * the stitched image is already in the canonical (non-flipped) orientation
     * -- which it is, because the stitcher honours
     * {@link StageImageTransform#stitcherFlipFlags()} when writing the output.
     * The resulting transform therefore has positive scale components and a
     * translation equal to the (x1, y1) corner of the acquisition region.
     *
     * <p>Pixel {@code (px, py)} maps to stage {@code (x1 + px * sx, y1 + py * sy)}
     * where {@code sx = (x2 - x1) / widthPx} and {@code sy = (y2 - y1) / heightPx}.
     *
     * @param stageX1Um stage X of the top-left corner of the acquisition region
     * @param stageY1Um stage Y of the top-left corner
     * @param stageX2Um stage X of the bottom-right corner (must be &gt; x1)
     * @param stageY2Um stage Y of the bottom-right corner (must be &gt; y1)
     * @param imageWidthPx width of the stitched output in pixels (must be &gt; 0)
     * @param imageHeightPx height of the stitched output in pixels (must be &gt; 0)
     * @return pixel -> stage AffineTransform, or {@code null} if the inputs are
     *     degenerate (caller falls back to "no alignment" behaviour).
     */
    public static AffineTransform buildTransformFromStageBounds(
            double stageX1Um,
            double stageY1Um,
            double stageX2Um,
            double stageY2Um,
            int imageWidthPx,
            int imageHeightPx) {
        if (imageWidthPx <= 0 || imageHeightPx <= 0) {
            logger.warn(
                    "buildTransformFromStageBounds: invalid image dimensions ({}, {})", imageWidthPx, imageHeightPx);
            return null;
        }
        if (stageX2Um <= stageX1Um || stageY2Um <= stageY1Um) {
            logger.warn(
                    "buildTransformFromStageBounds: degenerate bounds x=[{}..{}] y=[{}..{}]",
                    stageX1Um,
                    stageX2Um,
                    stageY1Um,
                    stageY2Um);
            return null;
        }
        double scaleX = (stageX2Um - stageX1Um) / imageWidthPx;
        double scaleY = (stageY2Um - stageY1Um) / imageHeightPx;
        AffineTransform t = new AffineTransform();
        t.translate(stageX1Um, stageY1Um);
        t.scale(scaleX, scaleY);
        logger.info(
                "buildTransformFromStageBounds: bounds=({},{})->({},{}) size=({}x{}) -> scale=({},{}) origin=({},{})",
                stageX1Um,
                stageY1Um,
                stageX2Um,
                stageY2Um,
                imageWidthPx,
                imageHeightPx,
                String.format("%.4f", scaleX),
                String.format("%.4f", scaleY),
                String.format("%.1f", stageX1Um),
                String.format("%.1f", stageY1Um));
        return t;
    }

    public static void saveSlideAlignment(
            Project<BufferedImage> project,
            String sampleName,
            String modality,
            AffineTransform transform,
            BufferedImage processedMacroImage) {
        saveSlideAlignment(project, sampleName, modality, transform, processedMacroImage, null, null);
    }

    /**
     * Save a per-slide alignment plus the flip frame the alignment was built in.
     *
     * <p>The {@code flipMacroX}/{@code flipMacroY} fields capture the macro-image
     * flip that was active during alignment. Back-propagation reads these to
     * decide which sibling (unflipped, flipped X, flipped Y, flipped XY) is
     * the canonical input frame for the saved transform. When null, the
     * loader falls back to the active microscope preset's flipMacroX/Y.
     *
     * @param flipMacroX flip-X state at alignment time, or null to omit the field
     * @param flipMacroY flip-Y state at alignment time, or null to omit the field
     */
    public static void saveSlideAlignment(
            Project<BufferedImage> project,
            String sampleName,
            String modality,
            AffineTransform transform,
            BufferedImage processedMacroImage,
            Boolean flipMacroX,
            Boolean flipMacroY) {

        try {
            // Get project folder
            File projectDir = project.getPath().toFile().getParentFile();

            // Create alignmentFiles directory
            File alignmentDir = new File(projectDir, "alignmentFiles");
            if (!alignmentDir.exists()) {
                alignmentDir.mkdirs();
            }

            // Resolve the current microscope so we can scope this alignment to it.
            // Same project + same sample on a different scope (e.g. PPM acquisition
            // re-opened on OWS3) used to silently load the wrong scope's transform
            // because the file was keyed only on sampleName. Namespacing both the
            // filename and an in-JSON field by microscope eliminates the
            // cross-scope mix-up. Scope name comes from the active config; if we
            // can't determine it, fall back to the legacy unscoped filename so
            // we don't lose the save -- the load path will simply ignore it
            // when no microscope is recorded.
            String microscopeName = null;
            try {
                MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
                if (mgr != null) {
                    microscopeName = mgr.getMicroscopeName();
                }
            } catch (Exception ignore) {
            }

            // Filename: <sample>_<scope>_alignment.json when scope is known,
            // <sample>_alignment.json otherwise (legacy fallback).
            String filename = (microscopeName != null && !microscopeName.isEmpty() && !"Unknown".equals(microscopeName))
                    ? sampleName + "_" + microscopeName + "_alignment.json"
                    : sampleName + "_alignment.json";
            File alignmentFile = new File(alignmentDir, filename);

            // Save the transform data as JSON
            Map<String, Object> alignmentData = new HashMap<>();
            alignmentData.put("sampleName", sampleName);
            alignmentData.put("modality", modality);
            if (microscopeName != null && !microscopeName.isEmpty()) {
                alignmentData.put("microscope", microscopeName);
            }
            alignmentData.put("timestamp", new Date().toString());
            alignmentData.put("transform", new double[] {
                transform.getScaleX(),
                transform.getShearY(),
                transform.getShearX(),
                transform.getScaleY(),
                transform.getTranslateX(),
                transform.getTranslateY()
            });
            if (flipMacroX != null) alignmentData.put("flipMacroX", flipMacroX);
            if (flipMacroY != null) alignmentData.put("flipMacroY", flipMacroY);

            // Mark that the saved macro image is in raw format (no display flips baked in).
            // Old alignment files without this flag have preference flips baked into the PNG.
            if (processedMacroImage != null) {
                alignmentData.put("macroImageRaw", true);
            } else if (alignmentFile.exists()) {
                // When re-saving without a new macro image, preserve the existing
                // macroImageRaw flag from the previous JSON so that the on-disk PNG's
                // format continues to be correctly detected by downstream consumers.
                try {
                    String existingJson =
                            new String(Files.readAllBytes(alignmentFile.toPath()), StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingData = new Gson().fromJson(existingJson, Map.class);
                    Object rawFlag = existingData.get("macroImageRaw");
                    if (rawFlag instanceof Boolean && (Boolean) rawFlag) {
                        alignmentData.put("macroImageRaw", true);
                        logger.debug("Preserved macroImageRaw=true from existing alignment JSON");
                    }
                } catch (Exception e) {
                    logger.debug(
                            "Could not read existing alignment JSON to preserve macroImageRaw: {}", e.getMessage());
                }
            }

            // Convert to JSON and save
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(alignmentData);
            Files.write(alignmentFile.toPath(), json.getBytes(StandardCharsets.UTF_8));

            logger.info("Saved slide-specific alignment to: {}", alignmentFile.getAbsolutePath());

            // Save the processed macro image if provided
            if (processedMacroImage != null) {
                String imageFilename = sampleName + "_alignment.png";
                File imageFile = new File(alignmentDir, imageFilename);
                ImageIO.write(processedMacroImage, "png", imageFile);
                logger.info("Saved processed macro image to: {}", imageFile.getAbsolutePath());
            }

        } catch (Exception e) {
            logger.error("Failed to save slide alignment", e);
        }
    }

    public static AffineTransform loadSlideAlignment(Project<BufferedImage> project, String sampleName) {

        try {
            // Get project folder
            File projectDir = project.getPath().toFile().getParentFile();
            return loadSlideAlignmentFromDirectory(projectDir, sampleName);
        } catch (Exception e) {
            logger.error("Failed to load slide alignment", e);
        }

        return null;
    }

    /**
     * Load slide-specific alignment from a project directory without requiring an open project.
     * Useful for checking if alignment exists before project is loaded.
     *
     * @param projectDir The project directory
     * @param sampleName The sample/slide name
     * @return The slide-specific transform, or null if not found
     */
    public static AffineTransform loadSlideAlignmentFromDirectory(File projectDir, String sampleName) {
        if (projectDir == null || !projectDir.exists() || sampleName == null) {
            return null;
        }

        File alignmentDir = new File(projectDir, "alignmentFiles");
        if (!alignmentDir.exists()) {
            return null;
        }

        // Resolve the active microscope so we only load alignments built for it.
        // Same project + same sample re-opened on a different scope used to load
        // the wrong scope's transform because the file was keyed on sampleName
        // alone. Now we look for <sample>_<scope>_alignment.json first, and
        // fall back to legacy <sample>_alignment.json only if its in-JSON
        // microscope field matches the active scope.
        String activeMicroscope = null;
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                activeMicroscope = mgr.getMicroscopeName();
            }
        } catch (Exception ignore) {
        }

        // Preferred: scope-namespaced filename.
        if (activeMicroscope != null && !activeMicroscope.isEmpty() && !"Unknown".equals(activeMicroscope)) {
            File scoped = new File(alignmentDir, sampleName + "_" + activeMicroscope + "_alignment.json");
            if (scoped.exists()) {
                AffineTransform t = readAlignmentJson(scoped, activeMicroscope);
                if (t != null) return t;
            }
        }

        // Legacy: unnamespaced file. Only use when its microscope field matches
        // the active scope, or when no active scope is known. A legacy file
        // with no microscope field is treated as ambiguous and skipped -- the
        // user should re-run alignment under the current scope rather than
        // risk reusing a different scope's transform (the OWS3-loading-PPM-
        // alignment bug from 2026-04-30).
        File legacy = new File(alignmentDir, sampleName + "_alignment.json");
        if (legacy.exists()) {
            return readAlignmentJson(legacy, activeMicroscope);
        }

        logger.debug("No slide-specific alignment found for sample '{}' under {}", sampleName, alignmentDir);
        return null;
    }

    /**
     * Per-slide alignment plus the flip frame it was built in.
     *
     * <p>{@code flipMacroX} / {@code flipMacroY} are null when the JSON predates
     * the per-slide flip-frame schema (i.e. legacy file). Callers should fall
     * back to the active microscope preset's flipMacroX/Y in that case.
     */
    public static class SlideAlignmentResult {
        private final AffineTransform transform;
        private final Boolean flipMacroX;
        private final Boolean flipMacroY;

        public SlideAlignmentResult(AffineTransform transform, Boolean flipMacroX, Boolean flipMacroY) {
            this.transform = transform;
            this.flipMacroX = flipMacroX;
            this.flipMacroY = flipMacroY;
        }

        public AffineTransform getTransform() { return transform; }
        public Boolean getFlipMacroX() { return flipMacroX; }
        public Boolean getFlipMacroY() { return flipMacroY; }
        public boolean hasFlipFrame() { return flipMacroX != null && flipMacroY != null; }
    }

    /**
     * Like {@link #loadSlideAlignment(Project, String)} but also surfaces the
     * recorded flip frame (or nulls if the JSON is from before phase 3).
     */
    public static SlideAlignmentResult loadSlideAlignmentWithFrame(
            Project<BufferedImage> project, String sampleName) {
        try {
            File projectDir = project.getPath().toFile().getParentFile();
            return loadSlideAlignmentWithFrameFromDirectory(projectDir, sampleName);
        } catch (Exception e) {
            logger.error("Failed to load slide alignment with frame", e);
            return null;
        }
    }

    /** Directory-based variant; mirrors {@link #loadSlideAlignmentFromDirectory(File, String)}. */
    public static SlideAlignmentResult loadSlideAlignmentWithFrameFromDirectory(
            File projectDir, String sampleName) {
        if (projectDir == null || !projectDir.exists() || sampleName == null) return null;
        File alignmentDir = new File(projectDir, "alignmentFiles");
        if (!alignmentDir.exists()) return null;

        String activeMicroscope = null;
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) activeMicroscope = mgr.getMicroscopeName();
        } catch (Exception ignore) {
        }

        if (activeMicroscope != null && !activeMicroscope.isEmpty() && !"Unknown".equals(activeMicroscope)) {
            File scoped = new File(alignmentDir, sampleName + "_" + activeMicroscope + "_alignment.json");
            if (scoped.exists()) {
                SlideAlignmentResult r = readAlignmentJsonWithFrame(scoped, activeMicroscope);
                if (r != null) return r;
            }
        }
        File legacy = new File(alignmentDir, sampleName + "_alignment.json");
        if (legacy.exists()) {
            return readAlignmentJsonWithFrame(legacy, activeMicroscope);
        }
        return null;
    }

    /**
     * Load the slide alignment built for {@code requestedScope}, regardless of
     * the active microscope. Used by back-propagation when the source sub-image
     * was acquired on a different scope than the one currently active: the
     * sub's xy_offset is in {@code requestedScope}'s stage frame, so the
     * inverse alignment must be the one for that same scope. The base SVS
     * pixel coords are absolute, so this composition is correct without
     * needing CrossScopeTransformBuilder.
     *
     * <p>Looks for {@code <sample>_<requestedScope>_alignment.json}. Falls
     * back to the legacy unnamespaced filename only if its in-JSON
     * {@code microscope} field matches {@code requestedScope}.
     *
     * @param project        QuPath project (provides the directory)
     * @param sampleName     sample/slide name
     * @param requestedScope scope name to load the alignment for (e.g. "OWS3")
     * @return the transform + recorded flip frame, or null if no matching file
     */
    public static SlideAlignmentResult loadSlideAlignmentWithFrameForScope(
            Project<BufferedImage> project, String sampleName, String requestedScope) {
        if (project == null || sampleName == null || requestedScope == null || requestedScope.isEmpty()) {
            return null;
        }
        try {
            File projectDir = project.getPath().toFile().getParentFile();
            if (projectDir == null || !projectDir.exists()) return null;
            File alignmentDir = new File(projectDir, "alignmentFiles");
            if (!alignmentDir.exists()) return null;

            File scoped = new File(alignmentDir, sampleName + "_" + requestedScope + "_alignment.json");
            if (scoped.exists()) {
                SlideAlignmentResult r = readAlignmentJsonWithFrame(scoped, requestedScope);
                if (r != null) {
                    logger.info("Loaded slide alignment for cross-scope use: scope='{}' file='{}'",
                            requestedScope, scoped.getName());
                    return r;
                }
            }
            File legacy = new File(alignmentDir, sampleName + "_alignment.json");
            if (legacy.exists()) {
                SlideAlignmentResult r = readAlignmentJsonWithFrame(legacy, requestedScope);
                if (r != null) {
                    logger.info("Loaded legacy slide alignment for cross-scope use: scope='{}' file='{}'",
                            requestedScope, legacy.getName());
                    return r;
                }
            }
            logger.warn("No slide alignment found for sample='{}' scope='{}' under {}",
                    sampleName, requestedScope, alignmentDir);
            return null;
        } catch (Exception e) {
            logger.error("Failed to load slide alignment for scope '{}': {}", requestedScope, e.getMessage());
            return null;
        }
    }

    /**
     * Read a slide alignment JSON and return both the transform and the
     * recorded flip frame (or nulls). Mirrors the validation in
     * {@link #readAlignmentJson(File, String)}.
     */
    private static SlideAlignmentResult readAlignmentJsonWithFrame(File alignmentFile, String activeMicroscope) {
        try {
            String json = new String(Files.readAllBytes(alignmentFile.toPath()), StandardCharsets.UTF_8);
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> data = new Gson().fromJson(json, mapType);
            if (data == null) return null;

            Object scopeObj = data.get("microscope");
            String fileScope = scopeObj instanceof String ? (String) scopeObj : null;
            if (activeMicroscope != null && !activeMicroscope.isEmpty() && !"Unknown".equals(activeMicroscope)) {
                if (fileScope == null) return null;
                if (!fileScope.equals(activeMicroscope)) return null;
            }

            @SuppressWarnings("unchecked")
            List<Double> tv = (List<Double>) data.get("transform");
            if (tv == null || tv.size() != 6) return null;

            AffineTransform transform = new AffineTransform(
                    tv.get(0), tv.get(1), tv.get(2), tv.get(3), tv.get(4), tv.get(5));

            Boolean fx = data.get("flipMacroX") instanceof Boolean ? (Boolean) data.get("flipMacroX") : null;
            Boolean fy = data.get("flipMacroY") instanceof Boolean ? (Boolean) data.get("flipMacroY") : null;
            return new SlideAlignmentResult(transform, fx, fy);
        } catch (Exception e) {
            logger.error("Error reading slide alignment file {}: {}", alignmentFile, e.getMessage());
            return null;
        }
    }

    /**
     * Reads a slide alignment JSON, validates the microscope field if present,
     * and returns the transform. Used by loadSlideAlignmentFromDirectory for
     * both the scope-namespaced and legacy filename paths.
     *
     * @param alignmentFile JSON file on disk
     * @param activeMicroscope active scope name, or null if unknown
     * @return the transform, or null if the file is empty / mismatched scope /
     *         legacy file with no microscope field
     */
    private static AffineTransform readAlignmentJson(File alignmentFile, String activeMicroscope) {
        try {
            String json = new String(Files.readAllBytes(alignmentFile.toPath()), StandardCharsets.UTF_8);
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> data = new Gson().fromJson(json, mapType);
            if (data == null) return null;

            Object scopeObj = data.get("microscope");
            String fileScope = scopeObj instanceof String ? (String) scopeObj : null;

            // Cross-scope guard. Three cases:
            //   active known + file has scope: must match
            //   active known + file has no scope: legacy/ambiguous -> reject
            //   active unknown: accept either way (caller ran without a config)
            if (activeMicroscope != null && !activeMicroscope.isEmpty() && !"Unknown".equals(activeMicroscope)) {
                if (fileScope == null) {
                    logger.warn(
                            "Ignoring legacy slide alignment {} -- no microscope field, cannot verify it was built for active scope '{}'. Re-run alignment under '{}' to refresh.",
                            alignmentFile.getName(),
                            activeMicroscope,
                            activeMicroscope);
                    return null;
                }
                if (!fileScope.equals(activeMicroscope)) {
                    logger.warn(
                            "Ignoring slide alignment {} -- built for scope '{}' but active scope is '{}'.",
                            alignmentFile.getName(),
                            fileScope,
                            activeMicroscope);
                    return null;
                }
            }

            @SuppressWarnings("unchecked")
            List<Double> tv = (List<Double>) data.get("transform");
            if (tv == null || tv.size() != 6) return null;

            AffineTransform transform = new AffineTransform(
                    tv.get(0), tv.get(1), tv.get(2), tv.get(3), tv.get(4), tv.get(5));

            logger.info("Loaded slide-specific alignment from: {}", alignmentFile.getAbsolutePath());
            logger.info(
                    "Alignment scope='{}', timestamp={}, scale=(X={}, Y={})",
                    fileScope == null ? "(legacy/unscoped)" : fileScope,
                    data.get("timestamp"),
                    transform.getScaleX(),
                    transform.getScaleY());
            return transform;
        } catch (Exception e) {
            logger.error("Error reading slide alignment file {}: {}", alignmentFile, e.getMessage());
            return null;
        }
    }

    /**
     * A per-slide alignment record paired with the microscope it was built against.
     * Used by the cross-scope path to surface alignments built for *other* microscopes
     * than the active one -- those would be filtered out by
     * {@link #loadSlideAlignmentFromDirectory(File, String)}.
     */
    public static class SlideAlignmentRecord {
        private final AffineTransform transform;
        private final String microscope;
        private final File file;

        public SlideAlignmentRecord(AffineTransform transform, String microscope, File file) {
            this.transform = transform;
            this.microscope = microscope;
            this.file = file;
        }

        public AffineTransform getTransform() {
            return transform;
        }

        public String getMicroscope() {
            return microscope;
        }

        public File getFile() {
            return file;
        }
    }

    /**
     * Loads every per-slide alignment found in {@code projectDir/alignmentFiles} for the
     * given sample, regardless of which microscope it was built against. Records whose
     * JSON has no {@code microscope} field are skipped (legacy/ambiguous, see
     * {@link #readAlignmentJson(File, String)}).
     *
     * <p>Used by cross-scope acquisition: an alignment built for microscope A can be
     * composed with a saved preset pair to drive microscope B, provided both presets
     * share a {@code sourceScanner} (the macro source).
     *
     * @param projectDir project root containing the {@code alignmentFiles} directory
     * @param sampleName sample / image name; matches {@code <sample>_<scope>_alignment.json}
     * @return list of records, possibly empty; never null
     */
    public static List<SlideAlignmentRecord> loadAllSlideAlignmentsFromDirectory(
            File projectDir, String sampleName) {
        if (projectDir == null || !projectDir.exists() || sampleName == null) {
            return List.of();
        }
        File alignmentDir = new File(projectDir, "alignmentFiles");
        if (!alignmentDir.exists()) {
            return List.of();
        }
        File[] candidates = alignmentDir.listFiles((d, n) -> n.startsWith(sampleName + "_") && n.endsWith("_alignment.json"));
        if (candidates == null || candidates.length == 0) {
            return List.of();
        }
        List<SlideAlignmentRecord> out = new ArrayList<>();
        for (File f : candidates) {
            // Filename pattern: <sample>_<scope>_alignment.json. Anything between the
            // sample-trailing underscore and "_alignment.json" is the scope token.
            String name = f.getName();
            String middle = name.substring(sampleName.length() + 1, name.length() - "_alignment.json".length());
            // The legacy unscoped file has filename <sample>_alignment.json -- the prefix
            // filter above won't match (no second underscore), so we don't need to handle
            // it here. Re-saving under a scope-aware path is the intended migration.
            if (middle.isEmpty()) continue;
            try {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> data = new Gson().fromJson(json, mapType);
                if (data == null) continue;
                Object scopeObj = data.get("microscope");
                String fileScope = scopeObj instanceof String ? (String) scopeObj : null;
                if (fileScope == null) {
                    logger.debug("loadAllSlideAlignments: skipping {} -- no microscope field", f.getName());
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Double> tv = (List<Double>) data.get("transform");
                if (tv == null || tv.size() != 6) continue;
                AffineTransform t = new AffineTransform(
                        tv.get(0), tv.get(1), tv.get(2), tv.get(3), tv.get(4), tv.get(5));
                out.add(new SlideAlignmentRecord(t, fileScope, f));
            } catch (Exception e) {
                logger.warn("loadAllSlideAlignments: failed to parse {}: {}", f.getName(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * Gets the creation date of a slide-specific alignment from a project.
     *
     * @param project The QuPath project
     * @param sampleName The sample name
     * @return The timestamp string from the alignment file, or null if not found
     */
    public static String getSlideAlignmentDate(Project<BufferedImage> project, String sampleName) {
        try {
            File projectDir = project.getPath().toFile().getParentFile();
            return getSlideAlignmentDateFromDirectory(projectDir, sampleName);
        } catch (Exception e) {
            logger.debug("Could not get slide alignment date for {}: {}", sampleName, e.getMessage());
            return null;
        }
    }

    /**
     * Gets the creation date of a slide-specific alignment from a directory.
     *
     * @param projectDir The project directory
     * @param sampleName The sample name
     * @return The timestamp string from the alignment file, or null if not found
     */
    public static String getSlideAlignmentDateFromDirectory(File projectDir, String sampleName) {
        if (projectDir == null || !projectDir.exists() || sampleName == null) {
            return null;
        }

        File alignmentDir = new File(projectDir, "alignmentFiles");
        if (!alignmentDir.exists()) {
            return null;
        }

        File alignmentFile = new File(alignmentDir, sampleName + "_alignment.json");
        if (!alignmentFile.exists()) {
            return null;
        }

        try {
            String json = new String(Files.readAllBytes(alignmentFile.toPath()), StandardCharsets.UTF_8);
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> alignmentData = new Gson().fromJson(json, mapType);

            Object timestamp = alignmentData.get("timestamp");
            if (timestamp != null) {
                return timestamp.toString();
            }
        } catch (Exception e) {
            logger.debug("Could not read alignment date from {}: {}", alignmentFile, e.getMessage());
        }

        return null;
    }

    /**
     * Checks whether the saved macro image for a slide alignment is in raw format
     * (no display flips baked in). Files saved before this format change will not
     * have the {@code macroImageRaw} flag and return {@code false}, meaning the
     * saved PNG has preference flips baked in.
     *
     * @param project The QuPath project
     * @param sampleName The sample name
     * @return true if the saved macro is raw (no flips), false if old format or not found
     */
    public static boolean isSavedMacroRawFormat(Project<BufferedImage> project, String sampleName) {
        try {
            File projectDir = project.getPath().toFile().getParentFile();
            File alignmentDir = new File(projectDir, "alignmentFiles");
            if (!alignmentDir.exists()) {
                return false;
            }

            File alignmentFile = new File(alignmentDir, sampleName + "_alignment.json");
            if (!alignmentFile.exists()) {
                return false;
            }

            String json = new String(Files.readAllBytes(alignmentFile.toPath()), StandardCharsets.UTF_8);
            Gson gson = new Gson();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = gson.fromJson(json, Map.class);
            Object rawFlag = data.get("macroImageRaw");
            return rawFlag instanceof Boolean && (Boolean) rawFlag;

        } catch (Exception e) {
            logger.debug("Could not check macro format for '{}': {}", sampleName, e.getMessage());
            return false;
        }
    }

    /**
     * Loads the saved macro image for a specific slide alignment.
     *
     * @param project The QuPath project
     * @param sampleName The sample name
     * @return The saved macro image, or null if not found
     */
    public static BufferedImage loadSavedMacroImage(Project<BufferedImage> project, String sampleName) {
        try {
            File projectDir = project.getPath().toFile().getParentFile();
            File alignmentDir = new File(projectDir, "alignmentFiles");

            if (!alignmentDir.exists()) {
                return null;
            }

            String imageFilename = sampleName + "_alignment.png";
            File imageFile = new File(alignmentDir, imageFilename);

            if (!imageFile.exists()) {
                logger.debug("No saved macro image found at: {}", imageFile.getAbsolutePath());
                return null;
            }

            BufferedImage macroImage = ImageIO.read(imageFile);
            logger.info("Loaded saved macro image from: {}", imageFile.getAbsolutePath());
            return macroImage;

        } catch (Exception e) {
            logger.error("Failed to load saved macro image", e);
            return null;
        }
    }
}
