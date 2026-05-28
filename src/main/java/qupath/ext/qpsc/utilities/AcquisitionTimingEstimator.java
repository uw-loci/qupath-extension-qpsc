package qupath.ext.qpsc.utilities;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.ui.UIFunctions;

/**
 * Conservative pre-flight estimator for time-lapse acquisitions.
 *
 * <p>Flags configurations where the configured per-timepoint interval is
 * obviously shorter than the wall-clock cost of one timepoint -- e.g. 21 PPM
 * Z-planes at a 1 s interval. Catches the order-of-magnitude-wrong cases
 * (which the server only surfaces as a runtime warning after the run has
 * already started slipping) rather than trying to predict actual hardware
 * timing precisely.
 *
 * <p>Estimate is intentionally loose:
 * {@code planes * (angles * SECONDS_PER_FRAME + SECONDS_PER_PLANE_OVERHEAD)}.
 * Per-modality angle count is read from {@link ModalityHandler#getDefaultAngleCount()}
 * (1 for brightfield/mono, 4 for PPM). When this fires it almost always
 * indicates a misconfiguration; users can dismiss with "Proceed anyway" and
 * the choice is remembered for the rest of the session.
 */
public final class AcquisitionTimingEstimator {

    private static final Logger logger = LoggerFactory.getLogger(AcquisitionTimingEstimator.class);

    /** Seconds per camera frame (exposure + readout + debayer, rough lower bound). */
    private static final double SECONDS_PER_FRAME = 0.25;

    /** Per-plane fixed overhead: Z-move plus minimal settling. */
    private static final double SECONDS_PER_PLANE_OVERHEAD = 0.2;

    /** Session-scoped suppression of the slip warning per (modality|zPlanes|intervalSec). */
    private static final Set<String> SUPPRESSED_KEYS = Collections.synchronizedSet(new HashSet<>());

    private AcquisitionTimingEstimator() {}

    /**
     * Conservative lower-bound wall-clock seconds for a single timepoint.
     *
     * @param modality modality string (handler resolved via {@link ModalityRegistry})
     * @param zPlanes  number of Z planes per timepoint (treated as >=1)
     * @return estimated seconds for one timepoint's acquisition
     */
    public static double estimatePerTimepointSeconds(String modality, int zPlanes) {
        int planes = Math.max(1, zPlanes);
        int angles = 1;
        try {
            ModalityHandler h = ModalityRegistry.getHandler(modality);
            if (h != null) {
                angles = Math.max(1, h.getDefaultAngleCount());
            }
        } catch (Exception ex) {
            logger.debug("getDefaultAngleCount fallback for modality '{}': {}", modality, ex.getMessage());
        }
        return planes * (angles * SECONDS_PER_FRAME + SECONDS_PER_PLANE_OVERHEAD);
    }

    /**
     * Show a blocking warning dialog when the estimated per-timepoint duration
     * exceeds the configured interval, and let the user pick Adjust or Proceed.
     *
     * <p>No dialog is shown when {@code timepoints <= 1}, {@code intervalSec <= 0},
     * the estimate fits inside the interval, or the same
     * (modality, planes, interval) tuple was already confirmed via
     * "Proceed anyway" earlier in this session.
     *
     * <p>Must be called on the JavaFX application thread.
     *
     * @param parent      owner window for the modal (may be null)
     * @param modality    modality name
     * @param zPlanes     number of Z planes per timepoint
     * @param timepoints  number of timepoints
     * @param intervalSec configured inter-timepoint interval in seconds
     * @return true if the caller should proceed with the acquisition,
     *         false if the user picked "Adjust settings"
     */
    public static boolean confirmOrAdjustIfSlipping(
            Window parent, String modality, int zPlanes, int timepoints, double intervalSec) {
        if (timepoints <= 1 || intervalSec <= 0.0) {
            return true;
        }
        String modalityKey = modality != null ? modality : "(unknown)";
        double estimateSec = estimatePerTimepointSeconds(modalityKey, zPlanes);
        if (estimateSec <= intervalSec) {
            return true;
        }

        String key = String.format("%s|planes=%d|interval=%.3f", modalityKey, Math.max(1, zPlanes), intervalSec);
        if (SUPPRESSED_KEYS.contains(key)) {
            logger.info("Slip warning suppressed for this session: {}", key);
            return true;
        }

        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Interval too short for acquisition");
        alert.setHeaderText("Acquisition will fall behind schedule");
        alert.setContentText(String.format(
                "Modality: %s%n"
                        + "Z planes: %d%n"
                        + "Timepoints: %d%n"
                        + "Configured interval: %.2f s%n"
                        + "Estimated per-timepoint duration: ~%.1f s (conservative lower bound)%n%n"
                        + "Each timepoint takes longer than the interval allows, so "
                        + "timepoints will run back-to-back and the total wall-clock "
                        + "duration will far exceed (timepoints x interval).%n%n"
                        + "Adjust the interval or reduce planes / timepoints, or proceed anyway.",
                modalityKey, Math.max(1, zPlanes), timepoints, intervalSec, estimateSec));

        ButtonType adjust = new ButtonType("Adjust settings", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType proceed = new ButtonType("Proceed anyway", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(adjust, proceed);

        Optional<ButtonType> choice = UIFunctions.showAlertOverParent(alert, parent);
        boolean go = choice.isPresent() && choice.get() == proceed;
        if (go) {
            SUPPRESSED_KEYS.add(key);
            logger.warn(
                    "Slip warning: Proceed anyway (suppressed for session) - {} estimate={}s interval={}s",
                    modalityKey,
                    estimateSec,
                    intervalSec);
        } else {
            logger.info(
                    "Slip warning: Adjust settings - {} estimate={}s interval={}s",
                    modalityKey,
                    estimateSec,
                    intervalSec);
        }
        return go;
    }
}
