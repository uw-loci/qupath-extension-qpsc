package qupath.ext.qpsc.ui.components;

import java.awt.Toolkit;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.ManualFocusHandler;
import qupath.ext.qpsc.ui.QpscWindows;
import qupath.ext.qpsc.ui.ThemeColors;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.ext.qpsc.ui.UserErrors;
import qupath.ext.qpsc.ui.acquisition.AutoAdvanceController;
import qupath.ext.qpsc.ui.focus.AutofocusCurvePane;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;

/**
 * Workflow dialogs that need preferences, services or other dialogs: the progress window, the
 * stage alignment confirmation, the manual focus prompt and the completion beep. Kept out of
 * {@link UIFunctions} so that class stays usable from code below the UI.
 */
public final class WorkflowDialogs {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(WorkflowDialogs.class);

    private WorkflowDialogs() {}

    public static class ProgressHandle {
        private final Stage stage;
        private final Timeline timeline;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private Consumer<Void> cancelCallback;

        public ProgressHandle(Stage stage, Timeline timeline) {
            this.stage = stage;
            this.timeline = timeline;
        }

        public void close() {
            logger.info("ProgressHandle.close() called.");
            Platform.runLater(() -> {
                timeline.stop();
                stage.close();
            });
        }

        public void setCancelCallback(Consumer<Void> callback) {
            this.cancelCallback = callback;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        void triggerCancel() {
            cancelled.set(true);
            if (cancelCallback != null) {
                cancelCallback.accept(null);
            }
        }
    }

