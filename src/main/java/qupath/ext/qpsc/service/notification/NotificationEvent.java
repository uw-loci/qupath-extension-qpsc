package qupath.ext.qpsc.service.notification;

/**
 * Events that can trigger notifications.
 * Each event maps to a user-toggleable preference.
 */
public enum NotificationEvent {
    ACQUISITION_COMPLETE,
    STITCHING_COMPLETE,
    ACQUISITION_ERROR,
    STITCHING_ERROR,
    ALL_ANGLES_COMPLETE
}
