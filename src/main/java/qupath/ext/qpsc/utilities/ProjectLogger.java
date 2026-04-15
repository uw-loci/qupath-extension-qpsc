package qupath.ext.qpsc.utilities;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.Project;

/**
 * Manages project-scoped logging for the QPSC extension.
 *
 * <p>This class programmatically attaches a {@link FileAppender} to the
 * {@code qupath.ext.qpsc} logger, directing all QPSC log output to a file.
 * QuPath's own logback.xml shadows the extension's, so all file logging
 * must be configured via the logback API rather than XML.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #enableTempLogging()} -- called once at extension install.
 *       Creates a temp file for pre-project log output.</li>
 *   <li>{@link #enable(Project)} -- called when a project opens.
 *       Prepends any temp log content into the project log file,
 *       deletes the temp file, and starts logging to the project.</li>
 *   <li>{@link #disable()} -- called when a project closes.
 *       Stops the project appender and re-enables temp logging.</li>
 * </ol>
 *
 * <p>Project log files are created at:
 * {@code <project-parent>/logs/qpsc-session-<timestamp>.log}</p>
 *
 * @author Mike Nelson
 * @since 0.2.1
 */
public class ProjectLogger {

    private static final Logger logger = LoggerFactory.getLogger(ProjectLogger.class);

    private static final String APPENDER_NAME = "QPSC_PROJECT_LOG";
    private static final String LOG_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%-20thread] %-5level %-40logger{40} - %msg%n";
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static FileAppender<ILoggingEvent> activeAppender;
    private static Path tempLogFile;
    private static Path currentLogFile;

    private ProjectLogger() {}

    /**
     * Starts temporary logging to a system temp file.
     * Called once from {@code SetupScope.installExtension()} to capture
     * pre-project log output. When a project later opens, the temp
     * contents are prepended into the project log file.
     */
    public static synchronized void enableTempLogging() {
        try {
            tempLogFile = Files.createTempFile("qpsc-", ".log");
            attachAppender(tempLogFile);
            logger.info("QPSC temp logging started: {}", tempLogFile);
        } catch (IOException e) {
            logger.warn("Failed to create temp log file", e);
        }
    }

    /**
     * Enables project-specific logging for the given QuPath project.
     * Any existing temp log content is prepended to the new project log file.
     *
     * @param project the QuPath project to log for
     * @return true if successfully enabled, false otherwise
     */
    public static synchronized boolean enable(Project<?> project) {
        if (project == null) {
            logger.warn("Cannot enable project logging: project is null");
            return false;
        }

        Path projectPath = project.getPath();
        if (projectPath == null) {
            logger.warn("Cannot enable project logging: project path is null");
            return false;
        }

        // project.getPath() returns the .qpproj file -- use parent directory
        File projectDir = projectPath.toFile().getParentFile();
        return enable(projectDir);
    }

    /**
     * Enables project-specific logging for the given project directory.
     *
     * @param projectDir the project directory (parent of .qpproj file)
     * @return true if successfully enabled, false otherwise
     */
    public static synchronized boolean enable(File projectDir) {
        if (projectDir == null || !projectDir.exists() || !projectDir.isDirectory()) {
            logger.warn("Cannot enable project logging: invalid directory: {}", projectDir);
            return false;
        }

        // Create logs/ subdirectory
        File logsDir = new File(projectDir, "logs");
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            logger.warn("Cannot enable project logging: failed to create logs directory: {}", logsDir);
            return false;
        }

        // Build session log filename
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        Path logFile = logsDir.toPath().resolve("qpsc-session-" + timestamp + ".log");

        // Prepend temp log contents if present
        prependTempLog(logFile);

        // Detach current appender (temp or previous project) and attach new one
        attachAppender(logFile);
        currentLogFile = logFile;

        // Write version header as first log entries for provenance tracking
        logger.info(VersionInfo.formatLogHeader());

        logger.info("QPSC project logging enabled: {}", logFile);
        return true;
    }

    /**
     * Disables project-specific logging and re-enables temp logging.
     */
    public static synchronized void disable() {
        if (currentLogFile != null) {
            logger.info("QPSC project logging disabled: {}", currentLogFile);
        }
        currentLogFile = null;

        // Detach the project appender before starting temp logging
        detachAppender();

        // Resume temp logging for any pre-project activity
        enableTempLogging();
    }

    /**
     * Checks if project-specific logging is currently enabled
     * (as opposed to temp logging or no logging).
     *
     * @return true if logging to a project log file
     */
    public static synchronized boolean isEnabled() {
        return currentLogFile != null;
    }

    /**
     * Returns the current temp log file path, or null if temp logging is not active.
     *
     * @return the temp log file path
     */
    public static synchronized Path getTempLogFile() {
        return tempLogFile;
    }

    /**
     * Returns the current project log file path, or null if project logging is not active.
     *
     * @return the project log file path
     */
    public static synchronized Path getCurrentLogFile() {
        return currentLogFile;
    }

    // ---- internal ----

    /**
     * Prepends temp log file contents into the target log file, then deletes the temp file.
     */
    private static void prependTempLog(Path targetLogFile) {
        if (tempLogFile == null) {
            return;
        }

        try {
            // Stop appender so the temp file is flushed and released
            detachAppender();

            if (Files.exists(tempLogFile) && Files.size(tempLogFile) > 0) {
                byte[] tempContents = Files.readAllBytes(tempLogFile);
                Files.write(targetLogFile, tempContents, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                logger.info("Prepended {} bytes from temp log into project log", tempContents.length);
            }

            Files.deleteIfExists(tempLogFile);
        } catch (IOException e) {
            logger.warn("Failed to prepend temp log contents", e);
        } finally {
            tempLogFile = null;
        }
    }

    /**
     * Loggers that should route into the QPSC session log. The primary entry
     * is the QPSC extension itself; sibling extensions that QPSC drives (the
     * tiles-to-pyramid stitching pipeline, in particular ChannelMerger /
     * PyramidImageWriter) are included so that stitch + merge internals are
     * visible when diagnosing acquisition bugs from the session log alone.
     */
    private static final String[] ATTACHED_LOGGERS = new String[] {
            "qupath.ext.qpsc",
            "qupath.ext.basicstitching",
    };

    /**
     * Programmatically creates and attaches a {@link FileAppender} to each
     * logger in {@link #ATTACHED_LOGGERS}. Any existing appender is detached
     * and stopped first.
     */
    private static void attachAppender(Path logFile) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Detach any existing QPSC file appender
        detachAppender();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(LOG_PATTERN);
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.setFile(logFile.toString());
        appender.setEncoder(encoder);
        appender.setAppend(true);
        appender.start();

        for (String name : ATTACHED_LOGGERS) {
            context.getLogger(name).addAppender(appender);
        }
        activeAppender = appender;
    }

    /**
     * Detaches and stops the current QPSC file appender, if any, from every
     * logger it was attached to in {@link #attachAppender}.
     */
    private static void detachAppender() {
        if (activeAppender != null) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            for (String name : ATTACHED_LOGGERS) {
                context.getLogger(name).detachAppender(activeAppender);
            }
            activeAppender.stop();
            activeAppender = null;
        }
    }
}
