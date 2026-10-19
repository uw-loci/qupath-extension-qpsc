package qupath.ext.qpsc.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.state.ObjectiveState;
import qupath.ext.qpsc.ui.ObjectivePresetConfirmDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager.ObjectiveMmPreset;
import qupath.fx.dialogs.Dialogs;

/**
 * Keeps Micro-Manager's objective preset in step with the objective chosen in QPSC.
 *
 * <p>Micro-Manager picks its pixel size from the pixel-size preset that matches the current
 * hardware state, and that state includes the objective. On a scope whose nosepiece is turned
 * by hand, the objective is a labelling device (the LC-PolScope's DemoCamera "DObjective") that
 * only changes when someone selects its preset, so choosing 20x in QPSC left Micro-Manager on
 * another label, reporting pixel size 0 and blocking SIFT (2026-10-19).
 *
 * <p>Opt-in per objective: only an objective with {@code mm_preset: {group, preset, motorized}}
 * in the microscope YAML is acted on, because group and preset names are whatever each lab's
 * Micro-Manager configuration calls them. Then:
 * <ul>
 *   <li>{@code motorized: false} (a labelling device, which moves nothing): applied silently,
 *       on selection and whenever the server connects;</li>
 *   <li>{@code motorized: true}, or the key missing: applied only after the operator confirms
 *       in {@link ObjectivePresetConfirmDialog}, and only on selection, never on connect;</li>
 *   <li>nothing while an acquisition runs (the server refuses as well), nothing when Micro-Manager
 *       is already on that preset, and a warning naming what exists when the group or preset
 *       does not.</li>
 * </ul>
 */
public final class ObjectivePresetActuator {

    private static final Logger logger = LoggerFactory.getLogger(ObjectivePresetActuator.class);

    private static final AtomicBoolean registered = new AtomicBoolean(false);
    /** Only the newest selection is acted on; an older one still being checked is dropped. */
    private static final AtomicLong generation = new AtomicLong();

    /** What to do with a preset, given Micro-Manager's answer about it. */
    enum Decision {
        NOTHING,
        APPLY,
        CONFIRM,
        WARN_MISSING
    }

    private ObjectivePresetActuator() {}

