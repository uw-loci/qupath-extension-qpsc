package qupath.ext.qpsc.ui.setupwizard;

import javafx.scene.Node;

/**
 * Interface for a single step in the Setup Wizard.
 * Each step provides a UI panel and validation logic.
 */
public interface WizardStep {

    /** Short title shown in the step list sidebar. */
    String getTitle();

    /** Description shown at the top of the step panel. */
    String getDescription();

    /** The JavaFX node containing this step's UI controls. */
    Node getContent();

    /**
     * Validate the current step's inputs.
     *
     * @return null if valid, or an error message string if invalid
     */
    String validate();

    /**
     * Called when the user navigates away from this step (Next or Back).
     * Use this to persist transient UI state into the shared {@link WizardData}.
     */
    default void onLeave() {}

    /**
     * Called when the user navigates to this step.
     * Use this to refresh UI from shared {@link WizardData} (e.g., after Back).
     */
    default void onEnter() {}
}
