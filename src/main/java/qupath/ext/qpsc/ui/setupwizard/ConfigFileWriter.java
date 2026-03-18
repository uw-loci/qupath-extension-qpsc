package qupath.ext.qpsc.ui.setupwizard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Writes YAML configuration files from {@link WizardData}.
 *
 * <p>Generates three config files matching the structure defined in {@link ConfigSchema}:
 * <ul>
 *   <li>{@code config_<name>.yml} - Hardware and stage configuration</li>
 *   <li>{@code autofocus_<name>.yml} - Per-objective autofocus parameters</li>
 *   <li>{@code imageprocessing_<name>.yml} - Imaging profiles (placeholder exposures)</li>
 * </ul>
 *
 * <p>Also copies {@code resources_LOCI.yml} to the resources/ subdirectory if not present.
 */
public final class ConfigFileWriter {

    private static final Logger logger = LoggerFactory.getLogger(ConfigFileWriter.class);

    private ConfigFileWriter() {}

    /**
     * Write all configuration files from wizard data.
     *
     * @param data the populated wizard data
     * @throws IOException if file writing fails
     */
    public static void writeAll(WizardData data) throws IOException {
        Files.createDirectories(data.configDirectory);
        Files.createDirectories(data.getResourcesDir());

        writeMainConfig(data);
        writeAutofocusConfig(data);
        writeImagingConfig(data);
        copyResourcesIfNeeded(data);

        logger.info("All configuration files written to {}", data.configDirectory);
    }

    /**
     * Write the main microscope configuration file.
     */
    static void writeMainConfig(WizardData data) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();

        // Microscope section
        Map<String, Object> microscope = new LinkedHashMap<>();
        microscope.put("name", data.microscopeName);
        microscope.put("type", data.microscopeType);
        microscope.put("detector_in_use", null);
        microscope.put("objective_in_use", null);
        microscope.put("modality", null);
        config.put("microscope", microscope);

        // Modalities section
        Map<String, Object> modalities = new LinkedHashMap<>();
        for (Map<String, Object> mod : data.modalities) {
            String name = (String) mod.get("name");
            Map<String, Object> modConfig = new LinkedHashMap<>();
            modConfig.put("type", mod.get("type"));

            // PPM-specific: rotation_stage and rotation_angles
            if (mod.containsKey("rotation_stage")) {
                modConfig.put("rotation_stage", mod.get("rotation_stage"));
            }
            if (mod.containsKey("rotation_angles")) {
                modConfig.put("rotation_angles", mod.get("rotation_angles"));
            }
            // Fluorescence-specific: filter_wheel
            if (mod.containsKey("filter_wheel")) {
                modConfig.put("filter_wheel", mod.get("filter_wheel"));
            }
            // Lamp
            if (mod.containsKey("lamp")) {
                modConfig.put("lamp", mod.get("lamp"));
            }

            // Background correction placeholder
            Map<String, Object> bgCorrection = new LinkedHashMap<>();
            bgCorrection.put("enabled", false);
            bgCorrection.put("method", "divide");
            bgCorrection.put("base_folder", "");
            modConfig.put("background_correction", bgCorrection);

            modalities.put(name, modConfig);
        }
        config.put("modalities", modalities);

        // Hardware section
        Map<String, Object> hardware = new LinkedHashMap<>();

        // Objectives
        List<Map<String, Object>> objectives = new ArrayList<>();
        for (Map<String, Object> obj : data.objectives) {
            Map<String, Object> objEntry = new LinkedHashMap<>();
            String objId = (String) obj.get("id");
            objEntry.put("id", objId);

            // Pixel sizes per detector
            Map<String, Object> pixelSizeMap = new LinkedHashMap<>();
            for (Map<String, Object> det : data.detectors) {
                String detId = (String) det.get("id");
                String key = objId + "::" + detId;
                Double ps = data.pixelSizes.get(key);
                if (ps != null && ps > 0) {
                    pixelSizeMap.put(detId, ps);
                }
            }
            objEntry.put("pixel_size_xy_um", pixelSizeMap);
            objectives.add(objEntry);
        }
        hardware.put("objectives", objectives);

        // Detectors (list of IDs)
        List<String> detectorIds = new ArrayList<>();
        for (Map<String, Object> det : data.detectors) {
            detectorIds.add((String) det.get("id"));
        }
        hardware.put("detectors", detectorIds);
        config.put("hardware", hardware);

        // Stage section
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage_id", data.stageId);
        Map<String, Object> limits = new LinkedHashMap<>();
        Map<String, Object> xLimits = new LinkedHashMap<>();
        xLimits.put("low", data.stageLimitXLow);
        xLimits.put("high", data.stageLimitXHigh);
        limits.put("x_um", xLimits);
        Map<String, Object> yLimits = new LinkedHashMap<>();
        yLimits.put("low", data.stageLimitYLow);
        yLimits.put("high", data.stageLimitYHigh);
        limits.put("y_um", yLimits);
        Map<String, Object> zLimits = new LinkedHashMap<>();
        zLimits.put("low", data.stageLimitZLow);
        zLimits.put("high", data.stageLimitZHigh);
        limits.put("z_um", zLimits);
        stage.put("limits", limits);
        config.put("stage", stage);

