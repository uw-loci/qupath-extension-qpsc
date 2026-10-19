package qupath.ext.qpsc.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.service.ObjectivePresetActuator.Decision;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager.ObjectiveMmPreset;

/** When QPSC changes Micro-Manager's objective preset, and when it must not. */
class ObjectivePresetActuatorTest {

    private static final ObjectiveMmPreset LABEL = new ObjectiveMmPreset("Objective Lens", "20x", false);
    private static final ObjectiveMmPreset TURRET = new ObjectiveMmPreset("Objective", "20X", true);

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void aLabellingDeviceIsAppliedAndATurretIsConfirmed() {
        JsonObject other = json("{\"group_exists\": true, \"preset_exists\": true, \"current\": \"4x\"}");
        assertEquals(Decision.APPLY, ObjectivePresetActuator.decide(LABEL, other));
        assertEquals(Decision.CONFIRM, ObjectivePresetActuator.decide(TURRET, other));
    }

    @Test
    void nothingHappensWhenMicroManagerIsAlreadyThere() {
        assertEquals(
                Decision.NOTHING,
                ObjectivePresetActuator.decide(
                        TURRET, json("{\"group_exists\": true, \"preset_exists\": true, \"current\": \"20X\"}")));
    }

    @Test
    void missingNamesAreAWarningNamingWhatExists() {
        JsonObject noGroup =
                json("{\"group_exists\": false, \"preset_exists\": false, \"groups\": [\"Objective\", \"Channel\"]}");
        assertEquals(Decision.WARN_MISSING, ObjectivePresetActuator.decide(LABEL, noGroup));
        String m = ObjectivePresetActuator.missingMessage("OBJ20", LABEL, noGroup);
        assertTrue(m.contains("no configuration group 'Objective Lens'") && m.contains("'Objective', 'Channel'"), m);

        JsonObject noPreset =
                json("{\"group_exists\": true, \"preset_exists\": false, \"presets\": [\"10x\", \"40x\"]}");
        assertEquals(Decision.WARN_MISSING, ObjectivePresetActuator.decide(LABEL, noPreset));
        assertTrue(
                ObjectivePresetActuator.missingMessage("OBJ20", LABEL, noPreset).contains("'10x', '40x'"));

        assertEquals(Decision.WARN_MISSING, ObjectivePresetActuator.decide(LABEL, null), "no answer");
        assertTrue(ObjectivePresetActuator.missingMessage("OBJ20", LABEL, null).contains("did not answer"));
    }
}
