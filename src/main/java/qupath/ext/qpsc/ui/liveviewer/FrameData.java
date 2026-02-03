package qupath.ext.qpsc.ui.liveviewer;

/**
 * Immutable record holding a single camera frame with metadata.
 * Used for transferring frame data from the socket client to the live viewer.
 *
 * @param width        Image width in pixels
 * @param height       Image height in pixels
 * @param channels     Number of channels (1=grayscale, 3=RGB)
 * @param bytesPerPixel Bytes per pixel per channel (1=uint8, 2=uint16)
 * @param rawPixels    Raw pixel data, row-major, HWC for multi-channel.
 *                     uint16 data is in big-endian byte order from the wire.
 * @param timestampMs  Timestamp when the frame was received (System.currentTimeMillis)
 */
public record FrameData(
        int width,
        int height,
        int channels,
        int bytesPerPixel,
        byte[] rawPixels,
        long timestampMs
) {
    /**
     * Returns the maximum pixel value for this bit depth.
     */
    public int maxValue() {
        return bytesPerPixel == 2 ? 65535 : 255;
    }

    /**
     * Returns true if this is an RGB frame.
     */
    public boolean isRGB() {
        return channels == 3;
    }

    /**
     * Returns the total number of pixels (width * height).
     */
    public int pixelCount() {
        return width * height;
    }

    /**
     * Reads a pixel value at the given byte offset in rawPixels.
     * For uint8, returns the unsigned byte value.
     * For uint16, reads two bytes in big-endian order and returns the unsigned value.
     */
    public int readPixelValue(int byteOffset) {
        if (bytesPerPixel == 1) {
            return rawPixels[byteOffset] & 0xFF;
        } else {
            // Big-endian uint16
            return ((rawPixels[byteOffset] & 0xFF) << 8) | (rawPixels[byteOffset + 1] & 0xFF);
        }
    }
}
