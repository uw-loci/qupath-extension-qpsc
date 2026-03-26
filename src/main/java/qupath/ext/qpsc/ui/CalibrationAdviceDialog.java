package qupath.ext.qpsc.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/**
 * Shows best-practice advice dialogs for calibration workflows
 * (white balance and background collection).
 */
public final class CalibrationAdviceDialog {

    private CalibrationAdviceDialog() {}

    /** Shows advice for background image collection. */
    public static void showBackgroundAdvice() {
        showAdvice(
                "Best Practices: Background Image Collection",
                "Background images are used for flat-field correction. A good background\n"
                        + "image is critical for even illumination across your stitched output.\n\n"
                        + "BEFORE COLLECTING\n"
                        + "=================\n\n"
                        + "  - Keep the slide in place. The glass layers affect the light path.\n"
                        + "    Removing the slide changes the optical properties and produces\n"
                        + "    incorrect flat-field corrections.\n\n"
                        + "  - Navigate to a BLANK area of the slide -- clear glass with no\n"
                        + "    tissue, ink, debris, or labels. Avoid areas near the slide edge\n"
                        + "    where adhesive or mounting media may be visible.\n\n"
                        + "  - Avoid color gradients. If you see a gradient across the field\n"
                        + "    of view (brighter on one side), move to a different area.\n"
                        + "    Gradients near tissue edges are common.\n\n"
                        + "  - Defocus slightly (1-2 Z steps) if the slide surface isn't\n"
                        + "    perfectly clean. Minor scratches and dust become invisible\n"
                        + "    when slightly out of focus, but the illumination pattern\n"
                        + "    is preserved.\n\n"
                        + "  - Verify Kohler illumination is set up correctly before\n"
                        + "    collecting backgrounds. Kohler illumination provides the\n"
                        + "    most uniform, even illumination across the field of view.\n"
                        + "    Reference: https://micro-manager.org/Kohler_Illumination\n\n"
                        + "  - Collect new backgrounds whenever you change the objective,\n"
                        + "    detector, illumination source, or lamp intensity.\n\n"
                        + "CHECKING FOR OPTICAL CONTAMINATION\n"
                        + "==================================\n\n"
                        + "  If you see dark spots or marks that stay in the same position\n"
                        + "  when you move the stage, the contamination is on the optics,\n"
                        + "  not the slide.\n\n"
                        + "  To identify which component is dirty:\n"
                        + "  - Rotate the camera (if possible). If spots rotate with it,\n"
                        + "    the camera sensor or its protective window needs cleaning.\n"
                        + "  - Move the condenser or filter turret. If spots move, clean\n"
                        + "    that component.\n"
                        + "  - Check the objective front element with a magnifier.\n\n"
                        + "  Clean optics with lens paper and appropriate solvent (ethanol\n"
                        + "  or commercial lens cleaner). Never use paper towels or tissues\n"
                        + "  on optical surfaces.\n\n"
                        + "AFTER COLLECTING\n"
                        + "================\n\n"
                        + "  - Review the background images. They should show smooth,\n"
                        + "    gradual illumination variation -- no tissue, no debris,\n"
                        + "    no sharp features.\n\n"
                        + "  - If using per-angle backgrounds (PPM), each angle should\n"
                        + "    have similar overall brightness. Large brightness differences\n"
                        + "    between angles may indicate polarizer alignment issues.");
    }

    /** Shows advice for white balance calibration. */
    public static void showWhiteBalanceAdvice() {
        showAdvice(
                "Best Practices: White Balance Calibration",
                "White balance ensures consistent color reproduction across the\n"
                        + "camera's RGB channels. Proper calibration prevents color casts\n"
                        + "in your acquired images.\n\n"
                        + "BEFORE CALIBRATING\n"
                        + "==================\n\n"
                        + "  - Keep the slide in place (same as for background collection).\n"
                        + "    The glass affects the spectral transmission.\n\n"
                        + "  - Navigate to a BLANK area -- clear glass, no tissue, no ink.\n"
                        + "    The same position used for background collection is ideal.\n\n"
                        + "  - Verify Kohler illumination before calibrating. Proper\n"
                        + "    Kohler alignment ensures uniform illumination, which is\n"
                        + "    essential for accurate white balance.\n"
                        + "    Reference: https://micro-manager.org/Kohler_Illumination\n\n"
                        + "  - Let the illumination warm up for at least 5-10 minutes.\n"
                        + "    Lamp color temperature shifts as it warms, which will\n"
                        + "    invalidate a calibration done on a cold lamp.\n\n"
                        + "  - Avoid color gradients and areas near tissue.\n"
                        + "    Tissue stain (especially eosin) can scatter light and\n"
                        + "    tint nearby blank areas.\n\n"
                        + "  - Defocus slightly if the slide isn't perfectly clean.\n\n"
                        + "  - Do NOT change the lamp intensity after white balance.\n"
                        + "    Changing lamp power may shift the color temperature.\n"
                        + "    If you must change it, recalibrate.\n\n"
                        + "WHITE BALANCE MODES\n"
                        + "===================\n\n"
                        + "  - Simple WB: One calibration for all angles. Fastest.\n"
                        + "    Good when color consistency between angles is not critical.\n\n"
                        + "  - Per-Angle WB: Separate calibration at each polarizer angle.\n"
                        + "    Best for PPM where each angle may have different color\n"
                        + "    characteristics due to polarization effects.\n\n"
                        + "WHEN TO RECALIBRATE\n"
                        + "===================\n\n"
                        + "  - After changing objectives (different glass, different NA)\n"
                        + "  - After changing the illumination source or lamp\n"
                        + "  - After realigning the optical path\n"
                        + "  - At the start of each imaging session (recommended)\n"
                        + "  - If colors look wrong in acquired images\n\n"
                        + "TROUBLESHOOTING\n"
                        + "===============\n\n"
                        + "  - Images have a color cast: Recalibrate WB on a cleaner\n"
                        + "    blank area, further from tissue.\n\n"
                        + "  - WB calibration fails to converge: Check that the camera\n"
                        + "    exposure is not saturating any channel. Reduce exposure\n"
                        + "    or lamp intensity.\n\n"
                        + "  - Colors differ between angles (PPM): Switch to per-angle\n"
                        + "    WB mode for better per-angle correction.");
    }

    private static void showAdvice(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.initModality(Modality.NONE);

        TextArea textArea = new TextArea(content);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        textArea.setPrefHeight(450);
        textArea.setPrefWidth(550);

        VBox wrapper = new VBox(textArea);
        wrapper.setPadding(new Insets(5));

        alert.getDialogPane().setContent(wrapper);
        alert.getDialogPane().setMinWidth(580);
        alert.setResizable(true);
        alert.show(); // Non-blocking so user can reference while working
    }
}
