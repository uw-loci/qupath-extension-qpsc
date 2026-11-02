package qupath.ext.qpsc.service.microscope;

/**
 * The server declined to start an acquisition because it is still running one.
 *
 * <p>Tearing down the socket here would be worse than for other answers: the reconnect would be
 * blocked by the very acquisition the {@code BUSY:ACQUIRING} reply named.
 *
 * @since 0.9.3
 */
public class AcquisitionRefusedException extends ServerAnswerException {

    public AcquisitionRefusedException(String serverReply) {
        super(
                "The microscope is already running an acquisition. Wait for it to finish, or cancel it, "
                        + "before starting another. (server replied: " + serverReply + ")",
                serverReply);
    }

    /** The server's reply verbatim, e.g. {@code BUSY:ACQUIRING}. */
    public String serverReply() {
        return reason();
    }
}
