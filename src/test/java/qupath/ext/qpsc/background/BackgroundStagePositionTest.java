package qupath.ext.qpsc.background;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading where a background set was collected, as Background Collection records it. */
class BackgroundStagePositionTest {

    @TempDir
    Path dir;

    @Test
    void readsTheRecordedStagePosition() throws Exception {
        Files.writeString(
                dir.resolve("background_settings.yml"),
                "context:\n  profile: MH_Hamm_background\n  stage_xy_um: [24711.2, 2534.5]\n  stage_z_um: 120.8\n");
        assertThat(BackgroundSettingsReader.readStagePosition(dir.toString())).containsExactly(24711.2, 2534.5);
    }

    @Test
    void anOlderSetWithoutAPositionGivesNull() throws Exception {
        Files.writeString(dir.resolve("background_settings.yml"), "context:\n  profile: p\n");
        assertThat(BackgroundSettingsReader.readStagePosition(dir.toString())).isNull();
    }

    @Test
    void noFolderOrNoFileGivesNull() {
        assertThat(BackgroundSettingsReader.readStagePosition(null)).isNull();
        assertThat(BackgroundSettingsReader.readStagePosition(dir.toString())).isNull();
    }
}
