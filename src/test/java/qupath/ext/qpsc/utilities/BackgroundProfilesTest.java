package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/** Background profiles: folders, records, the active one, and the setup checks. */
class BackgroundProfilesTest {

    @TempDir
    Path tempDir;

    private String base;
    private String previousActive;

    @BeforeEach
    void setUp() {
        base = tempDir.resolve("background_tiles").toString();
        previousActive = PersistentPreferences.getActiveBackgroundProfile();
        PersistentPreferences.setActiveBackgroundProfile("");
    }

    @AfterEach
    void tearDown() {
        PersistentPreferences.setActiveBackgroundProfile(previousActive);
    }

    @Test
    void idsAreFolderNames() {
        assertTrue(BackgroundProfiles.isValidId("carrier4_aperio"));
        assertTrue(BackgroundProfiles.isValidId("A-1"));
        assertFalse(BackgroundProfiles.isValidId(null));
        assertFalse(BackgroundProfiles.isValidId(""));
        assertFalse(BackgroundProfiles.isValidId("has space"));
        assertFalse(BackgroundProfiles.isValidId("-leading"));
        assertFalse(BackgroundProfiles.isValidId("a/b"));
        assertFalse(BackgroundProfiles.isValidId("x".repeat(65)));
    }

    @Test
    void createListFindAndReadBack() throws IOException {
        var created = BackgroundProfiles.create(
                base,
                "carrier4_aperio",
                "4-slide carrier, Aperio slides",
                "Permount, 1.5 coverslips",
                "quad_v",
                "b",
                Map.of("carrier", "quad_v", "medium", "Permount"));
        assertEquals(Path.of(base, "profiles", "carrier4_aperio"), created.folder());
        assertTrue(Files.isRegularFile(created.folder().resolve("profile.yml")));
        assertEquals("B", created.expectedSlideInsertion(), "placement is normalized to upper case");

        List<BackgroundProfiles.BackgroundProfile> all = BackgroundProfiles.list(base);
        assertEquals(1, all.size());
        var read = all.get(0);
        assertEquals("carrier4_aperio", read.id());
        assertEquals("4-slide carrier, Aperio slides", read.name());
        assertEquals("Permount, 1.5 coverslips", read.description());
        assertEquals("quad_v", read.expectedInsert());
        assertEquals("B", read.expectedSlideInsertion());
        assertEquals("Permount", read.attributes().get("medium"));
        assertNotNull(read.createdAt());
        assertTrue(BackgroundProfiles.find(base, "carrier4_aperio").isPresent());
        assertTrue(BackgroundProfiles.find(base, "nope").isEmpty());
    }

    @Test
    void listIsSortedByDisplayNameAndSkipsFoldersWithoutARecord() throws IOException {
        BackgroundProfiles.create(base, "zeta", "Zeta", null, null, null, null);
        BackgroundProfiles.create(base, "alpha", "alpha", null, null, null, null);
        BackgroundProfiles.create(base, "noname", null, null, null, null, null);
        Files.createDirectories(Path.of(base, "profiles", "stray"));
        List<String> names = BackgroundProfiles.list(base).stream()
                .map(BackgroundProfiles.BackgroundProfile::displayName)
                .toList();
        assertEquals(List.of("alpha", "noname", "Zeta"), names);
    }

