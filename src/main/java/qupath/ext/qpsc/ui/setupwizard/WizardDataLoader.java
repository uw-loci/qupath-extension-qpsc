package qupath.ext.qpsc.ui.setupwizard;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Pre-populates {@link WizardData} from an existing
 * {@code config_<name>.yml} on disk.
 *
 * <p>Reads the inverse of {@link ConfigFileWriter#writeMainConfig} so a
 * re-run of the Setup Wizard on a previously-configured rig does not
 * force the user to re-input everything (stage limits, objectives,
 * detectors, modalities, streaming-AF probe results, ...). When the
 * config file does not exist, this no-ops and {@code WizardData} stays
 * at its constructor defaults -- the same behaviour as a fresh install.
 *
 * <p>Drift between this loader and {@link ConfigFileWriter} is caught
 * by {@code WizardDataLoaderTest}'s round-trip: writer -> loader ->
 * assert all fields match.
 */
public final class WizardDataLoader {

    private static final Logger logger = LoggerFactory.getLogger(WizardDataLoader.class);

    private WizardDataLoader() {}

    /**
     * Load existing config files (if present) into the supplied
     * {@code WizardData}. Eagerly invoked from
     * {@link SetupWizardDialog} once a microscope identity is known
     * (e.g. after the Welcome step). Safe to call repeatedly.
     *
     * @param configDirectory the wizard's working config directory
     * @param microscopeName  the microscope name (used to pick the file)
     * @param target          mutable WizardData to populate
     * @return true if a main config was found and at least one section
     *         was populated; false if no existing configs were loaded.
     */
    public static boolean loadFromExistingConfigs(
            Path configDirectory, String microscopeName, WizardData target) {
        if (configDirectory == null || microscopeName == null
                || microscopeName.isBlank() || target == null) {
            return false;
        }
        Path mainPath = configDirectory.resolve("config_" + microscopeName + ".yml");
        if (!Files.exists(mainPath)) {
            logger.debug("WizardDataLoader: no existing config at {}", mainPath);
            return false;
        }

        Map<String, Object> doc = readYaml(mainPath);
        if (doc == null || doc.isEmpty()) {
            logger.warn("WizardDataLoader: existing config {} unreadable or empty", mainPath);
            return false;
        }

        target.configDirectory = configDirectory;
        target.microscopeName = microscopeName;

        loadMicroscopeSection(doc, target);
        loadStageSection(doc, target);
        loadHardwareSection(doc, target);
        loadDetectorSection(doc, target);
        loadModalitiesSection(doc, target);
        loadSlideSize(doc, target);

        logger.info("WizardDataLoader: pre-populated wizard from {}", mainPath);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYaml(Path p) {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(p.toFile())) {
            Object loaded = yaml.load(in);
            if (loaded instanceof Map) {
                return new LinkedHashMap<>((Map<String, Object>) loaded);
            }
        } catch (Exception e) {
            logger.warn("WizardDataLoader: failed to read {}: {}", p, e.toString());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void loadMicroscopeSection(Map<String, Object> doc, WizardData target) {
        Object micObj = doc.get("microscope");
        if (!(micObj instanceof Map)) return;
        Map<String, Object> mic = (Map<String, Object>) micObj;
        if (mic.get("type") instanceof String s && !s.isBlank()) {
            target.microscopeType = s;
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadStageSection(Map<String, Object> doc, WizardData target) {
        Object stageObj = doc.get("stage");
        if (!(stageObj instanceof Map)) return;
        Map<String, Object> stage = (Map<String, Object>) stageObj;

        if (stage.get("stage_id") instanceof String s) target.stageId = s;
        if (stage.get("z_stage") instanceof String s) target.zStageDevice = s;

        Object limitsObj = stage.get("limits");
        if (limitsObj instanceof Map) {
            Map<String, Object> limits = (Map<String, Object>) limitsObj;
            target.stageLimitXLow = readDouble(limits, "x_um", "low", target.stageLimitXLow);
            target.stageLimitXHigh = readDouble(limits, "x_um", "high", target.stageLimitXHigh);
            target.stageLimitYLow = readDouble(limits, "y_um", "low", target.stageLimitYLow);
            target.stageLimitYHigh = readDouble(limits, "y_um", "high", target.stageLimitYHigh);
            target.stageLimitZLow = readDouble(limits, "z_um", "low", target.stageLimitZLow);
            target.stageLimitZHigh = readDouble(limits, "z_um", "high", target.stageLimitZHigh);
        }

        // Streaming-AF block (added in v3 schema). Tolerant of
        // partially-populated maps -- loader keeps existing nulls when
        // a key is absent so the wizard step can show "not yet probed".
        Object saObj = stage.get("streaming_af");
        if (saObj instanceof Map) {
            Map<String, Object> sa = (Map<String, Object>) saObj;
            if (sa.get("enabled") instanceof Boolean b) target.streamingAfEnabled = b;
            if (sa.get("speed_property") instanceof String s) target.streamingAfSpeedProperty = s;
            if (sa.get("slow_speed_value") instanceof String s) target.streamingAfSlowSpeedValue = s;
            else if (sa.get("slow_speed_value") != null) target.streamingAfSlowSpeedValue =
                    sa.get("slow_speed_value").toString();
            Object umps = sa.get("slow_speed_um_per_s");
            if (umps instanceof Number n) target.streamingAfSlowSpeedUmPerS = n.doubleValue();
            if (sa.get("normal_speed_value") instanceof String s) target.streamingAfNormalSpeedValue = s;
            else if (sa.get("normal_speed_value") != null) target.streamingAfNormalSpeedValue =
                    sa.get("normal_speed_value").toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadHardwareSection(Map<String, Object> doc, WizardData target) {
        Object hwObj = doc.get("hardware");
        if (!(hwObj instanceof Map)) return;
        Map<String, Object> hw = (Map<String, Object>) hwObj;

        target.objectives.clear();
        target.pixelSizes.clear();
        Object objsObj = hw.get("objectives");
        if (objsObj instanceof List) {
            for (Object entry : (List<Object>) objsObj) {
                if (!(entry instanceof Map)) continue;
                Map<String, Object> objEntry = (Map<String, Object>) entry;
                String objId = (String) objEntry.get("id");
                if (objId == null) continue;
                Map<String, Object> objCopy = new LinkedHashMap<>();
                objCopy.put("id", objId);
                target.objectives.add(objCopy);
                Object psMapObj = objEntry.get("pixel_size_xy_um");
                if (psMapObj instanceof Map) {
                    for (Map.Entry<String, Object> ps : ((Map<String, Object>) psMapObj).entrySet()) {
                        if (ps.getValue() instanceof Number n) {
                            target.pixelSizes.put(objId + "::" + ps.getKey(), n.doubleValue());
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadDetectorSection(Map<String, Object> doc, WizardData target) {
        Object detsObj = doc.get("id_detector");
        if (!(detsObj instanceof Map)) return;
        target.detectors.clear();
        Map<String, Object> dets = (Map<String, Object>) detsObj;
        for (Map.Entry<String, Object> e : dets.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> det = (Map<String, Object>) e.getValue();
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("id", e.getKey());
            for (String k : List.of("name", "camera_type", "device", "width_px", "height_px")) {
                if (det.containsKey(k)) copy.put(k, det.get(k));
            }
            target.detectors.add(copy);
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadModalitiesSection(Map<String, Object> doc, WizardData target) {
        Object modsObj = doc.get("modalities");
        if (!(modsObj instanceof Map)) return;
        target.modalities.clear();
        Map<String, Object> mods = (Map<String, Object>) modsObj;
        for (Map.Entry<String, Object> e : mods.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> mod = (Map<String, Object>) e.getValue();
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("name", e.getKey());
            // type is required; sub-objects (rotation_stage, illumination,
            // laser, pmt, ...) round-trip as opaque sub-maps. The wizard
            // step will only touch the keys it knows about -- everything
            // else flows through unchanged.
            for (String k : List.of("type", "rotation_stage", "rotation_angles",
                    "filter_wheel", "illumination", "laser", "pockels_cell",
                    "pmt", "zoom", "shutter")) {
                if (mod.containsKey(k)) copy.put(k, mod.get(k));
            }
            target.modalities.add(copy);
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadSlideSize(Map<String, Object> doc, WizardData target) {
        Object sizeObj = doc.get("slide_size_um");
        if (!(sizeObj instanceof Map)) return;
        Map<String, Object> size = (Map<String, Object>) sizeObj;
        if (size.get("x") instanceof Number n) target.slideSizeX = n.intValue();
        if (size.get("y") instanceof Number n) target.slideSizeY = n.intValue();
    }

    @SuppressWarnings("unchecked")
    private static double readDouble(Map<String, Object> map, String k1, String k2, double dflt) {
        Object v1 = map.get(k1);
        if (!(v1 instanceof Map)) return dflt;
        Object v2 = ((Map<String, Object>) v1).get(k2);
        if (v2 instanceof Number n) return n.doubleValue();
        return dflt;
    }
}
