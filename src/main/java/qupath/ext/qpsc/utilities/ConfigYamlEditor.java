package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Targeted, comment-preserving line-based edits to the microscope YAML.
 *
 * <p>SnakeYAML round-trips would be simpler but lose every comment in the
 * file -- including the DLED Enable/State documentation block and the
 * cross-modality hygiene notes that future maintainers (human or LLM) rely
 * on. Each operation here finds the exact line by walking indentation
 * blocks and rewrites just that one value, so the rest of the file is
 * byte-identical.
 *
 * <p>Two write surfaces are exposed today:
 * <ul>
 *   <li>{@link #setProfileScalar} -- updates a scalar field directly under
 *       {@code acquisition_profiles.<profileKey>}, e.g.
 *       {@code illumination_intensity}.</li>
 *   <li>{@link #setChannelExposureMs} and {@link #setChannelDevicePropertyValue}
 *       -- update one of the per-channel fields under
 *       {@code modalities.<modalityKey>.channels[id=<channelId>]}.</li>
 * </ul>
 *
 * <p>Operations are idempotent and report whether the file actually changed
 * so the caller can avoid redundant RECONFG round-trips.
 */
public final class ConfigYamlEditor {

    private static final Logger logger = LoggerFactory.getLogger(ConfigYamlEditor.class);

    private ConfigYamlEditor() {}

    /** Outcome of a single edit. */
    public static final class Result {
        public final boolean changed;
        public final String message;
        public Result(boolean changed, String message) {
            this.changed = changed;
            this.message = message;
        }
    }

    /**
     * Set or insert a scalar key directly under
     * {@code acquisition_profiles.<profileKey>}. Numbers are written without
     * a trailing ".0" when integer-valued so the file matches the human
     * style already in use ({@code illumination_intensity: 700}, not
     * {@code 700.0}).
     */
    public static Result setProfileScalar(
            Path configPath, String profileKey, String fieldName, double value) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
        int profilesAt = findTopLevelKey(lines, "acquisition_profiles");
        if (profilesAt < 0) {
            return new Result(false, "acquisition_profiles section not found");
        }
        String parentIndent = leadingSpaces(lines.get(profilesAt));
        int childIndentSize = parentIndent.length() + 2; // assume 2-space indent

        int profileLine = findChildKey(lines, profilesAt + 1, childIndentSize, profileKey);
        if (profileLine < 0) {
            return new Result(false, "Profile '" + profileKey + "' not found under acquisition_profiles");
        }
        int profileEnd = findBlockEnd(lines, profileLine + 1, childIndentSize);
        int fieldIndentSize = childIndentSize + 2;
        String formatted = formatScalar(value);

        for (int i = profileLine + 1; i < profileEnd; i++) {
            String line = lines.get(i);
            if (matchesKey(line, fieldIndentSize, fieldName)) {
                String existingValue = scalarValueOf(line);
                String newLine = repeat(' ', fieldIndentSize) + fieldName + ": " + formatted;
                if (existingValue != null && existingValue.equals(formatted)) {
                    return new Result(false, "No change (already " + formatted + ")");
                }
                lines.set(i, newLine);
                Files.write(configPath, lines, StandardCharsets.UTF_8);
                logger.info("ConfigYamlEditor: {}.{} {} -> {}", profileKey, fieldName, existingValue, formatted);
                return new Result(true, profileKey + "." + fieldName + " -> " + formatted);
            }
        }
        // Field absent -- insert at the end of the profile block.
        String newLine = repeat(' ', fieldIndentSize) + fieldName + ": " + formatted;
        lines.add(profileEnd, newLine);
        Files.write(configPath, lines, StandardCharsets.UTF_8);
        logger.info("ConfigYamlEditor: inserted {}.{}: {}", profileKey, fieldName, formatted);
        return new Result(true, profileKey + "." + fieldName + " -> " + formatted + " (new)");
    }

    /**
     * Update {@code exposure_ms} on a single channel under
     * {@code modalities.<modalityKey>.channels}. The channel must already
     * exist; this method does not create channels.
     */
    public static Result setChannelExposureMs(
            Path configPath, String modalityKey, String channelId, double exposureMs) throws IOException {
        return setChannelScalar(configPath, modalityKey, channelId, "exposure_ms", exposureMs);
    }

    /**
     * Update the {@code value} of the {@code device_properties} entry on a
     * channel where {@code device} and {@code property} match. Used to
     * persist a fluorescence channel's intensity_property tuning.
     *
     * <p>If no matching device_property entry exists, returns a no-op
     * Result -- we don't add new device_properties because their order
     * matters for hardware-state ordering and only the YAML author should
     * decide where they go.
     */
    public static Result setChannelDevicePropertyValue(
            Path configPath,
            String modalityKey,
            String channelId,
            String device,
            String property,
            String valueStr)
            throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
        int[] channelRange = locateChannelBlock(lines, modalityKey, channelId);
        if (channelRange == null) {
            return new Result(false, "Channel '" + channelId + "' not found under modalities." + modalityKey);
        }
        int chStart = channelRange[0];
        int chEnd = channelRange[1];

        // Find device_properties: header inside the channel block.
        int dpHeader = -1;
        int channelFieldIndent = leadingSpaces(lines.get(chStart)).length() + 2;
        for (int i = chStart + 1; i < chEnd; i++) {
            if (matchesKey(lines.get(i), channelFieldIndent, "device_properties")) {
                dpHeader = i;
                break;
            }
        }
        if (dpHeader < 0) {
            return new Result(false, "device_properties not declared on channel '" + channelId + "'");
        }
        // Like the channels list, device_properties items align at
        // channelFieldIndent in compact YAML. Scan forward to the first
        // non-list line at <= channelFieldIndent (a sibling field of the
        // channel) or to chEnd, whichever comes first.
        int dpEnd = chEnd;
        for (int i = dpHeader + 1; i < chEnd; i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            int lead = leadingSpaces(line).length();
            if (lead < channelFieldIndent) { dpEnd = i; break; }
            if (lead == channelFieldIndent && !line.trim().startsWith("-")) { dpEnd = i; break; }
        }

        // Walk list-item blocks (each starts with '- device:' at indent
        // channelFieldIndent). For each, capture device+property and find
        // the value line.
        Pattern listItemStart = Pattern.compile("^(\\s*)-\\s*device:\\s*(.*\\S)\\s*$");
        for (int i = dpHeader + 1; i < dpEnd; i++) {
            Matcher m = listItemStart.matcher(lines.get(i));
            if (!m.matches()) continue;
            String itemIndent = m.group(1);
            String devName = stripQuotes(m.group(2));
            // Inside this list item, gather subsequent lines whose indent is
            // greater than itemIndent.length() but not part of the next item.
            int itemEnd = i + 1;
            int contIndent = itemIndent.length() + 2;
            String propVal = null;
            int valueLine = -1;
            while (itemEnd < dpEnd) {
                String l = lines.get(itemEnd);
                if (l.isBlank()) { itemEnd++; continue; }
                int lead = leadingSpaces(l).length();
                if (lead < contIndent) break;
                Matcher pm = Pattern.compile("^\\s*property:\\s*(.*\\S)\\s*$").matcher(l);
                Matcher vm = Pattern.compile("^\\s*value:\\s*(.*?)\\s*$").matcher(l);
                if (pm.matches()) propVal = stripQuotes(pm.group(1));
                else if (vm.matches()) valueLine = itemEnd;
                itemEnd++;
            }
            if (devName.equals(device) && property.equals(propVal) && valueLine >= 0) {
                String existing = scalarValueOf(lines.get(valueLine));
                String formatted = formatScalarString(valueStr);
                if (existing != null && existing.equals(formatted)) {
                    return new Result(false, "No change (already " + formatted + ")");
                }
                String pad = repeat(' ', contIndent);
                lines.set(valueLine, pad + "value: " + formatted);
                Files.write(configPath, lines, StandardCharsets.UTF_8);
                logger.info(
                        "ConfigYamlEditor: modalities.{}.channels[{}].device_properties[{}.{}].value {} -> {}",
                        modalityKey, channelId, device, property, existing, formatted);
                return new Result(true, device + "." + property + " -> " + formatted);
            }
            i = itemEnd - 1; // continue scanning the next list item
        }
        return new Result(false,
                "device_properties entry " + device + "." + property + " not found on channel '" + channelId + "'");
    }

    // ---------- internals ----------

    private static Result setChannelScalar(
            Path configPath, String modalityKey, String channelId, String fieldName, double value)
            throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
        int[] channelRange = locateChannelBlock(lines, modalityKey, channelId);
        if (channelRange == null) {
            return new Result(false, "Channel '" + channelId + "' not found under modalities." + modalityKey);
        }
        int chStart = channelRange[0];
        int chEnd = channelRange[1];
        int fieldIndent = leadingSpaces(lines.get(chStart)).length() + 2;
        String formatted = formatScalar(value);
        for (int i = chStart + 1; i < chEnd; i++) {
            if (matchesKey(lines.get(i), fieldIndent, fieldName)) {
                String existing = scalarValueOf(lines.get(i));
                if (existing != null && existing.equals(formatted)) {
                    return new Result(false, "No change (already " + formatted + ")");
                }
                lines.set(i, repeat(' ', fieldIndent) + fieldName + ": " + formatted);
                Files.write(configPath, lines, StandardCharsets.UTF_8);
                logger.info("ConfigYamlEditor: modalities.{}.channels[{}].{} {} -> {}",
                        modalityKey, channelId, fieldName, existing, formatted);
                return new Result(true, channelId + "." + fieldName + " -> " + formatted);
            }
        }
        return new Result(false, fieldName + " not declared on channel '" + channelId + "'");
    }

    /**
     * Return [startLine, endLine] of a channel list-item block, or null if
     * the channel doesn't exist. startLine is the {@code - id: <channelId>}
     * line; endLine is the first line at or below the list-item indent.
     */
    private static int[] locateChannelBlock(List<String> lines, String modalityKey, String channelId) {
        int modalitiesAt = findTopLevelKey(lines, "modalities");
        if (modalitiesAt < 0) return null;
        int parentIndent = leadingSpaces(lines.get(modalitiesAt)).length();
        int modIndent = parentIndent + 2;
        int modKeyLine = findChildKey(lines, modalitiesAt + 1, modIndent, modalityKey);
        if (modKeyLine < 0) return null;
        int modEnd = findBlockEnd(lines, modKeyLine + 1, modIndent);
        int modFieldIndent = modIndent + 2;
        int channelsHeader = -1;
        for (int i = modKeyLine + 1; i < modEnd; i++) {
            if (matchesKey(lines.get(i), modFieldIndent, "channels")) {
                channelsHeader = i;
                break;
            }
        }
        if (channelsHeader < 0) return null;
        // Channels-list scan: items can sit at modFieldIndent (compact YAML
        // where '- id:' aligns with 'channels:') OR at modFieldIndent + 2
        // (nested style). Sniff the first '- id:' line to lock the indent,
        // then walk until we hit a non-list line at <= that indent (the
        // next sibling field of the modality or the next modality).
        int listItemIndent = -1;
        Pattern itemPat = Pattern.compile("^(\\s*)-\\s*id:\\s*(\\S+).*$");
        Pattern anyKeyPat = Pattern.compile("^(\\s*)\\S.*$");
        for (int i = channelsHeader + 1; i < modEnd; i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            Matcher m = itemPat.matcher(line);
            if (m.matches()) {
                if (listItemIndent < 0) listItemIndent = m.group(1).length();
                if (m.group(1).length() != listItemIndent) continue;
                String thisId = stripQuotes(m.group(2));
                if (thisId.equals(channelId)) {
                    int chEnd = i + 1;
                    while (chEnd < modEnd) {
                        String l = lines.get(chEnd);
                        if (l.isBlank()) { chEnd++; continue; }
                        int lead = leadingSpaces(l).length();
                        if (lead < listItemIndent) break;             // back to modality-sibling field
                        if (lead == listItemIndent && !l.trim().startsWith("-")) break; // next sibling at same indent
                        if (lead == listItemIndent && l.trim().startsWith("- ")) break; // next list item
                        chEnd++;
                    }
                    return new int[] {i, chEnd};
                }
                continue;
            }
            // A non-list line at indent <= listItemIndent (or, before
            // listItemIndent is locked in, at indent <= modFieldIndent) is
            // a sibling field -- channels list has ended.
            Matcher km = anyKeyPat.matcher(line);
            if (km.matches()) {
                int lead = km.group(1).length();
                int boundary = (listItemIndent >= 0) ? listItemIndent : modFieldIndent;
                if (lead <= boundary) break;
            }
        }
        return null;
    }

    private static int findTopLevelKey(List<String> lines, String key) {
        Pattern p = Pattern.compile("^" + Pattern.quote(key) + ":\\s*(#.*)?$");
        for (int i = 0; i < lines.size(); i++) {
            if (p.matcher(lines.get(i)).matches()) return i;
        }
        return -1;
    }

    private static int findChildKey(List<String> lines, int from, int indent, String keyName) {
        Pattern p = Pattern.compile("^" + repeat(' ', indent) + Pattern.quote(keyName) + ":\\s*(#.*)?$");
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            int lead = leadingSpaces(line).length();
            if (lead < indent) return -1; // walked out of the parent block
            if (p.matcher(line).matches()) return i;
        }
        return -1;
    }

    /**
     * Return the first line index >= from whose non-blank content has indent
     * less than or equal to {@code parentIndent} (i.e. the start of the
     * parent's sibling block). lines.size() if we hit EOF.
     */
    private static int findBlockEnd(List<String> lines, int from, int parentIndent) {
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            int lead = leadingSpaces(line).length();
            if (lead <= parentIndent) return i;
        }
        return lines.size();
    }

    private static boolean matchesKey(String line, int indent, String keyName) {
        return Pattern.compile("^" + repeat(' ', indent) + Pattern.quote(keyName) + ":\\s*(.*)?$")
                .matcher(line)
                .matches();
    }

    private static String scalarValueOf(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return null;
        String tail = line.substring(colon + 1).trim();
        // strip trailing comment
        int hash = indexOfUnquoted(tail, '#');
        if (hash >= 0) tail = tail.substring(0, hash).trim();
        return stripQuotes(tail);
    }

    private static int indexOfUnquoted(String s, char c) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'' && !inDouble) inSingle = !inSingle;
            else if (ch == '"' && !inSingle) inDouble = !inDouble;
            else if (ch == c && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static String leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return line.substring(0, i);
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] buf = new char[n];
        java.util.Arrays.fill(buf, c);
        return new String(buf);
    }

    /** Format a numeric value for YAML. Drops ".0" on integer-valued doubles. */
    private static String formatScalar(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /**
     * Format a string value for YAML. Mostly identity, but quotes the value
     * when it would otherwise be parsed as a special token (On/Off/yes/no/
     * true/false) since those are YAML 1.1 booleans -- writing them bare
     * round-trips to a Java Boolean and the MM driver then chokes.
     */
    private static String formatScalarString(String s) {
        if (s == null) return "''";
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return "''";
        switch (trimmed.toLowerCase()) {
            case "on": case "off":
            case "yes": case "no":
            case "true": case "false":
            case "y": case "n":
            case "null": case "~":
                return "'" + trimmed + "'";
            default:
        }
        return trimmed;
    }
}
