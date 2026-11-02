package qupath.ext.qpsc.modality;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * What the Live Viewer camera tab offers a modality that builds its own camera controls through
 * {@link ModalityHandler#buildLiveCameraControls}. Every method runs on the FX thread unless
 * it says otherwise.
 */
public interface LiveCameraHost {

    /** The box the modality's controls go in. It is empty when the build starts. */
    VBox content();

    /** The microscope configuration the panel reads. */
    MicroscopeConfigManager configManager();

    /** The acquisition profile in use: the one picked in the panel, else the first matching the modality. */
    String activeProfile(String modality);

    /** The panel's profile selector for the modality, or null when it has none. */
    Node profileSelector(String modality);

    /** The illumination control, or null when the modality declares no lamp maximum in its config. */
    Node declaredIlluminationControl(String modality);

    /** Shows {@code text} in the camera tab's status line, in {@code color}. */
    void setStatus(String text, String color);

    /**
     * Applies a channel of a profile on a background thread, then the exposure.
     *
     * @param exposureMs exposure to set after the channel, or null to keep the channel's own
     */
    void applyChannelInBackground(String profile, String channelId, Double exposureMs);

    /** Asks before writing to the microscope configuration. @return true if the operator chose Save */
    boolean confirmSaveToYaml(String header, String details);

    /** The button that opens Background Collection, shown once a save asks for a new background. */
    Button collectBackgroundButton(String modality);

    /** The note under a Save to Profile button saying it changes the system default for {@code what}. */
    Label saveToProfileCaption(String what);

    /** Marks the modality's backgrounds as needing collection again, for {@link #collectBackgroundButton}. */
    void requestBackgroundRecollection(String modality);

    /** Builds the panel's default controls for the modality into {@link #content()}. */
    void buildGenericContent(String modality);

    /** Clears {@link #content()} and builds the modality's controls again. */
    void rebuild(String modality);
}
