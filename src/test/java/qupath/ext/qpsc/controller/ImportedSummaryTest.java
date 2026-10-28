package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The completion toast names what was added, since nothing is opened any more. */
class ImportedSummaryTest {

    @Test
    void nothingAddedKeepsTheOldMessage() {
        assertEquals(
                "All acquisitions have completed successfully.",
                ExistingImageWorkflowV2.importedSummary(List.of(), "P"));
        assertEquals(
                "All acquisitions have completed successfully.", ExistingImageWorkflowV2.importedSummary(null, "P"));
    }

    @Test
    void oneImage() {
        String m = ExistingImageWorkflowV2.importedSummary(List.of("L004_ppm_20x_001.ome.tif"), "liver");
        assertEquals(
                "1 image added to project liver: L004_ppm_20x_001.ome.tif. Open them from the project browser.", m);
    }

    @Test
    void manyImagesAreTruncated() {
        String m = ExistingImageWorkflowV2.importedSummary(List.of("a", "b", "c", "d", "e"), null);
        assertEquals("5 images added to the project: a, b, c and 2 more. Open them from the project browser.", m);
    }
}
