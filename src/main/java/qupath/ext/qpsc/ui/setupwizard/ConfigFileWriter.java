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

        // Reload config if the manager is already active (wizard re-run while connected)
        try {
            var mgr = qupath.ext.qpsc.utilities.MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                mgr.reload(data.getMainConfigPath().toString());
                logger.info("Reloaded config after Setup Wizard write");
            }
        } catch (Exception e) {
            logger.debug("Config reload after wizard not needed (not yet initialized): {}", e.getMessage());
        }
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
        // detector_in_use / objective_in_use / modality are LIVE STATE written by the server
        // (workflow.py sets them as the scope changes) and read by the Live Viewer, Stage Map and
        // the acquisition dialogs. The wizard has no business knowing them, and writing null here
        // reset them on every run. They are seeded only when the file is new, further below, so an
        // existing config keeps whatever the scope last reported.
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
            // Illumination (brightfield) - full subsection with type, properties
            if (mod.containsKey("illumination")) {
                modConfig.put("illumination", mod.get("illumination"));
            }
            // Multiphoton-specific: laser, pockels_cell, pmt, zoom, shutter
            for (String mpKey : List.of("laser", "pockels_cell", "pmt", "zoom", "shutter")) {
                if (mod.containsKey(mpKey)) {
                    modConfig.put(mpKey, mod.get(mpKey));
                }
            }

            // No background_correction placeholder here. It used to be written unconditionally,
            // which overwrote a configured base_folder with "" on every re-run. The authoritative
            // copy lives in imageprocessing_<scope>.yml (that is the one getBackgroundCorrectionEntry
            // reads); seeding a second, competing copy from the wizard only created drift.

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

        // Detectors (list of IDs for hardware section)
        List<String> detectorIds = new ArrayList<>();
        for (Map<String, Object> det : data.detectors) {
            detectorIds.add((String) det.get("id"));
        }
        hardware.put("detectors", detectorIds);
        config.put("hardware", hardware);

        // id_detector section -- full detector definitions with camera_type
        Map<String, Object> idDetector = new LinkedHashMap<>();
        for (Map<String, Object> det : data.detectors) {
            String detId = (String) det.get("id");
            Map<String, Object> detEntry = new LinkedHashMap<>();
            detEntry.put("name", det.getOrDefault("name", ""));
            detEntry.put("camera_type", det.getOrDefault("camera_type", "generic"));
            String device = (String) det.getOrDefault("device", "");
            if (!device.isEmpty()) {
                detEntry.put("device", device);
            }
            detEntry.put("width_px", det.getOrDefault("width_px", 0));
            detEntry.put("height_px", det.getOrDefault("height_px", 0));
            detEntry.put("flip_x", false);
            detEntry.put("flip_y", false);
            idDetector.put(detId, detEntry);
        }
        config.put("id_detector", idDetector);

        // Stage section
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage_id", data.stageId);
        if (!data.zStageDevice.isEmpty()) {
            stage.put("z_stage", data.zStageDevice);
        }
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

        // Streaming-AF block: populated by the wizard probe step. We
        // always emit a block (even when not probed) so v3 schema
        // validation passes. Null fields are written explicitly so the
        // YAML reader on the Python side can distinguish "no probe yet"
        // from "block missing entirely".
        Map<String, Object> streamingAf = new LinkedHashMap<>();
        // "enabled" is a policy choice -- whether we want the feature on --
        // so defaulting it is fine. The other three are NOT: they are the
        // stage's measured speeds and the device property values that
        // produce them, which only the probe step can know. Substituting
        // "1" / 11.5 / "100" wrote invented numbers into a generated config
        // that then reads as though it had been probed, and defeated the
        // contract stated in the comment above. PPM has already shipped a
        // stale slow_speed_um_per_s once (1.422 against a real ~10.85), so
        // a plausible-looking placeholder here is exactly the failure mode.
        // The Python side already initialises all three to None in
        // handlers/probe_stage_af.py, so null is the expected "not probed".
        streamingAf.put("enabled", data.streamingAfEnabled != null ? data.streamingAfEnabled : true);
        streamingAf.put("speed_property", data.streamingAfSpeedProperty);
        streamingAf.put("slow_speed_value", data.streamingAfSlowSpeedValue);
        streamingAf.put("slow_speed_um_per_s", data.streamingAfSlowSpeedUmPerS);
        streamingAf.put("normal_speed_value", data.streamingAfNormalSpeedValue);
        stage.put("streaming_af", streamingAf);

        config.put("stage", stage);

        // Slide size
        Map<String, Object> slideSize = new LinkedHashMap<>();
        slideSize.put("x", data.slideSizeX);
        slideSize.put("y", data.slideSizeY);
        config.put("slide_size_um", slideSize);

        // Seed the runtime-state keys only on a brand-new file, so their absence is never
        // mistaken for "the scope reported nothing".
        Path mainPath = data.getMainConfigPath();
        if (!Files.exists(mainPath)) {
            microscope.put("detector_in_use", null);
            microscope.put("objective_in_use", null);
            microscope.put("modality", null);
        }

        writeMerged(
                mainPath,
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

        // Keep every objective that already has an entry EXACTLY as it is, and append defaults
        // only for objectives that have none.
        //
        // These entries are measured, and expensively so: autofocus_PPM.yml carries per-objective
        // score_metric and channel_reduction choices settled over several reversals with the
        // evidence recorded beside them. Rebuilding the list from
        // ConfigSchema.getDefaultAutofocusParams would reset all of it, including calibrated back
        // to false, and would also drop the schema_version / strategies / modalities sections the
        // wizard does not model at all (those survive via the deepMerge in writeMerged).
        Path afPath = data.getAutofocusConfigPath();
        Set<String> alreadyTuned = new LinkedHashSet<>();
        Object priorSettings = readExisting(afPath).get("autofocus_settings");
        if (priorSettings instanceof List<?> priorList) {
            for (Object entry : priorList) {
                if (entry instanceof Map<?, ?> entryMap) {
                    Object objective = entryMap.get("objective");
                    if (objective instanceof String id) {
                        alreadyTuned.add(id);
                    }
                }
            }
        }

        for (Map<String, Object> obj : data.objectives) {
            String objId = (String) obj.get("id");
            if (alreadyTuned.contains(objId)) {
                logger.info("Autofocus: keeping the existing tuned entry for objective {}", objId);
                continue;
            }
            settings.add(ConfigSchema.getDefaultAutofocusParams(objId));
        }

        if (settings.isEmpty()) {
            // Emit NO autofocus_settings key at all. Putting an empty list here would hand
            // deepMerge an empty list to replace the real one with -- the exact data loss this
            // whole change exists to prevent.
            logger.info("Autofocus: every objective already has an entry; leaving {} untouched", afPath.getFileName());
        } else {
            // Append to the existing list rather than replacing it -- deepMerge replaces lists,
            // so the union has to be built here.
            List<Map<String, Object>> merged = new ArrayList<>();
            if (priorSettings instanceof List<?> priorList2) {
                for (Object entry : priorList2) {
                    if (entry instanceof Map<?, ?> entryMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) entryMap;
                        merged.add(typed);
                    }
                }
            }
            merged.addAll(settings);
            settings = merged;
        }

        if (!settings.isEmpty()) {
            config.put("autofocus_settings", settings);
        }

        writeMerged(
                afPath,
                config,
                "# Autofocus configuration generated by Setup Wizard\n"
                        + "# Schema version: " + ConfigSchema.SCHEMA_VERSION + "\n"
                        + "#\n"
                        + "# IMPORTANT: Each objective has 'calibrated: false' by default.\n"
                        + "# Acquisition will REFUSE to run autofocus until you set 'calibrated: true'\n"
                        + "# after verifying the parameters are safe for your hardware.\n"
                        + "#\n"
                        + "# CRITICAL: An incorrect search_range_um can crash the objective into the sample!\n"
                        + "# Adjust search_range_um and n_steps for each objective, then set calibrated: true.\n"
                        + "# Or run the Autofocus Benchmark utility from the QP Scope menu.\n");
    }

    /**
     * Write the imaging profiles configuration file with placeholder exposures.
     */
    static void writeImagingConfig(WizardData data) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>();

        // Everything below seeds PLACEHOLDERS, so every write is conditional on there being
        // nothing there already. The values this file ends up holding -- measured exposures,
        // white-balance gains, background base_folder -- are the output of White Balance
        // Calibration and Background Collection, and re-running the wizard used to replace them
        // with exposures_ms: {single: 50}.
        Path imagingPath = data.getImagingConfigPath();
        Map<String, Object> priorImaging = readExisting(imagingPath);
        Map<String, Object> priorBg = asMap(priorImaging.get("background_correction"));
        Map<String, Object> priorProfiles = asMap(priorImaging.get("imaging_profiles"));

        // Background correction section
        Map<String, Object> bgSection = new LinkedHashMap<>();
        for (Map<String, Object> mod : data.modalities) {
            String modName = (String) mod.get("name");
            if (priorBg.containsKey(modName)) {
                continue;
            }
            Map<String, Object> bgEntry = new LinkedHashMap<>();
            bgEntry.put("enabled", false);
            bgEntry.put("method", "divide");
            bgEntry.put("base_folder", "");
            bgSection.put(modName, bgEntry);
        }
        if (!bgSection.isEmpty()) {
            config.put("background_correction", bgSection);
        }

        // Imaging profiles: modality -> objective -> detector -> settings
        Map<String, Object> profiles = new LinkedHashMap<>();
        for (Map<String, Object> mod : data.modalities) {
            String modName = (String) mod.get("name");
            String modType = (String) mod.getOrDefault("type", "");
            boolean isLSM = "multiphoton".equals(modType);
            Map<String, Object> modProfile = new LinkedHashMap<>();

            for (Map<String, Object> obj : data.objectives) {
                String objId = (String) obj.get("id");
                Map<String, Object> objProfile = new LinkedHashMap<>();

                Map<String, Object> priorObjProfile =
                        asMap(asMap(priorProfiles.get(modName)).get(objId));

                for (Map<String, Object> det : data.detectors) {
                    String detId = (String) det.get("id");
                    if (priorObjProfile.containsKey(detId)) {
                        continue;
                    }
                    objProfile.put(
                            detId,
                            isLSM
                                    ? ConfigSchema.getDefaultLSMImagingProfile()
                                    : ConfigSchema.getDefaultImagingProfile());
                }

                if (!objProfile.isEmpty()) {
                    modProfile.put(objId, objProfile);
                }
            }

            if (!modProfile.isEmpty()) {
                profiles.put(modName, modProfile);
            }
        }
        if (!profiles.isEmpty()) {
            config.put("imaging_profiles", profiles);
        }

        writeMerged(
                imagingPath,
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
    /**
     * Reads an existing config file, or an empty map when there is none.
     *
     * <p>A parse failure returns empty AND is rethrown by the caller rather than swallowed: if we
     * cannot read what is already on disk we must not write over it, because the merge would
     * silently degrade to the old whole-file overwrite.
     */
    /** Narrows an untyped YAML value to a map, or an empty map when it is anything else. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (value instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, Object> readExisting(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return new LinkedHashMap<>(typed);
            }
            return new LinkedHashMap<>();
        } catch (Exception e) {
            throw new IOException(
                    "Could not parse the existing config at " + path + ", so it cannot be safely updated. "
                            + "Fix or move the file and re-run the wizard: " + e.getMessage(),
                    e);
        }
    }

    /**
     * Recursively merges {@code incoming} over {@code existing}, preserving anything the wizard
     * does not manage.
     *
     * <p>This is the whole point of the class as it now stands. The wizard collects a fraction of
     * what a working config holds -- it knows nothing about {@code stage.inserts},
     * {@code light_path}, {@code stage.focus.retract_sign}, {@code stage.safe_z_um},
     * {@code acquisition_profiles}, or a modality's {@code channels} list -- and it used to write
     * whole files from that fraction. Re-running it on a calibrated scope therefore deleted every
     * measured value the rig had, with no prompt and no backup.
     *
     * <p>Rules: nested maps recurse; a leaf in {@code incoming} wins; a key present only in
     * {@code existing} survives. Lists are replaced wholesale, because the wizard's lists
     * (objectives, detectors) are authoritative when the user has just edited them -- the files
     * whose lists must NOT be clobbered get their own merge instead of this one.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> out = new LinkedHashMap<>(existing);
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            Object incomingValue = e.getValue();
            Object existingValue = out.get(e.getKey());
            if (incomingValue instanceof Map<?, ?> incomingMap && existingValue instanceof Map<?, ?> existingMap) {
                out.put(e.getKey(), deepMerge((Map<String, Object>) existingMap, (Map<String, Object>) incomingMap));
            } else {
                out.put(e.getKey(), incomingValue);
            }
        }
        return out;
    }

    /**
     * Copies a file to {@code <name>.bak-<timestamp>} before it is rewritten.
     *
     * <p>Best-effort: a backup that cannot be written must not stop the wizard, but it is logged at
     * WARN because it is the last line of defence if the merge above is ever wrong.
     */
    private static void backup(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        String stamp =
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = path.resolveSibling(path.getFileName() + ".bak-" + stamp);
        try {
            Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logger.info("Backed up {} to {}", path.getFileName(), target.getFileName());
        } catch (IOException e) {
            logger.warn("Could not back up {} before rewriting it: {}", path, e.getMessage());
        }
    }

    /**
     * Merges {@code incoming} into whatever is already at {@code path}, after taking a backup.
     * The header is only applied when the file is new, so a hand-written config keeps its own
     * comments rather than being relabelled as wizard output.
     */
    private static void writeMerged(Path path, Map<String, Object> incoming, String header) throws IOException {
        Map<String, Object> existing = readExisting(path);
        boolean isNew = existing.isEmpty();
        backup(path);
        writeYaml(path, deepMerge(existing, incoming), isNew ? header : null);
    }

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
