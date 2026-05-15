package qupath.ext.qpsc.utilities;

import java.util.Locale;

/**
 * Operator-facing hint text for autofocus failures.
 *
 * <p>AF refusals are surfaced from several places (Live Viewer button, Autofocus
 * Configuration Editor "Test" button, acquisition tile-AF, manual-focus modal)
 * and the bare server reason string is rarely actionable on its own. The hints
 * here name the most likely root cause given the failure mode and the modality,
 * and point the operator at the two places they can adjust strategy: the
 * acquisition wizard's per-run AF dropdown and the AF Configuration Editor's
 * Modality Bindings tab.
 *
 * <p>Format is plain text with newline separators. Callers append it to the
 * existing error/warning message body.
 */
public final class AfFailureHint {

    private AfFailureHint() {}

    /**
     * Build a hint for the given failure mode + modality.
     *
     * @param modality the active modality name as passed to the AF call, or
     *                 null/empty if unknown. Used to suggest the right
     *                 alternative strategy (sparse_signal vs dense_texture vs
     *                 dark_field).
     * @param reason   the server-supplied failure reason. Recognised
     *                 substrings (case-insensitive): "metric_flat" / "within
     *                 noise", "no peak found" / "could not find peak" /
     *                 "edge", "saturation", "exposure" / "blur", "no_slow_speed".
     *                 Unknown reasons get a generic hint.
     * @return a multi-line hint suitable for appending to an error message.
     *         Never null; always at least the where-to-change-strategy footer.
     */
    public static String format(String modality, String reason) {
        StringBuilder out = new StringBuilder();
        String reasonLower = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        String modalityLower = modality == null ? "" : modality.trim().toLowerCase(Locale.ROOT);

        String diagnosis = diagnose(reasonLower, modalityLower);
        if (!diagnosis.isEmpty()) {
            out.append(diagnosis).append("\n\n");
        }

        out.append("To change focus strategy:\n")
                .append("- This run only: acquisition wizard's Advanced panel -> AF strategy dropdown.\n")
                .append("- Persistent: Settings -> Autofocus Configuration -> Modality Bindings.");

        return out.toString();
    }

    private static String diagnose(String reasonLower, String modalityLower) {
        if (reasonLower.contains("metric_flat")
                || reasonLower.contains("within noise")
                || reasonLower.contains("is likely inside one depth-of-field")) {
            return "The Z scan produced uniformly flat metric values -- no clear focus peak in "
                    + "the swept range. Common causes:\n"
                    + "- Sparse sample (beads, pollen, scattered objects) with a strategy that "
                    + "expects dense texture: switch to strategy 'sparse_signal'.\n"
                    + "- Tissue with too narrow a scan range: widen 'sweep_range_um' for this "
                    + "objective or use Sweep Focus instead of Streaming.\n"
                    + "- Dark-field / SHG / 2P with a strategy that expects mid-gray bright "
                    + "spots: switch to strategy 'dark_field'.";
        }

        if (reasonLower.contains("no peak found")
                || reasonLower.contains("could not find peak")
                || reasonLower.contains("edge_low")
                || reasonLower.contains("edge_high")) {
            return "The scan kept finding the focus peak at the boundary of the swept window. "
                    + "Common causes:\n"
                    + "- Starting Z is too far from focus: move closer manually, then re-run.\n"
                    + "- Sweep range is too narrow for this objective: increase 'sweep_range_um' "
                    + "in the AF configuration for this objective.\n"
                    + "- Sample drifted significantly between tiles: consider running Sweep Focus "
                    + "instead of Streaming, which is more robust to a poor initial Z.";
        }

        if (reasonLower.contains("saturation")) {
            return "The pre-flight saturation check refused the scan -- too many pixels were at "
                    + "the camera's maximum value. Reduce exposure, close the aperture, or lower "
                    + "the illumination intensity, then re-run.";
        }

        if (reasonLower.contains("blur")
                || reasonLower.contains("exposure too long")
                || reasonLower.contains("motion blur")) {
            return "Exposure is too long for the streaming AF blur budget (frames smear across "
                    + "more than the per-frame Z step). Either shorten exposure or fall back to "
                    + "Sweep Focus, which stops the stage at each step and has no blur budget.";
        }

        if (reasonLower.contains("no_slow_speed") || reasonLower.contains("slow speed")) {
            return "The stage refused the configured slow speed for Streaming AF and cannot run "
                    + "a swept scan on this hardware. Run Sweep Focus instead, or update the "
                    + "stage's 'streaming_af.slow_speed' value in the scope config.";
        }

        if (modalityLower.equals("brightfield") || modalityLower.equals("bf")) {
            return "If this is sparse-particle brightfield (pollen, beads) try strategy "
                    + "'sparse_signal'; if it's tissue (H&E, IHC) try 'dense_texture'.";
        }
        if (modalityLower.equals("fluorescence") || modalityLower.equals("fl")) {
            return "If this is sparse-fluorescence (beads, FISH spots) try strategy "
                    + "'sparse_signal'; if it's dense tissue try 'dense_texture'.";
        }

        return "";
    }
}
