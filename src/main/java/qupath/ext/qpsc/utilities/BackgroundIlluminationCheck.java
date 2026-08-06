package qupath.ext.qpsc.utilities;

import java.util.ArrayList;
import java.util.List;
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
import qupath.ext.qpsc.modality.AngleExposure;

/**
 * Pre-acquisition check that flags a mismatch between the acquisition settings
 * about to be used and the settings recorded when the flat-field background was
 * collected.
 *
 * <p>Flat-field (divide) correction assumes the tile's illumination matches the
 * background reference. Two ways they drift:
 * <ul>
 *   <li><b>Illumination intensity</b> -- the profile's {@code illumination_intensity}
 *       (e.g. tuning the DiaLamp in Live and saving it back to the profile) was
 *       changed after the background was collected. Brightfield / lamp-based only.</li>
 *   <li><b>Exposure</b> -- the per-angle exposures the acquisition will use differ
 *       from those recorded in the background. In the common case both come from
 *       the same background calibration and match exactly; they diverge when the
 *       operator <em>overrides</em> an angle's exposure in the acquisition dialog.
 *       This is the meaningful case for PPM (which has no illumination intensity)
 *       and for brightfield with an exposure override.</li>
 * </ul>
 *
 * <p>Either mismatch is silent otherwise -- the background image still exists and
 * correction still runs, just against the wrong reference -- so this surfaces a
 * one-shot, non-blocking warning with a chance to cancel before the stage is
 * locked. Mirrors {@link AcquisitionSpaceCheck} in threading (latch +
 * {@code Platform.runLater}) and dialog style.
 */
