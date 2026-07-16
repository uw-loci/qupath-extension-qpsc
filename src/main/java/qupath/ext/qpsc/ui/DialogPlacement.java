package qupath.ext.qpsc.ui;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Window;

/**
 * Screen-edge-aware window placement helpers for QPSC dialogs.
 *
 * <p>{@link #dockBeside} positions a child window beside a reference window on the side that has
 * room to the screen edge (right preferred, else left, else clamped), so chained dialogs sit next
 * to their predecessor instead of stacking on top of it. Call it from a {@code setOnShown} handler,
 * when the child's width/height are known.
 *
 * <p><b>Dialog Position Manager interaction.</b> The separate {@code dialog-manager} extension
 * observes every window by title: for a dialog the user has previously moved it re-applies the saved
 * position after show (a deferred {@code Platform.runLater}), and it leaves dialogs it has never seen
 * untouched. So this initial dock only takes effect for a first-seen dialog -- exactly the intended
 * fallback: seed a sensible position, then defer to whatever the user has since arranged.
 *
 * <p>The {@link #setBatchAnchor}/{@link #dockBesideBatchAnchor} pair lets the multi-slide workflow
 * register its always-open progress panel as the anchor for the per-slot dialog chain without the
 * dialog classes needing to reference the controller.
 */
public final class DialogPlacement {

    /** Default gap between docked windows, in px. */
    public static final double DEFAULT_GAP = 12.0;

    /** The anchor the multi-slide dialog chain docks beside (the progress panel), or null. */
    private static volatile Window batchAnchor;

    private DialogPlacement() {}

    /** Registers the anchor window the multi-slide per-slot dialogs dock beside. Null to clear. */
    public static void setBatchAnchor(Window anchor) {
        batchAnchor = anchor;
    }

    /** Clears the multi-slide dock anchor (call when the progress panel closes). */
    public static void clearBatchAnchor() {
        batchAnchor = null;
    }

    /**
     * Docks {@code child} beside the registered multi-slide anchor, if one is set (i.e. a batch is
     * running). No-op otherwise, so single-slide dialogs are unaffected. Safe to call from any
     * dialog's {@code setOnShown}.
     */
    public static void dockBesideBatchAnchor(Window child) {
        Window anchor = batchAnchor;
        if (anchor != null && anchor != child) {
            dockBeside(child, anchor);
        }
    }

    /** Docks {@code child} beside {@code owner} using {@link #DEFAULT_GAP}. */
    public static void dockBeside(Window child, Window owner) {
        dockBeside(child, owner, DEFAULT_GAP);
    }

    /**
     * Positions {@code child} beside {@code owner}: to the right when the child fits between the
     * owner and the screen's right edge, else to the left when it fits there, else centered within
     * the owner's screen. The child is clamped vertically to the owner's screen. No-op when either
     * window is null or the child has no size yet (call from {@code setOnShown}).
     */
    public static void dockBeside(Window child, Window owner, double gap) {
        if (child == null || owner == null) {
            return;
        }
        double childW = child.getWidth();
        double childH = child.getHeight();
        if (childW <= 0 || childH <= 0 || Double.isNaN(owner.getX()) || Double.isNaN(owner.getY())) {
            return;
        }

        // Use the visual bounds of the screen the owner's center sits on (multi-monitor aware).
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double ownerCx = owner.getX() + owner.getWidth() / 2.0;
        double ownerCy = owner.getY() + owner.getHeight() / 2.0;
        for (Screen s : Screen.getScreens()) {
            if (s.getBounds().contains(ownerCx, ownerCy)) {
                bounds = s.getVisualBounds();
                break;
            }
        }

        double rightX = owner.getX() + owner.getWidth() + gap;
        double leftX = owner.getX() - gap - childW;
        if (rightX + childW <= bounds.getMaxX()) {
            child.setX(rightX); // room on the right (preferred)
        } else if (leftX >= bounds.getMinX()) {
            child.setX(leftX); // else room on the left
        } else {
            // Neither side fits: center within the screen's usable width.
            child.setX(bounds.getMinX() + Math.max(0, (bounds.getWidth() - childW) / 2.0));
        }

        double y = owner.getY();
        if (y + childH > bounds.getMaxY()) {
            y = Math.max(bounds.getMinY(), bounds.getMaxY() - childH);
        }
        if (y < bounds.getMinY()) {
            y = bounds.getMinY();
        }
        child.setY(y);
    }
}
