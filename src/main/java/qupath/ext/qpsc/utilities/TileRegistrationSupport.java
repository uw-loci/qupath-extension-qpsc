package qupath.ext.qpsc.utilities;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationSettings;
import qupath.ext.basicstitching.registration.TileRegistrationSolution;

/**
 * The only place QPSC touches the tile-registration types from tiles-to-pyramid.
 *
 * <h2>Why this class exists at all</h2>
 *
 * tiles-to-pyramid is a <b>separate extension</b>, installed alongside QPSC rather than bundled
 * into its shadow JAR, so the two versions can drift: a workstation can perfectly well be running a
 * current QPSC against a tiles-to-pyramid from before registration existed. Every reference below
 * would then be a {@link NoClassDefFoundError}.
 *
 * <p>Isolating them here means the JVM only ever loads this class -- and therefore only resolves
 * those types -- after {@link QPPreferences#isTileRegistrationSupported()} has confirmed they are
 * present. Callers must not reference the registration types directly, or they will reintroduce the
 * failure at their own call site. That is also why this class deals in {@link Object} at its
 * boundary: an {@code instanceof RegistrationMode} in a caller resolves the class the moment the
 * instruction executes, whatever the value is.
 *
 * <p>An old tiles-to-pyramid therefore degrades to stitching at nominal stage positions -- exactly
 * what QPSC did before registration existed -- rather than failing.
 */
public final class TileRegistrationSupport {

    private static final Logger logger = LoggerFactory.getLogger(TileRegistrationSupport.class);

    /** Fully-qualified name of the type whose presence proves registration is supported. */
    public static final String PROBE_CLASS = "qupath.ext.basicstitching.registration.RegistrationMode";

    private TileRegistrationSupport() {}

    /** @return the conventional solution file name, beside the tile subdirectories. */
    public static String solutionFileName() {
        return TileRegistrationSolution.DEFAULT_FILENAME;
    }

    /**
     * @param solutionOut where the solve should be written for siblings to reuse
     * @return a mode that measures the grid and writes its solution
     */
    public static Object solveMode(Path solutionOut) {
        return new RegistrationMode.Solve(solutionOut, RegistrationSettings.defaults(), null);
    }

    /**
     * @param solutionIn the solution written by a previous {@link #solveMode}
     * @return a mode that reuses an existing solve
     */
    public static Object applyMode(Path solutionIn) {
        return new RegistrationMode.Apply(solutionIn);
    }

    /**
     * Attach a registration mode to a stitching configuration.
     *
     * @param config the configuration being built
     * @param mode a value previously produced by {@link #solveMode} or {@link #applyMode}
     * @param label identifier for the log line, e.g. the angle or channel being stitched
     */
    public static void apply(StitchingConfig config, Object mode, String label) {
        if (!(mode instanceof RegistrationMode registrationMode)) {
            logger.warn("Ignoring unrecognised registration mode for {}: {}", label, mode);
            return;
        }
        config.setRegistrationMode(registrationMode);
        logger.info(
                "Tile registration mode for {}: {}",
                label,
                registrationMode.getClass().getSimpleName());
    }
}
