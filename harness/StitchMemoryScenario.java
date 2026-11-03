import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javax.imageio.ImageIO;
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;

/**
 * Measures the live heap of an LC-PolScope stitch job (five state folders, retardance,
 * orientation, orientation_color) run through StitchingHelper.performRegionStitching in a hidden
 * QuPathGUI, to derive the memory estimate behind the concurrent-stitch warning.
 *
 * <p>A sampler thread runs System.gc() every sample period and records the used heap (the live
 * set at that moment), the committed heap, the bytes held by QuPath's shared tile cache (read
 * from the cache itself) and what is running, read from thread stacks: channel stitches,
 * derived stitches, the channel merge, the registration solve. Tiles are hard links to a few
 * distinct files, so large grids need little disk.
 *
 * <p>Args: workDir tilePx grid concurrency derivedConcurrent(true|false|default) sampleMs
 * [rgbDerived(true|false)]. Writes samples.csv and summary.txt into workDir.
 */
public final class StitchMemoryScenario {

    private static final List<String> STATES = List.of("State0", "State1", "State2", "State3", "State4");

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]).toAbsolutePath();
        int tile = Integer.parseInt(args[1]);
        int grid = Integer.parseInt(args[2]);
        int concurrency = Integer.parseInt(args[3]);
        String derivedConcurrent = args[4];
        long sampleMs = Long.parseLong(args[5]);
        boolean rgbDerived = args.length <= 6 || Boolean.parseBoolean(args[6]);
        Files.createDirectories(work);

        Path configDir = Files.createDirectories(work.resolve("config"));
        for (String f : List.of("config_Demo.yml", "autofocus_Demo.yml", "imageprocessing_Demo.yml", "resources_LOCI.yml")) {
            try (InputStream in = StitchingHelper.class.getResourceAsStream("/qupath/ext/qpsc/templates/demo/" + f)) {
                Files.copy(in, configDir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path resourcesDir = Files.createDirectories(configDir.resolve("resources"));
        Files.copy(configDir.resolve("resources_LOCI.yml"), resourcesDir.resolve("resources_LOCI.yml"),
                StandardCopyOption.REPLACE_EXISTING);

        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await();
        Platform.setImplicitExit(false);
        QPPreferenceDialog.setMicroscopeConfigFileProperty(configDir.resolve("config_Demo.yml").toString());
        setPref("tileRegistrationEnabledProperty", true);
        Field cf = QPPreferenceDialog.class.getDeclaredField("stitchingConcurrencyProperty");
        cf.setAccessible(true);
        ((IntegerProperty) cf.get(null)).set(concurrency);
        if (!derivedConcurrent.equals("default")) {
            setPref("stitchDerivedConcurrentlyProperty", Boolean.parseBoolean(derivedConcurrent));
        }
        QuPathGUI gui = onFx(QuPathGUI::createHiddenInstance);
        Map<?, ?> cache = gui.getImageRegionStore().getCache();
        long cacheMax = -1;
        try {
            Field f = gui.getImageRegionStore().getClass().getSuperclass().getDeclaredField("tileCacheSizeBytes");
            f.setAccessible(true);
            cacheMax = (long) f.get(gui.getImageRegionStore());
        } catch (ReflectiveOperationException e) {
            System.out.println("cannot read the tile cache size: " + e);
        }

        Path projectsFolder = work.resolve("projects");
        String sampleName = "Sample1";
        Path projectDir = Files.createDirectories(projectsFolder.resolve(sampleName));
        Project<BufferedImage> project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
        onFx(() -> {
            gui.setProject(project);
            return null;
        });

        String mode = "lcpolscope_10x_1";
        Path tileBase = projectsFolder.resolve(sampleName).resolve(mode).resolve("bounds");
        Path src = Files.createDirectories(work.resolve("tile-src"));
        int distinct = 4;
        int step = (int) Math.round(tile * 0.9);
        for (int k = 0; k < distinct; k++) {
            writeTile(src.resolve("g" + k + ".tif"), tile, k, false);
            if (rgbDerived) {
                writeTile(src.resolve("c" + k + ".tif"), tile, k, true);
            }
        }
        List<String> folders = new ArrayList<>(STATES);
        folders.add("retardance");
        folders.add("orientation");
        if (rgbDerived) {
            folders.add("orientation_color");
        }
        for (String folder : folders) {
            Path dir = Files.createDirectories(tileBase.resolve(folder));
            boolean rgb = folder.equals("orientation_color");
            StringBuilder cfg = new StringBuilder("dim = 2\n");
            int i = 0;
            for (int row = 0; row < grid; row++) {
                for (int col = 0; col < grid; col++) {
                    String name = String.format("tile_%d_%d.tif", row, col);
                    Files.createLink(dir.resolve(name), src.resolve((rgb ? "c" : "g") + (i++ % distinct) + ".tif"));
                    cfg.append(name).append("; ; (").append(col * step).append(", ").append(row * step).append(")\n");
                }
            }
            Files.writeString(dir.resolve("TileConfiguration.txt"), cfg.toString());
        }

        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        long baseline = rt.totalMemory() - rt.freeMemory();
        long baseCache = cacheBytes(cache);
        String header = String.format(Locale.ROOT,
                "tile=%d grid=%d (%d tiles, output ~%d x %d) concurrency=%d derivedConcurrent=%s maxHeap=%d MB"
                        + " cacheMax=%d MB baseline=%d MB (cache %d MB)",
                tile, grid, grid * grid, (grid - 1) * step + tile, (grid - 1) * step + tile, concurrency,
                derivedConcurrent, rt.maxMemory() >> 20, cacheMax >> 20, baseline >> 20, baseCache >> 20);
        System.out.println(header);

        List<String> rows = new ArrayList<>();
        rows.add("t_ms,used_mb,committed_mb,cache_mb,siblings,derived,derived_rgb,merge,solve,gc_ms");
        Map<String, long[]> byState = new TreeMap<>();
        long[] peak = new long[3];
        long t0 = System.currentTimeMillis();
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(sampleMs);
                } catch (InterruptedException e) {
                    return;
                }
                int sib = 0, der = 0, derRgb = 0, merge = 0, solve = 0;
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    boolean isSib = false, isDer = false, isMerge = false, isSolve = false, isRgb = false;
                    for (StackTraceElement f : e.getValue()) {
                        String m = f.getMethodName();
                        String c = f.getClassName();
                        if (m.equals("stitchOne") && c.endsWith("StitchingHelper")) {
                            isSib = true;
                        } else if (m.equals("stitchDerivedOne")) {
                            isDer = true;
                        } else if (c.endsWith("ChannelMerger") && m.equals("merge")) {
                            isMerge = true;
                        } else if (c.endsWith("ChannelRegistrationSupport") && m.equals("solve")) {
                            isSolve = true;
                        } else if (c.endsWith("PyramidGeneratingImageServer") || m.equals("pyramidalizeTiled")) {
                            isRgb = true;
                        }
                    }
                    if (isDer && e.getKey().getName().startsWith("stitch-target")) {
                        // RGB is known from the folder being isolated; read the thread's isolation below.
                        der++;
                        if (isRgb) {
                            derRgb++;
                        }
                    } else if (isSib) {
                        sib++;
                    }
                    if (isMerge) {
                        merge++;
                    }
                    if (isSolve) {
                        solve++;
                    }
                }
                long g0 = System.nanoTime();
                System.gc();
                long gcMs = (System.nanoTime() - g0) / 1_000_000;
                long used = rt.totalMemory() - rt.freeMemory();
                long committed = rt.totalMemory();
                long cacheBytes = cacheBytes(cache);
                String row = String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                        System.currentTimeMillis() - t0, used >> 20, committed >> 20, cacheBytes >> 20,
                        sib, der, derRgb, merge, solve, gcMs);
                synchronized (rows) {
                    rows.add(row);
                    String key = "siblings=" + sib + " derived=" + der + " (rgb " + derRgb + ") merge=" + merge
                            + " solve=" + solve;
                    long nonCache = used - Math.max(0, cacheBytes) - baseline + Math.max(0, baseCache);
                    long[] v = byState.computeIfAbsent(key, k -> new long[] {Long.MIN_VALUE, 0, 0});
                    v[0] = Math.max(v[0], nonCache);
                    v[1]++;
                    v[2] = Math.max(v[2], cacheBytes);
                    peak[0] = Math.max(peak[0], used);
                    peak[1] = Math.max(peak[1], cacheBytes);
                    peak[2] = Math.max(peak[2], committed);
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);
        sampler.start();

        ModalityHandler handler = ModalityRegistry.getHandler("lcpolscope");
        SampleSetupResult sample = new SampleSetupResult(
                sampleName, projectsFolder.toFile(), "lcpolscope", "DEMO_OBJECTIVE_10X", "DEMO_MONO_CAMERA");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long j0 = System.currentTimeMillis();
        StitchingHelper.performRegionStitching(
                        "bounds", sample, mode, List.of(), 1.0, gui, project, executor, handler, sampleName,
                        projectsFolder.toString())
                .get(180, TimeUnit.MINUTES);
        long jobMs = System.currentTimeMillis() - j0;
        sampler.interrupt();
        sampler.join();
        executor.shutdown();

        StringBuilder summary = new StringBuilder(header).append('\n');
        // Windows the job opened (the memory warning, when it fired) and their text.
        Thread.sleep(500);
        summary.append(onFx(() -> {
            StringBuilder w = new StringBuilder();
            for (javafx.stage.Window win : javafx.stage.Window.getWindows()) {
                if (win instanceof javafx.stage.Stage st && st.isShowing()) {
                    w.append("window: '").append(st.getTitle()).append("' owner=")
                            .append(st.getOwner() == null ? "none" : "set").append(" modality=")
                            .append(st.getModality()).append('\n');
                    for (javafx.scene.Node n : st.getScene().getRoot().lookupAll(".text-area")) {
                        w.append(((javafx.scene.control.TextArea) n).getText()).append('\n');
                    }
                }
            }
            return w.toString();
        }));
        summary.append(String.format(Locale.ROOT, "job %.1f s; peak used %d MB, peak cache %d MB, peak committed %d MB%n",
                jobMs / 1000.0, peak[0] >> 20, peak[1] >> 20, peak[2] >> 20));
        summary.append("max live heap above baseline, tile cache excluded, by what was running (samples):\n");
        for (Map.Entry<String, long[]> e : byState.entrySet()) {
            summary.append(String.format(Locale.ROOT, "  %-50s %6d MB  (n=%d, cache up to %d MB)%n",
                    e.getKey(), e.getValue()[0] >> 20, e.getValue()[1], e.getValue()[2] >> 20));
        }
        try (var s = Files.list(projectsFolder.resolve(sampleName).resolve("SlideImages"))) {
            summary.append("outputs: ").append(s.map(p -> p.getFileName().toString()).sorted().toList()).append('\n');
        }
        System.out.print(summary);
        Files.writeString(work.resolve("summary.txt"), summary.toString());
        synchronized (rows) {
            Files.write(work.resolve("samples.csv"), rows);
        }
        Runtime.getRuntime().halt(0);
    }

    /** Bytes held by the tile cache's images (soft values; a cleared one counts nothing). */
    private static long cacheBytes(Map<?, ?> cache) {
        long sum = 0;
        for (Object v : new ArrayList<>(cache.values())) {
            if (v instanceof BufferedImage img) {
                var db = img.getRaster().getDataBuffer();
                sum += (long) db.getSize() * db.getNumBanks()
                        * java.awt.image.DataBuffer.getDataTypeSize(db.getDataType()) / 8;
            }
        }
        return sum;
    }

    private static void setPref(String field, boolean value) throws Exception {
        try {
            Field f = QPPreferenceDialog.class.getDeclaredField(field);
            f.setAccessible(true);
            ((BooleanProperty) f.get(null)).set(value);
        } catch (NoSuchFieldException e) {
            System.out.println("no preference field " + field + " in this build");
        }
    }

    /** Smooth texture plus mild noise, deterministic per k. */
    private static void writeTile(Path file, int size, int k, boolean rgb) throws Exception {
        Random rnd = new Random(42 + k);
        BufferedImage img = new BufferedImage(size, size, rgb ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_USHORT_GRAY);
        double fx = 2 * Math.PI * (3 + k) / size;
        double fy = 2 * Math.PI * (2 + k) / size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double v = 8000 + 6000 * Math.sin(fx * x) * Math.cos(fy * y) + rnd.nextGaussian() * 200;
                int s = (int) Math.max(0, Math.min(65535, v));
                if (rgb) {
                    int g = s >> 8;
                    img.setRGB(x, y, (g << 16) | ((255 - g) << 8) | (g / 2));
                } else {
                    img.getRaster().setSample(x, y, 0, s);
                }
            }
        }
        ImageIO.write(img, "tif", file.toFile());
    }

    private static <T> T onFx(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return c.call();
        }
        FutureTask<T> task = new FutureTask<>(c);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
