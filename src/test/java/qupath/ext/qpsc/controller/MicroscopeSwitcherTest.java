package qupath.ext.qpsc.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Switching microscopes without restarting QuPath: what must be true for it to be safe.
 *
 * <p>The order inside {@link MicroscopeSwitcher#switchTo} is the point -- refuse, confirm, close,
 * disconnect, validate-or-roll-back, reconnect, rebuild, reopen -- so most of these read the
 * source and check the order, the way the repo's other wiring tests do.
 */
class MicroscopeSwitcherTest {

    private static final Path MAIN = Path.of("src/main/java/qupath/ext/qpsc");
    private static final Path SWITCHER = MAIN.resolve("controller/MicroscopeSwitcher.java");
    private static final Path SETUP = MAIN.resolve("SetupScope.java");

    // ---- the pure decision --------------------------------------------------------------

    @Test
    void aDifferentExistingFileIsWorthOffering() {
        assertThat(MicroscopeSwitcher.shouldOffer("/cfg/config_PPM.yml", "/cfg/config_OWS3.yml", true))
                .isTrue();
    }

    @Test
    void theSameFileIsNotOfferedEvenWhenSpelledDifferently() {
        // Our own rollback writes the active path back into the preference; that must not
        // re-offer a switch to the microscope already in use.
        assertThat(MicroscopeSwitcher.shouldOffer("/cfg/config_PPM.yml", "/cfg/./config_PPM.yml", true))
                .isFalse();
    }

    @Test
    void theNotedFileIsTheActiveOneEvenAfterTheManagerReloads() {
        // The config manager reloads on its own when any caller reads a changed preference, so
        // the offer must compare against the file the extension was built against.
        MicroscopeSwitcher.noteBuiltAgainst("/cfg/config_LCPolScope.yml");
        try {
            assertThat(MicroscopeSwitcher.activePath()).isEqualTo("/cfg/config_LCPolScope.yml");
            assertThat(MicroscopeSwitcher.shouldOffer(
                            MicroscopeSwitcher.activePath(), "/cfg/config_LCPolScope_Hamamatsu.yml", true))
                    .isTrue();
        } finally {
            MicroscopeSwitcher.noteBuiltAgainst(null);
        }
    }

    @Test
    void aMissingOrBlankCandidateIsIgnored() {
        // The preferences field fires on every keystroke; half-typed paths do not exist yet.
        assertThat(MicroscopeSwitcher.shouldOffer("/cfg/config_PPM.yml", "/cfg/config_OW", false))
                .isFalse();
        assertThat(MicroscopeSwitcher.shouldOffer("/cfg/config_PPM.yml", "", true))
                .isFalse();
        assertThat(MicroscopeSwitcher.shouldOffer("/cfg/config_PPM.yml", null, true))
                .isFalse();
    }

    @Test
    void withNoActiveConfigAnyRealFileIsOffered() {
        assertThat(MicroscopeSwitcher.shouldOffer(null, "/cfg/config_PPM.yml", true))
                .isTrue();
    }

    // ---- the order of operations -------------------------------------------------------

    private static String switcher() throws IOException {
        String src = Files.readString(SWITCHER);
        return src.substring(src.indexOf("public static void switchTo("));
    }

    @Test
    void anAcquisitionRefusesTheSwitchBeforeAnythingIsAsked() throws IOException {
        String s = switcher();
        int local = s.indexOf("controller.isAcquisitionActive()");
        int server = s.indexOf("awaitServerIdle(0)");
        int ask = s.indexOf("confirmed(oldPath, newPath");
        assertThat(local).isGreaterThan(0).isLessThan(ask);
        assertThat(server).isGreaterThan(0).isLessThan(ask);
    }

    @Test
    void windowsCloseOnlyAfterTheOperatorAgreesAndBeforeTheServerIsTouched() throws IOException {
        String s = switcher();
        int ask = s.indexOf("confirmed(oldPath, newPath");
        int viewers = s.indexOf("LiveViewerWindow.hide();");
        int others = s.indexOf("QpscWindows.closeAll();");
        int disconnect = s.indexOf("controller.disconnect();");
        assertThat(ask).isLessThan(viewers);
        assertThat(viewers).isLessThan(others);
        assertThat(others).isLessThan(disconnect);
    }

    @Test
    void validationHappensBeforeReconnectAndFailureRollsThePreferenceBack() throws IOException {
        String s = switcher();
        int validate = s.indexOf("loadAndValidate(newPath)");
        int rollback = s.indexOf("QPPreferenceDialog.setMicroscopeConfigFileProperty(oldPath)");
        int reconnect = s.lastIndexOf("reconnectIf(wasConnected, controller);");
        assertThat(validate).isGreaterThan(0).isLessThan(rollback);
        assertThat(rollback).isLessThan(reconnect);
        // A YAML that fails to parse is treated as invalid, not as an escaping exception.
        String load = Files.readString(SWITCHER);
        int helper = load.indexOf("private static boolean loadAndValidate(");
        assertThat(load.substring(helper, helper + 600))
                .contains("catch (RuntimeException e)")
                .contains("return false;");
    }

    @Test
    void theMenuIsRebuiltEitherWayButViewersReopenOnlyOnSuccess() throws IOException {
        String s = switcher();
        int rebuild = s.indexOf("SetupScope.reloadForNewConfig();");
        int success = s.indexOf("if (err == null) {");
        int reopen = s.indexOf("LiveViewerWindow.show();");
        assertThat(rebuild).isGreaterThan(0).isLessThan(success);
        assertThat(success).isLessThan(reopen);
    }

    // ---- the hooks -----------------------------------------------------------------------

    @Test
    void thePreferenceChangeIsDebouncedIntoAnOfferNotASwitch() throws IOException {
        String s = Files.readString(SETUP);
        assertThat(s).contains("PauseTransition settle");
        assertThat(s).contains("MicroscopeSwitcher.offerSwitch(");
        assertThat(s).contains("microscopeConfigFileProperty().addListener(");
        assertThat(s).doesNotContain("addListener((obs, was, now) -> MicroscopeSwitcher.switchTo(");
    }

    @Test
    void theOfferRunsOutsideTheAnimationPulse() throws IOException {
        // PauseTransition.onFinished runs inside a pulse, where the confirmation dialog's
        // showAndWait throws; on the rig (2026-10-21) the offer died there on every change.
        String s = Files.readString(SETUP);
        assertThat(s)
                .contains("settle.setOnFinished(e -> Platform.runLater(")
                .doesNotContain("settle.setOnFinished(e -> MicroscopeSwitcher.offerSwitch(");
    }

    @Test
    void aSwitchThatDoesNotHappenPutsThePreferenceBack() throws IOException {
        // The preference is what every CONFIG sends and what controller calls load by path,
        // so it must never be left naming a file QPSC has not switched to.
        String s = switcher();
        int decline = s.indexOf("declined by the operator");
        assertThat(s.indexOf("restorePreference(oldPath);", decline)).isGreaterThan(decline);
        int caught = s.indexOf("} catch (RuntimeException e) {");
        assertThat(s.indexOf("restorePreference(activePath());", caught)).isGreaterThan(caught);
        String refuse = Files.readString(SWITCHER);
        int def = refuse.indexOf("private static void refuse(String oldPath, String why) {");
        assertThat(refuse.substring(def, def + 200)).contains("restorePreference(oldPath);");
    }

    @Test
    void theMenuRebuildReplacesRatherThanAppends() throws IOException {
        String s = Files.readString(SETUP);
        int def = s.indexOf("private void addMenuItem(QuPathGUI qupath) {");
        String head = s.substring(def, def + 700);
        assertThat(head).contains("extensionMenu.getItems().clear();");
        assertThat(s).contains("public static void reloadForNewConfig()");
        assertThat(s).contains("menu.switchMicroscope");
    }

    // ---- every window can be found -------------------------------------------------------

    @Test
    void everyStageTheExtensionOpensIsRegistered() throws IOException {
        // The two viewers are closed and reopened by the switch through their own hide/show,
        // so they are the one deliberate exception.
        Pattern creation = Pattern.compile("^\\s*(?:Stage\\s+)?[\\w.]+\\s*=\\s*new Stage\\(\\);\\s*$");
        List<String> unregistered = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString();
                if (name.equals("LiveViewerWindow.java") || name.equals("StageMapWindow.java")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    if (creation.matcher(lines.get(i)).matches()) {
                        String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
                        if (!next.contains("QpscWindows.track(")) {
                            unregistered.add(f.getFileName() + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        assertThat(unregistered).as("Stage created without QpscWindows.track").isEmpty();
    }

    @Test
    void theNonModalCameraControlDialogIsRegisteredToo() throws IOException {
        String s = Files.readString(MAIN.resolve("ui/CameraControlController.java"));
        assertThat(s).contains("QpscWindows.track(stage);");
    }
}
