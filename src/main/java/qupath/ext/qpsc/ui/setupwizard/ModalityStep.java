package qupath.ext.qpsc.ui.setupwizard;

import java.util.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 5: Modality Setup.
 * Allows the user to define imaging modalities (brightfield, polarized, fluorescence,
 * multiphoton) with modality-specific configuration such as rotation angles for PPM.
 */
public class ModalityStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(ModalityStep.class);

    private final WizardData data;
    private final ResourceCatalog catalog;
    private final VBox content;

    private final ObservableList<Map<String, Object>> modalityItems = FXCollections.observableArrayList();
    private final TableView<Map<String, Object>> modalityTable;

    public ModalityStep(WizardData data, ResourceCatalog catalog) {
        this.data = data;
        this.catalog = catalog;

        content = new VBox(12);
        content.setPadding(new Insets(15));

        Label instructions = new Label("Define the imaging modalities available on your microscope. "
                + "Each modality type has specific configuration options.");
        instructions.setWrapText(true);

        // Modality table
        modalityTable = createModalityTable();
        modalityTable.setItems(modalityItems);
        modalityTable.setPrefHeight(200);
        VBox.setVgrow(modalityTable, Priority.ALWAYS);

        // Buttons
        Button addBtn = new Button("Add Modality");
        addBtn.setOnAction(e -> showAddModalityDialog());

        Button editBtn = new Button("Edit");
        editBtn.setOnAction(e -> editSelectedModality());

        Button removeBtn = new Button("Remove");
        removeBtn.setOnAction(e -> {
            int idx = modalityTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                Map<String, Object> removed = modalityItems.remove(idx);
                logger.debug("Removed modality: {}", removed.get("name"));
            }
        });

        HBox buttons = new HBox(8, addBtn, editBtn, removeBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(instructions, modalityTable, buttons);
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> createModalityTable() {
        TableView<Map<String, Object>> table = new TableView<>();
        table.setPlaceholder(new Label("No modalities defined yet"));

        TableColumn<Map<String, Object>, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getOrDefault("name", ""))));
        nameCol.setPrefWidth(200);

        TableColumn<Map<String, Object>, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getOrDefault("type", ""))));
        typeCol.setPrefWidth(150);

        TableColumn<Map<String, Object>, String> detailCol = new TableColumn<>("Details");
        detailCol.setCellValueFactory(cd -> {
            Map<String, Object> mod = cd.getValue();
            String type = String.valueOf(mod.getOrDefault("type", ""));
            String detail = "";
            if ("polarized".equals(type)) {
                Object angles = mod.get("rotation_angles");
                if (angles instanceof List) {
                    detail = ((List<?>) angles).size() + " rotation angle(s)";
                }
            } else if ("brightfield".equals(type)) {
                detail = "Lamp: " + mod.getOrDefault("lamp", "(not set)");
            } else if ("fluorescence".equals(type)) {
                detail = "Filter: " + mod.getOrDefault("filter_wheel", "(not set)");
            } else if ("multiphoton".equals(type)) {
                detail = "Laser: " + mod.getOrDefault("laser", "(not set)");
            }
            return new SimpleStringProperty(detail);
        });
        detailCol.setPrefWidth(200);

        table.getColumns().addAll(nameCol, typeCol, detailCol);
        return table;
    }

    // ---- Add/Edit dialogs ----

    private void showAddModalityDialog() {
        showModalityDialog(null);
    }

    private void editSelectedModality() {
        int idx = modalityTable.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;
        Map<String, Object> existing = modalityItems.get(idx);
        Map<String, Object> edited = showModalityDialog(existing);
        if (edited != null) {
            modalityItems.set(idx, edited);
        }
    }

    /**
     * Show a dialog to add or edit a modality.
     *
     * @param existing the existing modality map to edit, or null for a new one
     * @return the new/edited modality map, or null if cancelled
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> showModalityDialog(Map<String, Object> existing) {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "Add Modality" : "Edit Modality");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(500);

        VBox dialogContent = new VBox(10);
        dialogContent.setPadding(new Insets(10));

        // Basic fields
        GridPane basicGrid = new GridPane();
        basicGrid.setHgap(10);
        basicGrid.setVgap(8);

        TextField nameField = new TextField();
        nameField.setPromptText("e.g., ppm, brightfield_20x");

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(ConfigSchema.MODALITY_TYPES);
        typeCombo.setValue("brightfield");

        basicGrid.add(new Label("Name:"), 0, 0);
        basicGrid.add(nameField, 1, 0);
        basicGrid.add(new Label("Type:"), 0, 1);
        basicGrid.add(typeCombo, 1, 1);

        // Type-specific panels
        VBox typeSpecificBox = new VBox(8);
        typeSpecificBox.setPadding(new Insets(10, 0, 0, 0));

        // Polarized fields
        VBox polarizedPanel = createPolarizedPanel(existing);
        // Brightfield fields
        VBox brightfieldPanel = createBrightfieldPanel(existing);
        // Fluorescence fields
        VBox fluorescencePanel = createFluorescencePanel(existing);
        // Multiphoton fields
        VBox multiphotonPanel = createMultiphotonPanel(existing);

        // Switch visibility based on type selection
        Runnable updatePanels = () -> {
            typeSpecificBox.getChildren().clear();
            String type = typeCombo.getValue();
            if ("polarized".equals(type)) {
                typeSpecificBox.getChildren().add(polarizedPanel);
            } else if ("brightfield".equals(type)) {
                typeSpecificBox.getChildren().add(brightfieldPanel);
            } else if ("fluorescence".equals(type)) {
                typeSpecificBox.getChildren().add(fluorescencePanel);
            } else if ("multiphoton".equals(type)) {
                typeSpecificBox.getChildren().add(multiphotonPanel);
            }
        };
        typeCombo.setOnAction(e -> updatePanels.run());

        // Pre-fill if editing
        if (existing != null) {
            nameField.setText(String.valueOf(existing.getOrDefault("name", "")));
            String existType = String.valueOf(existing.getOrDefault("type", "brightfield"));
            typeCombo.setValue(existType);
        }
        updatePanels.run();

        dialogContent.getChildren().addAll(basicGrid, new Separator(), typeSpecificBox);
        dialog.getDialogPane().setContent(dialogContent);

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;

            String name = nameField.getText().trim();
            if (name.isEmpty()) return null;

            String type = typeCombo.getValue();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("type", type);

            if ("polarized".equals(type)) {
                collectPolarizedData(polarizedPanel, result);
            } else if ("brightfield".equals(type)) {
                collectBrightfieldData(brightfieldPanel, result);
            } else if ("fluorescence".equals(type)) {
                collectFluorescenceData(fluorescencePanel, result);
            } else if ("multiphoton".equals(type)) {
                collectMultiphotonData(multiphotonPanel, result);
            }

            return result;
        });

        Optional<Map<String, Object>> result = dialog.showAndWait();
        if (result.isPresent()) {
            Map<String, Object> mod = result.get();
            // For new modalities, check duplicate name
            if (existing == null) {
                String newName = String.valueOf(mod.get("name"));
                boolean dup = modalityItems.stream().anyMatch(m -> newName.equals(m.get("name")));
                if (dup) {
                    showAlert("Duplicate", "A modality named '" + newName + "' already exists.");
                    return null;
                }
                modalityItems.add(mod);
                logger.debug("Added modality: {} ({})", mod.get("name"), mod.get("type"));
            }
            return mod;
        }
        return null;
    }

    // ---- Polarized panel (PPM) ----

    @SuppressWarnings("unchecked")
    private VBox createPolarizedPanel(Map<String, Object> existing) {
        VBox panel = new VBox(8);

        Label header = new Label("Polarized Light Configuration");
        header.setStyle("-fx-font-weight: bold;");

        // Rotation stage
        HBox rotStageRow = new HBox(8);
        rotStageRow.setAlignment(Pos.CENTER_LEFT);
        Label rotLabel = new Label("Rotation stage device:");
        ComboBox<String> rotStageCombo = new ComboBox<>();
        rotStageCombo.setEditable(true);
        rotStageCombo.setPromptText("Select or type device name");
        // Populate from stages catalog
        for (String stageId : catalog.getStages().keySet()) {
            rotStageCombo.getItems().add(stageId);
        }
        rotStageRow.getChildren().addAll(rotLabel, rotStageCombo);

        // Rotation angles table
        Label anglesLabel = new Label("Rotation angles:");
        ObservableList<Map<String, Object>> angleItems = FXCollections.observableArrayList();

        TableView<Map<String, Object>> angleTable = new TableView<>(angleItems);
        angleTable.setPrefHeight(150);

        TableColumn<Map<String, Object>, String> angleNameCol = new TableColumn<>("Name");
        angleNameCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().get("name"))));
        angleNameCol.setPrefWidth(150);

        TableColumn<Map<String, Object>, String> angleTickCol = new TableColumn<>("Tick Value");
        angleTickCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().get("tick"))));
        angleTickCol.setPrefWidth(100);

        angleTable.getColumns().addAll(angleNameCol, angleTickCol);

        // Pre-fill with standard PPM angles or from existing data
        if (existing != null && "polarized".equals(existing.get("type"))) {
            Object rotStage = existing.get("rotation_stage");
            if (rotStage instanceof Map) {
                Object device = ((Map<String, Object>) rotStage).get("device");
                if (device != null) rotStageCombo.setValue(device.toString());
            }
            Object angles = existing.get("rotation_angles");
            if (angles instanceof List) {
                for (Object a : (List<?>) angles) {
                    if (a instanceof Map) {
                        angleItems.add(new LinkedHashMap<>((Map<String, Object>) a));
                    }
                }
            }
        }

        if (angleItems.isEmpty()) {
            // Standard PPM angles
            angleItems.add(createAngle("negative", -7));
            angleItems.add(createAngle("crossed", 0));
            angleItems.add(createAngle("positive", 7));
            angleItems.add(createAngle("uncrossed", 90));
        }

        Button addAngle = new Button("Add Angle");
        addAngle.setOnAction(e -> showAddAngleDialog(angleItems));

        Button removeAngle = new Button("Remove Angle");
        removeAngle.setOnAction(e -> {
            int idx = angleTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0) angleItems.remove(idx);
        });

        HBox angleButtons = new HBox(8, addAngle, removeAngle);
        angleButtons.setAlignment(Pos.CENTER_LEFT);

        // Store references for data collection
        panel.setUserData(new Object[] {rotStageCombo, angleItems});
        panel.getChildren().addAll(header, rotStageRow, anglesLabel, angleTable, angleButtons);
        return panel;
    }

    private Map<String, Object> createAngle(String name, double tick) {
        Map<String, Object> angle = new LinkedHashMap<>();
        angle.put("name", name);
        angle.put("tick", tick);
        return angle;
    }

    private void showAddAngleDialog(ObservableList<Map<String, Object>> angleItems) {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("Add Rotation Angle");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        TextField nameField = new TextField();
        nameField.setPromptText("e.g., crossed");
        TextField tickField = new TextField("0");
        tickField.setPromptText("e.g., 0, 7, -7, 90");

        grid.add(new Label("Angle name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Tick value:"), 0, 1);
        grid.add(tickField, 1, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                String name = nameField.getText().trim();
                if (name.isEmpty()) return null;
                try {
                    double tick = Double.parseDouble(tickField.getText().trim());
                    return createAngle(name, tick);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        });
        dialog.showAndWait().ifPresent(angleItems::add);
    }

    @SuppressWarnings("unchecked")
    private void collectPolarizedData(VBox panel, Map<String, Object> result) {
        Object[] refs = (Object[]) panel.getUserData();
        ComboBox<String> rotStageCombo = (ComboBox<String>) refs[0];
        ObservableList<Map<String, Object>> angleItems = (ObservableList<Map<String, Object>>) refs[1];

        // Rotation stage
        String device = rotStageCombo.getValue();
        if (device == null) device = rotStageCombo.getEditor().getText();
        if (device != null && !device.trim().isEmpty()) {
            Map<String, Object> rotStage = new LinkedHashMap<>();
            rotStage.put("device", device.trim());
            result.put("rotation_stage", rotStage);
        }

        // Rotation angles
        List<Map<String, Object>> angles = new ArrayList<>();
        for (Map<String, Object> a : angleItems) {
            angles.add(new LinkedHashMap<>(a));
        }
        result.put("rotation_angles", angles);
    }

    // ---- Brightfield panel ----

    private VBox createBrightfieldPanel(Map<String, Object> existing) {
        VBox panel = new VBox(8);
        Label header = new Label("Brightfield Configuration");
        header.setStyle("-fx-font-weight: bold;");

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label lampLabel = new Label("Lamp device:");
        TextField lampField = new TextField();
        lampField.setPromptText("e.g., TransmittedLamp");

        if (existing != null && "brightfield".equals(existing.get("type"))) {
            Object lamp = existing.get("lamp");
            if (lamp != null) lampField.setText(lamp.toString());
        }

        row.getChildren().addAll(lampLabel, lampField);
        panel.setUserData(lampField);
        panel.getChildren().addAll(header, row);
        return panel;
    }

    private void collectBrightfieldData(VBox panel, Map<String, Object> result) {
        TextField lampField = (TextField) panel.getUserData();
        String lamp = lampField.getText().trim();
        if (!lamp.isEmpty()) {
            result.put("lamp", lamp);
        }
    }

    // ---- Fluorescence panel ----

    private VBox createFluorescencePanel(Map<String, Object> existing) {
        VBox panel = new VBox(8);
        Label header = new Label("Fluorescence Configuration");
        header.setStyle("-fx-font-weight: bold;");

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label filterLabel = new Label("Filter wheel device:");
        TextField filterField = new TextField();
        filterField.setPromptText("e.g., FilterWheel1 (placeholder - configure later)");

        if (existing != null && "fluorescence".equals(existing.get("type"))) {
            Object fw = existing.get("filter_wheel");
            if (fw != null) filterField.setText(fw.toString());
        }

        row.getChildren().addAll(filterLabel, filterField);
        panel.setUserData(filterField);
        panel.getChildren().addAll(header, row);
        return panel;
    }

    private void collectFluorescenceData(VBox panel, Map<String, Object> result) {
        TextField filterField = (TextField) panel.getUserData();
        String filter = filterField.getText().trim();
        if (!filter.isEmpty()) {
            result.put("filter_wheel", filter);
        }
    }

    // ---- Multiphoton panel ----

    private VBox createMultiphotonPanel(Map<String, Object> existing) {
        VBox panel = new VBox(8);
        Label header = new Label("Multiphoton Configuration");
        header.setStyle("-fx-font-weight: bold;");

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label laserLabel = new Label("Laser device:");
        TextField laserField = new TextField();
        laserField.setPromptText("e.g., MaiTaiLaser (placeholder - configure later)");

        if (existing != null && "multiphoton".equals(existing.get("type"))) {
            Object laser = existing.get("laser");
            if (laser != null) laserField.setText(laser.toString());
        }

        row.getChildren().addAll(laserLabel, laserField);
        panel.setUserData(laserField);
        panel.getChildren().addAll(header, row);
        return panel;
    }

    private void collectMultiphotonData(VBox panel, Map<String, Object> result) {
        TextField laserField = (TextField) panel.getUserData();
        String laser = laserField.getText().trim();
        if (!laser.isEmpty()) {
            result.put("laser", laser);
        }
    }

    // ---- Utility ----

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    // ---- WizardStep interface ----

    @Override
    public String getTitle() {
        return "Modalities";
    }

    @Override
    public String getDescription() {
        return "Define imaging modalities and their device configurations.";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String validate() {
        if (modalityItems.isEmpty()) {
            return "At least one modality is required.";
        }

        for (Map<String, Object> mod : modalityItems) {
            String type = String.valueOf(mod.getOrDefault("type", ""));
            String name = String.valueOf(mod.getOrDefault("name", ""));

            if ("polarized".equals(type)) {
                Object rotStage = mod.get("rotation_stage");
                if (rotStage == null) {
                    return "Polarized modality '" + name + "' requires a rotation stage device.";
                }
                if (rotStage instanceof Map) {
                    Object device = ((Map<String, Object>) rotStage).get("device");
                    if (device == null || device.toString().trim().isEmpty()) {
                        return "Polarized modality '" + name + "' requires a rotation stage device.";
                    }
                }

                Object angles = mod.get("rotation_angles");
                if (!(angles instanceof List) || ((List<?>) angles).isEmpty()) {
                    return "Polarized modality '" + name + "' requires at least one rotation angle.";
                }
            }
        }

        return null;
    }

    @Override
    public void onEnter() {
        if (!data.modalities.isEmpty() && modalityItems.isEmpty()) {
            for (Map<String, Object> mod : data.modalities) {
                modalityItems.add(new LinkedHashMap<>(mod));
            }
        }
    }

    @Override
    public void onLeave() {
        data.modalities.clear();
        for (Map<String, Object> mod : modalityItems) {
            data.modalities.add(new LinkedHashMap<>(mod));
        }
        logger.debug("ModalityStep: saved {} modalities", data.modalities.size());
    }
}
