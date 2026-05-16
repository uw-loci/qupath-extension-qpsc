package qupath.ext.qpsc.service.mda;

import java.util.List;
import java.util.Map;

record MdaMultiStagePosition(
        String label,
        String defaultXYStage,
        String defaultZStage,
        int gridRow,
        int gridColumn,
        Map<String, String> properties,
        List<MdaStagePosition> devicePositions) {}
