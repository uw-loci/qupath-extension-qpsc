package qupath.ext.qpsc.service.autofocus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A search the server can no longer report on is ended for every display.
 *
 * <p>2026-11-11: after the server was closed and restarted mid-search, the Live Viewer kept
 * showing "Autofocusing..." with its button disabled and its focus trace frozen, because
 * the search's start had been seen and its end never would be.
 */
class AutofocusFeedInterruptedTest {

    private static AutofocusEvent event(String type, long seq) {
        AutofocusEvent e = new AutofocusEvent();
        e.type = type;
        e.seq = seq;
        e.run = 7;
        e.method = "sweep";
        return e;
    }

    @Test
    void anOpenSearchGetsAnInterruptedEnd() {
        List<AutofocusEvent> run = List.of(event("start", 40), event("sample", 41), event("sample", 42));

        AutofocusEvent end = AutofocusFeed.interruptedEnd(run, "the microscope server restarted");

        assertThat(end).isNotNull();
        assertThat(end.isEnd()).isTrue();
        assertThat(end.run).isEqualTo(7);
        assertThat(end.status).isEqualTo("interrupted");
        assertThat(AutofocusEvent.describeOutcome(run.get(0), end))
                .isEqualTo("full search: interrupted: the microscope server restarted");
    }

    @Test
    void aFinishedSearchIsLeftAlone() {
        List<AutofocusEvent> run = List.of(event("start", 40), event("sample", 41), event("end", 42));
        assertThat(AutofocusFeed.interruptedEnd(run, "x")).isNull();
        assertThat(AutofocusFeed.interruptedEnd(List.of(), "x")).isNull();
    }

    @Test
    void aRestartAndALostConnectionBothEndTheSearch() throws Exception {
        String src = Files.readString(Path.of("src/main/java/qupath/ext/qpsc/service/autofocus/AutofocusFeed.java"));
        assertThat(src).contains("endOpenSearch(\"the microscope server restarted\");");
        assertThat(src).contains("endOpenSearch(\"the connection to the microscope server was lost\");");
    }
}
