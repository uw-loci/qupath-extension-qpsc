package qupath.ext.qpsc.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.MultiSlideAcquisitionMode;

/**
 * Counts a multi-slide setup dialog down and then fires its primary button, so the
 * SETUP pass of a multi-slide batch can run without an operator.
 *
 * <h2>Why a session holder rather than threaded parameters</h2>
 * The five setup dialogs are a mix of {@link Dialog} and bespoke {@link Stage},
 * reached through four different call chains from
 * {@code MultiSlideExistingImageWorkflow} down through {@code ExistingImageWorkflowV2}.
 * There is no chokepoint to thread a mode through, and threading one would touch every
 * signature on those paths. Instead the multi-slide driver arms this class for the
 * duration of a batch and each dialog site asks whether it should attach. That mirrors
 * the ambient state {@code MultiSlideExistingImageWorkflow} already keeps for the batch
 * ({@code intendedSlotEntry}, {@code batchAbortAction}).
 *
 * <p>Consequence to respect: arming is global, so {@link #disarmSession()} MUST run on
 * every batch exit -- normal finish, abort, and failure alike -- or a later single-image
 * workflow would inherit auto-advance. The driver does this in a {@code finally}-equivalent
 * position; do not add an early return that skips it.
 *
 * <h2>Behavior per mode</h2>
 * <ul>
 *   <li>{@link MultiSlideAcquisitionMode#MANUAL} -- {@code attach} is a no-op.</li>
 *   <li>{@link MultiSlideAcquisitionMode#AUTOMATIC_WITH_OVERRIDE} -- counts down from the
 *       YAML-configured seconds, showing the remaining time in the button label. A
 *       deliberate interaction (mouse press or key press anywhere in the dialog) cancels
 *       the countdown and marks the CURRENT SLIDE overridden, so the remaining dialogs for
 *       that slide also wait. {@link #beginSlide()} clears the flag at the next slide.</li>
 *   <li>{@link MultiSlideAcquisitionMode#FULLY_AUTOMATIC} -- counts down from
 *       {@value #FULLY_AUTOMATIC_FLOOR_SECONDS}s (a legibility floor, so the operator can
 *       see the run stepping through rather than dialogs flashing past) and installs no
 *       override filter.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 * The countdown never fires a disabled button -- a disabled primary means the dialog is not
 * satisfiable (e.g. "Collect Regions" with no matching annotations), and firing would be a
 * no-op that leaves the batch waiting forever. In that case the countdown stops and the
 * slide is marked overridden so a human is asked. The countdown also only ever fires the
 * primary/confirm control it was given: never Cancel, Back, or a secondary action such as
 * "Save MDA...".
 *
 * <p>All methods must be called on the JavaFX Application Thread; {@code attach} logs and
 * no-ops otherwise rather than starting a timer on the wrong thread.
 */
public final class AutoAdvanceController {

    private static final Logger logger = LoggerFactory.getLogger(AutoAdvanceController.class);

    /**
     * Minimum countdown in FULLY_AUTOMATIC. Firing on the very next tick makes dialogs
     * appear and vanish faster than the eye can follow, which makes a stuck run
     * indistinguishable from a fast one. One second keeps the sequence readable at
     * negligible cost over a batch.
     */
    public static final int FULLY_AUTOMATIC_FLOOR_SECONDS = 1;

    private static volatile MultiSlideAcquisitionMode mode = MultiSlideAcquisitionMode.MANUAL;
    private static volatile int autoAdvanceSeconds = 0;
    private static volatile boolean overriddenThisSlide = false;

    private AutoAdvanceController() {}

    // ---- session control (called by the multi-slide driver) --------------------

    /**
     * Arm auto-advance for a batch. Call once at batch start, after reading the mode
     * preference and the YAML countdown.
     *
     * @param batchMode        resolved mode; {@code null} is treated as MANUAL
     * @param seconds          countdown from the scope YAML (ignored in FULLY_AUTOMATIC,
     *                         which uses {@value #FULLY_AUTOMATIC_FLOOR_SECONDS})
     */
    public static void armSession(MultiSlideAcquisitionMode batchMode, int seconds) {
        mode = (batchMode == null) ? MultiSlideAcquisitionMode.MANUAL : batchMode;
        autoAdvanceSeconds = Math.max(0, seconds);
        overriddenThisSlide = false;
        if (mode.isAutomatic()) {
            logger.info(
                    "Auto-advance ARMED: mode={} countdown={}s",
                    mode,
                    mode == MultiSlideAcquisitionMode.FULLY_AUTOMATIC
                            ? FULLY_AUTOMATIC_FLOOR_SECONDS
                            : autoAdvanceSeconds);
        }
    }

    /** Disarm auto-advance. MUST run on every batch exit path, including abort. */
    public static void disarmSession() {
        if (mode.isAutomatic()) {
            logger.info("Auto-advance DISARMED");
        }
        mode = MultiSlideAcquisitionMode.MANUAL;
        autoAdvanceSeconds = 0;
        overriddenThisSlide = false;
    }

    /** Clear the per-slide override. Call as each slot's setup begins. */
    public static void beginSlide() {
        if (overriddenThisSlide) {
            logger.info("Auto-advance resuming: new slide clears the operator override");
        }
        overriddenThisSlide = false;
    }

    /** True when a batch is running in one of the automatic modes. */
    public static boolean isArmed() {
        return mode.isAutomatic();
    }

    /** True when the operator has taken over the slide currently being set up. */
    public static boolean isOverriddenThisSlide() {
        return overriddenThisSlide;
    }

    /** The armed mode; MANUAL when not armed. */
    public static MultiSlideAcquisitionMode mode() {
        return mode;
    }

