package qupath.ext.qpsc.service.microscope;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.config.MicroscopeConfigManager;
import qupath.ext.qpsc.state.ObjectiveState;

/**
 * The one place that works out which objective and camera are mounted.
 *
 * <p>{@link #detect()} asks the microscope: the pixel size Micro-Manager reports is matched
 * against the configured per-(objective, detector) pixel sizes, which is how the server resolves
 * the objective too. {@link #currentObjective} adds the fallback for when the microscope cannot
 * say: the objective selected for this session, then {@code microscope.objective_in_use}, then
 * (with a WARN) the first objective in the config.
 */
public final class MountedHardware {

    private static final Logger logger = LoggerFactory.getLogger(MountedHardware.class);

    /** How long an answer from the microscope is reused before it is asked again. */
    private static final long CACHE_MS = 2000;

    /**
     * Longest {@link #detect()} waits for the microscope. Callers include the FX thread (the Live
     * Viewer, dialogs being built), and the auxiliary socket's own read timeout is at least
     * 30 s, so without this bound a server that accepts the connection and never replies would
     * freeze the windows for that long. The round trip normally takes milliseconds.
     */
    private static final long PROBE_TIMEOUT_MS = 1500;

    private record Answer(Optional<MicroscopeConfigManager.HardwareSelection> hardware, long at) {}

    private static volatile Answer cached;

    private MountedHardware() {}

    /**
     * The objective and camera whose configured pixel size matches what Micro-Manager reports.
     * Empty when the microscope is not connected, does not answer within
     * {@value #PROBE_TIMEOUT_MS} ms, or reports a pixel size no configured pair has. The answer,
     * a failed one included, is reused for {@value #CACHE_MS} ms, because several callers ask in
     * quick succession and a dead server would otherwise cost each of them the full wait.
     */
    public static Optional<MicroscopeConfigManager.HardwareSelection> detect() {
        Answer last = cached;
        if (last != null && System.currentTimeMillis() - last.at() < CACHE_MS) {
            return last.hardware();
        }
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        MicroscopeController controller = MicroscopeController.getInstance();
        if (mgr == null || controller == null || !controller.isConnected()) {
            return Optional.empty();
        }
        Optional<MicroscopeConfigManager.HardwareSelection> match = Optional.empty();
        try {
            double pixelSizeUm = CompletableFuture.supplyAsync(() -> {
                        try {
                            return controller.getSocketClient().getMicroscopePixelSize();
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    })
                    .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            match = mgr.findHardwareByPixelSize(pixelSizeUm, MicroscopeConfigManager.DEFAULT_PIXEL_SIZE_TOLERANCE_UM);
            if (match.isPresent()) {
                logger.debug(
                        "Mounted hardware resolved as {} / {} from Micro-Manager pixel size {} um/px",
                        match.get().objectiveId(),
                        match.get().detectorId(),
                        pixelSizeUm);
            } else {
                logger.warn("Micro-Manager pixel size {} um/px matches no configured objective", pixelSizeUm);
            }
        } catch (TimeoutException e) {
            logger.warn("The microscope did not report its pixel size within {} ms", PROBE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Reading the Micro-Manager pixel size failed: {}", e.getMessage());
        }
        cached = new Answer(match, System.currentTimeMillis());
        return match;
    }

    /**
     * Forgets the cached answer, so the next {@link #detect()} asks the microscope. Called after
     * a command that can change the mounted hardware (a profile or a Micro-Manager preset).
     */
    public static void forget() {
        cached = null;
    }

    /**
     * The objective to use for per-objective settings: the mounted one when the microscope can
     * say, otherwise the session's selection (see the class comment for the full order). Logs a
     * WARN when the mounted objective and the session's selection disagree. Returns null only
     * when no source has an answer.
     */
    public static String currentObjective(MicroscopeConfigManager configManager) {
        // 1. Ask the hardware. The pixel size MicroManager reports identifies the objective,
        //    and the SERVER resolves it the same way ("resolved objective ... via pixel-match"),
        //    so the two ends agree by construction. This has to come first: the config field
        //    below is never populated at runtime on these rigs, so everything that trusted it
        //    was really running on the first-in-list fallback.
        String fromHardware = detect().map(MicroscopeConfigManager.HardwareSelection::objectiveId)
                .orElse(null);
        String selected = ObjectiveState.getInstance().getObjective();
        if (fromHardware != null) {
            // Say so when the operator's selection and the mounted objective disagree. Nothing
            // downstream can tell: exposures, autofocus ranges and pixel sizes are all looked up
            // per objective, so a wrong selection produces a plausible-looking run with the
            // wrong numbers throughout. The hardware wins here, but the operator needs to know
            // their selection is being overruled -- the acquisition that follows uses THEIR
            // choice, not this one.
            if (selected != null && !selected.isEmpty() && !selected.equals(fromHardware)) {
                logger.warn(
                        "OBJECTIVE MISMATCH: the microscope reports {} but this session is set to {}. "
                                + "Using {} for hardware settings; check the objective selected for the run, "
                                + "because acquisition uses the selected one.",
                        fromHardware,
                        selected,
                        fromHardware);
            }
            return fromHardware;
        }

        // 2. What the operator chose for this session. Wrong only if they picked wrongly, which
        //    is a different and much more visible kind of wrong.
        if (selected != null && !selected.isEmpty()) {
            logger.info("Objective resolved as {} from the session's selection (hardware did not answer)", selected);
            return selected;
        }

        try {
            // getString rather than walking getAllConfig() by hand: the raw-map read needed an
            // unchecked cast to Map<String, Object>, which is a cast the compiler cannot verify
            // and one that would have thrown at runtime if the key ever held a scalar. The
            // typed accessor does the same lookup and returns null for anything that is not a
            // string, which is what every branch here wanted anyway.
            String objectiveInUse = configManager.getString("microscope", "objective_in_use");
            if (objectiveInUse != null && !objectiveInUse.isEmpty()) {
                logger.info("Objective resolved as {} from microscope.objective_in_use", objectiveInUse);
                return objectiveInUse;
            }

            // 3. Last resort, and a genuinely dangerous answer -- hence WARN, not INFO. It is
            //    a GUESS with no evidence behind it, and the callers feed it straight into
            //    per-objective exposure tables. On PPM the first entry is the 10x, whose
            //    uncrossed exposure is ~4x the 20x one: applying it at 20x saturates the
            //    sensor and autofocus then reports "99.4% of pixels saturated" three steps
            //    later, with nothing pointing back here.
            List<Map<String, Object>> hardwareObjectives = configManager.getHardwareObjectives();
            if (!hardwareObjectives.isEmpty()) {
                Object objectiveId = hardwareObjectives.get(0).get("id");
                if (objectiveId != null) {
                    logger.warn(
                            "GUESSING objective '{}' -- it is simply the first in the hardware list. "
                                    + "MicroManager could not be asked, no objective is selected for this session, "
                                    + "and microscope.objective_in_use is not set. Per-objective exposures and "
                                    + "autofocus settings will be wrong if this is not what is mounted.",
                            objectiveId);
                    return objectiveId.toString();
                }
            }

        } catch (Exception e) {
            logger.error("Error reading current objective from config", e);
        }

        return null;
    }
}
