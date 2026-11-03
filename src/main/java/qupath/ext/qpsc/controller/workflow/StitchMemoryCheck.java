package qupath.ext.qpsc.controller.workflow;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.stage.Modality;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.ui.DialogOwner;

/**
 * Estimates the heap a stitch job's concurrent stitches need, compares it with the memory QuPath
 * has, and warns once per job when it is short.
 *
 * <p>The per-stitch and merge terms were measured with {@code harness/StitchMemoryScenario.java}
 * (live heap after a full collection, soft references cleared, so QuPath's tile cache, which gives
 * memory back on demand, is not in them); the measurements and the fit are in the 2026-11-03
 * journal {@code claude-reports/2026-11-03_qpsc-derived-stitches-in-pool.md}. The warning never
 * blocks or changes the job.
 */
final class StitchMemoryCheck {

    private static final Logger logger = LoggerFactory.getLogger(StitchMemoryCheck.class);

    static final String TITLE = "QPSC Stitching - Memory Warning";

    /** Warn when the estimate passes this fraction of the maximum heap. */
    static final double HEAP_FRACTION = 0.8;

    private static final long MB = 1024L * 1024L;

    // Measured terms (see the class comment): live heap above an idle QuPath, tiles-to-pyramid
    // 0.8.3 (levels from the tile cache) and 0.8.4 (staged levels), 1024 and 2048 px 16-bit tiles,
    // mosaics of 3.8k-37k px. Each line lies on or above every measurement of both versions.
    /** One grayscale stitch: 0.8.3 47-51 MB, 0.8.4 15-25 MB measured; the slope is 0.8.3's. */
    static final long STITCH_FIXED = 47 * MB;

    static final double STITCH_BYTES_PER_OUTPUT_PIXEL = 0.0095;
    /** One RGB stitch (orientation_color): 79-153 MB measured, flat over the larger mosaics. */
    static final long RGB_STITCH = 155 * MB;
    /** The five-channel merge: 18-28 MB measured, growing slowly with the mosaic. */
    static final long MERGE_FIXED = 26 * MB;

    static final double MERGE_BYTES_PER_OUTPUT_PIXEL = 0.0104;
    /** The five-channel registration solve: 33-143 MB measured, flat (134-143) from 64 tiles of 2048 px. */
    static final long SOLVE = 145 * MB;

    private StitchMemoryCheck() {}

    /**
     * What a job will stitch, and how.
     *
     * @param siblings channel or angle folders
     * @param derived derived-output folders
     * @param derivedRgb how many of the derived outputs are RGB
     * @param concurrency the Stitching concurrency preference
     * @param merge whether a channel merge follows the siblings
     * @param derivedConcurrent whether derived outputs may run beside the siblings and the merge
     * @param solve whether a registration solve runs before the stitches
     * @param tileWidth source tile width in pixels
     * @param tileHeight source tile height in pixels
     * @param tileCount tiles per folder
     * @param bitsPerSample of the source tiles
     * @param outputWidth full-resolution mosaic width in pixels
     * @param outputHeight full-resolution mosaic height in pixels
     */
    record Plan(
            int siblings,
            int derived,
            int derivedRgb,
            int concurrency,
            boolean merge,
            boolean derivedConcurrent,
            boolean solve,
            int tileWidth,
            int tileHeight,
            int tileCount,
            int bitsPerSample,
            long outputWidth,
            long outputHeight) {

        long outputPixels() {
            return outputWidth * outputHeight;
        }

        /**
         * Reads the tile grid of the first sibling folder: tile count and positions from its
         * {@code TileConfiguration.txt}, size and bit depth from its first tile.
         *
         * @return the plan, or null when the folder cannot be read (logged)
         */
        static Plan read(
                Path tileBaseDir,
                List<String> siblings,
                List<String> derived,
                int concurrency,
                boolean merge,
                boolean derivedConcurrent,
                boolean solve) {
            if (siblings.isEmpty()) {
                return null;
            }
            try {
                Grid grid = Grid.read(tileBaseDir.resolve(siblings.get(0)));
                int rgb = 0;
                for (String d : derived) {
                    Grid g = Grid.read(tileBaseDir.resolve(d));
                    if (g.bands() >= 3) {
                        rgb++;
                    }
                }
                return new Plan(
                        siblings.size(),
                        derived.size(),
                        rgb,
                        concurrency,
                        merge,
                        derivedConcurrent,
                        solve,
                        grid.tileWidth(),
                        grid.tileHeight(),
                        grid.tileCount(),
                        grid.bitsPerSample(),
                        grid.width(),
                        grid.height());
            } catch (IOException | RuntimeException e) {
                logger.info("No stitching memory estimate for {}: {}", tileBaseDir, e.getMessage());
                return null;
            }
        }
    }

