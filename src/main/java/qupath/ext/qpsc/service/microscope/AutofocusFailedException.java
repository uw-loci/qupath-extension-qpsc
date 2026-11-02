package qupath.ext.qpsc.service.microscope;

import java.io.IOException;

/**
 * The server ran an autofocus and reports that it found no focus.
 *
 * <p>This is an answer, not a fault: the socket is healthy and the stage is back where the search
 * started. It gets its own type for the reason {@link AcquisitionRefusedException} does: the
 * generic I/O catch in the socket client treats every {@link IOException} as a broken connection,
 * so a few slides that would not focus would have raised "Microscope Connection Unstable".
 */
public class AutofocusFailedException extends IOException {

    /** @param reason the server's reason, which is shown to the operator */
    public AutofocusFailedException(String reason) {
        super(reason);
    }
}
