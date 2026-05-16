package qupath.ext.qpsc.service.mda;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.model.SampleSetupResult;

public record MdaWriteRequest(
        Path regionDir,
        String regionName,
        String modalityBaseName,
        SampleSetupResult sample,
        List<ResolvedChannel> channels,
        List<AngleExposure> angleExposures,
        ZStackSpec zStack,
        TimeLapseSpec timeLapse,
        List<TileStagePos> tiles,
        MmStageDevices stageDevices,
        Map<String, String> channelGroupOverrides) {}
