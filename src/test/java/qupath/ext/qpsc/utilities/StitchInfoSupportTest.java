package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.basicstitching.workflow.StitchInfoFile;

/** QPSC's acquisition section of the stitch record, and the record surviving a rename. */
class StitchInfoSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void acquisitionSectionFindsTheAcquisitionBehindAnIsolationFolder() throws IOException {
        Path acquisition = Files.createDirectories(tempDir.resolve("FluoCells/Fluorescence_10x_7/bounds"));
        Path isolation = Files.createDirectories(acquisition.resolve("_temp_DAPI_ab12"));
        Files.writeString(
                acquisition.resolve("acquisition_command_20260917_151641.txt"),
                "# QuPath Acquisition Command\n--channels (TRITC,DAPI,FITC) \\\n    --focus-channel TRITC\n",
                StandardCharsets.UTF_8);
        Files.writeString(acquisition.resolve("MMproperties.txt"), "x", StandardCharsets.UTF_8);
        Files.writeString(acquisition.resolve("unrelated.log"), "x", StandardCharsets.UTF_8);

        Path out = Files.createDirectories(tempDir.resolve("SlideImages"));
        Path stitched = out.resolve("FluoCells_bounds_DAPI_tmp.ome.tif");
        Files.writeString(stitched, "image");
        StitchInfoFile.write(stitched, List.of(StitchInfoFile.Section.of("image", Map.of("file", "x"))));

        Path renamed = out.resolve("FluoCells_Fluorescence_10x_1_bounds_DAPI.ome.tif");
        Files.move(stitched, renamed);
        StitchInfoSupport.moveWith(stitched.toFile(), renamed.toFile());
        StitchInfoSupport.appendAcquisition(
                renamed.toString(), null, isolation.toFile(), Map.of("produced by", "test"));

