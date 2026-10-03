package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Saves {@code autofocus_<scope>.yml} by changing only what actually changed.
 *
 * <p>The Autofocus Editor used to save by building a fresh document from a fixed list of
 * keys and dumping it over the file. That silently destroyed two things the editor never
 * knew about:
 *
 * <ul>
 *   <li><b>Keys outside its model.</b> {@code channel_reduction: green} on PPM 20x is read
 *       by the server and has no field in the editor's {@code AutofocusSettings}, so a save
 *       dropped it and reverted a shipped fix -- with no error and nothing in the log.</li>
 *   <li><b>Every comment in the file.</b> On PPM that is 42 comment lines inside
 *       {@code autofocus_settings} recording why {@code brenner_gradient} beat
 *       {@code p98_p2} after two reversals, plus the {@code stained_colour} strategy
 *       block explaining why a texture tissue gate let a focus land 200 um off the
 *       sample. That rationale is the reason nobody re-litigates those settings.</li>
 * </ul>
 *
 * <p>So this writer edits in place instead of re-rendering:
 *
 * <ul>
 *   <li>{@code autofocus_settings} entries are matched by {@code objective} and updated
 *       one scalar line at a time. A key the editor does not model is never visited, so it
 *       survives by construction -- which is the property that matters: the next unmodelled
 *       key somebody adds to the YAML is protected without anyone remembering to protect
 *       it.</li>
 *   <li>{@code strategies} and {@code modalities} hold nested maps and lists, so they are
 *       compared against the file and re-rendered <i>only when they differ</i>. An operator
 *       changing an objective's step count leaves them byte-identical.</li>
 *   <li>Top-level blocks this writer knows nothing about are copied through verbatim.</li>
 * </ul>
 *
 * <p>The leading comment block is the one thing deliberately replaced: it is generated from
 * {@link FocusMetricsManifest} precisely so the documented vocabulary cannot drift from
 * what the runtime accepts.
 *
 * <p>A missing or unparseable file falls back to a full render, which is the only case
 * where there is nothing to preserve.
 */
public final class AutofocusYamlUpdater {

    private static final Logger logger = LoggerFactory.getLogger(AutofocusYamlUpdater.class);

