package qupath.ext.qpsc.ui.liveviewer;

/**
 * Stale-frame detection helpers shared by Sweep Focus and Refine Focus.
 *
 * <p>{@link FrameData#timestampMs()} is set when the Java client RECEIVES the
 * frame data, not when the camera CAPTURED it. After a stage move the live
 * viewer can serve back the same physical frame with a fresh receive
 * timestamp, so a timestamp-only freshness check passes immediately and the
 * focus probe ends up reading metrics from a pre-move pixel buffer. The
 * symptom is identical metric values at base/plus/minus probes (PPM 40x,
 * 2026-05-04: metric=37 at all three).
 *
 * <p>{@link Snapshot} pairs the receive timestamp with a cheap content hash
 * sampled from the raw pixel buffer, and {@link #isFresh(FrameData, Snapshot)}
 * requires both to advance before treating the frame as fresh.
 */
final class FrameFreshness {

    private FrameFreshness() {}

    /**
     * @param timestamp   receive-time wall clock, ms (FrameData.timestampMs)
     * @param contentHash hash of representative pixel bytes
     */
    record Snapshot(long timestamp, int contentHash) {
        static final Snapshot EMPTY = new Snapshot(0L, 0);
    }

    /** Snap the current frame supplier into a Snapshot. */
    static Snapshot capture(java.util.function.Supplier<FrameData> supplier) {
        FrameData f = supplier.get();
        if (f == null) return Snapshot.EMPTY;
        return new Snapshot(f.timestampMs(), pixelContentHash(f));
    }

    /**
     * True iff the frame is both NEWER (receive timestamp) AND DIFFERENT
     * (content hash) from {@code prev}. Both gates must hold to treat the
     * frame as a real post-move sample.
     */
    static boolean isFresh(FrameData frame, Snapshot prev) {
        if (frame == null) return false;
        if (frame.timestampMs() <= prev.timestamp()) return false;
        return pixelContentHash(frame) != prev.contentHash();
    }

    /**
     * Cheap content fingerprint of the raw pixel buffer. Samples 4 disjoint
     * 32-byte blocks at 20%/40%/60%/80% through the buffer; at least one
     * block lands in the well-exposed image centre even when corners sit
     * in vignette darkness whose bytes do not change frame-to-frame.
     */
    static int pixelContentHash(FrameData frame) {
        if (frame == null) return 0;
        byte[] pixels = frame.rawPixels();
        if (pixels == null || pixels.length == 0) return 0;
        final int blockSize = 32;
        final int n = pixels.length;
        int hash = 1;
        int[] fractions = {n / 5, 2 * n / 5, 3 * n / 5, 4 * n / 5};
        for (int frac : fractions) {
            int off = Math.max(0, Math.min(n - blockSize, frac - blockSize / 2));
            int end = Math.min(off + blockSize, n);
            for (int i = off; i < end; i++) {
                hash = 31 * hash + pixels[i];
            }
        }
        return hash;
    }
}