        String text = Files.readString(StitchInfoFile.pathFor(renamed), StandardCharsets.US_ASCII);
        assertTrue(text.contains("[image]"), "the stitcher's sections must survive the rename:\n" + text);
        assertTrue(text.contains("[acquisition]"), text);
        assertTrue(text.contains("produced by: test"), text);
        assertTrue(text.contains("acquisition folder: " + acquisition.toFile().getAbsolutePath()), text);
        assertTrue(
                text.contains("acquisition files: MMproperties.txt, acquisition_command_20260917_151641.txt")
                        || text.contains(
                                "acquisition files: acquisition_command_20260917_151641.txt, MMproperties.txt"),
                text);
        assertFalse(text.contains("unrelated.log"), text);
        assertTrue(text.contains("    --focus-channel TRITC"), "the command is embedded verbatim:\n" + text);
        assertFalse(text.contains("# QuPath Acquisition Command"), "comment header is dropped:\n" + text);
    }

    @Test
    void whiteBalanceModeComesFromTheNewestCommandAndIsNeverAnError() throws IOException {
        Path acquisition = Files.createDirectories(tempDir.resolve("PPMSlide/ppm_20x_3/Tissue_1"));
        Path isolation = Files.createDirectories(acquisition.resolve("_temp_7_0_ab"));
        Files.writeString(
                acquisition.resolve("acquisition_command_20261019_090000.txt"),
                "--angles (-7,0,7,90) \\\n    --wb-mode simple \\\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                acquisition.resolve("acquisition_command_20261020_090000.txt"),
                "# QuPath Acquisition Command\n--angles (-7,0,7,90) \\\n    --wb-mode per_angle \\\n    --af-tiles 4\n",
                StandardCharsets.UTF_8);
        assertEquals("per_angle", StitchInfoSupport.whiteBalanceMode(isolation.toFile()));
        assertEquals("Per-angle (PPM) (per_angle)", StitchInfoSupport.whiteBalanceLabel("per_angle"));

        // No white balance: "off" is an answer like any other.
        Path off = Files.createDirectories(tempDir.resolve("off"));
        Files.writeString(off.resolve("acquisition_command_20261020_100000.txt"), "--wb-mode off\n");
        assertEquals("off", StitchInfoSupport.whiteBalanceMode(off.toFile()));
        assertEquals("Off (off)", StitchInfoSupport.whiteBalanceLabel("off"));

        // Not recorded at all: null, no exception.
        Path noFlag = Files.createDirectories(tempDir.resolve("noflag"));
        Files.writeString(noFlag.resolve("acquisition_command_20261020_100000.txt"), "--angles (0)\n");
        assertNull(StitchInfoSupport.whiteBalanceMode(noFlag.toFile()));
        assertNull(StitchInfoSupport.whiteBalanceMode(
                Files.createDirectories(tempDir.resolve("empty")).toFile()));
        assertNull(StitchInfoSupport.whiteBalanceMode(tempDir.resolve("missing").toFile()));
        assertNull(StitchInfoSupport.whiteBalanceMode(null));
        assertNull(StitchInfoSupport.whiteBalanceLabel(null));
        assertEquals("future_mode", StitchInfoSupport.whiteBalanceLabel("future_mode"));
    }

    @Test
    void acquisitionSectionNamesTheWhiteBalanceOrSaysItWasNotRecorded() throws IOException {
        Path out = Files.createDirectories(tempDir.resolve("SlideImages"));

        Path acquisition = Files.createDirectories(tempDir.resolve("a/ppm_20x_1/Tissue"));
        Files.writeString(acquisition.resolve("acquisition_command_20261020_090000.txt"), "--wb-mode off\n");
        Path withMode = out.resolve("withMode.ome.tif");
        Files.writeString(withMode, "image");
        StitchInfoSupport.appendAcquisition(withMode.toString(), null, acquisition.toFile(), Map.of());
        assertTrue(Files.readString(StitchInfoFile.pathFor(withMode), StandardCharsets.US_ASCII)
                .contains("white balance: Off (off)"));

        Path bare = Files.createDirectories(tempDir.resolve("b/ppm_20x_1/Tissue"));
        Path without = out.resolve("without.ome.tif");
        Files.writeString(without, "image");
        StitchInfoSupport.appendAcquisition(without.toString(), null, bare.toFile(), Map.of());
        assertTrue(Files.readString(StitchInfoFile.pathFor(without), StandardCharsets.US_ASCII)
                .contains("white balance: not recorded"));
    }

    @Test
    void acquisitionSectionNamesTheBackgroundProfileAndFolder() throws IOException {
        Path out = Files.createDirectories(tempDir.resolve("SlideImages"));

        Path corrected = Files.createDirectories(tempDir.resolve("c/ppm_20x_1/Tissue"));
        Files.writeString(
                corrected.resolve("acquisition_command_20261021_090000.txt"),
                "--bg-correction true \\\n    --bg-method divide \\\n"
                        + "    --bg-folder D:/bg/profiles/carrier4/LOCI_DETECTOR_JAI_001/ppm/20x/per_angle \\\n"
                        + "    --bg-profile carrier4 \\\n    --wb-mode per_angle\n");
        Path withBg = out.resolve("withBg.ome.tif");
        Files.writeString(withBg, "image");
        StitchInfoSupport.appendAcquisition(withBg.toString(), null, corrected.toFile(), Map.of());
        String text = Files.readString(StitchInfoFile.pathFor(withBg), StandardCharsets.US_ASCII);
        assertTrue(text.contains("background profile: carrier4"), text);
        assertTrue(
                text.contains("background folder: D:/bg/profiles/carrier4/LOCI_DETECTOR_JAI_001/ppm/20x/per_angle"),
                text);
        assertEquals("carrier4", StitchInfoSupport.backgroundProfile(corrected.toFile()));

        Path uncorrected = Files.createDirectories(tempDir.resolve("u/ppm_20x_1/Tissue"));
        Files.writeString(uncorrected.resolve("acquisition_command_20261021_090000.txt"), "--wb-mode off\n");
        Path without = out.resolve("without.ome.tif");
        Files.writeString(without, "image");
        StitchInfoSupport.appendAcquisition(without.toString(), null, uncorrected.toFile(), Map.of());
        text = Files.readString(StitchInfoFile.pathFor(without), StandardCharsets.US_ASCII);
        assertTrue(text.contains("background profile: none"), text);
        assertTrue(text.contains("background folder: none (no background correction)"), text);
        assertNull(StitchInfoSupport.backgroundProfile(uncorrected.toFile()));
        assertNull(StitchInfoSupport.backgroundProfile(null));
    }

    @Test
    void isolationFoldersAreSkippedButOthersAreNot() {
        File acquisition = tempDir.resolve("bounds").toFile();
        assertEquals(acquisition, StitchInfoSupport.acquisitionFolder(new File(acquisition, "_temp_7_0_ab")));
        assertEquals(acquisition, StitchInfoSupport.acquisitionFolder(acquisition));
    }
}
