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
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * Real-QuPath scenario: opens one of the two acquisition dialogs (bounded acquisition, or
 * acquisition from an existing image) in a hidden QuPathGUI, drives it, and writes the state of
 * every control after each step to a text file.
 *
 * <p>The steps: every modality, then every detector and objective of each; a bad sample name; an
 * empty, a relative and a full projects folder (no project open only); Z-stack on; for the
 * bounded dialog a region, then time-lapse on and off; learned acquisition timing; an MDA export;
 * and Start. The file ends with the dialog's result and every preference the run left behind.
 *
 * <p>The dump is the point: run the scenario against two builds and diff the files to see
 * exactly what a change did to the dialogs. Paths that differ between runs are replaced with
 * {@code <CONFIG_DIR>} and {@code <SCRATCH>}, and the projected completion time with
 * {@code NOW}, so two runs of the same build give identical files. The checks only cover
 * properties both dialogs must have.
 *
 * <p>args: {@code configFile U|E 0|1 outFile}. {@code U} is the bounded dialog, {@code E} the
 * existing-image dialog; {@code 1} opens a project first. The config is read, and its folder
 * may be written to (saved transforms), so pass a copy, not a checkout of
 * microscope_configurations. For example:
 *
 * <pre>
 * cp -r ~/QPSC_Project/microscope_configurations /tmp/cfg
 * tools/qp-harness/bin/qp-gui \
 *     qupath-extension-qpsc/harness/AcquisitionDialogDumpScenario.java \
 *     qupath-extension-qpsc/build/libs/qupath-extension-qpsc-0.13.0-all.jar \
 *     /tmp/cfg/config_PPM.yml U 0 /tmp/dump-U-0.txt
 * </pre>
 *
 * Use a fresh {@code QP_HARNESS_WORK} for each run: the dump includes the preferences, so a
 * run that starts from another run's preferences does not compare.
 */
public final class AcquisitionDialogDumpScenario {

    private static final StringBuilder out = new StringBuilder();
    private static final Set<String> promptsSeen = new LinkedHashSet<>();
    private static int failures = 0;
    private static Object builder;
    private static Dialog<?> dialog;
    private static String configDir;
    private static String scratchDir;

    private static void ok(String what) {
        System.out.println("[PASS] " + what);
    }

    private static void bad(String what) {
        System.out.println("[FAIL] " + what);
        failures++;
    }

    private static void check(boolean condition, String what) {
        if (condition) ok(what);
        else bad(what);
    }

    private static void note(String what) {
        System.out.println("[....] " + what);
    }

