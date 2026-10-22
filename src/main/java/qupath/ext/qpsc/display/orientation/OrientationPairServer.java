package qupath.ext.qpsc.display.orientation;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.regions.RegionRequest;

/**
 * Orientation (band 0) and, when present, retardance (band 1) as one uint16 image, read tile by
 * tile from the two stitched images at the orientation image's pyramid levels.
 *
 * <p>It exists because QuPath's live overlay paints only tiles held in the shared tile cache, and
 * only tileable servers put tiles there; a channel-concatenation server does not. Not saved in a
 * project, so it has no server builder.
 */
final class OrientationPairServer extends AbstractTileableImageServer {

    private final ImageServer<BufferedImage> orientation;
    private final ImageServer<BufferedImage> retardance;
    private final ImageServerMetadata metadata;

    OrientationPairServer(ImageServer<BufferedImage> orientation, ImageServer<BufferedImage> retardance) {
        this.orientation = orientation;
        this.retardance = retardance;
        List<ImageChannel> channels = new ArrayList<>();
        channels.add(ImageChannel.getInstance("Orientation (deg x 100)", 0xff00ff));
        if (retardance != null) {
            channels.add(ImageChannel.getInstance("Retardance (nm x 100)", 0x00ffff));
        }
        this.metadata = new ImageServerMetadata.Builder(orientation.getMetadata())
                .channels(channels)
                .pixelType(PixelType.UINT16)
                .rgb(false)
                .name("Orientation colour view")
                .build();
    }

    @Override
    protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
        RegionRequest request = tileRequest.getRegionRequest();
        Raster ori = orientation
                .readRegion(request.updatePath(orientation.getPath()))
                .getRaster();
        Raster ret = retardance == null
                ? null
                : retardance
                        .readRegion(request.updatePath(retardance.getPath()))
                        .getRaster();
        int w = ori.getWidth();
        int h = ori.getHeight();
        int bands = ret == null ? 1 : 2;
        WritableRaster out = Raster.createBandedRaster(DataBuffer.TYPE_USHORT, w, h, bands, null);
        out.setSamples(0, 0, w, h, 0, ori.getSamples(0, 0, w, h, 0, (int[]) null));
        if (ret != null) {
            int rw = Math.min(w, ret.getWidth());
            int rh = Math.min(h, ret.getHeight());
            out.setSamples(0, 0, rw, rh, 1, ret.getSamples(0, 0, rw, rh, 0, (int[]) null));
        }
        int[] colors = bands == 1 ? new int[] {0xff00ff} : new int[] {0xff00ff, 0x00ffff};
        return new BufferedImage(
                ColorModelFactory.createColorModel(PixelType.UINT16, bands, false, colors), out, false, null);
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        return null;
    }

    @Override
    protected String createID() {
        return "qpsc-orientation-pair:" + orientation.getPath() + "|"
                + (retardance == null ? "-" : retardance.getPath());
    }

    @Override
    public Collection<URI> getURIs() {
        List<URI> uris = new ArrayList<>(orientation.getURIs());
        if (retardance != null) {
            uris.addAll(retardance.getURIs());
        }
        return uris;
    }

    @Override
    public String getServerType() {
        return "Orientation colour view";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }
}
