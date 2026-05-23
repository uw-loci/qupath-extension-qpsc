package qupath.ext.qpsc.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;
import qupath.ext.qpsc.preferences.QPPreferenceDialog;
import qupath.ext.qpsc.utilities.MicroscopeConfigManager;

/**
 * Calibrate per-acquisition-profile parfocality offsets. The user focuses a
 * sample under one "reference" profile, captures the reference Z, then switches
 * modality via the Live Viewer's Camera tab (which auto-applies any
 * already-calibrated offset), refocuses, and captures the new Z per profile.
 * The signed delta {@code currentZ - referenceZ} becomes that profile's
 * {@code parfocal_offset_um}; saving writes a sidecar
 * {@code parfocality_{microscope}.yml} that overrides inline profile values.
 *
 * <p>This dialog is intentionally non-modal so the user can interact with the
 * Live Viewer to focus and switch modalities while the dialog is open.
 */
public class ParfocalityCalibrationController {

    private static final Logger logger = LoggerFactory.getLogger(ParfocalityCalibrationController.class);

    private static Stage activeStage;

    /** Per-row table model. */
    public static class ProfileRow {
        private final SimpleStringProperty profileKey;
        private final SimpleStringProperty modality;
        private final SimpleStringProperty detector;
        private final SimpleObjectProperty<Double> offsetUm;
        private final SimpleStringProperty status;

        public ProfileRow(String profileKey, String modality, String detector, Double offsetUm) {
            this.profileKey = new SimpleStringProperty(profileKey);
            this.modality = new SimpleStringProperty(modality == null ? "" : modality);
            this.detector = new SimpleStringProperty(detector == null ? "" : detector);
            this.offsetUm = new SimpleObjectProperty<>(offsetUm);
            this.status = new SimpleStringProperty(offsetUm == null ? "Uncalibrated" : "Saved");
        }

        public String getProfileKey() {
            return profileKey.get();
        }

        public String getModality() {
            return modality.get();
        }

        public String getDetector() {
            return detector.get();
        }

        public Double getOffsetUm() {
            return offsetUm.get();
        }

        public void setOffsetUm(Double v) {
            offsetUm.set(v);
            status.set(v == null ? "Uncalibrated" : "Captured (unsaved)");
        }

        public String getStatus() {
            return status.get();
        }

        public SimpleStringProperty profileKeyProperty() {
            return profileKey;
        }

        public SimpleStringProperty modalityProperty() {
            return modality;
        }

        public SimpleStringProperty detectorProperty() {
            return detector;
        }

        public SimpleObjectProperty<Double> offsetUmProperty() {
            return offsetUm;
        }

        public SimpleStringProperty statusProperty() {
            return status;
        }
    }

    public static void show() {
        Platform.runLater(() -> {
            if (activeStage != null && activeStage.isShowing()) {
                activeStage.toFront();
                activeStage.requestFocus();
                return;
            }
            MicroscopeController controller = MicroscopeController.getInstance();
            if (!controller.isConnected()) {
                new Alert(
                                Alert.AlertType.WARNING,
                                "Parfocality calibration needs an active microscope connection -- the dialog reads the current Z from the stage.")
                        .showAndWait();
                return;
            }
            String configPath = QPPreferenceDialog.getMicroscopeConfigFileProperty();
            if (configPath == null || configPath.isBlank()) {
                new Alert(Alert.AlertType.WARNING, "No microscope config selected.").showAndWait();
                return;
            }
            createAndShow(configPath);
        });
    }

    private static void createAndShow(String configPath) {
        MicroscopeConfigManager mgr = MicroscopeConfigManager.getInstance(configPath);
        MicroscopeController controller = MicroscopeController.getInstance();

        Set<String> objectives = mgr.getAvailableObjectives();
        if (objectives.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "No objectives are declared in the active microscope config.")
                    .showAndWait();
            return;
        }

        Stage stage = new Stage();
        activeStage = stage;
        stage.setTitle("Calibrate Parfocality");
        stage.initModality(Modality.NONE);

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.setPrefWidth(820);

        // --- Header ---
        Label header = new Label("Parfocality Calibration");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label hint = new Label("1) Pick objective + reference profile.  2) In the Live Viewer Camera tab,"
                + " switch to the reference profile and focus the slide.  3) Click \"Capture Reference Z\""
                + " below.  4) Switch to each other profile (Z auto-applies any existing offset),"
                + " refocus, click that row's \"Capture\".  5) Click Save.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px;");

        // --- Z readout ---
        Label currentZLabel = new Label("Current stage Z: -- um");
        currentZLabel.setStyle("-fx-font-family: monospace;");
        Button refreshZBtn = new Button("Refresh");
        HBox zRow = new HBox(8, currentZLabel, refreshZBtn);
        zRow.setAlignment(Pos.CENTER_LEFT);

