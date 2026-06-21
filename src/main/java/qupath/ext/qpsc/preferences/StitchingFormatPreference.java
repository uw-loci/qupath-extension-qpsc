package qupath.ext.qpsc.preferences;

import java.util.Arrays;
import java.util.List;
import javafx.beans.property.ObjectProperty;
import javafx.collections.ObservableList;
import org.controlsfx.control.PropertySheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.basicstitching.config.StitchingConfig;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

/**
 * Isolates every reference to {@link StitchingConfig.OutputFormat} -- a class that
 * lives in the {@code qupath-extension-tiles-to-pyramid} extension, not in QPSC.
 *
 * <p>The point of this class is class-loading isolation. If those references lived
 * in {@link QPPreferenceDialog}, the JVM would fail to <em>verify/link</em>
 * {@code QPPreferenceDialog} when tiles-to-pyramid is absent (or has not been added
 * to the extension class loader yet), throwing {@code NoClassDefFoundError} at the
 * call site before any method body -- and therefore any {@code try/catch} -- can run.
 * That is exactly what crashed the whole QPSC extension on a fresh install.
 *
 * <p>By moving the references here, {@code QPPreferenceDialog} loads cleanly with no
 * dependency on tiles-to-pyramid. Callers gate every entry point with a reflection
 * check ({@link QPPreferenceDialog#isStitchingAvailable()}); this class is only
 * loaded once that check confirms the dependency is present, so its own
 * {@code StitchingConfig} references resolve. Methods are still defensively wrapped
 * so a late/partial failure degrades to a sensible default instead of crashing.
 */
public final class StitchingFormatPreference {

    private static final Logger logger = LoggerFactory.getLogger(StitchingFormatPreference.class);

    // Lazy so the persistent preference (which is typed with StitchingConfig.OutputFormat)
    // is only created once we know the class is available.
    private static ObjectProperty<StitchingConfig.OutputFormat> property;

    private StitchingFormatPreference() {}

    private static synchronized ObjectProperty<StitchingConfig.OutputFormat> property() {
        if (property == null) {
            property = PathPrefs.createPersistentPreference(
                    "stitchingOutputFormat", StitchingConfig.OutputFormat.OME_TIFF, StitchingConfig.OutputFormat.class);
        }
        return property;
    }

    /**
     * Current stitching output format, or {@code null} if tiles-to-pyramid is missing
     * (in which case stitching is unavailable and callers must not proceed).
     */
    public static StitchingConfig.OutputFormat get() {
        try {
            return property().get();
        } catch (Throwable t) {
            logger.warn("tiles-to-pyramid not available; output format unavailable: {}", t.toString());
            return null;
        }
    }

    /**
     * Compression types valid for the given output format.
     *
     * <p>OME-TIFF supports all compression types. OME-ZARR only supports a subset:
     * JPEG-2000 variants (J2K, J2K_LOSSY) have no native ZARR codec, and JPEG would
     * silently fall back to zstd which is misleading. Only types that produce the
     * expected compression algorithm are offered.
     */
    public static List<OMEPyramidWriter.CompressionType> compressionTypesFor(StitchingConfig.OutputFormat format) {
        if (format != null && format.stitchAsZarr()) {
            return List.of(
                    OMEPyramidWriter.CompressionType.LZW,
                    OMEPyramidWriter.CompressionType.ZLIB,
                    OMEPyramidWriter.CompressionType.UNCOMPRESSED,
                    OMEPyramidWriter.CompressionType.DEFAULT);
        }
        return Arrays.asList(OMEPyramidWriter.CompressionType.values());
    }

    /**
     * Builds the "Stitching output format" preference item and appends it to {@code items},
     * wiring the compression-choice list to follow the selected format. Must only be invoked
     * when tiles-to-pyramid is confirmed present; the caller wraps the invocation in a
     * {@code try/catch(Throwable)} so a late failure flips stitching to unavailable.
     *
     * @param items                 the preference pane item list to append to
     * @param compressionChoices    the observable compression choices to keep in sync with the format
     * @param compressionTypeProperty the compression preference, reset to LZW when the format change
     *                                invalidates the current selection
     */
    static void addFormatPreferenceItem(
            ObservableList<PropertySheet.Item> items,
            ObservableList<OMEPyramidWriter.CompressionType> compressionChoices,
            ObjectProperty<OMEPyramidWriter.CompressionType> compressionTypeProperty,
            String category) {
        ObjectProperty<StitchingConfig.OutputFormat> formatProp = property();
        items.add(new PropertyItemBuilder<>(formatProp, StitchingConfig.OutputFormat.class)
                .propertyType(PropertyItemBuilder.PropertyType.CHOICE)
                .choices(Arrays.asList(StitchingConfig.OutputFormat.values()))
                .name("Stitching output format")
                .category(category)
                .description("Output format for stitched images.\n"
                        + "OME-TIFF: Traditional single-file format, widely compatible.\n"
                        + "OME-ZARR: Directory format with parallel writing (2-3x faster). "
                        + "Produces many small files -- harder to copy on Windows.\n"
                        + "OME-TIFF via ZARR: Best of both -- parallel ZARR stitching for speed, "
                        + "then automatic background conversion to single-file OME-TIFF. "
                        + "Images are available immediately via ZARR while TIFF converts unattended.")
                .build());

        // Populate compression choices for the current format
        compressionChoices.setAll(compressionTypesFor(formatProp.get()));

        // When format changes, update compression choices and fix invalid selection
        formatProp.addListener((obs, oldFormat, newFormat) -> {
            compressionChoices.setAll(compressionTypesFor(newFormat));
            if (!compressionChoices.contains(compressionTypeProperty.get())) {
                compressionTypeProperty.set(OMEPyramidWriter.CompressionType.LZW);
            }
        });
    }
}
