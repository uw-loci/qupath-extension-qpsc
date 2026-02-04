package qupath.ext.qpsc.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

/**
 * A circular virtual joystick widget for continuous stage movement.
 *
 * <p>The joystick provides a drag-based interface where displacement from center
 * controls both direction and speed of stage movement. A quadratic response curve
 * gives fine control near center and full speed at the edge.
 *
 * <p>Movement ticks are fired on a 150ms interval via a JavaFX Timeline while
 * the knob is displaced beyond the dead zone (10% of radius).
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class VirtualJoystick extends Pane {

    private static final Logger logger = LoggerFactory.getLogger(VirtualJoystick.class);

    private static final double DEAD_ZONE = 0.1;
    private static final Duration TICK_INTERVAL = Duration.millis(150);

    private final double radius;
    private final Circle knob;
    private final Timeline movementTimer;

    private double knobOffsetX = 0;
    private double knobOffsetY = 0;
    private double maxStepUm = 100;
    private BiConsumer<Double, Double> movementCallback;

    /**
     * Creates a virtual joystick with the specified outer radius.
     *
     * @param radius the radius of the outer circle in pixels
     */
    public VirtualJoystick(double radius) {
        this.radius = radius;
        double size = radius * 2 + 20; // padding
        setPrefSize(size, size);
        setMinSize(size, size);
        setMaxSize(size, size);

        double cx = size / 2;
        double cy = size / 2;

        // Outer circle
        Circle outer = new Circle(cx, cy, radius);
        outer.setFill(Color.rgb(50, 50, 55));
        outer.setStroke(Color.rgb(120, 120, 130));
        outer.setStrokeWidth(2);

        // Cross-hair lines
        Line hLine = new Line(cx - radius * 0.7, cy, cx + radius * 0.7, cy);
        hLine.setStroke(Color.rgb(80, 80, 90));
        hLine.setStrokeWidth(1);

        Line vLine = new Line(cx, cy - radius * 0.7, cx, cy + radius * 0.7);
        vLine.setStroke(Color.rgb(80, 80, 90));
        vLine.setStrokeWidth(1);

        // Draggable knob
        knob = new Circle(cx, cy, 10);
        knob.setFill(Color.DODGERBLUE);
        knob.setStroke(Color.rgb(30, 100, 200));
        knob.setStrokeWidth(1.5);

        getChildren().addAll(outer, hLine, vLine, knob);

        // Movement timer
        movementTimer = new Timeline(new KeyFrame(TICK_INTERVAL, e -> onTick()));
        movementTimer.setCycleCount(Timeline.INDEFINITE);

        // Mouse interaction
        setOnMousePressed(event -> {
            updateKnobPosition(event.getX() - cx, event.getY() - cy);
            movementTimer.play();
        });

        setOnMouseDragged(event -> {
            updateKnobPosition(event.getX() - cx, event.getY() - cy);
        });

        setOnMouseReleased(event -> {
            movementTimer.stop();
            resetKnob(cx, cy);
        });
    }

    private void updateKnobPosition(double dx, double dy) {
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > radius) {
            // Clamp to outer circle
            dx = dx / dist * radius;
            dy = dy / dist * radius;
        }
        knobOffsetX = dx;
        knobOffsetY = dy;

        double cx = getPrefWidth() / 2;
        double cy = getPrefHeight() / 2;
        knob.setCenterX(cx + dx);
        knob.setCenterY(cy + dy);
    }

    private void resetKnob(double cx, double cy) {
        knobOffsetX = 0;
        knobOffsetY = 0;
        knob.setCenterX(cx);
        knob.setCenterY(cy);
    }

    private void onTick() {
        if (movementCallback == null) {
            return;
        }
        double dist = Math.sqrt(knobOffsetX * knobOffsetX + knobOffsetY * knobOffsetY);
        double normalizedDist = dist / radius;

        if (normalizedDist < DEAD_ZONE) {
            return;
        }

        // Quadratic response: 20% deflection = 4% max speed
        double scaledMagnitude = normalizedDist * normalizedDist * maxStepUm;
        double angle = Math.atan2(knobOffsetY, knobOffsetX);
        double deltaX = Math.cos(angle) * scaledMagnitude;
        double deltaY = Math.sin(angle) * scaledMagnitude;

        try {
            movementCallback.accept(deltaX, deltaY);
        } catch (Exception e) {
            logger.warn("Joystick movement callback failed: {}", e.getMessage());
        }
    }

    /**
     * Sets the callback invoked each tick with (deltaX_um, deltaY_um).
     *
     * @param callback receives displacement in micrometers per tick
     */
    public void setMovementCallback(BiConsumer<Double, Double> callback) {
        this.movementCallback = callback;
    }

    /**
     * Sets the maximum displacement per tick at full deflection.
     *
     * @param maxStepUm maximum step in micrometers
     */
    public void setMaxStepUm(double maxStepUm) {
        this.maxStepUm = maxStepUm;
    }

    /**
     * Stops the movement timer. Call on dialog close to prevent leaks.
     */
    public void stop() {
        movementTimer.stop();
        double cx = getPrefWidth() / 2;
        double cy = getPrefHeight() / 2;
        resetKnob(cx, cy);
    }
}
