package qupath.ext.qpsc.service;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.ProjectLogger;
import qupath.ext.qpsc.utilities.VersionInfo;

/**
 * Backend client for the in-app bug reporter.
 *
 * <p>Submits a user-written bug description -- optionally with system info,
 * log artifacts, and a window screenshot -- to a Cloudflare Worker that holds
 * a GitHub PAT server-side and files the report as a GitHub Issue. No token is
 * shipped in the JAR; the Worker is the only thing that can talk to GitHub.
 * Source and deploy instructions live in {@code cloudflare-worker-bug-reporter/}.</p>
 *
 * <p>This class is UI-free: it gathers payload pieces and performs the blocking
 * HTTP POST. The caller is responsible for running {@link #submit} off the
 * JavaFX Application Thread. ASCII-only in all internal strings (cp1252 runtime);
 * user-entered text is carried as UTF-8 in the JSON body, which is fine over HTTP.</p>
 */
public class BugReportService {

    private static final Logger logger = LoggerFactory.getLogger(BugReportService.class);

    /**
     * Cloudflare Worker endpoint. Set this to the deployed URL after running
     * {@code wrangler deploy} (see cloudflare-worker-bug-reporter/README.md).
     * Until it is set, {@link #isConfigured()} returns false and the dialog
     * disables submission.
     */
    public static final String WORKER_URL = "https://qpsc-bug-reporter.YOUR-SUBDOMAIN.workers.dev";

    /** Allow-list key understood by the Worker (see REPOS in src/index.js). */
    private static final String REPO_KEY = "qpsc";

    public static final int MIN_DESCRIPTION_CHARS = 20;
    public static final int MAX_DESCRIPTION_CHARS = 10000;

    private static final int MAX_RUN_LOG_CHARS = 20000;
    private static final int MAX_QUPATH_LOG_CHARS = 12000;

    /** Worker rejects screenshots whose base64 exceeds this (~3 MB binary). */
    public static final int MAX_SCREENSHOT_B64_CHARS = 4 * 1024 * 1024;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private BugReportService() {}

    /** Immutable request assembled by the dialog. */
    public record BugReport(
            String description, String sysinfo, Map<String, String> artifacts, String screenshotBase64) {}

    /** Outcome of a submission. {@code ok} false carries a user-facing {@code error}. */
    public record Result(boolean ok, String issueUrl, Integer issueNumber, String error) {}

    /** True once {@link #WORKER_URL} has been pointed at a real deployment. */
    public static boolean isConfigured() {
        return WORKER_URL != null && !WORKER_URL.contains("YOUR-SUBDOMAIN");
    }

    // ---- payload gathering -------------------------------------------------

    /** Multi-line system-info block (extension/QuPath/Java/OS versions). */
    public static String gatherSysInfo() {
        // VersionInfo.formatLogHeader() is already the canonical provenance block.
        return VersionInfo.formatLogHeader().trim();
    }

    /**
     * Reads the requested log files, scrubs home-directory paths, and caps each
     * to keep the combined GitHub issue body under its 64 KB limit.
     *
     * @param includeSessionLog attach the QPSC per-run session log (most useful)
     * @param includeQuPathLog  attach QuPath's own log file, if one is on disk
     * @return ordered map of artifact-key -> text (only non-empty entries)
     */
    public static Map<String, String> gatherLogArtifacts(boolean includeSessionLog, boolean includeQuPathLog) {
        Map<String, String> artifacts = new LinkedHashMap<>();

        if (includeSessionLog) {
            Path sessionLog = ProjectLogger.getCurrentLogFile();
            if (sessionLog == null) {
                sessionLog = ProjectLogger.getTempLogFile();
            }
            String content = readCapped(sessionLog, MAX_RUN_LOG_CHARS);
            if (!content.isEmpty()) {
                artifacts.put("run_log", content);
            }
        }

        if (includeQuPathLog) {
            Path quPathLog = findQuPathLogFile();
            String content = readCapped(quPathLog, MAX_QUPATH_LOG_CHARS);
            if (!content.isEmpty()) {
                artifacts.put("qupath_log", content);
            }
        }

        return artifacts;
    }

    /** True if a QuPath-side log file could be located on disk (drives a checkbox state). */
    public static boolean isQuPathLogAvailable() {
        return findQuPathLogFile() != null;
    }

