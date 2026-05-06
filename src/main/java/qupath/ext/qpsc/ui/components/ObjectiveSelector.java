package qupath.ext.qpsc.ui.components;

import javafx.scene.control.ComboBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

/**
 * Single source of truth for objective-selection dropdowns across QPSC.
 *
 * <p>Every dialog that lets the user pick an objective should use this
 * class so the population strategy and initial-selection logic match
 * everywhere. Initial selection priority:
 * <ol>
 *   <li>Live Micro-Manager pixel-size match (queries the active config
 *       for each (objective, detector) pair until the configured pixel
 *       size matches the value MM reports for the currently mounted
 *       optics).</li>
 *   <li>{@link PersistentPreferences#getLastObjective()} when the
 *       saved value is still in the populated list.</li>
 *   <li>First entry in the populated list.</li>
 * </ol>
 *
 * <p>Reference implementation lifted from
 * {@link qupath.ext.qpsc.ui.CameraControlController}'s objective-combo
 * setup. Use {@link #create} for the common case (creates and
 * auto-selects in one step) or {@link #autoSelect} when the caller has
 * already populated the combo with a domain-specific list (e.g. the
 * Autofocus Editor uses the hardware-objective list, which is a
 * superset of the acquisition-profile objectives).
 */
public final class ObjectiveSelector {

    private static final Logger logger = LoggerFactory.getLogger(ObjectiveSelector.class);

    /** Max difference (um) between MM-reported pixel size and config
     * pixel size to treat as the same objective/detector pair. */
    private static final double PIXEL_SIZE_MATCH_TOLERANCE_UM = 0.001;

    private ObjectiveSelector() {}

    /**
     * Build a populated objective combo box and apply the standard
     * initial selection. Uses {@link MicroscopeController#getInstance()}
     * to access the live socket client.
     *
     * @param objectives canonical list of objective ids to populate
     *                   (typically from
     *                   {@link MicroscopeConfigManager#getAvailableObjectives()}
     *                   or a hardware-list variant; the caller decides
     *                   the domain since some dialogs filter by
     *                   modality compatibility while others don't).
     * @param cfg active microscope config manager (for pixel-size lookup)
     * @return ComboBox populated and with an initial value selected
     */
    public static ComboBox<String> create(Collection<String> objectives,
                                          MicroscopeConfigManager cfg) {
        return create(objectives, cfg, getDefaultSocketClient());
    }

    /**
     * Build a populated objective combo box with an explicit socket
     * client (for tests or contexts where the singleton is unavailable).
     */
    public static ComboBox<String> create(Collection<String> objectives,
                                          MicroscopeConfigManager cfg,
                                          MicroscopeSocketClient socketClient) {
        ComboBox<String> combo = new ComboBox<>();
        if (objectives != null) {
            combo.getItems().addAll(objectives);
        }
        autoSelect(combo, cfg, socketClient);
        return combo;
    }

    /**
     * Apply the standard initial-selection policy to an
     * already-populated combo. Use this when the caller wants control
     * over which objectives appear in the list.
     *
     * @return the value that ended up selected, or null if none
     */
    public static String autoSelect(ComboBox<String> combo,
                                    MicroscopeConfigManager cfg) {
        return autoSelect(combo, cfg, getDefaultSocketClient());
    }

    /**
     * Apply the standard initial-selection policy with an explicit
     * socket client. {@code socketClient} may be null, in which case
     * the live-pixel-size step is skipped and we fall through to
     * last-used / first-entry.
     */
    public static String autoSelect(ComboBox<String> combo,
                                    MicroscopeConfigManager cfg,
                                    MicroscopeSocketClient socketClient) {
        if (combo == null || combo.getItems().isEmpty()) {
            return null;
        }

        // 1. Live MM pixel-size match.
        String matched = matchByLivePixelSize(combo.getItems(), cfg, socketClient);
        if (matched != null) {
            combo.setValue(matched);
            return matched;
        }

        // 2. Fall back to last-used preference.
        String last = PersistentPreferences.getLastObjective();
        if (last != null && !last.isEmpty() && combo.getItems().contains(last)) {
            combo.setValue(last);
            logger.debug("Pre-selected last-used objective: {}", last);
            return last;
        }

        // 3. Fall back to first entry.
        String first = combo.getItems().get(0);
        combo.setValue(first);
        logger.debug("Pre-selected first available objective: {}", first);
        return first;
    }

    private static String matchByLivePixelSize(Collection<String> objectives,
                                                MicroscopeConfigManager cfg,
                                                MicroscopeSocketClient socketClient) {
        if (socketClient == null || cfg == null || objectives == null || objectives.isEmpty()) {
            return null;
        }
        Set<String> detectors = cfg.getHardwareDetectors();
        if (detectors == null || detectors.isEmpty()) {
            return null;
        }
        try {
            double mmPixelSize = socketClient.getMicroscopePixelSize();
            if (mmPixelSize <= 0) {
                return null;
            }
            for (String obj : objectives) {
                for (String det : detectors) {
                    Double cp = cfg.getHardwarePixelSize(obj, det);
                    if (cp != null
                            && Math.abs(cp - mmPixelSize) < PIXEL_SIZE_MATCH_TOLERANCE_UM) {
                        logger.info(
                                "Auto-detected objective from MM pixel size {}: {} (detector {})",
                                mmPixelSize, obj, det);
                        return obj;
                    }
                }
            }
            logger.info(
                    "MM pixel size {} did not match any configured objective/detector pair",
                    mmPixelSize);
        } catch (IOException e) {
            logger.debug("Could not read MM pixel size for auto-detection: {}", e.getMessage());
        } catch (Exception e) {
            logger.debug("Pixel-size match failed: {}", e.getMessage());
        }
        return null;
    }

    private static MicroscopeSocketClient getDefaultSocketClient() {
        try {
            return MicroscopeController.getInstance().getSocketClient();
        } catch (Exception e) {
            logger.debug("MicroscopeController not available for objective auto-detection: {}",
                    e.getMessage());
            return null;
        }
    }
}
