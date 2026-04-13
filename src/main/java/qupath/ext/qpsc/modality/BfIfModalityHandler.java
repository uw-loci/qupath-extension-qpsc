package qupath.ext.qpsc.modality;

/**
 * Modality handler for combined brightfield + widefield immunofluorescence
 * acquisition on instruments with a shared camera (e.g. OWS3).
 *
 * <p>Implementation-wise, BF+IF is "just more channels" on top of widefield
 * fluorescence: the brightfield step is a regular entry in the channel
 * library whose {@code mm_setup_presets} switch the light path to the BF
 * camera port and turn on the transmitted lamp, and whose subsequent
 * channel entries switch to the epi-LED path and select fluorescence
 * wavelengths. The acquisition loop, stitcher, and multichannel merger
 * all treat it identically to pure IF.
 *
 * <p>This handler therefore inherits every behavior from
 * {@link WidefieldFluorescenceModalityHandler} and only overrides its
 * display name so the menu / logs / UI can distinguish the two.
 *
 * <p>The separation exists so that (a) users can see "BF+IF" as a
 * distinct, discoverable modality in the acquisition dialog, (b)
 * instruments can offer a pure-BF modality, a pure-IF modality, and a
 * combined BF+IF modality with different channel libraries in YAML,
 * and (c) future BF+IF-specific behavior (e.g. different default image
 * type, BF-specific background correction logic) has a home to land in
 * without touching the pure-IF handler.
 *
 * <p>Registered under prefix: {@code "bf_if"}
 */
public class BfIfModalityHandler extends WidefieldFluorescenceModalityHandler {

    @Override
    public String getDisplayName() {
        return "Brightfield + Immunofluorescence";
    }
}
