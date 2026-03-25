package qupath.ext.qpsc.utilities;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.WbMode;

/**
 * Checks background collection validity for each white balance mode.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@code BackgroundCollectionController} -- shows per-mode status panel</li>
 *   <li>{@code UnifiedAcquisitionController} / {@code ExistingImageAcquisitionController}
 *       -- filters WB mode dropdown to only valid modes when background correction is enabled</li>
 * </ul>
 *
 * @author Mike Nelson
 * @since 4.1
 */
public class BackgroundValidityChecker {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundValidityChecker.class);

    /** Validity status for a WB mode's background collection. */
    public enum ValidityStatus {
        /** "Off" mode -- backgrounds never required. */
        NOT_NEEDED,
        /** Backgrounds exist and match current calibration. */
        VALID,
        /** No backgrounds have been collected for this mode. */
        NO_BACKGROUNDS,
        /** Calibration was updated after backgrounds were collected. */
        CALIBRATION_STALE
    }

    /** Validity result for a single WB mode. */
    public record WbModeValidity(WbMode mode, ValidityStatus status, String message) {}

    /**
     * Check validity of backgrounds for all WB modes for the given hardware.
     *
     * @param baseBackgroundFolder Base folder for background images (from config)
     * @param modality Modality name (e.g., "ppm")
     * @param objective Objective ID
     * @param detector Detector ID
     * @param configManager Config manager for reading calibration timestamps
     * @return List of validity results for each WB mode
     */
    public static List<WbModeValidity> checkAllModes(
            String baseBackgroundFolder,
            String modality,
            String objective,
            String detector,
            MicroscopeConfigManager configManager) {

        List<WbModeValidity> results = new ArrayList<>();

        // Find all existing background settings across all WB modes
        Map<String, BackgroundSettingsReader.BackgroundSettings> allBgs =
                BackgroundSettingsReader.findAllBackgroundSettings(baseBackgroundFolder, modality, objective, detector);

        for (WbMode mode : WbMode.values()) {
            results.add(checkSingleMode(mode, allBgs, modality, objective, detector, configManager));
        }

        return results;
    }

    /**
     * Get WB modes that have valid backgrounds (or don't need them).
     *
     * @return List of WbModes safe for acquisition with background correction enabled
     */
    public static List<WbMode> getValidModes(
            String baseBackgroundFolder,
            String modality,
            String objective,
            String detector,
            MicroscopeConfigManager configManager) {

        return checkAllModes(baseBackgroundFolder, modality, objective, detector, configManager).stream()
                .filter(v -> v.status() == ValidityStatus.NOT_NEEDED || v.status() == ValidityStatus.VALID)
                .map(WbModeValidity::mode)
                .collect(Collectors.toList());
    }

    private static WbModeValidity checkSingleMode(
            WbMode mode,
            Map<String, BackgroundSettingsReader.BackgroundSettings> allBgs,
            String modality,
            String objective,
            String detector,
            MicroscopeConfigManager configManager) {

        if (!mode.requiresBackgrounds()) {
            return new WbModeValidity(mode, ValidityStatus.NOT_NEEDED, "No backgrounds needed");
        }

        // Look up backgrounds for this specific WB mode
        BackgroundSettingsReader.BackgroundSettings bg = allBgs.get(mode.getProtocolName());
        if (bg == null) {
            return new WbModeValidity(mode, ValidityStatus.NO_BACKGROUNDS, "No backgrounds collected");
        }

        // Check if calibration is newer than background collection
        if (configManager != null) {
            String calibTs =
                    configManager.getWbCalibrationTimestamp(mode.getProtocolName(), modality, objective, detector);
            if (calibTs != null) {
                // Get the background file's last-modified time as a proxy for collection timestamp
                // (background_settings.yml is written at collection time)
                if (bg.settingsFilePath != null) {
                    try {
                        java.io.File settingsFile = new java.io.File(bg.settingsFilePath);
                        if (settingsFile.exists()) {
                            java.time.Instant fileModified = java.nio.file.Files.getLastModifiedTime(
                                            settingsFile.toPath())
                                    .toInstant();
                            LocalDateTime bgTime =
                                    LocalDateTime.ofInstant(fileModified, java.time.ZoneId.systemDefault());

                            // Parse calibration timestamp (ISO format from YAML)
                            LocalDateTime calibTime = parseTimestamp(calibTs);
                            if (calibTime != null && calibTime.isAfter(bgTime)) {
                                logger.info(
                                        "WB mode '{}': calibration ({}) is newer than background collection ({})",
                                        mode.getProtocolName(),
                                        calibTime,
                                        bgTime);
                                return new WbModeValidity(
                                        mode,
                                        ValidityStatus.CALIBRATION_STALE,
                                        "Calibration updated since last collection");
                            }
                        }
                    } catch (Exception e) {
                        logger.debug(
                                "Error comparing timestamps for WB mode '{}': {}",
                                mode.getProtocolName(),
                                e.getMessage());
                    }
                }
            }
        }

        return new WbModeValidity(mode, ValidityStatus.VALID, "Backgrounds match calibration");
    }

    /**
     * Parse an ISO-ish timestamp string from YAML. Handles both
     * {@code 2026-03-05T16:26:18.437422} and {@code 2026-03-05 16:26:18} formats.
     */
    private static LocalDateTime parseTimestamp(String ts) {
        if (ts == null || ts.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(ts);
        } catch (Exception e1) {
            try {
                // Try with space separator instead of T
                return LocalDateTime.parse(ts.replace(' ', 'T'));
            } catch (Exception e2) {
                logger.debug("Could not parse timestamp '{}': {}", ts, e2.getMessage());
                return null;
            }
        }
    }
}
