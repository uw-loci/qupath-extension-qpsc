package qupath.ext.qpsc.utilities.lightpath;

/**
 * Immutable snapshot of the full light-path orientation stack in effect when an
 * image entry's pixels were produced. Produced by {@link LightPath#capture} and
 * stored per entry by {@code ImageMetadataManager.setLightPath}.
 *
 * <p>This replaces the lossy {@code flip_x}/{@code flip_y} parity bit. It records
 * each contributing factor (for provenance and future re-derivation) plus the
 * DERIVED net parity actually baked into the pixels ({@link #bakedParity()}) --
 * the single value downstream consumers (SIFT residual, transform pixel-frame,
 * back-propagation) need. Consumers read the baked parity via
 * {@code ImageMetadataManager.bakedParity}, not by recomputing it.</p>
 *
 * <p>{@code slideInsertion} here is the PER-SLIDE physical placement of this
 * entry's slide ({@code "A"} label-left / as-scanned, {@code "B"} label-right /
 * 180), distinct from the scope-wide default. {@code detectorId} records which
 * detector's branch of the light path produced these pixels (multi-detector
 * scopes).</p>
 */
public record LightPathSnapshot(
        String scopeType,
        String slideInsertion,
        String opticalFlip,
        String cameraOrientation,
        String stagePolarity,
        String detectorId,
        boolean macroFlipX,
        boolean macroFlipY,
        boolean bakedParityX,
        boolean bakedParityY) {

    /** The net parity baked into these pixels. */
    public Parity bakedParity() {
        return new Parity(bakedParityX, bakedParityY);
    }

    /** The empirical raw-scanner-to-camera parity of the source preset. */
    public Parity macroFlip() {
        return new Parity(macroFlipX, macroFlipY);
    }
}
