package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.File;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.modality.lcpolscope.LCPolScopeModalityHandler;
import qupath.lib.display.ImageDisplay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;

/**
 * A retardance map is imported with its display range stored in the image's own display
 * settings, so it opens on the physical 0..ceiling scale rather than stretched to its own
 * percentiles (which makes a blank field and a strongly birefringent sample look alike).
 */
class DisplayRangeOverrideTest {

    private static ImageData<BufferedImage> sixteenBit() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                img.getRaster().setSample(x, y, 0, 100 + x * 3); // a narrow, low range
            }
        }
        return new ImageData<>(new WrappedBufferedImageServer("ret", img));
    }

    @Test
    void retardanceIsStoredWithTheFixedRange() throws Exception {
        ImageData<BufferedImage> data = sixteenBit();
        QPProjectFunctions.applyDisplayRangeOverride(
                data, new File("S_lcps_20x_r1_1_retardance.ome.tif"), new LCPolScopeModalityHandler());
        assertNotNull(data.getProperty(ImageDisplay.class.getName()), "display settings stored on the image");
        ImageDisplay reopened = ImageDisplay.create(data);
        var channel = reopened.availableChannels().get(0);
        assertEquals(0f, channel.getMinDisplay(), 1e-3);
        assertEquals(2000f, channel.getMaxDisplay(), 1e-3);
    }

    @Test
    void otherFilesAreLeftToAutomaticContrast() {
        ImageData<BufferedImage> data = sixteenBit();
        QPProjectFunctions.applyDisplayRangeOverride(
                data, new File("S_lcps_20x_r1_1.ome.tif"), new LCPolScopeModalityHandler());
        assertNull(data.getProperty(ImageDisplay.class.getName()));
    }
}
