import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javax.imageio.ImageIO;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.StitchingHelper;
import qupath.ext.qpsc.geometry.StageImageTransform;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.RegionRequest;

/**
 * Real-QuPath scenario: an LC-PolScope acquisition (five state folders plus the retardance,
 * orientation and orientation_color folders the server writes) stitched end to end through
 * StitchingHelper.performRegionStitching against the demo microscope, with tile registration on.
 *
 * <p>Checks that the derived outputs stitch on the channels' pool and start before the channel
 * merge finishes, that they get the channels' registration and flips (retardance and orientation
 * carry State0's tiles, so they must equal the merged file's channel 0 pixel for pixel), that
 * every reserved entry is filled, that the per-channel intermediates are deleted after the merge
 * and that every tile folder is back in place with no _temp_ folder left.
 *
 * <p>Usage: run through a qp-gui style launcher with the QPSC shadow jar and a tiles-to-pyramid
 * jar on the classpath; args: work directory. Prints [PASS]/[FAIL] lines, exits non-zero on a
 * failure.
 */
public final class DerivedStitchPoolScenario {

    private static final List<String> STATES = List.of("State0", "State1", "State2", "State3", "State4");
    private static final List<String> DERIVED = List.of("retardance", "orientation", "orientation_color");
    private static final int TILE = 384;
    private static final int STEP = 346; // 10 % overlap
    private static final int GRID = 3;
    private static final int[] ERROR_PX = {4, -3}; // nominal position error of tile (1,1)

    private static int failures;
    private static final StringBuilder report = new StringBuilder();