    /** One folder's tile grid. */
    record Grid(int tileWidth, int tileHeight, int bands, int bitsPerSample, int tileCount, long width, long height) {

        private static final Pattern LINE =
                Pattern.compile("^\\s*([^;#]+?)\\s*;.*\\(\\s*([-0-9.eE+]+)\\s*,\\s*([-0-9.eE+]+)");

        static Grid read(Path folder) throws IOException {
            Path config = folder.resolve("TileConfiguration.txt");
            List<double[]> positions = new ArrayList<>();
            String firstTile = null;
            try (BufferedReader r = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = LINE.matcher(line);
                    if (m.find()) {
                        if (firstTile == null) {
                            firstTile = m.group(1);
                        }
                        positions.add(new double[] {Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))});
                    }
                }
            }
            if (firstTile == null) {
                throw new IOException("no tile positions in " + config);
            }
            int[] header = tileHeader(folder.resolve(firstTile));
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] p : positions) {
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
            long width = Math.round(maxX - minX) + header[0];
            long height = Math.round(maxY - minY) + header[1];
            return new Grid(header[0], header[1], header[2], header[3], positions.size(), width, height);
        }

        /** Width, height, bands and bits per sample of an image file, read from its header. */
        private static int[] tileHeader(Path file) throws IOException {
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                if (in == null) {
                    throw new IOException("cannot open " + file);
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) {
                    throw new IOException("no reader for " + file.getFileName());
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in, true, true);
                    ImageTypeSpecifier type = reader.getRawImageType(0);
                    if (type == null) {
                        type = reader.getImageTypes(0).next();
                    }
                    int bands = type.getNumBands();
                    return new int[] {reader.getWidth(0), reader.getHeight(0), bands, type.getBitsPerBand(0)};
                } finally {
                    reader.dispose();
                }
            }
        }
    }

    /**
     * The estimate and what it is made of.
     *
     * @param peakBytes the largest of the phases below
     * @param peakPhase which phase that is, in words
     * @param stitchBytes one grayscale stitch
     * @param rgbStitchBytes one RGB stitch
     * @param mergeBytes the channel merge, 0 without one
     * @param solveBytes the registration solve, 0 without one
     * @param stitchesAtOnce most stitches running at the same moment
     */
    record Estimate(
            long peakBytes,
            String peakPhase,
            long stitchBytes,
            long rgbStitchBytes,
            long mergeBytes,
            long solveBytes,
            int stitchesAtOnce) {}

    /**
     * What this QuPath has.
     *
     * @param maxHeap {@code Runtime.maxMemory()}, the most heap QuPath can use
     * @param usedHeap heap in use now, including garbage not yet collected
     * @param committedHeap heap the JVM holds from the system now
     * @param physicalTotal physical RAM, when it could be read
     * @param physicalFree physical RAM available to programs now, when it could be read
     * @param physicalSource where the physical numbers came from, or why they are missing
     */
    record Detected(
            long maxHeap,
            long usedHeap,
            long committedHeap,
            OptionalLong physicalTotal,
            OptionalLong physicalFree,
            String physicalSource) {}

    /** The estimate for a plan; each phase is the stitches running at once plus the merge or solve. */
    static Estimate estimate(Plan p) {
        long stitch = STITCH_FIXED + Math.round(STITCH_BYTES_PER_OUTPUT_PIXEL * p.outputPixels());
        long rgb = RGB_STITCH;
        long merge = p.merge() ? MERGE_FIXED + Math.round(MERGE_BYTES_PER_OUTPUT_PIXEL * p.outputPixels()) : 0;
        long solve = p.solve() ? SOLVE : 0;
        int pool = Math.max(1, p.concurrency());

        // While the siblings stitch: every slot busy; with concurrent derived outputs the slots
        // the siblings leave free (and, near the end, the RGB ones) are derived stitches.
        int withSiblings = Math.min(pool, p.siblings() + (p.derivedConcurrent() ? p.derived() : 0));
        int rgbWithSiblings = p.derivedConcurrent() ? Math.min(p.derivedRgb(), Math.max(0, withSiblings - 1)) : 0;
        long siblingsPhase = phase(withSiblings, rgbWithSiblings, stitch, rgb);
        // While the merge runs (or after the last angle): derived outputs only.
        int derivedAtOnce = Math.min(pool, p.derived());
        int rgbAtOnce = Math.min(p.derivedRgb(), derivedAtOnce);
        long derivedPhase = phase(derivedAtOnce, rgbAtOnce, stitch, rgb);
        long mergePhase = merge + (p.derivedConcurrent() ? derivedPhase : 0);
        long afterPhase = p.derivedConcurrent() ? 0 : derivedPhase;

        long peak = solve;
        String what = "the registration solve";
        int atOnce = 0;
        if (siblingsPhase > peak) {
            peak = siblingsPhase;
            what = withSiblings + " stitches at once";
            atOnce = withSiblings;
        }
        if (mergePhase > peak) {
            peak = mergePhase;
            what = p.derivedConcurrent() && derivedAtOnce > 0
                    ? "the channel merge with " + derivedAtOnce + " derived stitch" + (derivedAtOnce == 1 ? "" : "es")
                    : "the channel merge";
            atOnce = p.derivedConcurrent() ? derivedAtOnce : 0;
        }
        if (afterPhase > peak) {
            peak = afterPhase;
            what = derivedAtOnce + " derived stitches at once";
            atOnce = derivedAtOnce;
        }
        return new Estimate(peak, what, stitch, rgb, merge, solve, atOnce);
    }

    private static long phase(int stitches, int rgbStitches, long stitch, long rgb) {
        return (long) (stitches - rgbStitches) * stitch + (long) rgbStitches * rgb;
    }

    /**
     * Whether to warn: the estimate passes {@link #HEAP_FRACTION} of the maximum heap, or, when
     * physical RAM could be read, more than the heap now committed plus the RAM now available,
     * i.e. the heap would have to grow into memory other programs are using.
     */
    static boolean shouldWarn(Estimate e, Detected d) {
        if (e.peakBytes() > HEAP_FRACTION * d.maxHeap()) {
            return true;
        }
        return d.physicalFree().isPresent()
                && e.peakBytes() > d.committedHeap() + d.physicalFree().getAsLong();
    }

    /** The warning text, with the estimate and every detected value named. */
    static String message(Plan p, Estimate e, Detected d) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                Locale.ROOT,
                "Estimated memory for this stitch job: %s at its peak (%s).%n",
                gb(e.peakBytes()),
                e.peakPhase()));
        sb.append(String.format(
                Locale.ROOT,
                "This is an estimate from measurements, not a reading: %s per stitch (%s for an RGB one)%s%s, "
                        + "for %d folders of %d tiles of %d x %d px, %d-bit, a %d x %d px mosaic; "
                        + "%d channels or angles, %d derived outputs, Stitching concurrency %d, "
                        + "derived outputs %s. QuPath's tile cache is not counted: it gives memory back when "
                        + "needed.%n%n",
                gb(e.stitchBytes()),
                gb(e.rgbStitchBytes()),
                e.mergeBytes() > 0 ? ", " + gb(e.mergeBytes()) + " for the channel merge" : "",
                e.solveBytes() > 0 ? ", " + gb(e.solveBytes()) + " for the registration solve" : "",
                p.siblings() + p.derived(),
                p.tileCount(),
                p.tileWidth(),
                p.tileHeight(),
                p.bitsPerSample(),
                p.outputWidth(),
                p.outputHeight(),
                p.siblings(),
                p.derived(),
                p.concurrency(),
                p.derivedConcurrent() ? "concurrent" : "after the merge"));
        sb.append(String.format(
                Locale.ROOT,
                "Detected: QuPath maximum heap %s (%.0f %% = %s); heap in use now %s (includes garbage not yet "
                        + "collected); heap committed %s.%n",
                gb(d.maxHeap()),
                HEAP_FRACTION * 100,
                gb((long) (HEAP_FRACTION * d.maxHeap())),
                gb(d.usedHeap()),
                gb(d.committedHeap())));
        if (d.physicalTotal().isPresent() && d.physicalFree().isPresent()) {
            sb.append(String.format(
                    Locale.ROOT,
                    "Physical RAM (%s): %s total, %s available now.%n",
                    d.physicalSource(),
                    gb(d.physicalTotal().getAsLong()),
                    gb(d.physicalFree().getAsLong())));
        } else {
            sb.append("Physical RAM: could not be read (")
                    .append(d.physicalSource())
                    .append(").\n");
        }
        sb.append("\nThe job runs as planned. To use less memory, turn off \"Stitch derived outputs concurrently\" ")
                .append("(derived outputs then wait for the channel merge) or lower \"Stitching concurrency\", in ")
                .append("Edit > Preferences > QPSC; or give QuPath more memory (Edit > Preferences > General > ")
                .append("Set max memory %, then restart QuPath).");
        return sb.toString();
    }

    /** One line for the queue window row and the log. */
    static String shortLine(Estimate e, Detected d) {
        return String.format(
                Locale.ROOT,
                "memory warning: estimated %s, QuPath heap max %s%s",
                gb(e.peakBytes()),
                gb(d.maxHeap()),
                d.physicalFree().isPresent()
                        ? ", RAM available " + gb(d.physicalFree().getAsLong())
                        : "");
    }

    /**
     * Logs the estimate and, when {@link #shouldWarn} says so, notes it on the job's status line
     * and shows a non-modal warning owned by the QuPath window. Call once per job, off the FX
     * thread; never throws.
     */
    static void check(Plan plan, String jobName, StitchJobStatus status) {
        try {
            if (plan == null) {
                return;
            }
            Estimate e = estimate(plan);
            Detected d = detect();
            boolean warn = shouldWarn(e, d);
            logger.info(
                    "Stitching memory for {}: estimated {} MB ({}); max heap {} MB, used {} MB, committed {} MB, "
                            + "physical RAM {} ({}){}",
                    jobName,
                    e.peakBytes() / MB,
                    e.peakPhase(),
                    d.maxHeap() / MB,
                    d.usedHeap() / MB,
                    d.committedHeap() / MB,
                    d.physicalFree().isPresent()
                            ? d.physicalFree().getAsLong() / MB + " MB available of "
                                    + d.physicalTotal().orElse(-MB) / MB + " MB"
                            : "unknown",
                    d.physicalSource(),
                    warn ? " -> warning shown" : "");
            if (!warn) {
                return;
            }
            String text = message(plan, e, d);
            logger.warn("Stitching memory warning for {}:\n{}", jobName, text);
            if (status != null) {
                status.note(shortLine(e, d));
            }
            show(jobName, text);
        } catch (RuntimeException | LinkageError ex) {
            logger.warn("Stitching memory check failed for {}: {}", jobName, ex.toString());
        }
    }

    private static void show(String jobName, String text) {
        try {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.initModality(Modality.NONE);
                DialogOwner.own(alert);
                alert.setTitle(TITLE);
                alert.setHeaderText("Stitching " + jobName + " may need more memory than QuPath has");
                TextArea area = new TextArea(text);
                area.setEditable(false);
                area.setWrapText(true);
                area.setPrefColumnCount(70);
                area.setPrefRowCount(14);
                alert.getDialogPane().setContent(area);
                alert.setResizable(true);
                alert.show();
            });
        } catch (IllegalStateException e) {
            logger.debug("No JavaFX toolkit for the memory warning: {}", e.getMessage());
        }
    }

    /** Reads the heap from {@link Runtime} and physical RAM from the system. */
    static Detected detect() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long committed = rt.totalMemory();
        long used = committed - rt.freeMemory();
        OptionalLong total = OptionalLong.empty();
        OptionalLong free = OptionalLong.empty();
        String source;
        long[] meminfo = procMeminfo();
        if (meminfo != null) {
            total = OptionalLong.of(meminfo[0]);
            free = OptionalLong.of(meminfo[1]);
            source = "/proc/meminfo MemAvailable";
        } else {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            OptionalLong t = beanValue(bean, "getTotalMemorySize", "getTotalPhysicalMemorySize");
            OptionalLong f = beanValue(bean, "getFreeMemorySize", "getFreePhysicalMemorySize");
            if (t.isPresent() && f.isPresent()) {
                total = t;
                free = f;
                source = "OperatingSystemMXBean";
            } else {
                source = "neither /proc/meminfo nor com.sun.management.OperatingSystemMXBean answered";
            }
        }
        return new Detected(max, used, committed, total, free, source);
    }

    /** MemTotal and MemAvailable in bytes, or null off Linux or when unreadable. */
    private static long[] procMeminfo() {
        Path p = Path.of("/proc/meminfo");
        if (!Files.isReadable(p)) {
            return null;
        }
        long total = -1;
        long available = -1;
        try (Stream<String> lines = Files.lines(p, StandardCharsets.US_ASCII)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.startsWith("MemTotal:")) {
                    total = kib(line);
                } else if (line.startsWith("MemAvailable:")) {
                    available = kib(line);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return total > 0 && available >= 0 ? new long[] {total, available} : null;
    }

    private static long kib(String line) {
        String[] parts = line.trim().split("\\s+");
        return Long.parseLong(parts[1]) * 1024L;
    }

    /**
     * Calls the first of {@code names} that the platform bean answers, through the
     * {@code com.sun.management} interface by reflection, so a runtime without that module
     * reports "unknown" instead of failing.
     */
    private static OptionalLong beanValue(Object bean, String... names) {
        Class<?> iface;
        try {
            iface = Class.forName("com.sun.management.OperatingSystemMXBean");
        } catch (ClassNotFoundException | LinkageError e) {
            return OptionalLong.empty();
        }
        if (!iface.isInstance(bean)) {
            return OptionalLong.empty();
        }
        for (String name : names) {
            try {
                Method m = iface.getMethod(name);
                Object v = m.invoke(bean);
                if (v instanceof Long l && l > 0) {
                    return OptionalLong.of(l);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // try the next name
            }
        }
        return OptionalLong.empty();
    }

    /** Bytes as "512 MB" or "9.0 GB". */
    static String gb(long bytes) {
        return bytes >= 1024 * MB
                ? String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * MB))
                : String.format(Locale.ROOT, "%d MB", bytes / MB);
    }
}
