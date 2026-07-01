package qupath.ext.qpsc.utilities;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.setupwizard.ConfigSchema;

/**
 * Registers a new objective across the microscope config files, writing
 * skeleton ("stub") entries so the objective becomes immediately selectable
 * with a correct pixel size. Calibration-heavy data (autofocus params, white
 * balance, exposures, background) is written as {@code calibrated: false}
 * placeholders and must be filled in afterwards by the existing calibration
 * workflows -- this writer does the registration half of the "stub +
 * calibration handoff" flow.
 *
 * <p>Touches up to four files, all via the comment-preserving
 * {@link ConfigYamlEditor} append primitives (no full-file round-trip, so the
 * heavily-documented headers survive):
 * <ul>
 *   <li>{@code config_<scope>.yml} -> {@code hardware.objectives} (id + per-detector pixel size)</li>
 *   <li>{@code autofocus_<scope>.yml} -> {@code autofocus_settings[]} (calibrated:false defaults)</li>
 *   <li>{@code imageprocessing_<scope>.yml} -> {@code imaging_profiles.<modality>} (placeholder profile per modality)</li>
 *   <li>{@code resources/resources_LOCI.yml} -> {@code id_objective_lens} (display name / NA / magnification)</li>
 * </ul>
 *
 * <p>Every write is idempotent: an objective already present in a given file
 * is reported as skipped rather than duplicated, so re-running after a partial
 * failure is safe.
 */
public final class ObjectiveConfigWriter {

    private static final Logger logger = LoggerFactory.getLogger(ObjectiveConfigWriter.class);

    private ObjectiveConfigWriter() {}

    /**
     * Everything needed to register one objective.
     *
     * @param objectiveId    the objective id string (the key used across all files)
     * @param detectorId     detector the pixel size was measured with
     * @param pixelSizeXyUm  measured/confirmed pixel size (um/px) for that detector
     * @param displayName    human-friendly name for resources_LOCI
     * @param magnification  nominal magnification (e.g. 60.0)
     * @param na             numerical aperture, or null if unknown
     * @param wdUm           working distance in um, or null if unknown
     * @param manufacturerId manufacturer part number, or null
     * @param onScope        microscope name (resources_LOCI on_scope field)
     * @param modalities     modality name -> isLaserScanning (picks the imaging-profile stub shape)
     */
    public record Data(
            String objectiveId,
            String detectorId,
            double pixelSizeXyUm,
            String displayName,
            double magnification,
            Double na,
            Double wdUm,
            String manufacturerId,
            String onScope,
            Map<String, Boolean> modalities) {}

    /** Per-file outcome of a registration run. */
    public static final class Report {
        public final List<String> changed = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        public boolean anyChange() {
            return !changed.isEmpty();
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * Write stub entries for {@code d} into the config files that sit
     * alongside {@code mainConfigPath}. Never throws for a single-file
     * failure -- collects them in {@link Report#errors} so a partial write is
     * visible and re-runnable.
     */
    public static Report writeAll(Path mainConfigPath, Data d) {
        Report report = new Report();
        Path dir = mainConfigPath.getParent();
        String scope = extractScope(mainConfigPath.getFileName().toString());
        Path afPath = dir.resolve("autofocus_" + scope + ".yml");
        Path ipPath = dir.resolve("imageprocessing_" + scope + ".yml");
        Path resPath = dir.resolve("resources").resolve("resources_LOCI.yml");

        // 1. hardware.objectives: id + this detector's pixel size.
        Map<String, Object> objItem = new LinkedHashMap<>();
        objItem.put("id", d.objectiveId());
        Map<String, Object> px = new LinkedHashMap<>();
        px.put(d.detectorId(), d.pixelSizeXyUm());
        objItem.put("pixel_size_xy_um", px);
        objItem.put("pixel_size_z_um", null);
        run(
                report,
                "config hardware.objectives",
                () -> ConfigYamlEditor.appendListItem(
                        mainConfigPath, new String[] {"hardware", "objectives"}, "id", d.objectiveId(), objItem));

        // 2. autofocus_settings: safety-default stub (calibrated: false).
        if (java.nio.file.Files.exists(afPath)) {
            Map<String, Object> af = ConfigSchema.getDefaultAutofocusParams(d.objectiveId());
            run(
                    report,
                    "autofocus autofocus_settings",
                    () -> ConfigYamlEditor.appendListItem(
                            afPath, new String[] {"autofocus_settings"}, "objective", d.objectiveId(), af));
        } else {
            report.skipped.add("autofocus (" + afPath.getFileName() + " absent)");
        }

        // 3. imaging_profiles: one placeholder profile per modality/detector.
        if (java.nio.file.Files.exists(ipPath)) {
            for (Map.Entry<String, Boolean> mod : d.modalities().entrySet()) {
                Map<String, Object> profile = Boolean.TRUE.equals(mod.getValue())
                        ? ConfigSchema.getDefaultLSMImagingProfile()
                        : ConfigSchema.getDefaultImagingProfile();
                Map<String, Object> byDetector = new LinkedHashMap<>();
                byDetector.put(d.detectorId(), profile);
                run(
                        report,
                        "imaging_profiles." + mod.getKey(),
                        () -> ConfigYamlEditor.appendMapEntry(
                                ipPath, new String[] {"imaging_profiles", mod.getKey()}, d.objectiveId(), byDetector));
            }
        } else {
            report.skipped.add("imaging_profiles (" + ipPath.getFileName() + " absent)");
        }

        // 4. resources_LOCI id_objective_lens: display metadata.
        if (java.nio.file.Files.exists(resPath)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", d.displayName());
            entry.put("magnification", d.magnification());
            entry.put("na", d.na());
            entry.put("wd_um", d.wdUm());
            entry.put("description", "");
            entry.put("manufacturer_id", d.manufacturerId());
            entry.put("on_scope", d.onScope());
            run(
                    report,
                    "resources id_objective_lens",
                    () -> ConfigYamlEditor.appendMapEntry(
                            resPath, new String[] {"id_objective_lens"}, d.objectiveId(), entry));
        } else {
            report.skipped.add("resources_LOCI (" + resPath.getFileName() + " absent)");
        }

        logger.info(
                "Objective registration for {}: {} changed, {} skipped, {} errors",
                d.objectiveId(),
                report.changed.size(),
                report.skipped.size(),
                report.errors.size());
        return report;
    }

    /** Objective id existence check against a live manager (avoids re-registration). */
    public static boolean objectiveExists(MicroscopeConfigManager mgr, String objectiveId) {
        try {
            return mgr.getAvailableObjectives().contains(objectiveId);
        } catch (Exception e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface Edit {
        ConfigYamlEditor.Result apply() throws Exception;
    }

    private static void run(Report report, String label, Edit edit) {
        try {
            ConfigYamlEditor.Result r = edit.apply();
            if (r.changed) {
                report.changed.add(label + ": " + r.message);
            } else {
                report.skipped.add(label + ": " + r.message);
            }
        } catch (Exception e) {
            logger.warn("Objective registration write failed for {}: {}", label, e.getMessage(), e);
            report.errors.add(label + ": " + e.getMessage());
        }
    }

    /** {@code config_PPM.yml} -> {@code PPM}. Falls back to the stem if the prefix is unexpected. */
    static String extractScope(String fileName) {
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        if (stem.startsWith("config_")) return stem.substring("config_".length());
        return stem;
    }
}
