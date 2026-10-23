package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/**
 * Background profiles: named sets of flat-field backgrounds, one per sample setup.
 *
 * <p>A flat-field background depends on more than the optics. The slide carrier (its height
 * in the light path and its glass), the slide source, the slide's orientation under the
 * camera and the mounting medium all change the pattern, so one background per
 * (detector, modality, magnification, white balance mode) is not enough. A profile is a
 * folder that holds a complete set of backgrounds for one such setup:
 *
 * <pre>
 * &lt;base&gt;/profiles/&lt;id&gt;/profile.yml
 * &lt;base&gt;/profiles/&lt;id&gt;/&lt;detector&gt;/&lt;modality family&gt;/&lt;magnification&gt;/[&lt;wb mode&gt; | &lt;acquisition profile&gt;]/...
 * </pre>
 *
 * <p>Everything below the profile folder is the layout {@link BackgroundSettingsReader}
 * resolves, unchanged. {@code <base>} is {@code background_correction.base_folder} in the
 * microscope's image-processing YAML. Backgrounds outside {@code profiles/} are not read:
 * without an active profile QPSC behaves as if no background had been collected.
 *
 * <p>The active profile is a QuPath-wide preference, because it describes what is on the
 * stage now, not a project. Every acquisition records the profile it used on the image entry
 * and in the stitch record.
 */
