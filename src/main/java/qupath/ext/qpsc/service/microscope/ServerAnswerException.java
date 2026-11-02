package qupath.ext.qpsc.service.microscope;

import java.io.IOException;

/**
 * The server answered, and the answer is a refusal or a failure.
 *
 * <p>The socket is healthy and nothing needs reconnecting, so this must never reach
 * {@code handleIOException}: counting answers as connection errors raises "Microscope Connection
 * Unstable" and drops a good socket. It extends {@link IOException} so callers that already
 * handle a failed command keep working.
 */
public class ServerAnswerException extends IOException {

    private final String reason;

    /**
     * @param message the full message to show
     * @param reason the server's own words
     */
    public ServerAnswerException(String message, String reason) {
        super(message);
        this.reason = reason;
    }

    /** The server's own words, without any prefix. */
    public String reason() {
        return reason;
    }
}
