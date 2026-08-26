package qupath.ext.qpsc.modality.lcpolscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.widefield.ui.WidefieldChannelBoundingBoxUI;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.lib.images.ImageData;

/**
 * Modality handler for the LC-PolScope: quantitative polarized-light microscopy using a
 * liquid-crystal universal compensator (Meadowlark D5020) rather than a rotation stage.
 *
 * <h3>How this differs from PPM</h3>
 * <p>Both are polarized-light modalities, but the mechanism is different and conflating them
 * is the main way this goes wrong. PPM rotates a physical polarizer to N discrete angles;
 * the LC-PolScope sets N discrete polarization <em>states</em> electrically by driving two
 * liquid crystals to calibrated voltages. There is no rotation stage on this scope, so the
 * states are expressed through the generic {@link Channel} library -- the same mechanism
 * widefield fluorescence uses for DAPI/FITC/TRITC/Cy5 -- and this handler behaves like a
 * channel-based modality, not an angle-based one.
 *
 * <h3>The equal-exposure invariant</h3>
 * <p><b>All states must share one exposure.</b> The downstream Stokes inversion treats the
 * five intensities as samples of a single radiometric scale: it multiplies them by an
 * instrument matrix built from the calibration swing. A per-state exposure difference
 * rescales one row of that vector and biases retardance and orientation, with no visible
 * symptom -- the images look entirely normal. This is the single most important behavioural
 * difference from the fluorescence channel path, where per-channel exposure is not only
 * allowed but expected.
 *
 * <p>{@link #getChannels} therefore normalises every channel to one exposure and logs when
 * the configuration disagreed, rather than trusting the YAML to be internally consistent.
 *
 * <h3>Autofocus</h3>
 * <p>The extinction state is near-black by construction, so every gradient- and
 * texture-based focus metric collapses into noise on it. Focus on a bright state instead.
 * The focus channel is a user choice surfaced by the bounding-box UI
 * ({@link ModalityHandler.BoundingBoxUI#getFocusChannelId()}); {@link #defaultFocusChannelId}
 * gives the sensible default for this modality.
 *
 * @see <a href="https://elifesciences.org/articles/55502">Guo et al., eLife 2020 (QLIPP)</a>
 */
public class LCPolScopeModalityHandler implements ModalityHandler {

    private static final Logger logger = LoggerFactory.getLogger(LCPolScopeModalityHandler.class);

    /**
     * Channel id of the extinction state. Near-black by construction: it is the state the
     * calibration drove to minimum transmission, so it is never a sensible autofocus
     * reference and never the state to set exposure from.
     */
    public static final String EXTINCTION_CHANNEL_ID = "State0";

    @Override
    public CompletableFuture<List<AngleExposure>> getRotationAngles(
            String modalityName, String objective, String detector, String wbMode) {
        // No rotation stage. Returning an empty list keeps the command builder from also
        // emitting a bogus single exposure alongside the channel flags.
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<List<Channel>> getChannels(String modality, String objective, String detector) {
        return CompletableFuture.completedFuture(enforceEqualExposure(resolveChannels(modality)));
    }

    /**
     * Returns the channels with a single shared exposure, logging loudly if the
     * configuration did not already agree.
     *
     * <p>The shared value is the <b>maximum</b> configured exposure, not the first or the
     * mean: if the states disagree, the longest is the one that was presumably tuned so the
     * brightest state does not clip, and shortening it risks pushing the extinction state
     * into the noise floor. Lengthening the others only costs time.
     *
     * @param channels channels as configured; may be empty
     * @return channels with one common exposure, or the input unchanged if it is empty
     */
    static List<Channel> enforceEqualExposure(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) {
            return List.of();
        }
        double shared =
                channels.stream().mapToDouble(Channel::defaultExposureMs).max().orElse(-1);
        boolean mismatch = channels.stream().anyMatch(c -> c.defaultExposureMs() != shared);
        if (!mismatch) {
            return channels;
        }
        logger.warn(
                "LC-PolScope channels have unequal exposures {} -- normalizing all to {} ms. "
                        + "The Stokes inversion treats the states as one radiometric scale, so "
                        + "per-state exposure silently biases retardance and orientation. Fix the "
                        + "acquisition profile so every state declares the same exposure_ms.",
                channels.stream().map(c -> c.id() + "=" + c.defaultExposureMs()).toList(),
                shared);
        List<Channel> normalized = new ArrayList<>(channels.size());
        for (Channel c : channels) {
            normalized.add(new Channel(
                    c.id(), c.displayName(), shared, c.presets(), c.properties(), c.intensityProperty(), c.settleMs()));
        }
        return normalized;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Never the extinction state. It is driven to minimum transmission by the
     * calibration, so it is near-black and every gradient- and texture-based focus metric
     * evaluated on it measures noise rather than sharpness. Any swing state is far brighter,
     * and they are equivalent on a blank field, so the first non-extinction state is as good
     * a choice as any.
     */
    @Override
    public String defaultFocusChannelId(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) {
            return null;
        }
        return channels.stream()
                .map(Channel::id)
                .filter(id -> !EXTINCTION_CHANNEL_ID.equalsIgnoreCase(id))
                .findFirst()
                .orElse(channels.get(0).id());
    }