public final class BackgroundIlluminationCheck {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundIlluminationCheck.class);

    /** Lamp levels are discrete setpoints; treat anything beyond this as a real mismatch. */
    private static final double INTENSITY_TOLERANCE = 1.0;

    /**
     * Exposure tolerance in ms. Recorded and acquisition exposures come from the
     * same background calibration in the no-override case, so they are equal; a
     * value beyond float noise means the operator overrode an exposure.
     */
    private static final double EXPOSURE_TOLERANCE_MS = 0.5;

    private BackgroundIlluminationCheck() {}

    /**
     * Compares the acquisition settings about to be used against the background's
     * recorded settings and, on mismatch, shows a confirm dialog. Returns whether
     * the caller should proceed.
     *
     * <p>Returns {@code true} silently when there is nothing to compare (no
     * background found, missing fields) or when everything matches. Only a genuine
     * mismatch (intensity, profile key, or per-angle exposure) prompts the user;
     * cancelling returns {@code false}.
     *
     * @param cfg                  config manager for the active microscope
     * @param baseModality         base modality name (e.g. {@code "Brightfield"}, {@code "PPM"})
     * @param objective            objective id in use
     * @param detector             detector id in use
     * @param wbMode               white balance mode selected for this acquisition
     * @param bgBaseFolder         base background-correction folder from config
     * @param currentAngleExposures the per-angle exposures the acquisition will use
     *                             (after any operator overrides); may be null/empty
     *                             to skip the exposure comparison
     * @return true to proceed (match, nothing to compare, or user confirmed); false if cancelled
     */
    public static boolean checkAndWarn(
            MicroscopeConfigManager cfg,
            String baseModality,
            String objective,
            String detector,
            String wbMode,
            String bgBaseFolder,
            List<AngleExposure> currentAngleExposures) {

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

        // --- Intensity (lamp-based / brightfield only) ---
        // Only meaningful for an adjustable lamp. FL LED masters and v1.0 files
        // (lampAvailable == null) contribute no intensity comparison, but we still
        // fall through to the exposure comparison below (which covers PPM).
        boolean lampBased = Boolean.TRUE.equals(bg.lampAvailable);
        String profileKey = cfg.resolveProfileKey(baseModality, objective);
        Double currentIllum = profileKey != null ? cfg.getProfileIlluminationIntensity(profileKey) : null;
        Double recordedIllum =
                bg.profileIlluminationIntensity != null ? bg.profileIlluminationIntensity : bg.appliedLampIntensity;
        boolean intensityMismatch = lampBased
                && currentIllum != null
                && recordedIllum != null
                && Math.abs(currentIllum - recordedIllum) > INTENSITY_TOLERANCE;

        boolean profileChanged =
                profileKey != null && bg.profileKey != null && !profileKey.equalsIgnoreCase(bg.profileKey);

        // --- Exposure (angle-based: brightfield + PPM) ---
        // Diverges only when the acquisition exposures (post-override) differ from
        // those the background was collected at.
        boolean exposureComparable = currentAngleExposures != null
                && !currentAngleExposures.isEmpty()
                && bg.angleExposures != null
                && !bg.angleExposures.isEmpty();
        // Subset-tolerant: acquiring FEWER angles than the background holds is fine;
        // we only flag a selected angle whose exposure differs from the background's
        // (an override) or that has no background reference at all.
        boolean exposureMismatch = exposureComparable
                && !BackgroundSettingsReader.validateAngleExposuresSubset(
                        bg, currentAngleExposures, EXPOSURE_TOLERANCE_MS);

        if (!intensityMismatch && !profileChanged && !exposureMismatch) {
            logger.info(
                    "Background matches acquisition settings (profile '{}', intensity {}, {} angle exposure(s))",
                    profileKey,
                    currentIllum,
                    exposureComparable ? currentAngleExposures.size() : 0);
            return true;
        }

        String lamp = (bg.lampDeviceLabel != null && !bg.lampDeviceLabel.isEmpty()) ? bg.lampDeviceLabel : "Lamp";
        StringBuilder sb = new StringBuilder();
        if (intensityMismatch) {
            logger.warn(
                    "Background illumination MISMATCH: profile '{}' uses intensity {} but background "
                            + "(profile '{}') was collected at {}.",
                    profileKey,
                    currentIllum,
                    bg.profileKey,
                    recordedIllum);
            sb.append(String.format(
                    "%s intensity now:           %s%n%s intensity at background:  %s%n%n",
                    lamp, fmt(currentIllum), lamp, fmt(recordedIllum)));
        }
        if (profileChanged) {
            sb.append(String.format("Profile now: %s%nBackground profile: %s%n%n", profileKey, bg.profileKey));
        }
        if (exposureMismatch) {
            logger.warn(
                    "Background exposure MISMATCH: acquisition angle exposures differ from those recorded "
                            + "in the background (profile '{}').",
                    bg.profileKey);
            sb.append("Per-angle exposure differs from the background:").append(String.format("%n"));
            sb.append(summarizeExposureDiff(bg.angleExposures, currentAngleExposures));
            sb.append(String.format("%n"));
        }
        sb.append("Flat-field correction divides each tile by this background. When the "
                + "illumination level or exposure differs, the correction is applied against the "
                + "wrong reference and tiles show intensity/vignetting seams.%n%n"
                + "Recommended: re-run Background Collection at the current settings before "
                + "acquiring. Proceed anyway?");

        return showWarningDialog(String.format(sb.toString()));
    }

    /**
     * Builds a compact per-angle "now vs. background" list for the selected angles
     * whose exposure differs (or that have no background reference), matched by
     * angle to tolerate a subset selection. Capped so the dialog stays readable.
     */
    private static String summarizeExposureDiff(List<AngleExposure> recorded, List<AngleExposure> current) {
        java.util.Map<Double, Double> recByAngle = new java.util.HashMap<>();
        for (AngleExposure ae : recorded) {
            recByAngle.put(ae.ticks(), ae.exposureMs());
        }
        List<AngleExposure> cur = new ArrayList<>(current);
        cur.sort((a, b) -> Double.compare(a.ticks(), b.ticks()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int extra = 0;
        for (AngleExposure ce : cur) {
            Double re = recByAngle.get(ce.ticks());
            boolean differs = (re == null) || Math.abs(re - ce.exposureMs()) > EXPOSURE_TOLERANCE_MS;
            if (!differs) {
                continue;
            }
            if (shown < 6) {
                if (re == null) {
                    sb.append(String.format(
                            "  %s deg: %s ms now, no background reference%n", fmt(ce.ticks()), fmt(ce.exposureMs())));
                } else {
                    sb.append(String.format(
                            "  %s deg: %s ms now vs %s ms at background%n",
                            fmt(ce.ticks()), fmt(ce.exposureMs()), fmt(re)));
                }
                shown++;
            } else {
                extra++;
            }
        }
        if (extra > 0) {
            sb.append(String.format("  ... and %d more%n", extra));
        }
        return sb.toString();
    }

    private static boolean showWarningDialog(String body) {
        if (Platform.isFxApplicationThread()) {
            return showWarningDialogFx(body);
        }
        AtomicBoolean proceed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                proceed.set(showWarningDialogFx(body));
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

    private static boolean showWarningDialogFx(String body) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Background / Acquisition Settings Mismatch");
        alert.setHeaderText("Flat-field background does not match the acquisition settings");

        Label bodyLabel = new Label(body);
        bodyLabel.setWrapText(true);
        bodyLabel.setMaxWidth(520);

        VBox content = new VBox(12, bodyLabel);
        content.setPadding(new Insets(4, 4, 4, 4));
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(560);

        ButtonType proceedButton = new ButtonType("Proceed Anyway", ButtonBar.ButtonData.YES);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(proceedButton, cancelButton);

        var response = alert.showAndWait();
        boolean proceed = response.isPresent() && response.get() == proceedButton;
        if (!proceed) {
            logger.info("Acquisition cancelled by user after background/acquisition settings mismatch warning");
        }
        return proceed;
    }

    private static String fmt(double v) {
        return (v == Math.rint(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
