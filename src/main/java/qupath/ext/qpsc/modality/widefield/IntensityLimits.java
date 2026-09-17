package qupath.ext.qpsc.modality.widefield;

import java.util.Map;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.modality.Channel;
import qupath.ext.qpsc.modality.PropertyRef;

/**
 * Bounds per-channel intensity spinners with the range Micro-Manager actually accepts.
 *
 * <p>A channel's {@code intensity_property} is device-specific -- one DLED wavelength may
 * run 0-100 while a lamp runs 0-2100 -- and the YAML does not declare it per channel. Left
 * to itself a spinner can only pick an arbitrary maximum, so the user can type a value the
 * hardware will reject (entering 105 on a 0-100 LED). The MM Core knows the limits; this
 * asks it (GETPROPL) and narrows the spinners to the answer.
 *
 * <p>Advisory by design: when the microscope is not connected, the server is older than
 * GETPROPL, or the property has no numeric limits, the spinner keeps the range it was
 * built with. A guessed range is never substituted for a real one.
 */
public final class IntensityLimits {

    private static final Logger logger = LoggerFactory.getLogger(IntensityLimits.class);

    private IntensityLimits() {}

    /**
     * Query limits for every channel that has an intensity property and narrow its spinner,
     * off the FX thread. Spinner updates are applied back on the FX thread; a current value
     * outside the reported range is clamped into it.
     *
     * @param channels channel definitions by id
     * @param spinners intensity spinners by channel id; ids with no spinner are skipped
     * @param status called on the FX thread with a short message when anything was clamped,
     *     or {@code null} for no reporting
     */
    public static void applyAsync(
            Map<String, Channel> channels, Map<String, Spinner<Double>> spinners, Consumer<String> status) {
        if (channels == null || channels.isEmpty() || spinners == null || spinners.isEmpty()) return;
        Thread t = new Thread(() -> applyBlocking(channels, spinners, status), "qpsc-intensity-limits");
        t.setDaemon(true);
        t.start();
    }

    private static void applyBlocking(
            Map<String, Channel> channels, Map<String, Spinner<Double>> spinners, Consumer<String> status) {
        MicroscopeController mc;
        try {
            mc = MicroscopeController.getInstance();
        } catch (Exception e) {
            logger.debug("No microscope controller; intensity spinners keep their default range");
            return;
        }
        if (mc == null || !mc.isConnected()) return;

        StringBuilder clamped = new StringBuilder();
        for (Map.Entry<String, Spinner<Double>> entry : spinners.entrySet()) {
            String id = entry.getKey();
            Channel channel = channels.get(id);
            PropertyRef ref = channel == null ? null : channel.intensityProperty();
            if (ref == null) continue;
            try {
                var limits = mc.getSocketClient().getPropertyLimits(ref.device(), ref.property());
                if (limits.isEmpty()) continue;
                double low = limits.get().lower();
                double high = limits.get().upper();
                Spinner<Double> spinner = entry.getValue();
                Platform.runLater(() -> {
                    Double current = spinner.getValue();
                    double kept = current == null ? low : Math.min(Math.max(current, low), high);
                    spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(low, high, kept, 1.0));
                    spinner.setTooltip(new javafx.scene.control.Tooltip(String.format(
                            "%s.%s accepts %s to %s (reported by Micro-Manager).",
                            ref.device(), ref.property(), trim(low), trim(high))));
                });
                if (spinner.getValue() != null && (spinner.getValue() < low || spinner.getValue() > high)) {
                    if (clamped.length() > 0) clamped.append(", ");
                    clamped.append(id);
                    logger.info(
                            "Intensity for {} was {}, outside {}.{} range {}..{}; clamped",
                            id,
                            spinner.getValue(),
                            ref.device(),
                            ref.property(),
                            low,
                            high);
                }
            } catch (Exception e) {
                // Older servers do not know GETPROPL; keep the built-in range.
                logger.debug("Could not read limits for {}.{}: {}", ref.device(), ref.property(), e.getMessage());
            }
        }
        if (status != null && clamped.length() > 0) {
            String msg = "Intensity clamped to the hardware range for: " + clamped;
            Platform.runLater(() -> status.accept(msg));
        }
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
