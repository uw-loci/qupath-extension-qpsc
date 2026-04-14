package qupath.ext.qpsc.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.controller.MicroscopeController;

/**
 * Scrollable, resizable dialog showing per-tile saturation details after acquisition.
 *
 * <p>Each row shows a saturated tile with angle, filename, per-channel saturation %,
 * and stage XY position. Clicking a row moves the microscope stage to that tile's
 * position (if connected).
 *
 * @author Mike Nelson
 * @since 4.1
 */
public class SaturationSummaryDialog {

    private static final Logger logger = LoggerFactory.getLogger(SaturationSummaryDialog.class);

    /**
     * Show the saturation summary dialog from a saturation_report.json file.
     *
     * @param reportPath Path to the saturation_report.json file
     * @param summaryText Human-readable summary (for the header label)
     */
    public static void show(String reportPath, String summaryText) {
        Platform.runLater(() -> {
            try {
                showImpl(reportPath, summaryText);
            } catch (Exception e) {
                logger.error("Failed to show saturation summary dialog", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void showImpl(String reportPath, String summaryText) throws IOException {
        File reportFile = new File(reportPath);
        if (!reportFile.exists()) {
            logger.warn("Saturation report file not found: {}", reportPath);
            return;
        }

        // Parse JSON
        String json = Files.readString(reportFile.toPath(), StandardCharsets.UTF_8);
        Gson gson = new Gson();
        Map<String, Object> report = gson.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());

        List<Map<String, Object>> tiles = (List<Map<String, Object>>) report.get("saturated_tiles");
        if (tiles == null || tiles.isEmpty()) {
            logger.info("No saturated tiles in report");
            return;
        }

        // Build the dialog
        Stage stage = new Stage();
        stage.setTitle("Saturation Summary");
        stage.initModality(Modality.NONE); // Non-blocking

        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        // Summary header
        Label header = new Label("Saturation detected during acquisition");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label summary = new Label(summaryText != null ? summaryText : "");
        summary.setWrapText(true);
        summary.setMaxWidth(Double.MAX_VALUE);
        summary.setStyle("-fx-font-size: 12px;");

        Label instruction =
                new Label("Click a row to move stage to that tile position (requires microscope connection)");
        instruction.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");

        // Table
        TableView<Map<String, Object>> table = new TableView<>();
        table.setItems(FXCollections.observableArrayList(tiles));

        TableColumn<Map<String, Object>, String> angleCol = new TableColumn<>("Angle");
        angleCol.setCellValueFactory(
                d -> new SimpleStringProperty(String.valueOf(d.getValue().get("angle"))));
        angleCol.setPrefWidth(60);

        TableColumn<Map<String, Object>, String> fileCol = new TableColumn<>("Tile");
        fileCol.setCellValueFactory(
                d -> new SimpleStringProperty(String.valueOf(d.getValue().get("filename"))));
        fileCol.setPrefWidth(120);

        TableColumn<Map<String, Object>, Number> worstCol = new TableColumn<>("Worst %");
        worstCol.setCellValueFactory(d -> {
            Object val = d.getValue().get("worst_pct");
            return new SimpleDoubleProperty(val instanceof Number ? ((Number) val).doubleValue() : 0);
        });
        worstCol.setPrefWidth(70);

        TableColumn<Map<String, Object>, Number> rCol = new TableColumn<>("R %");
        rCol.setCellValueFactory(d -> {
            Object val = d.getValue().get("r_pct");
            return new SimpleDoubleProperty(val instanceof Number ? ((Number) val).doubleValue() : 0);
        });
        rCol.setPrefWidth(55);

        TableColumn<Map<String, Object>, Number> gCol = new TableColumn<>("G %");
        gCol.setCellValueFactory(d -> {
            Object val = d.getValue().get("g_pct");
            return new SimpleDoubleProperty(val instanceof Number ? ((Number) val).doubleValue() : 0);
        });
        gCol.setPrefWidth(55);

        TableColumn<Map<String, Object>, Number> bCol = new TableColumn<>("B %");
        bCol.setCellValueFactory(d -> {
            Object val = d.getValue().get("b_pct");
            return new SimpleDoubleProperty(val instanceof Number ? ((Number) val).doubleValue() : 0);
        });
        bCol.setPrefWidth(55);

        TableColumn<Map<String, Object>, Number> xCol = new TableColumn<>("Stage X");
        xCol.setCellValueFactory(d -> {
            Object val = d.getValue().get("stage_x");
            return new SimpleDoubleProperty(val instanceof Number ? ((Number) val).doubleValue() : 0);
        });
        xCol.setPrefWidth(80);

        TableColumn<Map<String, Object>, Number> yCol = new TableColumn<>("Stage Y");
        yCol.setCellValueFactory(d -> {
            Object val = d.getValue().get("stage_y");
            return new SimpleDoubleProperty(val instanceof Number ? ((Number) val).doubleValue() : 0);
        });
        yCol.setPrefWidth(80);

        table.getColumns().addAll(angleCol, fileCol, worstCol, rCol, gCol, bCol, xCol, yCol);

        // Sort by worst % descending by default
        worstCol.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().add(worstCol);

        // Click-to-navigate
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Map<String, Object> selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    navigateToTile(selected);
                }
            }
        });

        // Status bar
        Label statusLabel = new Label(tiles.size() + " saturated tile entries. Double-click to navigate.");
        statusLabel.setStyle("-fx-font-size: 11px;");

        root.getChildren().addAll(header, summary, instruction, table, statusLabel);
        VBox.setVgrow(table, Priority.ALWAYS);

        Scene scene = new Scene(root, 650, 500);
        stage.setScene(scene);
        stage.setMinWidth(500);
        stage.setMinHeight(300);
        stage.show();

        // Log the full summary for text-based access
        logger.info("Saturation summary dialog opened with {} entries from {}", tiles.size(), reportPath);
        for (Map<String, Object> tile : tiles) {
            logger.info(
                    "  Saturated tile: {} at {} deg, worst: {}%, R: {}%, G: {}%, B: {}%, pos: ({}, {})",
                    tile.get("filename"),
                    tile.get("angle"),
                    tile.get("worst_pct"),
                    tile.get("r_pct"),
                    tile.get("g_pct"),
                    tile.get("b_pct"),
                    tile.get("stage_x"),
                    tile.get("stage_y"));
        }
    }

    private static void navigateToTile(Map<String, Object> tile) {
        Object xObj = tile.get("stage_x");
        Object yObj = tile.get("stage_y");

        if (!(xObj instanceof Number) || !(yObj instanceof Number)) {
            logger.warn("No stage position available for tile {}", tile.get("filename"));
            return;
        }

        double x = ((Number) xObj).doubleValue();
        double y = ((Number) yObj).doubleValue();

        logger.info("Navigating to saturated tile {} at ({}, {})", tile.get("filename"), x, y);

        // Move stage on background thread
        new Thread(
                        () -> {
                            try {
                                MicroscopeController controller = MicroscopeController.getInstance();
                                if (controller != null && controller.isConnected()) {
                                    controller.moveStageXY(x, y);
                                    logger.info("Stage moved to ({}, {})", x, y);
                                } else {
                                    logger.warn("Microscope not connected -- cannot navigate to tile");
                                    Platform.runLater(() -> qupath.fx.dialogs.Dialogs.showWarningNotification(
                                            "Not Connected", "Microscope not connected. Cannot navigate to tile."));
                                }
                            } catch (Exception e) {
                                logger.error("Failed to navigate to tile", e);
                            }
                        },
                        "SaturationNav")
                .start();
    }
}
