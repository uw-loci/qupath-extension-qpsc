package qupath.ext.qpsc.ui.stagemap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data model representing a stage insert configuration with its physical dimensions
 * and slide positions.
 * <p>
 * Stage inserts hold slides in specific positions. Different insert types support
 * different numbers of slides in various orientations (e.g., single slide horizontal,
 * four slides vertical).
 * <p>
 * All dimensions are stored internally in microns (um) for consistency with
 * microscope stage coordinates.
 */
public class StageInsert {

    private static final Logger logger = LoggerFactory.getLogger(StageInsert.class);

    /** Conversion factor from millimeters to microns */
    private static final double MM_TO_UM = 1000.0;

    /**
     * Carrier kind discriminator. Slide-holder is the default and covers the
     * legacy single-slide and quad-slide configurations. Dish-holder and well-plate
     * are overlay-only at this point -- workflows still operate per-slide.
     */
    public enum Kind {
        SLIDE_HOLDER,
        DISH_HOLDER,
        WELL_PLATE
    }

    private final String id;
    private final String name;
    private final double widthUm;
    private final double heightUm;
    private final double originXUm;
    private final double originYUm;
    private final double slideMarginUm;
    private final List<SlidePosition> slides;
    private final Kind kind;

    // Axis inversion flags (detected from calibration points)
    // When true, larger stage values correspond to visually "left" or "top"
    private boolean xAxisInverted = false;
    private boolean yAxisInverted = false;

    // Optional outer-outline diameter (microns) drawn for context on the Stage
    // Map, concentric with the aperture center. Purely visual -- e.g. the 35mm
    // body of a petri dish around its imaging well. 0 means no outline.
    private double outlineDiameterUm = 0.0;

    /**
     * Creates a new StageInsert with the specified parameters.
     *
     * @param id            Unique identifier (e.g., "single_h", "quad_v")
     * @param name          Display name (e.g., "Single Slide (Horizontal)")
     * @param widthMm       Insert width in millimeters
     * @param heightMm      Insert height in millimeters
     * @param originXUm     X coordinate of insert corner in stage coordinates (um)
     * @param originYUm     Y coordinate of insert corner in stage coordinates (um)
     * @param slideMarginUm Safety margin around slides for legal zone (um)
     * @param slides        List of slide positions within the insert
     */
    public StageInsert(
            String id,
            String name,
            double widthMm,
            double heightMm,
            double originXUm,
            double originYUm,
            double slideMarginUm,
            List<SlidePosition> slides) {
        this(id, name, widthMm, heightMm, originXUm, originYUm, slideMarginUm, slides, Kind.SLIDE_HOLDER);
    }

    /**
     * Creates a new StageInsert with an explicit carrier kind.
     */
    public StageInsert(
            String id,
            String name,
            double widthMm,
            double heightMm,
            double originXUm,
            double originYUm,
            double slideMarginUm,
            List<SlidePosition> slides,
            Kind kind) {
        this.id = id;
        this.name = name;
        this.widthUm = widthMm * MM_TO_UM;
        this.heightUm = heightMm * MM_TO_UM;
        this.originXUm = originXUm;
        this.originYUm = originYUm;
        this.slideMarginUm = slideMarginUm;
        this.slides = slides != null ? new ArrayList<>(slides) : new ArrayList<>();
        this.kind = kind != null ? kind : Kind.SLIDE_HOLDER;
    }

    /**
     * Overrides the axis inversion flags.
     *
     * <p>{@link #fromConfigMap} detects inversion from the ordering of the
     * aperture calibration points. The synthesized-insert fallback
     * ({@code StageInsertRegistry.synthesizeFromStageLimits}) has no
     * calibration points, so it sets inversion explicitly from the
     * stage-polarity preference instead. {@code originXUm}/{@code originYUm}
     * must already be the visual top-left corner consistent with these flags
     * (the larger stage value on an inverted axis).
     *
     * @param xInverted true if larger stage X is visually left
     * @param yInverted true if larger stage Y is visually top
     */
    public void setAxisInversion(boolean xInverted, boolean yInverted) {
        this.xAxisInverted = xInverted;
        this.yAxisInverted = yInverted;
    }

