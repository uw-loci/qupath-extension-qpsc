package qupath.ext.qpsc.utilities;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Suggested values and input checks for the New/Edit Background Profile dialog.
 *
 * <p>A profile describes the sample setup on the stage, so the suggestion is built from what
 * QPSC already knows about it: the Stage Map's insert, the slide placement of the open image
 * (else the scope's default), and the scanner the open image came from. No QuPath state is read
 * here; the dialog gathers the {@link Setup} and this class turns it into text, so it is
 * testable without a GUI.
 */
public final class BackgroundProfileDefaults {

    private BackgroundProfileDefaults() {}

    /**
     * What is known about the setup on the stage. Any field may be null.
     *
     * @param insertId the Stage Map insert id, e.g. {@code single_h}
     * @param insertName that insert's display name, e.g. "Single slide (horizontal)"
     * @param placement slide placement, {@code A} or {@code B}
     * @param placementFromImage true when {@code placement} came from the open image, false when
     *     it is the scope's default
     * @param sourceScanner the scanner the open image came from ({@code source_microscope})
     */
    public record Setup(
            String insertId, String insertName, String placement, boolean placementFromImage, String sourceScanner) {}

    /** "Single slide (horizontal), label left, from Aperio GT450", or null when nothing is known. */
    public static String suggestName(Setup s) {
        List<String> parts = new ArrayList<>();
        String insert = firstNonBlank(s.insertName(), s.insertId());
        if (insert != null) {
            parts.add(insert);
        }
        if ("A".equals(s.placement())) {
            parts.add("label left");
        } else if ("B".equals(s.placement())) {
            parts.add("label right");
        }
        if (notBlank(s.sourceScanner())) {
            parts.add("from " + s.sourceScanner().trim());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * An id for {@code name}: lower case, words joined by '_', only the characters
     * {@link BackgroundProfiles#isValidId} accepts, made unique against {@code existingIds} with a
     * numeric suffix. "profile" when the name has nothing usable.
     */
    public static String suggestId(String name, Collection<String> existingIds) {
        String base = slug(name);
        if (base.isEmpty()) {
            base = "profile";
        }
        String id = base;
        for (int n = 2; contains(existingIds, id); n++) {
            String suffix = "_" + n;
            id = base.substring(0, Math.min(base.length(), 64 - suffix.length())) + suffix;
        }
        return id;
    }

    /** Lower-case ASCII words joined by '_', at most 64 characters, no leading or trailing '_'. */
    public static String slug(String text) {
        if (text == null) {
            return "";
        }
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "");
        String s = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[_-]+|[_-]+$", "");
        if (s.length() > 64) {
            s = s.substring(0, 64).replaceAll("[_-]+$", "");
        }
        return s;
    }

    /** "Backgrounds for ...; slide label left (from the open image)." or null when nothing is known. */
    public static String suggestDescription(Setup s) {
        List<String> parts = new ArrayList<>();
        String insert = firstNonBlank(s.insertName(), s.insertId());
        if (insert != null) {
            parts.add("Backgrounds collected on the " + insert + " insert");
        }
        if ("A".equals(s.placement()) || "B".equals(s.placement())) {
            parts.add("slide label " + ("A".equals(s.placement()) ? "left" : "right")
                    + (s.placementFromImage() ? " (as the open image)" : " (the scope's default)"));
        }
        if (notBlank(s.sourceScanner())) {
            parts.add("slides scanned on " + s.sourceScanner().trim());
        }
        if (parts.isEmpty()) {
            return null;
        }
        String text = String.join("; ", parts) + ".";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * Starting attributes: {@code slide_source} when the open image names its scanner, and an
     * empty {@code medium} line to fill in. Lines without a value are not saved.
     */
    public static Map<String, String> suggestAttributes(Setup s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (notBlank(s.sourceScanner())) {
            out.put("slide_source", s.sourceScanner().trim());
        }
        out.put("medium", "");
        return out;
    }

    /**
     * Why {@code id} cannot be used for a new profile, or null when it can. Names the offending
     * characters so the operator can fix it without guessing the rule.
     */
    public static String idProblem(String id, Collection<String> existingIds) {
        if (id == null || id.isBlank()) {
            return "Enter an id (it becomes the profile's folder name).";
        }
        if (id.length() > 64) {
            return "The id is " + id.length() + " characters; at most 64.";
        }
        if (!Character.isLetterOrDigit(id.charAt(0)) || id.charAt(0) > 127) {
            return "The id must start with a letter or digit.";
        }
        StringBuilder bad = new StringBuilder();
        for (char c : id.toCharArray()) {
            boolean ok = c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_');
            if (!ok && bad.indexOf(String.valueOf(c)) < 0) {
                bad.append(c == ' ' ? "space" : "'" + c + "'").append(' ');
            }
        }
        if (bad.length() > 0) {
            return "The id cannot contain " + bad.toString().trim().replace(" ", ", ")
                    + ". Use letters, digits, '-' and '_' (suggested: " + slug(id) + ").";
        }
        if (contains(existingIds, id)) {
            return "A profile with the id '" + id + "' already exists.";
        }
        return null;
    }

    /**
     * Lines of the attributes text that will not be saved, as warnings: lines without
     * "key: value" and keys given twice. Blank lines and lines with an empty value (a template to
     * fill in) are not reported.
     */
    public static List<String> attributeWarnings(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String[] lines = text.split("\\R");
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                out.add("Attribute line " + (i + 1) + " is not 'key: value' and will not be saved.");
                continue;
            }
            String key = line.substring(0, colon).trim();
            if (keys.contains(key)) {
                out.add("Attribute '" + key + "' is given twice; the last value is saved.");
            }
            keys.add(key);
        }
        return out;
    }

    /** The attributes as editable text, one "key: value" per line. */
    public static String attributesText(Map<String, String> attributes) {
        StringBuilder sb = new StringBuilder();
        attributes.forEach(
                (k, v) -> sb.append(k).append(": ").append(v == null ? "" : v).append('\n'));
        return sb.toString();
    }

    private static boolean contains(Collection<String> ids, String id) {
        if (ids == null) {
            return false;
        }
        for (String existing : ids) {
            if (existing != null && existing.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return notBlank(a) ? a.trim() : notBlank(b) ? b.trim() : null;
    }
}
