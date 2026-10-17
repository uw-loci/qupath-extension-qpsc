package qupath.ext.qpsc.modality.lcpolscope.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The setup window opens, shows the status box, and lists the three steps in order. */
class LCPolScopeSetupWindowTest {

    @BeforeAll
    static void startToolkit() {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            Assumptions.assumeTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // started elsewhere
        } catch (UnsupportedOperationException | InterruptedException noDisplay) {
            Assumptions.abort("No JavaFX toolkit available: " + noDisplay);
        }
    }

    private static List<Node> all(Parent p) {
        List<Node> out = new ArrayList<>();
        for (Node n : p.getChildrenUnmodifiable()) {
            out.add(n);
            if (n instanceof Parent q) out.addAll(all(q));
        }
        return out;
    }

    @Test
    void opensWithTheStatusAndTheThreeStepsInOrder() throws Exception {
        AtomicReference<List<String>> buttons = new AtomicReference<>();
        AtomicReference<List<String>> headings = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                LCPolScopeSetupWindow.show();
                Stage stage = (Stage) Window.getWindows().stream()
                        .filter(w -> w instanceof Stage s && "LC-PolScope Setup".equals(s.getTitle()))
                        .findFirst()
                        .orElseThrow();
                stage.getScene().getRoot().applyCss();
                // The ScrollPane's content is not a child until skinned; reach it directly.
                Parent content = (Parent)
                        ((javafx.scene.control.ScrollPane) stage.getScene().getRoot()).getContent();
                List<Node> nodes = all(content);
                buttons.set(nodes.stream()
                        .filter(n -> n instanceof Button)
                        .map(n -> ((Button) n).getText())
                        .toList());
                headings.set(nodes.stream()
                        .filter(n -> n instanceof Label l && l.getText().matches("\\d\\..*|Ready to acquire\\?"))
                        .map(n -> ((Label) n).getText())
                        .toList());
                stage.close();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals("Ready to acquire?", headings.get().get(0));
        assertTrue(headings.get().get(1).startsWith("1. Camera"), headings.get().toString());
        assertTrue(
                headings.get().get(2).startsWith("2. Calibrate"), headings.get().toString());
        assertTrue(
                headings.get().get(3).startsWith("3. Background"),
                headings.get().toString());
        assertTrue(
                buttons.get().containsAll(List.of("Recheck", "Calibrate Liquid Crystals...", "Collect background...")),
                buttons.get().toString());
    }
}
