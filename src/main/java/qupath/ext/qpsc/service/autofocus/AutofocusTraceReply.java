package qupath.ext.qpsc.service.autofocus;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.List;

/**
 * The reply to AFTRACE: the autofocus events after the sequence number asked for.
 *
 * <p>{@code seq} is the newest event the server holds, {@code first} the oldest it still holds
 * (a reader whose last event is older than that has missed some), {@code more} says the reply
 * stopped short and another request will return the rest, and {@code active} is the start event
 * of the search running now, or null.
 */
public final class AutofocusTraceReply {

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    public long seq;
    public long first;
    public boolean more;
    public AutofocusEvent active;
    public List<AutofocusEvent> events;

    /** Parses the server's JSON; a reply with no events list gets an empty one. */
    public static AutofocusTraceReply parse(String json) {
        AutofocusTraceReply reply = GSON.fromJson(json, AutofocusTraceReply.class);
        if (reply == null) {
            reply = new AutofocusTraceReply();
        }
        if (reply.events == null) {
            reply.events = List.of();
        }
        return reply;
    }
}
