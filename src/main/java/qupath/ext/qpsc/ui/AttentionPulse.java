package qupath.ext.qpsc.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * A single reusable "attention pulse" that points the operator at the next action.
 *
 * <p>Animates a soft colored glow (a pulsing {@link DropShadow}) on ONE node at a time. Call
 * {@link #highlight(Node, String)} to move the pulse to a node (re-targeting the same node is a
 * no-op so it does not restart), or {@link #clear()} to stop and remove the effect. One instance
 * owns at most one active pulse, so wiring "the next step" is just repeated {@code highlight(...)}
 * calls as state advances.
 *
 * <p>FX-thread only. The glow is an overlay effect (it does not change layout), and clearing
 * restores the node's previous {@code null} effect.
 */
public final class AttentionPulse {

    private Timeline timeline;
    private Node target;

    /**
     * Moves the pulse to {@code node}, glowing in {@code colorHex} (e.g. {@code "#1565C0"}).
     * Passing the node that is already pulsing does nothing (keeps the animation smooth). Passing
     * {@code null} clears any active pulse.
     */
    public void highlight(Node node, String colorHex) {
        if (node == target && timeline != null) {
            return;
        }
        clear();
        if (node == null) {
            return;
        }
        target = node;
        DropShadow glow = new DropShadow(BlurType.GAUSSIAN, Color.web(colorHex), 0, 0.7, 0, 0);
        node.setEffect(glow);
        timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 0.0)),
                new KeyFrame(Duration.seconds(0.75), new KeyValue(glow.radiusProperty(), 20.0)));
        timeline.setAutoReverse(true);
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    /** Stops the pulse (if any) and removes the glow from the current target. */
    public void clear() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        if (target != null) {
            target.setEffect(null);
            target = null;
        }
    }
}