        // --- Objective + reference selectors ---
        GridPane selectGrid = new GridPane();
        selectGrid.setHgap(10);
        selectGrid.setVgap(6);

        ComboBox<String> objectiveCombo = new ComboBox<>();
        objectiveCombo.getItems().addAll(objectives);
        objectiveCombo.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> referenceCombo = new ComboBox<>();
        referenceCombo.setMaxWidth(Double.MAX_VALUE);

        selectGrid.add(new Label("Objective:"), 0, 0);
        selectGrid.add(objectiveCombo, 1, 0);
        selectGrid.add(new Label("Reference profile:"), 0, 1);
        selectGrid.add(referenceCombo, 1, 1);
        GridPane.setHgrow(objectiveCombo, Priority.ALWAYS);
        GridPane.setHgrow(referenceCombo, Priority.ALWAYS);

        // --- Reference Z capture ---
        SimpleDoubleProperty referenceZ = new SimpleDoubleProperty(Double.NaN);
        Label referenceZLabel = new Label("Reference Z: <not captured>");
        referenceZLabel.setStyle("-fx-font-family: monospace;");
        Button captureRefBtn = new Button("Capture Reference Z");
        HBox refRow = new HBox(8, captureRefBtn, referenceZLabel);
        refRow.setAlignment(Pos.CENTER_LEFT);

        // --- Profile table ---
        TableView<ProfileRow> table = new TableView<>();
        table.setPrefHeight(280);
        // Constrained resize policy; the older constant is still public on the
        // JavaFX version we ship with (deprecated in newer FX but no replacement
        // available in 21).
        @SuppressWarnings("deprecation")
        var policy = TableView.CONSTRAINED_RESIZE_POLICY;
        table.setColumnResizePolicy(policy);

