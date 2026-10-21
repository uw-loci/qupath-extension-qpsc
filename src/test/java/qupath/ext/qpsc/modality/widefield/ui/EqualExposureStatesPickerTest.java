package qupath.ext.qpsc.modality.widefield.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.Channel;

/**
 * The LC-PolScope state picker: every state always acquired, at ONE exposure. The ordinary channel
 * picker let each state take its own exposure, which biases retardance and orientation with
 * nothing visible in the images.
 */
class EqualExposureStatesPickerTest {

    @BeforeAll
    static void startToolkit() {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            Assumptions.assumeTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // started by another test in this JVM
        } catch (UnsupportedOperationException | InterruptedException noDisplay) {
            Assumptions.abort("No JavaFX toolkit available: " + noDisplay);
        }
    }

    private static List<Channel> states(double... exposures) {
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < exposures.length; i++) {
            out.add(new Channel("State" + i, "State" + i, exposures[i], List.of(), List.of(), null, 50));
        }
        return out;
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "FX work timed out");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    private static List<Node> all(Parent parent) {
        List<Node> out = new ArrayList<>();
        for (Node n : parent.getChildrenUnmodifiable()) {
            out.add(n);
            if (n instanceof Parent p) {
                out.addAll(all(p));
            }
        }
        return out;
    }

    @Test
    void everyStateIsAcquiredAtTheOneSharedExposure() throws Exception {
        Map<String, Double> overrides = onFx(() -> {
            // States arrive unequal from a bad YAML; the panel offers ONE value, the longest.
            WidefieldChannelBoundingBoxUI ui =
                    new WidefieldChannelBoundingBoxUI(states(33, 33, 40, 33, 33), "lcpolscope");
            ui.useEqualExposureStates("LC-PolScope states");
            new Scene((Parent) ui.getNode());
            List<Node> nodes = all((Parent) ui.getNode());
            GridPane grid = (GridPane) nodes.stream()
                    .filter(n -> n instanceof GridPane)
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    grid.getChildren().stream().noneMatch(n -> n instanceof Spinner<?> && n.isVisible()),
                    "no per-state exposure in the state table");
            // The dedicated-autofocus bar keeps its own spinners (autofocus only, never an image).
            List<Node> shared1 = nodes.stream()
                    .filter(n -> n instanceof Spinner<?> sp
                            && sp.getTooltip() != null
                            && sp.getTooltip().getText().startsWith("One exposure for all states"))
                    .toList();
            assertEquals(1, shared1.size(), "exactly one shared exposure control");
            @SuppressWarnings("unchecked")
            Spinner<Double> shared = (Spinner<Double>) shared1.get(0);
            assertEquals(40.0, shared.getValue(), 1e-9, "starts at the longest configured exposure");
            shared.getValueFactory().setValue(25.0);
            return ui.getAngleOverrides();
        });
        assertEquals(5, overrides.size(), "all five states, always");
        assertTrue(overrides.values().stream().allMatch(v -> v == 25.0), overrides.toString());
    }

    @Test
    void theStatePanelHidesUseSplitAndIntensityAndSaysWhatItIs() throws Exception {
        onFx(() -> {
            WidefieldChannelBoundingBoxUI ui =
                    new WidefieldChannelBoundingBoxUI(states(33, 33, 33, 33, 33), "lcpolscope");
            ui.useEqualExposureStates("LC-PolScope states");
            new Scene((Parent) ui.getNode());
            List<Node> nodes = all((Parent) ui.getNode());
            assertTrue(
                    nodes.stream().anyMatch(n -> n instanceof Label l && "LC-PolScope states".equals(l.getText())),
                    "retitled");
            assertFalse(
                    nodes.stream().anyMatch(n -> n instanceof Label l && "Fluorescence Channels".equals(l.getText())));
            GridPane grid = (GridPane) nodes.stream()
                    .filter(n -> n instanceof GridPane)
                    .findFirst()
                    .orElseThrow();
            for (Node n : grid.getChildren()) {
                Integer col = GridPane.getColumnIndex(n);
                int c = col == null ? 0 : col;
                if (c == 0 || c == 2 || c == 3 || c == 5) {
                    assertFalse(n.isVisible(), "column " + c + " hidden");
                }
            }
            for (Node n : grid.getChildren()) {
                Integer col = GridPane.getColumnIndex(n);
                if (col != null && col == 0 && n instanceof CheckBox cb) {
                    assertTrue(cb.isSelected(), "every state included");
                }
            }
            assertTrue(ui.getSplitChannelIds().isEmpty(), "states are never split out");
            assertTrue(ui.getChannelIntensityOverrides().isEmpty());
            // The non-extinction focus default is the handler's (LCPolScopeModalityHandlerTest);
            // here the focus choice must simply stay available.
            assertNotNull(ui.getFocusChannelId());
            return null;
        });
    }

    @Test
    void autofocusGetsItsOwnExposureInTheStatePicker() throws Exception {
        // The LC-PolScope Hamamatsu needs ~300 ms per state; a focus frame needs only contrast.
        // The focus bar used to wait for the master override, which the state picker hides.
        String key = "widefield.channel.af_channel.lcpolscope.";
        String[] saved = new String[4];
        String[] suffixes = {"enabled", "id", "exposure_ms", "intensity"};
        for (int i = 0; i < 4; i++) {
            saved[i] = qupath.ext.qpsc.preferences.PersistentPreferences.getStringPreference(key + suffixes[i], "");
        }
        try {
            var focus = onFx(() -> {
                WidefieldChannelBoundingBoxUI ui =
                        new WidefieldChannelBoundingBoxUI(states(300, 300, 300, 300, 300), "lcpolscope");
                ui.useEqualExposureStates("LC-PolScope states");
                new Scene((Parent) ui.getNode());
                List<Node> nodes = all((Parent) ui.getNode());
                CheckBox enable = (CheckBox) nodes.stream()
                        .filter(n -> n instanceof CheckBox cb && "Focus on a dedicated channel".equals(cb.getText()))
                        .findFirst()
                        .orElseThrow();
                assertFalse(enable.isDisabled(), "usable without the hidden master override");
                enable.setSelected(true);
                @SuppressWarnings("unchecked")
                Spinner<Double> exposure = (Spinner<Double>) nodes.stream()
                        .filter(n -> n instanceof Spinner<?> sp
                                && sp.getTooltip() != null
                                && sp.getTooltip().getText().startsWith("Exposure used for focus frames only"))
                        .findFirst()
                        .orElseThrow();
                assertFalse(exposure.isDisabled());
                exposure.getValueFactory().setValue(30.0);
                assertTrue(
                        nodes.stream()
                                .filter(n -> n instanceof Label l && "Intensity:".equals(l.getText()))
                                .noneMatch(Node::isVisible),
                        "the crystals have no intensity knob");
                return ui.getDedicatedFocusChannel();
            });
            assertNotNull(focus);
            assertEquals(30.0, focus.exposureMs(), 1e-9);
            assertEquals(null, focus.intensity());
            assertFalse("State0".equals(focus.channelId()), "not the extinction state by default");
        } finally {
            for (int i = 0; i < 4; i++) {
                qupath.ext.qpsc.preferences.PersistentPreferences.setStringPreference(key + suffixes[i], saved[i]);
            }
        }
    }

    @Test
    void theOrdinaryPickerIsUnchanged() throws Exception {
        onFx(() -> {
            WidefieldChannelBoundingBoxUI ui = new WidefieldChannelBoundingBoxUI(states(10, 20), "fluorescence");
            new Scene((Parent) ui.getNode());
            long spinners = all((Parent) ui.getNode()).stream()
                    .filter(n -> n instanceof Spinner<?> && n.isManaged())
                    .count();
            assertTrue(spinners >= 2, "per-channel exposures still offered");
            return null;
        });
    }
}
