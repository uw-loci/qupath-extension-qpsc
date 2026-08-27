package qupath.ext.qpsc.ui;

/**
 * Theme-aware colours for styled text, as CSS fragments.
 *
 * <h2>The problem these solve</h2>
 * QuPath ships a light theme and a dark one, and installs the dark stylesheet globally (as a
 * user-agent stylesheet), so every window in the JVM -- including this extension's dialogs --
 * switches with it. A hardcoded colour does not. {@code -fx-text-fill: #666} is a readable grey
 * on the light theme and very nearly invisible on the dark one, and the mirror-image mistake is
 * just as easy to make: a light grey chosen to fix a dark-mode complaint disappears on white.
 * Both have happened here more than once.
 *
 * <h2>How they work</h2>
 * Each constant is a CSS {@code ladder()} expression keyed on {@code -fx-background}, the
 * background colour the active theme defines. {@code ladder} selects by the brightness of that
 * colour, so the first stop applies on a dark ground and the second on a light one. Resolution
 * happens in CSS, which means the colour follows a theme switch live, with nothing to re-apply
 * and no listener to register.
 *
 * <p>Crucially this needs no stylesheet of its own and no per-Scene wiring: {@code
 * -fx-background} is defined by Modena and redefined by QuPath's dark theme, both of which are
 * already in force everywhere. Each constant is a complete colour expression, so it drops
 * straight into an existing style string:
 *
 * <pre>
 * label.setStyle("-fx-font-size: 11px; -fx-text-fill: " + ThemeColors.MUTED + ";");
 * </pre>
 *
 * <h2>Choosing between them</h2>
 * Pick by MEANING, not by the colour you want. A value that reads "this is a problem" should be
 * {@link #ERROR} whether or not red suits the layout, because that is what survives someone
 * later retuning the palette.
 *
 * <h2>What NOT to use these for</h2>
 * Text drawn on an explicitly-coloured background -- a coloured badge, a pastel callout panel --
 * must NOT use these. The pair is already self-consistent: white on a solid red chip stays
 * readable in either theme, and making only the text adapt would leave light text on a light
 * panel. Make the background adapt too, or leave the pair alone.
 *
 * <p>Note also that {@code -fx-mid-text-color} is NOT theme-aware despite the name -- it
 * resolves to {@code #333333} on both themes. Use {@link #MUTED} instead.
 */
public final class ThemeColors {

    private ThemeColors() {}

    /**
     * Builds a ladder that picks {@code onDark} on a dark theme and {@code onLight} on a light
     * one.
     *
     * <p>The 49%/50% split is a step rather than a blend: anything in between would produce a
     * washed-out mixture at mid brightness, and there is no theme there to serve.
     *
     * @param onDark  colour to use when the theme background is dark
     * @param onLight colour to use when the theme background is light
     * @return a CSS colour expression
     */
    private static String ladder(String onDark, String onLight) {
        return "ladder(-fx-background, " + onDark + " 49%, " + onLight + " 50%)";
    }

    /** Ordinary body text. Theme-aware; equivalent to letting the control pick its own colour. */
    public static final String NORMAL = "-fx-text-base-color";

    /** Secondary text: hints, units, captions, anything deliberately quieter than body text. */
    public static final String MUTED = ladder("#ADADAD", "#666666");

    /** Something is wrong and the user must act: failures, invalid input, hard blocks. */
    public static final String ERROR = ladder("#FF8A80", "#C62828");

    /** Confirmed good: validation passed, hardware present, calibration current. */
    public static final String SUCCESS = ladder("#81C784", "#2E7D32");

    /** Proceeding is possible but something is stale, missing, or worth a second look. */
    public static final String WARNING = ladder("#FFB74D", "#9E4E00");

    /** Neutral emphasis: links, current selection, informational callouts. */
    public static final String INFO = ladder("#90CAF9", "#1565C0");
}
