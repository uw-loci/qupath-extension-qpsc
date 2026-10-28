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
import javafx.application.Platform;
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
import qupath.ext.qpsc.utilities.BackgroundProfileDefaults;
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
        sb.append("   slide in holder: ")
                .append(
                        p.expectedSlideInsertion() == null
                                ? "(not declared)"
                                : LightPathModel.placementLabel(p.expectedSlideInsertion(), p.expectedInsert()))
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
            // No folder yet: ask for one here rather than send the operator back to find the button.
            chooseBaseFolder(owner);
            if (baseFolder == null || baseFolder.isBlank()) {
                return;
            }
        }
        boolean creating = existing == null;
        List<String> existingIds =
                list.getItems().stream().map(BackgroundProfile::id).toList();

        // What is known about the setup on the stage, for the suggested values.
        String currentInsert = BackgroundProfiles.currentInsert();
        String imagePlacement = BackgroundProfiles.currentSlideInsertion();
        String placementDefault = imagePlacement != null ? imagePlacement : LightPathModel.slideInsertion();
        BackgroundProfileDefaults.Setup setup = new BackgroundProfileDefaults.Setup(
                currentInsert,
                insertName(currentInsert),
                placementDefault,
                LightPathModel.labelSide(placementDefault, currentInsert),
                imagePlacement != null,
                BackgroundProfiles.currentSourceScanner());

        Dialog<BackgroundProfile> dialog = new Dialog<>();
        dialog.setTitle(creating ? "New Background Profile" : "Edit Background Profile");
        dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

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
        insertBox.setValue(creating ? nullToEmpty(currentInsert) : nullToEmpty(existing.expectedInsert()));
        insertBox.setTooltip(new Tooltip("The stage insert these backgrounds are collected with (a stage.inserts id)."
                + " Acquiring with another insert selected in the Stage Map gives a warning. Leave empty to not"
                + " check. New profiles start with the Stage Map's current insert."));

        ComboBox<String> placementBox = new ComboBox<>();
        placementBox.getItems().addAll("", LightPathModel.INSERT_A, "B");
        placementBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String s) {
                return s == null || s.isEmpty()
                        ? "(not checked)"
                        : LightPathModel.placementLabel(s, editorText(insertBox));
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        placementBox.setValue(creating ? placementDefault : nullToEmpty(existing.expectedSlideInsertion()));
        placementBox.setTooltip(new Tooltip("How the slide sits in the holder when these backgrounds are collected:"
                + " placement A (as scanned) or B (rotated 180 in the plane), as the Stage Map's Slide in"
                + " holder control, which names the two by where the label ends up on your stage. Shown by"
                + " label side once the Stage Map has drawn a slide on this insert.\nAcquiring"
                + " an image placed the other way gives a warning. New profiles start with the open image's"
                + " setting, else the scope's default."));

        TextField nameField = new TextField(
                creating ? nullToEmpty(BackgroundProfileDefaults.suggestName(setup)) : nullToEmpty(existing.name()));
        nameField.setPromptText("e.g. 4-slide carrier, Aperio slides");
        TextField idField = new TextField(
                creating ? BackgroundProfileDefaults.suggestId(nameField.getText(), existingIds) : existing.id());
        idField.setPromptText("letters, digits, - and _ (the profile's folder name)");
        idField.setDisable(!creating);
        idField.setTooltip(new Tooltip("The profile's folder name, recorded with every acquisition. It follows the"
                + " name until you edit it, and cannot be changed later."));
        TextArea descriptionArea = new TextArea(
                creating
                        ? nullToEmpty(BackgroundProfileDefaults.suggestDescription(setup))
                        : nullToEmpty(existing.description()));
        descriptionArea.setPrefRowCount(3);
        descriptionArea.setWrapText(true);

        TextArea attributesArea = new TextArea(BackgroundProfileDefaults.attributesText(
                creating ? BackgroundProfileDefaults.suggestAttributes(setup) : existing.attributes()));
        attributesArea.setPrefRowCount(4);
        attributesArea.setPromptText(
                "one per line, key: value\ncarrier: quad_v\nslide_source: Aperio GT450\nmedium: Permount");

        CheckBox makeActive = new CheckBox("Make this the active background profile");
        makeActive.setSelected(creating || existing.id().equals(BackgroundProfiles.activeId()));
        makeActive.setDisable(!creating && existing.id().equals(BackgroundProfiles.activeId()));

        // A new profile's name and id follow the setup and each other until the operator types in them.
        boolean[] nameTouched = {!creating};
        boolean[] idTouched = {!creating};
        boolean[] descriptionTouched = {!creating};
        boolean[] programmatic = {false};
        Runnable resuggest = () -> {
            if (!creating) {
                return;
            }
            programmatic[0] = true;
            try {
                String insert = editorText(insertBox);
                String placement = placementBox.getValue();
                String placementOrNull = placement == null || placement.isEmpty() ? null : placement;
                BackgroundProfileDefaults.Setup now = new BackgroundProfileDefaults.Setup(
                        insert,
                        insertName(insert),
                        placementOrNull,
                        placementOrNull == null ? null : LightPathModel.labelSide(placementOrNull, insert),
                        placement != null && placement.equals(imagePlacement),
                        setup.sourceScanner());
                if (!nameTouched[0]) {
                    nameField.setText(nullToEmpty(BackgroundProfileDefaults.suggestName(now)));
                }
                if (!idTouched[0]) {
                    idField.setText(BackgroundProfileDefaults.suggestId(nameField.getText(), existingIds));
                }
                if (!descriptionTouched[0]) {
                    descriptionArea.setText(nullToEmpty(BackgroundProfileDefaults.suggestDescription(now)));
                }
            } finally {
                programmatic[0] = false;
            }
        };
        nameField.textProperty().addListener((obs, was, now) -> {
            if (programmatic[0]) return;
            nameTouched[0] = true;
            if (!idTouched[0]) {
                programmatic[0] = true;
                idField.setText(BackgroundProfileDefaults.suggestId(now, existingIds));
                programmatic[0] = false;
            }
        });
        // Clearing the id hands it back to the name.
        idField.textProperty().addListener((obs, was, now) -> {
            if (programmatic[0]) return;
            idTouched[0] = !now.isBlank();
            if (!idTouched[0]) {
                Platform.runLater(() -> {
                    if (!idTouched[0] && idField.getText().isBlank()) {
                        programmatic[0] = true;
                        idField.setText(BackgroundProfileDefaults.suggestId(nameField.getText(), existingIds));
                        idField.selectAll();
                        programmatic[0] = false;
                    }
                });
            }
        });
        descriptionArea.textProperty().addListener((obs, was, now) -> {
            if (!programmatic[0]) descriptionTouched[0] = true;
        });
        insertBox.getEditor().textProperty().addListener((obs, was, now) -> resuggest.run());
        insertBox.valueProperty().addListener((obs, was, now) -> resuggest.run());
        placementBox.valueProperty().addListener((obs, was, now) -> resuggest.run());

        // Problems are shown as the operator types; OK stays disabled while the id cannot be used.
        Label problems = new Label();
        problems.setWrapText(true);
        problems.setMaxWidth(440);
        problems.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> {
            String idProblem = creating
                    ? BackgroundProfileDefaults.idProblem(idField.getText().trim(), existingIds)
                    : null;
            List<String> warnings = new ArrayList<>();
            String insert = editorText(insertBox);
            if (!insert.isEmpty()
                    && insertBox.getItems().size() > 1
                    && !insertBox.getItems().contains(insert)) {
                warnings.add("'" + insert + "' is not an insert in this microscope's configuration; the"
                        + " Stage Map can never match it.");
            }
            warnings.addAll(BackgroundProfileDefaults.attributeWarnings(attributesArea.getText()));
            okButton.setDisable(idProblem != null);
            if (idProblem != null) {
                problems.setText(idProblem + (warnings.isEmpty() ? "" : "\n" + String.join("\n", warnings)));
                problems.setStyle("-fx-text-fill: " + ThemeColors.ERROR + ";");
            } else if (!warnings.isEmpty()) {
                problems.setText(String.join("\n", warnings));
                problems.setStyle("-fx-text-fill: " + ThemeColors.WARNING + ";");
            } else {
                problems.setText("");
            }
        };
        idField.textProperty().addListener((obs, was, now) -> validate.run());
        insertBox.getEditor().textProperty().addListener((obs, was, now) -> validate.run());
        insertBox.valueProperty().addListener((obs, was, now) -> validate.run());
        attributesArea.textProperty().addListener((obs, was, now) -> validate.run());
        validate.run();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int row = 0;
        if (creating) {
            Label from = new Label(describeSetupSource(setup));
            from.setWrapText(true);
            from.setMaxWidth(440);
            from.setStyle("-fx-text-fill: " + ThemeColors.MUTED + "; -fx-font-size: 11px;");
            grid.add(from, 0, row++, 2, 1);
        }
        grid.add(new Label("Expected insert:"), 0, row);
        grid.add(insertBox, 1, row++);
        grid.add(new Label(LightPathModel.PLACEMENT_CAPTION + ":"), 0, row);
        grid.add(placementBox, 1, row++);
        grid.add(new Label("Name:"), 0, row);
        grid.add(nameField, 1, row++);
        grid.add(new Label("Id:"), 0, row);
        grid.add(idField, 1, row++);
        grid.add(new Label("Description:"), 0, row);
        grid.add(descriptionArea, 1, row++);
        grid.add(new Label("Attributes:"), 0, row);
        grid.add(attributesArea, 1, row++);
        grid.add(makeActive, 1, row++);
        Label hint = new Label("Insert and slide in holder are checked before an acquisition (a warning, never a"
                + " block). Attributes are recorded with every acquisition and never checked; a line without a"
                + " value is not saved.");
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        grid.add(hint, 0, row++, 2, 1);
        grid.add(problems, 0, row, 2, 1);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) {
                return null;
            }
            String name = nameField.getText().trim();
            String insert = editorText(insertBox);
            return new BackgroundProfile(
                    idField.getText().trim(),
                    name.isEmpty() ? null : name,
                    descriptionArea.getText().isBlank()
                            ? null
                            : descriptionArea.getText().trim(),
                    creating ? null : existing.createdAt(),
                    insert.isEmpty() ? null : insert,
                    placementBox.getValue() == null || placementBox.getValue().isEmpty()
                            ? null
                            : placementBox.getValue(),
                    parseAttributes(attributesArea.getText()),
                    creating ? Path.of("") : existing.folder());
        });
        DialogOwner.own(dialog);
        Optional<BackgroundProfile> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        BackgroundProfile p = result.get();
        try {
            if (creating) {
                BackgroundProfiles.create(
                        baseFolder,
                        p.id(),
                        p.name(),
                        p.description(),
                        p.expectedInsert(),
                        p.expectedSlideInsertion(),
                        p.attributes());
            } else {
                BackgroundProfiles.write(p);
            }
            if (makeActive.isSelected()) {
                BackgroundProfiles.setActiveId(p.id());
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

    /** One line saying where the suggested values came from, so the operator knows what to check. */
    private static String describeSetupSource(BackgroundProfileDefaults.Setup s) {
        List<String> parts = new ArrayList<>();
        parts.add(
                s.insertId() == null
                        ? "Insert: none selected in the Stage Map."
                        : "Insert: " + s.insertId() + ", from the Stage Map.");
        parts.add("Slide in holder: "
                + (s.placementFromImage() ? "from the open image." : "the scope's default (no image open)."));
        parts.add(
                s.sourceScanner() != null
                        ? "Slide source: " + s.sourceScanner() + ", from the open image."
                        : "Slide source: unknown (no image from a scanner open).");
        return "Suggested values. Check them against what is on the stage.\n" + String.join("\n", parts);
    }

    /** The insert's display name, or null when unknown. */
    private static String insertName(String insertId) {
        if (insertId == null || insertId.isBlank()) {
            return null;
        }
        try {
            if (StageInsertRegistry.isInitialized()) {
                var insert = StageInsertRegistry.getInsert(insertId);
                return insert == null ? null : insert.getName();
            }
        } catch (RuntimeException e) {
            logger.debug("Insert '{}' not resolved: {}", insertId, e.toString());
        }
        return null;
    }

    /** The editable combo's typed text, falling back to its value; never null. */
    private static String editorText(ComboBox<String> box) {
        String typed = box.getEditor().getText();
        if (typed != null && !typed.isBlank()) {
            return typed.trim();
        }
        return box.getValue() == null ? "" : box.getValue().trim();
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
