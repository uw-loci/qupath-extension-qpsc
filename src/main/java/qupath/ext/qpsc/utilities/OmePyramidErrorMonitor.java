package qupath.ext.qpsc.utilities;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches QuPath's OMEPyramidWriter logger for per-tile write errors.
 *
 * <p>QuPath core's {@code OMEPyramidWriter} intermittently fails to write
 * individual pyramid tiles on certain grid dimensions (pyramid levels whose
 * size is not a clean multiple of the 512 px tile size). It logs the failure at
 * ERROR level ("Error writing Tile: level=N, bounds=(...)") but does NOT throw
 * -- the write runs to completion and produces a broken file: the
 * full-resolution level is intact, but downsampled pyramid levels are corrupt.
 * The failure is therefore invisible to any caller that only checks for thrown
 * exceptions.
 *
 * <p>This monitor installs a Logback appender on that logger and counts ERROR
 * events. Callers snapshot the counter before a stitch and check the delta
 * after; a positive delta means the stitch produced a broken file and must be
 * treated as a failure. See {@link StitchRetryExecutor}.
 */
public final class OmePyramidErrorMonitor {

    private static final Logger logger = LoggerFactory.getLogger(OmePyramidErrorMonitor.class);

    /** QuPath logger that emits per-tile pyramid-write errors. */
    private static final String OME_PYRAMID_WRITER_LOGGER = "qupath.lib.images.writers.ome.OMEPyramidWriter";

    private static final AtomicInteger errorCounter = new AtomicInteger();
    private static volatile boolean installed = false;
    private static final Object installLock = new Object();

    private OmePyramidErrorMonitor() {}

    /**
     * Installs the error-counting appender on the OMEPyramidWriter logger.
     * Idempotent and thread-safe; safe to call before every stitch.
     */
    public static void install() {
        if (installed) return;
        synchronized (installLock) {
            if (installed) return;
            try {
                org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(OME_PYRAMID_WRITER_LOGGER);
                if (!(slf4jLogger instanceof ch.qos.logback.classic.Logger logbackLogger)) {
                    logger.warn(
                            "OME pyramid writer logger is not a Logback logger ({}); "
                                    + "per-tile error detection disabled.",
                            slf4jLogger.getClass().getName());
                    installed = true;
                    return;
                }
                ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                        new ch.qos.logback.core.AppenderBase<>() {
                            @Override
                            protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                                if (event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)) {
                                    errorCounter.incrementAndGet();
                                }
                            }
                        };
                appender.setName("QPSC-OMEPyramidErrorCounter");
                appender.setContext(logbackLogger.getLoggerContext());
                appender.start();
                logbackLogger.addAppender(appender);
                installed = true;
                logger.info("Installed OME pyramid writer error counter");
            } catch (Throwable t) {
                logger.warn("Could not install OME pyramid writer error counter: {}", t.getMessage());
                installed = true; // don't keep retrying
            }
        }
    }

    /** Returns the current cumulative tile-write error count. */
    public static int snapshot() {
        return errorCounter.get();
    }

    /**
     * Returns the number of tile-write errors logged since the given snapshot.
     *
     * @param snapshot a value previously returned by {@link #snapshot()}
     */
    public static int deltaSince(int snapshot) {
        return errorCounter.get() - snapshot;
    }
}
