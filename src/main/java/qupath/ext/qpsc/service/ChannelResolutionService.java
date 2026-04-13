package qupath.ext.qpsc.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ChannelExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;

/**
 * Resolves the final {@link ChannelExposure} list for an acquisition by
 * combining the modality's channel library with user-provided selections
 * and/or per-channel exposure overrides.
 *
 * <p>Returns an empty list if the modality is not channel-based (no channel
 * library) -- callers should then fall back to the angle-based path.
 *
 * <p>The {@code overrides} map is the one returned by
 * {@link ModalityHandler.BoundingBoxUI#getAngleOverrides()} when the UI is
 * the widefield channel picker: keys are channel ids and values are per-channel
 * exposures in milliseconds. Only selected channels appear as keys. An empty
 * or null map means "no selection -- use library defaults".
 */
public final class ChannelResolutionService {

    private static final Logger logger = LoggerFactory.getLogger(ChannelResolutionService.class);

    private ChannelResolutionService() {}

    /**
     * Resolves the channel acquisition sequence for a modality.
     *
     * @param modality   full modality identifier (e.g. {@code "Fluorescence_20x"})
     * @param objective  objective id (may be null)
     * @param detector   detector id (may be null)
     * @param overrides  user selection map (channelId -> exposureMs); may be null
     * @return ordered channel exposure list, or empty list if not channel-based
     */
    public static List<ChannelExposure> resolve(
            String modality, String objective, String detector, Map<String, Double> overrides) {
        ModalityHandler handler = ModalityRegistry.getHandler(modality);
        if (handler == null) {
            return List.of();
        }
        List<Channel> library;
        try {
            library = handler.getChannels(modality, objective, detector).join();
        } catch (Exception e) {
            logger.warn("Failed to resolve channel library for '{}': {}", modality, e.getMessage());
            return List.of();
        }
        if (library == null || library.isEmpty()) {
            return List.of();
        }

        // Index library by id for efficient lookup while preserving order.
        LinkedHashMap<String, Channel> byId = new LinkedHashMap<>();
        for (Channel c : library) {
            byId.put(c.id(), c);
        }

        List<ChannelExposure> result = new ArrayList<>();

        if (overrides == null) {
            // null means "no user selection" (master override off, or no UI at all)
            // -> acquire every library channel at its default exposure.
            for (Channel c : library) {
                result.add(new ChannelExposure(c.id(), c.defaultExposureMs()));
            }
        } else if (overrides.isEmpty()) {
            // Empty map means "user actively selected zero channels" -> caller MUST
            // refuse to start the acquisition. Return empty list so this is visible.
            logger.warn(
                    "Channel selection is empty for modality '{}' -- acquisition should be refused",
                    modality);
            return List.of();
        } else {
            // Use library order, but include only channels present in overrides.
            for (Channel c : library) {
                Double exposure = overrides.get(c.id());
                if (exposure != null && exposure > 0) {
                    result.add(new ChannelExposure(c.id(), exposure));
                }
            }
            if (result.isEmpty()) {
                logger.warn(
                        "Channel overrides map had {} entries but none matched library channels for '{}'. "
                                + "Falling back to full library.",
                        overrides.size(),
                        modality);
                for (Channel c : library) {
                    result.add(new ChannelExposure(c.id(), c.defaultExposureMs()));
                }
            }
        }
        logger.debug("Resolved {} channels for modality '{}'", result.size(), modality);
        return List.copyOf(result);
    }

    /**
     * Returns true if the modality has a channel library AND the caller's overrides
     * map is an empty selection (i.e. the user explicitly chose no channels). This is
     * the signal for the workflow to refuse the acquisition with a clear error.
     *
     * @param modality  full modality identifier
     * @param objective objective id (may be null)
     * @param detector  detector id (may be null)
     * @param overrides user selection map (may be null or empty)
     * @return true if the user actively deselected every channel and the workflow should block
     */
    public static boolean isEmptySelectionForChannelBasedModality(
            String modality, String objective, String detector, Map<String, Double> overrides) {
        if (overrides == null || !overrides.isEmpty()) {
            return false;
        }
        ModalityHandler handler = ModalityRegistry.getHandler(modality);
        if (handler == null) {
            return false;
        }
        try {
            List<Channel> library = handler.getChannels(modality, objective, detector).join();
            return library != null && !library.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
