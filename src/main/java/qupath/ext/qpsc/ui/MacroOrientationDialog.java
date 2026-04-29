package qupath.ext.qpsc.ui;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the user whether the macro image is flipped relative to what they see in the Live View.
 *
 * <p>Shown as the first step of the Microscope Alignment workflow. The flip is the parity
 * correction between the source scanner's macro frame and the target microscope's display frame
 * -- it cannot be inferred automatically because it depends on both the scanner's optics and
 * the target's stage/camera orientation. The user is the only one who can answer it by
 * comparing the macro and the Live View.
 *
 * <p>The captured answer is threaded through the rest of the workflow and saved into the
 * {@code AffineTransformPreset} so the same alignment can be reloaded later without re-asking.
 */
public final class MacroOrientationDialog {

    private static final Logger logger = LoggerFactory.getLogger(MacroOrientationDialog.class);

    /** Plain record carrying the user's flip answer through the workflow. */
    public record MacroFlip(boolean flipX, boolean flipY) {}

    private MacroOrientationDialog() {}

    /**
     * Show the orientation dialog and return the user's flip answer.
     *
     * <p>If the user cancels the dialog the future completes with {@code null} so the workflow
     * can abort cleanly.
     *
     * @param macroPreview a thumbnail of the macro image to show beside the question; may be null
     * @param seedFlip     prior flip values for this scanner -> microscope pair (if known) used
     *                     to pre-tick the checkboxes; pass null for "no prior known"
     * @return future yielding {@link MacroFlip} or {@code null} on cancel
     */
    public static CompletableFuture<MacroFlip> show(BufferedImage macroPreview, MacroFlip seedFlip) {
        CompletableFuture<MacroFlip> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Dialog<MacroFlip> dialog = new Dialog<>();
                dialog.setTitle("Image orientation");
                dialog.setHeaderText("Compare the macro image to the Live View");

                Label instructions = new Label(
                        "Find the same point in both the macro image and the microscope Live View. "
                                + "Tick the boxes that correspond to flips required to make the macro "
                                + "match the Live View orientation.\n\n"
                                + "If the macro image looks correctly oriented (top-of-slide is up, "
                                + "etc.) leave both boxes unchecked.");
                instructions.setWrapText(true);
                instructions.setMaxWidth(420);

                CheckBox flipXBox = new CheckBox("Macro is flipped horizontally (X) relative to the Live View");
                CheckBox flipYBox = new CheckBox("Macro is flipped vertically (Y) relative to the Live View");
                if (seedFlip != null) {
                    flipXBox.setSelected(seedFlip.flipX());
                    flipYBox.setSelected(seedFlip.flipY());
                }

                VBox controls = new VBox(8, instructions, flipXBox, flipYBox);
                controls.setPadding(new Insets(10));
                controls.setAlignment(Pos.TOP_LEFT);

                HBox content = new HBox(12);
                content.setPadding(new Insets(10));
                content.setAlignment(Pos.CENTER_LEFT);
                if (macroPreview != null) {
                    ImageView iv = new ImageView(SwingFXUtils.toFXImage(macroPreview, null));
                    iv.setPreserveRatio(true);
                    iv.setFitWidth(280);
                    iv.setFitHeight(220);
                    content.getChildren().add(iv);
                }
                content.getChildren().add(controls);

                dialog.getDialogPane().setContent(content);
                dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

                dialog.setResultConverter(button -> {
                    if (button == ButtonType.OK) {
                        return new MacroFlip(flipXBox.isSelected(), flipYBox.isSelected());
                    }
                    return null;
                });

                Optional<MacroFlip> result = dialog.showAndWait();
                future.complete(result.orElse(null));
            } catch (Exception e) {
                logger.error("Macro orientation dialog failed", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}
