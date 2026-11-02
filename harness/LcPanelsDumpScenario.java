import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import qupath.ext.qpsc.service.microscope.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

public final class LcPanelsDumpScenario {
    private static final StringBuilder out = new StringBuilder();
    private static String configDir;
    private static String scratchDir;

    public static void main(String[] args) throws Exception {
        String config = args[0];
        Path outFile = Path.of(args[1]);
        configDir = new File(config).getAbsoluteFile().getParent();
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(170_000); } catch (InterruptedException e) { return; }
            out.append("WATCHDOG TIMEOUT\n");
            write(outFile);
            Runtime.getRuntime().halt(3);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await();
        Platform.setImplicitExit(false);
        QPPreferenceDialog.setMicroscopeConfigFileProperty(config);
        QuPathGUI gui = onFx(QuPathGUI::createHiddenInstance);
        scratchDir = Files.createTempDirectory("lcpanels").toString();

        // ---- Live Viewer camera tab ----
        Class<?> cls = Class.forName("qupath.ext.qpsc.ui.liveviewer.StageControlPanel");
        Object panel = onFx(() -> cls.getConstructor().newInstance());
        Method rebuild = cls.getDeclaredMethod("rebuildCameraModContent", String.class);
        rebuild.setAccessible(true);
        javafx.scene.layout.VBox content = (javafx.scene.layout.VBox) field(panel, "cameraModContent");
        javafx.scene.control.Label status = (javafx.scene.control.Label) field(panel, "cameraStatusLabel");
        Object mgr = field(panel, "mgr");
        @SuppressWarnings("unchecked")
        java.util.Set<String> mods = ((Map<String, Object>) mgr.getClass().getMethod("getSection", String[].class)
                .invoke(mgr, (Object) new String[] {"modalities"})).keySet();
        for (String m : new ArrayList<>(mods)) {
            onFx(() -> rebuild.invoke(panel, m));
            Thread.sleep(800);
            dumpCamera("camera tab modality=" + m, content, status);
        }
        String lc = mods.stream().filter(m -> m.toLowerCase().startsWith("lc")).findFirst().orElse(null);
        out.append("lc modality: ").append(lc).append('\n');
        if (lc != null) {
            onFx(() -> rebuild.invoke(panel, lc));
            Thread.sleep(800);
            // Bad exposure, committed with Enter.
            onFx(() -> {
                TextField exp = (TextField) find(content, n -> n instanceof TextField t && t.getPrefWidth() == 70);
                exp.setText("-3");
                exp.fireEvent(new javafx.event.ActionEvent());
                return null;
            });
            Thread.sleep(400);
            dumpCamera("lc bad exposure", content, status);
            onFx(() -> {
                TextField exp = (TextField) find(content, n -> n instanceof TextField t && t.getPrefWidth() == 70);
                exp.setText("42");
                TextField target = (TextField) find(content, n -> n instanceof TextField t && t.getPrefWidth() == 40);
                target.setText("150");
                button(content, "Measure all states").fire();
                return null;
            });
            Thread.sleep(400);
            dumpCamera("lc bad target", content, status);
            onFx(() -> {
                ((TextField) find(content, n -> n instanceof TextField t && t.getPrefWidth() == 40)).setText("75");
                button(content, "Measure all states").fire();
                return null;
            });
            Thread.sleep(2500);
            dumpCamera("lc measure (no server)", content, status);
            // Save, answered Cancel.
            cancelPromptsSoon();
            onFx(() -> {
                button(content, "Save to Profile").fire();
                return null;
            });
            Thread.sleep(1500);
            dumpCamera("lc save canceled", content, status);
            // Radio buttons: view a state (no server: apply does nothing).
            onFx(() -> {
                Node rb = find(content, n -> n instanceof RadioButton);
                ((RadioButton) rb).fire();
                return null;
            });
            Thread.sleep(500);
            dumpCamera("lc view state", content, status);
        }

