package qupath.ext.qpsc.service.mda;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ChannelExposure;
import qupath.ext.qpsc.modality.PresetRef;
import qupath.ext.qpsc.model.AcquisitionPlan;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Builds an {@link MdaWriteRequest} plus the matching {@link AcquisitionPlan}
 * from the live acquisition state. Shared between the auto-save path in
 * {@code AcquisitionManager} and the per-modality "Save as MicroManager MDA..."
 * dialog buttons so both code paths produce identical files.
 */
public final class MdaRequestBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MdaRequestBuilder.class);

    private MdaRequestBuilder() {}

    /** Bundle of the things both callers need: the request to feed the writer and the plan for the live panel. */
    public record Built(MdaWriteRequest request, AcquisitionPlan plan) {}

    /**
     * Build an {@link MdaWriteRequest} plus the matching {@link AcquisitionPlan}
     * for a single region's planned acquisition.
     *
     * @param regionDir       directory where the MDA files will be written (must already exist or be creatable).
     * @param regionName      label for this region (used in the {@code MDA_<region>.txt/.pos} filenames).
     * @param sample          {@link SampleSetupResult} for the current sample.
     * @param commandBuilder  the {@link AcquisitionCommandBuilder} already configured with Z, time-lapse,
     *                        channels, angles, and inner-axis state.
     * @param tilesStageUm    per-tile centroids in stage micrometers (already transformed). The list defines
     *                        position order; one MM {@code MultiStagePosition} per entry.
     * @param configManager   {@link MicroscopeConfigManager} for the active microscope (used for
     *                        {@code mm_stage_devices}).
     * @param channelLibrary  resolver from channel id to {@link Channel} for {@link PresetRef} and color
     *                        lookup. May be empty (or null) for PPM and other angle-only modalities;
     *                        in that case channels with a missing entry fall back to a degenerate
     *                        {@link ResolvedChannel} and a single WARN is logged per missing id.
     */
    public static Built build(
            Path regionDir,
            String regionName,
            SampleSetupResult sample,
            AcquisitionCommandBuilder commandBuilder,
            List<TileStagePos> tilesStageUm,
            MicroscopeConfigManager configManager,
            Map<String, Channel> channelLibrary) {

        if (regionDir == null) {
            throw new IllegalArgumentException("regionDir must be non-null");
        }
        if (commandBuilder == null) {
            throw new IllegalArgumentException("commandBuilder must be non-null");
        }

        String modalityBaseName = sample != null ? sample.modality() : commandBuilder.getScanType();
        boolean ppm = modalityBaseName != null
                && modalityBaseName.toLowerCase(Locale.ROOT).startsWith("ppm");

        List<ChannelExposure> channelExposures = commandBuilder.getChannelExposures();
        List<AngleExposure> angleExposures = commandBuilder.getAngleExposures();
        List<ResolvedChannel> resolvedChannels = ppm ? List.of() : resolveChannels(channelExposures, channelLibrary);
        List<AngleExposure> angles = angleExposures == null ? List.of() : List.copyOf(angleExposures);

        ZStackSpec zStack = null;
        int zCount = 1;
        if (commandBuilder.isZStackEnabled()
                && commandBuilder.getZStart() != null
                && commandBuilder.getZEnd() != null
                && commandBuilder.getZStep() != null
                && commandBuilder.getZStep() > 0.0) {
            double start = commandBuilder.getZStart();
            double end = commandBuilder.getZEnd();
            double step = commandBuilder.getZStep();
            zStack = new ZStackSpec(start, end, step);
            // Slice count matches MdaSettingsWriter.enumerateSlices: |range / step| + 1 inclusive.
            double signedStep = end >= start ? step : -step;
            int n = (int) Math.round((end - start) / signedStep) + 1;
            zCount = Math.max(1, n);
        }

        TimeLapseSpec timeLapse = null;
        int timepoints = Math.max(1, commandBuilder.getTimepoints());
        if (timepoints > 1) {
            timeLapse = new TimeLapseSpec(timepoints, commandBuilder.getIntervalSec());
        }

        String innerAxis = commandBuilder.getInnerAxis();
        if (innerAxis == null || innerAxis.isBlank()) {
            innerAxis = "z";
        }

        MmStageDevices stageDevices =
                configManager != null ? configManager.getMmStageDevices() : new MmStageDevices("XYStage", "ZStage");

        List<TileStagePos> tiles = tilesStageUm == null ? List.of() : List.copyOf(tilesStageUm);

        MdaWriteRequest request = new MdaWriteRequest(
                regionDir,
                regionName,
                modalityBaseName,
                sample,
                resolvedChannels,
                angles,
                zStack,
                timeLapse,
                tiles,
                stageDevices,
                Map.of());

        int chCount = resolvedChannels.size();
        int angleCount = angles.size();
        List<String> channelLabels =
                resolvedChannels.stream().map(ResolvedChannel::displayName).toList();
        List<Double> angleLabels = angles.stream().map(AngleExposure::ticks).toList();

        // AcquisitionPlan.compute requires chCount >= 1 on non-ppm plans and angleCount >= 1 on ppm
        // plans. Honor that by clamping to 1 when the corresponding list is empty (single-snap
        // fallback) so the dimension panel still renders meaningful totals.
        int planChCount = ppm ? chCount : Math.max(1, chCount);
        int planAngleCount = ppm ? Math.max(1, angleCount) : angleCount;

        AcquisitionPlan plan = AcquisitionPlan.compute(
                tiles.size(),
                planChCount,
                zCount,
                planAngleCount,
                timepoints,
                innerAxis,
                ppm,
                channelLabels,
                angleLabels);

        return new Built(request, plan);
    }

    private static List<ResolvedChannel> resolveChannels(
            List<ChannelExposure> channelExposures, Map<String, Channel> channelLibrary) {
        if (channelExposures == null || channelExposures.isEmpty()) {
            return List.of();
        }
        List<ResolvedChannel> out = new ArrayList<>(channelExposures.size());
        for (ChannelExposure ce : channelExposures) {
            Channel ch = channelLibrary == null ? null : channelLibrary.get(ce.channelId());
            if (ch == null) {
                logger.warn(
                        "Channel id '{}' not found in channel library; writing degenerate ResolvedChannel (no preset).",
                        ce.channelId());
                out.add(new ResolvedChannel(ce.channelId(), ce.channelId(), "", "", ce.exposureMs(), 0));
                continue;
            }
            String group = "";
            String preset = "";
            List<PresetRef> presets = ch.presets();
            if (presets != null && !presets.isEmpty()) {
                PresetRef pr = presets.get(0);
                group = pr.group();
                preset = pr.preset();
            }
            out.add(new ResolvedChannel(ch.id(), ch.displayName(), group, preset, ce.exposureMs(), 0));
        }
        return List.copyOf(out);
    }
}
