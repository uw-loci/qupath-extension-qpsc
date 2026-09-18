package qupath.ext.qpsc.service.mda;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a Micro-Manager 2.0 stage position list as MM's "Micro-Manager Property Map"
 * version 2 JSON -- the only encoding {@code PositionListDlg}'s Load... button accepts.
 *
 * <p>MM 2.0 serializes a {@code PositionList} through {@code PropertyMap.saveJSON}, which
 * wraps every value in a {@code {"type": ..., "scalar"|"array": ...}} envelope. A plain
 * JSON object with the same field names does NOT load: MM's parser finds no {@code map}
 * key, fails, and {@code PositionListDlg} discards the file without an error dialog -- the
 * file simply appears to be ignored. That silent failure is why this encoding is built
 * explicitly here rather than reflected off a record.
 *
 * <p>Structure (matching a file saved by MM itself):
 *
 * <pre>
 * {
 *   "encoding": "UTF-8",
 *   "format": "Micro-Manager Property Map",
 *   "major_version": 2,
 *   "minor_version": 0,
 *   "map": {
 *     "StagePositions": { "type": "PROPERTY_MAP", "array": [ &lt;position&gt;, ... ] }
 *   }
 * }
 * </pre>
 *
 * <p>Each position carries {@code Label}, {@code DefaultXYStage}, {@code DefaultZStage},
 * {@code GridRow}, {@code GridCol}, {@code Properties}, and a {@code DevicePositions}
 * array. A device position stores {@code Device} plus {@code Position_um}, a DOUBLE array
 * whose LENGTH is what tells MM the axis count: two entries for an XY stage, one for a
 * focus drive. There is no {@code numAxes} field -- MM's
 * {@code StagePosition.fromPropertyMap} switches on the array length and throws for any
 * other size.
 *
 * <p>Keys are emitted in MM's own (alphabetical) order so a QPSC-written file diffs
 * cleanly against one saved from the MM GUI.
 */
final class MmPositionListJson {

    private static final String PROPERTY_MAP = "PROPERTY_MAP";
    private static final String STRING = "STRING";
    private static final String INTEGER = "INTEGER";
    private static final String DOUBLE = "DOUBLE";

    private MmPositionListJson() {}

    /**
     * Builds the full property-map document for a set of tiles.
     *
     * <p>A tile whose {@link TileStagePos#zUm()} is null contributes no Z device position
     * and leaves {@code DefaultZStage} empty. That is deliberate: a position list carrying
     * an unknown-but-present Z of 0 would command the focus drive to absolute zero the
     * moment the list is run in MM. Omitting Z instead leaves the focus drive where the
     * operator put it.
     *
     * @param tilesIn tile centroids in stage micrometers, in acquisition order
     * @param dev     MM stage device labels; XYStage/ZStage are used when null or blank
     * @return a nested map ready for Gson serialization
     */
    static Map<String, Object> build(List<TileStagePos> tilesIn, MmStageDevices dev) {
        List<TileStagePos> tiles = tilesIn == null ? List.of() : tilesIn;
        String xyStage = dev != null && dev.xyStage() != null && !dev.xyStage().isBlank() ? dev.xyStage() : "XYStage";
        String zStage = dev != null && dev.zStage() != null && !dev.zStage().isBlank() ? dev.zStage() : "ZStage";

        List<Map<String, Object>> positions = new ArrayList<>(tiles.size());
        for (TileStagePos tile : tiles) {
            positions.add(position(tile, xyStage, zStage));
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("StagePositions", propertyMapArray(positions));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("encoding", "UTF-8");
        root.put("format", "Micro-Manager Property Map");
        root.put("major_version", 2);
        root.put("minor_version", 0);
        root.put("map", map);
        return root;
    }

    private static Map<String, Object> position(TileStagePos tile, String xyStage, String zStage) {
        List<Map<String, Object>> devicePositions = new ArrayList<>(2);
        devicePositions.add(devicePosition(xyStage, tile.xUm(), tile.yUm()));
        boolean hasZ = tile.zUm() != null;
        if (hasZ) {
            devicePositions.add(devicePosition(zStage, tile.zUm()));
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("Source", string("QPSC"));

        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("DefaultXYStage", string(xyStage));
        pos.put("DefaultZStage", string(hasZ ? zStage : ""));
        pos.put("DevicePositions", propertyMapArray(devicePositions));
        pos.put("GridCol", integer(0));
        pos.put("GridRow", integer(0));
        pos.put("Label", string(tile.label() == null ? "" : tile.label()));
        pos.put("Properties", propertyMapScalar(props));
        return pos;
    }

    private static Map<String, Object> devicePosition(String device, double... coordsUm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Device", string(device));
        m.put("Position_um", doubleArray(coordsUm));
        return m;
    }

    private static Map<String, Object> string(String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", STRING);
        m.put("scalar", value == null ? "" : value);
        return m;
    }

    private static Map<String, Object> integer(int value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", INTEGER);
        m.put("scalar", value);
        return m;
    }

    private static Map<String, Object> doubleArray(double... values) {
        List<Double> list = new ArrayList<>(values.length);
        for (double v : values) {
            list.add(v);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", DOUBLE);
        m.put("array", list);
        return m;
    }

    private static Map<String, Object> propertyMapArray(List<Map<String, Object>> entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", PROPERTY_MAP);
        m.put("array", entries);
        return m;
    }

    private static Map<String, Object> propertyMapScalar(Map<String, Object> entries) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", PROPERTY_MAP);
        m.put("scalar", entries);
        return m;
    }
}
