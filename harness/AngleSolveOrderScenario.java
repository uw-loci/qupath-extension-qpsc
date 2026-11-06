import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
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
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.model.SampleSetupResult;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.projects.Project;
import qupath.lib.projects.Projects;
import qupath.lib.regions.RegionRequest;

/**
 * Real-QuPath scenario: a PPM acquisition (angles 90.0, 7.0, -7.0 as RGB tiles plus the 7.0.biref
 * folder the server writes, 16-bit) stitched end to end through
 * StitchingHelper.performRegionStitching against the demo microscope, with tile registration on.
 *
 * <p>Checks that registration is solved on 90.0 before any angle is stitched, that every angle
 * starts before the first one finishes (none waits for the reference's pyramid), that all three
 * angles got the same placement (they carry the same tiles, so their files are equal pixel for
 * pixel), that registration corrected the deliberate error of tile (1,1), and that every tile
 * folder is back with no _temp_ folder left.
 *
 * <p>Usage: run through a qp-gui style launcher with the QPSC shadow jar and a tiles-to-pyramid
 * jar on the classpath; args: work directory. Prints [PASS]/[FAIL] lines, exits non-zero on a
 * failure.
 */
public final class AngleSolveOrderScenario {

    private static final List<String> ANGLES = List.of("90.0", "7.0", "-7.0");
    private static final int TILE = 512;
    private static final int STEP = 461; // 10 % overlap
    private static final int GRID = 4;
    private static final int[] ERROR_PX = {4, -3}; // nominal position error of tile (1,1)