        TableColumn<ProfileRow, String> colKey = new TableColumn<>("Profile");
        colKey.setCellValueFactory(new PropertyValueFactory<>("profileKey"));
        TableColumn<ProfileRow, String> colMod = new TableColumn<>("Modality");
        colMod.setCellValueFactory(new PropertyValueFactory<>("modality"));
        TableColumn<ProfileRow, String> colDet = new TableColumn<>("Detector");
        colDet.setCellValueFactory(new PropertyValueFactory<>("detector"));
        TableColumn<ProfileRow, Double> colOffset = new TableColumn<>("Offset (um)");
        colOffset.setCellValueFactory(new PropertyValueFactory<>("offsetUm"));
        colOffset.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setText("--");
                } else {
                    setText(String.format("%+.2f", v));
                }
            }
        });
        TableColumn<ProfileRow, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));

        TableColumn<ProfileRow, Void> colAction = new TableColumn<>("Action");
        colAction.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final Button captureBtn = new Button("Capture");

            {
                captureBtn.setOnAction(e -> {
                    ProfileRow row = getTableView().getItems().get(getIndex());
                    captureRowOffset(row, referenceZ, controller, currentZLabel, table);
                });
            }

            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    captureBtn.setDisable(Double.isNaN(referenceZ.get()));
                    setGraphic(captureBtn);
                }
            }
        });
        // Re-enable the row button when referenceZ becomes available
        referenceZ.addListener((obs, oldV, newV) -> table.refresh());

        @SuppressWarnings("unchecked")
        TableColumn<ProfileRow, ?>[] cols = new TableColumn[] {colKey, colMod, colDet, colOffset, colStatus, colAction};
        table.getColumns().setAll(cols);

        ObservableList<ProfileRow> rows = FXCollections.observableArrayList();
        table.setItems(rows);

        // --- Save / Close ---
        Button saveBtn = new Button("Save");
        Button closeBtn = new Button("Close");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, spacer, saveBtn, closeBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(header, hint, zRow, selectGrid, refRow, table, footer);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setOnHidden(e -> activeStage = null);

        // --- Wiring ---
        Runnable rebuildRows = () -> {
            rows.clear();
            String obj = objectiveCombo.getValue();
            if (obj == null) {
                referenceCombo.getItems().clear();
                return;
            }
            // Profiles for the chosen objective: those whose objective field matches,
            // or whose resolveProfileKey produces them for the objective. We use the
            // explicit objective field first; fall back to walking modalities and
            // resolving for thoroughness.
            List<String> profileKeys = new ArrayList<>();
            Set<String> allKeys = mgr.getAcquisitionProfileKeys();
            for (String key : allKeys) {
                String profObj = mgr.getProfileObjective(key);
                if (profObj != null && profObj.equals(obj)) {
                    profileKeys.add(key);
                }
            }
            if (profileKeys.isEmpty()) {
                // Fallback: iterate modalities and resolveProfileKey(modality, obj).
                Set<String> modalities = new java.util.LinkedHashSet<>();
                for (String key : allKeys) {
                    String m = mgr.getProfileModality(key);
                    if (m != null) modalities.add(m);
                }
                for (String m : modalities) {
                    String resolved = mgr.resolveProfileKey(m, obj);
                    if (resolved != null && !profileKeys.contains(resolved)) {
                        profileKeys.add(resolved);
                    }
                }
            }

            // Populate reference combo + rows.
            referenceCombo.getItems().setAll(profileKeys);
            String currentRef = mgr.getReferenceProfileForObjective(obj);
            if (currentRef != null && profileKeys.contains(currentRef)) {
                referenceCombo.setValue(currentRef);
            } else if (!profileKeys.isEmpty()) {
                referenceCombo.setValue(profileKeys.get(0));
            }
            for (String key : profileKeys) {
                Double off = mgr.getProfileParfocalOffset(key);
                rows.add(new ProfileRow(key, mgr.getProfileModality(key), mgr.getProfileDetector(key), off));
            }
        };

        objectiveCombo.setOnAction(e -> rebuildRows.run());
        if (!objectives.isEmpty()) {
            objectiveCombo.setValue(objectives.iterator().next());
            rebuildRows.run();
        }

        Runnable refreshZ = () -> {
            try {
                double z = controller.getStagePositionZ();
                currentZLabel.setText(String.format("Current stage Z: %.2f um", z));
            } catch (IOException ex) {
                currentZLabel.setText("Current stage Z: <read failed: " + ex.getMessage() + ">");
            }
        };
        refreshZBtn.setOnAction(e -> refreshZ.run());
        refreshZ.run();

        captureRefBtn.setOnAction(e -> {
            try {
                double z = controller.getStagePositionZ();
                referenceZ.set(z);
                referenceZLabel.setText(String.format("Reference Z: %.2f um", z));
                String refProfile = referenceCombo.getValue();
                if (refProfile != null) {
                    // Reference profile's offset is by definition zero -- populate it
                    // so the table reflects the contract.
                    for (ProfileRow row : rows) {
                        if (row.getProfileKey().equals(refProfile)) {
                            row.setOffsetUm(0.0);
                            break;
                        }
                    }
                    table.refresh();
                }
                refreshZ.run();
            } catch (IOException ex) {
                new Alert(Alert.AlertType.ERROR, "Failed to read Z: " + ex.getMessage()).showAndWait();
            }
        });

        saveBtn.setOnAction(e -> {
            // Aggregate offsets from the table; only save rows with a value.
            Map<String, Double> offsets = new LinkedHashMap<>();
            // Merge with existing sidecar values for objectives the user did NOT touch
            // this session (they may have calibrated other objectives previously).
            offsets.putAll(mgr.getCalibratedParfocalOffsets());
            for (ProfileRow row : rows) {
                if (row.getOffsetUm() != null) {
                    offsets.put(row.getProfileKey(), row.getOffsetUm());
                }
            }
            Map<String, String> refs = new LinkedHashMap<>(mgr.getCalibratedReferenceProfilesPerObjective());
            String obj = objectiveCombo.getValue();
            String refProfile = referenceCombo.getValue();
            if (obj != null && refProfile != null) {
                refs.put(obj, refProfile);
            }
            try {
                mgr.saveParfocalityCalibration(offsets, refs);
                for (ProfileRow row : rows) {
                    if (row.getOffsetUm() != null) {
                        row.statusProperty().set("Saved");
                    }
                }
                table.refresh();
                new Alert(Alert.AlertType.INFORMATION, "Parfocality calibration saved.").showAndWait();
            } catch (IOException ex) {
                logger.error("Failed to save parfocality sidecar", ex);
                new Alert(Alert.AlertType.ERROR, "Failed to save: " + ex.getMessage()).showAndWait();
            }
        });

        closeBtn.setOnAction(e -> stage.close());

        stage.show();
    }

    private static void captureRowOffset(
            ProfileRow row,
            SimpleDoubleProperty referenceZ,
            MicroscopeController controller,
            Label currentZLabel,
            TableView<ProfileRow> table) {
        if (Double.isNaN(referenceZ.get())) {
            new Alert(Alert.AlertType.WARNING, "Capture the Reference Z first.").showAndWait();
            return;
        }
        try {
            double z = controller.getStagePositionZ();
            double offset = z - referenceZ.get();
            row.setOffsetUm(offset);
            currentZLabel.setText(String.format("Current stage Z: %.2f um", z));
            table.refresh();
        } catch (IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Failed to read Z: " + ex.getMessage()).showAndWait();
        }
    }
}
