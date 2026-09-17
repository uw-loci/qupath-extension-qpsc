package qupath.ext.qpsc.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dedicated focus channel reaches the server as CLI flags. These pin the contract the
 * Python side parses ({@code --af-channel}, {@code --af-channel-exposure},
 * {@code --af-channel-intensity}) and, above all, that nothing is emitted unless the
 * operator opted in -- an accidental flag would silently change every acquisition's
 * autofocus.
 */
class DedicatedFocusChannelFlagsTest {

    private static AcquisitionCommandBuilder base() {
        return AcquisitionCommandBuilder.builder()
                .yamlPath("config.yml")
                .projectsFolder("/projects")
                .sampleLabel("sample")
                .scanType("Fluorescence")
                .regionName("bounds");
    }

    @Test
    void noFlagsWhenTheOperatorHasNotOptedIn() {
        String msg = base().buildSocketMessage();
        assertFalse(msg.contains("--af-channel"), msg);
    }

    @Test
    void channelExposureAndIntensityAreAllEmitted() {
        String msg = base().dedicatedFocusChannel("DAPI", 20.0, 35.0).buildSocketMessage();
        List<String> args = List.of(msg.split("\\s+"));
        int i = args.indexOf("--af-channel");
        assertTrue(i >= 0, msg);
        assertEquals("DAPI", args.get(i + 1));
        assertEquals("20.0", args.get(args.indexOf("--af-channel-exposure") + 1));
        assertEquals("35.0", args.get(args.indexOf("--af-channel-intensity") + 1));
    }

    @Test
    void omittedExposureAndIntensityMeanTheChannelsOwnValues() {
        String msg = base().dedicatedFocusChannel("DAPI", null, null).buildSocketMessage();
        assertTrue(msg.contains("--af-channel DAPI"), msg);
        assertFalse(msg.contains("--af-channel-exposure"), msg);
        assertFalse(msg.contains("--af-channel-intensity"), msg);
    }

    @Test
    void zeroIntensityIsStillSentBecauseItIsALegalValue() {
        String msg = base().dedicatedFocusChannel("DAPI", 20.0, 0.0).buildSocketMessage();
        assertTrue(msg.contains("--af-channel-intensity 0.0"), msg);
    }

    @Test
    void anUnusableExposureIsDroppedRatherThanSentAsZero() {
        // A zero exposure would be rejected by the camera; let the channel default stand.
        String msg = base().dedicatedFocusChannel("DAPI", 0.0, null).buildSocketMessage();
        assertTrue(msg.contains("--af-channel DAPI"), msg);
        assertFalse(msg.contains("--af-channel-exposure"), msg);
    }

    @Test
    void theFocusChannelNeedNotBeOneOfTheAcquiredChannels() {
        // Focusing on a bright stain while imaging dim ones is the main use of the option,
        // so unlike --focus-channel this is not filtered against the acquired channel list.
        String msg = base().channelExposures(List.of(new qupath.ext.qpsc.modality.ChannelExposure("FITC", 300.0)))
                .dedicatedFocusChannel("DAPI", 20.0, null)
                .buildSocketMessage();
        assertTrue(msg.contains("--af-channel DAPI"), msg);
    }

    @Test
    void aBlankChannelIdEmitsNothing() {
        assertFalse(base().dedicatedFocusChannel("  ", 20.0, null)
                .buildSocketMessage()
                .contains("--af-channel"));
    }
}
