package qupath.ext.qpsc.service.autofocus;

/**
 * One event from the microscope server's autofocus log (AFTRACE): a search's
 * {@code start}, one {@code sample} per scored Z, or its {@code end}.
 *
 * <p>Filled from the server's JSON by Gson with snake_case names, so fields a given event does
 * not carry stay null. See {@code microscope_command_server/server/af_events.py} for the schema.
 */
public final class AutofocusEvent {

    /** Position in the server's log; strictly increasing. */
    public long seq;
    /** Server wall clock, seconds since the epoch. */
    public double t;
    /** {@code start}, {@code sample} or {@code end}. */
    public String type;
    /** The search this event belongs to. */
    public long run;

    // start (method also repeated on end)
    /** {@code sweep}, {@code drift_check} or {@code streaming}. */
    public String method;
    /** What the search is for, e.g. "Tile 12 of 80"; null for a search with no label. */
    public String context;

    public Integer tile;
    public Double x;
    public Double y;
    public Double z0;
    public Double searchRange;
    public Double rangeUm;
    public Integer nSteps;
    public String scoreMetric;

    // sample
    public Double z;
    public Double score;
    /** P98 - P2 of the frame, when the search measured it. */
    public Double spread;

    // end
    /** {@code ok}, {@code failed}, {@code aborted}, {@code error} or {@code unavailable}. */
    public String status;

    public Double elapsedS;
    public String reason;

    public boolean isStart() {
        return "start".equals(type);
    }

    public boolean isSample() {
        return "sample".equals(type);
    }

    public boolean isEnd() {
        return "end".equals(type);
    }

    /** "Tile 12 of 80" when labelled, else the method as a person would say it. */
    public String describe() {
        String what = methodName(method);
        return context != null && !context.isBlank() ? context + " (" + what + ")" : what;
    }

    /**
     * How a search ended, for a status line: "Tile 3 of 10 (drift check): focus at Z -310.90
     * (+1.50 um), 21.4 s", "...: no focus found: peak at the edge, 40.2 s", "...: cancelled".
     *
     * @param start the search's start event, or null when it was not seen
     * @param end its end event
     */
    public static String describeOutcome(AutofocusEvent start, AutofocusEvent end) {
        String what = start != null ? start.describe() : methodName(end.method);
        String time = end.elapsedS != null ? String.format(", %.1f s", end.elapsedS) : "";
        String reason = end.reason != null && !end.reason.isBlank() ? ": " + end.reason : "";
        String status = end.status == null ? "" : end.status;
        return switch (status) {
            case "ok" -> {
                String shift = end.z != null && start != null && start.z0 != null
                        ? String.format(" (%+.2f um)", end.z - start.z0)
                        : "";
                yield what + (end.z != null ? String.format(": focus at Z %.2f", end.z) : ": done") + shift + time;
            }
            case "failed" -> what + ": no focus found" + reason + time;
            case "aborted" -> what + ": cancelled" + time;
            case "unavailable" -> what + ": not run" + reason;
            default -> what + ": stopped with an error" + reason + time;
        };
    }

    /** The method as a person would say it: "full search", "drift check", "streaming". */
    public static String methodName(String method) {
        if (method == null) return "autofocus";
        return switch (method) {
            case "sweep" -> "full search";
            case "drift_check" -> "drift check";
            case "streaming" -> "streaming";
            default -> method;
        };
    }
}
