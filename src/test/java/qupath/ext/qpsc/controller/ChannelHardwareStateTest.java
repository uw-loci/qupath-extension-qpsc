package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.controller.MicroscopeController.ChannelHardwareState;

/**
 * The Camera tab decides what to display from this state, so the property that matters is
 * that only a genuinely-known channel is ever presented as active. "Nothing applied" and
 * "someone else changed the light path" must both read as "do not name a channel".
 */
class ChannelHardwareStateTest {

    @Test
    void anAppliedChannelIsTheOnlyCaseThatNamesAChannel() {
        assertTrue(ChannelHardwareState.active("DAPI").hasChannel());
        assertEquals("DAPI", ChannelHardwareState.active("DAPI").channelId());
    }

    @Test
    void illuminationOffNamesNoChannelButIsStillKnown() {
        ChannelHardwareState none = ChannelHardwareState.none();
        assertFalse(none.hasChannel());
        assertTrue(none.known());
        assertNull(none.channelId());
    }

    @Test
    void unknownNamesNoChannelAndIsNotKnown() {
        // After an acquisition the hardware is on its last acquired channel, which the UI
        // has no way to name -- it must not keep showing the operator's preview channel.
        ChannelHardwareState unknown = ChannelHardwareState.unknown();
        assertFalse(unknown.hasChannel());
        assertFalse(unknown.known());
    }

    @Test
    void aStateWithAChannelButNotKnownNeverNamesIt() {
        // Defensive: the flag wins over the id, so a half-built state cannot leak a claim.
        assertFalse(new ChannelHardwareState("DAPI", false).hasChannel());
    }
}
