package qupath.ext.qpsc.controller.workflow;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.geometry.TransformationFunctions;
import qupath.ext.qpsc.service.microscope.MicroscopeController;
import qupath.ext.qpsc.ui.QpscWindows;
import qupath.ext.qpsc.ui.stagemap.StageMapWindow;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;

/**
 * Rough alignment from one click: the operator drives the stage to something they can
 * recognize on the slide (a tissue corner, a scratch, the label edge), then clicks the same spot
 * on the slide image in QuPath or on the Stage Map's macro overlay.
 *
 * <p>The alignment it starts from is the one the Existing Image workflow has just built from the
 * scanner preset and the green-box detection, in the open entry's frame. So where the scanned
 * image sits in the macro, the slide's placement in the insert and any Camera View rotation or
 * flip are already in it; the click corrects only the translation, exactly as single-tile
 * refinement does ({@link TransformationFunctions#addTranslationToScaledAffine}). Rotation and
 * scale stay the preset's, so the result is as good as the preset, and a refinement on a later
 * run can take it from there.
 *
 * <p>The slide image is the precise picture to click. A Stage Map click is placed by the map's
 * own drawing of the macro, so it is only as precise as the macro (tens of micrometers per
 * pixel) and as the map's placement of it.
 */
public final class ClickPositionRefinement {

    private static final Logger logger = LoggerFactory.getLogger(ClickPositionRefinement.class);

    private ClickPositionRefinement() {}

    /**
     * Shows the instruction window and waits for one click.
     *
     * @param gui QuPath
     * @param initialTransform the open entry's full-resolution pixel to stage transform
     * @return the refinement result: accepted with the corrected transform, skipped (keeps the
     *     initial one) or cancelled (no acquisition)
     */
    public static CompletableFuture<SingleTileRefinement.RefinementResult> performRefinement(
            QuPathGUI gui, AffineTransform initialTransform) {
        CompletableFuture<SingleTileRefinement.RefinementResult> future = new CompletableFuture<>();
        Platform.runLater(() -> show(gui, initialTransform, future));
        return future;
    }

