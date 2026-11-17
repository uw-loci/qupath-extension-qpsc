package qupath.ext.qpsc.background;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.stitching.StitchInfoSupport;

/**
 * A BaSiC profile records the objective and detector its tiles were acquired with, read from the
 * acquisition command beside them, and a run on other hardware is told; a profile covering only
 * some of the selected channels says which it misses.
 */
class FlatfieldHardwareRecordTest {

    private static final String OBJ = "LOCI_OBJECTIVE_NIKON_20X_001";
    private static final String DET = "LOCI_DETECTOR_PMT_001";

    @TempDir
    Path tmp;

    /** The command file as MinorFunctions.saveAcquisitionCommand formats it: one flag per line. */
    private void commandFile(Path folder, String name, String objective) throws Exception {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(name), """
                # QuPath Acquisition Command
                # Sample: s
                --yaml "C:/configs/config_SHG.yml" \\
                    --projects "D:/data" \\
                    --objective %s \\
                    --detector %s \\
                    --flatfield-folder "D:/profiles/my pmt"
                """.formatted(objective, DET));
    }

    @Test
    void theHardwareIsReadFromTheCommandBesideTheTiles() throws Exception {
        Path region = tmp.resolve("sample").resolve("SHG_1").resolve("bounds");
        commandFile(region, "acquisition_command_20261101_100000.txt", "OLD_OBJECTIVE");
        commandFile(region, "acquisition_command_20261102_100000.txt", OBJ);

        assertThat(StitchInfoSupport.acquiredObjective(region.toFile())).isEqualTo(OBJ);
        assertThat(StitchInfoSupport.acquiredDetector(region.toFile())).isEqualTo(DET);
    }

    @Test
    void aChannelFolderReadsTheRegionsCommand() throws Exception {
        Path region = tmp.resolve("region");
        commandFile(region, "acquisition_command_20261102_100000.txt", OBJ);
        Path channel = Files.createDirectories(region.resolve("DAPI"));

        assertThat(StitchInfoSupport.acquiredObjective(channel.toFile())).isEqualTo(OBJ);
    }

    @Test
    void foldersWithoutACommandGiveNothing() throws Exception {
        Path plain = Files.createDirectories(tmp.resolve("a").resolve("b"));
        assertThat(StitchInfoSupport.acquiredObjective(plain.toFile())).isNull();
        assertThat(StitchInfoSupport.acquiredDetector(null)).isNull();
    }

    @Test
    void aProfileFittedOnOtherHardwareIsReported() throws Exception {
        Path profile = Files.createDirectories(tmp.resolve("profile"));
        Files.writeString(
                profile.resolve("profile.yml"), "offset: 10.0\nobjective: " + OBJ + "\ndetector: " + DET + "\n");

        assertThat(FlatfieldProfiles.hardwareMismatches(profile.toString(), OBJ, DET))
                .isEmpty();
        List<String> other =
                FlatfieldProfiles.hardwareMismatches(profile.toString(), "LOCI_OBJECTIVE_NIKON_10X_001", DET);
        assertThat(other)
                .singleElement()
                .asString()
                .contains("objective " + OBJ)
                .contains("NIKON_10X");
        // Unknown on either side is not compared.
        assertThat(FlatfieldProfiles.hardwareMismatches(profile.toString(), null, null))
                .isEmpty();
    }

    @Test
    void eachChannelProfileIsCheckedAndOldProfilesAreNot() throws Exception {
        Path set = tmp.resolve("set");
        Files.createDirectories(set.resolve("DAPI"));
        Files.createDirectories(set.resolve("FITC"));
        Files.writeString(set.resolve("DAPI").resolve("profile.yml"), "offset: 1\ndetector: OTHER_CAMERA\n");
        Files.writeString(set.resolve("FITC").resolve("profile.yml"), "offset: 1\n");

        assertThat(FlatfieldProfiles.hardwareMismatches(set.toString(), OBJ, DET))
                .singleElement()
                .asString()
                .contains("for DAPI")
                .contains("detector OTHER_CAMERA");
    }

    @Test
    void theChannelsAProfileMissesAreNamed() throws Exception {
        Path set = tmp.resolve("set");
        Files.createDirectories(set.resolve("DAPI"));
        Files.writeString(set.resolve("DAPI").resolve("profile.yml"), "offset: 1\n");

        assertThat(FlatfieldProfiles.uncoveredChannels(set.toString(), List.of("DAPI", "FITC", "TRITC")))
                .containsExactly("FITC", "TRITC");

        Path every = Files.createDirectories(tmp.resolve("every"));
        Files.writeString(every.resolve("profile.yml"), "offset: 1\n");
        assertThat(FlatfieldProfiles.uncoveredChannels(every.toString(), List.of("DAPI", "FITC")))
                .isEmpty();
    }
}
