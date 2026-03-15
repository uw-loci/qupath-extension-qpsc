package qupath.ext.qpsc.modality;

/**
 * Descriptor for a modality-contributed menu item in the QPSC extension menu.
 *
 * <p>Modality handlers return a list of these from {@link ModalityHandler#getMenuContributions()}
 * to register workflow menu items dynamically, without requiring hardcoded entries in
 * SetupScope or QPScopeController.</p>
 *
 * <p>Use {@link #separator()} to insert a visual separator between groups of items.</p>
 *
 * @param id      unique identifier for this menu item (e.g., "polarizerCalibration"),
 *                or {@code null} for separator items
 * @param label   display text for the menu entry
 * @param tooltip tooltip text shown on hover (may be null for no tooltip)
 * @param action  action to execute when the menu item is clicked,
 *                or {@code null} for separator items
 *
 * @author Mike Nelson
 * @since 2.0
 */
public record ModalityMenuItem(String id, String label, String tooltip, Runnable action) {

    /**
     * Creates a separator sentinel that the menu renderer will replace with
     * a {@code SeparatorMenuItem}.
     *
     * @return a ModalityMenuItem representing a visual separator
     */
    public static ModalityMenuItem separator() {
        return new ModalityMenuItem(null, null, null, null);
    }

    /**
     * Returns {@code true} if this item is a separator sentinel.
     */
    public boolean isSeparator() {
        return id == null && action == null;
    }
}
