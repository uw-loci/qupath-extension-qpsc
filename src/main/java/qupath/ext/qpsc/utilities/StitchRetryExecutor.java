package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.config.StitchingConfig.OutputFormat;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;

/**
 * Runs a stitch with OMEPyramidWriter tile-write-error detection, retry, and
 * escalation to a writer that does not have the bug.
 *
 * <p>QuPath core's {@code OMEPyramidWriter} intermittently produces silently
 * corrupt OME-TIFF pyramids (see {@link OmePyramidErrorMonitor}). When the
 * requested format is {@link OutputFormat#OME_TIFF} this executor retries the
 * stitch up to {@link #OME_TIFF_ATTEMPTS} times, then escalates to
 * {@code OME_TIFF_VIA_ZARR} -- which stitches through {@code OMEZarrWriter}
 * (a different writer, not subject to the bug) and converts the result back to
 * a {@code .ome.tif} in the background. Any other base format does not use
 * {@code OMEPyramidWriter} and is simply retried on failure.
 *
 * <p>The doomed in-flight write cannot be cleanly cancelled
 * ({@code OMEPyramidWriter.writeSeries} has no cancellation API). When a
 * tile-write error is detected mid-write, this executor issues a best-effort
 * interrupt so the retry can start sooner, but correctness never depends on the
 * interrupt taking effect -- it always waits for the abandoned write to release
 * its temp output file before the next attempt begins.
 */
public final class StitchRetryExecutor {

    private static final Logger logger = LoggerFactory.getLogger(StitchRetryExecutor.class);

    /** Number of OME-TIFF attempts before the terminal OME_TIFF_VIA_ZARR fallback. */
    public static final int OME_TIFF_ATTEMPTS = 3;

    /** Poll cadence (ms) while waiting on a stitch, to spot tile-write errors mid-write. */
    private static final long POLL_MS = 250;

    /** Upper bound (minutes) on waiting for an abandoned write to release its temp file. */
    private static final long ABANDON_WAIT_MINUTES = 15;

    /** Guards against stacking multiple recovery notices when stitches fail in parallel. */
    private static final AtomicBoolean recoveryDialogVisible = new AtomicBoolean(false);

    /** Vertical gap between the StitchingBlockingDialog and the recovery notice when both are visible. */
    private static final double DIALOG_GAP_PX = 20.0;

    private StitchRetryExecutor() {}

    /** Builds a {@link StitchingConfig} for a requested output format. */
    @FunctionalInterface
    public interface ConfigFactory {
        StitchingConfig create(OutputFormat format);
    }

