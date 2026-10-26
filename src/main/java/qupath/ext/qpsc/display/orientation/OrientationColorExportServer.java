package qupath.ext.qpsc.display.orientation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

/**
 * The orientation colour image as an RGB server, for writing to a file: every tile of the
 * {@link OrientationPairServer} coloured with one fixed set of renderer settings.
 *
 * <p>It declares the orientation image's own pyramid levels, so each level is coloured from
 * that level's angles (which the stitcher averaged axially). A writer asking for a downsample
 * between two levels gets the finer level's RGB tile scaled, never interpolated angles.
 */
final class OrientationColorExportServer extends AbstractTileableImageServer {

    private final ImageServer<BufferedImage> pair;
    private final OrientationColorRenderer renderer;
    private final ImageServerMetadata metadata;

    OrientationColorExportServer(ImageServer<BufferedImage> pair, OrientationColorRenderer.Settings settings) {
        this.pair = pair;
        this.renderer = new OrientationColorRenderer(settings);
        this.metadata = new ImageServerMetadata.Builder(pair.getMetadata())
                .channels(ImageChannel.getDefaultRGBChannels())
                .pixelType(PixelType.UINT8)
                .rgb(true)
                .name("Orientation colour (" + settings.kind().configName() + ")")
                .build();
    }

    @Override
    protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
        BufferedImage raw = pair.readRegion(tileRequest.getRegionRequest().updatePath(pair.getPath()));
        return raw == null ? null : renderer.applyTransforms(raw, null);
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        return null;
    }

    @Override
    protected String createID() {
        return "qpsc-orientation-colour:" + pair.getPath() + "|"
                + renderer.getSettings().key();
    }

    @Override
    public Collection<URI> getURIs() {
        return pair.getURIs();
    }

    @Override
    public String getServerType() {
        return "Orientation colour export";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }
}
