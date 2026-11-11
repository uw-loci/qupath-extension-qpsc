package qupath.ext.qpsc.model;

import java.util.Locale;

/**
 * Where a time-lapse's timepoint loop sits relative to positions and Z planes. Sent to the
 * server as {@code --t-order}; the output layout ({@code {group}/t{tt}/[z{zz}/]{tile}}) is the
 * same for all three.
 */
public enum TimeLapseOrder {
    /** Each timepoint visits every position, taking its Z-stack there (a well plate's rounds). */
    REGION("region", "Across the region: every position, then the next timepoint"),
    /** Each position runs all its timepoints, each a full Z-stack, before the next position. */
    POSITION("position", "Per position: all timepoints at a position, then the next"),
    /** Each Z plane of a position runs all its timepoints before Z moves to the next plane. */
    PLANE("plane", "Per Z plane: all timepoints at a plane, then the next plane");

    private final String code;
    private final String label;

    TimeLapseOrder(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** The {@code --t-order} value. */
    public String code() {
        return code;
    }

    /** What the dialogs show. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** The order for a stored code; {@link #REGION} for null or anything unknown. */
    public static TimeLapseOrder fromCode(String code) {
        if (code != null) {
            String c = code.trim().toLowerCase(Locale.ROOT);
            for (TimeLapseOrder o : values()) {
                if (o.code.equals(c)) {
                    return o;
                }
            }
        }
        return REGION;
    }
}
