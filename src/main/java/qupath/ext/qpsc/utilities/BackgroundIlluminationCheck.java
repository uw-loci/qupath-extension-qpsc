package qupath.ext.qpsc.utilities;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-acquisition check that flags a mismatch between the lamp/illumination
 * level of the acquisition profile about to be used and the level recorded when
 * the flat-field background was collected.
 *
 * <p>Flat-field (divide) correction assumes the spatial illumination pattern at
 * acquisition matches the pattern captured in the background reference. Changing
 * the profile's {@code illumination_intensity} (e.g. tuning the DiaLamp in Live)
 * without re-collecting the background leaves the two out of step, which shows up
 * as per-tile intensity/vignetting artifacts in the stitched image. The mismatch
 * is silent otherwise -- the background image still exists and correction still
 * runs, just against the wrong reference -- so this surfaces a one-shot warning
 * with a chance to cancel before the stage is locked.
 *
 * <p>Scope: only fires for lamp-based collections ({@code lamp.available: true}
 * in {@code background_settings.yml}, i.e. brightfield DiaLamp). Fluorescence LED
 * masters and v1.0 settings files without the lamp block are skipped. Mirrors
 * {@link AcquisitionSpaceCheck} in threading (latch + {@code Platform.runLater})
 * and dialog style.
 */
public final class BackgroundIlluminationCheck {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundIlluminationCheck.class);

    /** Lamp levels are discrete setpoints; treat anything beyond this as a real mismatch. */
    private static final double INTENSITY_TOLERANCE = 1.0;

    private BackgroundIlluminationCheck() {}

    /**
     * Compares the current profile illumination against the background's recorded
     * level and, on mismatch, shows a confirm dialog. Returns whether the caller
     * should proceed.
     *
     * <p>Returns {@code true} silently when there is nothing to compare (no
     * background found, no lamp, missing fields) or when the levels match. Only a
     * genuine mismatch prompts the user; cancelling returns {@code false}.
     *
     * @param cfg            config manager for the active microscope
     * @param baseModality   base modality name (e.g. {@code "Brightfield"})
     * @param objective      objective id in use
     * @param detector       detector id in use
     * @param wbMode         white balance mode selected for this acquisition
     * @param bgBaseFolder   base background-correction folder from config
     * @return true to proceed (match, nothing to compare, or user confirmed); false if cancelled
     */
    public static boolean checkAndWarn(
            MicroscopeConfigManager cfg,
            String baseModality,
            String objective,
            String detector,
            String wbMode,
            String bgBaseFolder) {

        if (cfg == null || baseModality == null || objective == null || detector == null || bgBaseFolder == null) {
            return true;
        }

        BackgroundSettingsReader.BackgroundSettings bg;
        try {
            bg = BackgroundSettingsReader.findBackgroundSettings(
                    bgBaseFolder, baseModality, objective, detector, wbMode);
        } catch (Exception e) {
            logger.warn("Background illumination check skipped -- could not read settings: {}", e.getMessage());
            return true;
        }
        if (bg == null) {
            // No background reference here; the missing-background case is handled
            // elsewhere (correction simply isn't applied). Nothing to compare.
            return true;
        }

        // Only meaningful for an adjustable lamp (brightfield DiaLamp). FL LED
        // masters and v1.0 files (lampAvailable == null) are out of scope.
        if (!Boolean.TRUE.equals(bg.lampAvailable)) {
            return true;
        }

        // Current profile illumination for the objective in use.
        String profileKey = cfg.resolveProfileKey(baseModality, objective);
        Double currentIllum = profileKey != null ? cfg.getProfileIlluminationIntensity(profileKey) : null;

        // Recorded level: prefer the profile illumination captured at collection,
        // fall back to the actually-applied lamp intensity.
        Double recordedIllum =
                bg.profileIlluminationIntensity != null ? bg.profileIlluminationIntensity : bg.appliedLampIntensity;

        if (currentIllum == null || recordedIllum == null) {
            logger.debug(
                    "Background illumination check skipped -- current={}, recorded={} (one is null)",
                    currentIllum,
                    recordedIllum);
            return true;
        }

        boolean profileChanged =
                profileKey != null && bg.profileKey != null && !profileKey.equalsIgnoreCase(bg.profileKey);
        boolean intensityMismatch = Math.abs(currentIllum - recordedIllum) > INTENSITY_TOLERANCE;

        if (!intensityMismatch && !profileChanged) {
            logger.info(
                    "Background illumination matches profile ({} == {} for profile '{}')",
                    recordedIllum,
                    currentIllum,
                    profileKey);
            return true;
        }

        logger.warn(
                "Background illumination MISMATCH: profile '{}' uses intensity {} but background "
                        + "(profile '{}') was collected at {}. Flat-field artifacts likely.",
                profileKey,
                currentIllum,
                bg.profileKey,
                recordedIllum);

        return showWarningDialog(profileKey, bg.profileKey, currentIllum, recordedIllum, bg.lampDeviceLabel);
    }

    private static boolean showWarningDialog(
            String profileKey, String bgProfileKey, double currentIllum, double recordedIllum, String lampLabel) {
        if (Platform.isFxApplicationThread()) {
            return showWarningDialogFx(profileKey, bgProfileKey, currentIllum, recordedIllum, lampLabel);
        }
        AtomicBoolean proceed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                proceed.set(showWarningDialogFx(profileKey, bgProfileKey, currentIllum, recordedIllum, lampLabel));
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return proceed.get();
    }

    private static boolean showWarningDialogFx(
            String profileKey, String bgProfileKey, double currentIllum, double recordedIllum, String lampLabel) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Background / Profile Illumination Mismatch");
        alert.setHeaderText("Flat-field background does not match the acquisition lamp level");

        String lamp = (lampLabel != null && !lampLabel.isEmpty()) ? lampLabel : "Lamp";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "%s intensity now:           %s%n" + "%s intensity at background:  %s%n%n",
                lamp, fmt(currentIllum), lamp, fmt(recordedIllum)));
        if (profileKey != null && bgProfileKey != null && !profileKey.equalsIgnoreCase(bgProfileKey)) {
            sb.append(String.format("Profile now: %s%nBackground profile: %s%n%n", profileKey, bgProfileKey));
        }
        sb.append("Flat-field correction divides each tile by this background. When the "
                + "illumination level differs, the correction is applied against the wrong "
                + "reference and tiles show intensity/vignetting seams.%n%n"
                + "Recommended: re-run Background Collection at the current profile level "
                + "before acquiring. Proceed anyway?");

        Label body = new Label(String.format(sb.toString()));
        body.setWrapText(true);
        body.setMaxWidth(520);

        VBox content = new VBox(12, body);
        content.setPadding(new Insets(4, 4, 4, 4));
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(560);

        ButtonType proceedButton = new ButtonType("Proceed Anyway", ButtonBar.ButtonData.YES);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(proceedButton, cancelButton);

        var response = alert.showAndWait();
        boolean proceed = response.isPresent() && response.get() == proceedButton;
        if (!proceed) {
            logger.info("Acquisition cancelled by user after background/profile illumination mismatch warning");
        }
        return proceed;
    }

    private static String fmt(double v) {
        return (v == Math.rint(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
