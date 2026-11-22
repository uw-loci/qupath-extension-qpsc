package qupath.ext.qpsc.service.microscope;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs a WARN, once per call site, when a socket command starts on the JavaFX application thread.
 *
 * <p>A command holds its socket's lock until the server answers, and a slow or dead server makes
 * that seconds to minutes. On the FX thread every QuPath window stops repainting and answering for
 * that long. The warning names the first caller outside the socket classes, so a normal session's
 * log lists the call sites that still need moving to a background thread.
 */
final class FxThreadSocketUse {

    private static final Logger logger = LoggerFactory.getLogger(FxThreadSocketUse.class);

    private static final Set<String> CLASSES_TO_SKIP = Set.of(
            FxThreadSocketUse.class.getName(),
            MicroscopeSocketClient.class.getName(),
            MicroscopeController.class.getName());

    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private FxThreadSocketUse() {}

    /**
     * Warns if the current thread is the FX application thread.
     *
     * @param socket which socket the command uses, for the message ("primary" or "auxiliary")
     */
    static void check(String socket) {
        check(socket, javafx.application.Platform::isFxApplicationThread);
    }

    /**
     * Same as {@link #check(String)}, with the FX-thread test supplied (for tests).
     *
     * @return true if a warning was logged by this call
     */
    static boolean check(String socket, BooleanSupplier onFxThread) {
        if (!onFxThread.getAsBoolean()) {
            return false;
        }
        String site = callSite().orElse("unknown caller");
        if (!reported.add(socket + " " + site)) {
            return false;
        }
        logger.warn(
                "Socket command on the FX thread ({} socket) from {}: every QuPath window waits until the"
                        + " server answers. Logged once per call site.",
                socket,
                site);
        return true;
    }

    /** The first stack frame outside the socket classes, as class.method:line. */
    static Optional<String> callSite() {
        return StackWalker.getInstance()
                .walk(frames -> frames.filter(f -> !CLASSES_TO_SKIP.contains(f.getClassName())
                                && !f.getClassName().startsWith(MicroscopeSocketClient.class.getName() + "$")
                                && !f.getClassName().startsWith(MicroscopeController.class.getName() + "$"))
                        .findFirst()
                        .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber()));
    }

    /** Forgets the reported call sites (for tests). */
    static void resetForTests() {
        reported.clear();
    }
}
