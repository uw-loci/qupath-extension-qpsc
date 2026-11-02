package qupath.ext.qpsc.service.microscope;

import java.io.IOException;

/**
 * The server declined to start an acquisition because it is still running one.
 *
 * <p>This is an answer, not a fault: the socket is healthy and the server is doing exactly what
 * it should. It gets its own type because the generic I/O catch in the socket client treats every
 * {@link IOException} as a broken connection -- it counts an error, and after enough of them drops
 * the socket and reconnects. A {@code BUSY:ACQUIRING} reply would then tear down a
 * working socket, and the reconnect would be blocked by the very acquisition the reply named.
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
