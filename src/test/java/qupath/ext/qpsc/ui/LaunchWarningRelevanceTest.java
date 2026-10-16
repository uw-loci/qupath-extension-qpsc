package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Calibration warnings name only what the acquisition will use. The wizard used to roll up
 * every white-balance mode before the mode was chosen, and warned operators about modes they
 * were not using; the chosen mode is now checked after the acquisition dialog fixes it.
 */
class LaunchWarningRelevanceTest {

    private static final Path MAIN = Path.of("src/main/java/qupath/ext/qpsc");

    private static String method(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue(at >= 0, "find " + signature);
        int end = src.indexOf("\n    }\n", at);
        return src.substring(at, end);
    }

    @Test
    void theWizardDoesNotWarnAboutWhiteBalanceModesBeforeOneIsChosen() throws IOException {
        String wizard = Files.readString(MAIN.resolve("ui/AcquisitionWizardDialog.java"));
        String body = method(wizard, "private boolean confirmCalibrationStatus(boolean needsAlignment)");
        assertFalse(body.contains("CalibrationChecker.checkWhiteBalance("), "no white balance roll-up at launch");
        assertFalse(body.contains("CalibrationChecker.checkBackgroundCorrection("), "no background roll-up at launch");
        assertTrue(body.contains("if (needsAlignment)"), "alignment only where it is used");
        assertTrue(method(wizard, "private void onBoundedAcquisition()").contains("confirmCalibrationStatus(false)"));
        assertTrue(
                method(wizard, "private void onExistingImageAcquisition()").contains("confirmCalibrationStatus(true)"));
    }

    @Test
    void bothAcquisitionPathsCheckTheChosenModeOnceItIsKnown() throws IOException {
        String manager = Files.readString(MAIN.resolve("controller/workflow/AcquisitionManager.java"));
        assertTrue(manager.contains(
                "BackgroundIlluminationCheck.checkChosenWbModeAndWarn(modality, objective, detector, state.wbMode)"));
        String bounded = Files.readString(MAIN.resolve("controller/BoundedAcquisitionWorkflow.java"));
        assertTrue(bounded.contains("BackgroundIlluminationCheck.checkChosenWbModeAndWarn("));
        assertTrue(bounded.contains("result.wbMode())"));
    }

    @Test
    void theChosenModeCheckLooksAtThatModeAlone() throws IOException {
        String checker = Files.readString(MAIN.resolve("ui/CalibrationChecker.java"));
        String body = method(checker, "public static java.util.List<String> problemsForChosenWbMode(");
        assertTrue(body.contains("label.equals(mode.label())"), "filters the per-mode breakdown to the chosen mode");
    }
}
