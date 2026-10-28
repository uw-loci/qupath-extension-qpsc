package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.BackgroundProfileDefaults.Setup;

/**
 * The New Background Profile dialog started empty, and an id with a space was refused only after
 * the dialog had closed, losing what was typed. These are the values it now starts with and the
 * checks it runs while the operator types.
 */
class BackgroundProfileDefaultsTest {

    private static final Setup FULL =
            new Setup("quad_v", "Four slides (vertical)", "B", "Label toward you", true, "Aperio GT450");

    @Test
    void theSuggestionDescribesTheSetupOnTheStage() {
        assertEquals(
                "Four slides (vertical), label toward you, from Aperio GT450",
                BackgroundProfileDefaults.suggestName(FULL));
        assertEquals(
                "four_slides_vertical_label_toward_you_from_aperio_gt450",
                BackgroundProfileDefaults.suggestId(BackgroundProfileDefaults.suggestName(FULL), List.of()));
        String description = BackgroundProfileDefaults.suggestDescription(FULL);
        assertTrue(description.contains("Four slides (vertical)"), description);
        assertTrue(description.contains("slide label toward you (as the open image)"), description);
        assertEquals(
                Map.of("slide_source", "Aperio GT450", "medium", ""),
                BackgroundProfileDefaults.suggestAttributes(FULL));
    }

    /**
     * 2026-10-28, LC-PolScope: placement A was suggested as "label left" while the Stage Map, and
     * the slide, had the label on the right. Which side A is depends on the scanner and the scope,
     * so the label side comes from the Stage Map's caption or is not named at all.
     */
    @Test
    void theLabelSideIsTheStageMapsNeverAssumed() {
        Setup known = new Setup("single_h", null, "A", "Label on your right", false, "Ocus40");
        assertEquals("single_h, label on your right, from Ocus40", BackgroundProfileDefaults.suggestName(known));
        assertTrue(BackgroundProfileDefaults.suggestDescription(known).contains("slide label on your right"));

        Setup unknown = new Setup("single_h", null, "A", null, false, null);
        assertEquals("single_h, placement A", BackgroundProfileDefaults.suggestName(unknown));
        for (String text : List.of(
                BackgroundProfileDefaults.suggestName(unknown),
                BackgroundProfileDefaults.suggestDescription(unknown))) {
            assertFalse(text.contains("left") || text.contains("right"), text);
        }
    }

    @Test
    void aDefaultPlacementSaysSoAndNothingKnownSuggestsNothing() {
        Setup scopeDefault = new Setup("single_h", null, "A", null, false, null);
        assertTrue(BackgroundProfileDefaults.suggestDescription(scopeDefault).contains("the scope's default"));
        assertEquals(
                "Slide placement A (the scope's default).",
                BackgroundProfileDefaults.suggestDescription(new Setup(null, null, "A", null, false, null)),
                "a sentence, capitalized");
        Setup nothing = new Setup(null, null, null, null, false, null);
        assertNull(BackgroundProfileDefaults.suggestName(nothing));
        assertEquals("profile", BackgroundProfileDefaults.suggestId(null, List.of()));
    }

    @Test
    void suggestedIdsAreValidAndUnique() {
        String id = BackgroundProfileDefaults.suggestId("Slide A / Ventana (2026)", List.of("slide_a_ventana_2026"));
        assertEquals("slide_a_ventana_2026_2", id);
        assertTrue(BackgroundProfiles.isValidId(id));
        // Folder names compare case-insensitively on Windows.
        assertEquals("carrier_2", BackgroundProfileDefaults.suggestId("carrier", List.of("Carrier")));
        String longId = BackgroundProfileDefaults.suggestId("x".repeat(100), List.of("x".repeat(64)));
        assertTrue(BackgroundProfiles.isValidId(longId), longId);
        assertTrue(BackgroundProfiles.isValidId(
                BackgroundProfileDefaults.slug("Lamina" + (char) 0xE9 + " m" + (char) 0xE9 + "dium")));
    }

    @Test
    void anUnusableIdIsExplainedBeforeTheDialogCloses() {
        assertNull(BackgroundProfileDefaults.idProblem("quad_v-B", List.of()));
        String spaces = BackgroundProfileDefaults.idProblem("quad v/B", List.of());
        assertTrue(spaces.contains("space") && spaces.contains("'/'"), spaces);
        assertTrue(spaces.contains("quad_v_b"), "offers a fix: " + spaces);
        assertTrue(BackgroundProfileDefaults.idProblem("_x", List.of()).contains("start with"));
        assertTrue(BackgroundProfileDefaults.idProblem("", List.of()).contains("Enter an id"));
        assertTrue(BackgroundProfileDefaults.idProblem("QUAD", List.of("quad")).contains("already exists"));
        // Every id the checker accepts is one the profile store accepts.
        for (String ok : List.of("a", "A-1_b", "9x")) {
            assertNull(BackgroundProfileDefaults.idProblem(ok, List.of()));
            assertTrue(BackgroundProfiles.isValidId(ok), ok);
        }
    }

    @Test
    void attributeLinesThatWillNotBeSavedAreNamed() {
        List<String> warnings =
                BackgroundProfileDefaults.attributeWarnings("medium: Permount\nPermount\n\nmedium: DPX");
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("line 2"));
        assertTrue(warnings.get(1).contains("given twice"));
        assertTrue(BackgroundProfileDefaults.attributeWarnings("medium: \nslide_source: x")
                .isEmpty());
    }
}