    /** Wires the objective listener once. Safe to call repeatedly. */
    public static void ensureRegistered() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        ObjectiveState.getInstance()
                .objectiveProperty()
                .addListener((obs, oldV, newV) -> onObjectiveSelected(newV, true));
    }

    /**
     * After the server has loaded the configuration: bring a labelling-device preset in line
     * with the objective QPSC has selected. Never prompts; a motorized mismatch is only logged.
     */
    public static void onServerConfigured() {
        ensureRegistered();
        String objective;
        try {
            objective = ObjectiveState.getInstance().getObjective();
        } catch (Exception e) {
            logger.debug("Objective preset sync on connect skipped: {}", e.getMessage());
            return;
        }
        onObjectiveSelected(objective, false);
    }

    /** The decision for a preset spec and Micro-Manager's report on it (pure; tested). */
    static Decision decide(ObjectiveMmPreset spec, JsonObject report) {
        if (report == null || report.has("error") && !report.has("preset_exists")) {
            return Decision.WARN_MISSING;
        }
        if (!report.has("preset_exists") || !report.get("preset_exists").getAsBoolean()) {
            return Decision.WARN_MISSING;
        }
        String current = string(report, "current");
        if (spec.preset().equals(current)) {
            return Decision.NOTHING;
        }
        return spec.motorized() ? Decision.CONFIRM : Decision.APPLY;
    }

    /** The warning for a group or preset Micro-Manager does not have, naming what it does have. */
    static String missingMessage(String objectiveId, ObjectiveMmPreset spec, JsonObject report) {
        if (report == null) {
            return "Objective " + objectiveId + ": the microscope server did not answer about Micro-Manager preset '"
                    + spec.group() + "' / '" + spec.preset() + "' (it may predate this feature).";
        }
        boolean groupExists =
                report.has("group_exists") && report.get("group_exists").getAsBoolean();
        if (!groupExists) {
            return "Objective " + objectiveId + ": Micro-Manager has no configuration group '" + spec.group()
                    + "'. Groups it has: " + list(report, "groups") + ". Fix mm_preset.group in the microscope YAML.";
        }
        return "Objective " + objectiveId + ": Micro-Manager group '" + spec.group() + "' has no preset '"
                + spec.preset() + "'. Presets it has: " + list(report, "presets")
                + ". Fix mm_preset.preset in the microscope YAML.";
    }

    private static void onObjectiveSelected(String objectiveId, boolean selectedNow) {
        if (objectiveId == null || objectiveId.isBlank()) {
            return;
        }
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstanceIfAvailable();
        ObjectiveMmPreset spec = mgr == null ? null : mgr.getObjectiveMmPreset(objectiveId);
        if (spec == null) {
            return; // not opted in: QPSC leaves Micro-Manager's objective alone
        }
        long mine = generation.incrementAndGet();
        Thread t = new Thread(() -> check(objectiveId, spec, selectedNow, mine), "Objective-Preset");
        t.setDaemon(true);
        t.start();
    }

    private static void check(String objectiveId, ObjectiveMmPreset spec, boolean selectedNow, long mine) {
        MicroscopeController mc = MicroscopeController.getInstance();
        if (mc == null || !mc.isConnected()) {
            logger.debug("Objective {}: not connected; Micro-Manager preset not checked", objectiveId);
            return;
        }
        if (mc.isAcquisitionActive()) {
            logger.info("Objective {}: an acquisition is running; Micro-Manager preset left alone", objectiveId);
            return;
        }
        JsonObject report;
        try {
            report = mc.getSocketClient()
                    .mmPreset(false, spec.group(), spec.preset())
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("Objective {}: could not ask Micro-Manager about its preset: {}", objectiveId, e.getMessage());
            return;
        }
        if (mine != generation.get()) {
            return; // a newer selection superseded this one
        }
        switch (decide(spec, report)) {
            case NOTHING ->
                logger.debug(
                        "Objective {}: Micro-Manager already on [{}] '{}'", objectiveId, spec.group(), spec.preset());
            case WARN_MISSING -> warn(missingMessage(objectiveId, spec, report));
            case APPLY -> apply(objectiveId, spec, mine);
            case CONFIRM -> {
                if (!selectedNow) {
                    logger.info(
                            "Objective {}: Micro-Manager's [{}] is '{}', not '{}'. It is a motorized turret, "
                                    + "so QPSC does not move it on connect; choose the objective again to move it.",
                            objectiveId,
                            spec.group(),
                            string(report, "current"),
                            spec.preset());
                    return;
                }
                String current = string(report, "current");
                Platform.runLater(() -> {
                    if (mine != generation.get()) {
                        return;
                    }
                    boolean move = ObjectivePresetConfirmDialog.ask(objectiveId, spec, current);
                    if (!move) {
                        logger.info(
                                "Objective {}: operator declined moving the turret; Micro-Manager stays on {}",
                                objectiveId,
                                current == null || current.isBlank() ? "no matching preset" : "'" + current + "'");
                        Dialogs.showWarningNotification(
                                "Objective",
                                "Micro-Manager is still on '" + (current == null || current.isBlank() ? "?" : current)
                                        + "'. Its pixel size will not match " + objectiveId
                                        + " until the turret is on that objective.");
                        return;
                    }
                    Thread t = new Thread(() -> apply(objectiveId, spec, mine), "Objective-Preset-Move");
                    t.setDaemon(true);
                    t.start();
                });
            }
        }
    }

    private static void apply(String objectiveId, ObjectiveMmPreset spec, long mine) {
        MicroscopeController mc = MicroscopeController.getInstance();
        if (mine != generation.get() || mc == null || !mc.isConnected()) {
            return;
        }
        if (mc.isAcquisitionActive()) {
            // Re-checked here: time passes between the check and a confirmation.
            logger.info("Objective {}: an acquisition started; Micro-Manager preset left alone", objectiveId);
            return;
        }
        Optional<JsonObject> reply;
        try {
            reply = mc.getSocketClient().mmPreset(true, spec.group(), spec.preset());
        } catch (Exception e) {
            logger.warn("Objective {}: applying Micro-Manager preset failed: {}", objectiveId, e.getMessage());
            return;
        }
        JsonObject r = reply.orElse(null);
        if (r == null) {
            warn(missingMessage(objectiveId, spec, null));
        } else if (r.has("error")) {
            warn("Objective " + objectiveId + ": Micro-Manager preset [" + spec.group() + "] '" + spec.preset()
                    + "' not applied: " + r.get("error").getAsString());
        } else if (r.has("applied") && r.get("applied").getAsBoolean()) {
            logger.info(
                    "Objective {}: Micro-Manager [{}] '{}' -> '{}' ({}), pixel size {} um",
                    objectiveId,
                    spec.group(),
                    string(r, "previous"),
                    spec.preset(),
                    spec.motorized() ? "motorized turret, confirmed" : "labelling device",
                    string(r, "pixel_size_um"));
        }
    }

    private static void warn(String message) {
        logger.warn(message);
        Platform.runLater(() -> Dialogs.showWarningNotification("Objective preset", message));
    }

    private static String string(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).getAsString();
    }

    private static String list(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o != null && o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                out.add("'" + e.getAsString() + "'");
            }
        }
        return out.isEmpty() ? "(none)" : String.join(", ", out);
    }
}
