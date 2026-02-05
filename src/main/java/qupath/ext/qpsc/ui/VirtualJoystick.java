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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * A circular virtual joystick widget for continuous stage movement.
 *
 * <p>The joystick provides a drag-based interface where displacement from center
 * controls both direction and speed of stage movement. A quadratic response curve
 * gives fine control near center and full speed at the edge.
 *
 * <p>Movement commands are throttled using a "latest-target-wins" strategy to prevent
 * command flooding. When moves are requested faster than the hardware can execute them,
 * intermediate positions are skipped and only the most recent target is sent.
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

    // Minimum delay between move commands (ms). The server takes ~500ms per move,
    // so we rate-limit to prevent flooding. This value can be tuned if needed.
    private static final long MIN_MOVE_INTERVAL_MS = 400;

    // Throttling: single-threaded executor ensures moves are sequential
    private final ExecutorService moveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Joystick-Move-Executor");
        t.setDaemon(true);
        return t;
    });

    // Pending target for latest-wins semantics
    private final AtomicReference<double[]> pendingTarget = new AtomicReference<>(null);
    private final AtomicBoolean moveInProgress = new AtomicBoolean(false);
    private volatile long lastMoveTime = 0;

    private double knobOffsetX = 0;
    private double knobOffsetY = 0;
    private double maxStepUm = 100;
    private BiConsumer<Double, Double> movementCallback;
    private Runnable startCallback;

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
            // Notify listener that joystick started (for position sync)
            if (startCallback != null) {
                try {
                    startCallback.run();
                } catch (Exception e) {
                    logger.warn("Joystick start callback failed: {}", e.getMessage());
                }
            }
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

        // Store the latest delta request
        pendingTarget.set(new double[]{deltaX, deltaY});

        // If no move is in progress, start one
        if (moveInProgress.compareAndSet(false, true)) {
            executeNextMove();
        }
        // Otherwise, the pending target will be picked up when current move completes
    }

    /**
     * Executes the next pending move on a background thread.
     * Uses latest-target-wins semantics: if multiple deltas were queued while
     * a move was in progress, only the most recent one is executed.
     *
     * <p>Rate-limits commands to prevent server flooding, since the server
     * does not send acknowledgments for move commands.
     */
    private void executeNextMove() {
        moveExecutor.submit(() -> {
            try {
                // Get the latest pending target (may be newer than what triggered this call)
                double[] target = pendingTarget.getAndSet(null);
                if (target == null) {
                    // Nothing pending, we're done
                    moveInProgress.set(false);
                    return;
                }

                // Rate-limit: wait until minimum interval has passed since last move
                long now = System.currentTimeMillis();
                long elapsed = now - lastMoveTime;
                if (elapsed < MIN_MOVE_INTERVAL_MS) {
                    long sleepTime = MIN_MOVE_INTERVAL_MS - elapsed;
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        moveInProgress.set(false);
                        return;
                    }
                    // After sleeping, check if a newer target arrived
                    double[] newerTarget = pendingTarget.getAndSet(null);
                    if (newerTarget != null) {
                        target = newerTarget; // Use the newer position
                    }
                }

                // Execute the move (note: this returns immediately, server handles async)
                try {
                    movementCallback.accept(target[0], target[1]);
                    lastMoveTime = System.currentTimeMillis();
                } catch (Exception e) {
                    logger.warn("Joystick movement callback failed: {}", e.getMessage());
                }

                // Check if another target was queued while we were moving
                if (pendingTarget.get() != null) {
                    // More work to do, execute next move
                    executeNextMove();
                } else {
                    // No more pending moves
                    moveInProgress.set(false);
                }
            } catch (Exception e) {
                logger.error("Error in joystick move executor: {}", e.getMessage());
                moveInProgress.set(false);
            }
        });
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
     * Sets a callback invoked when the user starts dragging the joystick.
     * This is useful for syncing the position tracking before movement begins.
     *
     * @param callback called on mouse press before movement starts
     */
    public void setStartCallback(Runnable callback) {
        this.startCallback = callback;
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
     * Stops the movement timer and executor. Call on dialog close to prevent leaks.
     */
    public void stop() {
        movementTimer.stop();

        // Clear any pending moves and shut down executor
        pendingTarget.set(null);
        moveExecutor.shutdownNow();

        double cx = getPrefWidth() / 2;
        double cy = getPrefHeight() / 2;
        resetKnob(cx, cy);
    }
}
