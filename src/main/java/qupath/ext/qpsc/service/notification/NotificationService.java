package qupath.ext.qpsc.service.notification;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;

/**
 * Sends push notifications to external services when workflows complete or fail.
 *
 * <p>Notifications are fire-and-forget: sent on a background thread with short timeouts.
 * Failures are logged at DEBUG level and never block or interrupt workflows.
 *
 * <p>Currently supports <a href="https://ntfy.sh">ntfy.sh</a> as the notification provider.
 * Users subscribe to a topic on their phone (ntfy app for Android/iOS), then enter the
 * same topic name in QuPath preferences. No account or API key required.
 */
public final class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    private static final NotificationService INSTANCE = new NotificationService();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notification-sender");
        t.setDaemon(true);
        return t;
    });

    private NotificationService() {}

    public static NotificationService getInstance() {
        return INSTANCE;
    }

    /**
     * Send a notification if the user has enabled alerts for the given event type.
     * This method returns immediately; the notification is sent on a background thread.
     *
     * @param title    short title (e.g., "Stitching Complete")
     * @param message  body with actionable context (sample name, tile counts, timing)
     * @param priority notification priority level
     * @param event    the event type, checked against user preferences
     */
    public void notify(String title, String message, NotificationPriority priority, NotificationEvent event) {
        if (!QPPreferenceDialog.getNotificationsEnabled()) {
            return;
        }
        if (!isEventEnabled(event)) {
            return;
        }

        String topic = QPPreferenceDialog.getNotificationTopic();
        String server = QPPreferenceDialog.getNotificationServer();
        if (topic == null || topic.isBlank()) {
            logger.debug("Notification skipped: no topic configured");
            return;
        }

        executor.submit(() -> sendNtfy(server, topic, title, message, priority));
    }

    /**
     * Send a test notification to verify the configuration works.
     * Runs synchronously and returns the result.
     *
     * @return null if successful, or an error message string
     */
    public String sendTestNotification() {
        String topic = QPPreferenceDialog.getNotificationTopic();
        String server = QPPreferenceDialog.getNotificationServer();
        if (topic == null || topic.isBlank()) {
            return "No topic configured";
        }
        boolean ok = sendNtfy(
                server,
                topic,
                "QPSC Test Notification",
                "If you see this on your phone, notifications are working.",
                NotificationPriority.DEFAULT);
        return ok ? null : "Failed to send notification (check server/topic and internet connection)";
    }

    private boolean sendNtfy(String server, String topic, String title, String message, NotificationPriority priority) {
        try {
            String urlStr = server.endsWith("/") ? server + topic : server + "/" + topic;
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Title", title);
            conn.setRequestProperty("Priority", priority.ntfyValue());
            conn.setRequestProperty("Tags", "microscope");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(message.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                logger.debug("Notification sent: {} (HTTP {})", title, code);
                return true;
            } else {
                logger.debug("Notification failed: HTTP {} for topic '{}'", code, topic);
                return false;
            }
        } catch (Exception e) {
            logger.debug("Notification send failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean isEventEnabled(NotificationEvent event) {
        return switch (event) {
            case ACQUISITION_COMPLETE, ALL_ANGLES_COMPLETE -> QPPreferenceDialog.getNotifyOnAcquisition();
            case STITCHING_COMPLETE -> QPPreferenceDialog.getNotifyOnStitching();
            case ACQUISITION_ERROR, STITCHING_ERROR -> QPPreferenceDialog.getNotifyOnErrors();
        };
    }
}
