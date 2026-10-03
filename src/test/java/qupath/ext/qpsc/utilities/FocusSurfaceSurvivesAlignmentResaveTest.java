package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A focus surface is written into the alignment JSON after the alignment itself, by a
 * separate call. {@code saveSlideAlignment} rebuilds that document from its own map, so
 * without an explicit carry-over the next alignment re-save deletes the surface.
 *
 * <p>This is the same failure the Autofocus Editor had: a writer that rebuilds a file
 * from a fixed set of keys silently drops whatever it does not know about. It cost
 * {@code channel_reduction: green} there. Here it would cost a measured surface, and the
 * only symptom would be that approach hints quietly stopped working.
 *
 * <p>Asserted against the source because exercising the save needs a real QuPath
 * Project, and what matters is structural: the carry-over exists, and it is not
 * conditional on anything.
 */
class FocusSurfaceSurvivesAlignmentResaveTest {

    private static final Path SOURCE = Path.of("src/main/java/qupath/ext/qpsc/utilities/AffineTransformManager.java");

    @Test
    void theAlignmentSaveCarriesTheFocusSurfaceForward() throws Exception {
        String src = Files.readString(SOURCE);
        assertThat(src)
                .as("saveSlideAlignment must copy focusSurface out of the existing JSON")
                .contains("alignmentData.put(\"focusSurface\", existingData.get(\"focusSurface\"))");
    }

    @Test
    void theCarryOverIsNotConditionalOnTheMacroImage() throws Exception {
        // The macroImageRaw carry-over next to it runs only in the no-new-macro-image
        // branch. Copying that shape would lose the surface on exactly the saves that
        // DO bring a new macro image.
        String src = Files.readString(SOURCE);
        int at = src.indexOf("Carry over the measured focus surface");
        assertThat(at).as("the explaining comment is the anchor for this test").isGreaterThan(0);
        String block = src.substring(at, src.indexOf("// Mark that the saved macro image", at));
        assertThat(block).contains("if (alignmentFile.exists())");
        assertThat(block).as("must not be gated on processedMacroImage").doesNotContain("processedMacroImage");
    }

    @Test
    void everyReaderResolvesTheAlignmentFileTheSameWay() throws Exception {
        // Three readers used to carry their own copy of the scoped-then-legacy lookup.
        // A reader that resolved a different file would answer about a different slide.
        String src = Files.readString(SOURCE);
        int resolvers = src.split("private static File resolveAlignmentFile\\(", -1).length - 1;
        assertThat(resolvers).isEqualTo(1);
        for (String reader : new String[] {"loadSlideAlignmentInsert", "loadSlideFocusZ", "loadSlideFocusSurface"}) {
            int at = src.indexOf("public static " + (reader.contains("Insert") ? "String " : "") + reader);
            at = at >= 0 ? at : src.indexOf(reader + "(Project<BufferedImage> project");
            assertThat(at).as(reader + " exists").isGreaterThan(0);
            String body = src.substring(at, Math.min(src.length(), at + 600));
            assertThat(body).as(reader + " uses the shared resolver").contains("resolveAlignmentFile(");
        }
    }
}
