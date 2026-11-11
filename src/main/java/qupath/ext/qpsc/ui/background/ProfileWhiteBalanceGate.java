package qupath.ext.qpsc.ui.background;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.background.BackgroundProfiles;
import qupath.ext.qpsc.background.ProfileWhiteBalance;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;
import qupath.ext.qpsc.ui.DialogOwner;
import qupath.ext.qpsc.ui.UIFunctions;

/**
 * Asks before running with the scope-wide white balance because the active background profile
 * has none of its own.
 *
 * <p>Each background profile keeps its own white balance beside its backgrounds (see
 * {@link ProfileWhiteBalance}). When the active profile has none for the modality, objective,
 * detector and white balance mode, the run would use the scope-wide calibration, which is the
 * latest calibration of any profile and may have been made on a different carrier, glass or
 * medium. The operator chooses: use it this once, or cancel and calibrate the profile. An
 * unattended run (a multi-slide acquire pass) uses it, logs it and sends a notification.
 */
public final class ProfileWhiteBalanceGate {

    private static final Logger logger = LoggerFactory.getLogger(ProfileWhiteBalanceGate.class);

    private ProfileWhiteBalanceGate() {}

    /**
     * Whether to go ahead. True at once when the profile has its own white balance, or when the
     * mode or modality keeps none per profile. Safe from any thread; blocks while asking.
     *
     * @param wbMode the white balance mode's protocol name
     * @param what what is about to run, e.g. "Acquisition" or "Background collection"
     * @param unattended true when nobody is watching: use the scope-wide one and notify
     */
    public static boolean confirm(
            String modality, String objective, String detector, String wbMode, String what, boolean unattended) {
        MicroscopeConfigManager mgr;
        try {
            mgr = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
        } catch (RuntimeException e) {
            logger.debug("Profile white balance not checked: {}", e.getMessage());
            return true;
        }
        return confirmForProfile(
                mgr.getBackgroundProfileFolder(),
                BackgroundProfiles.activeId(),
                modality,
                objective,
                detector,
                wbMode,
                what,
                unattended);
    }

    /**
     * As {@link #confirm} for a named profile folder rather than the active one (background
     * collection files into the profile chosen in its dialog).
     */
    public static boolean confirmForProfile(
            String profileFolder,
            String profile,
            String modality,
            String objective,
            String detector,
            String wbMode,
            String what,
            boolean unattended) {
        if (!ProfileWhiteBalance.appliesTo(modality, wbMode)
                || ProfileWhiteBalance.exists(profileFolder, modality, objective, detector, wbMode)) {
            return true;
        }
        String scopeWide = null;
        try {
            scopeWide = MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty())
                    .getWbCalibrationTimestamp(wbMode, modality, objective, detector);
        } catch (RuntimeException e) {
            logger.debug("Scope-wide white balance date unavailable: {}", e.getMessage());
        }
        String message = message(profile, wbMode, objective, detector, scopeWide, what);
        if (unattended) {
            logger.warn("{} (unattended run: using the scope-wide white balance)", message);
            NotificationService.getInstance()
                    .notify(
                            "No white balance in background profile " + profile,
                            message + " The unattended run uses the scope-wide white balance.",
                            NotificationPriority.DEFAULT,
                            NotificationEvent.ACQUISITION_WARNING);
            return true;
        }
        logger.warn(message);
        final boolean scopeWideExists = scopeWide != null;
        if (Platform.isFxApplicationThread()) {
            return ask(message, scopeWideExists);
        }
        AtomicBoolean go = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                go.set(ask(message, scopeWideExists));
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
        return go.get();
    }

    /** The explanation shown and logged. Package-visible for tests. */
    static String message(
            String profile, String wbMode, String objective, String detector, String scopeWide, String what) {
        String mode = "simple".equals(wbMode) ? "simple" : "per-angle";
        return String.format(
                "Background profile '%s' has no %s white balance for %s / %s. %s will use the scope-wide"
                        + " white balance (%s), which may have been calibrated with another carrier, glass or"
                        + " medium. Calibrate this profile with Image Quality > JAI Camera > JAI White Balance Calibration to give it"
                        + " its own.",
                profile == null ? "(none)" : profile,
                mode,
                objective,
                detector,
                what,
                scopeWide == null ? "no calibration found" : "calibrated " + scopeWide);
    }

    private static boolean ask(String message, boolean scopeWideExists) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("No white balance in this background profile");
        alert.setHeaderText("This background profile has no white balance of its own");
        UIFunctions.setWrappedContent(alert, message, 560);
        ButtonType use = new ButtonType("Use scope-wide white balance", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, use);
        UIFunctions.neverDefaultButton(alert.getDialogPane().lookupButton(use));
        if (!scopeWideExists) {
            alert.getDialogPane().lookupButton(use).setDisable(true);
        }
        DialogOwner.own(alert);
        boolean go = alert.showAndWait().filter(use::equals).isPresent();
        logger.info("No profile white balance: {}", go ? "using the scope-wide white balance" : "canceled");
        return go;
    }
}
