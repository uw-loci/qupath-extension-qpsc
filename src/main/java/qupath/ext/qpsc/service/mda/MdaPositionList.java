package qupath.ext.qpsc.service.mda;

import com.google.gson.annotations.SerializedName;
import java.util.List;

record MdaPositionList(@SerializedName("STAGE_POSITIONS") List<MdaMultiStagePosition> positions) {}
