package qupath.ext.qpsc.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.BackgroundCollectionWorkflow;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.ui.stagemap.StageInsertRegistry;
import qupath.ext.qpsc.utilities.BackgroundProfiles;
import qupath.ext.qpsc.utilities.BackgroundProfiles.BackgroundProfile;
import qupath.ext.qpsc.utilities.LightPathModel;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.tools.GuiTools;

/**
 * Utilities > Image Quality > Background Profiles...: the list of background profiles under the
 * background base folder, with New, Edit, Set active, Open folder, Delete, and Adopt for
 * background folders from before profiles existed.
 *
 * <p>Modeless and always on top, like the insert calibration window, so it can stay open beside
 * the Stage Map while the operator checks which carrier is fitted. One window at a time.
 */
public final class BackgroundProfilesDialog {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundProfilesDialog.class);

    private static Stage current;

    private final String configPath;
    private String baseFolder;
    private final Runnable onClosed;
    private final ListView<BackgroundProfile> list = new ListView<>();
    private final Label baseLabel = new Label();
    private final Label detail = new Label();
    private final Button adoptButton = new Button("Adopt old folders...");

    private BackgroundProfilesDialog(String configPath, String baseFolder, Runnable onClosed) {
        this.configPath = configPath;
        this.baseFolder = baseFolder;
        this.onClosed = onClosed;
    }

    /**
     * Opens the window (or brings the open one to the front).
     *
     * @param owner the window to own this one; may be null
     * @param baseFolder the background base folder to manage; null reads it from the imageprocessing YAML
     * @param onClosed called when the window closes (callers refresh their combos); may be null
     */
    public static void show(Window owner, String baseFolder, Runnable onClosed) {
        if (current != null && current.isShowing()) {
            current.toFront();
            return;
        }
        String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
        String base = baseFolder;
        if (base == null || base.isBlank()) {
            MicroscopeConfigManager cfg = MicroscopeConfigManager.getInstanceIfAvailable();
            base = cfg == null ? null : cfg.getBackgroundBaseFolder();
        }
        BackgroundProfilesDialog dialog = new BackgroundProfilesDialog(configPath, base, onClosed);
        current = dialog.build(owner);
        current.show();
    }

    private Stage build(Window owner) {
        Stage win = new Stage();
        QpscWindows.track(win);
        win.setTitle("Background Profiles");
        if (owner != null) {
            win.initOwner(owner);
        }
        win.initModality(Modality.NONE);
        win.setAlwaysOnTop(true);

        Label intro = new Label("A background profile is one set of flat-field backgrounds for one sample setup:"
                + " the slide carrier or insert, the slide source, the slide's orientation and the mounting"
                + " medium. Collect backgrounds into the profile for the setup on the stage, and make it active"
                + " before acquiring. Every acquisition records the profile it used.");
        intro.setWrapText(true);
        intro.setMaxWidth(576);
        intro.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        Button chooseBase = new Button("Choose...");
        chooseBase.setTooltip(new Tooltip("The folder profiles live under, background_correction.base_folder in the"
                + " imageprocessing YAML. Profiles are its profiles/ subfolder."));
        chooseBase.setOnAction(e -> chooseBaseFolder(win));
        Label baseCaption = new Label("Background folder:");
        baseCaption.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        chooseBase.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        // The path can be long: it gives way (ellipsis in the middle) before the caption or the button do.
        baseLabel.setMaxWidth(Double.MAX_VALUE);
        baseLabel.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
        HBox.setHgrow(baseLabel, Priority.ALWAYS);
        HBox baseRow = new HBox(8, baseCaption, baseLabel, chooseBase);
        baseRow.setAlignment(Pos.CENTER_LEFT);

        list.setPrefHeight(180);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(BackgroundProfile p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                boolean active = p.id().equals(BackgroundProfiles.activeId());
                setText(p.displayName() + (active ? "   (active)" : ""));
                setStyle(active ? "-fx-font-weight: bold;" : "");
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> showDetail(now));

        detail.setWrapText(true);
        detail.setMaxWidth(576);
        detail.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        detail.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        Button newButton = new Button("New...");
        newButton.setOnAction(e -> edit(win, null));
        Button editButton = new Button("Edit...");
        editButton.setOnAction(e -> edit(win, selected()));
        Button activateButton = new Button("Set active");
        activateButton.setOnAction(e -> {
            BackgroundProfile p = selected();
            if (p != null) {
                BackgroundProfiles.setActiveId(p.id());
                list.refresh();
                showDetail(p);
            }
        });
        Button openButton = new Button("Open folder");
        openButton.setOnAction(e -> {
            BackgroundProfile p = selected();
            if (p != null) {
                GuiTools.browseDirectory(p.folder().toFile());
            }
        });
        Button deleteButton = new Button("Delete...");
        deleteButton.setOnAction(e -> delete(selected()));
        adoptButton.setTooltip(new Tooltip("Background folders directly under the background folder come from"
                + " before profiles existed and are not read. Move them into a profile."));
        adoptButton.setOnAction(e -> adopt(win));
        for (Button b : List.of(editButton, activateButton, openButton, deleteButton)) {
            b.disableProperty()
                    .bind(list.getSelectionModel().selectedItemProperty().isNull());
        }
        HBox buttons = new HBox(8, newButton, editButton, activateButton, openButton, deleteButton, adoptButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Button close = new Button("Close");
        close.setOnAction(e -> win.close());
        HBox closeRow = new HBox(close);
        closeRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, intro, baseRow, list, detail, buttons, closeRow);
        root.setPadding(new Insets(12));
        VBox.setVgrow(list, Priority.ALWAYS);
        win.setScene(new Scene(root, 600, 560));
        win.setOnHidden(e -> {
            current = null;
            if (onClosed != null) {
                onClosed.run();
            }
        });
        refresh();
        return win;
    }

    private BackgroundProfile selected() {
        return list.getSelectionModel().getSelectedItem();
    }

    private void refresh() {
        boolean hasBase = baseFolder != null && !baseFolder.isBlank();
        baseLabel.setText(hasBase ? baseFolder : "(not set: choose one to create profiles)");
        String selectedId = selected() == null ? null : selected().id();
        list.setItems(FXCollections.observableArrayList(hasBase ? BackgroundProfiles.list(baseFolder) : List.of()));
        if (selectedId != null) {
            list.getItems().stream()
                    .filter(p -> p.id().equals(selectedId))
                    .findFirst()
                    .ifPresent(p -> list.getSelectionModel().select(p));
        }
        boolean old =
                hasBase && !BackgroundProfiles.unprofiledFolders(baseFolder).isEmpty();
        adoptButton.setVisible(old);
        adoptButton.setManaged(old);
        showDetail(selected());
    }

    private void showDetail(BackgroundProfile p) {
        if (p == null) {
            detail.setText(
                    list.getItems().isEmpty()
                            ? "No profiles yet. Click New... to describe the setup on the stage, then collect backgrounds into it."
                            : "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(p.id()).append('\n');
        if (p.description() != null) {
            sb.append(p.description()).append('\n');
        }
        sb.append("expects insert: ").append(p.expectedInsert() == null ? "(not declared)" : p.expectedInsert());
        sb.append("   slide placement: ")
                .append(p.expectedSlideInsertion() == null ? "(not declared)" : p.expectedSlideInsertion())
                .append('\n');
        for (Map.Entry<String, String> a : p.attributes().entrySet()) {
            sb.append(a.getKey()).append(": ").append(a.getValue()).append('\n');
        }
        sb.append("created: ")
                .append(p.createdAt() == null ? "?" : p.createdAt())
                .append('\n');
        sb.append("sets: ").append(describeSets(p.folder())).append('\n');
        sb.append(p.folder());
        detail.setText(sb.toString());
    }

    /** "3 (LOCI_DETECTOR_JAI_001/ppm/20x/per_angle, ...)" : the background_settings.yml files under the profile. */
    private static String describeSets(Path folder) {
        List<String> sets = new ArrayList<>();
        try (var stream = Files.walk(folder, 6)) {
            stream.filter(f -> f.getFileName().toString().equals("background_settings.yml"))
                    .forEach(f ->
                            sets.add(folder.relativize(f.getParent()).toString().replace('\\', '/')));
        } catch (IOException | RuntimeException e) {
            // An unreadable subfolder (Windows junctions, permissions) must not take the dialog down.
            logger.debug("Could not list sets under {}: {}", folder, e.toString());
        }
        if (sets.isEmpty()) {
            return "none collected";
        }
        sets.sort(String::compareTo);
        return sets.size() + " (" + String.join(", ", sets) + ")";
    }

    private void chooseBaseFolder(Window owner) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Background folder");
        if (baseFolder != null && new File(baseFolder).isDirectory()) {
            chooser.setInitialDirectory(new File(baseFolder));
        }
        File dir = chooser.showDialog(owner);
        if (dir == null) {
            return;
        }
        baseFolder = dir.getAbsolutePath();
        try {
            BackgroundCollectionWorkflow.setBackgroundBaseFolder(configPath, baseFolder);
        } catch (Exception e) {
            UserErrors.show(
                    logger,
                    "Background folder",
                    "Could not record the folder in the imageprocessing config: " + e.getMessage());
        }
        refresh();
    }

    private void edit(Window owner, BackgroundProfile existing) {
        if (baseFolder == null || baseFolder.isBlank()) {
            UserErrors.show(logger, "Background Profiles", "Choose the background folder first.");
            return;
        }
        Dialog<BackgroundProfile> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "New Background Profile" : "Edit Background Profile");
        dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField idField = new TextField(existing == null ? "" : existing.id());
        idField.setPromptText("letters, digits, - and _ (a folder name)");
        idField.setDisable(existing != null);
        TextField nameField = new TextField(existing == null ? "" : nullToEmpty(existing.name()));
        nameField.setPromptText("e.g. 4-slide carrier, Aperio slides");
        TextArea descriptionArea = new TextArea(existing == null ? "" : nullToEmpty(existing.description()));
        descriptionArea.setPrefRowCount(3);
        descriptionArea.setWrapText(true);

        ComboBox<String> insertBox = new ComboBox<>();
        insertBox.setEditable(true);
        insertBox.getItems().add("");
        try {
            if (StageInsertRegistry.isInitialized()) {
                StageInsertRegistry.getAvailableInserts()
                        .forEach(i -> insertBox.getItems().add(i.getId()));
            }
        } catch (RuntimeException e) {
            logger.debug("Inserts not listed: {}", e.toString());
        }
        insertBox.setValue(existing == null ? "" : nullToEmpty(existing.expectedInsert()));
        insertBox.setTooltip(
                new Tooltip(
                        "The stage insert these backgrounds are collected with (a stage.inserts id)."
                                + " Acquiring with another insert selected in the Stage Map gives a warning. Leave empty to not check."));

        ComboBox<String> placementBox = new ComboBox<>();
        placementBox.getItems().addAll("", LightPathModel.INSERT_A, "B");
        placementBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String s) {
                if (s == null || s.isEmpty()) return "(not checked)";
                return "B".equals(s) ? "B (label right)" : "A (label left, as scanned)";
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        placementBox.setValue(existing == null ? "" : nullToEmpty(existing.expectedSlideInsertion()));
        placementBox.setTooltip(new Tooltip("The slide's placement these backgrounds are collected with. Acquiring an"
                + " image whose placement differs gives a warning."));

        TextArea attributesArea = new TextArea();
        attributesArea.setPrefRowCount(4);
        attributesArea.setPromptText(
                "one per line, key: value\ncarrier: quad_v\nslide_source: Aperio GT450\nmedium: Permount");
        if (existing != null) {
            StringBuilder sb = new StringBuilder();
            existing.attributes()
                    .forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
            attributesArea.setText(sb.toString());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int row = 0;
        grid.add(new Label("Id:"), 0, row);
        grid.add(idField, 1, row++);
        grid.add(new Label("Name:"), 0, row);
        grid.add(nameField, 1, row++);
        grid.add(new Label("Description:"), 0, row);
        grid.add(descriptionArea, 1, row++);
        grid.add(new Label("Expected insert:"), 0, row);
        grid.add(insertBox, 1, row++);
        grid.add(new Label("Slide placement:"), 0, row);
        grid.add(placementBox, 1, row++);
        grid.add(new Label("Attributes:"), 0, row);
        grid.add(attributesArea, 1, row++);
        Label hint = new Label("Insert and slide placement are checked before an acquisition (a warning, never a"
                + " block). Attributes are recorded with every acquisition and never checked.");
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        grid.add(hint, 0, row, 2, 1);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) {
                return null;
            }
            String id = idField.getText().trim();
            String name = nameField.getText().trim();
            String insert = insertBox.getEditor().getText() == null
                    ? ""
                    : insertBox.getEditor().getText().trim();
            if (insert.isEmpty() && insertBox.getValue() != null) {
                insert = insertBox.getValue().trim();
            }
            return new BackgroundProfile(
                    id,
                    name.isEmpty() ? null : name,
                    descriptionArea.getText().isBlank()
                            ? null
                            : descriptionArea.getText().trim(),
                    existing == null ? null : existing.createdAt(),
                    insert.isEmpty() ? null : insert,
                    placementBox.getValue() == null || placementBox.getValue().isEmpty()
                            ? null
                            : placementBox.getValue(),
                    parseAttributes(attributesArea.getText()),
                    existing == null ? Path.of("") : existing.folder());
        });
        DialogOwner.own(dialog);
        Optional<BackgroundProfile> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        BackgroundProfile p = result.get();
        try {
            if (existing == null) {
                if (!BackgroundProfiles.isValidId(p.id())) {
                    UserErrors.show(
                            logger,
                            "Background Profiles",
                            "The id must be a folder name: letters, digits,"
                                    + " '-' and '_' only, starting with a letter or digit (1 to 64 characters).");
                    return;
                }
                BackgroundProfiles.create(
                        baseFolder,
                        p.id(),
                        p.name(),
                        p.description(),
                        p.expectedInsert(),
                        p.expectedSlideInsertion(),
                        p.attributes());
                if (BackgroundProfiles.activeId() == null) {
                    BackgroundProfiles.setActiveId(p.id());
                }
            } else {
                BackgroundProfiles.write(p);
            }
        } catch (IOException | RuntimeException e) {
            UserErrors.show(logger, "Background Profiles", "Could not save the profile: " + e.getMessage());
        }
        refresh();
        list.getItems().stream()
                .filter(x -> x.id().equals(p.id()))
                .findFirst()
                .ifPresent(x -> list.getSelectionModel().select(x));
    }

    private void delete(BackgroundProfile p) {
        if (p == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete Background Profile");
        alert.setHeaderText("Delete '" + p.displayName() + "' and every background in it?");
        alert.setContentText(p.folder() + "\n\nThis cannot be undone. Acquisitions that recorded this profile keep"
                + " their record; the background images are deleted.");
        ButtonType deleteButton = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(deleteButton, ButtonType.CANCEL);
        DialogOwner.own(alert);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != deleteButton) {
            return;
        }
        try {
            BackgroundProfiles.delete(baseFolder, p.id());
        } catch (IOException e) {
            UserErrors.show(logger, "Background Profiles", "Could not delete the profile: " + e.getMessage());
        }
        refresh();
    }

    private void adopt(Window owner) {
        List<Path> old = BackgroundProfiles.unprofiledFolders(baseFolder);
        if (old.isEmpty() || list.getItems().isEmpty()) {
            UserErrors.show(
                    logger,
                    "Background Profiles",
                    old.isEmpty()
                            ? "There are no old background folders to adopt."
                            : "Create a profile to adopt them into first.");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Adopt old background folders");
        dialog.initOwner(owner);
        dialog.getDialogPane()
                .getButtonTypes()
                .addAll(new ButtonType("Move", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        Label text = new Label(
                "These folders sit directly under the background folder, from before profiles"
                        + " existed, and are not read. Move the ticked ones into a profile (the folders are moved, not copied).");
        text.setWrapText(true);
        text.setMaxWidth(480);
        VBox boxes = new VBox(4);
        List<CheckBox> checks = new ArrayList<>();
        for (Path f : old) {
            CheckBox cb = new CheckBox(f.getFileName().toString());
            cb.setSelected(true);
            checks.add(cb);
            boxes.getChildren().add(cb);
        }
        ComboBox<BackgroundProfile> target = new ComboBox<>(FXCollections.observableArrayList(list.getItems()));
        target.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(BackgroundProfile p) {
                return p == null ? "" : p.displayName();
            }

            @Override
            public BackgroundProfile fromString(String s) {
                return null;
            }
        });
        target.setValue(selected() != null ? selected() : list.getItems().get(0));
        HBox targetRow = new HBox(8, new Label("Into profile:"), target);
        targetRow.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(10, text, boxes, targetRow);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);
        DialogOwner.own(dialog);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty()
                || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE
                || target.getValue() == null) {
            return;
        }
        List<Path> chosen = new ArrayList<>();
        for (int i = 0; i < old.size(); i++) {
            if (checks.get(i).isSelected()) {
                chosen.add(old.get(i));
            }
        }
        try {
            List<Path> skipped =
                    BackgroundProfiles.adopt(baseFolder, target.getValue().id(), chosen);
            String msg = (chosen.size() - skipped.size()) + " folder(s) moved into '"
                    + target.getValue().displayName() + "'.";
            if (!skipped.isEmpty()) {
                msg += "\nLeft in place because the profile already has them: "
                        + skipped.stream().map(p -> p.getFileName().toString()).toList();
            }
            Dialogs.showInfoNotification("Background Profiles", msg);
        } catch (IOException e) {
            UserErrors.show(logger, "Background Profiles", "Could not move the folders: " + e.getMessage());
        }
        refresh();
    }

    static Map<String, String> parseAttributes(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
