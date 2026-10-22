package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.PropertyRef;
import qupath.ext.qpsc.modality.PropertyWrite;

/** The per-channel background summary says nothing about intensity for a channel without one. */
class ChannelBackgroundSummaryTest {

    // An LC-PolScope state: no intensity control.
    private static final Channel STATE1 = new Channel("State1", "State1", 300.0, List.of(), List.of());
    // A fluorescence channel whose LED intensity is set to 40.
    private static final Channel DAPI = new Channel(
            "DAPI",
            "DAPI",
            50.0,
            List.of(),
            List.of(new PropertyWrite("LED", "Intensity-385nm", "40")),
            new PropertyRef("LED", "Intensity-385nm"),
            0);

    @Test
    void aChannelWithoutAnIntensityControlHasNone() {
        assertNull(BackgroundCollectionWorkflow.channelIntensity(STATE1, Map.of()));
        assertEquals(40.0, BackgroundCollectionWorkflow.channelIntensity(DAPI, Map.of()));
        assertEquals(55.0, BackgroundCollectionWorkflow.channelIntensity(DAPI, Map.of("DAPI", 55.0)));
    }

    @Test
    void theSummaryOmitsIntensityForLcStates() {
        // 2026-10-22: every LC-PolScope background reported "intensity 0" for all five states.
        String text = BackgroundCollectionWorkflow.buildChannelBackgroundSummary(
                "lcpolscope_20x", null, List.of(STATE1, DAPI), Map.of("State1", 300.0), Map.of());
        assertTrue(text.contains("State1: 300 ms\n") || text.endsWith("State1: 300 ms"), text);
        assertFalse(text.contains("intensity 0"), text);
        assertTrue(text.contains("DAPI: 50 ms, intensity 40"), text);
    }
}