        // Slide size
        Map<String, Object> slideSize = new LinkedHashMap<>();
        slideSize.put("x", data.slideSizeX);
        slideSize.put("y", data.slideSizeY);
        config.put("slide_size_um", slideSize);

        writeYaml(
                data.getMainConfigPath(),
                config,
                "# Microscope configuration generated by Setup Wizard\n"
                        + "# Schema version: " + ConfigSchema.SCHEMA_VERSION + "\n"
                        + "# Generated for: " + data.microscopeName + "\n");
    }

    /**
     * Write the autofocus configuration file.
     */
    static void writeAutofocusConfig(WizardData data) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();
        List<Map<String, Object>> settings = new ArrayList<>();

        for (Map<String, Object> obj : data.objectives) {
            String objId = (String) obj.get("id");
            settings.add(ConfigSchema.getDefaultAutofocusParams(objId));
        }

        config.put("autofocus_settings", settings);

        writeYaml(
                data.getAutofocusConfigPath(),
                config,
                "# Autofocus configuration generated by Setup Wizard\n"
                        + "# Schema version: " + ConfigSchema.SCHEMA_VERSION + "\n"
                        + "# Adjust these parameters after running the Autofocus Benchmark utility\n");
    }

    /**
     * Write the imaging profiles configuration file with placeholder exposures.
     */
    static void writeImagingConfig(WizardData data) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();

        // Background correction section
        Map<String, Object> bgSection = new LinkedHashMap<>();
        for (Map<String, Object> mod : data.modalities) {
            String modName = (String) mod.get("name");
            Map<String, Object> bgEntry = new LinkedHashMap<>();
            bgEntry.put("enabled", false);
            bgEntry.put("method", "divide");
            bgEntry.put("base_folder", "");
            bgSection.put(modName, bgEntry);
        }
        config.put("background_correction", bgSection);

        // Imaging profiles: modality -> objective -> detector -> settings
        Map<String, Object> profiles = new LinkedHashMap<>();
        for (Map<String, Object> mod : data.modalities) {
            String modName = (String) mod.get("name");
            Map<String, Object> modProfile = new LinkedHashMap<>();

            for (Map<String, Object> obj : data.objectives) {
                String objId = (String) obj.get("id");
                Map<String, Object> objProfile = new LinkedHashMap<>();

                for (Map<String, Object> det : data.detectors) {
                    String detId = (String) det.get("id");
                    objProfile.put(detId, ConfigSchema.getDefaultImagingProfile());
                }

                modProfile.put(objId, objProfile);
            }

            profiles.put(modName, modProfile);
        }
        config.put("imaging_profiles", profiles);

        writeYaml(
                data.getImagingConfigPath(),
                config,
                "# Imaging profiles generated by Setup Wizard\n"
                        + "# Schema version: " + ConfigSchema.SCHEMA_VERSION + "\n"
                        + "# IMPORTANT: These are placeholder values.\n"
                        + "# Run White Balance Calibration and Background Collection\n"
                        + "# to populate with real values for your hardware.\n");
    }

    /**
     * Copy resources_LOCI.yml to the resources/ subdirectory if not already present.
     */
    static void copyResourcesIfNeeded(WizardData data) throws IOException {
        Path dest = data.getResourcesDir().resolve("resources_LOCI.yml");
        if (Files.exists(dest)) {
            logger.info("resources_LOCI.yml already exists at {}, not overwriting", dest);
            return;
        }

        // Try to load from bundled JAR resources
        try (InputStream is =
                ConfigFileWriter.class.getResourceAsStream("/qupath/ext/qpsc/templates/resources_LOCI.yml")) {
            if (is != null) {
                Files.copy(is, dest);
                logger.info("Copied bundled resources_LOCI.yml to {}", dest);
            } else {
                // Create a minimal resources file
                Map<String, Object> resources = new LinkedHashMap<>();
                resources.put(ConfigSchema.RESOURCES_STAGES, new LinkedHashMap<>());
                resources.put(ConfigSchema.RESOURCES_DETECTORS, new LinkedHashMap<>());
                resources.put(ConfigSchema.RESOURCES_OBJECTIVES, new LinkedHashMap<>());
                writeYaml(
                        dest,
                        resources,
                        "# LOCI shared hardware resources\n" + "# Add your hardware definitions here\n");
                logger.info("Created minimal resources_LOCI.yml at {}", dest);
            }
        }
    }

    /**
     * Write a YAML file with an optional header comment.
     */
    private static void writeYaml(Path path, Map<String, Object> data, String header) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setIndent(2);
        options.setPrettyFlow(true);

        Yaml yaml = new Yaml(options);
        String yamlStr = yaml.dump(data);

        StringBuilder sb = new StringBuilder();
        if (header != null) {
            sb.append(header);
            sb.append("\n");
        }
        sb.append(yamlStr);

        Files.writeString(path, sb.toString());
        logger.info("Wrote config file: {}", path);
    }
}
