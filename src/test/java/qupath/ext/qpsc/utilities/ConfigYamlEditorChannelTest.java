package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies {@link ConfigYamlEditor#replaceChannelListField} and
 * {@link ConfigYamlEditor#setNestedChildScalar}, the two primitives the LC calibration uses to
 * write its palette into a channel library shaped like {@code config_LCPolScope.yml}: compact
 * block-sequence channels, each with {@code mm_setup_presets} and {@code device_properties}, and a
 * commented {@code reconstruction} block beside them.
 */
public class ConfigYamlEditorChannelTest {

    public static final String CONFIG = "# file header must survive\n"
            + "modalities:\n"
            + "  lcpolscope:\n"
            + "    type: polarized\n"
            + "    reconstruction:\n"
            + "      # swing comment survives\n"
            + "      swing_waves: 0.03\n"
            + "      wavelength_nm: 549   # trailing comment\n"
            + "      state_order: [State0, State1, State2, State4, State3]\n"
            + "    channels:\n"
            + "    # channel-library comment survives\n"
            + "    - id: State0\n"
            + "      display_name: State0 (extinction)\n"
            + "      exposure_ms: 50\n"
            + "      mm_setup_presets:\n"
            + "      - group: Channel\n"
            + "        preset: State0\n"
            + "      device_properties:\n"
            + "      - device: MeadowlarkLC\n"
            + "        property: Voltage (V) LC-A\n"
            + "        value: 6.4280\n"
            + "      - device: MeadowlarkLC\n"
            + "        property: Voltage (V) LC-B\n"
            + "        value: 4.0370\n"
            + "      # settle comment survives\n"
            + "      settle_ms: 50\n"
            + "    - id: State1\n"
            + "      display_name: State1\n"
            + "      exposure_ms: 50\n"
            + "      mm_setup_presets:\n"
            + "      - group: Channel\n"
            + "        preset: State1\n"
            + "      device_properties:\n"
            + "      - device: MeadowlarkLC\n"
            + "        property: Voltage (V) LC-A\n"
            + "        value: 1.1400\n"
            + "      - device: MeadowlarkLC\n"
            + "        property: Voltage (V) LC-B\n"
            + "        value: 1.7000\n"
            + "      settle_ms: 50\n"
            + "  brightfield:\n"
            + "    type: brightfield\n"
            + "stage:\n"
            + "  stage_id: S1\n";

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(Path p) throws Exception {
        try (var in = Files.newInputStream(p)) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> channel(Path p, String id) throws Exception {
        Map<String, Object> mods = (Map<String, Object>) parse(p).get("modalities");
        Map<String, Object> lcps = (Map<String, Object>) mods.get("lcpolscope");
        for (Object c : (List<Object>) lcps.get("channels")) {
            Map<String, Object> cm = (Map<String, Object>) c;
            if (id.equals(cm.get("id"))) return cm;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> reconstruction(Path p) throws Exception {
        Map<String, Object> mods = (Map<String, Object>) parse(p).get("modalities");
        return (Map<String, Object>) ((Map<String, Object>) mods.get("lcpolscope")).get("reconstruction");
    }

    static Map<String, Object> write(String property, double valueWaves) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device", "MeadowlarkLC");
        m.put("property", property);
        m.put("value", valueWaves);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacesDevicePropertiesAndLeavesTheRestAlone(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, CONFIG);

        ConfigYamlEditor.Result r = ConfigYamlEditor.replaceChannelListField(
                f,
                "lcpolscope",
                "State0",
                "device_properties",
                List.of(write("Retardance LC-A [in waves]", 0.2534), write("Retardance LC-B [in waves]", 0.4412)),
                List.of("LC calibration test"));
        assertTrue(r.changed);

        List<Map<String, Object>> props =
                (List<Map<String, Object>>) channel(f, "State0").get("device_properties");
        assertEquals(2, props.size());
        assertEquals("Retardance LC-A [in waves]", props.get(0).get("property"));
        assertEquals(0.2534, ((Number) props.get(0).get("value")).doubleValue(), 1e-12);
        assertEquals("Retardance LC-B [in waves]", props.get(1).get("property"));
        // Untouched: the channel's other fields, the other channel, the presets.
        assertEquals(50, channel(f, "State0").get("settle_ms"));
        assertNotNull(channel(f, "State0").get("mm_setup_presets"));
        List<Map<String, Object>> other =
                (List<Map<String, Object>>) channel(f, "State1").get("device_properties");
        assertEquals("Voltage (V) LC-A", other.get(0).get("property"));
        String text = Files.readString(f);
        assertTrue(text.contains("# file header must survive"));
        assertTrue(text.contains("# channel-library comment survives"));
        assertTrue(text.contains("# settle comment survives"));
        assertTrue(text.contains(ConfigYamlEditor.EDITOR_COMMENT_TAG + " LC calibration test"));
    }

    @Test
    void secondCallWithSameContentIsANoOpAndDoesNotStackComments(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, CONFIG);
        List<Map<String, Object>> items = List.of(write("Retardance LC-A [in waves]", 0.25));
        ConfigYamlEditor.replaceChannelListField(f, "lcpolscope", "State0", "device_properties", items, List.of("one"));
        ConfigYamlEditor.Result again = ConfigYamlEditor.replaceChannelListField(
                f, "lcpolscope", "State0", "device_properties", items, List.of("one"));
        assertFalse(again.changed);
        // A rewrite with a different comment replaces our own comment rather than adding to it.
        ConfigYamlEditor.replaceChannelListField(f, "lcpolscope", "State0", "device_properties", items, List.of("two"));
        String text = Files.readString(f);
        assertEquals(
                1, text.split(java.util.regex.Pattern.quote(ConfigYamlEditor.EDITOR_COMMENT_TAG), -1).length - 1, text);
        assertTrue(text.contains("two"));
    }

    @Test
    void nullItemsRemovesTheField(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, CONFIG);
        ConfigYamlEditor.Result r =
                ConfigYamlEditor.replaceChannelListField(f, "lcpolscope", "State1", "mm_setup_presets", null, null);
        assertTrue(r.changed);
        assertNull(channel(f, "State1").get("mm_setup_presets"));
        assertNotNull(channel(f, "State1").get("device_properties"), "the following field is kept");
        assertEquals(50, channel(f, "State1").get("settle_ms"));
        assertNotNull(channel(f, "State0").get("mm_setup_presets"), "only the named channel changes");
        ConfigYamlEditor.Result again =
                ConfigYamlEditor.replaceChannelListField(f, "lcpolscope", "State1", "mm_setup_presets", null, null);
        assertFalse(again.changed);
    }

    @Test
    void addsTheFieldWhenTheChannelLacksIt(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, CONFIG);
        ConfigYamlEditor.replaceChannelListField(f, "lcpolscope", "State1", "mm_setup_presets", null, null);
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("group", "Channel");
        preset.put("preset", "State1");
        ConfigYamlEditor.Result r = ConfigYamlEditor.replaceChannelListField(
                f, "lcpolscope", "State1", "mm_setup_presets", List.of(preset), null);
        assertTrue(r.changed);
        assertNotNull(channel(f, "State1").get("mm_setup_presets"));
        assertEquals(50, channel(f, "State1").get("settle_ms"));
    }

    @Test
    void unknownChannelIsReportedNotWritten(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, CONFIG);
        ConfigYamlEditor.Result r = ConfigYamlEditor.replaceChannelListField(
                f, "lcpolscope", "State9", "device_properties", List.of(write("x", 1)), null);
        assertFalse(r.changed);
        assertTrue(r.message.contains("State9"));
        assertEquals(CONFIG, Files.readString(f));
    }

    @Test
    void nestedScalarUpdatesAddsAndIsIdempotent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(f, CONFIG);
        String[] block = {"modalities", "lcpolscope", "reconstruction"};

        ConfigYamlEditor.Result r = ConfigYamlEditor.setNestedChildScalar(
                f, block, "state_order", "[State0, State1, State2, State3, State4]");
        assertTrue(r.changed);
        assertEquals(
                List.of("State0", "State1", "State2", "State3", "State4"),
                reconstruction(f).get("state_order"));
        assertFalse(ConfigYamlEditor.setNestedChildScalar(
                        f, block, "state_order", "[State0, State1, State2, State3, State4]")
                .changed);

        // A quoted timestamp stays a string rather than becoming a YAML date.
        ConfigYamlEditor.setNestedChildScalar(f, block, "lc_calibrated_at", "'2026-10-12T14:03:00'");
        assertEquals("2026-10-12T14:03:00", reconstruction(f).get("lc_calibrated_at"));
        assertFalse(
                ConfigYamlEditor.setNestedChildScalar(f, block, "lc_calibrated_at", "'2026-10-12T14:03:00'").changed);
        ConfigYamlEditor.setNestedChildScalar(f, block, "lc_extinction_ratio", "267.3");
        assertEquals(267.3, ((Number) reconstruction(f).get("lc_extinction_ratio")).doubleValue(), 1e-9);

        // Neighbours and comments survive; a missing block is reported, not created.
        assertEquals(0.03, ((Number) reconstruction(f).get("swing_waves")).doubleValue(), 1e-12);
        assertEquals(549, reconstruction(f).get("wavelength_nm"));
        assertTrue(Files.readString(f).contains("# swing comment survives"));
        ConfigYamlEditor.Result missing =
                ConfigYamlEditor.setNestedChildScalar(f, new String[] {"modalities", "nope", "x"}, "a", "b");
        assertFalse(missing.changed);
    }
    /**
     * The real configs end the reconstruction block with a nested
     * {@code estimated_background} mapping. A field the calibration's Apply adds must land back at
     * the reconstruction level, not inside that mapping, and the mapping must survive intact.
     */
    @Test
    void nestedScalarAddedAfterANestedMappingStaysAtTheBlockLevel(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.yml");
        Files.writeString(
                f,
                CONFIG.replace(
                        "      state_order: [State0, State1, State2, State4, State3]\n",
                        "      state_order: [State0, State1, State2, State4, State3]\n"
                                + "      estimated_background:\n"
                                + "        enabled: false\n"
                                + "        order: 2\n"));
        String[] block = {"modalities", "lcpolscope", "reconstruction"};

        assertTrue(ConfigYamlEditor.setNestedChildScalar(f, block, "lc_extinction_ratio", "267.3").changed);

        Map<String, Object> recon = reconstruction(f);
        assertEquals(267.3, ((Number) recon.get("lc_extinction_ratio")).doubleValue(), 1e-9);
        @SuppressWarnings("unchecked")
        Map<String, Object> estimated = (Map<String, Object>) recon.get("estimated_background");
        assertEquals(Map.of("enabled", false, "order", 2), estimated);
    }
}
