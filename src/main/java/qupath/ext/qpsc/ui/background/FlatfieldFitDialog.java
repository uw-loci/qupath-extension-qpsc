package qupath.ext.qpsc.ui.background;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.service.microscope.MicroscopeSocketClient;
import qupath.ext.qpsc.ui.QpscWindows;
import qupath.ext.qpsc.ui.UserErrors;

/**
 * Fits a flat-field profile from a folder of tiles, for detectors with no background image, such
 * as a PMT. The microscope server does the fit, so the folder must be visible from that machine.
 */
public final class FlatfieldFitDialog {

    private static final Logger logger = LoggerFactory.getLogger(FlatfieldFitDialog.class);
    private static final String TITLE = "Estimate Flat-Field from Tiles (BaSiC)";
    private static final String PROFILE_SUFFIX = "_flatfield";

    /** Below this a fit can come out nearly flat with no error; see correction/flatfield.py. */
    private static final int MIN_PLANES = 40;

    private final TextField tilesField = new TextField();
    private final TextField profileField = new TextField();
    private final TextField channelField = new TextField();
    private final TextField offsetField = new TextField();
    private final TextField labelField = new TextField();
    private final Label statusLabel = new Label();
    private final FlowPane previews = new FlowPane(8, 8);
    private final Button fitButton = new Button("Fit");

    private FlatfieldFitDialog() {}

    /** Opens the dialog. */
    public static void show(Window owner) {
        new FlatfieldFitDialog().open(owner);
    }

    private void open(Window owner) {
        Stage stage = new Stage();
        QpscWindows.track(stage);
        stage.setTitle(TITLE);
        if (owner != null) {
            stage.initOwner(owner);
        }

        channelField.setPromptText("every plane");
        channelField.setTooltip(
                new javafx.scene.control.Tooltip("MicroManager channel index, from 0. Fit one per detector."));
        offsetField.setPromptText("read from the empty tiles");
        offsetField.setTooltip(new javafx.scene.control.Tooltip(
                "The detector's level with no light, in raw counts. Enter it when the tiles have no empty fields."));
        labelField.setPromptText("for example: top PMT, 10x, zoom 1");
        tilesField.textProperty().addListener((obs, old, value) -> {
            if (profileField.getText().isBlank() || profileField.getText().equals(defaultProfile(old))) {
                profileField.setText(defaultProfile(value));
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int row = 0;
        addFolderRow(grid, row++, "Tiles folder", tilesField, stage);
        grid.addRow(row++, new Label("Channel"), channelField);
        grid.addRow(row++, new Label("Measured offset"), offsetField);
        grid.addRow(row++, new Label("Description"), labelField);
        addFolderRow(grid, row, "Save profile to", profileField, stage);
        GridPane.setHgrow(tilesField, Priority.ALWAYS);

        Label advice = new Label("Use raw tiles or averaged frames, never max projections, and at least "
                + MIN_PLANES + " tiles with tissue in them. A profile is good for one detector, objective, zoom "
                + "and channel. Choose a region's folder to fit each of its channels.");
        advice.setWrapText(true);
        advice.setMinHeight(Region.USE_PREF_SIZE);
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        fitButton.setDefaultButton(true);
        fitButton.setOnAction(e -> fit());

        VBox root = new VBox(10, advice, grid, new HBox(8, fitButton), statusLabel, previews);
        root.setPadding(new Insets(12));
        root.setPrefWidth(620);
        stage.setScene(new Scene(root));
        stage.show();
    }

    private void addFolderRow(GridPane grid, int row, String label, TextField field, Stage stage) {
        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(label);
            File current = new File(field.getText().trim());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File chosen = chooser.showDialog(stage);
            if (chosen != null) {
                field.setText(chosen.getAbsolutePath());
            }
        });
        grid.addRow(row, new Label(label), field, browse);
    }

    private static String defaultProfile(String tilesFolder) {
        String folder = tilesFolder == null ? "" : tilesFolder.trim();
        return folder.isEmpty() ? "" : folder.replaceAll("[\\\\/]+$", "") + PROFILE_SUFFIX;
    }

    private void fit() {
        String tiles = tilesField.getText().trim();
        String profile = profileField.getText().trim();
        Integer channel;
        Double offset;
        try {
            channel = channelField.getText().isBlank()
                    ? null
                    : Integer.valueOf(channelField.getText().trim());
            offset = offsetField.getText().isBlank()
                    ? null
                    : Double.valueOf(offsetField.getText().trim());
        } catch (NumberFormatException e) {
            statusLabel.setText("Channel must be a whole number and the offset a number.");
            return;
        }
        if (tiles.isEmpty() || profile.isEmpty()) {
            statusLabel.setText("Choose a tiles folder and where to save the profile.");
            return;
        }
        MicroscopeController controller = MicroscopeController.getInstance();
        if (controller == null || !controller.isConnected()) {
            statusLabel.setText("Connect to the microscope server first: it runs the fit.");
            return;
        }
        if (controller.isAcquisitionActive()) {
            statusLabel.setText("Wait for the acquisition to finish: a fit reads hundreds of tiles.");
            return;
        }

        String label = labelField.getText().trim();
        fitButton.setDisable(true);
        previews.getChildren().clear();
        statusLabel.setText("Fitting... A few hundred tiles take under a minute.");
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return controller.getSocketClient().fitFlatfield(tiles, profile, channel, offset, label);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    fitButton.setDisable(false);
                    if (error != null) {
                        statusLabel.setText("The fit failed.");
                        UserErrors.show(logger, TITLE, error.getCause() != null ? error.getCause() : error);
                        return;
                    }
                    showResult(result, profile);
                }));
    }

    private void showResult(List<MicroscopeSocketClient.FlatfieldFit> fits, String profile) {
        StringBuilder text = new StringBuilder();
        for (MicroscopeSocketClient.FlatfieldFit fit : fits) {
            text.append(String.format(
                    "%sShading %.3f to %.3f of the mean, from %d of %d planes; offset %.1f counts.%s%n",
                    fit.channel() == null ? "" : fit.channel() + ": ",
                    fit.flatfieldMin(),
                    fit.flatfieldMax(),
                    fit.imagesUsed(),
                    fit.imagesRead(),
                    fit.offset(),
                    fit.imagesUsed() < MIN_PLANES
                            ? " Too few planes to trust: below " + MIN_PLANES + " a fit can come out nearly flat."
                            : ""));
            File folder = fit.channel() == null ? new File(profile) : new File(profile, fit.channel());
            File picture = new File(folder, "flatfield_preview.png");
            if (picture.isFile()) {
                ImageView view = new ImageView(new Image(picture.toURI().toString()));
                view.setFitWidth(fits.size() == 1 ? 256 : 128);
                view.setPreserveRatio(true);
                previews.getChildren().add(new VBox(2, new Label(fit.channel() == null ? "" : fit.channel()), view));
            }
        }
        if (offsetField.getText().isBlank()) {
            text.append("The offset came from the darkest tiles. If none of them is an empty field, enter a measured"
                    + " offset and fit again.\n");
        }
        text.append("Dark blue below is the dimmest part of the field, yellow the brightest. To use it, choose this"
                + " folder as the BaSiC profile for an acquisition, or in Tiles to Pyramid: ");
        text.append(profile);
        statusLabel.setText(text.toString());
        statusLabel.getScene().getWindow().sizeToScene();
    }
}
