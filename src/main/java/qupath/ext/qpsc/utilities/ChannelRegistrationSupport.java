package qupath.ext.qpsc.utilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.ext.basicstitching.registration.RegistrationMode;
import qupath.ext.basicstitching.registration.RegistrationReference;
import qupath.ext.basicstitching.stitching.TileConfigurationTxtStrategy;
import qupath.ext.basicstitching.workflow.StitchingWorkflow;

/**
 * Solves tile registration once across every channel of an acquisition, before any channel is
 * stitched.
 *
 * <h2>Why a separate class from {@link TileRegistrationSupport}</h2>
 *
 * Same reason that class exists: tiles-to-pyramid is installed separately and can be older than
 * QPSC. The types used here ({@code RegistrationReference}, {@code
 * StitchingWorkflow.solveRegistration}) arrived later than {@code RegistrationMode}, so a
 * tiles-to-pyramid can pass {@link TileRegistrationSupport}'s probe and still lack them. Keeping
 * them here means the JVM only resolves them after {@link #PROBE_CLASS} has been found. Callers
 * must go through {@code StitchingRegistration}, which checks first.
 *
 * <h2>Why solve before stitching</h2>
 *
 * Each channel is stitched from its own isolated directory, so a solve run inside one channel's
 * stitch can only see that channel. A normalized merge needs every chosen channel at once, so the
 * solve runs here, on the tile directory while all the channel subdirectories are still side by
 * side, and every channel's stitch then applies the written solution.
 */
public final class ChannelRegistrationSupport {

    private static final Logger logger = LoggerFactory.getLogger(ChannelRegistrationSupport.class);

    /** Fully-qualified name of a type that proves the channel solve API is present. */
    public static final String PROBE_CLASS = "qupath.ext.basicstitching.registration.RegistrationReference";

    private ChannelRegistrationSupport() {}

    /**
     * Solve registration across the channel subdirectories of {@code tileBaseDir} and write the
     * solution file. Any existing solution file is deleted first, so a failed solve can never leave
     * a previous run's corrections to be applied.
     *
     * @param tileBaseDir directory holding one subdirectory per channel
     * @param channels every channel subdirectory being stitched
     * @param alignOn the channel(s) to measure on: one for that channel alone, several for a
     *     normalized merge
     * @param pixelSizeMicrons pixel size the stitch will use; the solution records it and refuses to
     *     apply to a stitch that differs
     * @param downsample downsample the stitch will use
     * @param solutionFile where to write the solution
     * @return whether a solution was written
     */
    public static boolean solve(
            Path tileBaseDir,
            List<String> channels,
            List<String> alignOn,
            double pixelSizeMicrons,
            double downsample,
            Path solutionFile) {
        try {
            Files.deleteIfExists(solutionFile);
        } catch (IOException e) {
            // Applying a stale solution would place every tile by another run's measurement.
            logger.warn("Could not remove old solution {} ({}); skipping registration", solutionFile, e.toString());
            return false;
        }
        RegistrationReference reference = alignOn.size() == 1
                ? new RegistrationReference.Single(alignOn.get(0))
                : new RegistrationReference.Projection(alignOn);

        StitchingConfig config = new StitchingConfig(
                "Coordinates in TileConfiguration.txt file",
                tileBaseDir.toString(),
                tileBaseDir.toString(),
                "LZW",
                pixelSizeMicrons,
                downsample,
                "",
                1.0,
                StitchingConfig.OutputFormat.OME_TIFF);
        config.setRegistrationMode(
                new RegistrationMode.Solve(solutionFile, TileRegistrationSupport.currentSettings(), reference));

        // The solution records the flip flags and refuses to apply to a stitch with different ones,
        // so they must be exactly what each channel's stitch will set.
        boolean[] flips = StageImageTransform.current().stitcherFlipFlags();
        TileConfigurationTxtStrategy.flipStitchingX = flips[0];
        TileConfigurationTxtStrategy.flipStitchingY = flips[1];

        logger.info(
                "Solving tile registration across channels {} on {} before stitching",
                channels,
                alignOn.size() == 1 ? "'" + alignOn.get(0) + "'" : "a normalized merge of " + alignOn);
        boolean written = StitchingWorkflow.solveRegistration(config, channels);
        if (!written) {
            logger.warn("Channel registration solve wrote no solution; every channel will stitch at nominal positions");
        }
        return written;
    }
}
