package qupath.ext.qpsc.modality;

/**
 * Descriptor for a modality-contributed menu item in the QPSC extension menu.
 *
 * <p>Modality handlers return a list of these from {@link ModalityHandler#getMenuContributions()}
 * to register workflow menu items dynamically, without requiring hardcoded entries in
 * SetupScope or QPScopeController.</p>
 *
 * @param id      unique identifier for this menu item (e.g., "polarizerCalibration")
 * @param label   display text for the menu entry
 * @param tooltip tooltip text shown on hover (may be null for no tooltip)
 * @param action  action to execute when the menu item is clicked
 *
 * @author Mike Nelson
 * @since 2.0
 */
public record ModalityMenuItem(String id, String label, String tooltip, Runnable action) {}
