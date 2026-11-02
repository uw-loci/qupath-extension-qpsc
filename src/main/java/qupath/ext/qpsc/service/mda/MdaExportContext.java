package qupath.ext.qpsc.service.mda;

import java.util.List;
import java.util.Map;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.service.AcquisitionCommandBuilder;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Snapshot of everything {@link MdaExportAction#exportAndConfirm} needs to
 * write per-region MicroManager MDA file sets. Built lazily by the parent
 * acquisition dialog when the user clicks "Save as MicroManager MDA..." so
 * the snapshot reflects the dialog's CURRENT state (not the state at panel
 * construction time).
 *
 * @param sample         sample setup metadata (sampleName, projectsFolder, modality, objective, detector).
 * @param cmdBuilder     fully-configured acquisition command builder, exactly as the "Collect Regions"
 *                       path would build it. Used as the single source of truth for channel / Z / time-lapse /
 *                       inner-axis settings.
 * @param regions        one entry per region to export. The export action does NOT generate tiles --
 *                       the caller is responsible for providing pre-computed tiles in stage micrometers.
 * @param configManager  active {@link MicroscopeConfigManager}; required for
 *                       {@code mm_stage_devices} resolution.
 * @param channelLibrary channel id -> {@link Channel} (matches the resolution path
 *                       {@code AcquisitionManager.resolveChannelLibrary} uses).
 *                       May be empty for angle-only modalities (e.g. PPM).
 * @param errorMessage   if non-null/non-blank, the parent dialog has detected a precondition
 *                       failure (e.g. no regions selected, missing sample name). The export
 *                       button will surface this verbatim in an INFORMATION alert and skip
 *                       the write. All other fields may be null in this case.
 */
public record MdaExportContext(
        SampleSetupResult sample,
        AcquisitionCommandBuilder cmdBuilder,
        List<MdaExportAction.RegionPlan> regions,
        MicroscopeConfigManager configManager,
        Map<String, Channel> channelLibrary,
        String errorMessage) {

    /** Convenience constructor for the success path. */
    public static MdaExportContext ready(
            SampleSetupResult sample,
            AcquisitionCommandBuilder cmdBuilder,
            List<MdaExportAction.RegionPlan> regions,
            MicroscopeConfigManager configManager,
            Map<String, Channel> channelLibrary) {
        return new MdaExportContext(sample, cmdBuilder, regions, configManager, channelLibrary, null);
    }

    /** Convenience constructor for the "not ready yet" path; surfaces {@code message} in an INFO alert. */
    public static MdaExportContext notReady(String message) {
        return new MdaExportContext(null, null, null, null, null, message == null ? "" : message);
    }

    /**
     * Sentinel error message flagging that the user canceled the export flow (e.g. dismissed
     * the tile-FoV prompt). Callers should skip SILENTLY on {@link #isCancelled()} -- no alert.
     */
    public static final String CANCELLED_MARKER = "mda-export-cancelled";

    /** Context representing a user-cancelled export; handlers must return without alerting. */
    public static MdaExportContext cancelled() {
        return notReady(CANCELLED_MARKER);
    }

    /** True when the user canceled the export flow; handlers should return silently (no alert). */
    public boolean isCancelled() {
        return CANCELLED_MARKER.equals(errorMessage);
    }

    public boolean hasError() {
        return errorMessage != null && !errorMessage.isBlank();
    }
}