    private static void line(String s) {
        System.out.println(s);
        report.append(s).append('\n');
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
        }
        line((ok ? "[PASS] " : "[FAIL] ") + what);
    }

    /** Log events with their thread, captured from the root logger. */
    private static final List<String> events = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]).toAbsolutePath();
        // "fail-orientation": the orientation folder's tiles are unreadable, so that one derived
        // stitch fails while the channels, the merge and the other derived outputs must not.
        boolean failOrientation = args.length > 1 && args[1].equals("fail-orientation");
        Files.createDirectories(work);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(600_000);
            } catch (InterruptedException e) {
                return;
            }
            line("WATCHDOG TIMEOUT");
            Runtime.getRuntime().halt(3);
        });
        watchdog.setDaemon(true);
        watchdog.start();

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent e) {
                events.add(e.getTimeStamp() + " [" + e.getThreadName() + "] " + e.getFormattedMessage());
            }
        };
        capture.setContext(root.getLoggerContext());
        capture.start();
        root.addAppender(capture);

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
        Field reg = QPPreferenceDialog.class.getDeclaredField("tileRegistrationEnabledProperty");
        reg.setAccessible(true);
        ((BooleanProperty) reg.get(null)).set(true);
        QuPathGUI gui = onFx(QuPathGUI::createHiddenInstance);

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
        short[][] truth = texture(GRID * STEP + TILE - STEP);
        for (String state : STATES) {
            writeTiles(tileBase.resolve(state), truth, STATES.indexOf(state), false);
        }
        writeTiles(tileBase.resolve("retardance"), truth, 0, false);
        writeTiles(tileBase.resolve("orientation"), truth, 0, false);
        if (failOrientation) {
            try (Stream<Path> tiles = Files.list(tileBase.resolve("orientation"))) {
                for (Path t : tiles.filter(p -> p.toString().endsWith(".tif")).toList()) {
                    Files.writeString(t, "not a tiff");
                }
            }
        }
        writeTiles(tileBase.resolve("orientation_color"), truth, 0, true);

        boolean[] flips = StageImageTransform.current().stitcherFlipFlags();
        line("stitcher flips: x=" + flips[0] + " y=" + flips[1] + "; registration on: "
                + QPPreferenceDialog.getTileRegistrationEnabled() + "; concurrency: "
                + QPPreferenceDialog.getStitchingConcurrency());

        ModalityHandler handler = ModalityRegistry.getHandler("lcpolscope");
        SampleSetupResult sample = new SampleSetupResult(
                sampleName, projectsFolder.toFile(), "lcpolscope", "DEMO_OBJECTIVE_10X", "DEMO_MONO_CAMERA");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long t0 = System.currentTimeMillis();
        StitchingHelper.performRegionStitching(
                        "bounds", sample, mode, List.of(), 1.0, gui, project, executor, handler, sampleName,
                        projectsFolder.toString())
                .get(8, TimeUnit.MINUTES);
        line("job took " + (System.currentTimeMillis() - t0) + " ms");
        for (int i = 0; i < 5; i++) {
            onFx(() -> null);
            Thread.sleep(300);
        }
        executor.shutdown();

        // -- ordering, from the log
        long firstDerivedStart = first("Stitching derived output ");
        long mergeStart = first("Channel output grouping");
        long mergeDone = first("Multichannel merge succeeded");
        long lastChannelDone = last("Stitch completed for channel");
        line("t(first derived start)=" + rel(firstDerivedStart, t0) + " t(last channel done)=" + rel(lastChannelDone, t0)
                + " t(merge start)=" + rel(mergeStart, t0) + " t(merge done)=" + rel(mergeDone, t0));
        check(firstDerivedStart > 0 && mergeDone > 0 && firstDerivedStart < mergeDone,
                "derived outputs start before the channel merge finishes");
        check(firstDerivedStart > 0 && lastChannelDone > 0 && firstDerivedStart <= lastChannelDone,
                "derived outputs start while channels are still stitching (same pool)");
        check(events.stream().anyMatch(e -> e.contains("[stitch-target]") && e.contains("Stitching derived output")),
                "derived outputs run on the stitch-target pool");
        boolean orientationFailed = events.stream()
                .anyMatch(e -> e.contains("Failed to stitch derived output orientation ")
                        || e.contains("Derived output orientation processing returned null"));
        check(orientationFailed == failOrientation, failOrientation
                ? "the broken orientation stitch is reported in the log"
                : "no derived output failed");
        check(events.stream().noneMatch(e -> e.contains("Failed to stitch derived output retardance")
                        || e.contains("Failed to stitch derived output orientation_color")),
                "retardance and orientation_color did not fail");

        // -- files on disk
        Path slide = projectsFolder.resolve(sampleName).resolve("SlideImages");
        List<String> files;
        try (Stream<Path> s = Files.list(slide)) {
            files = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".ome.tif")).sorted().toList();
        }
        line("SlideImages: " + files);
        int expected = failOrientation ? 3 : 4;
        check(files.size() == expected, expected + " images: the merge and the derived outputs that stitched");
        check(files.stream().noneMatch(n -> n.contains("State")), "per-channel intermediates deleted after the merge");
        for (String d : failOrientation ? List.of("retardance", "orientation_color") : DERIVED) {
            check(files.stream().anyMatch(n -> n.matches(".*_" + d + "(_\\d+)?\\.ome\\.tif")),
                    "a file named for " + d);
        }
        try (Stream<Path> s = Files.list(tileBase)) {
            List<String> left = s.map(p -> p.getFileName().toString()).sorted().toList();
            line("tile folders after: " + left);
            check(left.stream().noneMatch(n -> n.startsWith("_temp_")), "no isolation folder left behind");
            check(left.containsAll(STATES) && left.containsAll(DERIVED), "every tile folder is back in place");
        }

        // -- project entries
        List<ProjectImageEntry<BufferedImage>> entries = onFx(() -> new ArrayList<>(project.getImageList()));
        for (ProjectImageEntry<BufferedImage> e : entries) {
            line("entry: " + e.getImageName() + " status=" + e.getMetadata().get("qpsc_stitch_status"));
        }
        check(entries.size() == expected, expected + " project entries");
        check(entries.stream().noneMatch(e -> e.getMetadata().get("qpsc_stitch_status") != null),
                "every reserved entry was filled (no pending or failed status left)");

        // -- pixels: derived outputs carry State0's tiles, so they must equal merged channel 0
        String merged = files.stream().filter(n -> DERIVED.stream().noneMatch(n::contains)).findFirst().orElse(null);
        String retardance = files.stream().filter(n -> n.contains("retardance")).findFirst().orElse(null);
        String orientation = files.stream()
                .filter(n -> n.contains("orientation") && !n.contains("orientation_color"))
                .findFirst()
                .orElse(null);
        if (failOrientation) {
            check(orientation == null, "no orientation file from the broken folder");
            orientation = retardance;
        }
        if (merged != null && retardance != null && orientation != null) {
            Raster m = read(slide.resolve(merged));
            Raster r = read(slide.resolve(retardance));
            Raster o = read(slide.resolve(orientation));
            line("merged " + m.getWidth() + "x" + m.getHeight() + "x" + m.getNumBands() + ", retardance "
                    + r.getWidth() + "x" + r.getHeight());
            check(sameBand(m, 0, r), "retardance equals the merge's State0 pixel for pixel (same registration, flips)");
            check(sameBand(m, 0, o), "orientation equals the merge's State0 pixel for pixel");
            double err = tileError(m, truth);
            line(String.format("State0 tile (1,1) mean abs error vs ground truth at best global offset: %.2f", err));
            check(err < 50, "registration corrected the nominal error of tile (1,1)");
        } else {
            check(false, "merged, retardance and orientation files found");
        }

        Files.writeString(work.resolve("report.txt"), report.toString());
        Files.write(work.resolve("events.log"), new ArrayList<>(events));
        line(failures == 0 ? "ALL PASSED" : failures + " FAILURE(S)");
        Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
    }

    private static String rel(long t, long t0) {
        return t <= 0 ? "never" : (t - t0) + "ms";
    }

    private static long first(String text) {
        synchronized (events) {
            for (String e : events) {
                if (e.contains(text)) {
                    return Long.parseLong(e.substring(0, e.indexOf(' ')));
                }
            }
        }
        return -1;
    }

    private static long last(String text) {
        long t = -1;
        synchronized (events) {
            for (String e : events) {
                if (e.contains(text)) {
                    t = Long.parseLong(e.substring(0, e.indexOf(' ')));
                }
            }
        }
        return t;
    }

    /** Smooth 16-bit texture with detail, deterministic. */
    private static short[][] texture(int size) {
        Random rnd = new Random(42);
        double[][] v = new double[size][size];
        for (int k = 0; k < 400; k++) {
            double cx = rnd.nextDouble() * size, cy = rnd.nextDouble() * size, s = 4 + rnd.nextDouble() * 20;
            double a = 2000 + rnd.nextDouble() * 8000;
            int r = (int) (3 * s);
            for (int y = Math.max(0, (int) cy - r); y < Math.min(size, (int) cy + r); y++) {
                for (int x = Math.max(0, (int) cx - r); x < Math.min(size, (int) cx + r); x++) {
                    double d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (s * s);
                    v[y][x] += a * Math.exp(-d2 / 2);
                }
            }
        }
        short[][] out = new short[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                out[y][x] = (short) Math.min(60000, 500 + v[y][x]);
            }
        }
        return out;
    }

    private static void writeTiles(Path dir, short[][] truth, int state, boolean rgb) throws Exception {
        Files.createDirectories(dir);
        StringBuilder cfg = new StringBuilder("dim = 2\n");
        for (int row = 0; row < GRID; row++) {
            for (int col = 0; col < GRID; col++) {
                int x0 = col * STEP, y0 = row * STEP;
                BufferedImage img = new BufferedImage(TILE, TILE, rgb ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_USHORT_GRAY);
                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        int v = (truth[y0 + y][x0 + x] & 0xffff);
                        if (rgb) {
                            int g = v >> 8;
                            img.setRGB(x, y, (g << 16) | (g << 8) | g);
                        } else {
                            img.getRaster().setSample(x, y, 0, Math.min(65535, v * (state + 1) / 1));
                        }
                    }
                }
                String name = String.format("tile_%d_%d.tif", row, col);
                ImageIO.write(img, "tif", dir.resolve(name).toFile());
                double nx = x0 + (row == 1 && col == 1 ? ERROR_PX[0] : 0);
                double ny = y0 + (row == 1 && col == 1 ? ERROR_PX[1] : 0);
                cfg.append(name).append("; ; (").append(nx).append(", ").append(ny).append(")\n");
            }
        }
        Files.writeString(dir.resolve("TileConfiguration.txt"), cfg.toString());
    }

    private static Raster read(Path file) throws Exception {
        try (ImageServer<BufferedImage> server = ImageServerProvider.buildServer(file.toUri().toString(), BufferedImage.class)) {
            return server.readRegion(RegionRequest.createInstance(server.getPath(), 1, 0, 0, server.getWidth(), server.getHeight()))
                    .getRaster();
        }
    }

    private static boolean sameBand(Raster multi, int band, Raster single) {
        if (multi.getWidth() != single.getWidth() || multi.getHeight() != single.getHeight()) {
            line("  size differs: " + multi.getWidth() + "x" + multi.getHeight() + " vs " + single.getWidth() + "x"
                    + single.getHeight());
            return false;
        }
        long differ = 0;
        for (int y = 0; y < multi.getHeight(); y++) {
            for (int x = 0; x < multi.getWidth(); x++) {
                if (multi.getSample(x, y, band) != single.getSample(x, y, 0)) {
                    differ++;
                }
            }
        }
        line("  pixels that differ: " + differ);
        return differ == 0;
    }

    /** Mean abs error of State0 over tile (1,1)'s interior against the ground truth, at the best global offset. */
    private static double tileError(Raster m, short[][] truth) {
        double best = Double.MAX_VALUE;
        int x0 = STEP + 40, y0 = STEP + 40, w = TILE - 80;
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -8; dx <= 8; dx++) {
                // Global offset from tile (0,0)'s interior.
                double e = 0;
                int n = 0;
                for (int y = 40; y < TILE - 40; y += 2) {
                    for (int x = 40; x < STEP - 40; x += 2) {
                        int mx = x + dx, my = y + dy;
                        if (mx < 0 || my < 0 || mx >= m.getWidth() || my >= m.getHeight()) {
                            continue;
                        }
                        e += Math.abs(m.getSample(mx, my, 0) - (truth[y][x] & 0xffff));
                        n++;
                    }
                }
                e = n == 0 ? Double.MAX_VALUE : e / n;
                if (e < best) {
                    best = e;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        line("  global offset of the mosaic: (" + bestDx + ", " + bestDy + "), tile (0,0) error " + String.format("%.2f", best));
        double e = 0;
        int n = 0;
        for (int y = y0; y < y0 + w; y += 2) {
            for (int x = x0; x < x0 + w; x += 2) {
                int mx = x + bestDx, my = y + bestDy;
                if (mx < 0 || my < 0 || mx >= m.getWidth() || my >= m.getHeight()) {
                    continue;
                }
                e += Math.abs(m.getSample(mx, my, 0) - (truth[y][x] & 0xffff));
                n++;
            }
        }
        return n == 0 ? Double.MAX_VALUE : e / n;
    }

    private static int bestDx, bestDy;

    private static <T> T onFx(Callable<T> c) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return c.call();
        }
        FutureTask<T> task = new FutureTask<>(c);
        Platform.runLater(task);
        return task.get(60, TimeUnit.SECONDS);
    }
}