    private static String readCapped(Path path, int maxChars) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("Could not read log artifact {}: {}", path, e.getMessage());
            return "";
        }
        if (text.length() > maxChars) {
            text = "... [truncated to last " + maxChars + " chars]\n" + text.substring(text.length() - maxChars);
        }
        return scrubPaths(text);
    }

    /**
     * Replaces the user's home directory with {@code ~} so reports don't leak a
     * username. Cheap and preserves diagnostic value. Only applied to the copy
     * bundled for submission -- never to the on-screen log.
     */
    static String scrubPaths(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) {
            return text;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            // Case-insensitive literal replace of the home path.
            return text.replaceAll("(?i)" + java.util.regex.Pattern.quote(home), "~");
        }
        return text.replace(home, "~");
    }

    /**
     * Scans logback's root logger for a {@link FileAppender} and returns its
     * file path -- this is QuPath's own application log when one is configured.
     * Returns null if no file appender is attached to root.
     */
    private static Path findQuPathLogFile() {
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
                Appender<ILoggingEvent> appender = it.next();
                if (appender instanceof FileAppender<ILoggingEvent> fileAppender) {
                    String file = fileAppender.getFile();
                    if (file != null && !file.isBlank()) {
                        Path p = Path.of(file);
                        if (Files.isRegularFile(p)) {
                            return p;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not locate QuPath log file: {}", e.getMessage());
        }
        return null;
    }

    // ---- submission --------------------------------------------------------

    /**
     * Blocking POST of the report to the Worker. Run this off the FX thread.
     *
     * @param report the assembled bug report
     * @return a {@link Result}; never throws -- transport/parse failures become
     *         {@code ok=false} with a user-facing message
     */
    public static Result submit(BugReport report) {
        if (!isConfigured()) {
            return new Result(false, null, null, "Bug reporting is not configured (Worker URL not set).");
        }

        String json;
        try {
            json = buildJson(report);
        } catch (Exception e) {
            return new Result(false, null, null, "Could not build request: " + e.getMessage());
        }

        try {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WORKER_URL))
                    .timeout(HTTP_TIMEOUT)
                    // A custom User-Agent is REQUIRED: Cloudflare bot-blocks the
                    // default Java-http-client UA as casual-abuse filtering.
                    .header("User-Agent", "qpsc-bug-reporter-client")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(response.statusCode(), response.body());
        } catch (java.net.http.HttpTimeoutException e) {
            return new Result(false, null, null, "Timed out contacting the bug-report server.");
        } catch (IOException e) {
            return new Result(false, null, null, "Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, null, "Submission was interrupted.");
        } catch (Exception e) {
            return new Result(false, null, null, "Unexpected error: " + e.getMessage());
        }
    }

    private static String buildJson(BugReport report) {
        JsonObject root = new JsonObject();
        root.addProperty("repo", REPO_KEY);
        String version = VersionInfo.getQpscVersion();
        root.addProperty("extension", "QPSC " + version);
        root.addProperty("app_version", version);
        root.addProperty("description", report.description());

        String sysinfo = report.sysinfo();
        if (sysinfo != null && !sysinfo.isBlank()) {
            root.addProperty("sysinfo", sysinfo);
        }

        Map<String, String> artifacts = report.artifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            JsonObject artifactsObj = new JsonObject();
            for (Map.Entry<String, String> e : artifacts.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    artifactsObj.addProperty(e.getKey(), e.getValue());
                }
            }
            root.add("artifacts", artifactsObj);
        }

        String shot = report.screenshotBase64();
        if (shot != null && !shot.isEmpty()) {
            JsonObject screenshot = new JsonObject();
            screenshot.addProperty("content_base64", shot);
            screenshot.addProperty("mime_type", "image/png");
            root.add("screenshot", screenshot);
        }

        return root.toString();
    }

    private static Result parseResponse(int status, String body) {
        JsonObject obj = null;
        try {
            obj = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception ignored) {
            // fall through to status-based handling
        }

        if (obj != null && obj.has("ok") && obj.get("ok").getAsBoolean()) {
            String url = obj.has("issue_url") && !obj.get("issue_url").isJsonNull()
                    ? obj.get("issue_url").getAsString()
                    : "";
            Integer number = obj.has("issue_number") && !obj.get("issue_number").isJsonNull()
                    ? obj.get("issue_number").getAsInt()
                    : null;
            return new Result(true, url, number, null);
        }

        // Error path: prefer the server's message, else a status-based one.
        if (obj != null && obj.has("error") && !obj.get("error").isJsonNull()) {
            return new Result(false, null, null, obj.get("error").getAsString());
        }
        return new Result(false, null, null, "Server returned HTTP " + status + ".");
    }
}
