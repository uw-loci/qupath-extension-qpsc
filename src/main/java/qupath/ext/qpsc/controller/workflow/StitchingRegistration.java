package qupath.ext.qpsc.controller.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.TileRegistrationSupport;

/**
 * The single place every stitch path goes through to apply content-based tile registration.
 *
 * <h2>Why this exists</h2>
 *
 * Registration used to be wired into the acquisition stitch only, so the "Stitching recovery" and
 * "MicroManager folder stitch" utilities silently stitched at nominal positions -- a disconnect
 * that stayed invisible until a re-stitch looked wrong. Routing every path through here means a
 * stitch path cannot skip registration by omission: multi-subdir paths call {@link #stitchTargets}
 * and single-grid paths call {@link #modeForReuse}, and both honour the one "Register tiles on image
 * content" preference and the version-support probe (via {@link #enabled()}).
 *
 * <h2>The barrier</h2>
 *
 * Angles and channels are captured at the <b>same</b> stage position per tile, so they must share
 * <b>one</b> solve or they misregister against each other -- worse than a shared nominal grid.
 * {@link #stitchTargets} therefore solves the first target synchronously (writing the solution the
 * rest reuse), then applies that solution to the remaining targets in parallel. With registration
 * off it degrades to a plain bounded-parallel stitch.
 */
public final class StitchingRegistration {

    private static final Logger logger = LoggerFactory.getLogger(StitchingRegistration.class);

    private StitchingRegistration() {}

    /**
     * @return whether content-based registration is both enabled by preference and supported by the
     *     installed tiles-to-pyramid (an older jar stitches at nominal positions instead).
     */
    public static boolean enabled() {
        return QPPreferenceDialog.getTileRegistrationEnabled();
    }

    /**
     * The registration mode for a single-grid stitch that has no sibling targets: reuse an existing
     * solution if one sits beside the tiles, otherwise solve a fresh one.
     *
     * @param tileBaseDir directory the {@code TileRegistration.txt} lives beside
     * @return a {@code RegistrationMode} (typed {@link Object} to stay behind the version-isolation
     *     boundary), or {@code null} when registration is off/unsupported
     */
    public static Object modeForReuse(Path tileBaseDir) {
        if (!enabled()) {
            return null;
        }
        Path solutionFile = tileBaseDir.resolve(TileRegistrationSupport.solutionFileName());
        return Files.exists(solutionFile)
                ? TileRegistrationSupport.applyMode(solutionFile)
                : TileRegistrationSupport.solveMode(solutionFile);
    }

    /**
     * Attach the single-grid registration mode to a stitching config when registration is on. For
     * paths that stitch one grid in one call and so have no sibling targets to barrier against (e.g.
     * the MicroManager folder stitch). A no-op when registration is off/unsupported.
     *
     * @param config the config about to be stitched
     * @param tileBaseDir directory the solution file lives beside
     * @param matchingString the config's subdir matching string, for the log line
     */
    public static void applyTo(StitchingConfig config, Path tileBaseDir, String matchingString) {
        Object mode = modeForReuse(tileBaseDir);
        if (mode != null) {
            TileRegistrationSupport.apply(config, mode, matchingString);
        }
    }

    /**
     * Attach a registration mode chosen by {@link #stitchTargets} (or null) to a config a caller is
     * about to stitch itself. Lets a path that builds its own config and calls the stitcher directly
     * still participate in the barrier without touching the version-isolation class. A no-op for a
     * null mode.
     *
     * @param config the config about to be stitched
     * @param mode the mode {@link TargetStitcher} was handed, or null
     * @param matchingString the config's subdir matching string, for the log line
     */
    public static void attachMode(StitchingConfig config, Object mode, String matchingString) {
        if (mode != null) {
            TileRegistrationSupport.apply(config, mode, matchingString);
        }
    }

    /** Stitches one target with the supplied registration mode, returning its output path or null. */
    @FunctionalInterface
    public interface TargetStitcher<X> {
        String stitch(X target, Object registrationMode, int position, int total) throws Exception;
    }

