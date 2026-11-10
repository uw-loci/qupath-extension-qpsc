package qupath.ext.qpsc.controller.workflow;

import java.util.List;

/** Reads tile centers from a TileConfiguration_QP.txt ({@code "12.tif; ; (123.4, 567.8)"}). */
public final class TileConfigurationQp {

    private TileConfigurationQp() {}

    /**
     * @param lines the file's lines
     * @return {x, y} of the last tile entry in the file (acquisition order), or null when none
     *     parses
     */
    public static double[] lastTileCenter(List<String> lines) {
        double[] last = null;
        if (lines == null) {
            return null;
        }
        for (String line : lines) {
            double[] c = parse(line);
            if (c != null) {
                last = c;
            }
        }
        return last;
    }

    private static double[] parse(String line) {
        if (line == null || !line.contains(".tif")) {
            return null;
        }
        int open = line.indexOf('(');
        int close = line.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        String[] parts = line.substring(open + 1, close).split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new double[] {Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
