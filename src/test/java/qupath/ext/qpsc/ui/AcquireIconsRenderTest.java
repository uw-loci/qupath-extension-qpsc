package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The acquire-button icons render at their intended size on the wizard's button blue. */
class AcquireIconsRenderTest {

    @BeforeAll
    static void startToolkit() {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            Assumptions.assumeTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // fine
        } catch (UnsupportedOperationException | InterruptedException noDisplay) {
            Assumptions.abort("No JavaFX toolkit available: " + noDisplay);
        }
    }

    @Test
    void bothIconsRenderAtButtonSize() throws Exception {
        AtomicReference<double[]> sizes = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Node box = AcquireIcons.boundingBox();
                Node tissue = AcquireIcons.tissue();
                HBox row = new HBox(24, new StackPane(box), new StackPane(tissue));
                row.setPadding(new Insets(12));
                row.setStyle("-fx-background-color: #2b5797;");
                new Scene(row);
                row.applyCss();
                row.layout();
                // Set ACQUIRE_ICONS_PNG to a path to look at the icons (4x scale).
                String out = System.getenv("ACQUIRE_ICONS_PNG");
                if (out != null) {
                    javafx.scene.SnapshotParameters p = new javafx.scene.SnapshotParameters();
                    p.setTransform(javafx.scene.transform.Transform.scale(4, 4));
                    WritableImage img = row.snapshot(p, null);
                    int w = (int) img.getWidth();
                    int h = (int) img.getHeight();
                    var buffered = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var reader = img.getPixelReader();
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            buffered.setRGB(x, y, reader.getArgb(x, y));
                        }
                    }
                    javax.imageio.ImageIO.write(buffered, "png", new java.io.File(out));
                }
                var b1 = box.getBoundsInParent();
                var b2 = tissue.getBoundsInParent();
                sizes.set(new double[] {b1.getWidth(), b1.getHeight(), b2.getWidth(), b2.getHeight()});
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        for (double v : sizes.get()) {
            assertTrue(v > 25 && v < 60, "icon dimension " + v + " px is button-sized");
        }
    }
}
