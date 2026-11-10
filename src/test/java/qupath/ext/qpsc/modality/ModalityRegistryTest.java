package qupath.ext.qpsc.modality;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for prefix lookup in {@link ModalityRegistry}.
 *
 * <p>Two of the built-in prefixes overlap: "bf" (brightfield) is the start of "bf_if"
 * (brightfield + immunofluorescence). The registry must pick the longer one, and it must do
 * so regardless of the map's iteration order, which is unspecified.
 */
class ModalityRegistryTest {

    @Test
    @DisplayName("A BF+IF name resolves to the BF+IF handler, not the brightfield one")
    void longestPrefixWins() {
        for (String name : List.of("bf_if", "BF_IF", "bf_if_20x", "BF_IF_10x")) {
            ModalityHandler h = ModalityRegistry.getHandler(name);
            assertInstanceOf(BfIfModalityHandler.class, h, "'" + name + "' did not resolve to the BF+IF handler");
        }
    }

    @Test
    @DisplayName("Plain brightfield names still resolve to the brightfield handler")
    void shorterPrefixStillMatchesItsOwnNames() {
        for (String name : List.of("bf", "BF_10x", "bf_20x", "brightfield", "Brightfield_40x")) {
            ModalityHandler h = ModalityRegistry.getHandler(name);
            assertInstanceOf(
                    BrightfieldModalityHandler.class, h, "'" + name + "' did not resolve to the brightfield handler");
        }
    }

    @Test
    @DisplayName("Unknown, empty and null names fall back to the no-op handler")
    void unmatchedNamesGetNoOp() {
        assertInstanceOf(NoOpModalityHandler.class, ModalityRegistry.getHandler("unknown_modality"));
        assertInstanceOf(NoOpModalityHandler.class, ModalityRegistry.getHandler(""));
        assertInstanceOf(NoOpModalityHandler.class, ModalityRegistry.getHandler(null));
    }

    @Test
    @DisplayName("A BF+IF name still resolves to the BF+IF handler in Turkish")
    void longestPrefixWinsInTurkish() {
        // tr-TR lowers the I of "BF_IF_20x" to a dotless i, so the name matched "bf" instead.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for (String name : List.of("BF_IF", "BF_IF_10x", "BF_IF_60x")) {
                assertInstanceOf(
                        BfIfModalityHandler.class,
                        ModalityRegistry.getHandler(name),
                        "'" + name + "' did not resolve to the BF+IF handler");
            }
        } finally {
            Locale.setDefault(previous);
        }
    }
}
