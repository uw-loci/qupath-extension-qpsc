package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.workflow.AcquisitionManager;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.scripting.QP;

/**
 * Tails an NDJSON file (one JSON object per line) written incrementally by
 * the Python acquisition server and applies per-tile measurements to the
 * open slide's detection objects as they arrive.
 *
 * <p>This enables live updates to autofocus values, saturation percentages,
 * tile timing, and any future per-tile measurement fields while an
 * acquisition is still running, rather than waiting for the batch
 * attachment at the end.
 *
 * <p>Operation:
 * <ol>
 *   <li>Scheduled at ~1 Hz on a caller-supplied executor.</li>
 *   <li>On each tick: seek to last known offset, read up to the last
 *       complete newline, parse each JSON line, and apply via
 *       {@link AcquisitionManager#applyMeasurementEntry(PathObject, Map)}.</li>
 *   <li>A trailing partial line (no {@code \n}) is left for the next tick.</li>
 *   <li>Hierarchy change event is fired only when new entries were applied,
 *       to refresh QuPath's measurement panel.</li>
 * </ol>
 *
 * <p>Safe to run when no matching image is open -- the matching loop simply
 * finds zero detections and the poll becomes a no-op.
 *
 * <p>The NDJSON file may not exist yet when the poller starts (the Python
 * server opens it at the start of its tile loop). The poller tolerates this
 * and will pick up the file once it appears.
 */
public class LiveTileMeasurementPoller {

    private static final Logger logger = LoggerFactory.getLogger(LiveTileMeasurementPoller.class);

    private static final long POLL_PERIOD_MS = 1000L;

    private final Path ndjsonPath;
    private final String annotationName;
    private final com.google.gson.Gson gson = new com.google.gson.Gson();
    private final java.lang.reflect.Type mapType =
            new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType();

    private long offset = 0L;
    private int totalApplied = 0;
    private ScheduledFuture<?> handle;

    private LiveTileMeasurementPoller(Path ndjsonPath, String annotationName) {
        this.ndjsonPath = ndjsonPath;
        this.annotationName = annotationName;
    }

    /**
     * Starts polling the given NDJSON file. Returns a handle the caller can
     * pass to {@link #stop(LiveTileMeasurementPoller)} when acquisition
     * finishes for this annotation.
     *
     * @param ndjsonPath path to tile_measurements.ndjson (may not yet exist)
     * @param annotationName name of the annotation being acquired -- used to
     *     filter the detection hierarchy the same way the batch path does
     * @param executor shared executor to run ticks on; caller owns its lifecycle
     */
    public static LiveTileMeasurementPoller start(
            Path ndjsonPath, String annotationName, ScheduledExecutorService executor) {
        LiveTileMeasurementPoller poller = new LiveTileMeasurementPoller(ndjsonPath, annotationName);
        poller.handle =
                executor.scheduleAtFixedRate(poller::tickSafely, POLL_PERIOD_MS, POLL_PERIOD_MS, TimeUnit.MILLISECONDS);
        logger.debug("Started live tile measurement poller for annotation '{}' at {}", annotationName, ndjsonPath);
        return poller;
    }

    /**
     * Stops the poller. Runs one final tick synchronously to pick up any
     * tail entries the last scheduled tick didn't catch before cancellation.
     * Safe to call with null.
     */
    public static void stop(LiveTileMeasurementPoller poller) {
        if (poller == null) return;
        if (poller.handle != null) {
            poller.handle.cancel(false);
        }
        try {
            poller.tickSafely();
        } catch (Exception e) {
            logger.debug("Final live-poller tick failed: {}", e.getMessage());
        }
        logger.info(
                "Live tile measurement poller stopped for '{}' -- applied {} entries total",
                poller.annotationName,
                poller.totalApplied);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            logger.debug("Live poller tick error for '{}': {}", annotationName, e.getMessage());
        }
    }

    private void tick() throws IOException {
        java.io.File file = ndjsonPath.toFile();
        if (!file.exists() || file.length() <= offset) {
            return;
        }

        List<Map<String, Object>> newEntries = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            long available = raf.length() - offset;
            if (available <= 0) return;

            byte[] buf = new byte[(int) Math.min(available, Integer.MAX_VALUE)];
            raf.readFully(buf);

            // Find the last newline so we don't consume a partial trailing line.
            int lastNewline = -1;
            for (int i = buf.length - 1; i >= 0; i--) {
                if (buf[i] == '\n') {
                    lastNewline = i;
                    break;
                }
            }
            if (lastNewline < 0) {
                // No complete line yet -- leave offset alone.
                return;
            }

            String block = new String(buf, 0, lastNewline, java.nio.charset.StandardCharsets.UTF_8);
            offset += lastNewline + 1;

            for (String line : block.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    Map<String, Object> entry = gson.fromJson(trimmed, mapType);
                    if (entry != null) {
                        newEntries.add(entry);
                    }
                } catch (Exception e) {
                    logger.debug("Skipping malformed NDJSON line: {}", e.getMessage());
                }
            }
        }

        if (newEntries.isEmpty()) {
            return;
        }

        applyEntries(newEntries);
    }

    private void applyEntries(List<Map<String, Object>> entries) {
        // Build lookup from position_index -> entry
        Map<Integer, Map<String, Object>> byIndex = new HashMap<>();
        for (Map<String, Object> entry : entries) {
            Number posIdx = (Number) entry.get("position_index");
            if (posIdx != null) {
                byIndex.put(posIdx.intValue(), entry);
            }
        }
        if (byIndex.isEmpty()) return;

        QuPathGUI guiInstance = QuPathGUI.getInstance();
        PathObjectHierarchy hierarchy = (guiInstance != null && guiInstance.getImageData() != null)
                ? guiInstance.getImageData().getHierarchy()
                : QP.getCurrentHierarchy();
        if (hierarchy == null) {
            return;
        }

        List<PathObject> detections = hierarchy.getDetectionObjects().stream()
                .filter(d -> {
                    String name = d.getName();
                    return name != null && name.contains(annotationName);
                })
                .collect(Collectors.toList());

        List<PathObject> updated = new ArrayList<>();
        for (PathObject detection : detections) {
            Number tileNum = detection.getMeasurements().get("TileNumber");
            if (tileNum == null) continue;
            Map<String, Object> entry = byIndex.get(tileNum.intValue());
            if (entry == null) continue;
            AcquisitionManager.applyMeasurementEntry(detection, entry);
            updated.add(detection);
        }

        if (!updated.isEmpty()) {
            totalApplied += updated.size();
            logger.debug(
                    "Live poller applied {} entries to annotation '{}' ({} total so far)",
                    updated.size(),
                    annotationName,
                    totalApplied);
            // Fire a measurement-only change on just the touched detections.
            // fireHierarchyChangedEvent(root) forces QuPath's viewer to invalidate
            // the detection spatial cache and repaint the entire tile overlay,
            // which produced a visible "all tiles disappear then reappear" flicker
            // every poll tick. fireObjectMeasurementsChangedEvent only notifies the
            // measurement panel / measurement-dependent overlays of a value change
            // and leaves detection geometry untouched.
            try {
                hierarchy.fireObjectMeasurementsChangedEvent(hierarchy, updated);
            } catch (Exception e) {
                logger.debug("Measurement change event failed: {}", e.getMessage());
            }
        }
    }
}
