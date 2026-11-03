package qupath.ext.qpsc.controller.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one-line status of a stitch job whose targets run concurrently: what the job is doing
 * now, plus how far its siblings (angles or channels) and its derived outputs have got.
 *
 * <p>Derived outputs run on the siblings' pool and can outlive a channel merge, so the line names
 * the ones still stitching rather than leaving a "Merging..." line up while they finish. Any
 * thread; every change publishes the whole line to the sink.
 */
final class StitchJobStatus {

    private enum State {
        WAITING,
        STITCHING,
        DONE,
        FAILED
    }

    private final String siblingKind;
    private final Consumer<String> sink;
    private final Map<String, State> siblings = new LinkedHashMap<>();
    private final Map<String, State> derived = new LinkedHashMap<>();
    private String phase = "";

    /**
     * @param siblingKind plural noun for the siblings, e.g. "channels" or "angles"
     * @param siblingNames the angle or channel folders, in order
     * @param derivedNames the derived folders, in order
     * @param sink receives the full line on every change
     */
    StitchJobStatus(String siblingKind, List<String> siblingNames, List<String> derivedNames, Consumer<String> sink) {
        this.siblingKind = siblingKind;
        this.sink = sink;
        siblingNames.forEach(n -> siblings.put(n, State.WAITING));
        derivedNames.forEach(n -> derived.put(n, State.WAITING));
    }

    /** Sets what the job is doing now, e.g. "Merging 5 channels". */
    void phase(String text) {
        String line;
        synchronized (this) {
            phase = text;
            line = text();
        }
        sink.accept(line);
    }

    void started(String name) {
        set(name, State.STITCHING);
    }

    void finished(String name, boolean ok) {
        set(name, ok ? State.DONE : State.FAILED);
    }

    private void set(String name, State state) {
        String line;
        synchronized (this) {
            if (siblings.containsKey(name)) {
                siblings.put(name, state);
            } else if (derived.containsKey(name)) {
                derived.put(name, state);
            } else {
                return;
            }
            line = text();
        }
        sink.accept(line);
    }

    /** True while a derived output is waiting or stitching. */
    synchronized boolean derivedPending() {
        return derived.values().stream().anyMatch(s -> s == State.WAITING || s == State.STITCHING);
    }

    /** The current line. */
    synchronized String text() {
        List<String> parts = new ArrayList<>();
        if (!siblings.isEmpty()) {
            parts.add(group(siblingKind, siblings));
        }
        if (!derived.isEmpty()) {
            parts.add(group("derived outputs", derived));
        }
        if (parts.isEmpty()) {
            return phase;
        }
        return phase + " (" + String.join("; ", parts) + ")";
    }

    private static String group(String kind, Map<String, State> states) {
        long done = states.values().stream().filter(s -> s == State.DONE).count();
        StringBuilder sb = new StringBuilder();
        sb.append(kind)
                .append(' ')
                .append(done)
                .append(" of ")
                .append(states.size())
                .append(" done");
        appendNames(sb, "failed", states, State.FAILED);
        appendNames(sb, "stitching", states, State.STITCHING);
        appendNames(sb, "queued", states, State.WAITING);
        return sb.toString();
    }

    private static void appendNames(StringBuilder sb, String label, Map<String, State> states, State state) {
        List<String> names = new ArrayList<>();
        states.forEach((n, s) -> {
            if (s == state) {
                names.add(n);
            }
        });
        if (!names.isEmpty()) {
            sb.append(", ").append(label).append(": ").append(String.join(", ", names));
        }
    }
}
