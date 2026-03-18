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
 * Step 2: Objectives and Detectors configuration.
 * Allows adding hardware from the LOCI resource catalog or defining custom entries.
 */
public class HardwareStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(HardwareStep.class);

    private final WizardData data;
    private final ResourceCatalog catalog;
    private final VBox content;

    private final ObservableList<Map<String, Object>> objectiveItems = FXCollections.observableArrayList();
    private final ObservableList<Map<String, Object>> detectorItems = FXCollections.observableArrayList();

    private final TableView<Map<String, Object>> objectiveTable;
    private final TableView<Map<String, Object>> detectorTable;

    public HardwareStep(WizardData data, ResourceCatalog catalog) {
        this.data = data;
        this.catalog = catalog;

        content = new VBox(15);
        content.setPadding(new Insets(15));

        // --- Objectives section ---
        Label objHeader = new Label("Objectives");
        objHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        objectiveTable = createObjectiveTable();
        objectiveTable.setItems(objectiveItems);
        objectiveTable.setPrefHeight(160);
        VBox.setVgrow(objectiveTable, Priority.SOMETIMES);

        Button objAddCatalog = new Button("Add from Catalog");
        objAddCatalog.setOnAction(e -> addObjectiveFromCatalog());
        objAddCatalog.setDisable(catalog.getObjectives().isEmpty());

        Button objAddCustom = new Button("Add Custom");
        objAddCustom.setOnAction(e -> addCustomObjective());

        Button objRemove = new Button("Remove");
        objRemove.setOnAction(e -> removeSelected(objectiveTable, objectiveItems));

        HBox objButtons = new HBox(8, objAddCatalog, objAddCustom, objRemove);
        objButtons.setAlignment(Pos.CENTER_LEFT);

        // --- Detectors section ---
        Label detHeader = new Label("Detectors");
        detHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        detectorTable = createDetectorTable();
        detectorTable.setItems(detectorItems);
        detectorTable.setPrefHeight(160);
        VBox.setVgrow(detectorTable, Priority.SOMETIMES);

        Button detAddCatalog = new Button("Add from Catalog");
        detAddCatalog.setOnAction(e -> addDetectorFromCatalog());
        detAddCatalog.setDisable(catalog.getDetectors().isEmpty());

        Button detAddCustom = new Button("Add Custom");
        detAddCustom.setOnAction(e -> addCustomDetector());

        Button detRemove = new Button("Remove");
        detRemove.setOnAction(e -> removeSelected(detectorTable, detectorItems));

        HBox detButtons = new HBox(8, detAddCatalog, detAddCustom, detRemove);
        detButtons.setAlignment(Pos.CENTER_LEFT);

        content.getChildren()
                .addAll(objHeader, objectiveTable, objButtons, new Separator(), detHeader, detectorTable, detButtons);
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> createObjectiveTable() {
        TableView<Map<String, Object>> table = new TableView<>();
        table.setPlaceholder(new Label("No objectives added yet"));

        TableColumn<Map<String, Object>, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().get("id"))));
        idCol.setPrefWidth(200);

        TableColumn<Map<String, Object>, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getOrDefault("name", ""))));
        nameCol.setPrefWidth(150);

        TableColumn<Map<String, Object>, String> magCol = new TableColumn<>("Magnification");
        magCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getOrDefault("magnification", ""))));
        magCol.setPrefWidth(100);

        TableColumn<Map<String, Object>, String> naCol = new TableColumn<>("NA");
        naCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getOrDefault("na", ""))));
        naCol.setPrefWidth(80);

        table.getColumns().addAll(idCol, nameCol, magCol, naCol);
        return table;
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> createDetectorTable() {
        TableView<Map<String, Object>> table = new TableView<>();
        table.setPlaceholder(new Label("No detectors added yet"));

        TableColumn<Map<String, Object>, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().get("id"))));
        idCol.setPrefWidth(200);

        TableColumn<Map<String, Object>, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getOrDefault("name", ""))));
        nameCol.setPrefWidth(150);

        TableColumn<Map<String, Object>, String> wCol = new TableColumn<>("Width (px)");
        wCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getOrDefault("width_px", ""))));
        wCol.setPrefWidth(100);

        TableColumn<Map<String, Object>, String> hCol = new TableColumn<>("Height (px)");
        hCol.setCellValueFactory(
                cd -> new SimpleStringProperty(String.valueOf(cd.getValue().getOrDefault("height_px", ""))));
        hCol.setPrefWidth(100);

        table.getColumns().addAll(idCol, nameCol, wCol, hCol);
        return table;
    }

    // ---- Add from catalog ----

    private void addObjectiveFromCatalog() {
        Map<String, Map<String, Object>> available = catalog.getObjectives();
        String selected = showCatalogPicker("Select Objective", available);
        if (selected == null) return;

        // Check for duplicate
        if (objectiveItems.stream().anyMatch(m -> selected.equals(m.get("id")))) {
            showAlert("Duplicate", "Objective '" + selected + "' is already added.");
            return;
        }

        Map<String, Object> info = available.get(selected);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", selected);
        entry.put("name", ResourceCatalog.getString(info, "name", selected));
        entry.put("magnification", ResourceCatalog.getDouble(info, "magnification", 1.0));
        entry.put("na", ResourceCatalog.getDouble(info, "na", 0.0));
        entry.put("wd_um", ResourceCatalog.getDouble(info, "wd_um", 0.0));
        objectiveItems.add(entry);
        logger.debug("Added objective from catalog: {}", selected);
    }

    private void addDetectorFromCatalog() {
        Map<String, Map<String, Object>> available = catalog.getDetectors();
        String selected = showCatalogPicker("Select Detector", available);
        if (selected == null) return;

        if (detectorItems.stream().anyMatch(m -> selected.equals(m.get("id")))) {
            showAlert("Duplicate", "Detector '" + selected + "' is already added.");
            return;
        }

        Map<String, Object> info = available.get(selected);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", selected);
        entry.put("name", ResourceCatalog.getString(info, "name", selected));
        entry.put("manufacturer", ResourceCatalog.getString(info, "manufacturer", ""));
        entry.put("width_px", ResourceCatalog.getInt(info, "width_px", 0));
        entry.put("height_px", ResourceCatalog.getInt(info, "height_px", 0));
        detectorItems.add(entry);
        logger.debug("Added detector from catalog: {}", selected);
    }

    /**
     * Show a dialog listing catalog entries and return the selected LOCI ID, or null.
     */
    private String showCatalogPicker(String title, Map<String, Map<String, Object>> entries) {
        ChoiceDialog<String> dialog = new ChoiceDialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText("Select from the LOCI resource catalog:");

        List<String> labels = new ArrayList<>();
        Map<String, String> labelToId = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : entries.entrySet()) {
            String label = ResourceCatalog.formatLabel(e.getKey(), e.getValue());
            labels.add(label);
            labelToId.put(label, e.getKey());
        }
        dialog.getItems().addAll(labels);
        if (!labels.isEmpty()) {
            dialog.setSelectedItem(labels.get(0));
        }

        Optional<String> result = dialog.showAndWait();
        return result.map(labelToId::get).orElse(null);
    }

    // ---- Add custom ----

    private void addCustomObjective() {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("Add Custom Objective");
        dialog.setHeaderText("Enter objective details:");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        TextField idField = new TextField();
        idField.setPromptText("e.g., MY_OBJECTIVE_10X");
        TextField nameField = new TextField();
        nameField.setPromptText("e.g., 10x Plan Apo");
        TextField magField = new TextField("10");
        TextField naField = new TextField("0.3");

        grid.add(new Label("ID:"), 0, 0);
        grid.add(idField, 1, 0);
        grid.add(new Label("Name:"), 0, 1);
        grid.add(nameField, 1, 1);
        grid.add(new Label("Magnification:"), 0, 2);
        grid.add(magField, 1, 2);
        grid.add(new Label("NA:"), 0, 3);
        grid.add(naField, 1, 3);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                String id = idField.getText().trim();
                if (id.isEmpty()) return null;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("name", nameField.getText().trim());
                try {
                    entry.put(
                            "magnification",
                            Double.parseDouble(magField.getText().trim()));
                } catch (NumberFormatException e) {
                    entry.put("magnification", 1.0);
                }
                try {
                    entry.put("na", Double.parseDouble(naField.getText().trim()));
                } catch (NumberFormatException e) {
                    entry.put("na", 0.0);
                }
                entry.put("wd_um", 0.0);
                return entry;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(entry -> {
            String id = (String) entry.get("id");
            if (objectiveItems.stream().anyMatch(m -> id.equals(m.get("id")))) {
                showAlert("Duplicate", "Objective '" + id + "' is already added.");
                return;
            }
            objectiveItems.add(entry);
            logger.debug("Added custom objective: {}", id);
        });
    }

    private void addCustomDetector() {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("Add Custom Detector");
        dialog.setHeaderText("Enter detector details:");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        TextField idField = new TextField();
        idField.setPromptText("e.g., MY_CAMERA_01");
        TextField nameField = new TextField();
        nameField.setPromptText("e.g., Hamamatsu ORCA");
        TextField widthField = new TextField("2048");
        TextField heightField = new TextField("2048");

        grid.add(new Label("ID:"), 0, 0);
        grid.add(idField, 1, 0);
        grid.add(new Label("Name:"), 0, 1);
        grid.add(nameField, 1, 1);
        grid.add(new Label("Width (px):"), 0, 2);
        grid.add(widthField, 1, 2);
        grid.add(new Label("Height (px):"), 0, 3);
        grid.add(heightField, 1, 3);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                String id = idField.getText().trim();
                if (id.isEmpty()) return null;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("name", nameField.getText().trim());
                entry.put("manufacturer", "");
                try {
                    entry.put("width_px", Integer.parseInt(widthField.getText().trim()));
                } catch (NumberFormatException e) {
                    entry.put("width_px", 0);
                }
                try {
                    entry.put(
                            "height_px", Integer.parseInt(heightField.getText().trim()));
                } catch (NumberFormatException e) {
                    entry.put("height_px", 0);
                }
                return entry;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(entry -> {
            String id = (String) entry.get("id");
            if (detectorItems.stream().anyMatch(m -> id.equals(m.get("id")))) {
                showAlert("Duplicate", "Detector '" + id + "' is already added.");
                return;
            }
            detectorItems.add(entry);
            logger.debug("Added custom detector: {}", id);
        });
    }

    // ---- Remove ----

    private void removeSelected(TableView<Map<String, Object>> table, ObservableList<Map<String, Object>> items) {
        int idx = table.getSelectionModel().getSelectedIndex();
        if (idx >= 0) {
            Map<String, Object> removed = items.remove(idx);
            logger.debug("Removed item: {}", removed.get("id"));
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
        return "Hardware";
    }

    @Override
    public String getDescription() {
        return "Configure objectives and detectors for your microscope.";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validate() {
        if (objectiveItems.isEmpty()) {
            return "At least one objective is required.";
        }
        if (detectorItems.isEmpty()) {
            return "At least one detector is required.";
        }
        return null;
    }

    @Override
    public void onEnter() {
        // Sync from WizardData (e.g., when navigating back)
        if (!data.objectives.isEmpty() && objectiveItems.isEmpty()) {
            objectiveItems.addAll(data.objectives);
        }
        if (!data.detectors.isEmpty() && detectorItems.isEmpty()) {
            detectorItems.addAll(data.detectors);
        }
    }

    @Override
    public void onLeave() {
        data.objectives.clear();
        data.objectives.addAll(objectiveItems);

        data.detectors.clear();
        data.detectors.addAll(detectorItems);

        logger.debug("HardwareStep: saved {} objectives, {} detectors", data.objectives.size(), data.detectors.size());
    }
}
