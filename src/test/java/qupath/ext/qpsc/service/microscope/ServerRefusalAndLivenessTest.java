package qupath.ext.qpsc.service.microscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The client's side of the 2026-10-12 failure: a 318 s autofocus start declared the server dead
 * at 300 s, the abandoned acquisition was never cancelled, the next ACQUIRE was refused with
 * BUSY, and the refusal was handled as a broken socket and reported as a user cancellation.
 *
 * <p>Each test pins one link of that chain so it cannot quietly come back.
 */
class ServerRefusalAndLivenessTest {

    private static final Path CLIENT =
            Path.of("src/main/java/qupath/ext/qpsc/service/microscope/MicroscopeSocketClient.java");
    private static final Path MANAGER =
            Path.of("src/main/java/qupath/ext/qpsc/controller/workflow/AcquisitionManager.java");
    private static final Path WORKFLOW =
            Path.of("src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java");
    private static final Path CONTROLLER =
            Path.of("src/main/java/qupath/ext/qpsc/controller/MicroscopeController.java");

    private static String method(Path file, String signatureFragment) throws Exception {
        String src = Files.readString(file);
        int at = src.indexOf(signatureFragment);
        assertThat(at).as("find " + signatureFragment).isGreaterThan(0);
        return src.substring(at);
    }

    // ---- a refusal is an answer, not a fault ------------------------------------------------

    @Test
    void theRefusalCarriesTheServersOwnWords() {
        AcquisitionRefusedException e = new AcquisitionRefusedException("BUSY:ACQUIRING");
        assertThat(e.serverReply()).isEqualTo("BUSY:ACQUIRING");
        assertThat(e.getMessage()).contains("already running an acquisition").contains("BUSY:ACQUIRING");
    }

    @Test
    void busyIsThrownAsARefusalNotAGenericIoError() throws Exception {
        String body = method(CLIENT, "public void startAcquisition(AcquisitionCommandBuilder builder)");
        assertThat(body).contains("if (ackStr.startsWith(\"BUSY\"))");
        assertThat(body).contains("throw new AcquisitionRefusedException(ackStr)");
    }

    @Test
    void aRefusalNeverReachesTheReconnectMachinery() throws Exception {
        // handleIOException counts a connection error and, after enough of them, drops the
        // socket and reconnects. Doing that for a healthy socket is what produced the 15 s
        // BLOCKED outage. The refusal must be rethrown by a catch that comes FIRST.
        String body = method(CLIENT, "public void startAcquisition(AcquisitionCommandBuilder builder)");
        int refusedCatch = body.indexOf("catch (AcquisitionRefusedException refused)");
        int genericCatch = body.indexOf("catch (IOException | InterruptedException e)");
        assertThat(refusedCatch).isGreaterThan(0);
        assertThat(refusedCatch).isLessThan(genericCatch);
        String between = body.substring(refusedCatch, genericCatch);
        // The call, not the word: the comment in that block names the method it avoids.
        assertThat(between).contains("throw refused;").doesNotContain("handleIOException(");
    }

    // ---- liveness is the reply, not the file count -----------------------------------------

    @Test
    void aStatusReplyResetsTheLivenessTimer() throws Exception {
        // Two 100-step autofocus sweeps in a row take ~318 s with zero files saved. The
        // server answered every poll throughout; it was not dead.
        String body = method(CLIENT, "AcquisitionState currentState = getAcquisitionStatus();");
        int reset = body.indexOf("lastProgressUpdateTime.set(System.currentTimeMillis());");
        int manualFocus = body.indexOf("Check for manual focus request");
        assertThat(reset).isGreaterThan(0);
        assertThat(reset).isLessThan(manualFocus);
    }

    @Test
    void aTimeoutCancelsInsteadOfWalkingAway() throws Exception {
        // The thread left behind kept the socket RUNNING, parked at a manual-focus prompt
        // nobody answered, and refused every later ACQUIRE.
        String body = method(CLIENT, "Ceiling on time since the server last ANSWERED");
        String block = body.substring(0, body.indexOf("// Log state changes"));
        assertThat(block).contains("cancelAbandonedAcquisition();");
        assertThat(block).contains("throw new IOException(");
        assertThat(block).doesNotContain("break;");
    }

    @Test
    void theCancelIsSentAndThenWaitedForBounded() throws Exception {
        String body = method(CLIENT, "private void cancelAbandonedAcquisition()");
        assertThat(body).contains("cancelAcquisition();");
        assertThat(body).contains("ABANDON_CANCEL_WAIT_MS");
        assertThat(MicroscopeSocketClient.ABANDON_CANCEL_WAIT_MS).isEqualTo(30_000L);
    }

    // ---- ask whether the server is idle before taking the stage ----------------------------

    @Test
    void theControllerTreatsRunningAndCancellingAsBusy() throws Exception {
        String body = method(CONTROLLER, "public boolean awaitServerIdle(long timeoutMs)");
        assertThat(body).contains("AcquisitionState.RUNNING").contains("AcquisitionState.CANCELLING");
        assertThat(body).contains("return false;");
    }

    @Test
    void theAcquisitionManagerAsksBeforeEveryAcquire() throws Exception {
        String body = method(MANAGER, "armStageFrameWatchdog(annotation);");
        int ask = body.indexOf("awaitServerIdle(SERVER_IDLE_WAIT_MS)");
        int send = body.indexOf("startAcquisition(config.commandBuilder())");
        assertThat(ask).isGreaterThan(0);
        assertThat(ask).isLessThan(send);
    }

    @Test
    void theAlignmentGateAsksBeforeItMovesTheStage() throws Exception {
        // STRMAFZ moved Z 30 um under a parked acquisition thread on 2026-10-12.
        // The NONE branch hands the idle wait and the check to a background thread together.
        assertThat(method(WORKFLOW, "case NONE:")).contains("checkAlignmentOffFxThread(state)");
        String body = method(WORKFLOW, "private CompletableFuture<WorkflowState> checkAlignmentOffFxThread(");
        int ask = body.indexOf("awaitServerIdle(AcquisitionManager.SERVER_IDLE_WAIT_MS)");
        int gate = body.indexOf("verifyAlignmentBeforeUnattendedAcquire(state)");
        assertThat(ask).isGreaterThan(0);
        assertThat(ask).isLessThan(gate);
        // And a still-busy server is a block, which handleError logs as such -- not a cancel.
        assertThat(body.substring(ask, gate)).contains("WorkflowBlockedException");
    }

    // ---- a failure is reported as a failure ------------------------------------------------

    @Test
    void aFailedAnnotationIsRememberedAndNotMisfiledAsACancel() throws Exception {
        String src = Files.readString(MANAGER);
        assertThat(src).contains("lastAnnotationFailure = e;");
        String tail = method(MANAGER, "Throwable failure = lastAnnotationFailure;");
        int asFailure = tail.indexOf("throw new RuntimeException(\"Acquisition failed: \"");
        int asCancel = tail.indexOf("throw new CancellationException(\"Acquisition cancelled by user\")");
        assertThat(asFailure).isGreaterThan(0);
        assertThat(asFailure).isLessThan(asCancel);
    }

    @Test
    void theFailureMemoryIsClearedPerRun() throws Exception {
        String src = Files.readString(MANAGER);
        assertThat(src).contains("lastAnnotationFailure = null;");
    }
}