    // ---- attachment (called by each dialog site) -------------------------------

    /**
     * Attach a countdown to a {@link Dialog}, firing {@code primary} on expiry.
     * Call immediately before {@code show()} / {@code showAndWait()}, after the
     * button types have been added -- the primary button node does not exist before then.
     *
     * @param dialog  the dialog to auto-confirm
     * @param primary the confirm button type; never a cancel/back/secondary type
     */
    public static void attach(Dialog<?> dialog, ButtonType primary) {
        if (dialog == null || primary == null || !shouldAttach()) {
            return;
        }
        Node node = dialog.getDialogPane().lookupButton(primary);
        if (!(node instanceof Button button)) {
            logger.warn("Auto-advance: no button node for {}; dialog will wait for a human", primary.getText());
            return;
        }
        Countdown countdown = new Countdown(button, dialog.getDialogPane());
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, e -> countdown.stop());
        countdown.start();
    }

    /**
     * Attach a countdown to a bespoke {@link Stage} dialog, firing {@code primary} on expiry.
     * Call immediately before {@code show()}.
     *
     * @param stage   the window to auto-confirm
     * @param primary the confirm button; never a cancel button
     */
    public static void attach(Stage stage, Button primary) {
        if (stage == null || primary == null || !shouldAttach()) {
            return;
        }
        Node root = (stage.getScene() != null) ? stage.getScene().getRoot() : null;
        Countdown countdown = new Countdown(primary, root);
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> countdown.stop());
        countdown.start();
    }

    private static boolean shouldAttach() {
        if (!mode.isAutomatic() || overriddenThisSlide) {
            return false;
        }
        if (!Platform.isFxApplicationThread()) {
            logger.warn("Auto-advance: attach called off the FX thread; skipping (dialog will wait for a human)");
            return false;
        }
        return true;
    }

    /**
     * Hand the slide currently being set up back to the operator: cancels nothing by
     * itself, but every subsequent {@code attach} for this slide becomes a no-op, so the
     * remaining dialogs wait for a human. Cleared by the next {@link #beginSlide()}.
     *
     * <p>Called by the countdown on a deliberate interaction, and available to failure
     * paths that need a human decision on one slide without ending the batch.
     *
     * @param reason short phrase for the log, e.g. "SIFT confidence too low"
     */
    public static void overrideCurrentSlide(String reason) {
        if (!overriddenThisSlide) {
            overriddenThisSlide = true;
            logger.info("Auto-advance PAUSED for this slide: {}", reason);
        }
    }

    /**
     * One dialog's countdown: relabels the primary button each second, fires it on expiry,
     * and (in AUTOMATIC_WITH_OVERRIDE) cancels itself on the first deliberate interaction.
     */
    private static final class Countdown {

        private final Button button;
        private final Node interactionRoot;
        private final String originalText;
        private final int totalSeconds;
        private final Timeline timeline = new Timeline();
        private final EventHandler<MouseEvent> mouseFilter;
        private final EventHandler<KeyEvent> keyFilter;

        private int remaining;
        private boolean stopped = false;

        Countdown(Button button, Node interactionRoot) {
            this.button = button;
            this.interactionRoot = interactionRoot;
            this.originalText = button.getText();
            this.totalSeconds = (mode == MultiSlideAcquisitionMode.FULLY_AUTOMATIC)
                    ? FULLY_AUTOMATIC_FLOOR_SECONDS
                    : Math.max(1, autoAdvanceSeconds);
            this.remaining = totalSeconds;

            // Only AUTOMATIC_WITH_OVERRIDE yields to the operator. Use PRESSED rather than
            // hover/focus so drifting the pointer across the dialog is not an override.
            boolean allowOverride = (mode == MultiSlideAcquisitionMode.AUTOMATIC_WITH_OVERRIDE);
            this.mouseFilter = allowOverride ? e -> cancelForOverride("mouse press") : null;
            this.keyFilter = allowOverride ? e -> cancelForOverride("key press") : null;
        }

        void start() {
            relabel();
            if (interactionRoot != null && mouseFilter != null) {
                interactionRoot.addEventFilter(MouseEvent.MOUSE_PRESSED, mouseFilter);
                interactionRoot.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
            }
            timeline.getKeyFrames().add(new KeyFrame(Duration.seconds(1), e -> tick()));
            timeline.setCycleCount(totalSeconds);
            timeline.setOnFinished(e -> fire());
            timeline.play();
        }

        private void tick() {
            remaining--;
            relabel();
        }

        private void relabel() {
            button.setText(originalText + " (auto " + Math.max(remaining, 0) + "s)");
        }

        private void fire() {
            if (stopped) {
                return;
            }
            // A disabled primary means the dialog cannot be satisfied as configured
            // (e.g. no annotations match the selected classes). Firing would silently
            // do nothing and the batch would wait forever, so hand this slide back.
            if (button.isDisabled()) {
                stop();
                overrideCurrentSlide("primary action \"" + originalText + "\" is disabled");
                return;
            }
            stop();
            logger.info("Auto-advance firing \"{}\" after {}s", originalText, totalSeconds);
            button.fire();
        }

        private void cancelForOverride(String what) {
            if (stopped) {
                return;
            }
            stop();
            overrideCurrentSlide("operator " + what);
        }

        /** Idempotent: stop the timer, drop the filters, and restore the button label. */
        void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            timeline.stop();
            if (interactionRoot != null && mouseFilter != null) {
                interactionRoot.removeEventFilter(MouseEvent.MOUSE_PRESSED, mouseFilter);
                interactionRoot.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
            }
            button.setText(originalText);
        }
    }
}
