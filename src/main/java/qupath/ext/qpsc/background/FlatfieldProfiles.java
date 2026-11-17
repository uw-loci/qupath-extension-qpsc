package qupath.ext.qpsc.background;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * What a folder of flat-field profiles covers. A profile directly in the folder covers every image;
 * {@code <folder>/<channel id>/} covers one channel, which is how the server looks them up.
 */
public final class FlatfieldProfiles {

    private static final String PROFILE_FILE = "profile.yml";

    /** Covers every image: the name {@link #covered} reports for a profile directly in the folder. */
    public static final String EVERY_IMAGE = "every image";

    private FlatfieldProfiles() {}

    /**
     * @param folder the folder; may be null or blank
     * @return {@link #EVERY_IMAGE} and any channel ids with a profile, empty when there is none
     */
    public static List<String> covered(String folder) {
        List<String> covered = new ArrayList<>();
        if (folder == null || folder.isBlank()) {
            return covered;
        }
        File root = new File(folder.trim());
        if (new File(root, PROFILE_FILE).isFile()) {
            covered.add(EVERY_IMAGE);
        }
        File[] children = root.listFiles(File::isDirectory);
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) {
                if (new File(child, PROFILE_FILE).isFile()) {
                    covered.add(child.getName());
                }
            }
        }
        return covered;
    }

    /**
     * Whether a single-position Z-stack or time-lapse can use this folder: those paths have no
     * channel loop, so subfolders do not apply. A folder this machine cannot read gets the benefit
     * of the doubt, since the server reads it.
     */
    public static boolean coversSinglePosition(String folder) {
        List<String> covered = covered(folder);
        return covered.isEmpty() || covered.contains(EVERY_IMAGE);
    }

    /**
     * Where a fitted profile's recorded hardware differs from a run's. The fit records the
     * {@code objective} and {@code detector} the tiles were acquired with in each
     * {@code profile.yml} (directly in the folder, or one per channel subfolder). A profile
     * that recorded none, or a run value that is null, is not compared. Never throws.
     *
     * @param folder the profile folder; may be null
     * @param objective the run's objective, or null
     * @param detector the run's detector, or null
     * @return one plain sentence per difference, empty when everything matches or nothing is known
     */
    public static List<String> hardwareMismatches(String folder, String objective, String detector) {
        List<String> out = new ArrayList<>();
        if (folder == null || folder.isBlank()) {
            return out;
        }
        File root = new File(folder.trim());
        List<File> records = new ArrayList<>();
        records.add(new File(root, PROFILE_FILE));
        File[] children = root.listFiles(File::isDirectory);
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) {
                records.add(new File(child, PROFILE_FILE));
            }
        }
        for (File record : records) {
            if (!record.isFile()) {
                continue;
            }
            Map<?, ?> data = readRecord(record);
            String name = record.getParentFile().equals(root)
                    ? "The BaSiC profile"
                    : "The BaSiC profile for " + record.getParentFile().getName();
            compare(out, name, "objective", data.get("objective"), objective);
            compare(out, name, "detector", data.get("detector"), detector);
        }
        return out;
    }

    private static void compare(List<String> out, String name, String what, Object recorded, String run) {
        if (recorded == null || recorded.toString().isBlank() || run == null || run.isBlank()) {
            return;
        }
        if (!recorded.toString().equals(run)) {
            out.add(name + " was fitted with " + what + " " + recorded + "; this run uses " + run + ".");
        }
    }

    private static Map<?, ?> readRecord(File record) {
        try (java.io.Reader in =
                java.nio.file.Files.newBufferedReader(record.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            Object data = new org.yaml.snakeyaml.Yaml().load(in);
            return data instanceof Map<?, ?> map ? map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Which of the selected channels no profile in the folder covers. A profile directly in the
     * folder covers every channel; otherwise a channel is covered by a subfolder named after its id.
     *
     * @param folder the profile folder; may be null
     * @param channelIds the channel ids selected for the run
     * @return the selected ids with no profile, in the order given; empty when all are covered
     */
    public static List<String> uncoveredChannels(String folder, List<String> channelIds) {
        List<String> covered = covered(folder);
        List<String> out = new ArrayList<>();
        if (covered.contains(EVERY_IMAGE) || channelIds == null) {
            return out;
        }
        for (String id : channelIds) {
            if (!covered.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** @return one line saying what a folder covers, for a dialog or the wizard */
    public static String describe(String folder) {
        List<String> covered = covered(folder);
        return covered.isEmpty()
                ? "No BaSiC profile in this folder. Fit one with Utilities > Image Quality."
                : "BaSiC profile covers " + String.join(", ", covered);
    }
}
