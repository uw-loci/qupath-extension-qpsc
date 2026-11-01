package qupath.ext.qpsc.utilities;

/** How an acquisition evens out the brightness across the field. */
public enum FlatfieldMethod {
    /** No correction. */
    NONE("None"),
    /** Divide by background images measured from an empty field. */
    BACKGROUND("Background images"),
    /** Apply BaSiC profiles fitted from tiles, for detectors with no usable background image. */
    BASIC("BaSiC profile");

    private final String displayName;

    FlatfieldMethod(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /** Reads a saved name; anything unknown or empty is {@link #BACKGROUND}, the default. */
    public static FlatfieldMethod fromName(String name) {
        for (FlatfieldMethod method : values()) {
            if (method.name().equals(name)) {
                return method;
            }
        }
        return BACKGROUND;
    }
}
