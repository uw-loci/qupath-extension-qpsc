package qupath.ext.qpsc.modality;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import qupath.ext.qpsc.ui.ThemeColors;

/**
 * White balance modes supported by the QPSC acquisition system.
 *
 * <p>Each mode has a display name (shown in UI combo boxes), a protocol name
 * (sent over the socket protocol and stored in YAML configuration files),
 * and a UI color used to visually distinguish single-angle vs per-angle modes.
 *
 * <p>This enum is the single source of truth for WB mode names, replacing the
 * duplicated switch expressions previously in BackgroundCollectionController,
 * UnifiedAcquisitionController, and ExistingImageAcquisitionController.
 *
 * @author Mike Nelson
 * @since 4.1
 */
public enum WbMode {
    OFF("Off", "off", false, ThemeColors.MUTED),
    CAMERA_AWB("Camera AWB", "camera_awb", true, Palette.TEAL),
    SIMPLE("Simple (90deg)", "simple", true, ThemeColors.INFO),
    PER_ANGLE("Per-angle (PPM)", "per_angle", true, Palette.PURPLE);

    /**
     * Teal and purple exist only to tell these two modes apart at a glance -- they carry no
     * good/bad meaning, so neither maps onto a {@link ThemeColors} role. They follow the same
     * ladder pattern so they track the theme like everything else; see {@link ThemeColors} for
     * why a fixed hex cannot.
     *
     * <p>In a holder class because an enum constant may not reference a static field of its own
     * enum declared after it.
     */
    private static final class Palette {
        private Palette() {}

        static final String TEAL = "ladder(-fx-background, #4DB6AC 49%, #00695C 50%)";
        static final String PURPLE = "ladder(-fx-background, #CE93D8 49%, #6A1B9A 50%)";
    }

    private final String displayName;
    private final String protocolName;
    private final boolean requiresBackgrounds;
    private final String color;

    WbMode(String displayName, String protocolName, boolean requiresBackgrounds, String color) {
        this.displayName = displayName;
        this.protocolName = protocolName;
        this.requiresBackgrounds = requiresBackgrounds;
        this.color = color;
    }

    /** Name shown in UI combo boxes (e.g., "Simple (90deg)"). */
    public String getDisplayName() {
        return displayName;
    }

    /** Name used in socket protocol and YAML files (e.g., "simple"). */
    public String getProtocolName() {
        return protocolName;
    }

    /** Whether this mode requires matching background images for acquisition. */
    public boolean requiresBackgrounds() {
        return requiresBackgrounds;
    }

    /** Theme-aware CSS colour expression distinguishing this mode in the UI. */
    public String getColor() {
        return color;
    }

    /**
     * Creates a Label with the mode's display name styled with its color.
     *
     * @param mode the WB mode
     * @return a styled Label
     */
    public static Label createColoredLabel(WbMode mode) {
        Label label = new Label(mode.getDisplayName());
        label.setStyle("-fx-text-fill: " + mode.getColor() + "; -fx-font-weight: bold;");
        return label;
    }

    /**
     * Applies a color-coded cell factory to a ComboBox whose items are WB mode display names.
     * Colors both the dropdown list cells and the selected-item button cell.
     *
     * @param comboBox the ComboBox to style
     */
    public static void applyColorCellFactory(ComboBox<String> comboBox) {
        comboBox.setCellFactory(lv -> createColoredListCell());
        comboBox.setButtonCell(createColoredListCell());
    }

    private static ListCell<String> createColoredListCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    try {
                        WbMode mode = WbMode.fromDisplayName(item);
                        setStyle("-fx-text-fill: " + mode.getColor() + "; -fx-font-weight: bold;");
                    } catch (IllegalArgumentException e) {
                        setStyle("");
                    }
                }
            }
        };
    }

    /**
     * Look up a WbMode by its UI display name.
     *
     * @param displayName the display name (e.g., "Per-angle (PPM)")
     * @return the matching WbMode
     * @throws IllegalArgumentException if no match found
     */
    public static WbMode fromDisplayName(String displayName) {
        if (displayName == null) {
            return PER_ANGLE; // default
        }
        for (WbMode mode : values()) {
            if (mode.displayName.equals(displayName)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown WB mode display name: " + displayName);
    }

    /**
     * Look up a WbMode by its protocol/YAML name.
     *
     * @param protocolName the protocol name (e.g., "per_angle")
     * @return the matching WbMode
     * @throws IllegalArgumentException if no match found
     */
    public static WbMode fromProtocolName(String protocolName) {
        if (protocolName == null) {
            return PER_ANGLE; // default
        }
        for (WbMode mode : values()) {
            if (mode.protocolName.equals(protocolName)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown WB mode protocol name: " + protocolName);
    }
}
