package qupath.ext.qpsc.ui.setupwizard;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Re-running the Setup Wizard must not destroy what the wizard does not model.
 *
 * <p>The wizard collects a fraction of a working config and used to write whole files from that
 * fraction, with no merge, no backup and no confirmation. On a calibrated scope a re-run therefore
 * deleted {@code stage.inserts}, {@code light_path}, {@code stage.focus.retract_sign},
 * {@code stage.safe_z_um}, {@code acquisition_profiles}, every modality's {@code channels} list,
 * the tuned per-objective autofocus entries, and the measured white balance -- and the invalid-config
 * path promotes "Setup Wizard (Start Here)..." to the top of the menu, so the UI's most prominent
 * suggestion was the thing that would finish the job.
 *
 * <p>The fixtures below are shaped after the real config_PPM.yml and config_OWS3.yml.
 */
class ConfigFileWriterMergeTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path p) throws Exception {
        return (Map<String, Object>) new Yaml().load(Files.readString(p));
    }

    private static WizardData minimalData(Path dir) {
        WizardData d = new WizardData();
        d.configDirectory = dir;
        d.microscopeName = "PPM";
        d.microscopeType = "UprightBrightfield";
        d.objectives.add(new java.util.LinkedHashMap<>(Map.of("id", "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001")));
        d.detectors.add(new java.util.LinkedHashMap<>(Map.of("id", "LOCI_DETECTOR_JAI_001")));
        d.pixelSizes.put("LOCI_OBJECTIVE_OLYMPUS_20X_POL_001::LOCI_DETECTOR_JAI_001", 0.250552);
        d.modalities.add(new java.util.LinkedHashMap<>(Map.of("name", "ppm", "type", "polarized")));
        d.stageId = "LOCI_STAGE_PRIOR_001";
        return d;
    }

    /** The blocks the wizard has no concept of, as they appear on the real rigs. */
    private static final String EXISTING_MAIN = """
            microscope:
              name: PPM
              type: UprightBrightfield
              detector_in_use: LOCI_DETECTOR_JAI_001
              objective_in_use: LOCI_OBJECTIVE_OLYMPUS_20X_POL_001
            modalities:
              ppm:
                type: polarized
                pizstage_offset: 177082.5
                optics: 1
            stage:
              stage_id: LOCI_STAGE_PRIOR_001
              focus:
                retract_sign: positive
              safe_z_um: 0
              inserts:
                default: single_h
                configurations:
                  quad_v:
                    kind: slide_holder
                    slide1_center_x_um: 1093
            acquisition_profiles:
              Brightfield_20x:
                modality: Brightfield
            light_path:
              scope_type: inverted
              optical_flip: xy
            """;

    @Test
    void aReRunPreservesEverythingTheWizardDoesNotModel() throws Exception {
        Path dir = Files.createTempDirectory("qpsc-wizard-merge");
        WizardData data = minimalData(dir);
        Files.createDirectories(dir);
        Files.writeString(data.getMainConfigPath(), EXISTING_MAIN);

        ConfigFileWriter.writeMainConfig(data);

        Map<String, Object> out = read(data.getMainConfigPath());
        Map<String, Object> stage = (Map<String, Object>) out.get("stage");

        assertThat(out).containsKey("light_path");
        assertThat((Map<String, Object>) out.get("light_path")).containsEntry("scope_type", "inverted");
        assertThat(out).containsKey("acquisition_profiles");
        assertThat(stage).containsKey("inserts");
        assertThat(stage).containsKey("focus");
        assertThat((Map<String, Object>) stage.get("focus")).containsEntry("retract_sign", "positive");
        assertThat(stage).containsEntry("safe_z_um", 0);

        Map<String, Object> ppm = (Map<String, Object>) ((Map<String, Object>) out.get("modalities")).get("ppm");
        assertThat(ppm)
                .as("pizstage_offset absence makes PIZ hardware init raise")
                .containsKey("pizstage_offset");
        assertThat(ppm).containsKey("optics");
    }

    @Test
    void aReRunDoesNotResetTheScopesLiveRuntimeState() throws Exception {
        Path dir = Files.createTempDirectory("qpsc-wizard-runtime");
        WizardData data = minimalData(dir);
        Files.writeString(data.getMainConfigPath(), EXISTING_MAIN);

        ConfigFileWriter.writeMainConfig(data);

        Map<String, Object> scope =
                (Map<String, Object>) read(data.getMainConfigPath()).get("microscope");
        assertThat(scope).containsEntry("detector_in_use", "LOCI_DETECTOR_JAI_001");
        assertThat(scope).containsEntry("objective_in_use", "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001");
    }

    @Test
    void wizardManagedValuesStillTakeEffect() throws Exception {
        // The merge must not turn the wizard read-only: an edited limit has to land.
        Path dir = Files.createTempDirectory("qpsc-wizard-writes");
        WizardData data = minimalData(dir);
        data.stageLimitXLow = -12345;
        Files.writeString(data.getMainConfigPath(), EXISTING_MAIN);

        ConfigFileWriter.writeMainConfig(data);

        Map<String, Object> stage =
                (Map<String, Object>) read(data.getMainConfigPath()).get("stage");
        Map<String, Object> limits = (Map<String, Object>) stage.get("limits");
        Object low = ((Map<String, Object>) limits.get("x_um")).get("low");
        assertThat(((Number) low).doubleValue()).isEqualTo(-12345.0);
    }

    @Test
    void tunedAutofocusEntriesSurviveAndOnlyNewObjectivesAreAppended() throws Exception {
        Path dir = Files.createTempDirectory("qpsc-wizard-af");
        WizardData data = minimalData(dir);
        data.objectives.add(new java.util.LinkedHashMap<>(Map.of("id", "BRAND_NEW_OBJECTIVE")));
        Files.writeString(data.getAutofocusConfigPath(), """
                schema_version: 2
                autofocus_settings:
                - objective: LOCI_OBJECTIVE_OLYMPUS_20X_POL_001
                  calibrated: true
                  score_metric: brenner_gradient
                  channel_reduction: green
                strategies:
                  stained_colour:
                    validity_check: chroma_deviation
                modalities:
                  ppm:
                    strategy: stained_colour
                """);

        ConfigFileWriter.writeAutofocusConfig(data);

        Map<String, Object> out = read(data.getAutofocusConfigPath());
        assertThat(out)
                .as("sections the wizard does not model")
                .containsKeys("schema_version", "strategies", "modalities");

        List<Map<String, Object>> settings = (List<Map<String, Object>>) out.get("autofocus_settings");
        Map<String, Object> tuned = settings.stream()
                .filter(m -> "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001".equals(m.get("objective")))
                .findFirst()
                .orElseThrow();
        assertThat(tuned).containsEntry("calibrated", true);
        assertThat(tuned).containsEntry("score_metric", "brenner_gradient");
        assertThat(tuned).as("the reduction settled on 2026-09-12").containsEntry("channel_reduction", "green");

        assertThat(settings).anyMatch(m -> "BRAND_NEW_OBJECTIVE".equals(m.get("objective")));
    }

    @Test
    void measuredExposuresAreNotReplacedByPlaceholders() throws Exception {
        Path dir = Files.createTempDirectory("qpsc-wizard-imaging");
        WizardData data = minimalData(dir);
        Files.writeString(data.getImagingConfigPath(), """
                background_correction:
                  ppm:
                    enabled: true
                    base_folder: D:\\backgrounds\\ppm
                imaging_profiles:
                  ppm:
                    LOCI_OBJECTIVE_OLYMPUS_20X_POL_001:
                      LOCI_DETECTOR_JAI_001:
                        exposures_ms:
                          positive: 3.61
                          negative: 7.86
                """);

        ConfigFileWriter.writeImagingConfig(data);

        Map<String, Object> out = read(data.getImagingConfigPath());
        Map<String, Object> profile = (Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) out.get("imaging_profiles")).get("ppm"))
                        .get("LOCI_OBJECTIVE_OLYMPUS_20X_POL_001");
        Map<String, Object> det = (Map<String, Object>) profile.get("LOCI_DETECTOR_JAI_001");
        Map<String, Object> exposures = (Map<String, Object>) det.get("exposures_ms");

        assertThat(exposures).containsEntry("positive", 3.61);
        assertThat(exposures).as("the placeholder must not appear").doesNotContainKey("single");

        Map<String, Object> bg =
                (Map<String, Object>) ((Map<String, Object>) out.get("background_correction")).get("ppm");
        assertThat(bg).containsEntry("enabled", true);
        assertThat(bg).containsEntry("base_folder", "D:\\backgrounds\\ppm");
    }

    @Test
    void aBackupIsTakenBeforeRewriting() throws Exception {
        Path dir = Files.createTempDirectory("qpsc-wizard-backup");
        WizardData data = minimalData(dir);
        Files.writeString(data.getMainConfigPath(), EXISTING_MAIN);

        ConfigFileWriter.writeMainConfig(data);

        try (var entries = Files.list(dir)) {
            assertThat(entries.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("config_PPM.yml.bak-"));
        }
    }

    @Test
    void aFreshInstallStillGetsACompleteFile() throws Exception {
        // Nothing on disk: the wizard must still produce a config that validation accepts,
        // including the runtime-state placeholders.
        Path dir = Files.createTempDirectory("qpsc-wizard-fresh");
        WizardData data = minimalData(dir);
        Files.createDirectories(dir);

        ConfigFileWriter.writeMainConfig(data);

        Map<String, Object> out = read(data.getMainConfigPath());
        assertThat(out).containsKeys("microscope", "modalities", "hardware", "stage", "slide_size_um");
        assertThat((Map<String, Object>) out.get("microscope")).containsKey("detector_in_use");
        assertThat(Files.readString(data.getMainConfigPath())).startsWith("# Microscope configuration");
    }
}
