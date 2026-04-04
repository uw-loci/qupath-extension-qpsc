package qupath.ext.qpsc.ui.setupwizard;

import java.nio.file.Path;
import java.util.*;

/**
 * Shared mutable state for all wizard steps.
 * Each step reads from and writes to this data object.
 * {@link ConfigFileWriter} converts this into YAML files.
 */
public class WizardData {

    // ====== Step 1: Welcome ======
    public Path configDirectory;
    public String microscopeName = "";
    public String microscopeType = "UprightBrightfield";

    // ====== Step 2: Objectives ======
    /**
     * Each map contains: "id" (String), "name" (String from resources),
     * "magnification" (Double), "na" (Double), "wd_um" (Double).
     */
    public final List<Map<String, Object>> objectives = new ArrayList<>();

    // ====== Step 3: Detectors ======
    /**
     * Each map contains: "id" (String), "name" (String), "manufacturer" (String),
     * "width_px" (Integer), "height_px" (Integer).
     */
    public final List<Map<String, Object>> detectors = new ArrayList<>();

    // ====== Step 4: Pixel Sizes ======
    /**
     * Map from "objectiveId::detectorId" to pixel size (double, um).
     */
    public final Map<String, Double> pixelSizes = new LinkedHashMap<>();

    // ====== Step 5: Stage ======
    public String stageId = "";
    public String zStageDevice = ""; // MM focus device name (e.g. "ZDrive")
    public double stageLimitXLow = -36000;
    public double stageLimitXHigh = 36000;
    public double stageLimitYLow = -39000;
    public double stageLimitYHigh = 39000;
    public double stageLimitZLow = -5000;
    public double stageLimitZHigh = 0;

    // ====== Step 6: Modalities ======
    /**
     * Each map contains: "name" (String key like "ppm"), "type" (String like "polarized"),
     * and modality-specific sub-maps (e.g., "rotation_stage", "rotation_angles").
     */
    public final List<Map<String, Object>> modalities = new ArrayList<>();

    // ====== Step 7: Server ======
    public String serverHost = "localhost";
    public int serverPort = 5000;

    // ====== Slide size ======
    public int slideSizeX = 40000;
    public int slideSizeY = 20000;

    /**
     * Get the config file path for the main microscope config.
     */
    public Path getMainConfigPath() {
        return configDirectory.resolve("config_" + microscopeName + ".yml");
    }

    /**
     * Get the autofocus config file path.
     */
    public Path getAutofocusConfigPath() {
        return configDirectory.resolve("autofocus_" + microscopeName + ".yml");
    }

    /**
     * Get the imaging profiles config file path.
     */
    public Path getImagingConfigPath() {
        return configDirectory.resolve("imageprocessing_" + microscopeName + ".yml");
    }

    /**
     * Get the resources directory path.
     */
    public Path getResourcesDir() {
        return configDirectory.resolve("resources");
    }
}
