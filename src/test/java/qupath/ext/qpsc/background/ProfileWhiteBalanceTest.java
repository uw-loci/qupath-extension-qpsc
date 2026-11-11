package qupath.ext.qpsc.background;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;

/**
 * Each background profile keeps its own PPM white balance beside its backgrounds, in
 * white_balance.yml of the white-balance-mode folder.
 */
class ProfileWhiteBalanceTest {

    private static final String OBJ = "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001";
    private static final String DET = "LOCI_DETECTOR_JAI_001";

    @TempDir
    Path tmp;

    private Map<String, Object> scopeWide(boolean withSimple) {
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        settings.put("exposures_ms", Map.of("positive", Map.of("all", 3.9, "r", 2.4, "g", 3.9, "b", 10.2)));
        settings.put("gains", Map.of("positive", Map.of("unified_gain", 2.0, "analog_red", 1.0, "analog_blue", 4.0)));
        settings.put("wb_last_modified", "2026-11-05T23:59:25");
        if (withSimple) {
            settings.put("simple_wb", Map.of("base_angle", "uncrossed", "angles", Map.of()));
        }
        return Map.of(OBJ, Map.of(DET, settings));
    }

    @Test
    void theFileLivesInTheBackgroundFolderForTheMode() {
        File f = ProfileWhiteBalance.file(tmp.toString(), "ppm_20x", OBJ, DET, "per_angle");
        assertThat(f.toPath())
                .isEqualTo(tmp.resolve(DET)
                        .resolve("ppm")
                        .resolve("20x")
                        .resolve("per_angle")
                        .resolve("white_balance.yml"));
        assertThat(ProfileWhiteBalance.file(tmp.toString(), "ppm", OBJ, DET, "off"))
                .isNull();
        assertThat(ProfileWhiteBalance.file(tmp.toString(), "brightfield", OBJ, DET, "per_angle"))
                .isNull();
    }

    @Test
    void copyingTheScopeWideCalibrationGivesTheProfileItsOwn() {
        List<File> written = ProfileWhiteBalance.copyScopeWide(scopeWide(true), tmp.toString());

        assertThat(written).hasSize(2);
        assertThat(ProfileWhiteBalance.exists(tmp.toString(), "ppm", OBJ, DET, "per_angle"))
                .isTrue();
        assertThat(ProfileWhiteBalance.exists(tmp.toString(), "ppm", OBJ, DET, "simple"))
                .isTrue();
        File perAngle = ProfileWhiteBalance.file(tmp.toString(), "ppm", OBJ, DET, "per_angle");
        assertThat(ProfileWhiteBalance.value(perAngle, "exposures_ms", "positive", "g"))
                .isEqualTo(3.9);
        assertThat(ProfileWhiteBalance.value(perAngle, "simple_wb")).isNull();
        File simple = ProfileWhiteBalance.file(tmp.toString(), "ppm", OBJ, DET, "simple");
        assertThat(ProfileWhiteBalance.value(simple, "simple_wb", "base_angle")).isEqualTo("uncrossed");
    }

    @Test
    void aProfilesOwnWhiteBalanceIsNeverOverwritten() throws Exception {
        File perAngle = ProfileWhiteBalance.file(tmp.toString(), "ppm", OBJ, DET, "per_angle");
        Files.createDirectories(perAngle.toPath().getParent());
        Files.writeString(perAngle.toPath(), "white_balance:\n  exposures_ms: {positive: {g: 1.5}}\n");

        List<File> written = ProfileWhiteBalance.copyScopeWide(scopeWide(false), tmp.toString());

        assertThat(written).isEmpty();
        assertThat(ProfileWhiteBalance.value(perAngle, "exposures_ms", "positive", "g"))
                .isEqualTo(1.5);
    }

    @Test
    void anEditedFileIsReadAgain() throws Exception {
        File perAngle = ProfileWhiteBalance.file(tmp.toString(), "ppm", OBJ, DET, "per_angle");
        Files.createDirectories(perAngle.toPath().getParent());
        Files.writeString(perAngle.toPath(), "white_balance:\n  wb_last_modified: a\n");
        assertThat(ProfileWhiteBalance.value(perAngle, "wb_last_modified")).isEqualTo("a");
        Files.writeString(perAngle.toPath(), "white_balance:\n  wb_last_modified: bb\n");
        assertThat(ProfileWhiteBalance.value(perAngle, "wb_last_modified")).isEqualTo("bb");
    }

    @Test
    void theAcquisitionNamesTheProfilesFolder() {
        String cmd = AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("s")
                .scanType("ppm_20x")
                .regionName("bounds")
                .wbMode("per_angle")
                .wbFolder("D:/bg/profiles/gel/JAI/ppm/20x/per_angle")
                .buildSocketMessage();
        assertThat(cmd)
                .contains("--wb-mode per_angle")
                .contains("--wb-folder D:/bg/profiles/gel/JAI/ppm/20x/per_angle");
        String off = AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("s")
                .scanType("ppm_20x")
                .regionName("bounds")
                .wbMode("off")
                .wbFolder("D:/x")
                .buildSocketMessage();
        assertThat(off).doesNotContain("--wb-folder");
    }

    @Test
    void theRunStartsAskWhenTheProfileHasNone() throws Exception {
        for (String file : new String[] {
            "controller/BoundedAcquisitionWorkflow.java",
            "controller/workflow/AcquisitionManager.java",
            "controller/ExistingImageWorkflowV2.java",
            "controller/BackgroundCollectionWorkflow.java"
        }) {
            assertThat(Files.readString(Path.of("src/main/java/qupath/ext/qpsc", file)))
                    .as(file)
                    .contains("ProfileWhiteBalanceGate.confirm");
        }
    }
}