    public static void main(String[] args) throws Exception {
        String config = args[0];
        boolean bounded = args[1].equals("U");
        boolean withProject = args[2].equals("1");
        Path outFile = Path.of(args[3]);
        configDir = new File(config).getAbsoluteFile().getParent();

        // A dialog that never closes would otherwise hang the run.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(180_000);
            } catch (InterruptedException e) {
                return;
            }
            out.append("WATCHDOG TIMEOUT\n");
            write(outFile);
            System.out.println("[FAIL] the scenario finished within 180 s");
            System.out.println("SCENARIO FAILED (watchdog)");
            Runtime.getRuntime().halt(3);
        });
        watchdog.setDaemon(true);
        watchdog.start();

        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await();
        Platform.setImplicitExit(false);
        // Set the config before QuPath loads the extension, so it does not ask to switch.
        QPPreferenceDialog.setMicroscopeConfigFileProperty(config);
        QuPathGUI gui = onFx(QuPathGUI::createHiddenInstance);
        note("hidden QuPathGUI up, QuPath " + QuPathGUI.getVersion());
        // A full-res -> stage transform, so the existing-image MDA export gets past alignment.
        MicroscopeController.getInstance().setCurrentTransform(new AffineTransform(0.5, 0, 0, 0.5, 100, 200));

        Path scratch = Files.createTempDirectory("acqdialogdump");
        scratchDir = scratch.toString();
        PersistentPreferences.setLastSampleName("lastSample");
        if (withProject) {
            File dir = scratch.resolve("projects/ProjA").toFile();
            dir.mkdirs();
            Project<BufferedImage> project = Projects.createProject(dir, BufferedImage.class);
            project.syncChanges();
            onFx(() -> {
                gui.setProject(project);
                return null;
            });
            note("project open: " + dir);
        }

        Class<?> cls;
        if (bounded) {
            cls = Class.forName("qupath.ext.qpsc.ui.UnifiedAcquisitionController$UnifiedDialogBuilder");
            Constructor<?> k = cls.getDeclaredConstructor();
            k.setAccessible(true);
            builder = onFx(k::newInstance);
        } else {
            cls = Class.forName("qupath.ext.qpsc.ui.ExistingImageAcquisitionController$ConsolidatedDialogBuilder");
            Constructor<?> k = cls.getDeclaredConstructor(String.class, List.class, boolean.class);
            k.setAccessible(true);
            PathObject annotation = PathObjects.createAnnotationObject(
                    ROIs.createRectangleROI(0, 0, 5000, 3000, ImagePlane.getDefaultPlane()));
            annotation.setName("regionA");
            builder = onFx(() -> k.newInstance("defaultSample", new ArrayList<>(List.of(annotation)), false));
        }

        Method show = cls.getDeclaredMethod("buildAndShow");
        show.setAccessible(true);
        Object[] result = new Object[1];
        CountDownLatch closed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result[0] = show.invoke(builder);
            } catch (Throwable t) {
                out.append("buildAndShow threw ").append(cause(t)).append('\n');
            }
            closed.countDown();
        });
        Thread.sleep(2500);
        dialog = (Dialog<?>) field("dialog");
        check(dialog != null && onFx(() -> dialog.isShowing()), "the dialog opens");
        dump("initial");

        @SuppressWarnings("unchecked")
        ComboBox<String> modalityBox = (ComboBox<String>) field("modalityBox");
        @SuppressWarnings("unchecked")
        ComboBox<String> detectorBox = (ComboBox<String>) field("detectorBox");
        @SuppressWarnings("unchecked")
        ComboBox<String> objectiveBox = (ComboBox<String>) field("objectiveBox");

        List<String> modalities = onFx(() -> new ArrayList<>(modalityBox.getItems()));
        check(!modalities.isEmpty(), "the config offers at least one modality");
        for (String m : modalities) {
            select(modalityBox, m, 500);
            dump("modality=" + m);
            for (String d : onFx(() -> new ArrayList<>(detectorBox.getItems()))) {
                select(detectorBox, d, 400);
                dump("modality=" + m + " detector=" + d);
            }
            for (String o : onFx(() -> new ArrayList<>(objectiveBox.getItems()))) {
                select(objectiveBox, o, 400);
                dump("modality=" + m + " objective=" + o);
            }
        }

        setText("sampleNameField", "bad/name", 500);
        dump("bad sample name");
        check(errors().containsKey("sampleName"), "an invalid sample name is reported");
        setText("sampleNameField", "dumpSample", 500);

        if (!withProject) {
            setText("folderField", "", 500);
            dump("projects folder empty");
            check(
                    errors().containsKey("projectsFolder") && startDisabled(),
                    "an empty projects folder blocks Start when a new project will be created");
            setText("folderField", "relative" + File.separator + "projects", 500);
            dump("projects folder relative");
            check(errors().containsKey("projectsFolder"), "a relative projects folder is reported");
            setText("folderField", scratch.resolve("projects").toString(), 500);
            check(!errors().containsKey("projectsFolder"), "a full projects folder path is accepted");
        }

        onFx(() -> {
            ((CheckBox) field("zStackEnableCheck")).setSelected(true);
            return null;
        });
        Thread.sleep(500);
        dump("zstack on");

        if (bounded) {
            onFx(() -> {
                ((TextField) field("startXField")).setText("1000");
                ((TextField) field("startYField")).setText("2000");
                ((TextField) field("widthField")).setText("3000");
                ((TextField) field("heightField")).setText("4000");
                return null;
            });
            Thread.sleep(700);
            dump("region filled");
            onFx(() -> {
                ((CheckBox) field("timeLapseEnableCheck")).setSelected(true);
                return null;
            });
            Thread.sleep(500);
            dump("time-lapse on");
            onFx(() -> {
                ((CheckBox) field("timeLapseEnableCheck")).setSelected(false);
                return null;
            });
            Thread.sleep(500);
        }

        // Learned per-file timing, as a finished acquisition leaves it.
        PersistentPreferences.updateTimingData(1500, "ppm_20x_1", "20x");
        onFx(() -> {
            Method m = method(builder.getClass(), "triggerPreviewUpdate");
            m.setAccessible(true);
            return m.invoke(builder);
        });
        Thread.sleep(700);
        dump("learned timing");

        out.append("=== MDA context\n");
        dismissPromptsSoon();
        Object ctx = onFxOrNull(() -> {
            Method m = method(builder.getClass(), "buildMdaExportContext");
            m.setAccessible(true);
            return m.invoke(builder);
        });
        check(ctx != null, "an MDA export returns a context or a reason it cannot");
        out.append(normalize(describeContext(ctx))).append('\n');
        out.append("prompts: ").append(promptsSeen).append('\n');

        Thread.sleep(500);
        dump("before start");
        boolean fired = onFx(() -> {
            for (ButtonType t : dialog.getDialogPane().getButtonTypes()) {
                if (t.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                    Button b = (Button) dialog.getDialogPane().lookupButton(t);
                    if (!b.isDisabled()) {
                        b.fire();
                        return true;
                    }
                }
            }
            for (ButtonType t : dialog.getDialogPane().getButtonTypes()) {
                if (t.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE) {
                    ((Button) dialog.getDialogPane().lookupButton(t)).fire();
                }
            }
            return false;
        });
        out.append("=== start fired: ").append(fired).append('\n');
        closed.await(25, TimeUnit.SECONDS);
        out.append("=== result\n").append(normalize(String.valueOf(result[0]))).append('\n');
        check(fired, "Start is enabled once every field is valid");
        check(result[0] instanceof java.util.Optional<?> o && o.isPresent(), "Start returns a result");

        out.append("=== preferences\n");
        Preferences.userRoot().sync();
        Map<String, String> prefs = new TreeMap<>();
        collect(Preferences.userRoot(), prefs);
        prefs.forEach((k, v) -> out.append(k).append(" = ").append(normalize(v)).append('\n'));

        write(outFile);
        note("dump written to " + outFile);
        System.out.println();
        System.out.println(failures == 0 ? "SCENARIO PASSED" : "SCENARIO FAILED (" + failures + ")");
        Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
    }

    // ==================== driving the dialog ====================

    private static void select(ComboBox<String> box, String value, long settleMs) throws Exception {
        onFx(() -> {
            box.setValue(value);
            return null;
        });
        Thread.sleep(settleMs);
    }

    private static void setText(String fieldName, String text, long settleMs) throws Exception {
        onFx(() -> {
            ((TextField) field(fieldName)).setText(text);
            return null;
        });
        Thread.sleep(settleMs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> errors() throws Exception {
        return onFx(() -> new TreeMap<>((Map<String, String>) field("validationErrors")));
    }

    private static boolean startDisabled() throws Exception {
        return onFx(() -> {
            for (ButtonType t : dialog.getDialogPane().getButtonTypes()) {
                if (t.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                    return dialog.getDialogPane().lookupButton(t).isDisabled();
                }
            }
            return false;
        });
    }

    /** Accepts the prompts the MDA export opens (the tile FOV prompt of the existing-image dialog). */
    private static void dismissPromptsSoon() {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 40; i++) {
                    Thread.sleep(250);
                    Platform.runLater(() -> {
                        for (javafx.stage.Window w : new ArrayList<>(javafx.stage.Window.getWindows())) {
                            if (w.getScene() != null
                                    && w.getScene().getRoot() instanceof DialogPane p
                                    && p != dialog.getDialogPane()) {
                                promptsSeen.add(String.valueOf(p.getHeaderText()));
                                for (ButtonType bt : p.getButtonTypes()) {
                                    if (bt.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                                        ((Button) p.lookupButton(bt)).fire();
                                    }
                                }
                            }
                        }
                    });
                }
            } catch (InterruptedException e) {
                // done
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ==================== reading the dialog ====================

    private static String describeContext(Object ctx) throws Exception {
        if (ctx == null) {
            return "null";
        }
        Object error = ctx.getClass().getMethod("errorMessage").invoke(ctx);
        Object sample = ctx.getClass().getMethod("sample").invoke(ctx);
        Object regions = ctx.getClass().getMethod("regions").invoke(ctx);
        Object channels = ctx.getClass().getMethod("channelLibrary").invoke(ctx);
        Object cmd = ctx.getClass().getMethod("cmdBuilder").invoke(ctx);
        StringBuilder fields = new StringBuilder();
        if (cmd != null) {
            for (Field f : cmd.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                fields.append(f.getName()).append('=').append(f.get(cmd)).append("; ");
            }
        }
        return "error=" + error + "\nsample=" + sample + "\nregions=" + regions + "\nchannels=" + channels
                + "\ncmd=" + fields;
    }

    private static void collect(Preferences node, Map<String, String> into) throws Exception {
        for (String k : node.keys()) {
            into.put(node.absolutePath() + "/" + k, node.get(k, ""));
        }
        for (String c : node.childrenNames()) {
            collect(node.node(c), into);
        }
    }

    /** A field of the builder, looked up through its superclasses. */
    private static Object field(String name) throws Exception {
        for (Class<?> c = builder.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(builder);
            } catch (NoSuchFieldException e) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method method(Class<?> start, String name) throws Exception {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException e) {
                // try the superclass
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void dump(String title) throws Exception {
        onFx(() -> {
            out.append("=== ").append(title).append('\n');
            DialogPane pane = dialog.getDialogPane();
            out.append("title=").append(dialog.getTitle()).append(" header=").append(pane.getHeaderText());
            out.append(" pref=").append(pane.getPrefWidth()).append('x').append(pane.getPrefHeight()).append('\n');
            for (ButtonType t : pane.getButtonTypes()) {
                Node b = pane.lookupButton(t);
                out.append("button ").append(t.getText()).append(' ').append(t.getButtonData());
                out.append(" disabled=").append(b.isDisabled()).append('\n');
            }
            node(pane.getContent(), 0);
            return null;
        });
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

    private static <T> T onFxOrNull(Callable<T> c) {
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
