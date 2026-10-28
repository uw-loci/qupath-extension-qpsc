package qupath.ext.qpsc.service.autofocus;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The AFTRACE reply as the microscope server writes it (server/af_events.py): snake_case names,
 * fields absent on events that do not carry them, null for a non-finite value.
 */
class AutofocusTraceReplyTest {

    private static final String REPLY = "{\"seq\": 5, \"first\": 1, \"more\": false,"
            + " \"active\": {\"type\": \"start\", \"run\": 2, \"method\": \"drift_check\", \"seq\": 5, \"t\": 1.0},"
            + " \"events\": ["
            + "{\"type\": \"start\", \"run\": 1, \"method\": \"sweep\", \"context\": \"Tile 3 of 10\","
            + " \"tile\": 2, \"x\": 100.5, \"y\": -50.0, \"z0\": -300.0, \"search_range\": 30.0,"
            + " \"n_steps\": 9, \"score_metric\": \"brenner_gradient\", \"seq\": 1, \"t\": 1730148000.123},"
            + "{\"type\": \"sample\", \"run\": 1, \"z\": -315.0, \"score\": 182000.5, \"spread\": 5120.0,"
            + " \"seq\": 2, \"t\": 1730148001.0},"
            + "{\"type\": \"sample\", \"run\": 1, \"z\": -312.0, \"score\": null, \"seq\": 3, \"t\": 1730148002.0},"
            + "{\"type\": \"end\", \"run\": 1, \"method\": \"sweep\", \"status\": \"ok\", \"z\": -301.2,"
            + " \"elapsed_s\": 21.4, \"seq\": 4, \"t\": 1730148021.0}"
            + "]}";

    @Test
    void parsesEveryEventKindWithSnakeCaseFields() {
        AutofocusTraceReply reply = AutofocusTraceReply.parse(REPLY);
        assertEquals(5, reply.seq);
        assertEquals(1, reply.first);
        assertFalse(reply.more);
        assertEquals(4, reply.events.size());
        assertEquals("drift_check", reply.active.method);
        assertEquals(2, reply.active.run);

        AutofocusEvent start = reply.events.get(0);
        assertTrue(start.isStart());
        assertEquals("Tile 3 of 10", start.context);
        assertEquals(2, start.tile);
        assertEquals(100.5, start.x);
        assertEquals(-300.0, start.z0);
        assertEquals(30.0, start.searchRange);
        assertEquals(9, start.nSteps);
        assertEquals("brenner_gradient", start.scoreMetric);

        AutofocusEvent sample = reply.events.get(1);
        assertTrue(sample.isSample());
        assertEquals(-315.0, sample.z);
        assertEquals(182000.5, sample.score);
        assertEquals(5120.0, sample.spread);
        assertNull(reply.events.get(2).score, "a non-finite score arrives as null");
        assertNull(reply.events.get(2).spread, "a field the event does not carry stays null");

        AutofocusEvent end = reply.events.get(3);
        assertTrue(end.isEnd());
        assertEquals("ok", end.status);
        assertEquals(-301.2, end.z);
        assertEquals(21.4, end.elapsedS);
    }

    @Test
    void aReplyWithNothingRunningAndNoEventsIsEmptyNotNull() {
        AutofocusTraceReply reply = AutofocusTraceReply.parse(
                "{\"seq\": 0, \"first\": 1, \"more\": false," + " \"active\": null, \"events\": []}");
        assertNull(reply.active);
        assertTrue(reply.events.isEmpty());
        assertTrue(AutofocusTraceReply.parse("{\"seq\": 3}").events.isEmpty());
    }

    private static AutofocusEvent start(String context, String method, Double z0) {
        AutofocusEvent e = new AutofocusEvent();
        e.type = "start";
        e.context = context;
        e.method = method;
        e.z0 = z0;
        return e;
    }

    private static AutofocusEvent end(String status, Double z, Double elapsed, String reason) {
        AutofocusEvent e = new AutofocusEvent();
        e.type = "end";
        e.status = status;
        e.z = z;
        e.elapsedS = elapsed;
        e.reason = reason;
        return e;
    }

    @Test
    void outcomesReadAsSentences() {
        AutofocusEvent tile = start("Tile 3 of 10", "drift_check", -312.4);
        assertEquals(
                "Tile 3 of 10 (drift check): focus at Z -310.90 (+1.50 um), 21.4 s",
                AutofocusEvent.describeOutcome(tile, end("ok", -310.9, 21.4, null)));
        assertEquals(
                "Tile 3 of 10 (drift check): no focus found: peak at the edge, 40.0 s",
                AutofocusEvent.describeOutcome(tile, end("failed", null, 40.0, "peak at the edge")));
        assertEquals(
                "full search: cancelled, 3.0 s",
                AutofocusEvent.describeOutcome(start(null, "sweep", null), end("aborted", null, 3.0, null)));
        assertEquals(
                "streaming: not run: no calibration",
                AutofocusEvent.describeOutcome(null, endWithMethod("unavailable", "streaming", "no calibration")));
        assertEquals(
                "Initial focus (full search): stopped with an error: stage timeout, 1.2 s",
                AutofocusEvent.describeOutcome(
                        start("Initial focus", "sweep", -300.0), end("error", null, 1.2, "stage timeout")));
    }

    private static AutofocusEvent endWithMethod(String status, String method, String reason) {
        AutofocusEvent e = end(status, null, null, reason);
        e.method = method;
        return e;
    }
}