        Thread.sleep(4500); // let the prompt canceller finish
        // ---- Background Collection ----
        Class<?> bc = Class.forName("qupath.ext.qpsc.ui.background.BackgroundCollectionController");
        Platform.runLater(() -> {
            try { bc.getMethod("showDialog").invoke(null); } catch (Throwable t) { out.append("showDialog threw ").append(cause(t)).append('\n'); }
        });
        Thread.sleep(3000);
        DialogPane bgPane = onFx(() -> {
            for (javafx.stage.Window w : new ArrayList<>(javafx.stage.Window.getWindows())) {
                if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) return p;
            }
            return null;
        });
        out.append("background dialog: ").append(bgPane != null).append('\n');
        if (bgPane != null) {
            @SuppressWarnings("unchecked")
            ComboBox<String> modBox = (ComboBox<String>) onFx(() -> find(bgPane.getContent(),
                    n -> n instanceof ComboBox<?> c && c.getItems().stream().anyMatch(i -> mods.contains(String.valueOf(i)))));
            out.append("modality combo found: ").append(modBox != null).append('\n');
            if (modBox != null) {
                for (String m : new ArrayList<>(onFx(() -> new ArrayList<>(modBox.getItems())))) {
                    onFx(() -> { modBox.setValue(m); return null; });
                    Thread.sleep(1200);
                    onFx(() -> { out.append("=== background modality=").append(m).append('\n'); node(bgPane.getContent(), 0); return null; });
                }
            }
        }
        write(outFile);
        System.out.println("[PASS] wrote " + outFile);
        Runtime.getRuntime().halt(0);
    }

    private static void dumpCamera(String title, Node content, javafx.scene.control.Label status) throws Exception {
        onFx(() -> {
            out.append("=== ").append(title).append('\n');
            out.append("status=[").append(status.getText()).append("] style=[").append(status.getStyle()).append("]\n");
            node(content, 0);
            return null;
        });
    }

    private static Node find(Node n, java.util.function.Predicate<Node> p) {
        if (n == null) return null;
        if (p.test(n)) return n;
        java.util.List<Node> kids = new ArrayList<>();
        if (n instanceof TitledPane t) kids.add(t.getContent());
        else if (n instanceof ScrollPane s) kids.add(s.getContent());
        else if (n instanceof javafx.scene.control.TabPane tp) tp.getTabs().forEach(t -> kids.add(t.getContent()));
        else if (!(n instanceof Control) && n instanceof Parent par) kids.addAll(par.getChildrenUnmodifiable());
        for (Node k : kids) {
            Node f = find(k, p);
            if (f != null) return f;
        }
        return null;
    }

    private static Button button(Node root, String text) {
        return (Button) find(root, n -> n instanceof Button b && text.equals(b.getText()));
    }

    private static void cancelPromptsSoon() {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(200);
                    Platform.runLater(() -> {
                        for (javafx.stage.Window w : new ArrayList<>(javafx.stage.Window.getWindows())) {
                            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) {
                                out.append("[prompt: ").append(p.getHeaderText()).append(" | ")
                                        .append(p.getContent() instanceof javafx.scene.control.Label l ? l.getText() : "").append("]\n");
                                for (ButtonType bt : p.getButtonTypes()) {
                                    if (bt.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE) ((Button) p.lookupButton(bt)).fire();
                                }
                            }
                        }
                    });
                }
            } catch (InterruptedException e) { }
        });
        t.setDaemon(true);
        t.start();
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException e) { }
        }
        throw new NoSuchFieldException(name);
    }

    /** One line per node: class, visible/managed/disabled, grid cell, style and control state. */
    private static void node(Node n, int depth) {
        if (n == null) {
            return;
        }
        StringBuilder l = new StringBuilder("  ".repeat(depth));
        l.append(n.getClass().getSimpleName());
        l.append(" v=").append(n.isVisible() ? 1 : 0);
        l.append(" m=").append(n.isManaged() ? 1 : 0);
        l.append(" d=").append(n.isDisabled() ? 1 : 0);
        if (n.getParent() instanceof GridPane) {
            l.append(" grid=").append(GridPane.getRowIndex(n)).append(',').append(GridPane.getColumnIndex(n));
            l.append(',').append(GridPane.getRowSpan(n)).append(',').append(GridPane.getColumnSpan(n));
        }
        if (n.getStyle() != null && !n.getStyle().isEmpty()) {
            l.append(" style=[").append(n.getStyle()).append(']');
        }
        if (n instanceof Control c) {
            if (c.getTooltip() != null) {
                l.append(" tip=[").append(c.getTooltip().getText().replace('\n', '|')).append(']');
            }
            if (c instanceof Labeled lab) {
                l.append(" text=[").append(lab.getText()).append(']');
            }
            if (c instanceof TextInputControl t) {
                l.append(" text=[").append(t.getText()).append("] prompt=[").append(t.getPromptText()).append(']');
            }
            if (c instanceof CheckBox b) {
                l.append(" sel=").append(b.isSelected());
            }
            if (c instanceof RadioButton b) {
                l.append(" sel=").append(b.isSelected());
            }
            if (c instanceof ComboBox<?> b) {
                l.append(" value=[").append(b.getValue()).append("] items=").append(b.getItems());
            }
            if (c instanceof Spinner<?> s) {
                l.append(" value=[").append(s.getValue()).append(']');
                if (s.getValueFactory() instanceof SpinnerValueFactory.DoubleSpinnerValueFactory f) {
                    l.append(" range=").append(f.getMin()).append("..").append(f.getMax());
                    l.append(" step=").append(f.getAmountToStepBy());
                }
            }
            if (c instanceof TitledPane t) {
                l.append(" expanded=").append(t.isExpanded());
                l.append(" collapsible=").append(t.isCollapsible());
                l.append(" animated=").append(t.isAnimated());
            }
        }
        out.append(normalize(l.toString())).append('\n');
        if (n instanceof TitledPane t) {
            node(t.getContent(), depth + 1);
        } else if (n instanceof ScrollPane s) {
            node(s.getContent(), depth + 1);
        } else if (!(n instanceof Control) && n instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                node(child, depth + 1);
            }
        }
    }

    /** Replaces what differs between two runs of the same build. */
    private static String normalize(String s) {
        String r = s;
        if (configDir != null) {
            r = r.replace(configDir, "<CONFIG_DIR>");
        }
        if (scratchDir != null) {
            r = r.replace(scratchDir, "<SCRATCH>");
        }
        return r.replaceAll("@[0-9a-f]{5,}", "@X").replaceAll("Est\\. Completion: [^\\]]+", "Est. Completion: NOW");
    }

    private static String cause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.toString();
    }

    private static void write(Path file) {
        try {
            Files.writeString(file, out.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static <T> T onFx(Callable<T> c) throws Exception {
        FutureTask<T> task = new FutureTask<>(c);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }

    private static <T> T unusedOnFxOrNull(Callable<T> c) {
        FutureTask<T> task = new FutureTask<>(c);
        Platform.runLater(task);
        try {
            return task.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            out.append("failed: ").append(cause(e)).append('\n');
            return null;
        }
    }
}
