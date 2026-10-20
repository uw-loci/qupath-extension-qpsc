package qupath.ext.qpsc.ui;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the user whether the current high-res image is flipped relative to the Live View.
 *
 * <p>Shown after the project has been created and the original image entry exists, but before
 * tile-picking starts. The dialog is <b>non-modal</b> (Modality.NONE) so the user can pan and
 * zoom in QuPath while the dialog floats. Each checkbox toggle invokes a callback that switches
 * the active image entry to the matching (potentially newly-created) flipped duplicate, so the
 * user can see the result immediately and verify against the Live View before clicking OK.
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
     * Show the non-modal orientation dialog and return the user's flip answer.
     *
     * <p>If the user closes/cancels the dialog the future completes with {@code null} so the
     * workflow can abort cleanly.
     *
     * @param owner          the window the dialog should float over (typically the QuPath stage); may be null
     * @param seedFlip       initial flip values (from a prior preset for this pair, or null)
     * @param onToggle       callback invoked on each checkbox change with the new (flipX, flipY).
     *                       Implementations switch the active image entry to match and return a
     *                       future that completes when the new image is showing; until then the
     *                       dialog shows a wait message and locks its boxes and OK.
     * @return future yielding {@link MacroFlip} or {@code null} on cancel
     */
    public static CompletableFuture<MacroFlip> show(
            Window owner, MacroFlip seedFlip, BiFunction<Boolean, Boolean, CompletableFuture<?>> onToggle) {
        CompletableFuture<MacroFlip> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Stage stage = new Stage();
                QpscWindows.track(stage);
                stage.initModality(Modality.NONE);
                if (owner != null) {
                    stage.initOwner(owner);
                }
                stage.setTitle("Image orientation");
                stage.setAlwaysOnTop(true);

                Label header = new Label("Compare the current image to the Live View");
                header.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");

                Label instructions = new Label("Pan and zoom in QuPath to find a recognizable feature, then move the "
                        + "microscope stage so the same feature is visible in the Live View. "
                        + "If QuPath shows the feature mirrored relative to the Live View, "
                        + "tick the matching axis. The image will switch to its flipped variant "
                        + "immediately so you can verify the orientation. Click OK when the "
                        + "QuPath view matches the Live View. The boxes are relative to the "
                        + "original scan, and the image shown always matches them.");
                instructions.setWrapText(true);
                instructions.setMaxWidth(440);

                CheckBox flipXBox = new CheckBox("Flipped horizontally (X)");
                CheckBox flipYBox = new CheckBox("Flipped vertically (Y)");
                if (seedFlip != null) {
                    flipXBox.setSelected(seedFlip.flipX());
                    flipYBox.setSelected(seedFlip.flipY());
                }

                Button okButton = new Button("OK");

                // Switching images loads a whole scan, which can take several seconds with no
                // other sign of life (2026-10-19): say so, and lock the controls so a second
                // click cannot start another switch while the first is still loading.
                ProgressIndicator spinner = new ProgressIndicator();
                spinner.setPrefSize(18, 18);
                Label waitLabel = new Label();
                waitLabel.setWrapText(true);
                waitLabel.setMaxWidth(400);
                HBox waitRow = new HBox(8, spinner, waitLabel);
                waitRow.setAlignment(Pos.CENTER_LEFT);
                waitRow.setVisible(false);
                waitRow.setManaged(false);

                Runnable fireToggle = () -> {
                    boolean flipX = flipXBox.isSelected();
                    boolean flipY = flipYBox.isSelected();
                    waitLabel.setText(
                            (flipX || flipY ? "Switching to the flipped image" : "Switching to the original scan")
                                    + ", please wait. A large scan can take several seconds to load.");
                    spinner.setVisible(true);
                    waitRow.setVisible(true);
                    waitRow.setManaged(true);
                    flipXBox.setDisable(true);
                    flipYBox.setDisable(true);
                    okButton.setDisable(true);
                    stage.sizeToScene();
                    CompletableFuture<?> switching;
                    try {
                        switching = onToggle.apply(flipX, flipY);
                    } catch (Exception e) {
                        switching = CompletableFuture.failedFuture(e);
                    }
                    if (switching == null) {
                        switching = CompletableFuture.completedFuture(null);
                    }
                    switching.whenComplete((ok, ex) -> Platform.runLater(() -> {
                        flipXBox.setDisable(false);
                        flipYBox.setDisable(false);
                        okButton.setDisable(false);
                        if (ex != null) {
                            logger.error("Error switching image entry on flip toggle", ex);
                            spinner.setVisible(false);
                            waitLabel.setText("Could not switch the image: " + ex.getMessage());
                        } else {
                            waitRow.setVisible(false);
                            waitRow.setManaged(false);
                        }
                        stage.sizeToScene();
                    }));
                };
                flipXBox.selectedProperty().addListener((obs, was, now) -> fireToggle.run());
                flipYBox.selectedProperty().addListener((obs, was, now) -> fireToggle.run());

                Button cancelButton = new Button("Cancel");
                okButton.setDefaultButton(true);
                cancelButton.setCancelButton(true);
                okButton.setOnAction(e -> {
                    future.complete(new MacroFlip(flipXBox.isSelected(), flipYBox.isSelected()));
                    stage.close();
                });
                cancelButton.setOnAction(e -> {
                    future.complete(null);
                    stage.close();
                });
                stage.setOnCloseRequest(e -> {
                    if (!future.isDone()) {
                        future.complete(null);
                    }
                });

                HBox buttons = new HBox(8, okButton, cancelButton);
                buttons.setAlignment(Pos.CENTER_RIGHT);

                VBox root = new VBox(10, header, instructions, flipXBox, flipYBox, waitRow, buttons);
                root.setPadding(new Insets(14));

                stage.setScene(new Scene(root));
                stage.setResizable(false);
                stage.show();
                // Show the image the boxes describe from the start. The boxes are flips relative
                // to the ORIGINAL scan, seeded from the saved preset, and the image only switched
                // on a toggle -- so with a (Camera View) entry already open and the boxes
                // unticked, the view matched the Live View, OK recorded "no flip", and the
                // workflow then switched to the unflipped original (2026-10-19).
                fireToggle.run();
            } catch (Exception e) {
                logger.error("Macro orientation dialog failed", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}
