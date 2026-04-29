package qupath.ext.qpsc.utilities;

import java.awt.image.BufferedImage;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.utilities.AffineTransformManager.TransformPreset;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Single source of truth for resolving the macro-image flip used at any given site.
 *
 * <p>Flip is the parity correction between the source (macro/scanner) coordinate frame and
 * the target (microscope) coordinate frame. It is properly a property of the
 * (source-scanner, target-microscope) pair, not of either device alone, so the canonical
 * storage location is the saved {@link TransformPreset}. Three earlier sources still exist
 * for backward compatibility, and this class composes them in priority order.
 *
 * <h2>Resolution priority (highest first)</h2>
 * <ol>
 *   <li><b>Per-image metadata</b> ({@code flip_x}/{@code flip_y} on the {@link ProjectImageEntry}).
 *       If the entry exists and has these keys, that value is authoritative -- it represents
 *       what was recorded when the image was imported and any downstream coordinate math has
 *       already assumed it.
 *   <li><b>Active preset</b> ({@link TransformPreset#getFlipMacroX()} / {@code Y}). Set when
 *       the alignment that produced the preset captured its flip state.
 *   <li><b>Per-detector YAML config</b> ({@link MicroscopeConfigManager#getDetectorFlipX(String)}).
 *       Hardware-side optical flip; defaults to false if not set in YAML.
 *   <li><b>Default {@code false}.</b> No global preference exists for macro flip -- the flip is
 *       captured per-alignment via the orientation question at the start of the alignment
 *       workflow and persisted into the preset.
 * </ol>
 *
 * <p>This replaces the chain previously inlined in {@code AcquisitionManager.getParentFlipX/Y}.
 * Per-pair flip lives on the saved preset so switching presets between source scanner -> target
 * microscope pairs (e.g. Ocus40 -> OWS3 vs. Ocus40 -> CAMM-PPM) gives different flip values
 * without any global state.
 *
 * @since 0.4.x
 */
public final class FlipResolver {

    private static final Logger logger = LoggerFactory.getLogger(FlipResolver.class);

    private FlipResolver() {}

    /**
     * Resolve the macro X flip in priority order: image metadata, preset, detector, global pref.
     *
     * @param entry         the project entry whose flip applies; may be {@code null}
     * @param activePreset  the currently-selected alignment preset; may be {@code null}
     * @param detectorId    detector identifier for YAML lookup (e.g. "LOCI_DETECTOR_JAI_001"); may be {@code null}
     * @return resolved flip value
     */
    public static boolean resolveFlipX(
            ProjectImageEntry<BufferedImage> entry, TransformPreset activePreset, String detectorId) {
        // 1. Per-image metadata (key present and == "1") wins for an already-imported entry.
        if (entry != null && entry.getMetadata().get(ImageMetadataManager.FLIP_X) != null) {
            return ImageMetadataManager.isFlippedX(entry);
        }
        // 2. Active preset captured its flip at alignment time.
        if (activePreset != null && activePreset.getFlipMacroX() != null) {
            return activePreset.getFlipMacroX();
        }
        // 3. Per-detector YAML config.
        if (detectorId != null) {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                return mgr.getDetectorFlipX(detectorId);
            }
        }
        // 4. Safe default: no flip. Alignment workflow will overwrite via orientation dialog.
        return false;
    }

    /** Resolve the macro Y flip; see {@link #resolveFlipX(ProjectImageEntry, TransformPreset, String)}. */
    public static boolean resolveFlipY(
            ProjectImageEntry<BufferedImage> entry, TransformPreset activePreset, String detectorId) {
        if (entry != null && entry.getMetadata().get(ImageMetadataManager.FLIP_Y) != null) {
            return ImageMetadataManager.isFlippedY(entry);
        }
        if (activePreset != null && activePreset.getFlipMacroY() != null) {
            return activePreset.getFlipMacroY();
        }
        if (detectorId != null) {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr != null) {
                return mgr.getDetectorFlipY(detectorId);
            }
        }
        return false;
    }

    /**
     * Seed the alignment dialog's flip checkboxes based on any prior preset for the given pair.
     *
     * <p>Looks for an existing preset with matching {@code sourceScanner} and {@code targetMicroscope}
     * that has captured flip state. If found, returns the preset's flip values so the dialog can
     * pre-populate. If no matching preset exists, returns {@link Optional#empty()} and the caller
     * should fall back to the global pref.
     *
     * @param manager           transform manager (needed to enumerate presets)
     * @param sourceScanner     source-scanner identifier (e.g. "Ocus40"); may be {@code null}
     * @param targetMicroscope  target microscope identifier; may be {@code null}
     * @return seeded {@code [flipX, flipY]} or empty when no matching preset has captured flips
     */
    public static Optional<boolean[]> seedFlipForNewAlignment(
            AffineTransformManager manager, String sourceScanner, String targetMicroscope) {
        if (manager == null || targetMicroscope == null) {
            return Optional.empty();
        }
        for (TransformPreset preset : manager.getTransformsForMicroscope(targetMicroscope)) {
            if (!preset.hasFlipState()) {
                continue;
            }
            if (sourceScanner != null && !sourceScanner.equals(preset.getSourceScanner())) {
                continue; // strict pair match when source is known
            }
            boolean[] flips = {preset.getFlipMacroX(), preset.getFlipMacroY()};
            logger.debug(
                    "Seeded flip for ({}, {}) from preset '{}': X={}, Y={}",
                    sourceScanner,
                    targetMicroscope,
                    preset.getName(),
                    flips[0],
                    flips[1]);
            return Optional.of(flips);
        }
        return Optional.empty();
    }
}
