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
 * Central in-memory state for "the currently-selected objective" across the
 * QPSC extension. Parallels {@link ModalityState} -- before this existed, the
 * Live Viewer Camera tab, Acquisition Wizard, Existing-Image dialog, Background
 * Collection, Sample Setup and White Balance dialog each owned their own
 * objective combo and synced only via {@link PersistentPreferences}'s
 * file-backed {@code lastObjective} string, with no in-memory broadcast. The
 * Camera tab's combo never even called {@link PersistentPreferences#setLastObjective}
 * after a selection change, so changing the objective there was invisible to
 * any other open dialog -- and to the Wizard the next time it opened.
 *
 * <p>{@code ObjectiveState} owns one observable string; every linked combo binds
 * to it (selection -&gt; {@link #setObjective(String)}; property change -&gt;
 * combo.setValue). Persistent backing remains intact for boot/restore; this
 * class supersedes only the cross-dialog sync role.
 *
 * <h2>Idempotency &amp; re-entrancy</h2>
 * {@link #setObjective(String)} is a no-op when the value equals the current
 * value, leveraging {@code ReadOnlyStringWrapper.set}'s natural idempotency.
 * Combined with {@code ComboBox.setValue}'s identity-set short-circuit, this
 * prevents combo-A -&gt; state -&gt; combo-A feedback loops without requiring
 * explicit guards.
 *
 * <h2>Validate-on-read clamping</h2>
 * When the microscope config changes (e.g. user picks a different scope in
 * Preferences), the persisted objective may not exist in the new config.
 * {@link #getObjective()} validates against {@link #getValidObjectives()} and,
 * on mismatch, replaces the current value with the first valid objective (also
 * persisting). This mirrors the validate-on-read clamp in {@link ModalityState}.
 *
 * <h2>Threading</h2>
 * All public methods are thread-safe via {@code synchronized} on
 * {@link #getInstance()}; the property fires listeners on whichever thread
 * {@link #setObjective(String)} was called from. Callers from non-FX threads
 * are responsible for routing UI updates through {@code Platform.runLater}.
 * In practice all combo listeners that drive this state run on the FX thread.
 */
public final class ObjectiveState {

    private static final Logger logger = LoggerFactory.getLogger(ObjectiveState.class);

    private static ObjectiveState instance;

    private final ReadOnlyStringWrapper objective;
    private final Supplier<String> initialValueSource;
    private final Consumer<String> persistenceSink;
    private final Supplier<Set<String>> validityProvider;

    ObjectiveState(
            Supplier<String> initialValueSource,
            Consumer<String> persistenceSink,
            Supplier<Set<String>> validityProvider) {
        this.initialValueSource = initialValueSource;
        this.persistenceSink = persistenceSink;
        this.validityProvider = validityProvider;
        String initial = initialValueSource.get();
        this.objective = new ReadOnlyStringWrapper(this, "objective", initial);
        logger.debug("ObjectiveState initialized with: {}", initial);
    }

    public static synchronized ObjectiveState getInstance() {
        if (instance == null) {
            instance = new ObjectiveState(
                    PersistentPreferences::getLastObjective,
                    PersistentPreferences::setLastObjective,
                    ObjectiveState::lookupValidObjectivesFromActiveConfig);
        }
        return instance;
    }

    static synchronized void resetForTest() {
        instance = null;
    }

    public ReadOnlyStringProperty objectiveProperty() {
        return objective.getReadOnlyProperty();
    }

    /**
     * Returns the current objective, validating it against the active config's
     * declared objectives. When the persisted value is unknown for the current
     * scope (e.g. after switching configs), this clamps to the first valid
     * objective and persists the clamped value, so subsequent reads are stable.
     */
    public String getObjective() {
        String current = objective.get();
        Set<String> valid = validityProvider.get();
        if (valid == null || valid.isEmpty()) {
            return current;
        }
        if (current != null && valid.contains(current)) {
            return current;
        }
        String clamped = valid.iterator().next();
        if (current != null && !current.isEmpty()) {
            logger.info(
                    "ObjectiveState: persisted objective '{}' is not valid for the active config; clamping to '{}'",
                    current,
                    clamped);
        }
        setObjective(clamped);
        return clamped;
    }

    /**
     * Updates the current objective. No-op when the new value equals the
     * current value. Persists every effective change via the persistence sink.
     */
    public void setObjective(String newObjective) {
        String old = objective.get();
        if (java.util.Objects.equals(old, newObjective)) {
            return;
        }
        objective.set(newObjective);
        try {
            persistenceSink.accept(newObjective);
        } catch (Exception ex) {
            logger.warn("ObjectiveState: failed to persist new objective '{}': {}", newObjective, ex.getMessage());
        }
        logger.debug("ObjectiveState: '{}' -> '{}'", old, newObjective);
    }

    public Set<String> getValidObjectives() {
        Set<String> v = validityProvider.get();
        return v == null ? Set.of() : v;
    }

    private static Set<String> lookupValidObjectivesFromActiveConfig() {
        try {
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isBlank()) {
                return Set.of();
            }
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr == null) {
                return Set.of();
            }
            Set<String> objs = mgr.getAvailableObjectives();
            return objs == null ? Set.of() : objs;
        } catch (Exception ex) {
            logger.debug("ObjectiveState: validity lookup failed: {}", ex.getMessage());
            return Set.of();
        }
    }
}