    /**
     * Runs a stitch with tile-write-error detection, retry, and escalation.
     *
     * @param baseFormat    the user's preferred output format
     * @param configFactory builds a {@link StitchingConfig} for a given format
     * @param flipFlags     {@code [flipX, flipY]} applied for the whole retry sequence
     * @param cleanup       deletes corrupt partial output between attempts
     * @param label         human-readable label for logs and dialogs (sample or angle name)
     * @param interactive   if true, show a non-blocking warn dialog on the first failure
     * @return absolute path to the stitched output -- {@code .ome.tif}, or
     *         {@code .ome.zarr} if the retry escalated to ZARR
     * @throws IOException if every attempt (including the ZARR fallback) fails
     */
    public static String run(
            OutputFormat baseFormat,
            ConfigFactory configFactory,
            boolean[] flipFlags,
            Runnable cleanup,
            String label,
            boolean interactive)
            throws IOException {

        OmePyramidErrorMonitor.install();

        boolean escalates = baseFormat == OutputFormat.OME_TIFF;
        int maxAttempts = escalates ? OME_TIFF_ATTEMPTS + 1 : OME_TIFF_ATTEMPTS;

        // Flip flags are set once for the whole sequence: every OME-TIFF attempt
        // uses identical values, so set-once / clear-once avoids a per-attempt
        // clear racing with a write that is still being abandoned.
        TileConfigurationTxtStrategy.flipStitchingX = flipFlags[0];
        TileConfigurationTxtStrategy.flipStitchingY = flipFlags[1];
        try {
            Exception lastFailure = null;
            boolean firstFailureReported = false;
            int omeTiffAttemptsUsed = 0;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean zarrAttempt = escalates && attempt > OME_TIFF_ATTEMPTS;
                OutputFormat format = zarrAttempt ? OutputFormat.OME_TIFF_VIA_ZARR : baseFormat;
                boolean detectTileErrors = format == OutputFormat.OME_TIFF;

                logger.info("Stitching '{}' attempt {}/{} ({})", label, attempt, maxAttempts, format);
                int errBefore = OmePyramidErrorMonitor.snapshot();
                String outPath = null;
                Exception thrown = null;
                try {
                    outPath = runMonitoredStitch(configFactory.create(format), errBefore, detectTileErrors, label);
                } catch (Exception e) {
                    thrown = e;
                }
                int errDelta = OmePyramidErrorMonitor.deltaSince(errBefore);
                boolean tileWriteErrors = detectTileErrors && errDelta > 0;
                boolean failed = thrown != null || outPath == null || tileWriteErrors;

                if (!failed) {
                    if (zarrAttempt) {
                        logger.info("Stitching '{}' succeeded via the OME_TIFF_VIA_ZARR fallback", label);
                        reportRecoveryComplete(label, omeTiffAttemptsUsed, interactive);
                    }
                    return outPath;
                }

                if (!zarrAttempt) {
                    omeTiffAttemptsUsed = attempt;
                }
                lastFailure = thrown != null
                        ? thrown
                        : new IOException(
                                tileWriteErrors
                                        ? errDelta + " OMEPyramidWriter tile-write error(s)"
                                        : "stitching produced no output");
                logger.warn(
                        "Stitching '{}' attempt {}/{} failed: {}",
                        label,
                        attempt,
                        maxAttempts,
                        lastFailure.getMessage());
                cleanup.run();

                if (zarrAttempt) {
                    throw new IOException(
                            "Stitching '" + label + "' failed after " + OME_TIFF_ATTEMPTS
                                    + " OME-TIFF attempt(s) and the OME_TIFF_VIA_ZARR fallback",
                            lastFailure);
                }
                if (escalates && !firstFailureReported) {
                    firstFailureReported = true;
                    sendFirstFailureNotification(label);
                }
            }
            // Reached only by a non-escalating base format that exhausted its retries.
            throw new IOException("Stitching '" + label + "' failed after " + maxAttempts + " attempt(s)", lastFailure);
        } finally {
            TileConfigurationTxtStrategy.flipStitchingX = false;
            TileConfigurationTxtStrategy.flipStitchingY = false;
        }
    }

    /**
     * Runs one {@code StitchingWorkflow.run} on a monitored worker thread. If a
     * tile-write error is detected mid-write the doomed write is abandoned
     * (best-effort interrupt) and an exception is thrown so the caller retries;
     * the abandoned write is always awaited so it releases its temp output file
     * before the retry begins.
     */
    private static String runMonitoredStitch(
            StitchingConfig config, int errBefore, boolean detectTileErrors, String label) throws Exception {

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Stitch-" + label);
            t.setDaemon(true);
            return t;
        });
        Future<String> future = exec.submit(() -> StitchingWorkflow.run(config));
        boolean abandoned = false;
        try {
            while (true) {
                try {
                    return future.get(POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    if (detectTileErrors && OmePyramidErrorMonitor.deltaSince(errBefore) > 0) {
                        logger.warn(
                                "Stitching '{}': tile-write error detected mid-write; "
                                        + "abandoning the doomed write (best-effort interrupt)",
                                label);
                        future.cancel(true);
                        abandoned = true;
                        break;
                    }
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    if (cause instanceof Exception ex) {
                        throw ex;
                    }
                    throw ee;
                }
            }
        } finally {
            exec.shutdownNow();
            // Wait for the (possibly un-interruptible) write to actually finish
            // so it releases its temp output file before the caller retries.
            try {
                if (!exec.awaitTermination(ABANDON_WAIT_MINUTES, TimeUnit.MINUTES)) {
                    logger.warn(
                            "Stitching '{}': abandoned write still running after {} min", label, ABANDON_WAIT_MINUTES);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (abandoned) {
            throw new IOException("OMEPyramidWriter tile-write errors detected; doomed write abandoned");
        }
        throw new IllegalStateException("monitored stitch loop exited unexpectedly");
    }

    /**
     * Fires the high-priority push notification on the first failed OME-TIFF
     * attempt. The user-facing summary (with auto-recovery context) is fired
     * separately by {@link #reportRecoveryComplete} once the ZARR fallback
     * actually succeeds -- the retry/escalation cadence is faster than a
     * human can respond, so a mid-flight "switch to ZARR?" prompt was moot
     * in practice and has been removed.
     */
    private static void sendFirstFailureNotification(String label) {
        try {
            NotificationService.getInstance()
                    .notify(
                            "Stitching tile-write errors",
                            "Sample \"" + label + "\": OME-TIFF stitching hit a known writer bug. "
                                    + "Retrying automatically; it will fall back to ZARR if needed.",
                            NotificationPriority.HIGH,
                            NotificationEvent.STITCHING_ERROR);
        } catch (Exception e) {
            logger.debug("Could not send tile-write-error notification: {}", e.getMessage());
        }
    }

    /**
     * Shows a non-blocking post-recovery Alert after the ZARR fallback
     * succeeds, telling the user what happened and suggesting the
     * Preferences switch. Raised always-on-top and positioned below the
     * {@code StitchingBlockingDialog} when that is visible, mirroring the
     * pattern used by that dialog's own internal warning at
     * {@code StitchingBlockingDialog:295-310}.
     *
     * @param label                  sample / angle label for the message text
     * @param omeTiffAttemptsUsed    how many OME-TIFF attempts failed before
     *                               the ZARR escalation
     * @param interactive            no-op when false (headless / batch path)
     */
    private static void reportRecoveryComplete(String label, int omeTiffAttemptsUsed, boolean interactive) {
        if (!interactive) {
            return;
        }
        if (!recoveryDialogVisible.compareAndSet(false, true)) {
            // A previous recovery notice is still up; coalesce rather than stack.
            return;
        }
        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.initModality(Modality.NONE);
                alert.setTitle("Stitching recovered via ZARR");
                alert.setHeaderText("Stitching of \"" + label + "\" recovered automatically");
                String attemptsPhrase = omeTiffAttemptsUsed == 1 ? "1 attempt" : omeTiffAttemptsUsed + " attempts";
                alert.setContentText("A known OME-TIFF writer bug caused " + attemptsPhrase
                        + " to fail. Stitching escalated to OME-TIFF-via-ZARR (a different writer "
                        + "that is not subject to the bug) and produced a usable image. A background "
                        + "task is converting the ZARR back to .ome.tif.\n\n"
                        + "If this keeps happening for your slides, consider switching the default "
                        + "stitching format to OME-TIFF-via-ZARR in Edit -> Preferences -> QPSC.");
                alert.setResizable(true);
                alert.setOnHidden(ev -> recoveryDialogVisible.set(false));

                Stage stitchingWindow = findStitchingBlockingWindow();
                if (stitchingWindow != null) {
                    alert.initOwner(stitchingWindow);
                }

                alert.setOnShown(shown -> {
                    Window alertWindow = alert.getDialogPane().getScene() != null
                            ? alert.getDialogPane().getScene().getWindow()
                            : null;
                    if (alertWindow instanceof Stage alertStage) {
                        alertStage.setAlwaysOnTop(true);
                        alertStage.toFront();
                        if (stitchingWindow != null && stitchingWindow.getWidth() > 0 && alertWindow.getWidth() > 0) {
                            double parentX = stitchingWindow.getX();
                            double parentY = stitchingWindow.getY();
                            double parentW = stitchingWindow.getWidth();
                            double parentH = stitchingWindow.getHeight();
                            double alertW = alertWindow.getWidth();
                            alertStage.setX(parentX + (parentW - alertW) / 2.0);
                            alertStage.setY(parentY + parentH + DIALOG_GAP_PX);
                        }
                    }
                });
                alert.show();
            } catch (Exception e) {
                recoveryDialogVisible.set(false);
                logger.warn("Could not show stitching recovery notice: {}", e.getMessage());
            }
        });
    }

    /**
     * Finds the visible {@code StitchingBlockingDialog} window so the recovery
     * notice can be positioned relative to it. Returns null if the dialog is
     * not on screen (e.g. recovery completed after the workflow already
     * closed it).
     */
    private static Stage findStitchingBlockingWindow() {
        try {
            return Window.getWindows().stream()
                    .filter(w -> w instanceof Stage && w.isShowing())
                    .map(w -> (Stage) w)
                    .filter(s -> {
                        String t = s.getTitle();
                        return t != null && t.startsWith("Stitching in Progress");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            logger.debug("Could not locate StitchingBlockingDialog window: {}", e.getMessage());
            return null;
        }
    }
}
