package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a profile folder covers, which the dialogs and the wizard report. */
class FlatfieldProfilesTest {

    private static void writeProfile(Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("profile.yml"), "offset: 0\n");
    }

    @Test
    void aProfileInTheFolderCoversEveryImage(@TempDir Path dir) throws IOException {
        writeProfile(dir);
        assertEquals(List.of(FlatfieldProfiles.EVERY_IMAGE), FlatfieldProfiles.covered(dir.toString()));
    }

    @Test
    void subfoldersCoverTheirChannels(@TempDir Path dir) throws IOException {
        writeProfile(dir.resolve("FITC"));
        writeProfile(dir.resolve("DAPI"));
        Files.createDirectories(dir.resolve("notes"));
        assertEquals(List.of("DAPI", "FITC"), FlatfieldProfiles.covered(dir.toString()));
    }

    @Test
    void aMissingOrBlankFolderCoversNothing(@TempDir Path dir) {
        assertTrue(FlatfieldProfiles.covered(dir.resolve("gone").toString()).isEmpty());
        assertTrue(FlatfieldProfiles.covered("  ").isEmpty());
        assertTrue(FlatfieldProfiles.covered(null).isEmpty());
        assertTrue(FlatfieldProfiles.describe(null).startsWith("No BaSiC profile"));
    }

    @Test
    void anUnknownSavedMethodIsBackground() {
        assertEquals(FlatfieldMethod.BASIC, FlatfieldMethod.fromName("BASIC"));
        assertEquals(FlatfieldMethod.BACKGROUND, FlatfieldMethod.fromName("nonsense"));
    }
}
