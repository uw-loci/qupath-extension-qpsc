package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code context} block of a background_settings.yml: the setup the set was collected under. */
class BackgroundSettingsContextTest {

    @TempDir
    Path tempDir;

    @Test
    void contextIsReadWhenPresentAndNullWhenNot() throws IOException {
        Path withContext = tempDir.resolve("with.yml");
        Files.writeString(withContext, """
                hardware:
                  modality: Brightfield
                  objective: OBJ
                  detector: DET
                  magnification: 10x
                angle_exposures:
                  - angle: 0.0
                    exposure: 12.5
                context:
                  profile: carrier4
                  insert: quad_v
                  slide_insertion: B
                  stage_xy_um: [1.0, 2.0]
                  stage_z_um: -3.0
                  collected_at: '2026-10-21T09:30:12'
                """, StandardCharsets.UTF_8);
        var bg = BackgroundSettingsReader.readBackgroundSettings(withContext.toFile());
        assertNotNull(bg);
        assertEquals("carrier4", bg.profileId);
        assertEquals("quad_v", bg.contextInsert);
        assertEquals("B", bg.contextSlideInsertion);
        assertEquals("2026-10-21T09:30:12", bg.collectedAt);

        Path without = tempDir.resolve("without.yml");
        Files.writeString(without, """
                hardware:
                  modality: Brightfield
                angle_exposures:
                  - angle: 0.0
                    exposure: 12.5
                """, StandardCharsets.UTF_8);
        var old = BackgroundSettingsReader.readBackgroundSettings(without.toFile());
        assertNotNull(old);
        assertNull(old.profileId);
        assertNull(old.contextInsert);
        assertNull(old.contextSlideInsertion);
        assertNull(old.collectedAt);
    }
}