    @Override
    public Optional<BoundingBoxUI> createBoundingBoxUI() {
        // Reuse the generic channel picker rather than forking a near-identical dialog --
        // the LC states are channels as far as the UI is concerned.
        WidefieldChannelBoundingBoxUI ui = new WidefieldChannelBoundingBoxUI();
        if (!ui.hasChannels()) {
            return Optional.empty();
        }
        ui.addModalityNotice(
                "Equal exposure across all states is required",
                "The reconstruction treats the five states as samples of one radiometric scale. "
                        + "A per-state exposure difference biases retardance and orientation with no "
                        + "visible symptom -- the images still look correct. Leave the exposures equal.");
        ui.addModalityNotice(
                "Orientation is axial: stitching and downsampling need care",
                "This run also produces a slow-axis orientation map, where 0 and 180 degrees mean the "
                        + "same thing. Averaging such values is invalid -- the mean of 179 and 1 is 90 "
                        + "degrees, perpendicular to the truth and entirely plausible-looking. Seam "
                        + "blending and pyramid downsampling must therefore go through sin(2t)/cos(2t), "
                        + "and mirroring the image negates the angle. The written tiles declare this in "
                        + "their OME metadata; any external tool you use on them must honour it.");
        return Optional.of(ui);
    }

    private List<Channel> resolveChannels(String profileKey) {
        if (profileKey == null || profileKey.isBlank()) {
            return List.of();
        }
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path == null || path.isBlank()) {
                return List.of();
            }
            mgr = MicroscopeConfigManager.getInstance(path);
        }
        try {
            return mgr.getChannelsForProfile(profileKey);
        } catch (Exception e) {
            logger.warn("Failed to resolve LC-PolScope channels for profile '{}': {}", profileKey, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String getDisplayName() {
        return "LC-PolScope";
    }

    @Override
    public Optional<ImageData.ImageType> getImageType() {
        // Deliberately not FLUORESCENCE. A five-state polarization stack is neither
        // brightfield nor fluorescence, and mislabelling it drags in the wrong display
        // defaults downstream.
        return Optional.of(ImageData.ImageType.OTHER);
    }

    @Override
    public int getDefaultAngleCount() {
        // Used for time and storage estimates. Five snaps per tile, one per LC state.
        return 5;
    }

    @Override
    public void configureCommandBuilder(AcquisitionCommandBuilder builder) {
        // Oryx ORX-10G-51S5M is the monochrome variant -- no Bayer mosaic.
        builder.enableDebayer(false);
    }

    @Override
    public String getDefaultWbMode() {
        // Monochrome, and white balance would corrupt the radiometric scale the Stokes
        // inversion depends on.
        return "off";
    }
}