    /** Top-level keys whose nested shape is beyond line-level editing. */
    private static final List<String> RERENDER_WHEN_CHANGED = List.of("strategies", "modalities");

    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):\\s*(#.*)?$");
    private static final Pattern TOP_LEVEL_SCALAR = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):\\s+(\\S.*)$");
    private static final Pattern LIST_ITEM_OBJECTIVE = Pattern.compile("^(\\s*)-\\s*objective:\\s*(\\S+).*$");

    private AutofocusYamlUpdater() {}

    /** What the save did, for the log and for tests. */
    public record Result(boolean changed, boolean fullRender, List<String> edits) {
        public String summary() {
            if (fullRender) {
                return "wrote a new file";
            }
            return changed ? String.join("; ", edits) : "no change";
        }
    }

    /**
     * Update {@code file} so it expresses {@code desired}, preserving everything in it that
     * {@code desired} does not speak to.
     *
     * @param file the autofocus YAML to update; created when absent
     * @param headerBlock the manifest-generated comment block, newline-terminated
     * @param desired the document the editor wants: {@code schema_version},
     *     {@code autofocus_settings} (a list of maps each carrying {@code objective}), and
     *     optionally {@code strategies} and {@code modalities}
     */
    @SuppressWarnings("unchecked")
    public static Result update(Path file, String headerBlock, Map<String, Object> desired) throws IOException {
        Map<String, Object> existing = null;
        List<String> lines = null;
        if (Files.exists(file)) {
            lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            try {
                Object parsed = new Yaml().load(String.join("\n", lines));
                if (parsed instanceof Map) {
                    existing = (Map<String, Object>) parsed;
                }
            } catch (RuntimeException e) {
                logger.warn(
                        "Autofocus YAML at {} did not parse ({}); falling back to a full render, "
                                + "which will not preserve its comments",
                        file,
                        e.getMessage());
            }
        }
        if (existing == null) {
            fullRender(file, headerBlock, desired);
            return new Result(true, true, List.of());
        }

        List<String> edits = new ArrayList<>();
        List<String> body = new ArrayList<>(lines.subList(bodyStart(lines), lines.size()));

        // Edit autofocus_settings in place, one scalar at a time.
        Object desiredSettings = desired.get("autofocus_settings");
        if (desiredSettings instanceof List<?> wanted) {
            editSettingsBlock(body, (List<Map<String, Object>>) wanted, edits);
        }

        // Re-render the nested blocks only when they actually differ.
        for (String key : RERENDER_WHEN_CHANGED) {
            Object want = desired.get(key);
            if (want == null) {
                continue;
            }
            if (sameValue(existing.get(key), want)) {
                continue;
            }
            replaceSection(body, key, renderSection(key, want));
            edits.add(key + " re-rendered (its comments are not preserved across a change)");
        }

        // schema_version is a plain top-level scalar.
        Object wantSchema = desired.get("schema_version");
        if (wantSchema != null && !sameValue(existing.get("schema_version"), wantSchema)) {
            if (setTopLevelScalar(body, "schema_version", formatScalar(wantSchema))) {
                edits.add("schema_version -> " + formatScalar(wantSchema));
            }
        }

        if (edits.isEmpty() && headerBlock.equals(currentHeader(lines))) {
            logger.info("Autofocus settings unchanged; {} left untouched", file.getFileName());
            return new Result(false, false, List.of());
        }

        List<String> out = new ArrayList<>(headerLines(headerBlock));
        out.addAll(body);
        Files.write(file, out, StandardCharsets.UTF_8);
        logger.info("Autofocus YAML updated in place ({}): {}", file.getFileName(), String.join("; ", edits));
        return new Result(true, false, edits);
    }

    // ---------- autofocus_settings ----------

    private static void editSettingsBlock(List<String> body, List<Map<String, Object>> wanted, List<String> edits) {
        int header = findTopLevelKey(body, "autofocus_settings");
        if (header < 0) {
            body.addAll(renderSection("autofocus_settings", wanted));
            edits.add("autofocus_settings created");
            return;
        }
        int sectionEnd = sectionEnd(body, header);
        for (Map<String, Object> item : wanted) {
            Object objective = item.get("objective");
            if (objective == null) {
                continue;
            }
            int[] span = locateObjectiveItem(body, header + 1, sectionEnd, String.valueOf(objective));
            if (span == null) {
                // A newly registered objective: render just this entry at the end of the block.
                int insertAt = sectionEnd;
                while (insertAt - 1 > header && body.get(insertAt - 1).isBlank()) {
                    insertAt--;
                }
                body.addAll(insertAt, renderListItem(item));
                sectionEnd = sectionEnd(body, findTopLevelKey(body, "autofocus_settings"));
                edits.add(objective + " added");
                continue;
            }
            int before = body.size();
            int changes = editItemScalars(body, span, item, String.valueOf(objective), edits);
            if (body.size() != before) {
                sectionEnd += body.size() - before;
            }
            if (changes == 0) {
                logger.debug("Autofocus YAML: {} already matches the editor", objective);
            }
        }
    }

    /** Set or insert each modelled scalar inside one list item. Unmodelled keys are untouched. */
    private static int editItemScalars(
            List<String> body, int[] span, Map<String, Object> item, String objective, List<String> edits) {
        int itemIndent = leadingSpaces(body.get(span[0])).length();
        int fieldIndent = itemIndent + 2;
        int end = span[1];
        int changes = 0;
        for (Map.Entry<String, Object> entry : item.entrySet()) {
            String field = entry.getKey();
            if ("objective".equals(field)) {
                continue; // the selector itself
            }
            String formatted = formatScalar(entry.getValue());
            int at = -1;
            for (int i = span[0]; i < end; i++) {
                if (matchesKey(body.get(i), i == span[0] ? -1 : fieldIndent, field)) {
                    at = i;
                    break;
                }
            }
            if (at >= 0) {
                String line = body.get(at);
                String current = scalarValueOf(line);
                if (scalarsAgree(current, entry.getValue())) {
                    continue;
                }
                body.set(at, repeat(' ', fieldIndent) + field + ": " + formatted + inlineComment(line));
                edits.add(objective + "." + field + " " + current + " -> " + formatted);
                changes++;
            } else {
                int insertAt = end;
                while (insertAt - 1 > span[0] && body.get(insertAt - 1).isBlank()) {
                    insertAt--;
                }
                body.add(insertAt, repeat(' ', fieldIndent) + field + ": " + formatted);
                end++;
                edits.add(objective + "." + field + " added = " + formatted);
                changes++;
            }
        }
        return changes;
    }

    /**
     * Span of the {@code - objective: <name>} list item, as [firstLine, endExclusive].
     *
     * <p>A comment line sitting between two items belongs to the item it precedes, so the
     * span ends at the first line that starts a new item or leaves the list -- trailing
     * comments stay with the entry they were written under.
     */
    private static int[] locateObjectiveItem(List<String> body, int from, int to, String objective) {
        int itemIndent = -1;
        for (int i = from; i < to; i++) {
            Matcher m = LIST_ITEM_OBJECTIVE.matcher(body.get(i));
            if (!m.matches()) {
                continue;
            }
            if (itemIndent < 0) {
                itemIndent = m.group(1).length();
            }
            if (m.group(1).length() != itemIndent) {
                continue;
            }
            if (!stripQuotes(m.group(2)).equals(objective)) {
                continue;
            }
            int end = i + 1;
            while (end < to) {
                String line = body.get(end);
                if (!line.isBlank()) {
                    int lead = leadingSpaces(line).length();
                    if (lead < itemIndent) {
                        break;
                    }
                    if (lead == itemIndent && line.trim().startsWith("- ")) {
                        break;
                    }
                }
                end++;
            }
            // Do not swallow blank lines that separate this entry from the next.
            while (end - 1 > i && body.get(end - 1).isBlank()) {
                end--;
            }
            return new int[] {i, end};
        }
        return null;
    }

    // ---------- whole-section replacement ----------

    private static void replaceSection(List<String> body, String key, List<String> rendered) {
        int header = findTopLevelKey(body, key);
        if (header < 0) {
            int insertAt = body.size();
            while (insertAt > 0 && body.get(insertAt - 1).isBlank()) {
                insertAt--;
            }
            body.addAll(insertAt, rendered);
            return;
        }
        int end = sectionEnd(body, header);
        List<String> tail = new ArrayList<>(body.subList(end, body.size()));
        while (body.size() > header) {
            body.remove(body.size() - 1);
        }
        body.addAll(rendered);
        body.addAll(tail);
    }

    private static List<String> renderSection(String key, Object value) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put(key, value);
        List<String> out = new ArrayList<>();
        for (String line : dump(wrapper).split("\n", -1)) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> renderListItem(Map<String, Object> item) {
        List<String> rendered = renderSection("autofocus_settings", List.of(item));
        return new ArrayList<>(rendered.subList(1, rendered.size()));
    }

    private static void fullRender(Path file, String headerBlock, Map<String, Object> desired) throws IOException {
        List<String> out = new ArrayList<>(headerLines(headerBlock));
        for (String line : dump(desired).split("\n", -1)) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        Files.write(file, out, StandardCharsets.UTF_8);
        logger.info("Autofocus YAML rendered fresh at {}", file);
    }

    private static String dump(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(value);
    }

    // ---------- header ----------

    /** Index of the first line that is neither blank nor a comment. */
    private static int bodyStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return i;
            }
        }
        return lines.size();
    }

    private static String currentHeader(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bodyStart(lines); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static List<String> headerLines(String headerBlock) {
        List<String> out = new ArrayList<>();
        if (headerBlock == null || headerBlock.isEmpty()) {
            return out;
        }
        String[] split = headerBlock.split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            if (i == split.length - 1 && split[i].isEmpty()) {
                break; // the block's trailing newline, not a blank line
            }
            out.add(split[i]);
        }
        return out;
    }

    // ---------- value comparison and formatting ----------

    /**
     * Compare a parsed value with a model value, ignoring numeric boxing.
     *
     * <p>SnakeYAML reads {@code 15.0} as a Double and {@code 15} as an Integer, and the
     * editor's model does not always round-trip the same box. Without this, a save would
     * think {@code strategies} had changed and re-render it -- deleting its comments for
     * nothing.
     */
    private static boolean sameValue(Object a, Object b) {
        return normalize(a).equals(normalize(b));
    }

    private static Object normalize(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(normalize(v)));
            return out;
        }
        return value == null ? "" : value;
    }

    /**
     * Whether the text already in the file means the same value the editor wants.
     *
     * <p>Textual comparison is wrong here: {@code autofocus_mock.yml} writes
     * {@code texture_threshold: 0.010} and this writer would render {@code 0.01}, so a save
     * that changed nothing would still rewrite the line. A rewrite that cannot be explained
     * is how a reviewer stops trusting the diff, so agreement is decided on the value.
     */
    private static boolean scalarsAgree(String existingText, Object desiredValue) {
        if (existingText == null) {
            return false;
        }
        String text = stripQuotes(existingText);
        if (desiredValue instanceof Number n) {
            try {
                return Double.compare(Double.parseDouble(text), n.doubleValue()) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (desiredValue instanceof Boolean b) {
            return text.equalsIgnoreCase(b.toString());
        }
        return text.equals(String.valueOf(desiredValue));
    }

    /** Render a scalar the way the file already writes them. */
    private static String formatScalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                // Keep the ".0": these are declared as floats in the schema and the
                // Python side reads them with float(), so the style is load-bearing
                // documentation of the type even though YAML does not care.
                return String.format("%.1f", d);
            }
            String s = String.valueOf(d);
            return s;
        }
        String s = String.valueOf(value);
        if (s.isEmpty()) {
            return "''";
        }
        if (s.matches("^[A-Za-z_][A-Za-z0-9_.\\-]*$")) {
            return s;
        }
        return "'" + s.replace("'", "''") + "'";
    }

    // ---------- line helpers ----------

    private static int findTopLevelKey(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = TOP_LEVEL_KEY.matcher(lines.get(i));
            if (m.matches() && m.group(1).equals(key)) {
                return i;
            }
            Matcher s = TOP_LEVEL_SCALAR.matcher(lines.get(i));
            if (s.matches() && s.group(1).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** First line at or after {@code header + 1} that starts another top-level key. */
    private static int sectionEnd(List<String> lines, int header) {
        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith(" ") || line.startsWith("#")) {
                continue;
            }
            if (TOP_LEVEL_KEY.matcher(line).matches()
                    || TOP_LEVEL_SCALAR.matcher(line).matches()) {
                int end = i;
                // Comment lines immediately above the next key introduce it, not us.
                while (end - 1 > header && lines.get(end - 1).trim().startsWith("#")) {
                    end--;
                }
                return end;
            }
        }
        return lines.size();
    }

    private static boolean setTopLevelScalar(List<String> lines, String key, String formatted) {
        int at = findTopLevelKey(lines, key);
        if (at < 0) {
            lines.add(0, key + ": " + formatted);
            return true;
        }
        lines.set(at, key + ": " + formatted + inlineComment(lines.get(at)));
        return true;
    }

    /**
     * Matches {@code key:} at the given indent. Pass {@code indent = -1} to accept any
     * indent, which is what the {@code - objective: X} line needs since its key sits after
     * the dash.
     */
    private static boolean matchesKey(String line, int indent, String key) {
        String trimmed = line.trim();
        if (trimmed.startsWith("- ")) {
            trimmed = trimmed.substring(2).trim();
        } else if (indent >= 0 && leadingSpaces(line).length() != indent) {
            return false;
        }
        if (!trimmed.startsWith(key)) {
            return false;
        }
        String rest = trimmed.substring(key.length());
        return rest.startsWith(":");
    }

    /** The value text of {@code key: value}, with any inline comment removed. */
    private static String scalarValueOf(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String rest = line.substring(colon + 1);
        int hash = indexOfUnquoted(rest, '#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        return rest.trim();
    }

    /** The {@code  # ...} suffix of a line, or an empty string. */
    private static String inlineComment(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return "";
        }
        String rest = line.substring(colon + 1);
        int hash = indexOfUnquoted(rest, '#');
        return hash < 0 ? "" : "  " + rest.substring(hash).trim();
    }

    private static int indexOfUnquoted(String s, char c) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'' && !dbl) {
                single = !single;
            } else if (ch == '"' && !single) {
                dbl = !dbl;
            } else if (ch == c && !single && !dbl) {
                return i;
            }
        }
        return -1;
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith("\"") && t.endsWith("\"")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(0, i);
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(Math.max(0, n));
    }
}
