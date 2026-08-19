package qupath.ext.qpsc.modality.lcpolscope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityRegistry;
import qupath.ext.qpsc.modality.PresetRef;
import qupath.ext.qpsc.modality.PropertyWrite;

/**
 * Unit tests for the LC-PolScope modality handler.
 *
 * <p>These cover the two invariants that fail <em>silently</em> if broken -- unequal
 * exposure across the polarization states, and autofocusing on the extinction state --
 * plus the registry wiring that decides whether the modality is reachable at all.
 * None of them needs a microscope.
 */
class LCPolScopeModalityHandlerTest {

    private static Channel state(String id, double exposureMs) {
        return new Channel(
                id,
                id,
                exposureMs,
                List.of(new PresetRef("Channel", id)),
                List.of(new PropertyWrite("MeadowlarkLC", "Voltage (V) LC-A", "1.0")));
    }

    private static List<Channel> fiveStates(double... exposures) {
        return List.of(
                state("State0", exposures[0]),
                state("State1", exposures[1]),
                state("State2", exposures[2]),
                state("State3", exposures[3]),
                state("State4", exposures[4]));
    }

    @Test
    @DisplayName("Equal exposures are passed through untouched")
    void equalExposuresUnchanged() {
        List<Channel> in = fiveStates(50, 50, 50, 50, 50);
        List<Channel> out = LCPolScopeModalityHandler.enforceEqualExposure(in);
        assertSame(in, out, "no rewrite should happen when the config is already consistent");
    }

    @Test
    @DisplayName("Unequal exposures are normalized to the longest, not the first")
    void unequalExposuresNormalizedToMax() {
        // The longest is presumably the one tuned so the brightest state does not clip.
        // Shortening it would risk pushing extinction into the noise floor.
        List<Channel> out = LCPolScopeModalityHandler.enforceEqualExposure(fiveStates(50, 80, 50, 50, 50));
        assertEquals(5, out.size());
        for (Channel c : out) {
            assertEquals(80.0, c.defaultExposureMs(), 1e-9, "state " + c.id() + " not normalized");
        }
    }

    @Test
    @DisplayName("Normalizing preserves every other channel field")
    void normalizationPreservesHardwareState() {
        List<Channel> in = fiveStates(50, 80, 50, 50, 50);
        List<Channel> out = LCPolScopeModalityHandler.enforceEqualExposure(in);
        for (int i = 0; i < in.size(); i++) {
            assertEquals(in.get(i).id(), out.get(i).id());
            assertEquals(in.get(i).displayName(), out.get(i).displayName());
            assertEquals(in.get(i).presets(), out.get(i).presets(), "ConfigGroup presets must survive");
            assertEquals(in.get(i).properties(), out.get(i).properties(), "LC voltage writes must survive");
            assertEquals(in.get(i).settleMs(), out.get(i).settleMs(), 1e-9);
        }
    }

    @Test
    @DisplayName("Empty and null channel lists are handled")
    void emptyInputs() {
        assertTrue(LCPolScopeModalityHandler.enforceEqualExposure(List.of()).isEmpty());
        assertTrue(LCPolScopeModalityHandler.enforceEqualExposure(null).isEmpty());
    }

    @Test
    @DisplayName("Autofocus never defaults to the extinction state")
    void focusChannelIsNotExtinction() {
        String focus = LCPolScopeModalityHandler.defaultFocusChannelId(fiveStates(50, 50, 50, 50, 50));
        assertNotNull(focus);
        assertNotEquals(
                LCPolScopeModalityHandler.EXTINCTION_CHANNEL_ID,
                focus,
                "extinction is near-black; focus metrics collapse into noise on it");
        assertEquals("State1", focus);
    }

    @Test
    @DisplayName("Focus channel falls back gracefully when only extinction exists")
    void focusChannelDegenerateCases() {
        assertNull(LCPolScopeModalityHandler.defaultFocusChannelId(List.of()));
        assertNull(LCPolScopeModalityHandler.defaultFocusChannelId(null));
        assertEquals("State0", LCPolScopeModalityHandler.defaultFocusChannelId(List.of(state("State0", 50))));
    }

    @Test
    @DisplayName("Modality names resolve to this handler, not the no-op")
    void registryResolvesModalityNames() {
        // Before registration this name fell through to NoOpModalityHandler silently:
        // no channel picker, no five-state loop, and no error to notice.
        for (String name : List.of("lcpolscope", "LCPolScope", "lcpolscope_20x", "lcps_20x")) {
            ModalityHandler h = ModalityRegistry.getHandler(name);
            assertInstanceOf(
                    LCPolScopeModalityHandler.class, h, "'" + name + "' did not resolve to the LC-PolScope handler");
        }
    }

    @Test
    @DisplayName("Handler declares the traits the acquisition path depends on")
    void handlerTraits() {
        LCPolScopeModalityHandler h = new LCPolScopeModalityHandler();
        assertEquals(5, h.getDefaultAngleCount(), "five LC states per tile");
        assertEquals("off", h.getDefaultWbMode(), "WB would corrupt the radiometric scale");
        assertTrue(
                h.getRotationAngles("lcpolscope_20x", null, null, "off").join().isEmpty(),
                "no rotation stage: angles must stay empty so the command builder emits channels");
        assertEquals(
                qupath.lib.images.ImageData.ImageType.OTHER,
                h.getImageType().orElseThrow(),
                "a polarization stack is neither brightfield nor fluorescence");
    }
}
