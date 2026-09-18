package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A re-stitch must align channels the way the acquisition did, from what the acquisition left on
 * disk -- recovery has no dialog choices to go on.
 */
class StitchingRecoveryAlignmentTest {

    private static final List<String> CHANNELS = List.of("DAPI", "FITC", "TRITC");

    @TempDir
    Path tempDir;

    @Test
    void previousSingleChannelSolveIsReplayed() {
        assertEquals(
                List.of("DAPI"),
                StitchingRecoveryWorkflow.chooseRecoveryAlignment(CHANNELS, "DAPI", CHANNELS, null, "TRITC"));
    }

    @Test
    void previousProjectionIsReplayed() {
        assertEquals(
                List.of("DAPI", "FITC"),
                StitchingRecoveryWorkflow.chooseRecoveryAlignment(
                        CHANNELS, "projection(DAPI+FITC)", CHANNELS, null, "TRITC"));
    }

    @Test
    void withoutAPreviousSolveTheDedicatedFocusChannelWinsThenTheFocusRadio() {
        assertEquals(
                List.of("FITC"),
                StitchingRecoveryWorkflow.chooseRecoveryAlignment(CHANNELS, null, CHANNELS, "FITC", "TRITC"));
        assertEquals(
                List.of("TRITC"),
                StitchingRecoveryWorkflow.chooseRecoveryAlignment(CHANNELS, null, CHANNELS, null, "TRITC"));
    }

    @Test
    void noUsableFocusChannelMergesEveryChannel() {
        // A dedicated focus channel the run did not image, and no Focus radio.
        assertEquals(
                CHANNELS, StitchingRecoveryWorkflow.chooseRecoveryAlignment(CHANNELS, null, CHANNELS, "Cy5", null));
    }

    @Test
    void anglesAndUnrecordedFoldersKeepTheAnglePath() {
        List<String> angles = List.of("-7.0", "7.0", "90.0");
        assertNull(StitchingRecoveryWorkflow.chooseRecoveryAlignment(angles, null, List.of(), null, null));
        assertNull(
                StitchingRecoveryWorkflow.chooseRecoveryAlignment(CHANNELS, null, List.of("DAPI", "FITC"), null, null),
                "a folder the acquisition did not list is not known to be a channel");
        assertNull(
                StitchingRecoveryWorkflow.chooseRecoveryAlignment(List.of("DAPI"), null, CHANNELS, null, "DAPI"),
                "one channel has nothing to choose between");
    }

    @Test
    void readsTheRealAcquisitionRecordFormat() throws IOException {
        // Shape copied from a real OWS3 acquisition_command file: comment header, one flag per
        // line, backslash continuations.
        Files.writeString(
                tempDir.resolve("acquisition_command_20260917_151641.txt"),
                String.join(
                        "\n",
                        "# QuPath Acquisition Command",
                        "# Region: bounds",
                        "--yaml C:/QPSC/config.yml \\",
                        "    --pixel-size 0.653 \\",
                        "    --channels (TRITC,DAPI,FITC) \\",
                        "    --channel-exposures (80.0,20.0,100.0) \\",
                        "    --focus-channel TRITC \\",
                        "    --channel-intensities (TRITC=100.0,DAPI=100.0,FITC=100.0)"),
                StandardCharsets.UTF_8);
        for (String ch : CHANNELS) {
            Files.createDirectories(tempDir.resolve(ch));
        }

        assertEquals(List.of("TRITC"), StitchingRecoveryWorkflow.recoveryAlignment(tempDir.toFile(), CHANNELS));

        Files.writeString(
                tempDir.resolve("TileRegistration.txt"),
                "# QPSC tile registration solution v1\n# reference: DAPI\n# name; deltaXPx; deltaYPx\n0.tif; 1.0; 2.0\n",
                StandardCharsets.US_ASCII);
        assertEquals(
                List.of("DAPI"),
                StitchingRecoveryWorkflow.recoveryAlignment(tempDir.toFile(), CHANNELS),
                "the previous solve's choice outranks the focus channel");
    }
}
