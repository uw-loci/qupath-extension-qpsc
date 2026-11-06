package qupath.ext.qpsc.controller.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import qupath.ext.qpsc.stitching.JobEta;
import qupath.ext.qpsc.stitching.TargetEta;

/**
 * The one-line status of a stitch job whose targets run concurrently: what the job is doing
 * now, plus how far its siblings (angles or channels) and its derived outputs have got.
 *
 * <p>Derived outputs run on the siblings' pool and can outlive a channel merge, so the line names
 * the ones still stitching rather than leaving a "Merging..." line up while they finish; held
 * derived outputs (the "Stitch derived outputs concurrently" preference off) are listed as waiting
 * until {@link #releaseDerived()}. Any thread; every change publishes the whole line to the sink.
 *
 * <p>Time left ({@link #remainingSeconds()}): the targets stitching now report theirs (tiles-to-pyramid
 * measures it); a target still waiting is costed at its predicted time times the slowdown the
 * started targets show against their own predictions; all of them are scheduled on the pool's
 * writers ({@link JobEta#makespan}). A registration solve still running comes first.
 */
final class StitchJobStatus {

    private enum State {
        HELD,
        WAITING,
        STITCHING,
        DONE,
        FAILED
    }

    private final String siblingKind;
    private final Consumer<String> sink;
    private final Map<String, State> siblings = new LinkedHashMap<>();
    private final Map<String, State> derived = new LinkedHashMap<>();
    private final String heldUntil;
    private String phase = "";
    private String note = "";

    private final Map<String, TargetEta> eta = new LinkedHashMap<>();
    private final Map<String, Double> durations = new LinkedHashMap<>();
    private Map<String, Double> predicted = Map.of();
    private int slots = 1;
    private Consumer<Double> remainingSink;
    private TargetEta solve;

    /**
     * @param siblingKind plural noun for the siblings, e.g. "channels" or "angles"
     * @param siblingNames the angle or channel folders, in order
     * @param derivedNames the derived folders, in order
     * @param sink receives the full line on every change
     */
    StitchJobStatus(String siblingKind, List<String> siblingNames, List<String> derivedNames, Consumer<String> sink) {
        this(siblingKind, siblingNames, derivedNames, null, sink);
    }

    /**
     * @param heldUntil when the derived outputs are held back, what they wait for (e.g. "the
     *     merge"); null when they are queued behind the siblings
     */
    StitchJobStatus(
            String siblingKind,
            List<String> siblingNames,
            List<String> derivedNames,
            String heldUntil,
            Consumer<String> sink) {
        this.siblingKind = siblingKind;
        this.sink = sink;
        this.heldUntil = heldUntil;
        State initial = heldUntil == null ? State.WAITING : State.HELD;
        siblingNames.forEach(n -> siblings.put(n, State.WAITING));
        derivedNames.forEach(n -> derived.put(n, initial));
    }

    /** The held derived outputs are queued now. */
    void releaseDerived() {
        String line;
        synchronized (this) {
            derived.replaceAll((n, s) -> s == State.HELD ? State.WAITING : s);
            line = text();
        }
        sink.accept(line);
    }

    /** A short line appended to every status, e.g. a memory warning; empty for none. */
    void note(String text) {
        String line;
        synchronized (this) {
            note = text == null ? "" : text;
            line = text();
        }
        sink.accept(line);
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
        synchronized (this) {
            TargetEta e = eta.get(name);
            if (e != null && ok) {
                durations.put(name, e.elapsedSec());
            }
            eta.remove(name);
        }
        set(name, ok ? State.DONE : State.FAILED);
        publishRemaining();
    }

    /**
     * Time-left inputs: the pool's writer count and each target's predicted seconds (missing or
     * NaN when unknown).
     */
    synchronized void configureEta(int writers, Map<String, Double> predictedSeconds) {
        slots = Math.max(1, writers);
        predicted = predictedSeconds == null ? Map.of() : new LinkedHashMap<>(predictedSeconds);
    }

    /** Where to send the job's time left on every change; null for nowhere. */
    synchronized void onRemaining(Consumer<Double> sink) {
        remainingSink = sink;
    }

    /** Progress of one target, or of the registration solve (a name that is not a target). */
    void progress(TargetEta e) {
        synchronized (this) {
            if (siblings.containsKey(e.target()) || derived.containsKey(e.target())) {
                eta.put(e.target(), e);
            } else {
                solve = e.fraction() >= 1 && "measuring tile overlaps".equals(e.step()) ? null : e;
            }
        }
        publishRemaining();
    }

    /** The registration solve ended (its listener does not say so once the job moves on). */
    void solveDone() {
        synchronized (this) {
            solve = null;
        }
        publishRemaining();
    }

    private void publishRemaining() {
        Consumer<Double> s;
        double r;
        synchronized (this) {
            s = remainingSink;
            r = remainingSeconds();
        }
        if (s != null) {
            s.accept(r);
        }
    }

    /** Estimated seconds until every target is stitched, NaN when not known. */
    synchronized double remainingSeconds() {
        // Slowdown: how much longer the started targets take than predicted.
        double ratioSum = 0;
        int ratios = 0;
        for (Map.Entry<String, Double> d : durations.entrySet()) {
            double p = predicted.getOrDefault(d.getKey(), Double.NaN);
            if (p > 0) {
                ratioSum += d.getValue() / p;
                ratios++;
            }
        }
        for (TargetEta e : eta.values()) {
            double p = predicted.getOrDefault(e.target(), Double.NaN);
            if (e.measured() && p > 0 && Double.isFinite(e.remainingSec())) {
                ratioSum += (e.elapsedSec() + e.remainingSec()) / p;
                ratios++;
            }
        }
        double slowdown = ratios > 0 ? ratioSum / ratios : 1.0;

        List<Double> running = new ArrayList<>();
        List<Double> waiting = new ArrayList<>();
        Map<String, State> all = new LinkedHashMap<>(siblings);
        all.putAll(derived);
        for (Map.Entry<String, State> t : all.entrySet()) {
            switch (t.getValue()) {
                case STITCHING -> {
                    TargetEta e = eta.get(t.getKey());
                    double p = predicted.getOrDefault(t.getKey(), Double.NaN);
                    running.add(e != null && Double.isFinite(e.remainingSec()) ? e.remainingSec() : p * slowdown);
                }
                case WAITING, HELD -> waiting.add(predicted.getOrDefault(t.getKey(), Double.NaN) * slowdown);
                default -> {}
            }
        }
        double stitch = JobEta.makespan(
                running.stream().mapToDouble(Double::doubleValue).toArray(),
                waiting.stream().mapToDouble(Double::doubleValue).toArray(),
                slots);
        if (solve != null) {
            stitch += solve.remainingSec();
        }
        return stitch;
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
        return derived.values().stream().anyMatch(s -> s != State.DONE && s != State.FAILED);
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
        String line = parts.isEmpty() ? phase : phase + " (" + String.join("; ", parts) + ")";
        return note.isEmpty() ? line : line + " - " + note;
    }

    private String group(String kind, Map<String, State> states) {
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
        appendNames(sb, "waiting for " + heldUntil, states, State.HELD);
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