    /**
     * Stitch a set of sibling targets (angle/channel subdirs) that share one grid, applying the
     * registration barrier when enabled and a plain bounded-parallel stitch otherwise.
     *
     * @param targets the sibling targets in order
     * @param tileBaseDir directory the shared solution file is written to / read from
     * @param maxConcurrency cap on parallel writers for the non-reference targets
     * @param referenceIndex index into {@code targets} of the one to SOLVE on; every other target
     *     reuses that solve. Out-of-range values fall back to 0. Use
     *     {@link #referenceIndexFor(List, ModalityHandler)} to derive it.
     * @param stitcher builds and runs the stitch for one target with the mode this method supplies
     * @param <X> the caller's target type (e.g. a subdir name or a directory)
     * @return each target's output path (null where a target failed), in input order
     */
    public static <X> List<String> stitchTargets(
            List<X> targets, Path tileBaseDir, int maxConcurrency, int referenceIndex, TargetStitcher<X> stitcher) {
        int total = targets.size();
        // Results are filled BY INDEX, not appended: the reference is no longer necessarily the
        // first target, and callers that pair targets with outputs positionally (channel
        // split/merge) would otherwise silently mis-associate them.
        List<String> results = new ArrayList<>(Collections.nCopies(total, null));
        if (total == 0) {
            return results;
        }

        int reference = (referenceIndex >= 0 && referenceIndex < total) ? referenceIndex : 0;
        List<Integer> remaining = new ArrayList<>(total);
        Object applyMode = null;
        if (enabled()) {
            Path solutionFile = tileBaseDir.resolve(TileRegistrationSupport.solutionFileName());
            logger.info(
                    "Tile registration enabled: solving on target {} of {} ('{}'), then reusing that solve",
                    reference + 1,
                    total,
                    targets.get(reference));
            results.set(
                    reference,
                    runOne(
                            stitcher,
                            targets.get(reference),
                            TileRegistrationSupport.solveMode(solutionFile),
                            1,
                            total));
            // If the solve failed the solution file will be absent; the remaining targets then warn
            // and stitch at nominal, which still leaves every target mutually consistent (none moved).
            applyMode = TileRegistrationSupport.applyMode(solutionFile);
            for (int i = 0; i < total; i++) {
                if (i != reference) {
                    remaining.add(i);
                }
            }
        } else {
            for (int i = 0; i < total; i++) {
                remaining.add(i);
            }
        }
        if (remaining.isEmpty()) {
            return results;
        }

        int concurrency = Math.max(1, Math.min(remaining.size(), maxConcurrency));
        int offset = total - remaining.size();
        final Object mode = applyMode;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "stitch-target");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>(remaining.size());
            for (int i = 0; i < remaining.size(); i++) {
                X target = targets.get(remaining.get(i));
                int position = offset + i + 1;
                futures.add(CompletableFuture.supplyAsync(() -> runOne(stitcher, target, mode, position, total), pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.set(remaining.get(i), futures.get(i).get());
                } catch (Exception e) {
                    logger.error("Failed to retrieve a stitch result: {}", e.getMessage());
                    results.set(remaining.get(i), null);
                }
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Asks the modality which sibling target registration should be solved on, falling back to the
     * first when it has no preference.
     *
     * <p>Centralised here rather than at each call site so every stitch path -- acquisition,
     * recovery, MicroManager folder -- makes the same choice, for the same reason the barrier
     * itself lives here.
     *
     * @param targetNames sibling target names in the same order as the targets being stitched
     * @param handler the active modality handler, may be null
     * @return index into {@code targetNames}, always in range
     */
    public static int referenceIndexFor(List<String> targetNames, ModalityHandler handler) {
        if (targetNames == null || targetNames.isEmpty()) {
            return 0;
        }
        if (handler != null) {
            java.util.OptionalInt preferred = handler.registrationReferenceIndex(targetNames);
            if (preferred.isPresent()) {
                int index = preferred.getAsInt();
                if (index >= 0 && index < targetNames.size()) {
                    logger.info(
                            "Registration reference: '{}' selected by {}",
                            targetNames.get(index),
                            handler.getClass().getSimpleName());
                    return index;
                }
                logger.warn(
                        "{} asked to solve on out-of-range target index {} of {}; using the first instead",
                        handler.getClass().getSimpleName(),
                        index,
                        targetNames.size());
            }
        }
        logger.info(
                "Registration reference: '{}' (first target; modality expressed no preference)", targetNames.get(0));
        return 0;
    }

    /** Run one target's stitch, turning any failure into a null result so siblings still complete. */
    private static <X> String runOne(TargetStitcher<X> stitcher, X target, Object mode, int position, int total) {
        try {
            return stitcher.stitch(target, mode, position, total);
        } catch (Exception e) {
            logger.error("Stitch failed for target {}/{}: {}", position, total, e.getMessage(), e);
            return null;
        }
    }
}
