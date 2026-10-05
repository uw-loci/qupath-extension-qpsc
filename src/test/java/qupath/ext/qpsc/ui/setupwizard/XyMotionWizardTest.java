package qupath.ext.qpsc.ui.setupwizard;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The wizard's XY motion profile: what it writes, and what it refuses to invent.
 *
 * <p>These values go straight to a device property, so they are an instruction to real
 * hardware. Two properties matter more than the plumbing: a rig nobody has characterised
 * gets no cap written at all, and a value that is not a number never reaches the stage.
 */
class XyMotionWizardTest {

    private static final Path STAGE_STEP = Path.of("src/main/java/qupath/ext/qpsc/ui/setupwizard/StageStep.java");
    private static final Path WRITER = Path.of("src/main/java/qupath/ext/qpsc/ui/setupwizard/ConfigFileWriter.java");

    @Test
    void theDefaultIsToWriteNothing() {
        // We cannot pick a safe speed for a stage we have never driven, so the default must
        // leave the adapter's own values in force rather than guess at a cap.
        WizardData data = new WizardData();
        assertThat(data.xyMaxSpeedValue).isEmpty();
        assertThat(data.xyAccelerationValue).isEmpty();
    }

    @Test
    void anEmptyProfileOmitsTheBlockEntirely() throws Exception {
        // A block with blank values would be worse than no block: resolve_profile would
        // find nothing to hold and the readback canary would have nothing to compare.
        String src = Files.readString(WRITER);
        int at = src.indexOf("String xySpeed = data.xyMaxSpeedValue");
        assertThat(at).isGreaterThan(0);
        String body = src.substring(at, at + 900);
        assertThat(body).contains("if (!xySpeed.isEmpty() || !xyAccel.isEmpty())");
        assertThat(body).contains("stage.put(\"xy_motion\", xyMotion)");
        // Each property is written only when actually supplied.
        assertThat(body).contains("if (!xySpeed.isEmpty())").contains("if (!xyAccel.isEmpty())");
    }

    @Test
    void aNonNumericValueIsRefusedBeforeItReachesTheStage() throws Exception {
        String src = Files.readString(STAGE_STEP);
        int at = src.indexOf("private String validateMotionValue(");
        assertThat(at).isGreaterThan(0);
        String body = src.substring(at, at + 1200);
        assertThat(body).contains("Double.parseDouble(text)");
        assertThat(body).contains("must be a number");
        // Blank stays legal: it is how an operator declines to set anything.
        assertThat(body).contains("if (text.isEmpty()) {");
        assertThat(body).contains("return null;");
        // Zero or negative is not a gentler setting, it is a nonsense one.
        assertThat(body).contains("value <= 0");
    }

    @Test
    void theRangeIsNotPolicedBecauseTheScaleBelongsToTheAdapter() throws Exception {
        // Prior takes 1-100 percent; other adapters take um/s or a fixed enum. A bound
        // imposed here would be a guess about the very hardware the wizard is describing.
        String src = Files.readString(STAGE_STEP);
        int at = src.indexOf("private String validateMotionValue(");
        String body = src.substring(at, at + 1200);
        assertThat(body).doesNotContain("> 100").doesNotContain("<= 100");
    }

    @Test
    void bothFieldsAreValidatedNotJustTheFirst() throws Exception {
        String src = Files.readString(STAGE_STEP);
        assertThat(src).contains("validateMotionValue(xySpeedField.getText(), \"MaxSpeed\")");
        assertThat(src).contains("validateMotionValue(xyAccelField.getText(), \"Acceleration\")");
    }

    @Test
    void theOperatorIsWarnedAboutWhatFastMovesCanDo() throws Exception {
        String src = Files.readString(STAGE_STEP);
        // The specific risk, not a generic caution: a stage can lose position while
        // reporting that it has not, which is why this cost a four-slide run.
        assertThat(src).contains("skip steps while still reporting the position it was");
        // And the two things the operator must actually do.
        assertThat(src).contains("TEST the result");
        assertThat(src).contains("manufacturer");
        // Plus a stated way out, so nobody feels obliged to put a number in.
        assertThat(src).contains("Leave both blank to change nothing");
    }

    @Test
    void thePromptSaysTheValuesAreRawDevicePropertyValues() throws Exception {
        // An operator who reads these as microns or mm/s will write a wrong instruction.
        String src = Files.readString(STAGE_STEP);
        assertThat(src).contains("Raw device-property values");
        assertThat(src).contains("1-100 percent");
    }
}
