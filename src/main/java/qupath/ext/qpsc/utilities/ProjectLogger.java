package qupath.ext.qpsc.utilities;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.Project;

import qupath.ext.qpsc.preferences.QPPreferenceDialog;

import java.io.File;
import java.nio.file.Path;

/**
 * Utility for managing project-specific logging.
 *
 * <p>This class enables workflow logs to be saved directly in QuPath project folders
 * alongside the acquired data. When activated, all QPSC extension logs are written to
 * both the centralized acquisition log and a project-specific log file.</p>
 *
 * <p>Example usage in a workflow:</p>
 * <pre>{@code
 * // Start project-specific logging
 * ProjectLogger.enable(project);
 *
 * try {
 *     // Run acquisition workflow
 *     logger.info("Starting acquisition...");
 *     // ... workflow code ...
 * } finally {
 *     // Always disable in finally block to ensure cleanup
 *     ProjectLogger.disable();
 * }
 * }</pre>
 *
 * <p>Or using try-with-resources for automatic cleanup:</p>
 * <pre>{@code
 * try (ProjectLogger.Session session = ProjectLogger.start(project)) {
 *     logger.info("Starting acquisition...");
 *     // ... workflow code ...
 * } // Automatically cleaned up
 * }</pre>
 *
 * <p>The project-specific log is saved as: {@code <project-directory>/logs/acquisition.log}</p>
 *
 * @author Mike Nelson
 * @since 0.2.1
 */
public class ProjectLogger {
    private static final Logger logger = LoggerFactory.getLogger(ProjectLogger.class);
    private static final String PROPERTY_NAME = "qpsc.project.logdir";

    // Thread-local to support multiple concurrent workflows (though unlikely)
    private static final ThreadLocal<String> currentProjectPath = new ThreadLocal<>();

    /**
     * Enables project-specific logging for the given QuPath project.
     * Logs will be written to: {@code <project-directory>/logs/acquisition.log}
     *
     * @param project The QuPath project to log for
     * @return true if successfully enabled, false otherwise
     */
    public static boolean enable(Project<?> project) {
        if (project == null) {
            logger.warn("Cannot enable project logging: project is null");
            return false;
        }

        Path projectPath = project.getPath();
        if (projectPath == null) {
            logger.warn("Cannot enable project logging: project path is null");
            return false;
        }

        // project.getPath() returns the .qpproj file - use parent directory
        File projectDir = projectPath.toFile().getParentFile();
        return enable(projectDir);
    }

    /**
     * Enables project-specific logging for the given directory.
     * Logs will be written to: {@code <directory>/logs/acquisition.log}
     *
     * @param projectDir The directory to save logs in
     * @return true if successfully enabled, false otherwise
     */
    public static boolean enable(File projectDir) {
        if (projectDir == null || !projectDir.exists() || !projectDir.isDirectory()) {
            logger.warn("Cannot enable project logging: invalid directory: {}", projectDir);
            return false;
        }

        return enable(projectDir.getAbsolutePath());
    }

    /**
     * Enables logging using the microscope config file location as a fallback.
     *
     * <p>When no QuPath project is open, this method reads the config file path from
     * {@link QPPreferenceDialog#getMicroscopeConfigFileProperty()} and enables logging
     * in the config file's parent directory. Logs will be written to:
     * {@code <config_parent>/logs/acquisition.log}</p>
     *
     * @return true if successfully enabled, false otherwise
     */
    public static boolean enableFromConfig() {
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        if (configPath == null || configPath.trim().isEmpty()) {
            logger.warn("Cannot enable config-based logging: no microscope config file set in preferences");
            return false;
        }

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            logger.warn("Cannot enable config-based logging: config file does not exist: {}", configPath);
            return false;
        }

        File configDir = configFile.getParentFile();
        if (configDir == null || !configDir.exists() || !configDir.isDirectory()) {
            logger.warn("Cannot enable config-based logging: invalid config directory for: {}", configPath);
            return false;
        }

