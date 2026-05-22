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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Modality;
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

    /** Guards against stacking multiple warn dialogs when stitches fail in parallel. */
    private static final AtomicBoolean warnDialogVisible = new AtomicBoolean(false);

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
        AtomicBoolean switchToZarr = new AtomicBoolean(false);

        // Flip flags are set once for the whole sequence: every OME-TIFF attempt
        // uses identical values, so set-once / clear-once avoids a per-attempt
        // clear racing with a write that is still being abandoned.
        TileConfigurationTxtStrategy.flipStitchingX = flipFlags[0];
        TileConfigurationTxtStrategy.flipStitchingY = flipFlags[1];
        try {
            Exception lastFailure = null;
            boolean firstFailureReported = false;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean zarrAttempt = escalates && (attempt > OME_TIFF_ATTEMPTS || switchToZarr.get());
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
                    }
                    return outPath;
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
                    reportFirstFailure(label, switchToZarr, interactive);
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
     * Sends a push notification and (when interactive) shows a non-blocking
     * dialog offering to switch the remaining attempts to ZARR. Fired once,
     * after the first failed OME-TIFF attempt.
     */
    private static void reportFirstFailure(String label, AtomicBoolean switchToZarr, boolean interactive) {
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

        if (!interactive) {
            return;
        }
        if (!warnDialogVisible.compareAndSet(false, true)) {
            // A warn dialog is already on screen; do not stack another.
            return;
        }
        Platform.runLater(() -> {
            try {
                Dialog<ButtonType> dialog = new Dialog<>();
                dialog.initModality(Modality.NONE);
                dialog.setTitle("Stitching tile-write errors");
                dialog.setHeaderText("Known OME-TIFF writer bug detected while stitching \"" + label + "\"");
                ButtonType switchZarr = new ButtonType("Switch to ZARR", ButtonBar.ButtonData.OK_DONE);
                ButtonType keepTiff = new ButtonType("Keep retrying OME-TIFF", ButtonBar.ButtonData.CANCEL_CLOSE);
                dialog.getDialogPane().getButtonTypes().setAll(switchZarr, keepTiff);
                dialog.setContentText("Pyramid tile-write errors were detected -- the OME-TIFF output would have "
                        + "corrupt zoomed-out levels. Stitching is being retried automatically.\n\n"
                        + "You can switch the next attempt to the more reliable ZARR format (it "
                        + "still produces a .ome.tif via background conversion), or keep retrying "
                        + "OME-TIFF. If you do nothing, it falls back to ZARR after "
                        + OME_TIFF_ATTEMPTS + " OME-TIFF attempts.");
                dialog.setOnHidden(ev -> {
                    warnDialogVisible.set(false);
                    if (dialog.getResult() == switchZarr) {
                        switchToZarr.set(true);
                        logger.info("User chose to switch stitching of '{}' to ZARR", label);
                    }
                });
                dialog.show();
            } catch (Exception e) {
                warnDialogVisible.set(false);
                logger.warn("Could not show tile-write-error dialog: {}", e.getMessage());
            }
        });
    }
}