    /**
     * Creates a StageInsert from a YAML configuration map using reference point calibration.
     * <p>
     * The configuration uses 4 calibration reference points:
     * <ul>
     *   <li>aperture_left_x_um: Stage X when left aperture edge is centered in FOV</li>
     *   <li>aperture_right_x_um: Stage X when right aperture edge is centered in FOV</li>
     *   <li>slide_top_y_um: Stage Y when top edge of slide is centered in FOV</li>
     *   <li>slide_bottom_y_um: Stage Y when bottom edge of slide is centered in FOV</li>
     * </ul>
     * <p>
     * The aperture width is calculated from the X coordinates.
     * The slide is assumed to be centered vertically within the aperture.
     * <p>
     * Note: Due to optical inversion, "left" may have a larger X value than "right",
     * and "top" may have a larger Y value than "bottom". The code handles both orientations.
     *
     * @param id            Insert identifier
     * @param configMap     Configuration map from YAML
     * @param slideMarginUm Safety margin around slides (from parent config)
     * @return A new StageInsert instance
     */
    @SuppressWarnings("unchecked")
    public static StageInsert fromConfigMap(String id, Map<String, Object> configMap, double slideMarginUm) {
        String name = (String) configMap.getOrDefault("name", id);

        // Read carrier kind (new field; defaults to slide_holder for back-compat)
        Kind kind = parseKind((String) configMap.get("kind"));

        // Read calibration reference points for aperture X bounds
        double apertureLeftX = getDoubleValue(configMap, "aperture_left_x_um", 0.0);
        double apertureRightX = getDoubleValue(configMap, "aperture_right_x_um", 55000.0);

        // Read calibration reference points for aperture Y bounds (if available)
        // Fall back to slide positions if aperture Y not specified
        Double apertureTopY = getDoubleValueOrNull(configMap, "aperture_top_y_um");
        Double apertureBottomY = getDoubleValueOrNull(configMap, "aperture_bottom_y_um");

        // Read slide edge positions
        double slideTopY = getDoubleValue(configMap, "slide_top_y_um", 0.0);
        double slideBottomY = getDoubleValue(configMap, "slide_bottom_y_um", 25000.0);

        // Read optional slide X edge positions (measured, like Y edges)
        Double slideLeftX = getDoubleValueOrNull(configMap, "slide_left_x_um");
        Double slideRightX = getDoubleValueOrNull(configMap, "slide_right_x_um");

        // Detect axis orientation (optics may invert the coordinate system)
        // "Left" visually may correspond to larger X values if optics flip the image.
        // Derive each axis from the SAME source used for that axis's origin/extent:
        //   - X always comes from the aperture_left/right_x_um points.
        //   - Y prefers the aperture_top/bottom_y_um points when present (dishes and
        //     any insert that calibrates aperture Y), falling back to the slide_top/
        //     bottom_y_um edges only when aperture Y is absent. Using slideTopY/
        //     slideBottomY unconditionally was a bug: a dish supplies aperture Y but
        //     not slide Y, so yInverted defaulted to (0 > 25000) = false and the whole
        //     Y axis (origin at line ~187 and the acquisition overlay) rendered
        //     vertically flipped. The well is a centered circle so the flip is
        //     invisible on it, but the acquired image was upside down.
        boolean xInverted = apertureLeftX > apertureRightX;
        boolean yInverted = (apertureTopY != null && apertureBottomY != null)
                ? apertureTopY > apertureBottomY
                : slideTopY > slideBottomY;

        // Calculate aperture dimensions from reference points (always positive)
        double apertureWidthUm = Math.abs(apertureRightX - apertureLeftX);

        // Calculate aperture height - use actual Y coordinates if available
        double apertureHeightUm;
        double originYUm;
        if (apertureTopY != null && apertureBottomY != null) {
            // Use actual aperture Y coordinates
            apertureHeightUm = Math.abs(apertureBottomY - apertureTopY);
            // Origin is at visual top (larger Y if inverted, smaller if not)
            originYUm = yInverted ? Math.max(apertureTopY, apertureBottomY) : Math.min(apertureTopY, apertureBottomY);
        } else {
            // Fall back to estimated height from config
            double apertureHeightMm = getDoubleValue(configMap, "aperture_height_mm", 60.0);
            apertureHeightUm = apertureHeightMm * MM_TO_UM;
            // Estimate origin based on slide position (old behavior)
            double slideHeightUm = Math.abs(slideBottomY - slideTopY);
            double slideYOffsetUm = (apertureHeightUm - slideHeightUm) / 2.0;
            double slideTopStageY = yInverted ? Math.max(slideTopY, slideBottomY) : Math.min(slideTopY, slideBottomY);
            originYUm = slideTopStageY - (yInverted ? -slideYOffsetUm : slideYOffsetUm);
        }

        // Read slide dimensions - use configured physical size, not measured aperture extent.
        // The measured slide_top/bottom_y_um represent the visible edges through the aperture,
        // which is smaller than the actual slide (holder covers the edges).
        double slideWidthMm = getDoubleValue(configMap, "slide_width_mm", 75.0);
        double slideHeightMm = getDoubleValue(configMap, "slide_height_mm", 25.0);
        double slideWidthUm = slideWidthMm * MM_TO_UM;
        double slideHeightUm = slideHeightMm * MM_TO_UM;

        // Origin X is the top-left corner in VISUAL space
        // For inverted X: visual left = larger stage X, so origin = max X
        double originXUm =
                xInverted ? Math.max(apertureLeftX, apertureRightX) : Math.min(apertureLeftX, apertureRightX);

        // Calculate slide's position within aperture.
        // Center the full physical slide on the midpoint of the measured (visible) edges.
        // The measured edges are where the slide is visible through the aperture opening;
        // the full slide extends under the holder material on both sides.
        double slideCenterStageY = (slideTopY + slideBottomY) / 2.0;
        double slideCenterInsertY = Math.abs(slideCenterStageY - originYUm);
        double slideYOffsetUm = slideCenterInsertY - slideHeightUm / 2.0;

        // Slide X offset: use measured edges if available, otherwise center in aperture.
        double slideXOffsetUm;
        if (slideLeftX != null && slideRightX != null) {
            // Measured slide X edges - center the physical slide on their midpoint
            double slideCenterStageX = (slideLeftX + slideRightX) / 2.0;
            double slideCenterInsertX = Math.abs(slideCenterStageX - originXUm);
            slideXOffsetUm = slideCenterInsertX - slideWidthUm / 2.0;
            logger.info(
                    "Slide X from measured edges: left={}, right={}, center={}, offset={} um",
                    String.format("%.0f", slideLeftX),
                    String.format("%.0f", slideRightX),
                    String.format("%.0f", slideCenterStageX),
                    String.format("%.0f", slideXOffsetUm));
        } else {
            // No measured edges - assume slide is centered in aperture
            slideXOffsetUm = (apertureWidthUm - slideWidthUm) / 2.0;
        }

        // Build slide positions
        List<SlidePosition> slides = new ArrayList<>();

        // Precedence: explicit per-slot captured centers (slideK_center_x_um/_y_um) >
        // an explicit samples list > legacy num_slides / slide_spacing_mm pitch.
        int numSlides = ((Number) configMap.getOrDefault("num_slides", 1)).intValue();
        Object samplesObj = configMap.get("samples");
        boolean hasPerSlotCenters = false;
        for (int k = 1; k <= Math.max(numSlides, 1); k++) {
            if (getDoubleValueOrNull(configMap, "slide" + k + "_center_x_um") != null) {
                hasPerSlotCenters = true;
                break;
            }
        }
        if (hasPerSlotCenters) {
            // Per-slot calibration: each slot's center is an absolute stage position captured
            // via the Stage Map. Convert it to a local insert offset exactly as the single-slide
            // center is derived above (|centerStage - origin|, then subtract the half-size).
            for (int k = 1; k <= Math.max(numSlides, 1); k++) {
                Double centerX = getDoubleValueOrNull(configMap, "slide" + k + "_center_x_um");
                Double centerY = getDoubleValueOrNull(configMap, "slide" + k + "_center_y_um");
                if (centerX == null || centerY == null) {
                    continue;
                }
                double localCenterX = Math.abs(centerX - originXUm);
                double localCenterY = Math.abs(centerY - originYUm);
                slides.add(new SlidePosition(
                        "Slide " + k,
                        (localCenterX - slideWidthUm / 2.0) / MM_TO_UM,
                        (localCenterY - slideHeightUm / 2.0) / MM_TO_UM,
                        slideWidthMm,
                        slideHeightUm / MM_TO_UM,
                        slideHeightMm > slideWidthMm ? 90 : 0));
            }
        } else if (samplesObj instanceof List) {
            buildSamplesFromList(
                    slides,
                    (List<Map<String, Object>>) samplesObj,
                    apertureWidthUm,
                    apertureHeightUm,
                    slideWidthUm,
                    slideHeightUm);
        } else {
            // Legacy pitch path
            double slideSpacingMm = getDoubleValue(configMap, "slide_spacing_mm", 0.0);

            if (numSlides > 1 && slideSpacingMm > 0) {
                // Multi-slide configuration
                double slideSpacingUm = slideSpacingMm * MM_TO_UM;
                double totalWidth = (numSlides - 1) * slideSpacingUm + slideWidthUm;
                double startX = (apertureWidthUm - totalWidth) / 2.0; // Center the group

                for (int i = 0; i < numSlides; i++) {
                    double xOffset = startX + i * slideSpacingUm;
                    slides.add(new SlidePosition(
                            "Slide " + (i + 1),
                            xOffset / MM_TO_UM,
                            slideYOffsetUm / MM_TO_UM,
                            slideWidthMm,
                            slideHeightUm / MM_TO_UM,
                            slideHeightMm > slideWidthMm ? 90 : 0));
                }
            } else {
                // Single slide configuration
                slides.add(new SlidePosition(
                        "Slide 1",
                        slideXOffsetUm / MM_TO_UM,
                        slideYOffsetUm / MM_TO_UM,
                        slideWidthMm,
                        slideHeightUm / MM_TO_UM,
                        0));
            }
        }

        // Convert aperture dimensions back to mm for constructor
        double apertureWidthMm = apertureWidthUm / MM_TO_UM;
        double apertureHeightMm = apertureHeightUm / MM_TO_UM;

        // Store axis inversion flags in the insert for rendering
        StageInsert insert = new StageInsert(
                id, name, apertureWidthMm, apertureHeightMm, originXUm, originYUm, slideMarginUm, slides, kind);
        insert.xAxisInverted = xInverted;
        insert.yAxisInverted = yInverted;
        // Optional outer outline (e.g. a petri dish body around its well).
        insert.outlineDiameterUm = getDoubleValue(configMap, "dish_diameter_mm", 0.0) * MM_TO_UM;

        logger.info(
                "fromConfigMap '{}': aperture={}x{} mm, origin=({}, {}) um, "
                        + "xInverted={}, yInverted={}, slideYOffset={} um, slides={}",
                id,
                String.format("%.1f", apertureWidthMm),
                String.format("%.1f", apertureHeightMm),
                String.format("%.0f", originXUm),
                String.format("%.0f", originYUm),
                xInverted,
                yInverted,
                String.format("%.0f", slideYOffsetUm),
                slides.size());

        return insert;
    }

