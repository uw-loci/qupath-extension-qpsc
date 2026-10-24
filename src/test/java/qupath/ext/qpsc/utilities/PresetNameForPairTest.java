package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.AffineTransform;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A new preset must never replace another (source, target) pair's preset by sharing its name. */
class PresetNameForPairTest {

    private static AffineTransformManager.TransformPreset preset(String name, String target, String source) {
        return new AffineTransformManager.TransformPreset(
                name, target, source, new AffineTransform(), "", null, 1.0, 0.0, null, null, source);
    }

    private static Map<String, AffineTransformManager.TransformPreset> existing(
            AffineTransformManager.TransformPreset... presets) {
        Map<String, AffineTransformManager.TransformPreset> map = new HashMap<>();
        for (var p : presets) {
            map.put(p.getName(), p);
        }
        return map;
    }

    @Test
    void freeNameIsKept() {
        assertEquals(
                "LCPolScope_Transform_20261021",
                AffineTransformManager.nameForPair(
                        "LCPolScope_Transform_20261021", "Ocus40", "LCPolScope", existing()));
    }

    @Test
    void samePairResavesUnderItsName() {
        var map = existing(preset("Ocus40_Transform_20261019", "LCPolScope", "Ocus40"));
        assertEquals(
                "Ocus40_Transform_20261019",
                AffineTransformManager.nameForPair("Ocus40_Transform_20261019", "Ocus40", "LCPolScope", map));
    }

    @Test
    void otherMicroscopesPresetIsNotReplaced() {
        // The 2026-10-21 case: the Hamamatsu alignment reused the Oryx preset's name.
        var map = existing(preset("Ocus40_Transform_20261019", "LCPolScope", "Ocus40"));
        assertEquals(
                "Ocus40_Transform_20261019_2",
                AffineTransformManager.nameForPair("Ocus40_Transform_20261019", "Ocus40", "LCPolScope_Hamamatsu", map));
    }

    @Test
    void otherScannersPresetIsNotReplacedAndSuffixesSkipTakenNames() {
        var map = existing(
                preset("Shared", "PPM", "Ocus40"),
                preset("Shared_2", "PPM", "ScanScope"),
                preset("Shared_3", "OWS3", "Ocus40"));
        assertEquals("Shared_4", AffineTransformManager.nameForPair("Shared", "Aperio", "PPM", map));
    }

    @Test
    void suggestedNameLinksSourceScannerToConfigFile() {
        LocalDate day = LocalDate.of(2026, 10, 24);
        assertEquals(
                "Ocus40_to_LCPolScope_Hamamatsu_20261024",
                AffineTransformManager.suggestPresetName(
                        "Ocus40",
                        "C:\\qpsc\\microscope_configurations\\config_LCPolScope_Hamamatsu.yml",
                        "LCPolScope_Hamamatsu",
                        day));
        assertEquals(
                "Ocus40_to_LCPolScope_20261024",
                AffineTransformManager.suggestPresetName("Ocus40", "/cfg/config_LCPolScope.yml", "LCPolScope", day));
        // No config file: the microscope name stands in.
        assertEquals("Ocus40_to_PPM_20261024", AffineTransformManager.suggestPresetName("Ocus40", null, "PPM", day));
    }
}
