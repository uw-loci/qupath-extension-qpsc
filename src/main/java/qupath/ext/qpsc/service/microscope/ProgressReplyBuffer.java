package qupath.ext.qpsc.service.microscope;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Reassembles a long-running command's reply stream: {@code PROGRESS:<current>:<total>:<msg>}
 * messages, then {@code SUCCESS:<json>} or {@code FAILED:<reason>}.
 *
 * <p>The server writes these with no delimiter, and TCP does not keep its writes apart: one
 * {@code read()} can return two progress messages, a progress message and the head of the
 * result, or a result cut in half. Taking each read as one message dropped the head of a
 * 6 KB LC calibration result glued to the last progress message and then waited for a
 * {@code SUCCESS:} that had already gone by. This buffer keeps everything read so far and
 * answers from it; a result is returned only once its JSON parses.
 */
final class ProgressReplyBuffer {

    private static final String PROGRESS = "PROGRESS:";
    private static final String SUCCESS = "SUCCESS:";
    private static final String FAILED = "FAILED:";

    private final StringBuilder pending = new StringBuilder();
    private String lastReported;

    /** Adds the text of one read. */
    void append(String chunk) {
        pending.append(chunk);
    }

    /**
     * The JSON after {@code SUCCESS:} once all of it has arrived; null while it is
     * incomplete or absent.
     */
    String success() {
        int at = pending.indexOf(SUCCESS);
        if (at < 0) {
            return null;
        }
        String json = pending.substring(at + SUCCESS.length()).trim();
        try {
            JsonParser.parseString(json).getAsJsonObject();
            return json;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** The reason after {@code FAILED:}, as far as it has arrived; null if none. */
    String failure() {
        int at = pending.indexOf(FAILED);
        int ok = pending.indexOf(SUCCESS);
        if (at < 0 || (ok >= 0 && ok < at)) {
            return null;
        }
        return pending.substring(at + FAILED.length()).trim();
    }

    /**
     * The message part of the newest progress update, or null if nothing new has arrived
     * since the last call. Text before it is discarded, so the buffer does not grow over a
     * long run.
     */
    String latestProgressMessage() {
        if (pending.indexOf(SUCCESS) >= 0 || pending.indexOf(FAILED) >= 0) {
            return null;
        }
        int at = pending.lastIndexOf(PROGRESS);
        if (at < 0) {
            return null;
        }
        String body = pending.substring(at + PROGRESS.length());
        pending.delete(0, at);
        String[] parts = body.split(":", 3);
        String message = parts.length >= 3 ? parts[2].trim() : null;
        // The newest message is kept, since its tail may still be on the way; report it
        // again only once more of it (or a new one) has arrived.
        if (message == null || message.equals(lastReported)) {
            return null;
        }
        lastReported = message;
        return message;
    }
}
