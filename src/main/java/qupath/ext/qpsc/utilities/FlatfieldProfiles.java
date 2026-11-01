package qupath.ext.qpsc.utilities;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /** @return one line saying what a folder covers, for a dialog or the wizard */
    public static String describe(String folder) {
        List<String> covered = covered(folder);
        return covered.isEmpty()
                ? "No BaSiC profile in this folder. Fit one with Utilities > Image Quality."
                : "BaSiC profile covers " + String.join(", ", covered);
    }
}