    private static void show(
            QuPathGUI gui,
            AffineTransform initialTransform,
            CompletableFuture<SingleTileRefinement.RefinementResult> future) {
        Stage stage = new Stage();
        QpscWindows.track(stage);
        stage.setTitle("Click the stage position");
        if (gui != null && gui.getStage() != null) {
            stage.initOwner(gui.getStage());
        }
        Label text = new Label("1. Move the stage (Live Viewer or Stage Map) to a spot you can find on the slide "
                + "image: a tissue corner, a scratch, an edge.\n"
                + "2. Shift+click that same spot on the slide image in QuPath (most precise), or on the "
                + "Stage Map's slide overview. Ordinary clicks and double-clicks work as usual.\n"
                + "3. Check the shift shown below, then press Use this. Shift+click again to try another "
                + "spot.\n\n"
                + "The click corrects where the slide is; rotation and scale come from the scanner "
                + "alignment.");
        text.setWrapText(true);
        text.setMaxWidth(440);
        Label status = new Label("Waiting for a Shift+click...");
        status.setWrapText(true);
        status.setMaxWidth(440);
        Button use = new Button("Use this");
        use.setDisable(true);
        Button skip = new Button("Skip (keep the current alignment)");
        Button cancel = new Button("Cancel");
        HBox buttons = new HBox(8, use, skip, cancel);
        VBox root = new VBox(10, text, status, buttons);
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root));

        AtomicBoolean done = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<AffineTransform> pending =
                new java.util.concurrent.atomic.AtomicReference<>();
        QuPathViewer viewer = gui != null ? gui.getViewer() : null;
        // The image the alignment belongs to: a click on any other image means nothing here.
        Object slideImage = viewer != null ? viewer.getImageData() : null;
        java.util.concurrent.atomic.AtomicReference<EventHandler<MouseEvent>> viewerHandler =
                new java.util.concurrent.atomic.AtomicReference<>();

        Runnable disarm = () -> {
            StageMapWindow.disarmPositionPick();
            if (viewer != null && viewerHandler.get() != null) {
                viewer.getView().removeEventFilter(MouseEvent.MOUSE_CLICKED, viewerHandler.get());
            }
        };
        java.util.function.Consumer<SingleTileRefinement.RefinementResult> finish = r -> {
            if (done.compareAndSet(false, true)) {
                disarm.run();
                stage.close();
                future.complete(r);
            }
        };

        viewerHandler.set(e -> {
            if (e.getButton() != MouseButton.PRIMARY
                    || e.getClickCount() != 1
                    || !e.isShiftDown()
                    || !e.isStillSincePress()) {
                return;
            }
            e.consume();
            if (viewer.getImageData() != slideImage) {
                status.setText("That is not the slide being aligned. Open it again and Shift+click on it, or "
                        + "Shift+click on the Stage Map.");
                return;
            }
            Point2D p = viewer.componentPointToImagePoint(e.getX(), e.getY(), null, true);
            onPixel(new double[] {p.getX(), p.getY()}, "slide image", initialTransform, status, use, pending);
        });
        if (viewer != null) {
            viewer.getView().addEventFilter(MouseEvent.MOUSE_CLICKED, viewerHandler.get());
        }
        armStageMap(initialTransform, status, use, pending, done);

        use.setOnAction(e -> {
            AffineTransform refined = pending.get();
            if (refined != null) {
                Dialogs.showInfoNotification(
                        "Click the stage position", "Alignment corrected and saved for this slide.");
                finish.accept(SingleTileRefinement.RefinementResult.accepted(refined, null));
            }
        });

        skip.setOnAction(e -> finish.accept(SingleTileRefinement.RefinementResult.skipped(initialTransform, null)));
        cancel.setOnAction(e -> finish.accept(SingleTileRefinement.RefinementResult.cancelled(initialTransform, null)));
        stage.setOnCloseRequest(
                e -> finish.accept(SingleTileRefinement.RefinementResult.cancelled(initialTransform, null)));
        stage.show();
    }

    /** Arms the Stage Map's one-shot Shift+click pick, re-arming after each pick until the window closes. */
    private static void armStageMap(
            AffineTransform initialTransform,
            Label status,
            Button use,
            java.util.concurrent.atomic.AtomicReference<AffineTransform> pending,
            AtomicBoolean done) {
        if (done.get()) {
            return;
        }
        StageMapWindow.armPositionPick(mapStage -> Platform.runLater(() -> {
            if (done.get()) {
                return;
            }
            double[] pixel = pixelForStagePoint(initialTransform, mapStage);
            if (pixel == null) {
                status.setText("That Stage Map point could not be placed on the slide image; Shift+click again.");
            } else {
                onPixel(pixel, "Stage Map", initialTransform, status, use, pending);
            }
            armStageMap(initialTransform, status, use, pending, done);
        }));
    }

    /**
     * Reads the stage, computes the corrected alignment for the clicked pixel and offers it;
     * nothing is saved until Use this.
     */
    private static void onPixel(
            double[] pixel,
            String where,
            AffineTransform initialTransform,
            Label status,
            Button use,
            java.util.concurrent.atomic.AtomicReference<AffineTransform> pending) {
        use.setDisable(true);
        pending.set(null);
        status.setText("Reading the stage position...");
        Thread t = new Thread(
                () -> {
                    double[] stage;
                    try {
                        stage = MicroscopeController.getInstance().getStagePositionXY();
                    } catch (Exception ex) {
                        logger.warn("Click position refinement: could not read the stage: {}", ex.getMessage());
                        Platform.runLater(() -> status.setText(
                                "Could not read the stage position (" + ex.getMessage() + "); Shift+click again."));
                        return;
                    }
                    AffineTransform refined = refine(initialTransform, pixel, stage);
                    double[] before = new double[2];
                    initialTransform.transform(pixel, 0, before, 0, 1);
                    logger.info(
                            "Click position refinement ({}): pixel ({}, {}) is at stage ({}, {}); the alignment "
                                    + "had it at ({}, {}), a shift of ({}, {}) um",
                            where,
                            fmt(pixel[0]),
                            fmt(pixel[1]),
                            fmt(stage[0]),
                            fmt(stage[1]),
                            fmt(before[0]),
                            fmt(before[1]),
                            fmt(stage[0] - before[0]),
                            fmt(stage[1] - before[1]));
                    Platform.runLater(() -> {
                        pending.set(refined);
                        use.setDisable(false);
                        status.setText(String.format(
                                Locale.ROOT,
                                "From the %s click, the slide is %.0f um in X and %.0f um in Y from where the "
                                        + "alignment had it (stage now at %.0f, %.0f um). Use this, or Shift+click "
                                        + "another spot.",
                                where,
                                stage[0] - before[0],
                                stage[1] - before[1],
                                stage[0],
                                stage[1]));
                    });
                },
                "qpsc-click-position");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The alignment corrected so {@code pixel} maps onto {@code stage}: the linear part (scale,
     * rotation, flip) kept, the translation solved (pure; tested).
     */
    static AffineTransform refine(AffineTransform initial, double[] pixel, double[] stage) {
        return TransformationFunctions.addTranslationToScaledAffine(initial, pixel, stage);
    }

    /** The slide-image pixel the alignment puts at a stage point, or null (pure; tested). */
    static double[] pixelForStagePoint(AffineTransform fullResToStage, double[] stagePoint) {
        if (fullResToStage == null || stagePoint == null || stagePoint.length < 2) {
            return null;
        }
        try {
            double[] out = new double[2];
            fullResToStage.inverseTransform(stagePoint, 0, out, 0, 1);
            return out;
        } catch (NoninvertibleTransformException e) {
            return null;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
