package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.ExistingImageAcquisitionConfig;
import qupath.ext.qpsc.ui.ExistingImageAcquisitionController.RefinementChoice;

/**
 * Unit tests for ExistingImageAcquisitionController.
 *
 * <p>These tests focus on the logic and data structures of the controller,
 * not the JavaFX UI components which require the Application thread.
 */
class ExistingImageAcquisitionControllerTest {

    // ==================== RefinementChoice Tests ====================

    @Test
    @DisplayName("RefinementChoice.NONE has correct display name")
    void testRefinementChoiceNoneDisplayName() {
        assertEquals("Proceed without refinement", RefinementChoice.NONE.getDisplayName());
    }

    @Test
    @DisplayName("RefinementChoice.SINGLE_TILE has correct display name")
    void testRefinementChoiceSingleTileDisplayName() {
        assertEquals("Single-tile refinement", RefinementChoice.SINGLE_TILE.getDisplayName());
    }

    @Test
    @DisplayName("RefinementChoice.FULL_MANUAL has correct display name")
    void testRefinementChoiceFullManualDisplayName() {
        assertEquals("Full manual alignment", RefinementChoice.FULL_MANUAL.getDisplayName());
    }

    @Test
    @DisplayName("All RefinementChoice values are present")
    void testRefinementChoiceValuesCount() {
        assertEquals(4, RefinementChoice.values().length);
    }

    @Test
    @DisplayName("RefinementChoice.MULTI_TILE has correct display name")
    void testRefinementChoiceMultiTileDisplayName() {
        assertEquals("Multi-tile refinement", RefinementChoice.MULTI_TILE.getDisplayName());
    }

    // ==================== ExistingImageAcquisitionConfig Tests ====================

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores sample data correctly")
    void testConfigStoresSampleData() {
        File folder = new File("/test/projects");
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                folder,
                false,
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                RefinementChoice.NONE,
                null,
                Map.of(),
                null,
                null,
                false,
                false,
                null,
                null,
                java.util.Set.of(),
                java.util.List.of(),
                qupath.ext.qpsc.service.OutputFormat.OME_SINGLE,
                null);

        assertEquals("TestSample", config.sampleName());
        assertEquals(folder, config.projectsFolder());
        assertFalse(config.isExistingProject());
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores hardware data correctly")
    void testConfigStoresHardwareData() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                new File("/test"),
                true,
                "BF",
                "10x",
                "Basler",
                false,
                null,
                RefinementChoice.FULL_MANUAL,
                null,
                Map.of(),
                null,
                null,
                false,
                false,
                null,
                null,
                java.util.Set.of(),
                java.util.List.of(),
                qupath.ext.qpsc.service.OutputFormat.OME_SINGLE,
                null);

        assertEquals("BF", config.modality());
        assertEquals("10x", config.objective());
        assertEquals("Basler", config.detector());
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores alignment data correctly")
    void testConfigStoresAlignmentData() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                new File("/test"),
                false,
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                RefinementChoice.SINGLE_TILE,
                null,
                Map.of(),
                null,
                null,
                false,
                false,
                null,
                null,
                java.util.Set.of(),
                java.util.List.of(),
                qupath.ext.qpsc.service.OutputFormat.OME_SINGLE,
                null);

        assertTrue(config.useExistingAlignment());
        assertNull(config.selectedTransform());
        assertEquals(RefinementChoice.SINGLE_TILE, config.refinementChoice());
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores angle overrides correctly")
    void testConfigStoresAngleOverrides() {
        Map<String, Double> overrides = new HashMap<>();
        overrides.put("minus", 45.0);
        overrides.put("plus", 135.0);

        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                new File("/test"),
                false,
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                RefinementChoice.NONE,
                overrides,
                Map.of(),
                null,
                null,
                false,
                false,
                null,
                null,
                java.util.Set.of(),
                java.util.List.of(),
                qupath.ext.qpsc.service.OutputFormat.OME_SINGLE,
                null);

        assertNotNull(config.angleOverrides());
        assertEquals(2, config.angleOverrides().size());
        assertEquals(45.0, config.angleOverrides().get("minus"), 0.001);
        assertEquals(135.0, config.angleOverrides().get("plus"), 0.001);
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig with existing project variant")
    void testConfigExistingProjectVariant() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "ExistingSample",
                new File("/existing/project"),
                true, // existing project
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                RefinementChoice.NONE,
                null,
                Map.of(),
                null,
                null,
                false,
                false,
                null,
                null,
                java.util.Set.of(),
                java.util.List.of(),
                qupath.ext.qpsc.service.OutputFormat.OME_SINGLE,
                null);

        assertTrue(config.isExistingProject());
    }

    @Test
    @DisplayName("ExistingImageAcquisitionConfig with new project variant")
    void testConfigNewProjectVariant() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "NewSample",
                new File("/new/project"),
                false, // new project
                "BF",
                "10x",
                "Basler",
                false,
                null,
                RefinementChoice.FULL_MANUAL,
                null,
                Map.of(),
                null,
                null,
                false,
                false,
                null,
                null,
                java.util.Set.of(),
                java.util.List.of(),
                qupath.ext.qpsc.service.OutputFormat.OME_SINGLE,
                null);

        assertFalse(config.isExistingProject());
        assertFalse(config.useExistingAlignment());
    }

    // ==================== Manual Alignment Path Tests ====================

    @Test
    @DisplayName("Manual alignment path carries no saved transform")
    void testManualAlignmentHasNoTransform() {
        ExistingImageAcquisitionConfig manualConfig = new ExistingImageAcquisitionConfig(
                "TestSample",
                new File("/test"),
                false,
                "ppm",
                "20x",
                "JAI",
                false, // manual alignment
                null,
                RefinementChoice.FULL_MANUAL,
                null,
                Map.of(),
                null,
                null,
                false,
                false,
                null,
                null,
                java.util.Set.of(),
                java.util.List.of(),
                qupath.ext.qpsc.service.OutputFormat.OME_SINGLE,
                null);

        assertFalse(manualConfig.useExistingAlignment());
        assertNull(manualConfig.selectedTransform());
    }

    // ==================== White Balance Config Tests ====================

    @Test
    @DisplayName("ExistingImageAcquisitionConfig stores WB settings correctly")
    void testConfigStoresWbSettings() {
        ExistingImageAcquisitionConfig config = new ExistingImageAcquisitionConfig(
                "TestSample",
                new File("/test"),
                false,
                "ppm",
                "20x",
                "JAI",
                true,
                null,
                RefinementChoice.NONE,
                null,
                Map.of(),
                null,
                null,
                true,
                true,
                "per_angle",
                null,
                java.util.Set.of(),
                java.util.List.of(),
                qupath.ext.qpsc.service.OutputFormat.OME_SINGLE,
                null);

        assertTrue(config.enableWhiteBalance());
        assertTrue(config.perAngleWhiteBalance());
        assertEquals("per_angle", config.wbMode());
    }
}