public final class BackgroundProfiles {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundProfiles.class);

    /** Folder under the base that holds one folder per profile. */
    public static final String PROFILES_DIR = "profiles";

    /** The profile's own record, in its folder. */
    public static final String PROFILE_FILE = "profile.yml";

    /** Profile ids are folder names: ASCII letters, digits, '-' and '_', 1 to 64 characters. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private BackgroundProfiles() {}

    /**
     * One background profile.
     *
     * @param id folder name under {@code profiles/}
     * @param name display name
     * @param description free text
     * @param createdAt ISO local date-time the profile was created
     * @param expectedInsert stage insert id (from {@code stage.inserts} in the microscope YAML)
     *     this profile's backgrounds were collected with; null when not declared. Checked at
     *     acquisition, warn only.
     * @param expectedSlideInsertion slide placement, {@code "A"} or {@code "B"}; null when not
     *     declared. Checked at acquisition, warn only.
     * @param attributes free-form facts about the setup (carrier, slide source, medium,
     *     coverslip...), recorded with every acquisition and never checked
     * @param folder the profile folder
     */
    public record BackgroundProfile(
            String id,
            String name,
            String description,
            String createdAt,
            String expectedInsert,
            String expectedSlideInsertion,
            Map<String, String> attributes,
            Path folder) {

        /** The name shown in dialogs: the display name, or the id when the name is blank. */
        public String displayName() {
            return name == null || name.isBlank() ? id : name;
        }
    }

    /** True for a string usable as a profile id (and folder name). */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** {@code <base>/profiles}, or null without a base. */
    public static Path profilesRoot(String baseFolder) {
        if (baseFolder == null || baseFolder.isBlank()) {
            return null;
        }
        return Path.of(baseFolder, PROFILES_DIR);
    }

    /** {@code <base>/profiles/<id>}, or null without a base or a valid id. Does not check existence. */
    public static Path profileFolder(String baseFolder, String id) {
        Path root = profilesRoot(baseFolder);
        if (root == null || !isValidId(id)) {
            return null;
        }
        return root.resolve(id);
    }

    /**
     * Every profile under the base, sorted by display name. A folder under {@code profiles/}
     * without a readable {@code profile.yml} is skipped with a warning. Never throws.
     */
    public static List<BackgroundProfile> list(String baseFolder) {
        List<BackgroundProfile> out = new ArrayList<>();
        Path root = profilesRoot(baseFolder);
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : dirs) {
                BackgroundProfile p = read(dir);
                if (p != null) {
                    out.add(p);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not list background profiles under {}: {}", root, e.toString());
        }
        out.sort(Comparator.comparing(BackgroundProfile::displayName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The profile with this id, if its folder and record exist. */
    public static Optional<BackgroundProfile> find(String baseFolder, String id) {
        Path folder = profileFolder(baseFolder, id);
        return folder == null ? Optional.empty() : Optional.ofNullable(read(folder));
    }

    /**
     * Reads a profile folder's record. Null when the folder has no {@code profile.yml}, it does
     * not parse, or its {@code id} does not match the folder name.
     */
    public static BackgroundProfile read(Path folder) {
        Path file = folder.resolve(PROFILE_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                logger.warn("Background profile record is not a mapping: {}", file);
                return null;
            }
            String folderName = folder.getFileName().toString();
            String id = str(map.get("id"));
            if (id == null) {
                id = folderName;
            } else if (!id.equals(folderName)) {
                logger.warn(
                        "Background profile {} declares id '{}' but sits in folder '{}'; skipped",
                        file,
                        id,
                        folderName);
                return null;
            }
            if (!isValidId(id)) {
                logger.warn("Background profile folder name '{}' is not a valid id; skipped", folderName);
                return null;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            if (map.get("attributes") instanceof Map<?, ?> attrs) {
                for (Map.Entry<?, ?> e : attrs.entrySet()) {
                    String v = str(e.getValue());
                    if (e.getKey() != null && v != null) {
                        attributes.put(String.valueOf(e.getKey()), v);
                    }
                }
            }
            String expectedInsert = null;
            String expectedInsertion = null;
            if (map.get("expects") instanceof Map<?, ?> expects) {
                expectedInsert = str(expects.get("insert"));
                expectedInsertion = normalizeInsertion(str(expects.get("slide_insertion")));
            }
            return new BackgroundProfile(
                    id,
                    str(map.get("name")),
                    str(map.get("description")),
                    str(map.get("created")),
                    expectedInsert,
                    expectedInsertion,
                    attributes,
                    folder);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read background profile {}: {}", file, e.toString());
            return null;
        }
    }

    /**
     * Creates a profile folder and its record.
     *
     * @throws IllegalArgumentException for an invalid id, or no base folder
     * @throws IOException when the folder already exists or cannot be written
     */
    public static BackgroundProfile create(
            String baseFolder,
            String id,
            String name,
            String description,
            String expectedInsert,
            String expectedSlideInsertion,
            Map<String, String> attributes)
            throws IOException {
        if (!isValidId(id)) {
            throw new IllegalArgumentException(
                    "A background profile id uses letters, digits, '-' and '_' only (1 to 64 characters): '" + id
                            + "'");
        }
        Path folder = profileFolder(baseFolder, id);
        if (folder == null) {
            throw new IllegalArgumentException("No background base folder is configured");
        }
        if (Files.exists(folder)) {
            throw new IOException("A background profile '" + id + "' already exists at " + folder);
        }
        Files.createDirectories(folder);
        BackgroundProfile profile = new BackgroundProfile(
                id,
                name,
                description,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                blankToNull(expectedInsert),
                normalizeInsertion(expectedSlideInsertion),
                attributes == null ? Map.of() : new LinkedHashMap<>(attributes),
                folder);
        write(profile);
        logger.info("Created background profile '{}' at {}", id, folder);
        return profile;
    }

    /** Writes (or rewrites) a profile's record in its folder. The folder must exist. */
    public static void write(BackgroundProfile profile) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", profile.id());
        if (profile.name() != null) {
            data.put("name", profile.name());
        }
        if (profile.description() != null) {
            data.put("description", profile.description());
        }
        if (profile.createdAt() != null) {
            data.put("created", profile.createdAt());
        }
        Map<String, Object> expects = new LinkedHashMap<>();
        if (profile.expectedInsert() != null) {
            expects.put("insert", profile.expectedInsert());
        }
        if (profile.expectedSlideInsertion() != null) {
            expects.put("slide_insertion", profile.expectedSlideInsertion());
        }
        if (!expects.isEmpty()) {
            data.put("expects", expects);
        }
        if (profile.attributes() != null && !profile.attributes().isEmpty()) {
            data.put("attributes", new LinkedHashMap<>(profile.attributes()));
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Path file = profile.folder().resolve(PROFILE_FILE);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# QPSC background profile: one set of flat-field backgrounds for one sample setup.\n");
            writer.write(
                    "# The folders beside this file hold the backgrounds, per detector, modality and objective.\n");
            new Yaml(options).dump(data, writer);
        }
    }

    /** Deletes a profile folder and everything in it. Clears the active profile if it was this one. */
    public static void delete(String baseFolder, String id) throws IOException {
        Path folder = profileFolder(baseFolder, id);
        if (folder == null || !Files.isDirectory(folder)) {
            return;
        }
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        if (id.equals(activeId())) {
            setActiveId(null);
        }
        logger.info("Deleted background profile '{}' at {}", id, folder);
    }

    // ---- the active profile --------------------------------------------------------------

    /** The active profile's id from the preference, or null when none is set. */
    public static String activeId() {
        return blankToNull(PersistentPreferences.getActiveBackgroundProfile());
    }

    /** Sets (or clears, with null) the active profile. */
    public static void setActiveId(String id) {
        PersistentPreferences.setActiveBackgroundProfile(id);
        logger.info("Active background profile: {}", id == null ? "none" : id);
    }

    /** The active profile, when the preference names one that exists under this base. */
    public static Optional<BackgroundProfile> active(String baseFolder) {
        String id = activeId();
        return id == null ? Optional.empty() : find(baseFolder, id);
    }

    /**
     * The folder backgrounds resolve under: the active profile's folder, as a path string for
     * {@link BackgroundSettingsReader}. Null when there is no base folder, no active profile, or
     * the active profile does not exist under this base. Callers treat null as "no backgrounds".
     */
    public static String activeFolder(String baseFolder) {
        Optional<BackgroundProfile> active = active(baseFolder);
        if (active.isEmpty()) {
            String id = activeId();
            if (id != null && baseFolder != null && !baseFolder.isBlank()) {
                logger.warn(
                        "Active background profile '{}' does not exist under {}; no backgrounds will be used",
                        id,
                        profilesRoot(baseFolder));
            }
            return null;
        }
        return active.get().folder().toString();
    }

    // ---- the old layout ------------------------------------------------------------------

    /**
     * Folders directly under the base that are not {@code profiles/}: background sets from
     * before profiles existed (one folder per detector). They are never read; the Background
     * Profiles dialog offers to move them into a profile.
     */
    public static List<Path> unprofiledFolders(String baseFolder) {
        List<Path> out = new ArrayList<>();
        if (baseFolder == null || baseFolder.isBlank()) {
            return out;
        }
        Path base = Path.of(baseFolder);
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base, Files::isDirectory)) {
            for (Path dir : dirs) {
                if (!PROFILES_DIR.equals(dir.getFileName().toString())) {
                    out.add(dir);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not scan {} for old background folders: {}", base, e.toString());
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /**
     * Moves old-layout folders (from {@link #unprofiledFolders}) into an existing profile. A
     * folder whose name already exists in the profile is left where it is and reported.
     *
     * @return the folders that were not moved because the profile already had them
     */
    public static List<Path> adopt(String baseFolder, String id, List<Path> folders) throws IOException {
        Path target = profileFolder(baseFolder, id);
        if (target == null || !Files.isDirectory(target)) {
            throw new IOException("Background profile '" + id + "' does not exist");
        }
        List<Path> skipped = new ArrayList<>();
        for (Path folder : folders) {
            Path dest = target.resolve(folder.getFileName());
            if (Files.exists(dest)) {
                skipped.add(folder);
                continue;
            }
            Files.move(folder, dest, StandardCopyOption.ATOMIC_MOVE);
            logger.info("Moved {} into background profile '{}'", folder, id);
        }
        return skipped;
    }

    // ---- the current setup, for the manifest and the checks --------------------------------

    /** The Stage Map's current insert id, or null when none is selected. */
    public static String currentInsert() {
        try {
            return blankToNull(PersistentPreferences.getStageMapInsert());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The slide placement recorded for the slide whose image is open in QuPath ({@code "A"} or
     * {@code "B"}), or null when none is recorded or no image is open.
     *
     * <p>Only a recorded value counts. {@code ImageMetadataManager.getSlideInsertion} answers A
     * for an entry without one, and stitched sub-images never get one, so the pre-acquisition
     * check told the operator "Slide placement now: A" for a slide set to B (PPM, 2026-10-22).
     * An entry without its own value takes its base image's, the entry the Stage Map's Slide
     * control writes to.
     */
    public static String currentSlideInsertion() {
        try {
            var gui = qupath.lib.gui.QuPathGUI.getInstance();
            if (gui == null || gui.getProject() == null || gui.getImageData() == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            var project = (qupath.lib.projects.Project<java.awt.image.BufferedImage>) gui.getProject();
            var entry = project.getEntry(gui.getImageData());
            return entry == null ? null : recordedSlideInsertion(entry, project.getImageList());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The placement recorded on {@code entry}, else on its base image among {@code entries}, else
     * null. Package-private for tests.
     */
    static String recordedSlideInsertion(
            qupath.lib.projects.ProjectImageEntry<?> entry,
            List<? extends qupath.lib.projects.ProjectImageEntry<?>> entries) {
        String own = blankToNull(entry.getMetadata().get(ImageMetadataManager.LP_SLIDE_INSERTION));
        if (own != null) {
            return own;
        }
        String baseImage = blankToNull(ImageMetadataManager.getBaseImage(entry));
        if (baseImage == null || entries == null) {
            return null;
        }
        for (var e : entries) {
            if (e == entry || ImageMetadataManager.isCameraView(e)) {
                continue;
            }
            String name = e.getImageName();
            if (baseImage.equals(name) || baseImage.equals(qupath.lib.common.GeneralTools.stripExtension(name))) {
                return blankToNull(e.getMetadata().get(ImageMetadataManager.LP_SLIDE_INSERTION));
            }
        }
        return null;
    }

    /**
     * The scanner the image open in QuPath came from ({@code source_microscope}), or null when
     * no image is open or it does not record one.
     */
    public static String currentSourceScanner() {
        try {
            var gui = qupath.lib.gui.QuPathGUI.getInstance();
            if (gui == null || gui.getProject() == null || gui.getImageData() == null) {
                return null;
            }
            var entry = gui.getProject().getEntry(gui.getImageData());
            return entry == null ? null : blankToNull(ImageMetadataManager.getSourceMicroscope(entry));
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * What differs between a profile's declared setup and the current one. Empty when nothing
     * is declared, nothing is known, or everything matches. Each line is one difference, ready
     * for a warning dialog.
     */
    public static List<String> setupProblems(BackgroundProfile profile, String currentInsert, String currentInsertion) {
        List<String> problems = new ArrayList<>();
        if (profile == null) {
            return problems;
        }
        if (profile.expectedInsert() != null
                && currentInsert != null
                && !profile.expectedInsert().equalsIgnoreCase(currentInsert)) {
            problems.add("Stage insert now: " + currentInsert + "; the profile's backgrounds were collected with: "
                    + profile.expectedInsert());
        }
        String expected = profile.expectedSlideInsertion();
        String current = normalizeInsertion(currentInsertion);
        if (expected != null && current != null && !expected.equals(current)) {
            problems.add(LightPathModel.PLACEMENT_CAPTION + " now: " + LightPathModel.placementLabel(current)
                    + "; the profile expects: " + LightPathModel.placementLabel(expected));
        }
        return problems;
    }

    /** "A"/"B" in any case to "A"/"B"; anything else (including blank) to null. */
    static String normalizeInsertion(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().toUpperCase();
        return "A".equals(t) || "B".equals(t) ? t : null;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
