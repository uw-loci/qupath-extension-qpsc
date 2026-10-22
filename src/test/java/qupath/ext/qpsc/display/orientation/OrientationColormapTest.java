package qupath.ext.qpsc.display.orientation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.display.orientation.OrientationColormap.Kind;
import qupath.ext.qpsc.display.orientation.OrientationColormap.PpmHue;

/**
 * The Java colour maps match the microscope server's (design doc section 6 reference values),
 * the renderer reads stored counts, and image pairing finds the other half of a result.
 */
class OrientationColormapTest {

    // PPM -7 deg calibration, 2026-10-16 (Negative_7_Oct2026.npz).
    private static final PpmHue NEG7 =
            PpmHue.fromCalibration("Negative_7_Oct2026.npz", -0.005541962395299577, 0.9860845130836212, 0.28);

    private static final double[] ANGLES = {0, 30, 45, 60, 90, 120, 135, 150};
    // From microscope_command_server orientation_rgb, magnitude = ceiling, no registration.
    private static final int[][] UNIFORM = {
        {243, 142, 127}, {205, 170, 69}, {165, 185, 88}, {113, 196, 130},
        {30, 195, 216}, {142, 170, 253}, {185, 155, 242}, {218, 144, 213}
    };
    private static final int[][] HSV = {
        {255, 0, 0}, {255, 255, 0}, {128, 255, 0}, {0, 255, 0},
        {0, 255, 255}, {0, 0, 255}, {128, 0, 255}, {255, 0, 255}
    };
    private static final int[][] PPM = {
        {101, 255, 0}, {255, 154, 0}, {255, 26, 0}, {255, 0, 101},
        {154, 0, 255}, {0, 101, 255}, {0, 229, 255}, {0, 255, 154}
    };

    private static void assertMatches(Kind kind, PpmHue ppm, int[][] want) {
        for (int i = 0; i < ANGLES.length; i++) {
            int rgb = OrientationColormap.rgb(kind, ppm, 1, 0.0, ANGLES[i], 1.0);
            int[] got = {(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff};
            for (int c = 0; c < 3; c++) {
                assertTrue(
                        Math.abs(got[c] - want[i][c]) <= 1,
                        kind + " at " + ANGLES[i] + " deg: got " + java.util.Arrays.toString(got));
            }
        }
    }

    @Test
    void mapsMatchTheServer() {
        assertMatches(Kind.UNIFORM, null, UNIFORM);
        assertMatches(Kind.HSV, null, HSV);
        assertMatches(Kind.PPM, NEG7, PPM);
    }

    @Test
    void brightnessScalesAndRegistrationRotates() {
        int half = OrientationColormap.rgb(Kind.HSV, null, 1, 0.0, 90.0, 0.5);
        assertEquals((0 << 16) | (128 << 8) | 128, half);
        int mirrored = OrientationColormap.rgb(Kind.UNIFORM, null, -1, 30.0, 20.0, 1.0);
        int plain = OrientationColormap.rgb(Kind.UNIFORM, null, 1, 0.0, 10.0, 1.0);
        assertEquals(plain, mirrored);
        assertEquals(0, OrientationColormap.rgb(Kind.UNIFORM, null, 1, 0.0, Double.NaN, 1.0));
    }

    @Test
    void aWrongCalibrationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PpmHue.fromCalibration("x", 0.001, 0, 0));
    }

    @Test
    void theRendererReadsStoredCounts() {
        // Band 0: orientation deg x 100; band 1: retardance nm x 100.
        WritableRaster raster =
                java.awt.image.Raster.createBandedRaster(java.awt.image.DataBuffer.TYPE_USHORT, 2, 1, 2, null);
        raster.setSample(0, 0, 0, 9000); // 90 deg
        raster.setSample(0, 0, 1, 1000); // 10 nm
        raster.setSample(1, 0, 0, 9000);
        raster.setSample(1, 0, 1, 4000); // 40 nm, above the ceiling
        BufferedImage input = new BufferedImage(
                qupath.lib.color.ColorModelFactory.createColorModel(
                        qupath.lib.images.servers.PixelType.UINT16, 2, false, 0xff0000, 0x00ff00),
                raster,
                false,
                null);
        var renderer = new OrientationColorRenderer(
                new OrientationColorRenderer.Settings(Kind.HSV, null, 20.0, false, 1, 0.0));
        BufferedImage out = renderer.applyTransforms(input, null);
        assertEquals(0x008080, out.getRGB(0, 0) & 0xffffff); // half brightness cyan
        assertEquals(0x00ffff, out.getRGB(1, 0) & 0xffffff); // clipped to full
        long before = renderer.getLastChangeTimestamp();
        renderer.setSettings(new OrientationColorRenderer.Settings(Kind.HSV, null, 20.0, true, 1, 0.0));
        assertTrue(renderer.getLastChangeTimestamp() > before);
        assertEquals(0x00ffff, renderer.applyTransforms(input, null).getRGB(0, 0) & 0xffffff);
    }

    @Test
    void pairingFindsTheOtherHalf() {
        List<String> names = List.of(
                "PolScopeTest_103446_46709_retardance.ome.tif",
                "PolScopeTest_103446_46709_orientation.ome.tif",
                "PolScopeTest_103446_46709_orientation_color.ome.tif",
                "PolScopeTest_103446_46709_retardance.ome.tif (flipped X)",
                "PolScopeTest_103446_46709_orientation.ome.tif (flipped X)");
        assertEquals(
                Optional.of("PolScopeTest_103446_46709_orientation.ome.tif"),
                OrientationPairing.partnerName("PolScopeTest_103446_46709_retardance.ome.tif", names));
        assertEquals(
                Optional.of("PolScopeTest_103446_46709_retardance.ome.tif (flipped X)"),
                OrientationPairing.partnerName("PolScopeTest_103446_46709_orientation.ome.tif (flipped X)", names));
        assertEquals(
                Optional.empty(),
                OrientationPairing.partnerName("PolScopeTest_103446_46709_orientation_color.ome.tif", names));
        assertEquals(Optional.empty(), OrientationPairing.roleOf("slide_HE.ome.tif"));
        // "Orientation" in the sample name: the last token decides.
        assertEquals(
                Optional.of(OrientationPairing.Role.RETARDANCE),
                OrientationPairing.roleOf("OrientationStudy_1_retardance.ome.tif"));
    }
}
