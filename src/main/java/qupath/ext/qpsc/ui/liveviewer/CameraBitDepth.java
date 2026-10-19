package qupath.ext.qpsc.ui.liveviewer;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;

/**
 * The top of the live camera's range: 2^bits - 1 for the bits that carry data.
 *
 * <p>A 12-bit camera (the LC-PolScope's Oryx, the demo's mono camera) delivers 16-bit buffers
 * whose values stop at 4095. Scaled to 65535 the histogram crowded the whole image into the
 * first sixteenth of its width, auto-scale worked in 257-count bins, and "Sat" could never
 * count a clipped pixel. The depth comes from the server (GETCAP {@code significant_bits}),
 * fetched once per frame format and again after every modality switch, since a switch can
 * change camera. Until it arrives, or when the server does not know, a 16-bit frame is
 * taken at face value.
 */
final class CameraBitDepth {

    private static final Logger logger = LoggerFactory.getLogger(CameraBitDepth.class);

    private static volatile Integer bits;
    private static volatile String formatSeen;
    private static final AtomicBoolean fetching = new AtomicBoolean(false);
    private static final AtomicBoolean listening = new AtomicBoolean(false);

    private CameraBitDepth() {}

    /** The largest value {@code frame} can hold given the camera's real depth. */
    static int maxValue(FrameData frame) {
        if (frame.bytesPerPixel() != 2) {
            return frame.maxValue();
        }
        ensureFor(frame);
        return maxValueFor(bits, frame.maxValue());
    }

    /** 2^bits - 1 when {@code bits} is a believable depth below the container's, else the container's maximum. */
    static int maxValueFor(Integer bits, int containerMax) {
        if (bits == null || bits < 8 || bits >= 16) {
            return containerMax;
        }
        return Math.min((1 << bits) - 1, containerMax);
    }

    /** Forget the depth: the camera may have changed. */
    static void invalidate() {
        formatSeen = null;
    }

    private static void ensureFor(FrameData frame) {
        listenForModalitySwitches();
        String format = frame.width() + "x" + frame.height() + "x" + frame.channels() + "x" + frame.bytesPerPixel();
        if (format.equals(formatSeen)) {
            return;
        }
        formatSeen = format;
        fetch();
    }

    private static void fetch() {
        if (!fetching.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        MicroscopeController mc = MicroscopeController.getInstance();
                        if (mc == null || !mc.isConnected()) {
                            return;
                        }
                        var cap = mc.getSocketClient().getCapabilities(null);
                        Integer reported = cap != null && cap.camera != null ? cap.camera.significantBits : null;
                        if (!java.util.Objects.equals(reported, bits)) {
                            logger.info("Live Viewer: camera reports {} significant bits", reported);
                        }
                        bits = reported;
                    } catch (Exception e) {
                        logger.debug("Live Viewer: could not read the camera's bit depth: {}", e.getMessage());
                    } finally {
                        fetching.set(false);
                    }
                },
                "LiveViewer-BitDepth");
        t.setDaemon(true);
        t.start();
    }

    private static void listenForModalitySwitches() {
        if (!listening.compareAndSet(false, true)) {
            return;
        }
        qupath.ext.qpsc.service.ModalityActuator.getInstance().addStatusListener(status -> {
            if (status.phase() == qupath.ext.qpsc.service.ModalityActuator.Phase.SUCCEEDED) {
                invalidate();
            }
        });
    }
}
