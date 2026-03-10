package qupath.ext.qpsc.modality.ppm.analysis;

import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.PathObject;

/**
 * Writes batch PPM analysis results to annotation measurements and CSV files.
 *
 * <p>All measurement keys are prefixed with "PPM: " to avoid collisions with
 * other measurement sources. Measurements are doubles only (QuPath constraint).</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMBatchResultsWriter {

    private static final Logger logger = LoggerFactory.getLogger(PPMBatchResultsWriter.class);

    /** Prefix for all PPM measurements on annotations. */
    public static final String PREFIX = "PPM: ";

    // Polarity measurement keys
    public static final String CIRCULAR_MEAN = PREFIX + "circular_mean";
    public static final String CIRCULAR_STD = PREFIX + "circular_std";
    public static final String RESULTANT_LENGTH = PREFIX + "resultant_length";
    public static final String ARITHMETIC_MEAN = PREFIX + "arithmetic_mean";
    public static final String ARITHMETIC_STD = PREFIX + "arithmetic_std";
    public static final String N_VALID_PIXELS = PREFIX + "n_valid_pixels";
    public static final String DOMINANT_BIN_LOW = PREFIX + "dominant_bin_low";
    public static final String DOMINANT_BIN_HIGH = PREFIX + "dominant_bin_high";

    // Perpendicularity measurement keys
    public static final String MEAN_DEVIATION_DEG = PREFIX + "mean_deviation_deg";
    public static final String STD_DEVIATION_DEG = PREFIX + "std_deviation_deg";
    public static final String PCT_PARALLEL = PREFIX + "pct_parallel";
    public static final String PCT_OBLIQUE = PREFIX + "pct_oblique";
    public static final String PCT_PERPENDICULAR = PREFIX + "pct_perpendicular";
    public static final String PCT_TACS2 = PREFIX + "pct_tacs2";
    public static final String PCT_TACS3 = PREFIX + "pct_tacs3";
    public static final String N_TACS3_CLUSTERS = PREFIX + "n_tacs3_clusters";
    public static final String CONTOUR_LENGTH_UM = PREFIX + "contour_length_um";
    public static final String DILATION_UM = PREFIX + "dilation_um";

    private final List<Map<String, String>> csvRows = new ArrayList<>();
    private final List<String> csvColumns = new ArrayList<>();
    private boolean columnsFinalized = false;

    // Fixed metadata columns always present in CSV
    private static final List<String> META_COLUMNS = List.of(
            "image_name", "image_collection", "sample_name", "annotation_name", "annotation_class", "analysis_type");

    /**
     * Stores polarity plot results as measurements on the annotation.
     *
     * @param annotation the annotation to add measurements to
     * @param result JSON result from ppm_library CLI analyze mode
     */
    public void storePolarityMeasurements(PathObject annotation, JsonObject result) {
        var ml = annotation.getMeasurementList();

        putIfPresent(ml, CIRCULAR_MEAN, result, "circular_mean");
        putIfPresent(ml, CIRCULAR_STD, result, "circular_std");
        putIfPresent(ml, RESULTANT_LENGTH, result, "resultant_length");
        putIfPresent(ml, ARITHMETIC_MEAN, result, "arithmetic_mean");
        putIfPresent(ml, ARITHMETIC_STD, result, "arithmetic_std");
        putIfPresent(ml, N_VALID_PIXELS, result, "n_pixels");

        // Compute dominant bin from histogram
        if (result.has("histogram_counts") && result.has("histogram_bin_edges")) {
            var counts = result.getAsJsonArray("histogram_counts");
            var edges = result.getAsJsonArray("histogram_bin_edges");
            if (counts != null && counts.size() > 0) {
                int maxIdx = 0;
                int maxVal = 0;
                for (int i = 0; i < counts.size(); i++) {
                    int c = counts.get(i).getAsInt();
                    if (c > maxVal) {
                        maxVal = c;
                        maxIdx = i;
                    }
                }
                if (edges != null && edges.size() > maxIdx + 1) {
                    ml.put(DOMINANT_BIN_LOW, edges.get(maxIdx).getAsDouble());
                    ml.put(DOMINANT_BIN_HIGH, edges.get(maxIdx + 1).getAsDouble());
                }
            }
        }
    }

    /**
     * Stores perpendicularity results as measurements on the annotation.
     *
     * @param annotation the annotation to add measurements to
     * @param result JSON result from ppm_library CLI perpendicularity mode
     */
    public void storePerpendicularityMeasurements(PathObject annotation, JsonObject result) {
        var ml = annotation.getMeasurementList();

        // Top-level fields
        putIfPresent(ml, CONTOUR_LENGTH_UM, result, "contour_length_um");

        // Simple results
        JsonObject simple =
                result.has("simple") && !result.get("simple").isJsonNull() ? result.getAsJsonObject("simple") : null;
        if (simple != null) {
            putIfPresent(ml, MEAN_DEVIATION_DEG, simple, "mean_deviation_deg");
            putIfPresent(ml, STD_DEVIATION_DEG, simple, "std_deviation_deg");
            putIfPresent(ml, N_VALID_PIXELS, simple, "n_valid_pixels");

            JsonObject split3 = simple.has("histogram_3way") ? simple.getAsJsonObject("histogram_3way") : null;
            if (split3 != null) {
                putIfPresent(ml, PCT_PARALLEL, split3, "pct_parallel");
                putIfPresent(ml, PCT_OBLIQUE, split3, "pct_oblique");
                putIfPresent(ml, PCT_PERPENDICULAR, split3, "pct_perpendicular");
            }
        }

        // PS-TACS results
        JsonObject pstacs =
                result.has("pstacs") && !result.get("pstacs").isJsonNull() ? result.getAsJsonObject("pstacs") : null;
        if (pstacs != null) {
            putIfPresent(ml, PCT_TACS2, pstacs, "pct_tacs2");
            putIfPresent(ml, PCT_TACS3, pstacs, "pct_tacs3");
            putIfPresent(ml, N_TACS3_CLUSTERS, pstacs, "n_tacs3_clusters");
        }

        // Store dilation from top-level
        if (result.has("dilation_px") && result.has("pixel_size_um")) {
            double dilPx = result.get("dilation_px").getAsDouble();
            double pxSize = result.get("pixel_size_um").getAsDouble();
            ml.put(DILATION_UM, dilPx * pxSize);
        }
    }

    /**
     * Adds a CSV row for a polarity analysis result.
     */
    public void addPolarityRow(
            String imageName,
            String collection,
            String sampleName,
            String annotationName,
            String annotationClass,
            JsonObject result) {
        Map<String, String> row =
                buildMetaRow(imageName, collection, sampleName, annotationName, annotationClass, "polarity");

        row.put(CIRCULAR_MEAN, getJsonStr(result, "circular_mean"));
        row.put(CIRCULAR_STD, getJsonStr(result, "circular_std"));
        row.put(RESULTANT_LENGTH, getJsonStr(result, "resultant_length"));
        row.put(ARITHMETIC_MEAN, getJsonStr(result, "arithmetic_mean"));
        row.put(ARITHMETIC_STD, getJsonStr(result, "arithmetic_std"));
        row.put(N_VALID_PIXELS, getJsonStr(result, "n_pixels"));

        addRow(row);
    }

    /**
     * Adds a CSV row for a perpendicularity analysis result.
     */
    public void addPerpendicularityRow(
            String imageName,
            String collection,
            String sampleName,
            String annotationName,
            String annotationClass,
            JsonObject result) {
        Map<String, String> row =
                buildMetaRow(imageName, collection, sampleName, annotationName, annotationClass, "perpendicularity");

        row.put(CONTOUR_LENGTH_UM, getJsonStr(result, "contour_length_um"));

        JsonObject simple =
                result.has("simple") && !result.get("simple").isJsonNull() ? result.getAsJsonObject("simple") : null;
        if (simple != null) {
            row.put(MEAN_DEVIATION_DEG, getJsonStr(simple, "mean_deviation_deg"));
            row.put(STD_DEVIATION_DEG, getJsonStr(simple, "std_deviation_deg"));

            JsonObject split3 = simple.has("histogram_3way") ? simple.getAsJsonObject("histogram_3way") : null;
            if (split3 != null) {
                row.put(PCT_PARALLEL, getJsonStr(split3, "pct_parallel"));
                row.put(PCT_OBLIQUE, getJsonStr(split3, "pct_oblique"));
                row.put(PCT_PERPENDICULAR, getJsonStr(split3, "pct_perpendicular"));
            }
        }

        JsonObject pstacs =
                result.has("pstacs") && !result.get("pstacs").isJsonNull() ? result.getAsJsonObject("pstacs") : null;
        if (pstacs != null) {
            row.put(PCT_TACS2, getJsonStr(pstacs, "pct_tacs2"));
            row.put(PCT_TACS3, getJsonStr(pstacs, "pct_tacs3"));
            row.put(N_TACS3_CLUSTERS, getJsonStr(pstacs, "n_tacs3_clusters"));
        }

        addRow(row);
    }

    /**
     * Writes all accumulated rows to a CSV file.
     *
     * @param outputPath path for the CSV file
     * @return number of rows written
     * @throws IOException if writing fails
     */
    public int writeCSV(Path outputPath) throws IOException {
        if (csvRows.isEmpty()) {
            logger.info("No rows to write");
            return 0;
        }

        outputPath.getParent().toFile().mkdirs();

        // Collect all column names (preserving order: meta first, then measurements)
        List<String> allColumns = new ArrayList<>(META_COLUMNS);
        for (Map<String, String> row : csvRows) {
            for (String key : row.keySet()) {
                if (!allColumns.contains(key)) {
                    allColumns.add(key);
                }
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            // Header
            writer.write(String.join(",", allColumns));
            writer.newLine();

            // Rows
            for (Map<String, String> row : csvRows) {
                List<String> values = new ArrayList<>();
                for (String col : allColumns) {
                    String val = row.getOrDefault(col, "");
                    // Escape commas and quotes in values
                    if (val.contains(",") || val.contains("\"")) {
                        val = "\"" + val.replace("\"", "\"\"") + "\"";
                    }
                    values.add(val);
                }
                writer.write(String.join(",", values));
                writer.newLine();
            }
        }

        logger.info("Wrote {} rows to {}", csvRows.size(), outputPath);
        return csvRows.size();
    }

    /**
     * Returns the number of rows accumulated so far.
     */
    public int getRowCount() {
        return csvRows.size();
    }

    // --- Private helpers ---

    private Map<String, String> buildMetaRow(
            String imageName,
            String collection,
            String sampleName,
            String annotationName,
            String annotationClass,
            String analysisType) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("image_name", imageName != null ? imageName : "");
        row.put("image_collection", collection != null ? collection : "");
        row.put("sample_name", sampleName != null ? sampleName : "");
        row.put("annotation_name", annotationName != null ? annotationName : "");
        row.put("annotation_class", annotationClass != null ? annotationClass : "");
        row.put("analysis_type", analysisType);
        return row;
    }

    private void addRow(Map<String, String> row) {
        csvRows.add(row);
    }

    private static void putIfPresent(
            qupath.lib.measurements.MeasurementList ml, String measurementKey, JsonObject json, String jsonKey) {
        if (json == null || !json.has(jsonKey) || json.get(jsonKey).isJsonNull()) {
            return;
        }
        try {
            ml.put(measurementKey, json.get(jsonKey).getAsDouble());
        } catch (Exception e) {
            // Skip non-numeric values
        }
    }

    private static String getJsonStr(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }
}
