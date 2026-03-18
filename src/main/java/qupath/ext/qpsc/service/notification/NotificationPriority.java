package qupath.ext.qpsc.service.notification;

/**
 * Priority levels for notifications, mapped to ntfy.sh priority values.
 */
public enum NotificationPriority {
    LOW("2"),
    DEFAULT("3"),
    HIGH("4"),
    URGENT("5");

    private final String ntfyValue;

    NotificationPriority(String ntfyValue) {
        this.ntfyValue = ntfyValue;
    }

    /** Returns the ntfy.sh priority header value. */
    public String ntfyValue() {
        return ntfyValue;
    }
}
