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
    void isolationFoldersAreSkippedButOthersAreNot() {
        File acquisition = tempDir.resolve("bounds").toFile();
        assertEquals(acquisition, StitchInfoSupport.acquisitionFolder(new File(acquisition, "_temp_7_0_ab")));
        assertEquals(acquisition, StitchInfoSupport.acquisitionFolder(acquisition));
    }
}
