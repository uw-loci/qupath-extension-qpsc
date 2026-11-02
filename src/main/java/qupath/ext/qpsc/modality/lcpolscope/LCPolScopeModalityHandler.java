package qupath.ext.qpsc.modality.lcpolscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
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
 * symptom -- the images look entirely normal. This is the single most important behavioral
 * difference from the fluorescence channel path, where per-channel exposure is not only
 * allowed but expected.
 *
 * <p>{@link #getChannels} therefore normalizes every channel to one exposure and logs when
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

    /** True when {@code modality} routes through this handler (prefix {@code lcpolscope} or {@code lcps}). */
    public static boolean isLcPolScope(String modality) {
        return ModalityRegistry.getHandler(modality) instanceof LCPolScopeModalityHandler;
    }

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
    public boolean isFocusChannelCandidate(String channelId) {
        return !EXTINCTION_CHANNEL_ID.equalsIgnoreCase(channelId);
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
        // The orientation color map and brightness ceiling chosen in the acquisition panel;
        // null = the config's own.
        builder.displayColormap(LCDisplaySettings.selectedColormap());
        builder.displayCeilingNm(LCDisplaySettings.sendableCeilingNm());
    }

    @Override
    public String getDefaultWbMode() {
        // Monochrome, and white balance would corrupt the radiometric scale the Stokes
        // inversion depends on.
        return "off";
    }

    /**
     * The microscope server reconstructs every tile as it is acquired and writes the results
     * beside the state folders, in {@code retardance/} and {@code orientation/}, each with its
     * own {@code TileConfiguration.txt}. Those are the results of this method; the five states
     * are its raw data. Declared here so stitching picks them up as outputs of their own.
     */
    @Override
    public List<String> getPostProcessingDirectorySuffixes() {
        // orientation_color: the display-only RGB image, hue = orientation (cyclic over 0..180
        // deg), brightness = retardance up to the display ceiling. QuPath cannot put a cyclic
        // color map on an image channel, so the server renders it.
        return List.of("retardance", "orientation", "orientation_color");
    }

    /**
     * Retardance opens at 0 to the display ceiling (the acquisition panel's "Full brightness
     * at", else {@code reconstruction.display_retardance_ceiling_nm}, default 20 nm,
     * OpenPolScope's on this instrument), stored as counts at 100 per nm -- so two samples' maps are on one scale instead
     * of each stretched to its own percentiles. Orientation opens at its full 0..180 deg range.
     */
    @Override
    public double[] getDisplayRangeForOutput(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("_orientation_color")) {
            return null; // RGB; no single-channel range
        }
        if (lower.contains("_retardance")) {
            return new double[] {0.0, displayCeilingNm() * 100.0};
        }
        if (lower.contains("_orientation")) {
            return new double[] {0.0, 18000.0};
        }
        return null;
    }

    @Override
    public boolean reconstructsFromBackground() {
        return true;
    }

    @Override
    public java.util.OptionalDouble retardanceCeilingNm() {
        return java.util.OptionalDouble.of(displayCeilingNm());
    }

    private static double displayCeilingNm() {
        // The acquisition panel's ceiling when one was set, else the config's, else 20 nm.
        return LCDisplaySettings.effectiveCeilingNm();
    }

    /**
     * Stable channel names for the reconstructed outputs, so display settings carry across
     * images. Values are stored as counts: retardance in nanometres x 100, orientation in
     * degrees x 100 over [0, 180).
     */
    @Override
    public String getChannelNameForOutput(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("_retardance")) {
            return "Retardance (nm x100)";
        }
        if (lower.contains("_orientation") && !lower.contains("_orientation_color")) {
            return "Orientation (deg x100)";
        }
        return null;
    }
}