    @Test
    void createRefusesBadIdsDuplicatesAndNoBase() throws IOException {
        assertThrows(
                IllegalArgumentException.class,
                () -> BackgroundProfiles.create(base, "bad id", null, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> BackgroundProfiles.create(null, "fine", null, null, null, null, null));
        BackgroundProfiles.create(base, "twice", null, null, null, null, null);
        assertThrows(IOException.class, () -> BackgroundProfiles.create(base, "twice", null, null, null, null, null));
    }

    @Test
    void aRecordWhoseIdDisagreesWithItsFolderIsSkipped() throws IOException {
        Path folder = Files.createDirectories(Path.of(base, "profiles", "folder_name"));
        Files.writeString(folder.resolve("profile.yml"), "id: other_name\nname: x\n", StandardCharsets.UTF_8);
        assertNull(BackgroundProfiles.read(folder));
        assertTrue(BackgroundProfiles.list(base).isEmpty());
    }

    @Test
    void theActiveProfileResolvesToItsFolderOrToNothing() throws IOException {
        assertNull(BackgroundProfiles.activeFolder(base), "nothing active");
        var p = BackgroundProfiles.create(base, "p1", "P1", null, null, null, null);
        BackgroundProfiles.setActiveId("p1");
        assertEquals("p1", BackgroundProfiles.activeId());
        assertEquals(p.folder().toString(), BackgroundProfiles.activeFolder(base));
        assertEquals("p1", BackgroundProfiles.active(base).orElseThrow().id());

        BackgroundProfiles.setActiveId("missing");
        assertNull(BackgroundProfiles.activeFolder(base), "an active id that does not exist is no profile");
        assertNull(BackgroundProfiles.activeFolder(null), "no base folder, no profile");
        BackgroundProfiles.setActiveId(null);
        assertNull(BackgroundProfiles.activeId());
    }

    @Test
    void oldLayoutFoldersAreListedAndAdoptedNotRead() throws IOException {
        Files.createDirectories(Path.of(base, "LOCI_DETECTOR_JAI_001", "ppm", "20x"));
        Files.createDirectories(Path.of(base, "HAMAMATSU_DCAM_01", "Brightfield", "10x"));
        BackgroundProfiles.create(base, "legacy", "Before profiles", null, null, null, null);

        List<Path> old = BackgroundProfiles.unprofiledFolders(base);
        assertEquals(
                List.of("HAMAMATSU_DCAM_01", "LOCI_DETECTOR_JAI_001"),
                old.stream().map(f -> f.getFileName().toString()).toList(),
                "profiles/ itself is not an old folder");

        assertTrue(BackgroundProfiles.adopt(base, "legacy", old).isEmpty());
        assertTrue(Files.isDirectory(Path.of(base, "profiles", "legacy", "LOCI_DETECTOR_JAI_001", "ppm", "20x")));
        assertTrue(BackgroundProfiles.unprofiledFolders(base).isEmpty());

        // A second folder with a name the profile already has is left alone and reported.
        Path again = Files.createDirectories(Path.of(base, "LOCI_DETECTOR_JAI_001"));
        List<Path> skipped = BackgroundProfiles.adopt(base, "legacy", List.of(again));
        assertEquals(List.of(again), skipped);
        assertTrue(Files.isDirectory(again));

        assertThrows(IOException.class, () -> BackgroundProfiles.adopt(base, "nosuch", List.of()));
    }

    @Test
    void deleteRemovesTheFolderAndClearsTheActiveProfile() throws IOException {
        var p = BackgroundProfiles.create(base, "gone", null, null, null, null, null);
        Files.createDirectories(p.folder().resolve("DET").resolve("bf").resolve("10x"));
        Files.writeString(p.folder().resolve("DET/bf/10x/background.tif"), "x");
        BackgroundProfiles.setActiveId("gone");
        BackgroundProfiles.delete(base, "gone");
        assertFalse(Files.exists(p.folder()));
        assertNull(BackgroundProfiles.activeId());
        BackgroundProfiles.delete(base, "gone"); // idempotent
    }

    @Test
    void setupProblemsCompareOnlyWhatBothSidesKnow() {
        var profile =
                new BackgroundProfiles.BackgroundProfile("p", "P", null, null, "quad_v", "B", Map.of(), Path.of("p"));
        assertTrue(BackgroundProfiles.setupProblems(profile, null, null).isEmpty(), "current setup unknown");
        assertTrue(BackgroundProfiles.setupProblems(profile, "QUAD_V", "b").isEmpty(), "case does not matter");
        List<String> problems = BackgroundProfiles.setupProblems(profile, "single_h", "A");
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("single_h") && problems.get(0).contains("quad_v"));
        // Side-neutral wording: on a vertical holder the label is at an end, not on a side.
        assertTrue(problems.get(1).contains("Placement A") && problems.get(1).contains("Placement B"));

        var undeclared =
                new BackgroundProfiles.BackgroundProfile("q", null, null, null, null, null, Map.of(), Path.of("q"));
        assertTrue(BackgroundProfiles.setupProblems(undeclared, "single_h", "A").isEmpty(), "nothing declared");
        assertTrue(BackgroundProfiles.setupProblems(null, "single_h", "A").isEmpty());
    }
}
