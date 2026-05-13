package qupath.ext.qpsc.modality.widefield;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import qupath.ext.qpsc.preferences.PersistentPreferences;

/**
 * Persistence + serialization for named widefield channel presets, shared by:
 *
 * <ul>
 *   <li>{@code WidefieldChannelBoundingBoxUI} -- the bounded-acquisition dialog's
 *       Modality-Specific Options panel; the preset captures which channels
 *       are checked plus per-channel exposure / intensity / focus state.</li>
 *   <li>{@code StageControlPanel.buildFluorescenceCameraContent} -- the Live
 *       Viewer's Camera tab; the preset captures per-channel spinner values
 *       and the active preview radio.</li>
 * </ul>
 *
 * <p>Both UIs read and write the same {@code widefield.channel.preset.*} keys
 * so saving in one place makes the preset available in the other.
 *
 * <p>Serialization is a single pipe-delimited blob per preset:
 * <pre>v1|focus=&lt;id&gt;|&lt;chId&gt;=&lt;sel&gt;:&lt;exp&gt;:&lt;int&gt;|...</pre>
 * where {@code sel} is {@code true|false}, {@code exp} is the exposure in ms,
 * and {@code int} is the intensity value. Empty fields (e.g. no intensity
 * spinner for that channel) round-trip as {@code true:50:} or {@code true::}.
 */
public final class WidefieldChannelPresetStore {

    public static final int MAX_NAME_LENGTH = 40;

    private static final String PREF_KEY_PREFIX = "widefield.channel.";
    private static final String PREF_KEY_PRESET_NAMES = PREF_KEY_PREFIX + "preset.names";
    private static final String PREF_KEY_PRESET_DATA = PREF_KEY_PREFIX + "preset.";
    private static final String PREF_KEY_LAST_PRESET = PREF_KEY_PREFIX + "preset.last";
    private static final String FORMAT_VERSION = "v1";

    private WidefieldChannelPresetStore() {}

    /** Per-channel state captured in a preset. {@code intensity} may be null for channels with no intensity property. */
    public record ChannelState(boolean selected, Double exposureMs, Double intensity) {}

    /** Decoded preset: focus-channel id (may be null) plus an ordered per-channel-id state map. */
    public record DecodedPreset(String focusId, Map<String, ChannelState> states) {}

    /**
     * Validate a user-typed preset name. Returns null when OK, otherwise a
     * user-displayable reason. Rules: non-blank, no TAB or '|' (used as
     * delimiters internally), and {@code <=} {@link #MAX_NAME_LENGTH} chars.
     */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) return "Preset name cannot be empty.";
        if (name.contains("\t") || name.contains("|")) {
            return "Preset name cannot contain TAB or '|' characters.";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "Preset name too long (max " + MAX_NAME_LENGTH + " characters).";
        }
        return null;
    }

    /** Returns the saved preset names in insertion order. */
    public static List<String> loadNames() {
        String raw = PersistentPreferences.getStringPreference(PREF_KEY_PRESET_NAMES, "");
        if (raw.isEmpty()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\t")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static void persistNames(List<String> names) {
        PersistentPreferences.setStringPreference(PREF_KEY_PRESET_NAMES, String.join("\t", names));
    }

    public static String getLastPresetName() {
        return PersistentPreferences.getStringPreference(PREF_KEY_LAST_PRESET, "");
    }

    public static void setLastPresetName(String name) {
        PersistentPreferences.setStringPreference(PREF_KEY_LAST_PRESET, name);
    }

    /** Add (or overwrite) a preset. {@code states} is keyed by channel id in display order. */
    public static void savePreset(String name, String focusId, Map<String, ChannelState> states) {
        String blob = encode(focusId, states);
        PersistentPreferences.setStringPreference(PREF_KEY_PRESET_DATA + safeKey(name), blob);
        List<String> names = loadNames();
        if (!names.contains(name)) {
            names.add(name);
            persistNames(names);
        }
        setLastPresetName(name);
    }

    /** Returns the decoded preset, or null if no preset with that name exists. */
    public static DecodedPreset loadPreset(String name) {
        if (name == null || name.isEmpty()) return null;
        String blob = PersistentPreferences.getStringPreference(PREF_KEY_PRESET_DATA + safeKey(name), null);
        if (blob == null || blob.isEmpty()) return null;
        return decode(blob);
    }

    /** Remove a preset. Cleans up the names index and the last-used pointer if it matches. */
    public static void deletePreset(String name) {
        if (name == null || name.isEmpty()) return;
        List<String> names = loadNames();
        names.remove(name);
        persistNames(names);
        PersistentPreferences.setStringPreference(PREF_KEY_PRESET_DATA + safeKey(name), null);
        if (name.equals(getLastPresetName())) {
            PersistentPreferences.setStringPreference(PREF_KEY_LAST_PRESET, null);
        }
    }

    /** Lowercase + non-alphanumerics -> '_'. Names are validated to <=40 chars so the key stays under Prefs' 80-char limit. */
    public static String safeKey(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    static String encode(String focusId, Map<String, ChannelState> states) {
        StringBuilder sb = new StringBuilder(FORMAT_VERSION);
        if (focusId != null && !focusId.isEmpty()) {
            sb.append("|focus=").append(focusId);
        }
        for (Map.Entry<String, ChannelState> entry : states.entrySet()) {
            ChannelState s = entry.getValue();
            sb.append("|")
                    .append(entry.getKey())
                    .append("=")
                    .append(s.selected())
                    .append(":");
            if (s.exposureMs() != null) sb.append(s.exposureMs());
            sb.append(":");
            if (s.intensity() != null) sb.append(s.intensity());
        }
        return sb.toString();
    }

    static DecodedPreset decode(String blob) {
        if (blob == null || blob.isEmpty()) return null;
        String[] parts = blob.split("\\|", -1);
        if (parts.length < 1 || !FORMAT_VERSION.equals(parts[0])) return null;
        String focusId = null;
        Map<String, ChannelState> states = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("focus=")) {
                focusId = part.substring("focus=".length());
                continue;
            }
            int eq = part.indexOf('=');
            if (eq < 1) continue;
            String chId = part.substring(0, eq);
            String[] fields = part.substring(eq + 1).split(":", -1);
            boolean selected = fields.length > 0 && Boolean.parseBoolean(fields[0]);
            Double exposure = (fields.length > 1) ? parseDoubleOrNull(fields[1]) : null;
            Double intensity = (fields.length > 2) ? parseDoubleOrNull(fields[2]) : null;
            states.put(chId, new ChannelState(selected, exposure, intensity));
        }
        return new DecodedPreset(focusId, states);
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