    /**
     * Show a progress bar with cancel button that watches an AtomicInteger and updates itself periodically.
     * The progress bar will stay open until either:
     * - All expected files are found (progress reaches 100%)
     * - The timeout is reached with no progress
     * - The user clicks Cancel
     * - It is explicitly closed via the returned handle
     *
     * @param progressCounter Thread-safe counter (incremented externally as work completes).
     * @param totalFiles      The max value of progressCounter (raw image file count).
     * @param timeoutMs       If no progress for this many ms, bar will auto-terminate.
     * @param showCancelButton Whether to show a cancel button
     * @param stepsPerPosition Number of image files written per physical
     *                         XY position. Used only for the user-facing
     *                         label text ("Positions acquired: N of M") --
     *                         the progress bar itself still advances per
     *                         file for smooth updates. Pass 1 for
     *                         single-shot modalities (brightfield),
     *                         channelCount for widefield IF, angleCount
     *                         for PPM, or their product when combined.
     * @return a ProgressHandle you can .close() when you're done, and set cancel callback on
     */
    public static ProgressHandle showProgressBarAsync(
            AtomicInteger progressCounter,
            int totalFiles,
            int timeoutMs,
            boolean showCancelButton,
            int stepsPerPosition) {

        final ProgressHandle[] handleHolder = new ProgressHandle[1];
        final CountDownLatch handleReady = new CountDownLatch(1);
        final int stepsPerPositionFinal = Math.max(1, stepsPerPosition);
        final int totalPositions = totalFiles / stepsPerPositionFinal;

        Platform.runLater(() -> {
            logger.info(
                    "Creating progress bar UI on FX thread for {} total files ({} positions x {} steps)",
                    totalFiles,
                    totalPositions,
                    stepsPerPositionFinal);
            Stage stage = new Stage();
            QpscWindows.track(stage);
            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(300);
            Label timeLabel = new Label("Estimating time...");
            Label progressLabel = new Label("Positions acquired: 0 of " + totalPositions);
            Label statusLabel = new Label("Acquisition in progress...");

            // The microscope's autofocus searches, drawn as they run: the initial focus can take
            // minutes before the first position, with nothing else moving here.
            AutofocusCurvePane autofocusPane = new AutofocusCurvePane();
            // With every autofocus disabled, say so instead of replaying an earlier search.
            boolean autofocusOff = qupath.ext.qpsc.preferences.QPPreferenceDialog.getDisableAllAutofocus();
            autofocusPane.setVisible(autofocusOff);
            autofocusPane.setManaged(autofocusOff);
            if (autofocusOff) {
                autofocusPane.showAutofocusOff();
            }

            VBox vbox = new VBox(10, progressBar, progressLabel, timeLabel, statusLabel, autofocusPane);
            vbox.setStyle("-fx-padding: 10;");
            vbox.setAlignment(Pos.CENTER);

            // Add cancel button if requested
            final Button cancelButton;
            if (showCancelButton) {
                cancelButton = new Button("Cancel Acquisition");
                cancelButton.setPrefWidth(150);
                vbox.getChildren().addAll(new Separator(), cancelButton);
            } else {
                cancelButton = null;
            }

            stage.initModality(Modality.NONE);
            stage.setTitle("Microscope Acquisition Progress");
            stage.setScene(new Scene(vbox));
            stage.setAlwaysOnTop(true);
            stage.show();

            Runnable stopAutofocusFeed = autofocusOff
                    ? () -> {}
                    : qupath.ext.qpsc.service.autofocus.AutofocusFeed.getInstance()
                            .subscribe(e -> {
                                if (!autofocusPane.isVisible()) {
                                    autofocusPane.setVisible(true);
                                    autofocusPane.setManaged(true);
                                    stage.sizeToScene();
                                }
                                autofocusPane.accept(e);
                            });
            stage.showingProperty().addListener((obs, was, showing) -> {
                if (!showing) stopAutofocusFeed.run();
            });

            // Shared timing state
            AtomicLong startTime = new AtomicLong(0);
            AtomicLong lastProgressTime = new AtomicLong(System.currentTimeMillis());
            AtomicInteger lastSeenProgress = new AtomicInteger(0);

            // Build the Timeline
            final Timeline timeline = new Timeline();
            KeyFrame keyFrame = new KeyFrame(Duration.millis(500), evt -> {
                int current = progressCounter.get();
                long now = System.currentTimeMillis();

                // Log every 10th check to avoid spam
                if (evt.getSource() instanceof Timeline) {
                    Timeline tl = (Timeline) evt.getSource();
                    if (tl.getCycleCount() % 10 == 0) {
                        logger.debug(
                                "Progress bar reading counter (id: {}): value = {}",
                                System.identityHashCode(progressCounter),
                                current);
                    }
                }

                // Log progress updates
                if (current != lastSeenProgress.get()) {
                    logger.debug("PROGRESS UPDATE: {} of {} files", current, totalFiles);
                    lastSeenProgress.set(current);
                    lastProgressTime.set(now);
                }

                // Record start time once work begins
                if (current > 0 && startTime.get() == 0) {
                    startTime.set(now);
                }

                // Update UI. Progress bar fraction uses raw file count
                // for smooth per-image advancement. Label text shows
                // physical XY positions by dividing by stepsPerPosition
                // -- this is what the user actually cares about ("how
                // many stage stops remain") and matches the stage
                // movement cadence rather than the image-write cadence.
                double fraction = totalFiles > 0 ? current / (double) totalFiles : 0.0;
                progressBar.setProgress(fraction);
                int positionsDone = current / stepsPerPositionFinal;
                progressLabel.setText("Positions acquired: " + positionsDone + " of " + totalPositions);

                // Update status message based on progress
                if (current == 0) {
                    statusLabel.setText("Waiting for acquisition to start...");
                } else if (current < totalFiles) {
                    statusLabel.setText("Acquiring positions...");
                } else {
                    statusLabel.setText("Acquisition complete!");
                }

                // Calculate time estimate
                if (startTime.get() > 0 && current > 0 && current < totalFiles) {
                    long elapsed = now - startTime.get();
                    long remMs = (long) ((elapsed / (double) current) * (totalFiles - current));

                    // Format time more nicely
                    long remSec = remMs / 1000;
                    if (remSec < 60) {
                        timeLabel.setText("Time remaining: " + remSec + " seconds");
                    } else {
                        long minutes = remSec / 60;
                        long seconds = remSec % 60;
                        timeLabel.setText(String.format("Time remaining: %d min %d sec", minutes, seconds));
                    }
                } else if (current >= totalFiles) {
                    long totalTime = now - startTime.get();
                    long totalSec = totalTime / 1000;
                    if (totalSec < 60) {
                        timeLabel.setText("Completed in " + totalSec + " seconds");
                    } else {
                        long minutes = totalSec / 60;
                        long seconds = totalSec % 60;
                        timeLabel.setText(String.format("Completed in %d min %d sec", minutes, seconds));
                    }
                }

                // Check if canceled
                if (handleHolder[0] != null && handleHolder[0].isCancelled()) {
                    logger.info("Progress bar canceled by user");
                    statusLabel.setText("Acquisition canceled");
                    statusLabel.setTextFill(Color.RED);
                    timeline.stop();
                    // Keep window open for 2 seconds to show canceled status
                    PauseTransition pause = new PauseTransition(Duration.seconds(2));
                    pause.setOnFinished(e -> stage.close());
                    pause.play();
                    return;
                }

                // Check completion conditions
                boolean complete = (current >= totalFiles);
                boolean stalled = false;

                // Only check for stall if we haven't made progress in a while
                // AND we haven't reached completion
                if (!complete && current > 0) {
                    long timeSinceProgress = now - lastProgressTime.get();
                    stalled = timeSinceProgress > timeoutMs;

                    if (stalled) {
                        logger.warn(
                                "Progress stalled: no new files for {} ms (current: {}, total: {})",
                                timeSinceProgress,
                                current,
                                totalFiles);
                        statusLabel.setText("Timeout - acquisition may have stalled");
                        statusLabel.setTextFill(Color.RED);
                    }
                }

                // Close only when complete or truly stalled
                if (complete || stalled) {
                    logger.info(
                            "Progress bar closing - complete: {}, stalled: {}, files: {}/{}",
                            complete,
                            stalled,
                            current,
                            totalFiles);
                    timeline.stop();

                    // Show final status for a moment before closing
                    if (complete) {
                        PauseTransition pause = new PauseTransition(Duration.seconds(1));
                        pause.setOnFinished(e -> stage.close());
                        pause.play();
                    } else {
                        stage.close();
                    }
                }
            });
            timeline.getKeyFrames().add(keyFrame);
            timeline.setCycleCount(Timeline.INDEFINITE);

            // Create and store the ProgressHandle
            ProgressHandle handle = new ProgressHandle(stage, timeline);
            handleHolder[0] = handle;

            // Set up cancel button action if present
            if (cancelButton != null) {
                cancelButton.setOnAction(e -> {
                    logger.info("Cancel button clicked");
                    cancelButton.setDisable(true);
                    cancelButton.setText("Cancelling...");
                    handle.triggerCancel();
                });
            }

            logger.info("Starting progress Timeline on FX thread");
            timeline.play();
            // Signal the calling thread that the handle is published.
            handleReady.countDown();
        });

        // Wait for the FX thread to build and publish the handle (this method
        // is called off the FX thread; a latch replaces the former busy-wait spin).
        try {
            handleReady.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("Progress bar initialized for {} files with {} ms timeout", totalFiles, timeoutMs);
        return handleHolder[0];
    }

    /**
     * Backward-compatible overload. Assumes each image file is its own
     * "position" (stepsPerPosition = 1) -- correct for single-shot
     * modalities like plain brightfield. Channel-based (widefield IF)
     * and angle-based (PPM) callers should use the 5-arg form above to
     * get accurate position counts in the progress label.
     */
    public static ProgressHandle showProgressBarAsync(
            AtomicInteger progressCounter, int totalFiles, int timeoutMs, boolean showCancelButton) {
        return showProgressBarAsync(progressCounter, totalFiles, timeoutMs, showCancelButton, 1);
    }

    public static ProgressHandle showProgressBarAsync(AtomicInteger progressCounter, int totalFiles, int timeoutMs) {
        return showProgressBarAsync(progressCounter, totalFiles, timeoutMs, false, 1);
    }

    /**
     * Non-blocking async version of stage alignment confirmation.
     * Shows a non-modal, always-on-top dialog and completes the returned future
     * when the user confirms or cancels. Safe to call from the FX thread.
     *
     * @return CompletableFuture that completes with true if confirmed, false if canceled
     */
    public static CompletableFuture<Boolean> stageAlignmentConfirmAsync() {
        return stageAlignmentConfirmAsync(null, null, null, null);
    }

    /**
     * SIFT-enabled variant of {@link #stageAlignmentConfirmAsync()}.
     *
     * <p>When {@code siftTargetTile} is non-null, an "Auto-Align (SIFT)"
     * + Settings... button row is added above the Confirm/Cancel buttons.
     * Clicking SIFT runs {@link SiftAutoAlignHelper#autoAlign} against the
     * given tile and moves the stage to the matched position; the user
     * still clicks Confirm to commit. When {@code siftTargetTile} is null,
     * the dialog renders as a plain tile-selection dialog.
     *
     * @param gui              QuPath GUI (for image access). May be null
     *                         if {@code siftTargetTile} is null.
     * @param siftTargetTile   tile to match SIFT against. Pass null to
     *                         hide the SIFT button row.
     * @param headerText       custom header (defaults to "Is the current
     *                         position accurate?")
     * @param instructionText  custom instruction (defaults to "Compare
     *                         with the uManager live view.")
     */
    public static CompletableFuture<Boolean> stageAlignmentConfirmAsync(
            QuPathGUI gui, PathObject siftTargetTile, String headerText, String instructionText) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Runnable showDialog = () -> {
            Stage stage = new Stage();
            QpscWindows.track(stage);
            stage.initModality(Modality.NONE);
            stage.setTitle("Position Confirmation");
            stage.setAlwaysOnTop(true);

            VBox layout = new VBox(10);
            layout.setPadding(new Insets(20));
            layout.setAlignment(Pos.CENTER);

            Label headerLabel = new Label(headerText != null ? headerText : "Is the current position accurate?");
            headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

            Label instructionLabel =
                    new Label(instructionText != null ? instructionText : "Compare with the uManager live view.");
            instructionLabel.setWrapText(true);
            instructionLabel.setMaxWidth(440);
            instructionLabel.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

            HBox buttonBox = new HBox(10);
            buttonBox.setAlignment(Pos.CENTER);

            Button confirmButton = new Button("Current Position is Accurate");
            confirmButton.setDefaultButton(true);
            confirmButton.setOnAction(e -> {
                if (siftTargetTile != null) {
                    qupath.ext.qpsc.ui.alignment.SiftAutoAlignHelper.clearSearchRangeOnStageMap();
                }
                stage.close();
                future.complete(true);
            });

            Button cancelButton = new Button("Cancel acquisition");
            cancelButton.setCancelButton(true);
            cancelButton.setOnAction(e -> {
                if (siftTargetTile != null) {
                    qupath.ext.qpsc.ui.alignment.SiftAutoAlignHelper.clearSearchRangeOnStageMap();
                }
                stage.close();
                future.complete(false);
            });

            buttonBox.getChildren().addAll(confirmButton, cancelButton);

            // Optional SIFT button row + status label above the
            // confirm/cancel pair when the caller supplied a target tile.
            if (siftTargetTile != null && gui != null) {
                Label siftStatus = new Label();
                siftStatus.setWrapText(true);
                siftStatus.setMaxWidth(440);
                siftStatus.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                siftStatus.setStyle("-fx-font-size: 10px;");
                // A SIFT error is several lines long; grow the window to show all of it.
                siftStatus.textProperty().addListener((obs, was, now) -> Platform.runLater(stage::sizeToScene));
                HBox siftRow = qupath.ext.qpsc.ui.alignment.SiftAutoAlignHelper.buildSiftButtonRow(
                        gui, siftTargetTile, stage, siftStatus);
                layout.getChildren()
                        .addAll(
                                headerLabel,
                                qupath.ext.qpsc.ui.alignment.SiftAutoAlignHelper.buildFocusSaturationWarning(),
                                instructionLabel,
                                siftRow,
                                siftStatus,
                                new Separator(),
                                buttonBox);
                // Show the SIFT search range on the Stage Map (no-op if it isn't
                // open) for the whole time this dialog is up. Cleared on close below.
                qupath.ext.qpsc.ui.alignment.SiftAutoAlignHelper.drawSearchRangeAtCurrentPosition(gui, siftTargetTile);
            } else {
                layout.getChildren().addAll(headerLabel, instructionLabel, new Separator(), buttonBox);
            }

            // Sized to its content: a fixed size would cut the focus warning, the
            // instructions and a SIFT error to one ellipsized line each.
            layout.setPrefWidth(siftTargetTile != null ? 480 : 380);
            Scene scene = new Scene(layout);
            stage.setScene(scene);

            stage.setOnCloseRequest(e -> {
                if (siftTargetTile != null) {
                    qupath.ext.qpsc.ui.alignment.SiftAutoAlignHelper.clearSearchRangeOnStageMap();
                }
                future.complete(false);
            });

            // Multi-slide auto-advance: no-op unless a batch armed an automatic mode.
            // Reached by both the single alignment confirm and every per-tile confirm in
            // multi-tile refinement, so one attachment covers both.
            AutoAdvanceController.attach(stage, confirmButton);

            stage.centerOnScreen();
            stage.show();
        };

        if (Platform.isFxApplicationThread()) {
            showDialog.run();
        } else {
            Platform.runLater(showDialog);
        }

        return future;
    }

