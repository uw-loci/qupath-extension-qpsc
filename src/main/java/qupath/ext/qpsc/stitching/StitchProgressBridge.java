package qupath.ext.qpsc.stitching;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reaches tiles-to-pyramid's progress reporting (its {@code progress} package, newer than the
 * tiles-to-pyramid QPSC compiles against) by reflection, so QPSC builds and runs with an older
 * tiles-to-pyramid too: there every call here is a no-op and every prediction NaN.
 */
public final class StitchProgressBridge {

    private static final Logger logger = LoggerFactory.getLogger(StitchProgressBridge.class);

    private static final Api API = Api.load();

    private StitchProgressBridge() {}

    /** Whether the installed tiles-to-pyramid reports progress and predicts stitch times. */
    public static boolean available() {
        return API != null;
    }

    /**
     * Have the stitch run with {@code config} (a tiles-to-pyramid {@code StitchingConfig}) report
     * to {@code sink}. No-op with an older tiles-to-pyramid.
     */
    public static void attach(Object config, Consumer<TargetEta> sink) {
        if (API == null || config == null || sink == null) {
            return;
        }
        Consumer<Object> listener = p -> {
            try {
                sink.accept(API.convert(p));
            } catch (ReflectiveOperationException | RuntimeException e) {
                logger.debug("Stitch progress not readable: {}", e.toString());
            }
        };
        try {
            API.setProgressListener.invoke(config, listener);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.debug("Could not attach stitch progress: {}", e.toString());
        }
    }

    /**
     * Seconds one target should take before it starts, at the speeds this computer learned for
     * {@code compression}: tile headers, the solve ({@code solveBandPixels} 0 when the target only
     * applies a solution) and the pyramid write of a {@code width x height} image with
     * {@code bytesPerPixel} (channels x bytes per sample). NaN with an older tiles-to-pyramid.
     */
    public static double predictSeconds(
            long tiles, long solveBandPixels, int width, int height, int bytesPerPixel, String compression) {
        if (API == null) {
            return Double.NaN;
        }
        try {
            double[] ds = (double[]) API.computePyramidDownsamples.invoke(null, width, height, 1.0, 512);
            long bytes = (long) API.pyramidBytes.invoke(null, width, height, 1, Math.max(1, bytesPerPixel), ds);
            Object rates = API.loadRates.invoke(null, compression);
            Object work = API.workCtor.newInstance(tiles, solveBandPixels, bytes);
            return (double) API.predictSeconds.invoke(null, work, rates);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.debug("No stitch time prediction: {}", e.toString());
            return Double.NaN;
        }
    }

    /** The reflected members; null when any is missing. */
    private record Api(
            Method setProgressListener,
            Method target,
            Method phase,
            Method phaseLabel,
            Method phaseFraction,
            Method elapsedSeconds,
            Method remainingSeconds,
            Method measured,
            Method computePyramidDownsamples,
            Method pyramidBytes,
            Method loadRates,
            Constructor<?> workCtor,
            Method predictSeconds) {

        static Api load() {
            try {
                ClassLoader cl = StitchProgressBridge.class.getClassLoader();
                Class<?> config = Class.forName("qupath.ext.basicstitching.config.StitchingConfig", false, cl);
                Class<?> progress = Class.forName(TargetEta.PROBE_CLASS, false, cl);
                Class<?> phase = Class.forName("qupath.ext.basicstitching.progress.StitchPhase", false, cl);
                Class<?> writer =
                        Class.forName("qupath.ext.basicstitching.assembly.direct.DirectTiffOutputWriter", false, cl);
                Class<?> learned = Class.forName("qupath.ext.basicstitching.progress.LearnedRates", false, cl);
                Class<?> eta = Class.forName("qupath.ext.basicstitching.progress.StitchEta", false, cl);
                Class<?> work = Class.forName("qupath.ext.basicstitching.progress.StitchEta$Work", false, cl);
                Class<?> rates = Class.forName("qupath.ext.basicstitching.progress.StitchEta$Rates", false, cl);
                return new Api(
                        config.getMethod("setProgressListener", Consumer.class),
                        progress.getMethod("target"),
                        progress.getMethod("phase"),
                        phase.getMethod("label"),
                        progress.getMethod("phaseFraction"),
                        progress.getMethod("elapsedSeconds"),
                        progress.getMethod("remainingSeconds"),
                        progress.getMethod("measured"),
                        writer.getMethod("computePyramidDownsamples", int.class, int.class, double.class, int.class),
                        writer.getMethod("pyramidBytes", int.class, int.class, int.class, int.class, double[].class),
                        learned.getMethod("load", String.class),
                        work.getConstructor(long.class, long.class, long.class),
                        eta.getMethod("predictSeconds", work, rates));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                LoggerFactory.getLogger(StitchProgressBridge.class)
                        .info("Stitch time estimates need a newer tiles-to-pyramid ({}); none shown", e.toString());
                return null;
            }
        }

        TargetEta convert(Object p) throws ReflectiveOperationException {
            return new TargetEta(
                    (String) target.invoke(p),
                    (String) phaseLabel.invoke(phase.invoke(p)),
                    (double) phaseFraction.invoke(p),
                    (double) elapsedSeconds.invoke(p),
                    (double) remainingSeconds.invoke(p),
                    (boolean) measured.invoke(p));
        }
    }
}