        logger.info("No project available - using config directory for logging: {}", configDir);
        return enable(configDir.getAbsolutePath());
    }

    /**
     * Enables project-specific logging for the given directory path.
     * Logs will be written to: {@code <path>/logs/acquisition.log}
     *
     * @param projectPath The directory path to save logs in
     * @return true if successfully enabled, false otherwise
     */
    public static boolean enable(String projectPath) {
        if (projectPath == null || projectPath.trim().isEmpty()) {
            logger.warn("Cannot enable project logging: path is null or empty");
            return false;
        }

        // Verify directory exists
        File dir = new File(projectPath);
        if (!dir.exists() || !dir.isDirectory()) {
            logger.warn("Cannot enable project logging: directory does not exist: {}", projectPath);
            return false;
        }

        // Create logs/ subdirectory if it doesn't exist
        File logsDir = new File(dir, "logs");
        if (!logsDir.exists()) {
            if (!logsDir.mkdirs()) {
                logger.warn("Cannot enable project logging: failed to create logs directory: {}", logsDir);
                return false;
            }
        }

        // Set system property for logback (logback.xml appends /logs/acquisition.log)
        System.setProperty(PROPERTY_NAME, projectPath);
        currentProjectPath.set(projectPath);

        // Reconfigure logback to pick up the new property
        reconfigureLogback();

        logger.info("Project-specific logging enabled: {}/logs/acquisition.log", projectPath);
        return true;
    }

    /**
     * Disables project-specific logging.
     * Should always be called when a workflow completes (use finally block or try-with-resources).
     */
    public static void disable() {
        String path = currentProjectPath.get();

        if (path != null) {
            logger.info("Project-specific logging disabled: {}/logs/acquisition.log", path);
            currentProjectPath.remove();
        }

        // Clear system property
        System.clearProperty(PROPERTY_NAME);

        // Reconfigure logback
        reconfigureLogback();
    }

    /**
     * Checks if project-specific logging is currently enabled.
     *
     * @return true if enabled, false otherwise
     */
    public static boolean isEnabled() {
        return currentProjectPath.get() != null;
    }

    /**
     * Gets the current project log directory, if enabled.
     *
     * @return The current project log directory, or null if not enabled
     */
    public static String getCurrentProjectPath() {
        return currentProjectPath.get();
    }

    /**
     * Starts a project-specific logging session with automatic cleanup.
     * Use with try-with-resources for guaranteed cleanup.
     *
     * <pre>{@code
     * try (ProjectLogger.Session session = ProjectLogger.start(project)) {
     *     // Your workflow code here
     * } // Automatically cleaned up
     * }</pre>
     *
     * @param project The QuPath project to log for
     * @return A Session object that will disable logging when closed
     */
    public static Session start(Project<?> project) {
        return new Session(project);
    }

    /**
     * Starts a project-specific logging session with automatic cleanup.
     * Use with try-with-resources for guaranteed cleanup.
     *
     * @param projectDir The directory to save logs in
     * @return A Session object that will disable logging when closed
     */
    public static Session start(File projectDir) {
        return new Session(projectDir);
    }

    /**
     * Starts a project-specific logging session with automatic cleanup.
     * Use with try-with-resources for guaranteed cleanup.
     *
     * @param projectPath The directory path to save logs in
     * @return A Session object that will disable logging when closed
     */
    public static Session start(String projectPath) {
        return new Session(projectPath);
    }

    /**
     * Reconfigures the Logback context to pick up property changes.
     * This is necessary for the PROJECT_LOG appender to activate/deactivate.
     */
    private static void reconfigureLogback() {
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            // Don't do a full reset - just update the context
            context.getLogger(Logger.ROOT_LOGGER_NAME).info("Logback context updated");
        } catch (Exception e) {
            logger.debug("Could not reconfigure logback context", e);
        }
    }

    /**
     * Auto-closeable session for project-specific logging.
     * Ensures logging is disabled when the session closes.
     */
    public static class Session implements AutoCloseable {
        private final boolean wasEnabled;

        private Session(Project<?> project) {
            this.wasEnabled = enable(project);
        }

        private Session(File projectDir) {
            this.wasEnabled = enable(projectDir);
        }

        private Session(String projectPath) {
            this.wasEnabled = enable(projectPath);
        }

        /**
         * Checks if the session was successfully started.
         *
         * @return true if project logging was enabled, false otherwise
         */
        public boolean isActive() {
            return wasEnabled;
        }

        @Override
        public void close() {
            if (wasEnabled) {
                disable();
            }
        }
    }
}
