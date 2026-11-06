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
import qupath.ext.qpsc.stitching.ChannelRegistrationSupport;
import qupath.ext.qpsc.stitching.TileRegistrationSupport;

/**
 * The single place every stitch path goes through to apply content-based tile registration.
 *
 * <h2>Why this exists</h2>
 *
 * Registration wired into the acquisition stitch only would leave the "Stitching recovery"
 * utility silently stitching at nominal positions.
 * Routing every path through here means a stitch path cannot skip registration by omission: multi-subdir paths call {@link #stitchTargets}
 * and single-grid paths call {@link #modeForReuse}, and both honor the one "Register tiles on image
 * content" preference and the version-support probe (via {@link #enabled()}).
 *
 * <p><b>The barrier must cover every stitch entry point.</b> The single-acquisition
 * branch of {@code StitchingHelper} -- the one every brightfield and fluorescence acquisition takes,
 * and any single-angle PPM run -- must hand the stitcher a {@code stitchParams} map that
 * carries a registration mode; without one it stitches at nominal positions no matter what the
 * preference says, and logs nothing to say so. The lesson for anyone adding a stitch entry point:
 * the absence of a registration mode is indistinguishable from registration being switched off, so
 * a new path that forgets one fails silently and looks like a preference problem. Grep for
 * {@code stitchImagesAndUpdateProject} callers when auditing.
 *
 * <p>{@code WBComparisonWorkflow} deliberately does not register: it stitches a small
 * white-balance comparison strip to compare exposure settings, not a mosaic anyone measures.
 *
 * <h2>The barrier</h2>
 *
 * Angles and channels are captured at the <b>same</b> stage position per tile, so they must share
 * <b>one</b> solve or they misregister against each other -- worse than a shared nominal grid.
 * {@link #stitchTargets} therefore solves the first target synchronously (writing the solution the
 * rest reuse), then applies that solution to the remaining targets in parallel. With registration
 * off it degrades to a plain bounded-parallel stitch.
 *
 * <p>{@link Extras} (outputs a modality computed into folders of their own) join the same pool
 * after the solve and apply the same solution, but the call returns when the siblings are done,
 * so a channel merge does not wait for them.
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
     * Targets that share the siblings' tile grid and pool but that nothing after the siblings
     * reads, such as the outputs a modality computes into folders of its own (LC-PolScope
     * retardance, PPM {@code .biref}). They run on the siblings' bounded pool after any reference
     * solve, with the mode every non-reference sibling gets, and are queued behind the siblings.
     *
     * <p>{@code start} holds them back: they are submitted to the pool when it completes. A
     * completed future (the default) submits them right behind the siblings; an incomplete one
     * lets the caller run them after its next step, through {@link Batch#releaseExtras()}.
     *
     * @param targets folder names, in the order their results are returned
     * @param stitcher builds and runs the stitch for one of them
     * @param start completes when the extras may be submitted; null means at once
     */
    public record Extras(List<String> targets, TargetStitcher<String> stitcher, CompletableFuture<Void> start) {
        public Extras {
            targets = targets == null ? List.of() : List.copyOf(targets);
            start = start == null ? CompletableFuture.completedFuture(null) : start;
        }

        /** Extras submitted right behind the siblings. */
        public Extras(List<String> targets, TargetStitcher<String> stitcher) {
            this(targets, stitcher, null);
        }

        /** No extras. */
        public static Extras none() {
            return new Extras(List.of(), null, null);
        }
    }

    /**
     * What a stitch with extras returns.
     *
     * @param siblings each sibling's output path (null where it failed), in input order; all
     *     siblings have finished when the batch is returned
     * @param extras each extra's output path (null where it failed), in input order; may still be
     *     running, or not yet submitted while {@code extrasStart} is incomplete; never completes
     *     exceptionally
     * @param extrasStart the extras' start gate (see {@link Extras#start()})
     */
    public record Batch(
            List<String> siblings, CompletableFuture<List<String>> extras, CompletableFuture<Void> extrasStart) {

        /** Lets held extras start; a no-op when they already have. Call before waiting on them. */
        public void releaseExtras() {
            extrasStart.complete(null);
        }
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
        return stitchTargets(targets, tileBaseDir, maxConcurrency, referenceIndex, stitcher, Extras.none())
                .siblings();
    }

    /**
     * {@link #stitchTargets(List, Path, int, int, TargetStitcher)} with extras on the same pool.
     * The extras start once the reference solve (if any) is done and may still be running when
     * this returns.
     *
     * @param extras targets stitched with the siblings' apply mode, collected through
     *     {@link Batch#extras()}
     * @return the siblings' results and the extras' future
     */
    public static <X> Batch stitchTargets(
            List<X> targets,
            Path tileBaseDir,
            int maxConcurrency,
            int referenceIndex,
            TargetStitcher<X> stitcher,
            Extras extras) {
        int total = targets.size();
        // Results are filled BY INDEX, not appended: the reference is not necessarily the
        // first target, and callers that pair targets with outputs positionally (channel
        // split/merge) would otherwise silently mis-associate them.
        List<String> results = new ArrayList<>(Collections.nCopies(total, null));
        if (total == 0) {
            Object mode = enabled()
                    ? TileRegistrationSupport.applyMode(tileBaseDir.resolve(TileRegistrationSupport.solutionFileName()))
                    : null;
            return new Batch(
                    results,
                    stitchInParallel(targets, List.of(), mode, maxConcurrency, 0, stitcher, results, extras),
                    extras.start());
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
        CompletableFuture<List<String>> extrasDone = stitchInParallel(
                targets, remaining, applyMode, maxConcurrency, total - remaining.size(), stitcher, results, extras);
        return new Batch(results, extrasDone, extras.start());
    }

    /**
     * Stitch a channel acquisition, measuring registration on the chosen channel(s). Angle
     * acquisitions use it too, with {@code alignOn} naming the reference angle, so no angle waits
     * for another angle's pyramid write.
     *
     * <p>With a current tiles-to-pyramid the solve runs <b>before</b> any channel is stitched,
     * with every channel subdirectory in view, and every channel then applies the written
     * solution -- in parallel, the reference included. That is what lets {@code alignOn} name
     * several channels: a normalized merge needs them all at once, and a solve run inside one
     * channel's own isolated stitch can only see that channel.
     *
     * <p>An older tiles-to-pyramid has no channel solve. It falls back to solving on the first
     * {@code alignOn} channel during that channel's stitch, as {@link #stitchTargets} always has,
     * and says so when a merge was asked for.
     *
     * @param channels channel subdirectory names, in stitch order
     * @param alignOn channel(s) to measure on; one for a single channel, several for a merge
     * @param tileBaseDir directory holding the channel subdirectories and the solution file
     * @param pixelSizeMicrons pixel size every channel's stitch uses
     * @param downsample downsample every channel's stitch uses
     * @param maxConcurrency cap on parallel writers
     * @param stitcher builds and runs the stitch for one channel with the mode supplied
     * @return each channel's output path (null where it failed), in input order
     */
    public static List<String> stitchChannels(
            List<String> channels,
            List<String> alignOn,
            Path tileBaseDir,
            double pixelSizeUm,
            double downsample,
            int maxConcurrency,
            TargetStitcher<String> stitcher) {
        return stitchChannels(
                        channels,
                        alignOn,
                        tileBaseDir,
                        pixelSizeUm,
                        downsample,
                        maxConcurrency,
                        stitcher,
                        Extras.none())
                .siblings();
    }

    /**
     * {@link #stitchChannels(List, List, Path, double, double, int, TargetStitcher)} with extras on
     * the same pool, started after the solve with the mode every channel applies.
     *
     * @param extras targets stitched alongside the channels, collected through {@link Batch#extras()}
     * @return the channels' results and the extras' future, which may still be running
     */
    public static Batch stitchChannels(
            List<String> channels,
            List<String> alignOn,
            Path tileBaseDir,
            double pixelSizeUm,
            double downsample,
            int maxConcurrency,
            TargetStitcher<String> stitcher,
            Extras extras) {
        List<String> results = new ArrayList<>(Collections.nCopies(channels.size(), null));
        if (channels.isEmpty() || !enabled() || alignOn.isEmpty()) {
            return stitchTargets(channels, tileBaseDir, maxConcurrency, 0, stitcher, extras);
        }
        if (!channelSolveSupported()) {
            int reference = Math.max(0, channels.indexOf(alignOn.get(0)));
            if (alignOn.size() > 1) {
                logger.warn(
                        "Aligning on a merge of {} needs a newer tiles-to-pyramid; aligning on '{}' only",
                        alignOn,
                        channels.get(reference));
            }
            return stitchTargets(channels, tileBaseDir, maxConcurrency, reference, stitcher, extras);
        }

        Path solutionFile = tileBaseDir.resolve(TileRegistrationSupport.solutionFileName());
        // If the solve fails no file exists (it is deleted first), so every channel's Apply warns and
        // stitches at nominal -- still mutually consistent, since none of them moved.
        ChannelRegistrationSupport.solve(tileBaseDir, channels, alignOn, pixelSizeUm, downsample, solutionFile);
        Object applyMode = TileRegistrationSupport.applyMode(solutionFile);
        List<Integer> all = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            all.add(i);
        }
        return new Batch(
                results,
                stitchInParallel(channels, all, applyMode, maxConcurrency, 0, stitcher, results, extras),
                extras.start());
    }

    /**
     * Whether the installed tiles-to-pyramid can solve across channels before stitching. Checked
     * by name so that asking never loads the types themselves.
     */
    static boolean channelSolveSupported() {
        return CHANNEL_SOLVE_SUPPORTED;
    }

    private static final boolean CHANNEL_SOLVE_SUPPORTED = probe(ChannelRegistrationSupport.PROBE_CLASS);

    private static boolean probe(String className) {
        try {
            Class.forName(className, false, StitchingRegistration.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Stitch the targets at {@code indices}, then the extras, with one mode on one bounded pool,
     * writing each sibling's output into {@code results} at its target's index.
     *
     * <p>Siblings are queued first, so a free slot goes to a sibling before an extra: the caller's
     * next step (a channel merge) waits on the siblings only. The extras are submitted when their
     * start gate completes, which for an already completed gate is right behind the siblings.
     * Returns once every sibling is done; the extras may still be running (or held) and are
     * collected through the returned future.
     */
    private static <X> CompletableFuture<List<String>> stitchInParallel(
            List<X> targets,
            List<Integer> indices,
            Object mode,
            int maxConcurrency,
            int positionOffset,
            TargetStitcher<X> stitcher,
            List<String> results,
            Extras extras) {
        List<String> extraTargets = extras.targets();
        if (indices.isEmpty() && extraTargets.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        int total = targets.size();
        int concurrency = Math.max(1, Math.min(indices.size() + extraTargets.size(), maxConcurrency));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "stitch-target");
            t.setDaemon(true);
            return t;
        });
        List<CompletableFuture<String>> futures = new ArrayList<>(indices.size());
        CompletableFuture<List<String>> extrasDone;
        try {
            for (int i = 0; i < indices.size(); i++) {
                X target = targets.get(indices.get(i));
                int position = positionOffset + i + 1;
                futures.add(CompletableFuture.supplyAsync(() -> runOne(stitcher, target, mode, position, total), pool));
            }
        } finally {
            // Runs on the thread that opens the gate: at once for an open gate, so the extras queue
            // right behind the siblings. Queued tasks still run after shutdown; the pool's threads
            // end with the last of them.
            extrasDone = extras.start()
                    .handle((ignored, gateFailure) -> {
                        List<CompletableFuture<String>> extraFutures = new ArrayList<>(extraTargets.size());
                        try {
                            for (int i = 0; i < extraTargets.size(); i++) {
                                String target = extraTargets.get(i);
                                int position = i + 1;
                                extraFutures.add(CompletableFuture.supplyAsync(
                                        () -> runOne(extras.stitcher(), target, mode, position, extraTargets.size()),
                                        pool));
                            }
                        } catch (RuntimeException e) {
                            logger.error("Could not submit the derived outputs: {}", e.getMessage(), e);
                        } finally {
                            pool.shutdown();
                        }
                        return extraFutures;
                    })
                    .thenCompose(extraFutures -> CompletableFuture.allOf(extraFutures.toArray(new CompletableFuture[0]))
                            .handle((ignored, failure) -> padded(collect(extraFutures), extraTargets.size())));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .handle((ignored, failure) -> null)
                .join();
        List<String> siblingResults = collect(futures);
        for (int i = 0; i < futures.size(); i++) {
            results.set(indices.get(i), siblingResults.get(i));
        }
        return extrasDone;
    }

    /** {@code values} with nulls appended up to {@code size} (extras that were never submitted). */
    private static List<String> padded(List<String> values, int size) {
        List<String> out = new ArrayList<>(values);
        while (out.size() < size) {
            out.add(null);
        }
        return out;
    }

    /** Each future's result, null where it failed. */
    private static List<String> collect(List<CompletableFuture<String>> futures) {
        List<String> out = new ArrayList<>(futures.size());
        for (CompletableFuture<String> f : futures) {
            try {
                out.add(f.get());
            } catch (Exception e) {
                logger.error("Failed to retrieve a stitch result: {}", e.getMessage(), e);
                out.add(null);
            }
        }
        return out;
    }

    /**
     * Asks the modality which sibling target registration should be solved on, falling back to the
     * first when it has no preference.
     *
     * <p>Centralized here rather than at each call site so every stitch path -- acquisition,
     * recovery -- makes the same choice, for the same reason the barrier
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
