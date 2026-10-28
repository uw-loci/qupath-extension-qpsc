package qupath.ext.qpsc.modality.lcpolscope.ui;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.lcpolscope.LCPolScopeModalityHandler;
import qupath.ext.qpsc.modality.lcpolscope.ui.LCPolScopeReadiness.Check;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.DialogOwner;
import qupath.ext.qpsc.ui.SaturationSummaryDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * The reminder an LC-PolScope acquisition shows before the stage locks: calibrate the liquid
 * crystals and collect a background on the slide being imaged.
 *
 * <p>The calibration and the background both describe the slide they were measured on, because
 * its glass, coverslip and mountant add retardance of their own. The OpenPolScope manual
 * (Pol-Acquisition 3.0, p. 9) advises recalibrating whenever the slide changes, near the area of
 * interest, with the background recorded straight after. A background from another slide gives
 * wrong retardance and orientation while the mosaic still looks clean, and nothing downstream can
 * tell. Until the acquisition does both steps itself, the operator is asked every time.
 *
 * <p>A multi-slide batch asks once, when Acquire All is pressed ({@link #confirmBatch}), and the
 * per-acquisition reminder stays quiet while the batch runs so an unattended run never stalls.
 */
public final class LCPolScopeSlideReminder {

    private static final Logger logger = LoggerFactory.getLogger(LCPolScopeSlideReminder.class);

    static final String TITLE = "LC-PolScope: this slide";

    enum Choice {
        ACQUIRE,
        OPEN_SETUP,
        CANCEL
    }

    private LCPolScopeSlideReminder() {}

    /**
     * Asks before one LC-PolScope acquisition. Returns true to go ahead: at once for any other
     * modality, and inside a multi-slide batch, which asked when it started. Callable from any
     * thread; blocks until answered.
     */
    public static boolean confirmBeforeAcquiring(String modality) {
        if (!shouldAsk(modality, SaturationSummaryDialog.isBatchActive())) {
            return true;
        }
        List<String> state = currentState(configManager(), LocalDateTime.now());
        Choice choice = onFxThread(
                () -> ask("Calibrate and collect a background on this slide first", singleBody(state), "Acquire"));
        return settle(choice, "acquisition");
    }

    /**
     * Asks once before a multi-slide batch that holds LC-PolScope slides. Returns true to go
     * ahead. Callable from any thread; blocks until answered.
     */
    public static boolean confirmBatch(int lcSlides) {
        if (lcSlides <= 0) {
            return true;
        }
        List<String> state = currentState(configManager(), LocalDateTime.now());
        Choice choice = onFxThread(() -> ask(
                "This batch uses one calibration and one background for every slide",
                batchBody(lcSlides, state),
                "Acquire the batch anyway"));
        return settle(choice, "multi-slide batch");
    }

    /** Whether to ask: only for LC-PolScope, and not inside a batch that already asked. */
    static boolean shouldAsk(String modality, boolean batchActive) {
        return LCPolScopeModalityHandler.isLcPolScope(modality) && !batchActive;
    }

    static String singleBody(List<String> state) {
        StringBuilder sb = new StringBuilder();
        sb.append("The calibration and the background describe the slide they were measured on: its glass, "
                + "coverslip and mountant add retardance of their own. For a new slide:\n\n"
                + "  1. Calibrate Liquid Crystals on a clear area of THIS slide, near the region, then Apply.\n"
                + "  2. Collect the background on a clear area of this slide, defocused 10-20 um.\n\n"
                + "Both are in LC-PolScope Setup. A calibration or background from another slide gives wrong "
                + "retardance and orientation, and the tiles still join cleanly, so nothing looks wrong.");
        appendState(sb, state);
        return sb.toString();
    }

    static String batchBody(int lcSlides, List<String> state) {
        StringBuilder sb = new StringBuilder();
        sb.append(lcSlides == 1 ? "One LC-PolScope slide" : lcSlides + " LC-PolScope slides")
                .append(" will be acquired with the calibration and background in use now. A batch cannot "
                        + "recalibrate or collect a background per slide yet, so every slide except the one they "
                        + "were measured on is corrected against another slide's glass, coverslip and mountant. "
                        + "Retardance and orientation can be off by a few nm and degrees without anything "
                        + "looking wrong.\n\n"
                        + "For quantitative work, acquire LC-PolScope slides one at a time, calibrating and "
                        + "collecting a background on each.");
        appendState(sb, state);
        return sb.toString();
    }

    private static void appendState(StringBuilder sb, List<String> state) {
        if (state.isEmpty()) {
            return;
        }
        sb.append("\n\nIn use now:");
        for (String line : state) {
            sb.append("\n  - ").append(line);
        }
    }

    /** The calibration and background lines of the readiness summary, as plain text. */
    static List<String> currentState(MicroscopeConfigManager mgr, LocalDateTime now) {
        List<String> out = new ArrayList<>();
        if (mgr == null) {
            return out;
        }
        try {
            for (Check c : LCPolScopeReadiness.configChecks(mgr, now)) {
                out.add(c.text());
            }
        } catch (Exception e) {
            logger.warn("LC-PolScope slide reminder: could not read the calibration state: {}", e.getMessage());
        }
        return out;
    }

    private static boolean settle(Choice choice, String what) {
        switch (choice) {
            case ACQUIRE -> {
                logger.info("LC-PolScope per-slide reminder: operator chose to go ahead with the {}", what);
                return true;
            }
            case OPEN_SETUP -> {
                logger.warn("LC-PolScope {} cancelled at the per-slide reminder; opening LC-PolScope Setup", what);
                Platform.runLater(LCPolScopeSetupWindow::show);
                return false;
            }
            default -> {
                logger.warn("LC-PolScope {} cancelled at the per-slide reminder", what);
                return false;
            }
        }
    }

    private static Choice ask(String header, String body, String goText) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(TITLE);
        alert.setHeaderText(header);
        Label label = new Label(body);
        label.setWrapText(true);
        label.setMaxWidth(560);
        VBox content = new VBox(label);
        content.setPadding(new Insets(4));
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(600);

        ButtonType go = new ButtonType(goText, ButtonBar.ButtonData.OK_DONE);
        ButtonType setup = new ButtonType("Open LC-PolScope Setup", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(go, setup, cancel);
        // Enter must not start an acquisition the operator has not read: Cancel is the default.
        ((Button) alert.getDialogPane().lookupButton(go)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true);

        DialogOwner.own(alert);
        var response = alert.showAndWait();
        if (response.isEmpty() || response.get() == cancel) {
            return Choice.CANCEL;
        }
        return response.get() == go ? Choice.ACQUIRE : Choice.OPEN_SETUP;
    }

    private static Choice onFxThread(Supplier<Choice> dialog) {
        if (Platform.isFxApplicationThread()) {
            return dialog.get();
        }
        AtomicReference<Choice> choice = new AtomicReference<>(Choice.CANCEL);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                choice.set(dialog.get());
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Choice.CANCEL;
        }
        return choice.get();
    }

    private static MicroscopeConfigManager configManager() {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        if (mgr == null) {
            String path = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (path != null && !path.isBlank()) {
                mgr = MicroscopeConfigManager.getInstance(path);
            }
        }
        return mgr;
    }
}
