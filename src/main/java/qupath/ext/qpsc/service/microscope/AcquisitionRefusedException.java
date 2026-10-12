package qupath.ext.qpsc.service.microscope;

import java.io.IOException;

/**
 * The server declined to start an acquisition because it is still running one.
 *
 * <p>This is an answer, not a fault: the socket is healthy and the server is doing exactly what
 * it should. It gets its own type because the generic I/O catch in the socket client treats every
 * {@link IOException} as a broken connection -- it counts an error, and after enough of them drops
 * the socket and reconnects. On 2026-10-12 a {@code BUSY:ACQUIRING} reply did precisely that: a
 * working socket was torn down, the reconnect was then blocked for fifteen seconds by the very
 * acquisition the reply had named, and the batch ended with the words "cancelled by user" over a
 * run nobody had touched.
 *
 * @since 0.9.3
 */
public class AcquisitionRefusedException extends IOException {

    private final String serverReply;

    public AcquisitionRefusedException(String serverReply) {
        super("The microscope is already running an acquisition. Wait for it to finish, or cancel it, "
                + "before starting another. (server replied: " + serverReply + ")");
        this.serverReply = serverReply;
    }

    /** The server's reply verbatim, e.g. {@code BUSY:ACQUIRING}. */
    public String serverReply() {
        return serverReply;
    }
}
