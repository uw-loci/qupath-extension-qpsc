package qupath.ext.qpsc.service.microscope;

/**
 * The server ran an autofocus and reports that it found no focus.
 *
 * <p>The stage is back where the search started. It has its own type so callers can tell a slide
 * that would not focus from any other failure.
 */
public class AutofocusFailedException extends ServerAnswerException {

    /** @param reason the server's reason, which is shown to the operator */
    public AutofocusFailedException(String reason) {
        super(reason, reason);
    }
}