    /**
     * Shows a blocking dialog requesting manual focus from the user.
     * Used when autofocus fails and manual intervention is required.
     * Provides three options: retry autofocus, use current focus, or cancel.
     *
     * @param retriesRemaining Number of autofocus retries remaining (0 means no retries left)
     * @return ManualFocusResult indicating user's choice
     */
    public static ManualFocusHandler.ManualFocusResult showManualFocusDialog(int retriesRemaining) {
        // Play system beep to alert user that attention is needed
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (Exception e) {
            // Ignore beep failures - not critical
        }

        // Send push notification so user knows even if away from workstation
        qupath.ext.qpsc.service.notification.NotificationService.getInstance()
                .notify(
                        "Manual Focus Required",
                        "Autofocus failed during acquisition. User intervention needed at the microscope."
                                + (retriesRemaining > 0 ? " (" + retriesRemaining + " retries remaining)" : ""),
                        qupath.ext.qpsc.service.notification.NotificationPriority.URGENT,
                        qupath.ext.qpsc.service.notification.NotificationEvent.ACQUISITION_ERROR);

        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Manual Focus Required");
        alert.setHeaderText("Autofocus Failed");

        // Update message based on retries remaining
        String message;
        if (retriesRemaining > 0) {
            message = "Autofocus was unable to find a reliable focus position.\n\n"
                    + "Focus the microscope on the tissue -- at the scope, or with the Live Viewer's "
                    + "Z controls, which stay unlocked while this dialog is open -- then choose:\n\n"
                    + "- Retry Autofocus - Run autofocus again after manual adjustment ("
                    + retriesRemaining + " retries left)\n"
                    + "- Use Current Focus - Accept current focus and continue\n"
                    + "- Cancel - Stop the acquisition";
        } else {
            message = "Autofocus was unable to find a reliable focus position after all retry attempts.\n\n"
                    + "Focus the microscope on the tissue -- at the scope, or with the Live Viewer's "
                    + "Z controls, which stay unlocked while this dialog is open -- then choose:\n\n"
                    + "- Use Current Focus - Accept current focus and continue\n"
                    + "- Cancel - Stop the acquisition";
        }
        // Append a generic strategy hint -- the manual-focus path is server-
        // initiated and does not carry a specific failure reason, but
        // pointing the operator at the strategy controls is still useful
        // when AF repeatedly fails because the sample type does not match
        // the modality's bound strategy (e.g. pollen on a tissue strategy
        // or vice versa). The hint footer matches the language used by
        // streaming AF refusals so operators see consistent guidance.
        message += "\n\n"
                + "If autofocus keeps failing on a sample type that worked previously,\n"
                + "the bound AF strategy may not fit. Common mismatches:\n"
                + "- Sparse particles (beads, pollen) with a dense-texture strategy.\n"
                + "- Dense tissue with a sparse-signal strategy.\n\n"
                + "To change focus strategy:\n"
                + "- This run only: acquisition wizard's Advanced panel -> AF strategy dropdown.\n"
                + "- Persistent: Settings -> Autofocus Configuration -> Modality Bindings.";
        // The hint above tells the operator where the AF settings live; this button opens
        // that editor directly. It sits in the content area rather than the button bar so it
        // reads as a second row and, crucially, does NOT dismiss the dialog -- the
        // acquisition is still waiting on a Retry / Use Current / Cancel answer.
        Label messageLabel = new Label(message);
        messageLabel.setWrapText(true);
        Button openAfConfigBtn = new Button("Open Autofocus Configuration...");
        openAfConfigBtn.setTooltip(new javafx.scene.control.Tooltip(
                "Open the Autofocus Configuration editor without answering this dialog, "
                        + "so you can change the bound strategy before retrying."));
        openAfConfigBtn.setOnAction(evt -> {
            try {
                qupath.ext.qpsc.controller.QPScopeController.getInstance().startWorkflow("autofocusEditor");
            } catch (Exception ex) {
                logger.error("Could not open the Autofocus Configuration editor", ex);
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("Autofocus Configuration");
                err.setHeaderText("Could not open the editor");
                err.setContentText(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                UserErrors.logAlert(logger, err);
                UIFunctions.showAlertOverParent(
                        err, alert.getDialogPane().getScene().getWindow());
            }
        });
        HBox afConfigRow = new HBox(openAfConfigBtn);
        afConfigRow.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(10, messageLabel, afConfigRow);
        content.setPrefWidth(560);
        alert.getDialogPane().setContent(content);
        // Modality.NONE so the user can actually interact with the Live
        // Viewer focus controls -- the whole point of this dialog is to
        // prompt a manual-focus action that requires those controls.
        // APPLICATION_MODAL blocked every other window, making the
        // "Use Current Focus" option the only viable choice. The dialog
        // is still kept on top via setAlwaysOnTop below, and showAndWait()
        // still blocks the calling thread so the workflow waits for the
        // user's choice.
        alert.initModality(Modality.NONE);

        // Add custom buttons
        ButtonType retryButton = new ButtonType("Retry Autofocus", ButtonBar.ButtonData.OK_DONE);
        ButtonType useCurrentButton = new ButtonType("Use Current Focus", ButtonBar.ButtonData.APPLY);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        // Add buttons based on retries remaining
        if (retriesRemaining > 0) {
            alert.getButtonTypes().setAll(retryButton, useCurrentButton, cancelButton);
        } else {
            // No retries left - only show use current and cancel
            alert.getButtonTypes().setAll(useCurrentButton, cancelButton);
        }

        // Make dialog always on top so it's visible above progress dialog
        alert.initOwner(null);
        if (alert.getDialogPane() != null && alert.getDialogPane().getScene() != null) {
            javafx.stage.Window window = alert.getDialogPane().getScene().getWindow();
            if (window instanceof javafx.stage.Stage) {
                ((javafx.stage.Stage) window).setAlwaysOnTop(true);
            }
        }

        // Show and wait for user choice
        Optional<ButtonType> result = alert.showAndWait();

        if (result.isPresent()) {
            if (result.get() == retryButton) {
                return ManualFocusHandler.ManualFocusResult.RETRY_AUTOFOCUS;
            } else if (result.get() == useCurrentButton) {
                return ManualFocusHandler.ManualFocusResult.USE_CURRENT_FOCUS;
            } else {
                return ManualFocusHandler.ManualFocusResult.CANCEL_ACQUISITION;
            }
        }

        // Default to cancel if dialog closed without selection
        return ManualFocusHandler.ManualFocusResult.CANCEL_ACQUISITION;
    }

    /**
     * Plays a system beep to notify the user of workflow completion.
     * This is useful for long-running acquisitions where the user may have
     * stepped away from the computer.
     *
     * <p>The beep is played asynchronously and failures are silently ignored
     * since audio notification is a non-critical feature.
     */
    public static void playWorkflowCompletionBeep() {
        if (!QPPreferenceDialog.getCompletionBeepEnabled()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().beep();
            logger.debug("Played workflow completion beep");
        } catch (Exception e) {
            // Ignore beep failures - not critical
            logger.trace("Failed to play completion beep: {}", e.getMessage());
        }
    }

    /**
     * Enum representing user's choice when no annotations are detected.
     */
    public enum AnnotationAction {
        /** User ran tissue detection or drew annotations and confirmed them. */
        ANNOTATIONS_CONFIRMED,
        CANCEL
    }

    /**
     * Shows a non-modal annotation setup dialog when no annotations are
     * detected. The dialog stays open while the user runs tissue detection
     * and/or draws annotations manually, giving them a chance to review
     * and edit the results before proceeding.
     *
     * <p>The dialog provides:
     * <ul>
     *   <li>"Run Tissue Detection" -- runs the configured script but keeps the
     *       dialog open so the user can review/edit results in QuPath</li>
     *   <li>"Use Annotations and Continue" -- checks for annotations and
     *       proceeds if any are found</li>
     *   <li>"Cancel" -- aborts the workflow</li>
     * </ul>
     *
     * @param gui          QuPath GUI instance for running tissue detection
     * @param validClasses annotation classification filter (may be empty)
     * @return CompletableFuture completing with the user's choice
     */
    public static CompletableFuture<AnnotationAction> showAnnotationWarningDialog(
            QuPathGUI gui, List<String> validClasses) {
        logger.info("Showing annotation setup dialog");

        CompletableFuture<AnnotationAction> future = new CompletableFuture<>();

        Platform.runLater(() -> {
            Stage stage = new Stage();
            QpscWindows.track(stage);
            stage.initModality(Modality.NONE);
            stage.setTitle("Annotation Setup");
            stage.setAlwaysOnTop(true);
            stage.setResizable(false);

            VBox layout = new VBox(12);
            layout.setPadding(new Insets(20));
            layout.setMinWidth(480);

            Label headerLabel = new Label("No annotations detected");
            headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

            Label infoLabel = new Label("Annotations are needed for acquisition.\n"
                    + "Run tissue detection or draw annotations manually in QuPath,\n"
                    + "then click \"Use Annotations\" to continue.");
            infoLabel.setWrapText(true);

            Separator sep1 = new Separator();

            // --- Tissue detection section ---
            Button tissueDetectionButton = new Button("Run Tissue Detection");
            tissueDetectionButton.setPrefWidth(440);

            Label statusLabel = new Label("");
            statusLabel.setWrapText(true);
            statusLabel.setStyle("-fx-font-size: 12px;");

            tissueDetectionButton.setOnAction(e -> {
                tissueDetectionButton.setDisable(true);
                tissueDetectionButton.setText("Running...");
                statusLabel.setText("Running tissue detection script...");
                statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + ThemeColors.MUTED + ";");

                // Run on background thread so the UI stays responsive
                CompletableFuture.runAsync(() -> {
                            qupath.ext.qpsc.controller.workflow.AnnotationHelper.runTissueDetection(gui, validClasses);
                        })
                        .whenCompleteAsync(
                                (v, ex) -> {
                                    tissueDetectionButton.setDisable(false);
                                    tissueDetectionButton.setText("Run Tissue Detection");
                                    if (ex != null) {
                                        statusLabel.setText("Tissue detection failed: " + ex.getMessage());
                                        statusLabel.setStyle(
                                                "-fx-font-size: 12px; -fx-text-fill: " + ThemeColors.ERROR + ";");
                                    } else {
                                        int count = countCurrentAnnotations(gui, validClasses);
                                        if (count > 0) {
                                            statusLabel.setText(String.format(
                                                    "Found %d annotation(s). Review/edit in QuPath, "
                                                            + "then click \"Use Annotations\" below.",
                                                    count));
                                            statusLabel.setStyle(
                                                    "-fx-font-size: 12px; -fx-text-fill: " + ThemeColors.SUCCESS + ";");
                                            headerLabel.setText("Annotations ready");
                                        } else {
                                            statusLabel.setText("No annotations created. "
                                                    + "Try adjusting the tissue detection script or draw manually.");
                                            statusLabel.setStyle(
                                                    "-fx-font-size: 12px; -fx-text-fill: " + ThemeColors.WARNING + ";");
                                        }
                                    }
                                },
                                Platform::runLater);
            });

            Separator sep2 = new Separator();

            // --- Action buttons ---
            Button useAnnotationsButton = new Button("Use Annotations and Continue");
            useAnnotationsButton.setPrefWidth(440);
            useAnnotationsButton.setStyle("-fx-font-weight: bold;");
            useAnnotationsButton.setOnAction(e -> {
                int count = countCurrentAnnotations(gui, validClasses);
                if (count > 0) {
                    logger.info("User confirmed {} annotations", count);
                    stage.close();
                    future.complete(AnnotationAction.ANNOTATIONS_CONFIRMED);
                } else {
                    statusLabel.setText("No annotations found. Please run tissue detection "
                            + "or draw annotations in QuPath first.");
                    statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + ThemeColors.ERROR + ";");
                }
            });

            Button cancelButton = new Button("Cancel Workflow");
            cancelButton.setPrefWidth(440);
            cancelButton.setStyle("-fx-text-fill: " + ThemeColors.ERROR + ";");
            cancelButton.setOnAction(e -> {
                logger.info("User canceled workflow from annotation setup dialog");
                stage.close();
                future.complete(AnnotationAction.CANCEL);
            });

            stage.setOnCloseRequest(e -> {
                if (!future.isDone()) {
                    logger.info("Annotation setup dialog closed - treating as cancel");
                    future.complete(AnnotationAction.CANCEL);
                }
            });

            layout.getChildren()
                    .addAll(
                            headerLabel,
                            infoLabel,
                            sep1,
                            tissueDetectionButton,
                            statusLabel,
                            sep2,
                            useAnnotationsButton,
                            cancelButton);

            Scene scene = new Scene(layout);
            stage.setScene(scene);
            stage.show();
        });

        return future;
    }

    /**
     * Counts the current valid annotations in the open image.
     */
    private static int countCurrentAnnotations(QuPathGUI gui, List<String> validClasses) {
        try {
            return qupath.ext.qpsc.controller.workflow.AnnotationHelper.getCurrentValidAnnotations(gui, validClasses)
                    .size();
        } catch (Exception e) {
            logger.debug("Error counting annotations: {}", e.getMessage());
            return 0;
        }
    }
}
