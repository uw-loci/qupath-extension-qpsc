package qupath.ext.qpsc.ui.acquisition;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.controller.workflow.StageLimitCheck;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;
import qupath.ext.qpsc.ui.DialogOwner;
import qupath.ext.qpsc.ui.UIFunctions;

/** Tells the operator which regions reach past the stage limits, and asks whether to go on. */
public final class StageLimitPrompt {

    private static final Logger logger = LoggerFactory.getLogger(StageLimitPrompt.class);

    private StageLimitPrompt() {}

    /** The configured stage limits {x low, x high, y low, y high}, or null when unavailable. */
    public static double[] configuredLimits() {
        try {
            MicroscopeConfigManager mgr =
                    MicroscopeConfigManager.getInstance(QPPreferenceDialog.getMicroscopeConfigFileProperty());
            return new double[] {
                mgr.getStageLimit("x", "low"),
                mgr.getStageLimit("x", "high"),
                mgr.getStageLimit("y", "low"),
                mgr.getStageLimit("y", "high")
            };
        } catch (RuntimeException e) {
            logger.warn("Stage limits unavailable; tiles not checked against them: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Asks whether to acquire anyway. Safe to call from any thread; blocks until the operator
     * answers.
     *
     * @param overruns the regions past the limits (non-empty)
     * @param when what is about to happen, e.g. "before acquiring" or "while setting up this slide"
     * @return true to continue anyway
     */
    public static boolean askToContinue(List<StageLimitCheck.RegionOverrun> overruns, String when) {
        overruns.forEach(r -> logger.warn("Stage limits {}: {}", when, r.describe()));
        if (Platform.isFxApplicationThread()) {
            return askFx(overruns);
        }
        AtomicBoolean proceed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                proceed.set(askFx(overruns));
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

    /**
     * For a run with nobody watching (a multi-slide acquire pass): logs and sends a notification,
     * and does not stop. The operator was asked during setup; if the run reaches the limit, its
     * tiles are held rather than deleted.
     */
    public static void warnUnattended(List<StageLimitCheck.RegionOverrun> overruns) {
        String text =
                overruns.stream().map(StageLimitCheck.RegionOverrun::describe).collect(Collectors.joining("\n"));
        logger.warn("Stage limits, unattended run continuing: {}", text);
        NotificationService.getInstance()
                .notify(
                        "Tiles past the stage limit",
                        text + "\nThe run continues; if it stops there, its tiles are kept for you to decide.",
                        NotificationPriority.HIGH,
                        NotificationEvent.ACQUISITION_WARNING);
    }

    private static boolean askFx(List<StageLimitCheck.RegionOverrun> overruns) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Tiles past the stage limit");
        alert.setHeaderText("Part of the acquisition is outside the stage's travel");
        String body =
                overruns.stream().map(StageLimitCheck.RegionOverrun::describe).collect(Collectors.joining("\n\n"))
                        + "\n\nThe stage cannot reach those tiles (stage.limits in the microscope configuration)."
                        + " Move or shrink the annotation, or continue: the acquisition will stop at the first"
                        + " such tile, and the tiles acquired up to then are kept for you to stitch or delete.";
        UIFunctions.setWrappedContent(alert, body, 560);
        ButtonType cont = new ButtonType("Continue anyway", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, cont);
        DialogOwner.own(alert);
        boolean go = alert.showAndWait().filter(cont::equals).isPresent();
        logger.info("Stage limit warning answered: {}", go ? "continue anyway" : "cancel");
        return go;
    }
}
