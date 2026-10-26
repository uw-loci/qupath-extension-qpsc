package qupath.ext.qpsc.display.orientation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.display.orientation.OrientationColormap.Kind;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.RegionRequest;

class OrientationColorExportTest {

    private static final OrientationColorRenderer.Settings HSV_20 =
            new OrientationColorRenderer.Settings(Kind.HSV, null, 20.0, false, 1, 0.0);

    @Test
    void stemsReplaceTheRoleAndNameTheSettings() {
        assertEquals(
                "slide_orientation_colour_uniform_20nm",
                OrientationColorExport.outputStem(
                        "slide_orientation.ome.tif",
                        new OrientationColorRenderer.Settings(Kind.UNIFORM, null, 20.0, false, 1, 0.0)));
        assertEquals(
                "slide_orientation_colour_hsv_hueonly",
                OrientationColorExport.outputStem(
                        "slide_retardance.ome.zarr",
                        new OrientationColorRenderer.Settings(Kind.HSV, null, 20.0, true, 1, 0.0)));
        assertEquals(
                "other_orientation_colour_hsv_12.5nm",
                OrientationColorExport.outputStem(
                        "other.tif", new OrientationColorRenderer.Settings(Kind.HSV, null, 12.5, false, 1, 0.0)));
    }

    @Test
    void metadataRecordsTheSettings() {
        var m = OrientationColorExport.metadata(HSV_20, "a + b");
        assertEquals("hsv", m.get("orientation_colour.map"));
        assertEquals("retardance, full brightness at 20 nm", m.get("orientation_colour.brightness"));
        assertEquals("angle x 1 + 0 deg", m.get("orientation_colour.registration"));
        assertEquals("a + b", m.get("orientation_colour.source_images"));
    }

    @Test
    void theExportServerColoursThePair() throws Exception {
        ImageServer<BufferedImage> ori = uint16("orientation", 9000); // 90 deg
        ImageServer<BufferedImage> ret = uint16("retardance", 1000); // 10 nm
        try (var colour = new OrientationColorExportServer(new OrientationPairServer(ori, ret), HSV_20)) {
            assertTrue(colour.isRGB());
            assertEquals(3, colour.nChannels());
            BufferedImage out = colour.readRegion(RegionRequest.createInstance(colour.getPath(), 1, 0, 0, 8, 8));
            assertEquals(0x008080, out.getRGB(3, 3) & 0xffffff); // half-bright cyan
        }
    }

    // The OME-TIFF write itself needs Bio-Formats, which is not on the test classpath (it would
    // pull the OME tree into every build); it is checked in a running QuPath instead.

    private static ImageServer<BufferedImage> uint16(String name, int value) {
        WritableRaster raster = Raster.createBandedRaster(DataBuffer.TYPE_USHORT, 8, 8, 1, null);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                raster.setSample(x, y, 0, value);
            }
        }
        BufferedImage img = new BufferedImage(
                ColorModelFactory.createColorModel(PixelType.UINT16, 1, false, 0xffffff), raster, false, null);
        return new WrappedBufferedImageServer(name, img, List.of(ImageChannel.getInstance(name, 0xffffff)));
    }
}
