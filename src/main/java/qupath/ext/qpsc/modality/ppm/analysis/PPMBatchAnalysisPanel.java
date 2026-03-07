package qupath.ext.qpsc.modality.ppm.analysis;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * JavaFX panel for configuring batch PPM analysis.
 *
 * <p>Shows a checklist of discovered PPM analysis sets, analysis type selection,
 * and perpendicularity parameters. The user selects which sets to analyze and
 * clicks "Run".</p>
 *
 * @author Mike Nelson
 * @since 1.0
 */
public class PPMBatchAnalysisPanel extends VBox {

    /**
     * Represents a discovered PPM analysis set in the checklist.
     */
    public static class AnalysisSetItem {
        public final String displayName;
        public final String imageName;
        public final int imageCollection;
        public final String sampleName;
        public final String annotationName;
        public final String calibrationPath;
        public final boolean hasBiref;
        public final int annotationCount;
        public boolean selected;

        public AnalysisSetItem(String displayName, String imageName,
                int imageCollection, String sampleName, String annotationName,
                String calibrationPath, boolean hasBiref, int annotationCount) {
            this.displayName = displayName;
            this.imageName = imageName;
            this.imageCollection = imageCollection;
            this.sampleName = sampleName;
            this.annotationName = annotationName;
            this.calibrationPath = calibrationPath;
            this.hasBiref = hasBiref;
            this.annotationCount = annotationCount;
            this.selected = true;
        }
    }

    private final List<AnalysisSetItem> items;
    private final List<CheckBox> checkBoxes = new ArrayList<>();

    // Analysis type
    private final CheckBox polarityCheck = new CheckBox("Polarity Plot (histogram + circular stats)");
    private final CheckBox perpCheck = new CheckBox("Surface Perpendicularity (simple + PS-TACS)");

    // Perpendicularity parameters
    private final ChoiceBox<String> boundaryClassChoice = new ChoiceBox<>();
    private final Spinner<Double> dilationSpinner;
    private final ChoiceBox<String> zoneModeChoice = new ChoiceBox<>();
    private final Spinner<Double> tacsThresholdSpinner;
    private final CheckBox fillHolesCheck = new CheckBox("Fill holes in boundary");

    // Callbacks
    private Runnable onRun;
    private Runnable onCancel;

