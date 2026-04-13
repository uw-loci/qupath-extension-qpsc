package qupath.ext.qpsc.modality;

/**
 * Pairs a {@link Channel} identifier with the exposure time to use for it in
 * a specific acquisition. Parallel to {@link AngleExposure}: where a
 * rotation-based modality builds a list of {@code AngleExposure}s for its
 * angle sequence, a channel-based modality builds a list of
 * {@code ChannelExposure}s for its channel sequence.
 *
 * <p>The channel id must reference a {@link Channel} previously declared in
 * the modality's channel library (see
 * {@link ModalityHandler#getChannels(String, String, String)}). The exposure
 * is per-acquisition and may differ from the channel's default.
 *
 * @param channelId  id of the channel being acquired (e.g. {@code "DAPI"})
 * @param exposureMs exposure time in milliseconds. Must be positive and finite
 */
public record ChannelExposure(String channelId, double exposureMs) {

    public ChannelExposure {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("ChannelExposure channelId must be non-blank");
        }
        if (exposureMs <= 0 || !Double.isFinite(exposureMs)) {
            throw new IllegalArgumentException(
                    "ChannelExposure exposureMs must be positive and finite, got: " + exposureMs);
        }
    }

    @Override
    public String toString() {
        return channelId + "," + exposureMs;
    }
}
