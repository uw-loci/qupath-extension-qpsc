package qupath.ext.qpsc.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Display-to-protocol mapping for the autofocus strategy override dropdown
 * that appears in the Advanced panel of the acquisition dialogs.
 *
 * <p>The display strings are user-facing; the protocol strings are the strategy
 * names accepted by the Python server's v2 YAML loader (matching the keys in
 * the {@code strategies:} section of {@code autofocus_<scope>.yml}).
 *
 * <p>The {@link #DEFAULT_DISPLAY} option maps to {@code null}: no
 * {@code --af-strategy} CLI flag is emitted, and the server falls back to the
 * per-modality binding from YAML.
 */
public final class AfStrategyChoice {

    public static final String DEFAULT_DISPLAY = "Default (from config)";

    private static final LinkedHashMap<String, String> DISPLAY_TO_PROTOCOL = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> PROTOCOL_TO_DISPLAY = new LinkedHashMap<>();

    static {
        DISPLAY_TO_PROTOCOL.put(DEFAULT_DISPLAY, null);
        DISPLAY_TO_PROTOCOL.put("Dense (H&E, IHC, PPM)", "dense_texture");
        DISPLAY_TO_PROTOCOL.put("Sparse (beads, pollen, IF)", "sparse_signal");
        DISPLAY_TO_PROTOCOL.put("Dark-field (SHG, LSM)", "dark_field");
        DISPLAY_TO_PROTOCOL.put("Manual only", "manual_only");
        for (Map.Entry<String, String> e : DISPLAY_TO_PROTOCOL.entrySet()) {
            if (e.getValue() != null) {
                PROTOCOL_TO_DISPLAY.put(e.getValue(), e.getKey());
            }
        }
    }

    private AfStrategyChoice() {}

    /** Ordered list of display strings, suitable for a ComboBox. */
    public static java.util.List<String> displayOrder() {
        return java.util.List.copyOf(DISPLAY_TO_PROTOCOL.keySet());
    }

    /**
     * Returns the protocol name for a given display string, or {@code null} if
     * the display starts with "Default (from config)" (which includes the
     * dynamic variant "Default (from config) -> strategy_name" used when the
     * caller has appended the resolved binding hint) or is unrecognized.
     */
    public static String displayToProtocol(String display) {
        if (display == null) return null;
        if (display.startsWith(DEFAULT_DISPLAY)) return null;
        return DISPLAY_TO_PROTOCOL.get(display);
    }

    /**
     * Returns the display string for a given protocol name, or
     * {@link #DEFAULT_DISPLAY} for {@code null}/unknown protocol.
     */
    public static String protocolToDisplay(String protocol) {
        if (protocol == null || protocol.isBlank()) return DEFAULT_DISPLAY;
        return PROTOCOL_TO_DISPLAY.getOrDefault(protocol, DEFAULT_DISPLAY);
    }

    /** Tooltip text shown alongside the dropdown. */
    public static final String TOOLTIP = "Autofocus strategy override.\n"
            + "Default (from config) uses the per-modality binding from autofocus_<scope>.yml.\n"
            + "Dense works for H&E, IHC, PPM, confluent IF.\n"
            + "Sparse is for beads/pollen/scattered IF spots on a dark background.\n"
            + "Dark-field is for SHG and laser scanning.\n"
            + "Manual only skips auto entirely and prompts the user.";
}
