package qupath.ext.qpsc.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.state.ModalityState;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Process-lifetime singleton that listens to {@link ModalityState} and drives
 * the hardware on every effective change: resolves the active acquisition
 * profile for the new modality, sends APPLYPR, and applies the parfocality Z
 * delta when both endpoints have calibrated offsets on the same objective.
 *
 * <p>Before this service existed, all of this logic lived in
 * {@code StageControlPanel.applyProfileForModality}, which meant a modality
 * change only drove hardware if the user happened to use the Live Viewer's
 * Camera tab. With a process-lifetime actuator subscribed to the central
 * state, any UI surface that calls {@link ModalityState#setModality(String)}
 * (Wizard, Background Collection, Sample Setup, etc.) drives the hardware in
 * the same way -- the user's "selected modality" always equals the hardware
 * state.
 *
 * <h2>Status callback</h2>
 * UI surfaces that want to display per-switch status (e.g. the Camera tab's
 * status label) register a listener via
 * {@link #addStatusListener(Consumer)} and unregister on close. Status
 * updates run on the FX thread.
 *
 * <h2>Threading</h2>
 * The {@link ModalityState} listener fires on whichever thread called
 * {@code setModality} (usually FX). The actuator spawns a daemon thread named
 * {@code Modality-Switch} for the APPLYPR socket call so the FX thread is
 * never blocked. Parfocality Z motion runs inside that thread; status
 * callbacks for "SUCCEEDED/FAILED" route back via {@code Platform.runLater}.
 *
 * <h2>Lazy initialization</h2>
 * The singleton is constructed on first {@link #getInstance()} call. To
 * guarantee the listener is wired even when no caller explicitly asks for
 * the actuator, {@link #ensureRegistered()} can be called at extension
 * startup; otherwise the first dialog or Live Viewer open implicitly wires
 * it (they hit ModalityState which the actuator depends on).
 */
public final class ModalityActuator {

    private static final Logger logger = LoggerFactory.getLogger(ModalityActuator.class);

    private static ModalityActuator instance;

    /** Profile key that APPLYPR last applied. Used for parfocality delta math. */
    private volatile String lastAppliedProfile;

    private final List<Consumer<ApplyStatus>> statusListeners = new CopyOnWriteArrayList<>();

    /** Phase of a single modality-driven APPLYPR cycle. */
    public enum Phase {
        SWITCHING,
        SUCCEEDED,
        FAILED
    }

    /**
     * A single status update routed to all registered listeners. The
     * {@code text} is suitable for direct display; {@code profileKey} carries
     * the resolved profile name for callers that want richer formatting.
     */
    public record ApplyStatus(Phase phase, String profileKey, String text) {}

    private ModalityActuator() {
        ModalityState.getInstance().modalityProperty().addListener((obs, oldV, newV) -> onModalityChanged(newV));
        logger.debug("ModalityActuator constructed; subscribed to ModalityState");
    }

    public static synchronized ModalityActuator getInstance() {
        if (instance == null) {
            instance = new ModalityActuator();
        }
        return instance;
    }

    /**
     * No-op convenience to guarantee the singleton is constructed and the
     * listener wired. Call this at extension startup if you want the actuator
     * to react to ModalityState changes that happen before any dialog opens.
     */
    public static void ensureRegistered() {
        getInstance();
    }

    /**
     * Reset for tests. Removes the existing listener and clears the
     * singleton. Production code does not call this.
     */
    static synchronized void resetForTest() {
        if (instance != null) {
            // Listener removal is implicit: ModalityState is also reset in tests,
            // so the property holding the listener goes away with it.
        }
        instance = null;
    }

    /** Registers a status callback. Returns a {@code Runnable} unregisterer. */
    public Runnable addStatusListener(Consumer<ApplyStatus> listener) {
        statusListeners.add(listener);
        return () -> statusListeners.remove(listener);
    }

    private void fireStatus(ApplyStatus status) {
        if (Platform.isFxApplicationThread()) {
            for (Consumer<ApplyStatus> l : statusListeners) {
                safeFire(l, status);
            }
        } else {
            Platform.runLater(() -> {
                for (Consumer<ApplyStatus> l : statusListeners) {
                    safeFire(l, status);
                }
            });
        }
    }

    private static void safeFire(Consumer<ApplyStatus> l, ApplyStatus s) {
        try {
            l.accept(s);
        } catch (Exception ex) {
            LoggerFactory.getLogger(ModalityActuator.class)
                    .warn("ModalityActuator status listener threw: {}", ex.getMessage());
        }
    }

    private void onModalityChanged(String newModality) {
        if (newModality == null || newModality.isBlank()) {
            return;
        }
        MicroscopeController mc = MicroscopeController.getInstance();
        if (mc == null || !mc.isConnected()) {
            logger.debug("ModalityActuator: not connected; skipping APPLYPR for '{}'", newModality);
            return;
        }
        final String profileToApply = findFirstMatchingProfile(newModality);
        if (profileToApply == null) {
            logger.debug("ModalityActuator: no matching profile for '{}'; skipping APPLYPR", newModality);
            return;
        }
        final String previousProfile = lastAppliedProfile;
        fireStatus(new ApplyStatus(Phase.SWITCHING, profileToApply, "Switching to " + profileToApply + "..."));
        Thread t = new Thread(
                () -> {
                    try {
                        mc.withLiveModeHandling(() -> mc.getSocketClient().applyProfile(profileToApply));
                        applyParfocalityDeltaIfApplicable(previousProfile, profileToApply);
                        lastAppliedProfile = profileToApply;
                        fireStatus(new ApplyStatus(Phase.SUCCEEDED, profileToApply, "Switched to " + profileToApply));
                        logger.info("ModalityActuator: APPLYPR({}) succeeded", profileToApply);
                    } catch (Exception ex) {
                        logger.error("ModalityActuator: APPLYPR({}) failed: {}", profileToApply, ex.getMessage());
                        fireStatus(new ApplyStatus(Phase.FAILED, profileToApply, "Switch failed: " + ex.getMessage()));
                    }
                },
                "Modality-Switch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Same algorithm as the StageControlPanel helper of the same name (now
     * removed). Prefer the longest case-insensitive prefix match of length 2+
     * between the modality name and the profile's declared {@code modality}
     * field.
     */
    @SuppressWarnings("unchecked")
    private static String findFirstMatchingProfile(String modality) {
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr == null) return null;
            Object profiles = mgr.getConfigItem("acquisition_profiles");
            if (!(profiles instanceof Map<?, ?> profileMap) || profileMap.isEmpty()) return null;
            String modalityLower = modality.toLowerCase();
            for (Map.Entry<?, ?> entry : profileMap.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> profileCfg)) continue;
                Object profModality = profileCfg.get("modality");
                if (profModality == null) continue;
                String profModStr = profModality.toString().toLowerCase();
                int prefix = Math.min(2, Math.min(modalityLower.length(), profModStr.length()));
                if (modalityLower.regionMatches(0, profModStr, 0, prefix)) {
                    return String.valueOf(entry.getKey());
                }
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(ModalityActuator.class)
                    .debug("findFirstMatchingProfile({}) failed: {}", modality, e.getMessage());
        }
        return null;
    }

    /**
     * Shifts the stage Z by the parfocality delta between two profiles. Skipped
     * when (a) either profile lacks a calibrated offset, (b) the profiles are
     * on different objectives, (c) the delta is below 0.05 um, or (d) this is
     * the session's first switch (previousProfile is null).
     */
    private static void applyParfocalityDeltaIfApplicable(String previousProfile, String newProfile) {
        if (previousProfile == null || previousProfile.equals(newProfile)) {
            return;
        }
        try {
            MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
            if (mgr == null) return;
            Double oldOffset = mgr.getProfileParfocalOffset(previousProfile);
            Double newOffset = mgr.getProfileParfocalOffset(newProfile);
            if (oldOffset == null || newOffset == null) {
                logger.info(
                        "Parfocality: skipping Z delta {} -> {} (offsets: {} -> {})",
                        previousProfile,
                        newProfile,
                        oldOffset,
                        newOffset);
                return;
            }
            String oldObj = mgr.getProfileObjective(previousProfile);
            String newObj = mgr.getProfileObjective(newProfile);
            if (oldObj == null || newObj == null || !oldObj.equals(newObj)) {
                logger.info(
                        "Parfocality: skipping Z delta {} -> {} (cross-objective: {} -> {})",
                        previousProfile,
                        newProfile,
                        oldObj,
                        newObj);
                return;
            }
            double delta = newOffset - oldOffset;
            if (Math.abs(delta) < 0.05) {
                logger.info(
                        "Parfocality: delta {} um below threshold ({} -> {})",
                        String.format("%.3f", delta),
                        previousProfile,
                        newProfile);
                return;
            }
            MicroscopeController mc = MicroscopeController.getInstance();
            double currentZ = mc.getStagePositionZ();
            double targetZ = currentZ + delta;
            mc.moveStageZ(targetZ);
            logger.info(
                    "Parfocality: applied Z delta {} -> {} ({} um, {} -> {})",
                    previousProfile,
                    newProfile,
                    String.format("%+.2f", delta),
                    String.format("%.2f", currentZ),
                    String.format("%.2f", targetZ));
        } catch (Exception ex) {
            logger.warn(
                    "Parfocality: Z delta application failed {} -> {}: {}",
                    previousProfile,
                    newProfile,
                    ex.getMessage());
        }
    }
}
