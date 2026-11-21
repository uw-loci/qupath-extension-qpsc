package qupath.ext.qpsc.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Re-stitch's folder selection. The old default "." matched only names with a dot (PPM angle
 * folders), so an LC-PolScope region (State0..4, retardance, orientation, driftcorr_*) or a
 * fluorescence one found no folders, fell back to the region folder and failed.
 */
class StitchingRecoveryFolderSelectionTest {

    @TempDir
    Path region;

    private void tileFolder(String name) throws IOException {
        Path d = Files.createDirectories(region.resolve(name));
        Files.writeString(d.resolve("TileConfiguration.txt"), "dim = 2\n");
    }

    private static java.util.List<String> names(java.util.List<File> folders) {
        return folders.stream().map(File::getName).toList();
    }

    @Test
    void emptySelectsEveryTileFolderOfAnLcPolScopeRegion() throws IOException {
        for (String n : new String[] {
            "State0", "retardance", "orientation", "orientation_color", "driftcorr_retardance", "driftcorr_orientation"
        }) {
            tileFolder(n);
        }
        Files.createDirectories(region.resolve("_protected").resolve("drift_correction"));
        tileFolder("_temp_retardance_ab12");
        assertThat(names(StitchingRecoveryWorkflow.findTileFolders(region, "")))
                .containsExactly(
                        "State0",
                        "driftcorr_orientation",
                        "driftcorr_retardance",
                        "orientation",
                        "orientation_color",
                        "retardance");
    }

    @Test
    void partOfANameSelectsTheOriginalAndTheCorrectedCopy() throws IOException {
        tileFolder("retardance");
        tileFolder("driftcorr_retardance");
        tileFolder("orientation");
        assertThat(names(StitchingRecoveryWorkflow.findTileFolders(region, "retardance")))
                .containsExactly("driftcorr_retardance", "retardance");
    }

    @Test
    void aDotSelectsEveryTileFolderToo() throws IOException {
        tileFolder("0.0");
        tileFolder("90.0");
        tileFolder("retardance");
        Files.createDirectories(region.resolve("notiles.0"));
        assertThat(names(StitchingRecoveryWorkflow.findTileFolders(region, ".")))
                .containsExactly("0.0", "90.0", "retardance");
    }
}
