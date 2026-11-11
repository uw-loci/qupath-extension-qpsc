package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The held-tiles window opens without blocking and offers both choices for each region. */
class HeldTilesWindowTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void startToolkit() {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            Assumptions.assumeTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // Another test in this JVM started it; fine.
        } catch (UnsupportedOperationException | InterruptedException noDisplay) {
            Assumptions.abort("No JavaFX toolkit available: " + noDisplay);
        }
    }

    @Test
    void holdingTwoRegionsShowsOneWindowWithBothChoicesEach() throws Exception {
        File region = Files.createDirectories(dir.resolve("42606_60590")).toFile();
        File other = Files.createDirectories(dir.resolve("41000_50000")).toFile();
        long t0 = System.nanoTime();
        HeldTilesWindow.hold(new HeldTilesWindow.HeldRegion(
                region,
                "MetroHealth_189",
                2964,
                "Stage move still failing after user intervention: Position out of range"));
        HeldTilesWindow.hold(new HeldTilesWindow.HeldRegion(other, "MetroHealth_189", 12, "canceled"));
        assertTrue((System.nanoTime() - t0) / 1e6 < 2000, "hold returns at once");

        Thread.sleep(1500);
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<Integer> buttons = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Stage held = null;
                for (Window w : Window.getWindows()) {
                    if (w instanceof Stage s && "Held tiles".equals(s.getTitle()) && s.isShowing()) {
                        held = s;
                    }
                }
                assertNotNull(held, "the window is showing");
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (var node : held.getScene().getRoot().lookupAll("*")) {
                    if (node instanceof Labeled l) {
                        sb.append(l.getText()).append('\n');
                    }
                    if (node instanceof Button) {
                        n++;
                    }
                }
                String out = System.getenv("HELD_TILES_SNAPSHOT");
                if (out != null) {
                    var img = held.getScene().snapshot(null);
                    int w = (int) img.getWidth();
                    int h = (int) img.getHeight();
                    var bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var reader = img.getPixelReader();
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            bi.setRGB(x, y, reader.getArgb(x, y));
                        }
                    }
                    javax.imageio.ImageIO.write(bi, "png", new File(out));
                }
                text.set(sb.toString());
                buttons.set(n);
                held.close();
            } catch (Throwable t) {
                text.set("FAILED " + t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue(text.get().contains("MetroHealth_189 / 42606_60590: 2964 tile images kept"), text.get());
        assertTrue(text.get().contains("MetroHealth_189 / 41000_50000: 12 tile images kept"), text.get());
        assertEquals(4, buttons.get(), "Stitch and Delete for each region");
    }
}