    /**
     * Parses a carrier kind string from YAML. Accepts slide_holder, dish_holder,
     * well_plate (case-insensitive). Defaults to SLIDE_HOLDER on null or unknown.
     */
    private static Kind parseKind(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Kind.SLIDE_HOLDER;
        }
        String norm = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (norm) {
            case "dish_holder", "dish" -> Kind.DISH_HOLDER;
            case "well_plate", "plate", "wells" -> Kind.WELL_PLATE;
            case "slide_holder", "slides", "slide" -> Kind.SLIDE_HOLDER;
            default -> {
                logger.warn("Unknown carrier kind '{}', defaulting to SLIDE_HOLDER", raw);
                yield Kind.SLIDE_HOLDER;
            }
        };
    }

    /**
     * Populates the slides list from an explicit `samples:` YAML list. Each entry
     * is a map with a `kind` (slide / dish / well / well_grid) and shape-specific
     * fields. Offsets are interpreted as center-relative-to-insert-center for
     * ergonomics; we convert to the legacy corner-offset coordinate space.
     */
    @SuppressWarnings("unchecked")
    private static void buildSamplesFromList(
            List<SlidePosition> out,
            List<Map<String, Object>> samples,
            double apertureWidthUm,
            double apertureHeightUm,
            double defaultSlideWidthUm,
            double defaultSlideHeightUm) {
        int autoIndex = 0;
        for (Map<String, Object> sample : samples) {
            String sampleKind =
                    String.valueOf(sample.getOrDefault("kind", "slide")).trim().toLowerCase(java.util.Locale.ROOT);
            if ("well_grid".equals(sampleKind)) {
                expandWellGrid(out, sample, apertureWidthUm, apertureHeightUm);
                continue;
            }
            autoIndex++;
            String id = String.valueOf(sample.getOrDefault("id", sampleKind + "_" + autoIndex));
            String label = String.valueOf(sample.getOrDefault("label", "Sample " + autoIndex));
            SlidePosition.Shape shape = parseShape((String) sample.get("shape"), sampleKind);
            double rotationDeg = getDoubleValue(sample, "rotation_deg", 0.0);
            double centerOffXUm = getDoubleValue(sample, "center_offset_x_mm", 0.0) * MM_TO_UM;
            double centerOffYUm = getDoubleValue(sample, "center_offset_y_mm", 0.0) * MM_TO_UM;
            String anchor = String.valueOf(sample.getOrDefault("label_anchor", "center"))
                    .trim()
                    .toLowerCase(java.util.Locale.ROOT);

            double widthUm;
            double heightUm;
            double lipAUm = getDoubleValue(sample, "lip_a_mm", 0.0) * MM_TO_UM;
            double lipBUm = getDoubleValue(sample, "lip_b_mm", 0.0) * MM_TO_UM;
            double lipInsetUm = getDoubleValue(sample, "lip_inset_mm", 0.0) * MM_TO_UM;

            if (shape == SlidePosition.Shape.CIRCLE) {
                double diameterMm = getDoubleValue(sample, "diameter_mm", 0.0);
                if (diameterMm <= 0) {
                    diameterMm = getDoubleValue(sample, "well_diameter_mm", 0.0);
                }
                widthUm = diameterMm * MM_TO_UM;
                heightUm = widthUm;
            } else {
                double widthMm = getDoubleValue(sample, "width_mm", defaultSlideWidthUm / MM_TO_UM);
                double heightMm = getDoubleValue(sample, "height_mm", defaultSlideHeightUm / MM_TO_UM);
                widthUm = widthMm * MM_TO_UM;
                heightUm = heightMm * MM_TO_UM;
            }

            // Convert center-of-insert offset to corner offset from insert origin
            double xOffsetUm = (apertureWidthUm / 2.0 + centerOffXUm) - widthUm / 2.0;
            double yOffsetUm = (apertureHeightUm / 2.0 + centerOffYUm) - heightUm / 2.0;

            SlidePosition.Kind sk = parseSampleKind(sampleKind);
            SlidePosition pos = new SlidePosition(
                    label,
                    xOffsetUm / MM_TO_UM,
                    yOffsetUm / MM_TO_UM,
                    widthUm / MM_TO_UM,
                    heightUm / MM_TO_UM,
                    rotationDeg,
                    id,
                    sk,
                    shape,
                    lipAUm,
                    lipBUm,
                    lipInsetUm,
                    anchor);
            out.add(pos);
        }
    }

    /**
     * Expands a `well_grid` shorthand into N circular WELL samples.
     * Offsets are computed relative to the insert center so that a1_center_offset
     * defines the A1 well's center in insert-center coordinates.
     */
    private static void expandWellGrid(
            List<SlidePosition> out, Map<String, Object> grid, double apertureWidthUm, double apertureHeightUm) {
        int rows = ((Number) grid.getOrDefault("rows", 2)).intValue();
        int cols = ((Number) grid.getOrDefault("cols", 3)).intValue();
        double wellDiameterMm = getDoubleValue(grid, "well_diameter_mm", 34.8);
        double rowSpacingMm = getDoubleValue(grid, "row_spacing_mm", 39.12);
        double colSpacingMm = getDoubleValue(grid, "col_spacing_mm", 39.12);
        double a1OffXMm = getDoubleValue(grid, "a1_center_offset_x_mm", -colSpacingMm * (cols - 1) / 2.0);
        double a1OffYMm = getDoubleValue(grid, "a1_center_offset_y_mm", -rowSpacingMm * (rows - 1) / 2.0);
        double lipInsetMm = getDoubleValue(grid, "lip_inset_mm", 0.0);
        String rowLabels = String.valueOf(grid.getOrDefault("row_labels", defaultRowLabels(rows)));
        String colLabels = String.valueOf(grid.getOrDefault("col_labels", defaultColLabels(cols)));

        double diameterUm = wellDiameterMm * MM_TO_UM;
        double lipInsetUm = lipInsetMm * MM_TO_UM;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double centerOffXUm = (a1OffXMm + c * colSpacingMm) * MM_TO_UM;
                double centerOffYUm = (a1OffYMm + r * rowSpacingMm) * MM_TO_UM;
                double xOffsetUm = (apertureWidthUm / 2.0 + centerOffXUm) - diameterUm / 2.0;
                double yOffsetUm = (apertureHeightUm / 2.0 + centerOffYUm) - diameterUm / 2.0;
                char rowCh = r < rowLabels.length() ? rowLabels.charAt(r) : (char) ('A' + r);
                char colCh = c < colLabels.length() ? colLabels.charAt(c) : (char) ('1' + c);
                String label = "" + rowCh + colCh;
                String id = "well_" + label;
                SlidePosition pos = new SlidePosition(
                        label,
                        xOffsetUm / MM_TO_UM,
                        yOffsetUm / MM_TO_UM,
                        diameterUm / MM_TO_UM,
                        diameterUm / MM_TO_UM,
                        0,
                        id,
                        SlidePosition.Kind.WELL,
                        SlidePosition.Shape.CIRCLE,
                        0,
                        0,
                        lipInsetUm,
                        "center");
                out.add(pos);
            }
        }
    }

    private static String defaultRowLabels(int rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            sb.append((char) ('A' + i));
        }
        return sb.toString();
    }

    private static String defaultColLabels(int cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols; i++) {
            sb.append((char) ('1' + i));
        }
        return sb.toString();
    }

    private static SlidePosition.Shape parseShape(String raw, String sampleKind) {
        if (raw != null) {
            String norm = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (norm.equals("circle") || norm.equals("disc") || norm.equals("disk")) {
                return SlidePosition.Shape.CIRCLE;
            }
            if (norm.equals("rectangle") || norm.equals("rect")) {
                return SlidePosition.Shape.RECTANGLE;
            }
        }
        // Default shape per kind
        return switch (sampleKind) {
            case "dish", "well" -> SlidePosition.Shape.CIRCLE;
            default -> SlidePosition.Shape.RECTANGLE;
        };
    }

    private static SlidePosition.Kind parseSampleKind(String raw) {
        if (raw == null) return SlidePosition.Kind.SLIDE;
        return switch (raw) {
            case "dish" -> SlidePosition.Kind.DISH;
            case "well" -> SlidePosition.Kind.WELL;
            default -> SlidePosition.Kind.SLIDE;
        };
    }

    private static double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    private static Double getDoubleValueOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    // ========== Getters ==========

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Returns insert width in microns */
    public double getWidthUm() {
        return widthUm;
    }

    /** Returns insert height in microns */
    public double getHeightUm() {
        return heightUm;
    }

    /** Returns the X coordinate of the insert's top-left corner in stage coordinates (um) */
    public double getOriginXUm() {
        return originXUm;
    }

    /** Returns the Y coordinate of the insert's top-left corner in stage coordinates (um) */
    public double getOriginYUm() {
        return originYUm;
    }

    /** Returns the safety margin around slides in microns */
    public double getSlideMarginUm() {
        return slideMarginUm;
    }

    /** Returns an unmodifiable list of slide positions */
    public List<SlidePosition> getSlides() {
        return Collections.unmodifiableList(slides);
    }

    /** Returns the carrier kind (slide_holder, dish_holder, well_plate). */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the outer-outline diameter in microns (e.g. a petri dish body),
     * or 0 if none. Drawn for context on the Stage Map, concentric with the
     * aperture center. Purely visual -- not a sample, move target, or legal zone.
     */
    public double getOutlineDiameterUm() {
        return outlineDiameterUm;
    }

    /**
     * Returns only the SLIDE-kind samples. Useful for callers that operate on
     * rectangular slide positions specifically (e.g., the MS workflow).
     */
    public List<SlidePosition> getSlideSamples() {
        List<SlidePosition> filtered = new ArrayList<>();
        for (SlidePosition s : slides) {
            if (s.getKind() == SlidePosition.Kind.SLIDE) {
                filtered.add(s);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    /**
     * Returns true if the stage X axis is inverted (larger X = visual left).
     * This is a STAGE HARDWARE property auto-detected from insert calibration data
     * (e.g., apertureLeftX > apertureRightX means X is inverted).
     * Not the same as optical flip -- see CLAUDE.md "COORDINATE SYSTEM TERMINOLOGY".
     */
    public boolean isXAxisInverted() {
        return xAxisInverted;
    }

    /**
     * Returns true if the stage Y axis is inverted (larger Y = visual top).
     * This is a STAGE HARDWARE property auto-detected from insert calibration data.
     * Not the same as optical flip -- see CLAUDE.md "COORDINATE SYSTEM TERMINOLOGY".
     */
    public boolean isYAxisInverted() {
        return yAxisInverted;
    }

    // ========== View Bounds ==========

    /**
     * Computes a bounding box focused on all slides plus a margin, for rendering purposes.
     * <p>
     * Instead of fitting the entire aperture into the canvas (which wastes space when the
     * aperture is much larger than the slides), this returns a tighter view rectangle
     * that shows just the slides with some context around them.
     * <p>
     * The returned values are in microns, relative to the insert origin (same coordinate
     * space used by slide offsets and canvas rendering).
     *
     * @param marginUm Padding around the slide bounding box in microns (e.g., 5000 for 5mm)
     * @return Array of [viewX, viewY, viewWidth, viewHeight] in microns relative to insert origin,
     *         or the full aperture bounds if no slides are configured
     */
    public double[] getSlideViewBounds(double marginUm) {
        if (slides.isEmpty()) {
            logger.info("getSlideViewBounds: no slides, returning full aperture {}x{} um", widthUm, heightUm);
            return new double[] {0, 0, widthUm, heightUm};
        }

        // Find bounding box of all slides (in insert-relative coordinates)
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (SlidePosition slide : slides) {
            logger.info(
                    "getSlideViewBounds: slide '{}' offset=({}, {}) size={}x{} um",
                    slide.getName(),
                    String.format("%.0f", slide.getXOffsetUm()),
                    String.format("%.0f", slide.getYOffsetUm()),
                    String.format("%.0f", slide.getWidthUm()),
                    String.format("%.0f", slide.getHeightUm()));
            minX = Math.min(minX, slide.getXOffsetUm());
            minY = Math.min(minY, slide.getYOffsetUm());
            maxX = Math.max(maxX, slide.getXOffsetUm() + slide.getWidthUm());
            maxY = Math.max(maxY, slide.getYOffsetUm() + slide.getHeightUm());
        }

        logger.info(
                "getSlideViewBounds: slide bbox [{}, {}] to [{}, {}], margin={} um",
                String.format("%.0f", minX),
                String.format("%.0f", minY),
                String.format("%.0f", maxX),
                String.format("%.0f", maxY),
                String.format("%.0f", marginUm));

        // Include the optional outer outline (e.g. a petri dish body) so the
        // view shows the whole dish, not just the well. The outline is
        // concentric with the aperture center and may extend beyond the
        // aperture (a 35mm dish around a 20mm well).
        double rawMinX = minX, rawMinY = minY, rawMaxX = maxX, rawMaxY = maxY;
        if (outlineDiameterUm > 0) {
            double r = outlineDiameterUm / 2.0;
            double cx = widthUm / 2.0;
            double cy = heightUm / 2.0;
            rawMinX = Math.min(rawMinX, cx - r);
            rawMinY = Math.min(rawMinY, cy - r);
            rawMaxX = Math.max(rawMaxX, cx + r);
            rawMaxY = Math.max(rawMaxY, cy + r);
        }
        minX = rawMinX;
        minY = rawMinY;
        maxX = rawMaxX;
        maxY = rawMaxY;

        // Add margin
        minX -= marginUm;
        minY -= marginUm;
        maxX += marginUm;
        maxY += marginUm;

        // Clamp so we don't show empty holder beyond the aperture -- but allow
        // the view to extend to anything that legitimately sticks out past the
        // aperture (a dish outline larger than its well-sized aperture). The
        // clamp bound is the union of the aperture and the raw sample/outline
        // extent, so normal slides still clamp to the aperture exactly while
        // dishes are shown in full.
        double preClampMinX = minX, preClampMinY = minY, preClampMaxX = maxX, preClampMaxY = maxY;
        minX = Math.max(Math.min(0, rawMinX), minX);
        minY = Math.max(Math.min(0, rawMinY), minY);
        maxX = Math.min(Math.max(widthUm, rawMaxX), maxX);
        maxY = Math.min(Math.max(heightUm, rawMaxY), maxY);

        logger.info(
                "getSlideViewBounds: after margin [{}, {}] to [{}, {}], "
                        + "after clamp [{}, {}] to [{}, {}], aperture={}x{} um",
                String.format("%.0f", preClampMinX),
                String.format("%.0f", preClampMinY),
                String.format("%.0f", preClampMaxX),
                String.format("%.0f", preClampMaxY),
                String.format("%.0f", minX),
                String.format("%.0f", minY),
                String.format("%.0f", maxX),
                String.format("%.0f", maxY),
                String.format("%.0f", widthUm),
                String.format("%.0f", heightUm));

        return new double[] {minX, minY, maxX - minX, maxY - minY};
    }

    // ========== Coordinate Bounds ==========

    /**
     * Returns the minimum X coordinate of the insert in stage coordinates.
     * Accounts for axis inversion.
     */
    public double getMinStageX() {
        if (xAxisInverted) {
            return originXUm - widthUm;
        }
        return originXUm;
    }

    /**
     * Returns the maximum X coordinate of the insert in stage coordinates.
     * Accounts for axis inversion.
     */
    public double getMaxStageX() {
        if (xAxisInverted) {
            return originXUm;
        }
        return originXUm + widthUm;
    }

    /**
     * Returns the minimum Y coordinate of the insert in stage coordinates.
     * Accounts for axis inversion.
     */
    public double getMinStageY() {
        if (yAxisInverted) {
            return originYUm - heightUm;
        }
        return originYUm;
    }

    /**
     * Returns the maximum Y coordinate of the insert in stage coordinates.
     * Accounts for axis inversion.
     */
    public double getMaxStageY() {
        if (yAxisInverted) {
            return originYUm;
        }
        return originYUm + heightUm;
    }

    // ========== Position Validation ==========

    /**
     * Checks if a stage position is within the insert boundaries.
     * Accounts for axis inversion.
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return true if the position is within the insert
     */
    public boolean isPositionInInsert(double stageX, double stageY) {
        return stageX >= getMinStageX()
                && stageX <= getMaxStageX()
                && stageY >= getMinStageY()
                && stageY <= getMaxStageY();
    }

    /**
     * True when this insert declares an outer outline (e.g. a petri-dish body
     * around its imaging well), set via {@code dish_diameter_mm}. The outline is
     * a circle concentric with the aperture center and may extend beyond the
     * aperture.
     */
    public boolean hasOutline() {
        return outlineDiameterUm > 0;
    }

    /**
     * Checks whether a stage position lies within the outer outline circle
     * (e.g. the 35mm petri-dish body). The outline is concentric with the
     * aperture center and is generally larger than the aperture, so this admits
     * positions outside {@link #isPositionInInsert} but still on the dish --
     * which is what makes well-edge calibration practical. Returns {@code false}
     * when no outline is configured.
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return true if the position is within the outline circle
     */
    public boolean isPositionInOutline(double stageX, double stageY) {
        if (outlineDiameterUm <= 0) {
            return false;
        }
        double cx = (getMinStageX() + getMaxStageX()) / 2.0;
        double cy = (getMinStageY() + getMaxStageY()) / 2.0;
        double r = outlineDiameterUm / 2.0;
        double dx = stageX - cx;
        double dy = stageY - cy;
        return (dx * dx + dy * dy) <= (r * r);
    }

    /**
     * Checks if a stage position is on any slide (within the slide boundaries).
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return true if the position is on a slide
     */
    public boolean isPositionOnSlide(double stageX, double stageY) {
        for (SlidePosition slide : slides) {
            if (slide.containsStagePosition(stageX, stageY, originXUm, originYUm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a stage position is in a "legal" zone (on or near a slide).
     * The legal zone includes the slide area plus the configured safety margin.
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return true if the position is in a legal movement zone
     */
    public boolean isPositionLegal(double stageX, double stageY) {
        for (SlidePosition slide : slides) {
            if (slide.containsStagePositionWithMargin(
                    stageX, stageY, originXUm, originYUm, slideMarginUm, xAxisInverted, yAxisInverted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the slide at the specified stage position, or null if not on a slide.
     *
     * @param stageX X coordinate in stage coordinates (um)
     * @param stageY Y coordinate in stage coordinates (um)
     * @return The SlidePosition at this location, or null
     */
    public SlidePosition getSlideAtPosition(double stageX, double stageY) {
        for (SlidePosition slide : slides) {
            if (slide.containsStagePosition(stageX, stageY, originXUm, originYUm)) {
                return slide;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name;
    }

    // ========== Inner Class: SlidePosition ==========

    /**
     * Represents a single slide position within a stage insert.
     * <p>
     * Coordinates are relative to the insert's top-left corner, not stage coordinates.
     * Standard microscope slides are 25mm x 75mm (1" x 3").
     */
    public static class SlidePosition {

        /** Sample kind. SLIDE is the default for legacy carriers. */
        public enum Kind {
            SLIDE,
            DISH,
            WELL
        }

        /** Geometric shape of the sample's hit-test region. */
        public enum Shape {
            RECTANGLE,
            CIRCLE
        }

        private final String name;
        private final double xOffsetUm;
        private final double yOffsetUm;
        private final double widthUm;
        private final double heightUm;
        private final double rotationDeg;

        // New fields (defaults preserve legacy rectangle behaviour)
        private final String id;
        private final Kind kind;
        private final Shape shape;
        /** Lip width at end "a" of the long axis in microns. Rectangle samples only. */
        private final double lipAUm;
        /** Lip width at end "b" of the long axis in microns. Rectangle samples only. */
        private final double lipBUm;
        /** Radial lip inset in microns. Circle samples only. */
        private final double lipInsetUm;
        /** Label anchor hint for overlay rendering ("center", "bottom", "top", "outside_top", "outside_bottom"). */
        private final String labelAnchor;

        /**
         * Creates a new slide position with legacy rectangle defaults.
         *
         * @param name        Display name (e.g., "Slide 1")
         * @param xOffsetMm   X offset from insert origin in millimeters
         * @param yOffsetMm   Y offset from insert origin in millimeters
         * @param widthMm     Slide width in millimeters
         * @param heightMm    Slide height in millimeters
         * @param rotationDeg Rotation angle in degrees (0 = horizontal, 90 = vertical)
         */
        public SlidePosition(
                String name, double xOffsetMm, double yOffsetMm, double widthMm, double heightMm, double rotationDeg) {
            this(
                    name,
                    xOffsetMm,
                    yOffsetMm,
                    widthMm,
                    heightMm,
                    rotationDeg,
                    name,
                    Kind.SLIDE,
                    Shape.RECTANGLE,
                    0,
                    0,
                    0,
                    "center");
        }

        /**
         * Creates a new sample with explicit kind, shape, and lip fields. Offsets
         * are still corner-relative-to-insert-origin (in mm) to keep parity with
         * the legacy constructor. Lip fields are in microns.
         */
        public SlidePosition(
                String name,
                double xOffsetMm,
                double yOffsetMm,
                double widthMm,
                double heightMm,
                double rotationDeg,
                String id,
                Kind kind,
                Shape shape,
                double lipAUm,
                double lipBUm,
                double lipInsetUm,
                String labelAnchor) {
            this.name = name;
            this.xOffsetUm = xOffsetMm * MM_TO_UM;
            this.yOffsetUm = yOffsetMm * MM_TO_UM;
            this.widthUm = widthMm * MM_TO_UM;
            this.heightUm = heightMm * MM_TO_UM;
            this.rotationDeg = rotationDeg;
            this.id = id != null ? id : name;
            this.kind = kind != null ? kind : Kind.SLIDE;
            this.shape = shape != null ? shape : Shape.RECTANGLE;
            this.lipAUm = lipAUm;
            this.lipBUm = lipBUm;
            this.lipInsetUm = lipInsetUm;
            this.labelAnchor = labelAnchor != null ? labelAnchor : "center";
        }

        /**
         * Creates a SlidePosition from a YAML configuration map.
         *
         * @param configMap Configuration map from YAML
         * @return A new SlidePosition instance
         */
        public static SlidePosition fromConfigMap(Map<String, Object> configMap) {
            String name = (String) configMap.getOrDefault("name", "Slide");
            double xOffsetMm = getDoubleValue(configMap, "x_offset_mm", 0.0);
            double yOffsetMm = getDoubleValue(configMap, "y_offset_mm", 0.0);
            double widthMm = getDoubleValue(configMap, "width_mm", 75.0);
            double heightMm = getDoubleValue(configMap, "height_mm", 25.0);
            double rotationDeg = getDoubleValue(configMap, "rotation_deg", 0.0);

            return new SlidePosition(name, xOffsetMm, yOffsetMm, widthMm, heightMm, rotationDeg);
        }

        private static double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            return defaultValue;
        }

        // ========== Getters ==========

        public String getName() {
            return name;
        }

        /** Returns X offset from insert origin in microns */
        public double getXOffsetUm() {
            return xOffsetUm;
        }

        /** Returns Y offset from insert origin in microns */
        public double getYOffsetUm() {
            return yOffsetUm;
        }

        /** Returns slide width in microns */
        public double getWidthUm() {
            return widthUm;
        }

        /** Returns slide height in microns */
        public double getHeightUm() {
            return heightUm;
        }

        /** Returns rotation in degrees (0 = horizontal, 90 = vertical) */
        public double getRotationDeg() {
            return rotationDeg;
        }

        /** Returns the stable id of this sample (e.g., "slide_1", "well_A3"). */
        public String getId() {
            return id;
        }

        /** Returns the sample kind (SLIDE / DISH / WELL). */
        public Kind getKind() {
            return kind;
        }

        /** Returns the geometric shape (RECTANGLE / CIRCLE). */
        public Shape getShape() {
            return shape;
        }

        /** Returns the lip width at end "a" of the long axis, in microns. */
        public double getLipAUm() {
            return lipAUm;
        }

        /** Returns the lip width at end "b" of the long axis, in microns. */
        public double getLipBUm() {
            return lipBUm;
        }

        /** Returns the radial lip inset in microns (circles only). */
        public double getLipInsetUm() {
            return lipInsetUm;
        }

        /** Returns the label anchor hint for overlay rendering. */
        public String getLabelAnchor() {
            return labelAnchor;
        }

        /** Returns true if this sample's long axis is aligned with stage Y (rotation_deg == 90). */
        public boolean isVerticallyOriented() {
            return Math.abs(rotationDeg - 90.0) < 1.0 || Math.abs(rotationDeg + 90.0) < 1.0;
        }

        // ========== Position Checking ==========

        /**
         * Checks if a stage position is within this slide's boundaries.
         *
         * @param stageX        Stage X coordinate (um)
         * @param stageY        Stage Y coordinate (um)
         * @param insertOriginX Insert origin X in stage coordinates (um)
         * @param insertOriginY Insert origin Y in stage coordinates (um)
         * @return true if the position is on this slide
         */
        public boolean containsStagePosition(double stageX, double stageY, double insertOriginX, double insertOriginY) {
            // Default to non-inverted for backward compatibility
            return containsStagePositionWithMargin(stageX, stageY, insertOriginX, insertOriginY, 0, false, false);
        }

        /**
         * Checks if a stage position is within this slide's boundaries plus a margin.
         * Handles axis inversion when optics flip the coordinate system.
         *
         * @param stageX        Stage X coordinate (um)
         * @param stageY        Stage Y coordinate (um)
         * @param insertOriginX Insert origin X in stage coordinates (um)
         * @param insertOriginY Insert origin Y in stage coordinates (um)
         * @param marginUm      Additional margin around the slide (um)
         * @param xInverted     True if X axis is inverted (origin is max X)
         * @param yInverted     True if Y axis is inverted (origin is max Y)
         * @return true if the position is within the slide plus margin
         */
        public boolean containsStagePositionWithMargin(
                double stageX,
                double stageY,
                double insertOriginX,
                double insertOriginY,
                double marginUm,
                boolean xInverted,
                boolean yInverted) {
            if (shape == Shape.CIRCLE) {
                // Circle: AABB centered on the bounding box center.
                double centerX = xInverted
                        ? insertOriginX - xOffsetUm - widthUm / 2.0
                        : insertOriginX + xOffsetUm + widthUm / 2.0;
                double centerY = yInverted
                        ? insertOriginY - yOffsetUm - heightUm / 2.0
                        : insertOriginY + yOffsetUm + heightUm / 2.0;
                double radius = widthUm / 2.0 + marginUm;
                double dx = stageX - centerX;
                double dy = stageY - centerY;
                return (dx * dx + dy * dy) <= (radius * radius);
            }

            double slideMinX, slideMaxX, slideMinY, slideMaxY;

            if (xInverted) {
                // Origin is at max X, slide extends in negative direction
                slideMaxX = insertOriginX - xOffsetUm + marginUm;
                slideMinX = insertOriginX - xOffsetUm - widthUm - marginUm;
            } else {
                // Origin is at min X, slide extends in positive direction
                slideMinX = insertOriginX + xOffsetUm - marginUm;
                slideMaxX = insertOriginX + xOffsetUm + widthUm + marginUm;
            }

            if (yInverted) {
                // Origin is at max Y, slide extends in negative direction
                slideMaxY = insertOriginY - yOffsetUm + marginUm;
                slideMinY = insertOriginY - yOffsetUm - heightUm - marginUm;
            } else {
                // Origin is at min Y, slide extends in positive direction
                slideMinY = insertOriginY + yOffsetUm - marginUm;
                slideMaxY = insertOriginY + yOffsetUm + heightUm + marginUm;
            }

            return stageX >= slideMinX && stageX <= slideMaxX && stageY >= slideMinY && stageY <= slideMaxY;
        }

        /**
         * Returns the usable interior in insert-relative coordinates: [minX, minY, maxX, maxY] in microns.
         * For rectangles, the lip widths are subtracted from the long-axis ends. For circles, the lip inset
         * shrinks the radius. The legacy long-axis convention is: rotation 0 means long axis is along X
         * (lipA = left, lipB = right); rotation 90 means long axis is along Y (lipA = top, lipB = bottom).
         */
        public double[] getUsableInteriorInsertRelativeUm() {
            if (shape == Shape.CIRCLE) {
                double inset = Math.max(0, lipInsetUm);
                return new double[] {
                    xOffsetUm + inset, yOffsetUm + inset, xOffsetUm + widthUm - inset, yOffsetUm + heightUm - inset
                };
            }
            double minX = xOffsetUm;
            double maxX = xOffsetUm + widthUm;
            double minY = yOffsetUm;
            double maxY = yOffsetUm + heightUm;
            if (isVerticallyOriented()) {
                // Long axis is Y -- lip A is at min Y (top), lip B at max Y (bottom)
                minY += lipAUm;
                maxY -= lipBUm;
            } else {
                // Long axis is X -- lip A is at min X (left), lip B at max X (right)
                minX += lipAUm;
                maxX -= lipBUm;
            }
            return new double[] {minX, minY, maxX, maxY};
        }

        /**
         * Returns the stage X coordinate of this slide's left edge.
         */
        public double getMinStageX(double insertOriginX) {
            return insertOriginX + xOffsetUm;
        }

        /**
         * Returns the stage X coordinate of this slide's right edge.
         */
        public double getMaxStageX(double insertOriginX) {
            return insertOriginX + xOffsetUm + widthUm;
        }

        /**
         * Returns the stage Y coordinate of this slide's top edge.
         */
        public double getMinStageY(double insertOriginY) {
            return insertOriginY + yOffsetUm;
        }

        /**
         * Returns the stage Y coordinate of this slide's bottom edge.
         */
        public double getMaxStageY(double insertOriginY) {
            return insertOriginY + yOffsetUm + heightUm;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