    /**
     * Creates the batch analysis panel.
     *
     * @param items discovered analysis sets to display in checklist
     * @param availableClasses annotation classes available for perpendicularity boundary
     */
    public PPMBatchAnalysisPanel(List<AnalysisSetItem> items, List<String> availableClasses) {
        this.items = items;
        setSpacing(8);
        setPadding(new Insets(12));

        // Title
        Label title = new Label("Batch PPM Analysis");
        title.setFont(Font.font("System", FontWeight.BOLD, 14));

        Label subtitle = new Label(String.format(
                "Found %d PPM analysis sets in this project.", items.size()));
        subtitle.setFont(Font.font("System", 11));

        getChildren().addAll(title, subtitle, new Separator());

        // --- Analysis set checklist ---
        Label checklistLabel = new Label("Select analysis sets:");
        checklistLabel.setFont(Font.font("System", FontWeight.BOLD, 11));

        HBox checkAllBox = new HBox(8);
        Button checkAll = new Button("Check All");
        Button checkNone = new Button("Check None");
        checkAll.setOnAction(e -> setAllChecked(true));
        checkNone.setOnAction(e -> setAllChecked(false));
        checkAllBox.getChildren().addAll(checkAll, checkNone);

        VBox checklistContent = new VBox(4);
        for (AnalysisSetItem item : items) {
            CheckBox cb = new CheckBox(item.displayName);
            cb.setSelected(item.selected);
            cb.selectedProperty().addListener((obs, old, val) -> item.selected = val);
            checkBoxes.add(cb);
            checklistContent.getChildren().add(cb);
        }

        ScrollPane checklistScroll = new ScrollPane(checklistContent);
        checklistScroll.setFitToWidth(true);
        checklistScroll.setPrefHeight(Math.min(200, items.size() * 28 + 10));

        getChildren().addAll(checklistLabel, checkAllBox, checklistScroll, new Separator());

        // --- Analysis type selection ---
        Label analysisLabel = new Label("Analysis types:");
        analysisLabel.setFont(Font.font("System", FontWeight.BOLD, 11));

        polarityCheck.setSelected(true);
        perpCheck.setSelected(false);

        getChildren().addAll(analysisLabel, polarityCheck, perpCheck, new Separator());

        // --- Perpendicularity parameters ---
        Label perpLabel = new Label("Perpendicularity parameters:");
        perpLabel.setFont(Font.font("System", FontWeight.BOLD, 11));

        GridPane perpGrid = new GridPane();
        perpGrid.setHgap(10);
        perpGrid.setVgap(6);
        perpGrid.setPadding(new Insets(0, 0, 0, 15));

        int row = 0;

        perpGrid.add(new Label("Boundary class:"), 0, row);
        boundaryClassChoice.getItems().addAll(availableClasses);
        if (!availableClasses.isEmpty()) {
            boundaryClassChoice.setValue(availableClasses.get(0));
        }
        perpGrid.add(boundaryClassChoice, 1, row);
        row++;

        perpGrid.add(new Label("Dilation (um):"), 0, row);
        dilationSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 500, 50, 5));
        dilationSpinner.setEditable(true);
        perpGrid.add(dilationSpinner, 1, row);
        row++;

        perpGrid.add(new Label("Zone mode:"), 0, row);
        zoneModeChoice.getItems().addAll("outside", "inside", "both");
        zoneModeChoice.setValue("outside");
        perpGrid.add(zoneModeChoice, 1, row);
        row++;

        perpGrid.add(new Label("TACS threshold (deg):"), 0, row);
        tacsThresholdSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(5, 85, 30, 5));
        tacsThresholdSpinner.setEditable(true);
        perpGrid.add(tacsThresholdSpinner, 1, row);
        row++;

        fillHolesCheck.setSelected(true);
        perpGrid.add(fillHolesCheck, 0, row, 2, 1);

        // Enable/disable perp params based on checkbox
        perpGrid.disableProperty().bind(perpCheck.selectedProperty().not());

        getChildren().addAll(perpLabel, perpGrid, new Separator());

        // --- Buttons ---
        Button runButton = new Button("Run Batch Analysis");
        Button cancelButton = new Button("Cancel");
        runButton.setDefaultButton(true);

        runButton.setOnAction(e -> {
            if (onRun != null) onRun.run();
        });
        cancelButton.setOnAction(e -> {
            if (onCancel != null) onCancel.run();
        });

        HBox buttons = new HBox(10, runButton, cancelButton);
        buttons.setPadding(new Insets(5, 0, 0, 0));
        getChildren().add(buttons);
    }

    // --- Accessors ---

    public List<AnalysisSetItem> getSelectedItems() {
        List<AnalysisSetItem> selected = new ArrayList<>();
        for (AnalysisSetItem item : items) {
            if (item.selected) selected.add(item);
        }
        return selected;
    }

    public boolean isPolaritySelected() {
        return polarityCheck.isSelected();
    }

    public boolean isPerpendicularitySelected() {
        return perpCheck.isSelected();
    }

    public String getBoundaryClass() {
        return boundaryClassChoice.getValue();
    }

    public double getDilationUm() {
        return dilationSpinner.getValue();
    }

    public String getZoneMode() {
        return zoneModeChoice.getValue();
    }

    public double getTacsThreshold() {
        return tacsThresholdSpinner.getValue();
    }

    public boolean getFillHoles() {
        return fillHolesCheck.isSelected();
    }

    public void setOnRun(Runnable onRun) {
        this.onRun = onRun;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    private void setAllChecked(boolean checked) {
        for (CheckBox cb : checkBoxes) {
            cb.setSelected(checked);
        }
    }
}
