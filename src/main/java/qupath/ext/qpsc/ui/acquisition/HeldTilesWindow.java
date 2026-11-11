package qupath.ext.qpsc.ui.acquisition;

import java.io.File;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.StitchingRecoveryWorkflow;
import qupath.ext.qpsc.controller.workflow.TileCleanupHelper;
import qupath.ext.qpsc.controller.workflow.TileProcessingUtilities;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.service.notification.NotificationEvent;
import qupath.ext.qpsc.service.notification.NotificationPriority;
import qupath.ext.qpsc.service.notification.NotificationService;
import qupath.ext.qpsc.ui.DialogOwner;
import qupath.ext.qpsc.ui.QpscWindows;
import qupath.ext.qpsc.ui.ThemeColors;
import qupath.ext.qpsc.ui.UIFunctions;
import qupath.lib.gui.QuPathGUI;

/**
 * Tiles of a region that stopped part way, kept until the operator decides what happens to them.
 *
 * <p>A failed or canceled run used to delete the folders of the regions that had not finished.
 * On 2026-11-10 one region stopped at the stage limit after 741 positions and ten hours, and its
 * 2964 images went with it. Now such a folder is held: nothing deletes it until the operator
 * chooses here, either to stitch it like a normal acquisition (the stitch-recovery dialog,
 * opened on that folder, then the usual tile handling preference) or to delete it.
 *
 * <p>The window never blocks: it is not modal and nothing waits on it, so a multi-slide batch
 * carries on to its next slide while the question stays open. Each held region also sends a
 * notification, since nobody may be at the microscope. Closing the window without choosing
 * leaves the tiles on disk.
 */
public final class HeldTilesWindow {

    private static final Logger logger = LoggerFactory.getLogger(HeldTilesWindow.class);

    private static Stage stage;
    private static VBox rows;

    private HeldTilesWindow() {}

    /**
     * A region's held tiles.
     *
     * @param folder the region's tile folder
     * @param sample the sample (slide) name
     * @param tileImages tile images in it
     * @param reason why the run stopped
     */
    public record HeldRegion(File folder, String sample, int tileImages, String reason) {

        /** The region (annotation) name: the folder's name. */
        public String region() {
            return folder.getName();
        }
    }

    /**
     * Holds a region's tiles: logs, notifies, and adds a row to the window (opening it if
     * needed). Safe from any thread; returns at once.
     */
    public static void hold(HeldRegion held) {
        logger.warn(
                "Holding {} tile image(s) of {} / {} at {} until the operator chooses to stitch or delete them ({})",
                held.tileImages(),
                held.sample(),
                held.region(),
                held.folder(),
                held.reason());
        NotificationService.getInstance()
                .notify(
                        "Tiles held: " + held.region(),
                        "\"" + held.sample() + "\" region " + held.region() + " stopped after " + held.tileImages()
                                + " tile images. They are kept at " + held.folder()
                                + " until you choose in QuPath: stitch them like a normal acquisition, or delete"
                                + " them.",
                        NotificationPriority.HIGH,
                        NotificationEvent.ACQUISITION_ERROR);
        Platform.runLater(() -> addRow(held));
    }

    private static void addRow(HeldRegion held) {
        ensureStage();
        Label title = new Label(held.sample() + " / " + held.region() + ": " + held.tileImages() + " tile images kept");
        title.setStyle("-fx-font-weight: bold;");
        Label detail = new Label("Stopped because: " + held.reason() + "\nFolder: " + held.folder());
        detail.setWrapText(true);
        detail.setMaxWidth(560);
        Label status = new Label("Waiting for your choice. Nothing is deleted until then.");
        status.setStyle("-fx-text-fill: " + ThemeColors.WARNING + ";");

        Button stitch = new Button("Stitch as a normal acquisition...");
        stitch.setTooltip(new javafx.scene.control.Tooltip(
                "Open Re-stitch Tiles on this folder. When every angle is stitched and imported, the tiles are"
                        + " then handled by your tile-handling preference, as after any acquisition."));
        Button delete = new Button("Delete tiles...");
        HBox buttons = new HBox(8, stitch, delete);
        VBox row = new VBox(4, title, detail, status, buttons, new Separator());
        row.setPadding(new Insets(4, 0, 4, 0));

        stitch.setOnAction(e -> {
            stitch.setDisable(true);
            delete.setDisable(true);
            status.setText("Re-stitch dialog open...");
            StitchingRecoveryWorkflow.run(
                    held.folder(),
                    allOk -> Platform.runLater(() -> {
                        if (allOk) {
                            String handling = QPPreferenceDialog.getTileHandlingMethodProperty();
                            status.setText(
                                    "Stitched and imported. Tiles handled by your preference (" + handling + ").");
                            status.setStyle("-fx-text-fill: " + ThemeColors.SUCCESS + ";");
                            Thread t = new Thread(
                                    () -> TileCleanupHelper.performCleanup(
                                            held.folder().getPath()),
                                    "HeldTiles-cleanup");
                            t.setDaemon(true);
                            t.start();
                        } else {
                            status.setText("Not stitched (canceled, or an angle failed). The tiles are still kept.");
                            stitch.setDisable(false);
                            delete.setDisable(false);
                        }
                    }));
        });

        delete.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Delete held tiles");
            confirm.setHeaderText("Delete " + held.tileImages() + " tile images?");
            UIFunctions.setWrappedContent(
                    confirm, "Region " + held.region() + " in " + held.folder() + ". This cannot be undone.", 480);
            DialogOwner.own(confirm);
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.OK) {
                return;
            }
            stitch.setDisable(true);
            delete.setDisable(true);
            status.setText("Deleting...");
            logger.info("Operator chose to delete the held tiles of {}", held.folder());
            Thread t = new Thread(
                    () -> {
                        TileProcessingUtilities.deleteTilesAndFolder(
                                held.folder().getPath());
                        Platform.runLater(() -> status.setText("Deleted."));
                    },
                    "HeldTiles-delete");
            t.setDaemon(true);
            t.start();
        });

        rows.getChildren().add(row);
        stage.show();
        stage.toFront();
    }

    private static void ensureStage() {
        if (stage != null) {
            return;
        }
        rows = new VBox(6);
        rows.setPadding(new Insets(10));
        Label intro = new Label("These regions stopped part way. Their tiles are kept until you choose."
                + " Closing this window keeps them on disk.");
        intro.setWrapText(true);
        intro.setMaxWidth(560);
        VBox content = new VBox(8, intro, new Separator(), rows);
        content.setPadding(new Insets(10));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        stage = new Stage();
        QpscWindows.track(stage);
        stage.setTitle("Held tiles");
        stage.initModality(Modality.NONE);
        QuPathGUI gui = QuPathGUI.getInstance();
        if (gui != null && gui.getStage() != null) {
            stage.initOwner(gui.getStage());
        }
        stage.setScene(new Scene(scroll, 620, 360));
        stage.setOnHidden(e -> {
            logger.info("Held tiles window closed; any undecided tiles stay on disk");
            stage = null;
            rows = null;
        });
    }
}
