package qupath.ext.qpsc.controller.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads tile entries from a TileConfiguration file ({@code "12.tif; ; (123.4, 567.8)"}): the
 * pixel-coordinate TileConfiguration_QP.txt, or TileConfiguration.txt once it holds stage um.
 */
public final class TileConfigurationFile {

    private TileConfigurationFile() {}

    /** One tile: its file name and position, in whatever units the file uses. */
    public record Entry(String name, double x, double y) {}

    /**
     * @param lines the file's lines
     * @return the tile entries in file order (acquisition order); lines that do not parse are skipped
     */
    public static List<Entry> entries(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            Entry e = parse(line);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * @param lines the file's lines
     * @return {x, y} of the last tile entry in the file (acquisition order), or null when none
     *     parses
     */
    public static double[] lastTileCenter(List<String> lines) {
        List<Entry> all = entries(lines);
        if (all.isEmpty()) {
            return null;
        }
        Entry last = all.get(all.size() - 1);
        return new double[] {last.x(), last.y()};
    }

    private static Entry parse(String line) {
        if (line == null || !line.contains(".tif")) {
            return null;
        }
        int semi = line.indexOf(';');
        int open = line.indexOf('(');
        int close = line.indexOf(')', open + 1);
        if (semi < 0 || open < 0 || close < 0) {
            return null;
        }
        String[] parts = line.substring(open + 1, close).split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new Entry(
                    line.substring(0, semi).trim(),
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
