package qupath.ext.qpsc.state;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.preferences.PersistentPreferences;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Central in-memory state for "the currently-selected modality" across the QPSC
 * extension. Multiple UI surfaces (Acquisition Wizard, Background Collection,
 * Sample Setup, Live Viewer Camera tab, etc.) used to maintain independent
 * modality combos that synced only through {@code PersistentPreferences}'s
 * file-backed {@code lastModality} string -- read on dialog open, written on
 * change, with no in-memory broadcast. The result was an out-of-sync UX: the
 * Wizard could read Brightfield while the Camera tab read Fluorescence, with no
 * indication that the two were referring to different concepts of "current".
 *
 * <p>{@code ModalityState} owns one observable string; every linked combo binds
 * to it (selection -&gt; {@link #setModality(String)}; property change -&gt;
 * combo.setValue). A separate {@code ModalityActuator} (in
 * {@code qupath.ext.qpsc.service}) listens for changes and drives the hardware
 * (APPLYPR + parfocality Z shift) regardless of which UI surface initiated the
 * change. The persistent backing (via {@link PersistentPreferences#getLastModality()}
 * / {@link PersistentPreferences#setLastModality(String)}) remains intact for
 * boot/restore; this class supersedes only the cross-dialog sync role.
 *
 * <h2>Idempotency &amp; re-entrancy</h2>
 * {@link #setModality(String)} is a no-op when the value equals the current
 * value, leveraging {@code SimpleStringProperty.set}'s natural idempotency.
 * Combined with {@code ComboBox.setValue}'s identity-set short-circuit, this
 * prevents combo-A -&gt; state -&gt; combo-A feedback loops without requiring
 * explicit guards.
 *
 * <h2>Validate-on-read clamping</h2>
 * When the microscope config changes (e.g. user picks a different scope in
 * Preferences), the persisted modality may no longer be valid for the new
 * config. {@link #getModality()} validates against {@link #getValidModalities()}
 * and, on mismatch, replaces the current value with the first valid modality
 * (also persisting). This avoids a separate config-reload callback (none
 * exists in the codebase today) at the cost of one extra validation pass per
 * read; reads are infrequent relative to property listener fan-out so the
 * cost is negligible.
 *
 * <h2>Threading</h2>
 * All public methods are thread-safe via {@code synchronized} on
 * {@link #getInstance()}; the property fires listeners on whichever thread
 * {@link #setModality(String)} was called from. Callers from non-FX threads
 * are responsible for routing UI updates through {@code Platform.runLater}.
 * In practice all combo listeners that drive this state run on the FX thread.
 */
public final class ModalityState {

    private static final Logger logger = LoggerFactory.getLogger(ModalityState.class);

    private static ModalityState instance;

    /** The observable string the UI combos bind to. */
    private final ReadOnlyStringWrapper modality;

    /** Where to read the initial value from. Production wires to PersistentPreferences. */
    private final Supplier<String> initialValueSource;

    /** Where to write every change to. Production wires to PersistentPreferences. */
    private final Consumer<String> persistenceSink;

    /** Where to look up valid modalities for the active config. */
    private final Supplier<Set<String>> validityProvider;

    /**
     * Visible for tests. Production callers use {@link #getInstance()}.
     */
    ModalityState(
            Supplier<String> initialValueSource,
            Consumer<String> persistenceSink,
            Supplier<Set<String>> validityProvider) {
        this.initialValueSource = initialValueSource;
        this.persistenceSink = persistenceSink;
        this.validityProvider = validityProvider;
        String initial = initialValueSource.get();
        this.modality = new ReadOnlyStringWrapper(this, "modality", initial);
        logger.debug("ModalityState initialized with: {}", initial);
    }

    /**
     * Returns the process-wide singleton. Wires the persistence sink to
     * {@link PersistentPreferences} and the validity provider to the active
     * {@link MicroscopeConfigManager}. Lazy-initialized; safe to call before
     * the config is loaded (validity provider degrades to an empty set).
     */
    public static synchronized ModalityState getInstance() {
        if (instance == null) {
            instance = new ModalityState(
                    PersistentPreferences::getLastModality,
                    PersistentPreferences::setLastModality,
                    ModalityState::lookupValidModalitiesFromActiveConfig);
        }
        return instance;
    }

    /**
     * Resets the singleton. Visible for tests so the state can be re-seeded
     * with a different supplier set; production code should not call this.
     */
    static synchronized void resetForTest() {
        instance = null;
    }

    /**
     * Public observable view of the current modality. UI combos bind their
     * {@code valueProperty()} to this and listen for external changes here.
     */
    public ReadOnlyStringProperty modalityProperty() {
        return modality.getReadOnlyProperty();
    }

    /**
     * Returns the current modality, validating it against the active config's
     * declared modalities. When the persisted value is unknown for the
     * current scope (e.g. after switching configs), this clamps to the first
     * valid modality and persists the clamped value, so subsequent reads are
     * stable.
     *
     * @return the (possibly clamped) current modality, or {@code null} when no
     *     modalities are declared in the active config and the persisted value
     *     is null/empty
     */
    public String getModality() {
        String current = modality.get();
        Set<String> valid = validityProvider.get();
        if (valid == null || valid.isEmpty()) {
            // No config yet, or config has no modalities -- return whatever
            // we have without validation. Combos that open before the config
            // loads will rebind once a real value arrives.
            return current;
        }
        if (current != null && valid.contains(current)) {
            return current;
        }
        // Clamp to the first valid modality.
        String clamped = valid.iterator().next();
        if (current != null && !current.isEmpty()) {
            logger.info(
                    "ModalityState: persisted modality '{}' is not valid for the active config; clamping to '{}'",
                    current,
                    clamped);
        }
        // Use setModality so listeners notice the clamp + the persistence
        // sink is updated. setModality is idempotent so calling getModality
        // repeatedly won't keep firing listeners after the first clamp.
        setModality(clamped);
        return clamped;
    }

    /**
     * Updates the current modality. No-op when the new value equals the
     * current value (relies on {@link ReadOnlyStringWrapper}'s natural
     * idempotency). Persists every effective change via the persistence sink.
     *
     * <p>Listeners on {@link #modalityProperty()} fire synchronously on the
     * calling thread; do not call this from a background thread if the
     * listeners drive FX UI directly.
     */
    public void setModality(String newModality) {
        String old = modality.get();
        if (java.util.Objects.equals(old, newModality)) {
            return;
        }
        modality.set(newModality);
        try {
            persistenceSink.accept(newModality);
        } catch (Exception ex) {
            // Persistence failure should not break in-memory propagation;
            // log and continue. The next QuPath restart will fall back to
            // whatever the persistence layer last managed to record.
            logger.warn("ModalityState: failed to persist new modality '{}': {}", newModality, ex.getMessage());
        }
        logger.debug("ModalityState: '{}' -> '{}'", old, newModality);
    }

    /**
     * The set of modality names currently valid (declared in the active
     * config). Empty when the config isn't loaded.
     */
    public Set<String> getValidModalities() {
        Set<String> v = validityProvider.get();
        return v == null ? Set.of() : v;
    }

    /**
     * Looks up modalities from the active microscope config. Returns an empty
     * set when nothing is loaded yet -- callers tolerate that.
     */
    private static Set<String> lookupValidModalitiesFromActiveConfig() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isBlank()) {
                return Set.of();
            }
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr == null) {
                return Set.of();
            }
            Set<String> mods = mgr.getAvailableModalities();
            return mods == null ? Set.of() : mods;
        } catch (Exception ex) {
            logger.debug("ModalityState: validity lookup failed: {}", ex.getMessage());
            return Set.of();
        }
    }
}
