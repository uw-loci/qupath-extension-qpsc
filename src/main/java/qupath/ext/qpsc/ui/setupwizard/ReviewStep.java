package qupath.ext.qpsc.ui.setupwizard;

import java.util.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step 7: Review and Summary.
 * Displays a read-only summary of all configured values before writing config files.
 */
public class ReviewStep implements WizardStep {

    private static final Logger logger = LoggerFactory.getLogger(ReviewStep.class);

    private final WizardData data;
    private final VBox content;
    private final VBox summaryBox;

    public ReviewStep(WizardData data, ResourceCatalog catalog) {
        this.data = data;

        content = new VBox(10);
        content.setPadding(new Insets(15));

        Label header = new Label(
                "Review your configuration before saving. " + "Use the Back button to make changes if needed.");
        header.setWrapText(true);

        summaryBox = new VBox(10);
        summaryBox.setPadding(new Insets(5));

        ScrollPane scroll = new ScrollPane(summaryBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        content.getChildren().addAll(header, scroll);
    }

    /**
     * Rebuild the entire summary from current WizardData.
     */
    @SuppressWarnings("unchecked")
    private void rebuildSummary() {
        summaryBox.getChildren().clear();

        List<String> warnings = new ArrayList<>();

        // ---- Microscope ----
        addSectionHeader("Microscope");
        GridPane microscopeGrid = createGrid();
        addRow(microscopeGrid, 0, "Name:", data.microscopeName);
        addRow(microscopeGrid, 1, "Type:", data.microscopeType);
        summaryBox.getChildren().add(microscopeGrid);

        if (data.microscopeName.isEmpty()) {
            warnings.add("Microscope name is not set.");
        }

        // ---- Config Directory ----
        addSectionHeader("Configuration Directory");
        String dirPath = data.configDirectory != null ? data.configDirectory.toString() : "(not set)";
        summaryBox.getChildren().add(createValueLabel(dirPath));

        if (data.configDirectory == null) {
            warnings.add("Configuration directory is not set.");
        }

        summaryBox.getChildren().add(new Separator());

        // ---- Objectives ----
        addSectionHeader("Objectives (" + data.objectives.size() + ")");
        if (data.objectives.isEmpty()) {
            summaryBox.getChildren().add(createValueLabel("(none)"));
            warnings.add("No objectives configured.");
        } else {
            GridPane objGrid = createGrid();
            int row = 0;
            for (Map<String, Object> obj : data.objectives) {
                String id = String.valueOf(obj.get("id"));
                String name = String.valueOf(obj.getOrDefault("name", ""));
                String mag = String.valueOf(obj.getOrDefault("magnification", ""));
                String na = String.valueOf(obj.getOrDefault("na", ""));
                addRow(objGrid, row, id, name + " | Mag: " + mag + " | NA: " + na);
                row++;
            }
            summaryBox.getChildren().add(objGrid);
        }

        // ---- Detectors ----
        addSectionHeader("Detectors (" + data.detectors.size() + ")");
        if (data.detectors.isEmpty()) {
            summaryBox.getChildren().add(createValueLabel("(none)"));
            warnings.add("No detectors configured.");
        } else {
            GridPane detGrid = createGrid();
            int row = 0;
            for (Map<String, Object> det : data.detectors) {
                String id = String.valueOf(det.get("id"));
                String name = String.valueOf(det.getOrDefault("name", ""));
                String w = String.valueOf(det.getOrDefault("width_px", ""));
                String h = String.valueOf(det.getOrDefault("height_px", ""));
                addRow(detGrid, row, id, name + " | " + w + " x " + h + " px");
                row++;
            }
            summaryBox.getChildren().add(detGrid);
        }

        summaryBox.getChildren().add(new Separator());

        // ---- Pixel Sizes ----
        addSectionHeader("Pixel Sizes (" + data.pixelSizes.size() + " entries)");
        if (data.pixelSizes.isEmpty()) {
            summaryBox.getChildren().add(createValueLabel("(none)"));
            warnings.add("No pixel sizes configured.");
        } else {
            GridPane psGrid = createGrid();
            int row = 0;
            for (Map.Entry<String, Double> entry : data.pixelSizes.entrySet()) {
                addRow(psGrid, row, entry.getKey(), entry.getValue() + " um");
                row++;
            }
            summaryBox.getChildren().add(psGrid);
        }

        summaryBox.getChildren().add(new Separator());

        // ---- Stage ----
        addSectionHeader("Stage");
        GridPane stageGrid = createGrid();
        addRow(stageGrid, 0, "Stage ID:", data.stageId.isEmpty() ? "(not set)" : data.stageId);
        addRow(stageGrid, 1, "X limits:", data.stageLimitXLow + " to " + data.stageLimitXHigh + " um");
        addRow(stageGrid, 2, "Y limits:", data.stageLimitYLow + " to " + data.stageLimitYHigh + " um");
        addRow(stageGrid, 3, "Z limits:", data.stageLimitZLow + " to " + data.stageLimitZHigh + " um");
        summaryBox.getChildren().add(stageGrid);

        if (data.stageId.isEmpty()) {
            warnings.add("Stage ID is not set.");
        }

        summaryBox.getChildren().add(new Separator());

        // ---- Modalities ----
        addSectionHeader("Modalities (" + data.modalities.size() + ")");
        if (data.modalities.isEmpty()) {
            summaryBox.getChildren().add(createValueLabel("(none)"));
            warnings.add("No modalities configured.");
        } else {
            for (Map<String, Object> mod : data.modalities) {
                String modName = String.valueOf(mod.getOrDefault("name", ""));
                String modType = String.valueOf(mod.getOrDefault("type", ""));

                GridPane modGrid = createGrid();
                addRow(modGrid, 0, "Name:", modName);
                addRow(modGrid, 1, "Type:", modType);

                int row = 2;
                if ("polarized".equals(modType)) {
                    Object rotStage = mod.get("rotation_stage");
                    String device = "(not set)";
                    if (rotStage instanceof Map) {
                        Object d = ((Map<String, Object>) rotStage).get("device");
                        if (d != null) device = d.toString();
                    }
                    addRow(modGrid, row++, "Rotation stage:", device);

                    Object angles = mod.get("rotation_angles");
                    if (angles instanceof List) {
                        StringBuilder sb = new StringBuilder();
                        for (Object a : (List<?>) angles) {
                            if (a instanceof Map) {
                                Map<String, Object> angle = (Map<String, Object>) a;
                                if (sb.length() > 0) sb.append(", ");
                                sb.append(angle.get("name")).append("=").append(angle.get("tick"));
                            }
                        }
                        addRow(modGrid, row++, "Rotation angles:", sb.toString());
                    }
                } else if ("brightfield".equals(modType)) {
                    addRow(modGrid, row++, "Lamp:", String.valueOf(mod.getOrDefault("lamp", "(not set)")));
                } else if ("fluorescence".equals(modType)) {
                    addRow(
                            modGrid,
                            row++,
                            "Filter wheel:",
                            String.valueOf(mod.getOrDefault("filter_wheel", "(not set)")));
                } else if ("multiphoton".equals(modType)) {
                    addRow(modGrid, row++, "Laser:", String.valueOf(mod.getOrDefault("laser", "(not set)")));
                }

                summaryBox.getChildren().add(modGrid);
            }
        }

        summaryBox.getChildren().add(new Separator());

        // ---- Server ----
        addSectionHeader("Server Connection");
        GridPane serverGrid = createGrid();
        addRow(serverGrid, 0, "Host:", data.serverHost);
        addRow(serverGrid, 1, "Port:", String.valueOf(data.serverPort));
        summaryBox.getChildren().add(serverGrid);

        summaryBox.getChildren().add(new Separator());

        // ---- Files to be created ----
        addSectionHeader("Files to be Created");
        VBox filesBox = new VBox(4);
        if (data.configDirectory != null && !data.microscopeName.isEmpty()) {
            filesBox.getChildren()
                    .addAll(
                            createValueLabel("  " + data.getMainConfigPath().getFileName()),
                            createValueLabel(
                                    "  " + data.getAutofocusConfigPath().getFileName()),
                            createValueLabel("  " + data.getImagingConfigPath().getFileName()),
                            createValueLabel("  resources/resources_LOCI.yml"));
        } else {
            filesBox.getChildren().add(createValueLabel("(Cannot determine filenames -- directory or name not set)"));
        }
        summaryBox.getChildren().add(filesBox);

        // ---- Warnings ----
        if (!warnings.isEmpty()) {
            summaryBox.getChildren().add(new Separator());
            addSectionHeader("Warnings");
            for (String w : warnings) {
                Label warnLabel = new Label("  - " + w);
                warnLabel.setStyle("-fx-text-fill: orange;");
                warnLabel.setWrapText(true);
                summaryBox.getChildren().add(warnLabel);
            }
        }

        logger.debug("ReviewStep: summary rebuilt with {} warning(s)", warnings.size());
    }

    // ---- Helper methods ----

    private void addSectionHeader(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        label.setPadding(new Insets(5, 0, 2, 0));
        summaryBox.getChildren().add(label);
    }

    private GridPane createGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(4);
        grid.setPadding(new Insets(0, 0, 0, 15));
        return grid;
    }

    private void addRow(GridPane grid, int row, String label, String value) {
        Label keyLabel = new Label(label);
        keyLabel.setStyle("-fx-font-weight: bold;");
        Label valLabel = new Label(value);
        valLabel.setWrapText(true);
        grid.add(keyLabel, 0, row);
        grid.add(valLabel, 1, row);
    }

    private Label createValueLabel(String text) {
        Label label = new Label(text);
        label.setPadding(new Insets(0, 0, 0, 15));
        label.setWrapText(true);
        return label;
    }

    // ---- WizardStep interface ----

    @Override
    public String getTitle() {
        return "Review";
    }

    @Override
    public String getDescription() {
        return "Review your configuration before saving.";
    }

    @Override
    public Node getContent() {
        return content;
    }

    @Override
    public String validate() {
        // Saving happens in the main dialog; review step always passes
        return null;
    }

    @Override
    public void onEnter() {
        rebuildSummary();
    }
}