    private static int failures;
    private static final StringBuilder report = new StringBuilder();
    private static final List<String> events = Collections.synchronizedList(new ArrayList<>());

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

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]).toAbsolutePath();
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

        String mode = "ppm_10x_1";
        Path tileBase = projectsFolder.resolve(sampleName).resolve(mode).resolve("bounds");
        short[][] truth = texture(GRID * STEP + TILE - STEP);
        for (String angle : ANGLES) {
            writeTiles(tileBase.resolve(angle), truth, true);
        }
        writeTiles(tileBase.resolve("7.0.biref"), truth, false);
        line("registration on: " + QPPreferenceDialog.getTileRegistrationEnabled() + "; concurrency: "
                + QPPreferenceDialog.getStitchingConcurrency());

        ModalityHandler handler = ModalityRegistry.getHandler("ppm");
        SampleSetupResult sample = new SampleSetupResult(
                sampleName, projectsFolder.toFile(), "ppm", "DEMO_OBJECTIVE_10X", "DEMO_MONO_CAMERA");
        List<AngleExposure> angles = List.of(
                new AngleExposure(90.0, 10), new AngleExposure(7.0, 10), new AngleExposure(-7.0, 10));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long t0 = System.currentTimeMillis();
        StitchingHelper.performRegionStitching(
                        "bounds", sample, mode, angles, 1.0, gui, project, executor, handler, sampleName,
                        projectsFolder.toString())
                .get(8, TimeUnit.MINUTES);
        line("job took " + (System.currentTimeMillis() - t0) + " ms");
        for (int i = 0; i < 5; i++) {
            onFx(() -> null);
            Thread.sleep(300);
        }
        executor.shutdown();

        // -- ordering, from the log
        long solveStart = first("Solving tile registration for");
        long firstAngleStart = first("Stitching angle ");
        long lastAngleStart = last("Stitching angle ");
        long firstAngleDone = first("Isolation processing completed for angle");
        line("t(solve)=" + rel(solveStart, t0) + " t(first angle start)=" + rel(firstAngleStart, t0)
                + " t(last angle start)=" + rel(lastAngleStart, t0) + " t(first angle done)="
                + rel(firstAngleDone, t0));
        check(solveStart > 0 && firstAngleStart > 0 && solveStart <= firstAngleStart,
                "registration is solved before any angle is stitched");
        check(events.stream().anyMatch(e -> e.contains("Solving tile registration for") && e.contains("'90.0'")),
                "the solve runs on the 90.0 reference angle");
        check(countOf("Stitching angle ") == 3 && lastAngleStart > 0 && firstAngleDone > 0
                        && lastAngleStart < firstAngleDone,
                "all three angles start before the first one finishes");
        check(events.stream().noneMatch(e -> e.contains("Tile registration mode for") && e.contains(": Solve")),
                "no angle solves inside its own stitch (every one applies)");

        // -- files on disk
        Path slide = projectsFolder.resolve(sampleName).resolve("SlideImages");
        List<String> files;
        try (Stream<Path> s = Files.list(slide)) {
            files = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".ome.tif")).sorted().toList();
        }
        line("SlideImages: " + files);
        check(files.size() == 4, "four images: three angles and the biref");
        try (Stream<Path> s = Files.list(tileBase)) {
            List<String> left = s.map(p -> p.getFileName().toString()).sorted().toList();
            line("tile folders after: " + left);
            check(left.stream().noneMatch(n -> n.startsWith("_temp_")), "no isolation folder left behind");
            check(left.containsAll(ANGLES) && left.contains("7.0.biref"), "every tile folder is back in place");
        }

        // -- pixels: the angles carry the same tiles, so they must be equal; registration fixed (1,1)
        String a90 = fileFor(files, "_90.0_");
        String a7 = fileFor(files, "_7.0_0");
        String am7 = fileFor(files, "_-7.0_");
        if (a90 != null && a7 != null && am7 != null) {
            Raster r90 = read(slide.resolve(a90));
            Raster r7 = read(slide.resolve(a7));
            Raster rm7 = read(slide.resolve(am7));
            line("90.0: " + r90.getWidth() + "x" + r90.getHeight() + "x" + r90.getNumBands());
            check(same(r90, r7) && same(r90, rm7), "7.0 and -7.0 equal 90.0 pixel for pixel (one shared solve)");
            double err = tileError(r90, truth);
            line(String.format("90.0 tile (1,1) mean abs error vs ground truth at best global offset: %.2f", err));
            check(err < 3, "registration corrected the nominal error of tile (1,1)");
        } else {
            check(false, "the three angle files found: " + files);
        }

        Files.writeString(work.resolve("report.txt"), report.toString());
        Files.write(work.resolve("events.log"), new ArrayList<>(events));
        line(failures == 0 ? "ALL PASSED" : failures + " FAILURE(S)");
        Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
    }

    private static String fileFor(List<String> files, String marker) {
        return files.stream().filter(n -> n.contains(marker) && !n.contains("biref")).findFirst().orElse(null);
    }

    private static String rel(long t, long t0) {
        return t <= 0 ? "never" : (t - t0) + "ms";
    }

    private static int countOf(String text) {
        synchronized (events) {
            return (int) events.stream().filter(e -> e.contains(text)).count();
        }
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
        for (int k = 0; k < 600; k++) {
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

    /** RGB tiles carry the texture's top 8 bits as gray; 16-bit tiles carry it whole. */
    private static void writeTiles(Path dir, short[][] truth, boolean rgb) throws Exception {
        Files.createDirectories(dir);
        StringBuilder cfg = new StringBuilder("dim = 2\n");
        for (int row = 0; row < GRID; row++) {
            for (int col = 0; col < GRID; col++) {
                int x0 = col * STEP, y0 = row * STEP;
                BufferedImage img =
                        new BufferedImage(TILE, TILE, rgb ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_USHORT_GRAY);
                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        int v = truth[y0 + y][x0 + x] & 0xffff;
                        if (rgb) {
                            int g = v >> 8;
                            img.setRGB(x, y, (g << 16) | (g << 8) | g);
                        } else {
                            img.getRaster().setSample(x, y, 0, v);
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
        try (ImageServer<BufferedImage> server =
                ImageServerProvider.buildServer(file.toUri().toString(), BufferedImage.class)) {
            return server.readRegion(
                            RegionRequest.createInstance(server.getPath(), 1, 0, 0, server.getWidth(), server.getHeight()))
                    .getRaster();
        }
    }

    private static boolean same(Raster a, Raster b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight() || a.getNumBands() != b.getNumBands()) {
            line("  size differs: " + a.getWidth() + "x" + a.getHeight() + " vs " + b.getWidth() + "x" + b.getHeight());
            return false;
        }
        long differ = 0;
        for (int band = 0; band < a.getNumBands(); band++) {
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    if (a.getSample(x, y, band) != b.getSample(x, y, band)) {
                        differ++;
                    }
                }
            }
        }
        line("  samples that differ: " + differ);
        return differ == 0;
    }

    /** Mean abs error of band 0 over tile (1,1)'s interior against the 8-bit truth, best global offset. */
    private static double tileError(Raster m, short[][] truth) {
        double best = Double.MAX_VALUE;
        int bestDx = 0, bestDy = 0;
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -8; dx <= 8; dx++) {
                double e = 0;
                int n = 0;
                for (int y = 40; y < TILE - 40; y += 2) {
                    for (int x = 40; x < STEP - 40; x += 2) {
                        int mx = x + dx, my = y + dy;
                        if (mx < 0 || my < 0 || mx >= m.getWidth() || my >= m.getHeight()) {
                            continue;
                        }
                        e += Math.abs(m.getSample(mx, my, 0) - ((truth[y][x] & 0xffff) >> 8));
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
        line("  global offset of the mosaic: (" + bestDx + ", " + bestDy + "), tile (0,0) error "
                + String.format("%.2f", best));
        int x0 = STEP + 40, y0 = STEP + 40, w = TILE - 80;
        double e = 0;
        int n = 0;
        for (int y = y0; y < y0 + w; y += 2) {
            for (int x = x0; x < x0 + w; x += 2) {
                int mx = x + bestDx, my = y + bestDy;
                if (mx < 0 || my < 0 || mx >= m.getWidth() || my >= m.getHeight()) {
                    continue;
                }
                e += Math.abs(m.getSample(mx, my, 0) - ((truth[y][x] & 0xffff) >> 8));
                n++;
            }
        }
        return n == 0 ? Double.MAX_VALUE : e / n;
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
